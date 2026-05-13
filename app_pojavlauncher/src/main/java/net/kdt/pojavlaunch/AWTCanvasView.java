package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.util.LinkedList;

import net.kdt.pojavlaunch.utils.JREUtils;

/**
 * SurfaceView-backed AWT canvas. Was a TextureView for years (Pojav's original
 * implementation), but TextureView routes every frame through an extra GPU
 * texture upload + compositor pass. For our software-rendered AWT pipeline that
 * extra upload is wasted CPU/GPU work. SurfaceView gives us a surface
 * SurfaceFlinger composites directly with no per-frame upload.
 */
public class AWTCanvasView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    public static int AWT_CANVAS_WIDTH = 720;
    public static int AWT_CANVAS_HEIGHT = 600;

    /** When true, suppress the FPS overlay (drawn directly onto the Surface in run()). */
    public static boolean HIDE_FPS_OVERLAY = false;

    /** When true, clear with transparent instead of opaque black per-frame. Used by the
     *  hybrid RuneLite activity so any GL surface underneath shows through where Cacio
     *  doesn't paint pixels. */
    public static boolean TRANSPARENT_BACKGROUND = false;

    /** Set the Cacio managed-screen / bitmap size before this view is constructed.
     *  Must be called before setContentView (i.e. before the JVM is launched too,
     *  since cacio.managed.screensize is propagated as a -D JVM arg from these). */
    public static void setManagedScreenSize(int w, int h) {
        if (w >= 320 && h >= 240) {
            AWT_CANVAS_WIDTH = w;
            AWT_CANVAS_HEIGHT = h;
        }
    }

    private static final int MAX_SIZE = 100;
    private static final double NANOS = 1000000000.0;
    private static final long FRAME_INTERVAL_NS = 16_666_667L; // 60 Hz

    private volatile boolean mIsDestroyed = false;
    private final TextPaint mFpsPaint;
    private Thread mRenderThread;
    private final LinkedList<Long> mTimes = new LinkedList<Long>(){{add(System.nanoTime());}};

    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }

    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);

        mFpsPaint = new TextPaint();
        mFpsPaint.setColor(Color.WHITE);
        mFpsPaint.setTextSize(20);

        SurfaceHolder h = getHolder();
        h.addCallback(this);
        h.setFixedSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
        // Transparent surface format if requested — surface won't be opaque so the
        // GL surface underneath (when present) can show through where we paint with
        // PorterDuff.CLEAR.
        if (TRANSPARENT_BACKGROUND) {
            h.setFormat(PixelFormat.TRANSLUCENT);
            setZOrderMediaOverlay(true);
        }
        post(this::refreshSize);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mIsDestroyed = false;
        if (mRenderThread != null && mRenderThread.isAlive()) return;
        mRenderThread = new Thread(this, "AndroidAWTRenderer");
        mRenderThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // No-op — we drive the surface size via setFixedSize.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mIsDestroyed = true;
        Thread t = mRenderThread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            mRenderThread = null;
        }
    }

    @Override
    public void run() {
        SurfaceHolder holder = getHolder();
        Bitmap rgbArrayBitmap = Bitmap.createBitmap(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        long nextFrameNs = System.nanoTime();
        try {
            while (!mIsDestroyed) {
                Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) break;

                long now = System.nanoTime();
                long sleepNs = nextFrameNs - now;
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    nextFrameNs += FRAME_INTERVAL_NS;
                } else if (sleepNs < -FRAME_INTERVAL_NS) {
                    nextFrameNs = now + FRAME_INTERVAL_NS;
                } else {
                    nextFrameNs += FRAME_INTERVAL_NS;
                }

                Canvas canvas = holder.lockCanvas(null);
                if (canvas == null) continue;
                try {
                    if (TRANSPARENT_BACKGROUND) {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    } else {
                        canvas.drawRGB(0, 0, 0);
                    }
                    int[] rgbArray = JREUtils.renderAWTScreenFrame();
                    boolean drawing = rgbArray != null;
                    if (drawing) {
                        rgbArrayBitmap.setPixels(rgbArray, 0, AWT_CANVAS_WIDTH, 0, 0,
                                AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
                        canvas.drawBitmap(rgbArrayBitmap, 0, 0, paint);
                    }
                    if (!HIDE_FPS_OVERLAY) {
                        canvas.drawText("FPS: " + (Math.round(fps() * 10) / 10) + ", drawing=" + drawing,
                                0, 20, mFpsPaint);
                    }
                } finally {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        rgbArrayBitmap.recycle();
    }

    private double fps() {
        long lastTime = System.nanoTime();
        double difference = (lastTime - mTimes.getFirst()) / NANOS;
        mTimes.addLast(lastTime);
        int size = mTimes.size();
        if (size > MAX_SIZE) mTimes.removeFirst();
        return difference > 0 ? mTimes.size() / difference : 0.0;
    }

    /** Constrain the view to the surface's aspect ratio. The new RuneLite activity
     *  overrides this to match_parent so the surface stretches to fill the screen. */
    private void refreshSize() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (layoutParams == null) return;
        if (getHeight() < getWidth()) {
            layoutParams.width = AWT_CANVAS_WIDTH * getHeight() / AWT_CANVAS_HEIGHT;
        } else {
            layoutParams.height = AWT_CANVAS_HEIGHT * getWidth() / AWT_CANVAS_WIDTH;
        }
        setLayoutParams(layoutParams);
    }
}

package net.kdt.pojavlaunch;

import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.text.*;
import android.util.*;
import android.view.*;

import java.util.*;
import net.kdt.pojavlaunch.utils.*;

public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    public static int AWT_CANVAS_WIDTH = 720;
    public static int AWT_CANVAS_HEIGHT = 600;
    // Cacio is set up as a square canvas (AWT_CANVAS_WIDTH = AWT_CANVAS_HEIGHT)
    // so both portrait and landscape fit without recreating the JVM. Only the
    // top-left visible-WxH rectangle of that canvas is composited to screen;
    // RuneLite's JFrame is resized to exactly that rect by the JVM-side
    // agent on every orientation change.
    public static volatile int AWT_VISIBLE_WIDTH = 720;
    public static volatile int AWT_VISIBLE_HEIGHT = 600;

    /** When true, suppress the FPS overlay (drawn directly onto the Surface in run()). */
    public static boolean HIDE_FPS_OVERLAY = false;

    /** When true, clear with transparent instead of opaque black per-frame. Used by the
     *  hybrid RuneLite activity so the GL surface underneath shows through where Cacio
     *  doesn't paint pixels (e.g. the OSRS 3D game canvas area when GPU plugin is on). */
    public static boolean TRANSPARENT_BACKGROUND = false;

    /** Set the Cacio managed-screen / bitmap size before this view is constructed.
     *  Must be called before setContentView (i.e. before the JVM is launched too,
     *  since cacio.managed.screensize is propagated as a -D JVM arg from these). */
    public static void setManagedScreenSize(int w, int h) {
        if (w >= 320 && h >= 240) {
            AWT_CANVAS_WIDTH = w;
            AWT_CANVAS_HEIGHT = h;
            AWT_VISIBLE_WIDTH = w;
            AWT_VISIBLE_HEIGHT = h;
        }
    }
    private static final int MAX_SIZE = 100;
    private static final double NANOS = 1000000000.0;
    private boolean mIsDestroyed = false;
    private final TextPaint mFpsPaint;

    // Temporary count fps https://stackoverflow.com/a/13729241
    private final LinkedList<Long> mTimes = new LinkedList<Long>(){{add(System.nanoTime());}};
    
    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }
    
    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        
        mFpsPaint = new TextPaint();
        mFpsPaint.setColor(Color.WHITE);
        mFpsPaint.setTextSize(20);


        setSurfaceTextureListener(this);

        post(this::refreshSize);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(
                Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
        mIsDestroyed = false;
        new Thread(this, "AndroidAWTRenderer").start();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        // Return false to KEEP the SurfaceTexture alive across activity
        // backgrounding. With SurfaceView (our previous setup) the underlying
        // Surface is destroyed by the Android compositor on every pause and
        // recreated on resume — that destroy/recreate race is what's been
        // crashing the JVM. TextureView's SurfaceTexture is owned by the
        // app, not the compositor, so it stays valid. The render thread does
        // NOT stop here — it keeps reading frames from the JVM and writing
        // to the (offscreen but valid) texture. When the activity returns,
        // the texture is reattached to the compositor and the user sees the
        // already-current frame instantly. No surface lifecycle event ever
        // reaches the JVM-side AWT path.
        return false;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(
                Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        getSurfaceTexture().setDefaultBufferSize(
                Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
    }

    /** Target frame interval. ~16.67 ms = 60 Hz. The Cacio render → setPixels → drawBitmap
     *  pipeline is all CPU; running it at 300 FPS like the uncapped loop did wastes ~5x the
     *  pixel work since the screen only refreshes at 60 Hz anyway. Capping frees that CPU
     *  budget for the AWT scene paint itself, which is what makes RuneLite feel laggy. */
    private static final long FRAME_INTERVAL_NS = 16_666_667L;

    @Override
    public void run() {
        Canvas canvas;
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap rgbArrayBitmap = Bitmap.createBitmap(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        long nextFrameNs = System.nanoTime();
        try {
            while (!mIsDestroyed && surface.isValid()) {
                long now = System.nanoTime();
                long sleepNs = nextFrameNs - now;
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    nextFrameNs += FRAME_INTERVAL_NS;
                } else if (sleepNs < -FRAME_INTERVAL_NS) {
                    // We're more than a frame behind — resync rather than try to catch up.
                    nextFrameNs = now + FRAME_INTERVAL_NS;
                } else {
                    nextFrameNs += FRAME_INTERVAL_NS;
                }

                // Keep the surface buffer sized to the current visible region.
                // On orientation change AWT_VISIBLE_* changes, and the next
                // frame here picks it up so the buffer matches before we draw.
                getSurfaceTexture().setDefaultBufferSize(
                        Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                        Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT));
                canvas = surface.lockCanvas(null);
                if (TRANSPARENT_BACKGROUND) {
                    canvas.drawColor(android.graphics.Color.TRANSPARENT,
                            android.graphics.PorterDuff.Mode.CLEAR);
                } else {
                    canvas.drawRGB(0, 0, 0);
                }
                int[] rgbArray = JREUtils.renderAWTScreenFrame(/* canvas, mWidth, mHeight */);
                boolean mDrawing = rgbArray != null;
                if (rgbArray != null) {
                    int vw = Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH);
                    int vh = Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT);
                    canvas.save();
                    rgbArrayBitmap.setPixels(rgbArray, 0, AWT_CANVAS_WIDTH, 0, 0, vw, vh);
                    // Draw the visible region to fill the surface buffer 1:1.
                    // Surface buffer size matches the visible region (set in
                    // onSurfaceTextureAvailable / onSurfaceTextureSizeChanged),
                    // and the TextureView scales the whole buffer to the view's
                    // aspect-fit dimensions. Using view coords here would leave
                    // most of the buffer empty.
                    android.graphics.Rect src = new android.graphics.Rect(0, 0, vw, vh);
                    android.graphics.Rect dst = new android.graphics.Rect(0, 0, vw, vh);
                    canvas.drawBitmap(rgbArrayBitmap, src, dst, paint);
                    canvas.restore();
                }
                if (!HIDE_FPS_OVERLAY) {
                    canvas.drawText("FPS: " + (Math.round(fps() * 10) / 10) + ", drawing=" + mDrawing, 0, 20, mFpsPaint);
                }
                surface.unlockCanvasAndPost(canvas);
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        rgbArrayBitmap.recycle();
        surface.release();
    }

    /** Calculates and returns frames per second */
    private double fps() {
        long lastTime = System.nanoTime();
        double difference = (lastTime - mTimes.getFirst()) / NANOS;
        mTimes.addLast(lastTime);
        int size = mTimes.size();
        if (size > MAX_SIZE) {
            mTimes.removeFirst();
        }
        return difference > 0 ? mTimes.size() / difference : 0.0;
    }

    /** Listener for visible-region changes; the activity uses this to forward
     *  the new size to the JVM-side agent so it can resize the JFrame. */
    public interface VisibleRegionListener {
        void onVisibleRegionChanged(int width, int height);
    }
    private static VisibleRegionListener sVisibleRegionListener;
    public static void setVisibleRegionListener(VisibleRegionListener l) {
        sVisibleRegionListener = l;
    }

    /** Recompute the visible region from the parent view's actual pixel
     *  dimensions (which include window insets and any system bars the
     *  Activity ate, so the result *always* matches what's actually on
     *  screen — unlike DisplayMetrics which can lag or include extras),
     *  set the view to exactly fill the parent, and fire the listener so
     *  the JVM-side agent gets a RESIZE IPC with matching dimensions. */
    public void refreshSize(){
        android.view.ViewParent vp = getParent();
        if (!(vp instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) vp;
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) {
            // Parent hasn't been measured yet; try again after this layout pass.
            post(this::refreshSize);
            return;
        }
        int canvasDim = AWT_CANVAS_WIDTH;
        int newVisW, newVisH;
        if (pw >= ph) {
            newVisW = canvasDim;
            newVisH = Math.max(1, canvasDim * ph / pw);
        } else {
            newVisW = Math.max(1, canvasDim * pw / ph);
            newVisH = canvasDim;
        }
        if (newVisW != AWT_VISIBLE_WIDTH || newVisH != AWT_VISIBLE_HEIGHT) {
            AWT_VISIBLE_WIDTH = newVisW;
            AWT_VISIBLE_HEIGHT = newVisH;
            VisibleRegionListener l = sVisibleRegionListener;
            if (l != null) l.onVisibleRegionChanged(newVisW, newVisH);
        }
        // Fill the parent exactly: parent dimensions match the device's
        // current orientation, and the visible-region aspect matches them
        // too, so the buffer scales 1:1 with no bars or stretch.
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.width = pw;
        layoutParams.height = ph;
        setLayoutParams(layoutParams);
    }

}

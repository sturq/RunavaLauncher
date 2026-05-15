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
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
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
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
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
                    canvas.save();
                    rgbArrayBitmap.setPixels(rgbArray, 0, AWT_CANVAS_WIDTH, 0, 0, AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
                    canvas.drawBitmap(rgbArrayBitmap, 0, 0, paint);
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

    /** Make the view fit the proper aspect ratio of the surface */
    private void refreshSize(){
        ViewGroup.LayoutParams layoutParams = getLayoutParams();

        if(getHeight() < getWidth()){
            layoutParams.width = AWT_CANVAS_WIDTH * getHeight() / AWT_CANVAS_HEIGHT;
        }else{
            layoutParams.height = AWT_CANVAS_HEIGHT * getWidth() / AWT_CANVAS_WIDTH;
        }

        setLayoutParams(layoutParams);
    }

}

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

    /** When true, keep whatever alpha AWT painted instead of forcing the frame opaque.
     *  Used by the hybrid RuneLite activity so the GL surface underneath shows through
     *  where Cacio doesn't paint pixels (e.g. the OSRS 3D game canvas area when the
     *  GPU plugin is on). */
    public static boolean TRANSPARENT_BACKGROUND = false;

    /** True when RuneLite's GPU plugin is drawing the game canvas into the GL
     *  layer underneath. Cacio fills that same rectangle with opaque black, and
     *  copying it hides the scene completely — which is what "the GPU renderer
     *  shows nothing" turned out to be. Skipping it also removes most of the
     *  per-frame copy, since the canvas is most of the screen. */
    public static boolean SCENE_DRAWN_ELSEWHERE = false;


    /** Fired when AWT moves or resizes the game canvas. The scene layer has to
     *  be laid out over exactly that rectangle: OpenGL draws at its drawable's
     *  own origin, so unless the drawable is the canvas, the picture ends up
     *  flush against the window's bottom edge instead of where AWT put it. */
    public interface GameCanvasListener {
        void onGameCanvasMoved(int x, int y, int width, int height);
    }
    private static GameCanvasListener sCanvasListener;
    public static void setGameCanvasListener(GameCanvasListener l) { sCanvasListener = l; }

    private int mHoleX, mHoleY, mHoleW, mHoleH;
    private long mHoleReadAtMs;
    private java.io.File mHoleFile;
    private volatile boolean mHoleDirty;
    private android.os.FileObserver mHoleWatcher;

    /** Rotation moves the game canvas, and until the new rectangle is known the
     *  scene is drawn in the old place and the transparent hole is cut in the
     *  old place — which is the black flash and the stretched frame. The JVM
     *  side writes the new rectangle as soon as its layout settles, so wait for
     *  that write rather than for the next poll. */
    @SuppressWarnings("deprecation")
    private void watchGameCanvasFile() {
        if (mHoleWatcher != null || mHoleFile == null) return;
        try {
            mHoleWatcher = new android.os.FileObserver(mHoleFile.getAbsolutePath(),
                    android.os.FileObserver.MODIFY | android.os.FileObserver.CLOSE_WRITE) {
                @Override public void onEvent(int event, String path) { mHoleDirty = true; }
            };
            mHoleWatcher.startWatching();
        } catch (Throwable ignored) {
            // Without it the poll still catches up, just half a second later.
        }
    }

    /** The game canvas's position on the Cacio screen, as the JVM-side agent
     *  last reported it. Polled rather than pushed: it changes only on layout,
     *  and the render thread is the only reader. */
    private void refreshGameCanvasHole() {
        long now = android.os.SystemClock.uptimeMillis();
        // Polled slowly as a backstop; the FileObserver below is what makes a
        // rotation look instant rather than half a second of stale rectangle.
        if (!mHoleDirty && now - mHoleReadAtMs < 500) return;
        mHoleDirty = false;
        mHoleReadAtMs = now;
        try {
            if (mHoleFile == null) {
                java.io.File home = getContext().getExternalFilesDir(null);
                if (home == null) return;
                mHoleFile = new java.io.File(home, ".runelitedroid_canvas");
                watchGameCanvasFile();
            }
            if (!mHoleFile.exists()) return;
            String[] parts;
            try (java.util.Scanner sc = new java.util.Scanner(mHoleFile)) {
                parts = sc.useDelimiter("\\A").next().trim().split("\\s+");
            }
            if (parts.length != 4) return;
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
            int w = Integer.parseInt(parts[2]), h = Integer.parseInt(parts[3]);
            if (x == mHoleX && y == mHoleY && w == mHoleW && h == mHoleH) return;
            mHoleX = x; mHoleY = y; mHoleW = w; mHoleH = h;
            GameCanvasListener l = sCanvasListener;
            if (l != null) l.onGameCanvasMoved(x, y, w, h);
        } catch (Throwable ignored) {
            // A half-written file just means the previous rectangle stands.
        }
    }

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
    private boolean mIsDestroyed = false;
    
    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }
    
    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        
        setSurfaceTextureListener(this);

        post(this::refreshSize);
    }

    /** Rotation used to be picked up from the activity's onConfigurationChanged,
     *  which fires when the configuration changes and not when the new size has
     *  landed: the parent still measured the old orientation, so refreshSize
     *  computed the previous geometry, latched it and never looked again. The
     *  frame then stayed portrait-shaped inside a landscape window and the
     *  TextureView stretched it, which is the distorted band on turning.
     *  This fires once the size is real, so there is nothing to time. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refreshSize();
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

    /** Target frame interval. ~16.67 ms = 60 Hz. Reading a frame out of Cacio and
     *  converting it is all CPU; running it at 300 FPS like the uncapped loop did wastes
     *  ~5x the pixel work since the screen only refreshes at 60 Hz anyway. Capping frees
     *  that CPU budget for the AWT scene paint itself, which is what makes RuneLite feel
     *  laggy. */
    private static final long FRAME_INTERVAL_NS = 16_666_667L;

    @Override
    public void run() {
        Surface surface = new Surface(getSurfaceTexture());
        JREUtils.setAWTSurface(surface);
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

                // The frame is converted and posted entirely in native code.
                // The visible region is passed every frame because it changes on
                // rotation; the native side only resizes the buffer when it
                // actually differs.
                //
                // False means RuneLite has not repainted. Nothing is posted and
                // the TextureView keeps showing the previous frame, which is the
                // correct visual and a large idle battery win.
                if (SCENE_DRAWN_ELSEWHERE) refreshGameCanvasHole();
                JREUtils.blitAWTScreenFrame(
                        AWT_CANVAS_WIDTH,
                        Math.min(AWT_VISIBLE_WIDTH, AWT_CANVAS_WIDTH),
                        Math.min(AWT_VISIBLE_HEIGHT, AWT_CANVAS_HEIGHT),
                        !TRANSPARENT_BACKGROUND,
                        mHoleX, mHoleY, mHoleW, mHoleH);
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        JREUtils.setAWTSurface(null);
        surface.release();
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
        int newVisW = SceneGeometry.visibleWidth(canvasDim, pw, ph);
        int newVisH = SceneGeometry.visibleHeight(canvasDim, pw, ph);
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
        // Only when it actually differs: setLayoutParams requests a layout, and
        // laying out unconditionally from onSizeChanged would loop.
        if (layoutParams.width != pw || layoutParams.height != ph) {
            layoutParams.width = pw;
            layoutParams.height = ph;
            setLayoutParams(layoutParams);
        }
    }

}

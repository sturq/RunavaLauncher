package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.keyboard.AwtCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MathUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fullscreen game-style host for RuneLite (or any Swing/AWT jar). Replaces the
 * Forge-installer-shaped JavaGUILauncherActivity for the RuneLite launch path:
 * immersive layout, single floating menu button, slide-out drawer for controls,
 * direct touch -> AWT mouse mapping with long-press for right-click.
 */
public class RuneLiteGameActivity extends BaseActivity implements View.OnTouchListener {

    public static final String EXTRA_JAVA_ARGS = "javaArgs";

    private AWTCanvasView mCanvas;
    private SurfaceView mGlSurface;
    private ImageView mPointer;
    private TouchCharInput mKeyboardInput;
    private DrawerLayout mDrawer;
    private LoggerView mLogger;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mGlSurfaceReady;
    private final Object mGlSurfaceReadyLock = new Object();

    private boolean mVirtualMouseEnabled;
    private float mLastTouchX, mLastTouchY;

    // Single-finger drag state. Touch-down position decides intent:
    //   - touch-down in left 75% (game world)  → drag rotates camera (arrow keys)
    //   - touch-down in right 25% (UI sidebar) → drag holds left button (drag-and-drop)
    // Tap (no movement) is always a single left click regardless of region.
    private boolean mLongPressFired;
    private boolean mLeftButtonHeld;
    private boolean mCameraDragging;   // 1-finger arrow-key drag is in progress
    private boolean mUiZoneTouch;      // touch-down was in the right-side UI strip
    private float mDownX, mDownY;
    private static final float DRAG_START_PX = 14f;
    private static final float UI_ZONE_FRACTION = 0.75f; // > this fraction of canvas width = UI strip
    private static final long LONG_PRESS_MS = 450L;       // hold this long without moving = right-click
    private static final float LONG_PRESS_SLOP_PX = 24f;  // movement that cancels long-press
    private static final long ARROW_REPEAT_MS = 30L;       // how often to spam arrow press while held

    // Two-finger gesture state for camera rotate (arrow keys) + zoom (scroll wheel).
    private boolean mTwoFingerActive;
    private float mLastMidX, mLastMidY;
    private float mLastPinchDistance;
    /** Per-axis arrow-held state. Indices match ARROW_KEYS: 0=LEFT, 1=RIGHT, 2=UP, 3=DOWN. */
    private final boolean[] mArrowHeld = new boolean[4];
    private static final int[] ARROW_KEYS = {
            AWTInputEvent.VK_LEFT, AWTInputEvent.VK_RIGHT,
            AWTInputEvent.VK_UP,   AWTInputEvent.VK_DOWN
    };
    /** Pixels of inter-frame midpoint movement required to register a camera rotation. */
    private static final float ROTATE_DEADZONE_PX = 2f;
    /** Pixels of pinch-distance change per scroll-wheel tick. Smaller = faster zoom. */
    private static final float PINCH_PIXELS_PER_TICK = 60f;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullscreenFlags();
        // Cacio's virtual screen at 75% of device landscape dimensions. The agent
        // maximizes RuneLite to fill it, then TextureView stretches the (smaller)
        // bitmap to the actual screen — net effect is RuneLite's UI elements appear
        // ~33% larger on the phone, while still using all the screen area.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels);
        int sh = Math.min(dm.widthPixels, dm.heightPixels);
        sw = (sw * 3) / 4;
        sh = (sh * 3) / 4;
        // Cap at 1920 wide for the per-frame pixel-pump cost.
        if (sw > 1920) { sh = (int)((long)sh * 1920 / sw); sw = 1920; }
        AWTCanvasView.setManagedScreenSize(sw, sh);
        AWTCanvasView.HIDE_FPS_OVERLAY = true;
        setContentView(R.layout.activity_runelite_game);

        mCanvas = findViewById(R.id.rl_awt_canvas);
        mGlSurface = findViewById(R.id.rl_gl_surface);
        mPointer = findViewById(R.id.rl_mouse_pointer);
        mKeyboardInput = findViewById(R.id.rl_keyboard_input);
        mDrawer = findViewById(R.id.rl_drawer);
        mLogger = findViewById(R.id.rl_logger);

        // Hybrid rendering: SurfaceView is the GL output target for RuneLite's GPU plugin;
        // AWTCanvasView (TextureView) sits on top with transparency so Cacio-painted Swing
        // UI shows over the GL-rendered game scene.
        mCanvas.setOpaque(false);
        AWTCanvasView.TRANSPARENT_BACKGROUND = true;
        mGlSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                try {
                    Log.i("RuneLiteGame", "GL surface created — installing GLFW bridge");
                    JREUtils.setupBridgeWindow(holder.getSurface());
                } catch (Throwable t) {
                    Log.e("RuneLiteGame", "setupBridgeWindow failed", t);
                }
                synchronized (mGlSurfaceReadyLock) {
                    mGlSurfaceReady = true;
                    mGlSurfaceReadyLock.notifyAll();
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });

        MainActivity.GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        mKeyboardInput.setCharacterSender(new AwtCharSender());
        mDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        wireMenu();
        wireCanvasTouch();

        // Re-apply immersive whenever focus comes back (system bars try to creep back).
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(v -> applyFullscreenFlags());

        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mDrawer.isDrawerOpen(android.view.Gravity.END)) {
                    mDrawer.closeDrawers();
                } else {
                    Tools.dialogForceClose(RuneLiteGameActivity.this);
                }
            }
        });

        // The AWTCanvasView constructor schedules refreshSize() which constrains the canvas
        // to its 720x600 aspect ratio (Pojav default for the installer surface). For the
        // game activity we want the canvas to fill the screen — the underlying bitmap is
        // still 720x600 but TextureView stretches it to whatever dimensions we give the view.
        mCanvas.post(() -> {
            ViewGroup.LayoutParams lp = mCanvas.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mCanvas.setLayoutParams(lp);
        });

        // Wait for both the AWT canvas to be laid out AND the GL surface to be created
        // (so setupBridgeWindow has already run by the time RuneLite's GPU plugin asks
        // GLFW for a context).
        mCanvas.post(() -> new Thread(() -> {
            synchronized (mGlSurfaceReadyLock) {
                while (!mGlSurfaceReady) {
                    try { mGlSurfaceReadyLock.wait(); } catch (InterruptedException ignored) { return; }
                }
            }
            runOnUiThread(() -> launchRuneLite(getIntent().getStringExtra(EXTRA_JAVA_ARGS)));
        }, "RuneLiteGLWait").start());
    }

    private void applyFullscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    protected boolean shouldIgnoreNotch() {
        // BaseActivity.onPostResume reads this — overriding so the canvas extends under
        // the notch regardless of the global PREF_IGNORE_NOTCH setting.
        return true;
    }

    private void wireMenu() {
        Button menu = findViewById(R.id.rl_menu_button);
        menu.setOnClickListener(v -> mDrawer.openDrawer(android.view.Gravity.END));

        findViewById(R.id.rl_btn_keyboard).setOnClickListener(v -> {
            mKeyboardInput.switchKeyboardState();
            mDrawer.closeDrawers();
        });
        findViewById(R.id.rl_btn_copy).setOnClickListener(v -> {
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_C);
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
            mDrawer.closeDrawers();
        });
        findViewById(R.id.rl_btn_paste).setOnClickListener(v -> {
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_V);
            AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
            mDrawer.closeDrawers();
        });
        findViewById(R.id.rl_btn_mouse_mode).setOnClickListener(v -> {
            mVirtualMouseEnabled = !mVirtualMouseEnabled;
            mPointer.setVisibility(mVirtualMouseEnabled ? View.VISIBLE : View.GONE);
            Toast.makeText(this,
                    "Virtual mouse: " + (mVirtualMouseEnabled ? "on" : "off"),
                    Toast.LENGTH_SHORT).show();
            mDrawer.closeDrawers();
        });
        findViewById(R.id.rl_btn_log).setOnClickListener(v -> {
            mLogger.setVisibility(View.VISIBLE);
            mDrawer.closeDrawers();
        });
        findViewById(R.id.rl_btn_force_close).setOnClickListener(v -> {
            mDrawer.closeDrawers();
            Tools.dialogForceClose(this);
        });
    }

    private void wireCanvasTouch() {
        mCanvas.setOnTouchListener(this);
    }

    /**
     * Touch mapping (mirrors OSRS Mobile feel):
     *  - 1 finger tap (quick down+up, no movement) = left click
     *  - 1 finger hold (>500ms, no movement)       = right click (context menu)
     *  - 1 finger drag (movement > threshold)      = left button HELD during the drag
     *      (inventory drag-and-drop, minimap drag, etc.)
     *  - 2 fingers pan                             = arrow keys (camera rotate / tilt)
     *  - 2 fingers pinch                           = mouse-wheel scroll (zoom)
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        int pointers = event.getPointerCount();

        // End of a multi-finger gesture — when one of several pointers lifts.
        if (action == MotionEvent.ACTION_POINTER_UP && mTwoFingerActive) {
            releaseAllArrows();
            mTwoFingerActive = false;
            return true;
        }

        // 2-finger gesture: camera + zoom. Release any in-progress 1-finger drag state first.
        if (pointers >= 2) {
            if (mLeftButtonHeld) {
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                mLeftButtonHeld = false;
            }
            if (mCameraDragging) {
                releaseAllArrows();
                mCameraDragging = false;
            }
            handleTwoFinger(event, action);
            return true;
        }

        // 2-finger gesture just ended — full-lift cleanup if it didn't already happen.
        if (mTwoFingerActive && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            releaseAllArrows();
            mTwoFingerActive = false;
            return true;
        }

        float x = event.getX(), y = event.getY();

        if (mVirtualMouseEnabled) {
            handleVirtualMouse(event, action, x, y);
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLongPressFired = false;
                mLeftButtonHeld = false;
                mCameraDragging = false;
                mDownX = x;
                mDownY = y;
                mUiZoneTouch = mCanvas.getWidth() > 0
                        && x > mCanvas.getWidth() * UI_ZONE_FRACTION;
                sendScaledMousePosition(x, y);
                // Custom long-press timer (generous slop, ignores small finger jitter).
                mUiHandler.removeCallbacks(mLongPressFire);
                mUiHandler.postDelayed(mLongPressFire, LONG_PRESS_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mCameraDragging) {
                    updateCameraArrowsFromDelta(x - mLastTouchX, y - mLastTouchY);
                    mLastTouchX = x;
                    mLastTouchY = y;
                    break;
                }
                if (mLeftButtonHeld) {
                    sendScaledMousePosition(x, y);
                    break;
                }
                if (!mLongPressFired) {
                    float dist = (float) Math.hypot(x - mDownX, y - mDownY);
                    // Cancel pending long-press if the finger has wandered too far.
                    if (dist > LONG_PRESS_SLOP_PX) {
                        mUiHandler.removeCallbacks(mLongPressFire);
                    }
                    if (dist > DRAG_START_PX) {
                        if (mUiZoneTouch) {
                            AWTInputBridge.sendMousePos(
                                    (int) MathUtils.map(mDownX, 0, mCanvas.getWidth(), 0, AWTCanvasView.AWT_CANVAS_WIDTH),
                                    (int) MathUtils.map(mDownY, 0, mCanvas.getHeight(), 0, AWTCanvasView.AWT_CANVAS_HEIGHT));
                            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                            mLeftButtonHeld = true;
                            sendScaledMousePosition(x, y);
                        } else {
                            mCameraDragging = true;
                            mLastTouchX = x;
                            mLastTouchY = y;
                            updateCameraArrowsFromDelta(x - mDownX, y - mDownY);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mUiHandler.removeCallbacks(mLongPressFire);
                if (mCameraDragging) {
                    releaseAllArrows();
                    mCameraDragging = false;
                } else if (mLeftButtonHeld) {
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    mLeftButtonHeld = false;
                } else if (!mLongPressFired) {
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                }
                break;
        }
        return true;
    }

    /** Custom long-press timer — fires unless cancelled by ACTION_UP or finger drift > slop. */
    private final Runnable mLongPressFire = () -> {
        mLongPressFired = true;
        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK);
    };

    /** Same arrow-key direction mapping used by two-finger camera; shared with one-finger
     *  camera drag in the game-world zone. */
    private void updateCameraArrowsFromDelta(float dx, float dy) {
        updateArrow(0, dx < -ROTATE_DEADZONE_PX); // LEFT
        updateArrow(1, dx >  ROTATE_DEADZONE_PX); // RIGHT
        updateArrow(2, dy >  ROTATE_DEADZONE_PX); // UP key when finger drags down (look down)
        updateArrow(3, dy < -ROTATE_DEADZONE_PX); // DOWN key when finger drags up (look up)
    }

    private void handleVirtualMouse(MotionEvent event, int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastTouchX = x;
                mLastTouchY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                float nx = Math.max(0, Math.min((float)(mCanvas.getWidth() - mPointer.getWidth()),
                        mPointer.getX() + (x - mLastTouchX)));
                float ny = Math.max(0, Math.min((float)(mCanvas.getHeight() - mPointer.getHeight()),
                        mPointer.getY() + (y - mLastTouchY)));
                mPointer.setX(nx);
                mPointer.setY(ny);
                mLastTouchX = x;
                mLastTouchY = y;
                sendScaledMousePosition(nx + mPointer.getWidth() / 2f, ny + mPointer.getHeight() / 2f);
                break;
        }
    }

    private void handleTwoFinger(MotionEvent event, int action) {
        float x1 = event.getX(0), y1 = event.getY(0);
        float x2 = event.getX(1), y2 = event.getY(1);
        float midX = (x1 + x2) / 2f;
        float midY = (y1 + y2) / 2f;
        float distance = (float) Math.hypot(x2 - x1, y2 - y1);

        if (!mTwoFingerActive) {
            // Second finger just came down — anchor the gesture.
            mTwoFingerActive = true;
            mLastMidX = midX;
            mLastMidY = midY;
            mLastPinchDistance = distance;
            return;
        }

        float dx = midX - mLastMidX;
        float dy = midY - mLastMidY;

        // OSRS arrow keys: LEFT/RIGHT rotate the camera around the player, UP/DOWN tilt.
        // Mapping: finger drags right → camera rotates right (RIGHT arrow). Finger drags
        // down → camera tilts down (UP arrow in OSRS, which lowers the view angle).
        updateArrow(0, dx < -ROTATE_DEADZONE_PX); // LEFT
        updateArrow(1, dx >  ROTATE_DEADZONE_PX); // RIGHT
        updateArrow(2, dy >  ROTATE_DEADZONE_PX); // UP-key when finger drags down (look further down)
        updateArrow(3, dy < -ROTATE_DEADZONE_PX); // DOWN-key when finger drags up (look up)

        // Pinch -> mouse wheel zoom. Fingers spread (positive distDelta) = zoom in.
        // Route via the AWT bridge — CallbackBridge.sendScroll goes to LWJGL/GLFW which
        // RuneLite isn't listening on. AWTInputBridge.sendScroll → Cacio → RuneLite's
        // AWT MouseWheelListener.
        float distDelta = distance - mLastPinchDistance;
        if (Math.abs(distDelta) >= PINCH_PIXELS_PER_TICK) {
            int ticks = (int) (distDelta / PINCH_PIXELS_PER_TICK);
            try {
                // AWT wheel convention: positive y = scroll down = zoom OUT in OSRS.
                AWTInputBridge.sendScroll(0, -ticks);
            } catch (Throwable ignored) {}
            mLastPinchDistance += ticks * PINCH_PIXELS_PER_TICK;
        }

        mLastMidX = midX;
        mLastMidY = midY;
    }

    private void updateArrow(int idx, boolean shouldHold) {
        if (mArrowHeld[idx] != shouldHold) {
            AWTInputBridge.sendKey(' ', ARROW_KEYS[idx], shouldHold ? 1 : 0);
            mArrowHeld[idx] = shouldHold;
            // Start the auto-repeat pump on the first held arrow; it self-terminates
            // when no arrows remain held.
            if (shouldHold) {
                mUiHandler.removeCallbacks(mArrowRepeater);
                mUiHandler.postDelayed(mArrowRepeater, ARROW_REPEAT_MS);
            }
        }
    }

    private void releaseAllArrows() {
        for (int i = 0; i < mArrowHeld.length; i++) updateArrow(i, false);
        mUiHandler.removeCallbacks(mArrowRepeater);
    }

    /** OSRS treats each KEY_PRESSED as a rotation tick (like X11 auto-repeat), so a
     *  single key-down then key-up only rotates once. Spam re-press at ~33 Hz while
     *  any arrow is held to get smooth continuous rotation. */
    private final Runnable mArrowRepeater = new Runnable() {
        @Override
        public void run() {
            boolean any = false;
            for (int i = 0; i < mArrowHeld.length; i++) {
                if (mArrowHeld[i]) {
                    AWTInputBridge.sendKey(' ', ARROW_KEYS[i], 1);
                    any = true;
                }
            }
            if (any) mUiHandler.postDelayed(this, ARROW_REPEAT_MS);
        }
    };

    private void sendScaledMousePosition(float x, float y) {
        int w = mCanvas.getWidth();
        int h = mCanvas.getHeight();
        if (w <= 0 || h <= 0) return;
        x = Math.max(0, Math.min((float)w, x));
        y = Math.max(0, Math.min((float)h, y));
        AWTInputBridge.sendMousePos(
                (int) MathUtils.map(x, 0, w, 0, AWTCanvasView.AWT_CANVAS_WIDTH),
                (int) MathUtils.map(y, 0, h, 0, AWTCanvasView.AWT_CANVAS_HEIGHT));
    }

    private File extractAgentJar() {
        File dst = new File(getFilesDir(), "runelite_window_agent.jar");
        try (java.io.InputStream in = getAssets().open("components/runelite_window_agent/runelite_window_agent.jar");
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst;
        } catch (Throwable t) {
            Log.e("RuneLiteGame", "extractAgentJar failed", t);
            return null;
        }
    }

    private void launchRuneLite(String javaArgs) {
        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile()) {
                throw new IOException("Failed to create log file");
            }
            Logger.begin(latestLogFile.getAbsolutePath());
        } catch (IOException e) {
            Tools.showError(this, e, true);
            return;
        }
        if (javaArgs == null) {
            Tools.showError(this, new IllegalStateException("missing javaArgs extra"), true);
            return;
        }
        Runtime runtime = MultiRTUtils.forceReread(LauncherPreferences.PREF_DEFAULT_RUNTIME);
        new Thread(() -> {
            try {
                JREUtils.redirectAndPrintJRELog();
                List<String> argList = new ArrayList<>(Arrays.asList(javaArgs.split(" ")));
                List<String> javaArgList = new ArrayList<>();
                Tools.getCacioJavaArgs(javaArgList, runtime.javaVersion == 8, this);
                // Sync-extract the window-maximizer agent into our own files dir so we
                // never race the async unpackComponents flow.
                File agentJar = extractAgentJar();
                if (agentJar != null) {
                    javaArgList.add("-javaagent:" + agentJar.getAbsolutePath());
                    Log.i("RuneLiteGame", "javaagent=" + agentJar.getAbsolutePath() + " size=" + agentJar.length());
                } else {
                    Log.w("RuneLiteGame", "window-maximizer agent could not be extracted");
                }
                javaArgList.addAll(argList);
                Log.i("RuneLiteGame", "JVM args: " + javaArgList);
                JREUtils.launchJavaVM(this, runtime, null, javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
            } catch (Throwable t) {
                Log.e("RuneLiteGame", "JVM launch failed", t);
                runOnUiThread(() -> Tools.showError(this, t, true));
            }
        }, "RuneLiteJREMain").start();
    }
}

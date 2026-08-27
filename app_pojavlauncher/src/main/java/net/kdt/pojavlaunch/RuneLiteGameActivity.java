package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
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
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.keyboard.AwtCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.services.RuneLiteGameService;
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
    private boolean mDidMove;          // finger moved past TAP_SLOP — release should NOT fire a click
    private float mDownX, mDownY;
    private long mDownTimeMs;          // for the ACTION_UP fallback that promotes long holds to right-click
    private static final float TAP_SLOP_PX = 5f;          // any move past this disables the tap-on-release
    private static final float DRAG_START_PX = 8f;        // movement that promotes from tap to drag/camera
    private static final float UI_ZONE_FRACTION = 0.75f;  // > this fraction of canvas width = UI strip
    private static final long LONG_PRESS_MS = 200L;       // hold this long without moving = right-click
    private static final float LONG_PRESS_SLOP_PX = 60f;  // movement that cancels long-press (natural jitter)
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
    private static final float PINCH_PIXELS_PER_TICK = 30f;
    /** Minimum gap between wheel-event IPC writes. Reproducible JVM SIGSEGV
     *  (libjvm.so+0xa14ca0) when this was zero and the user pinched quickly. */
    private static final long MIN_WHEEL_GAP_MS = 60L;
    private long mLastWheelWriteMs;

    /** Set once we've kicked the JVM launch from onCreate. Activity onCreate can fire
     *  more than once across the process lifetime if Android restarts the activity
     *  without killing :runelitegame; the foreground service should keep the JVM up. */
    private static boolean sJvmLaunched;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullscreenFlags();
        // On Android 13+ the foreground service notification is only shown
        // (and the FGS is only treated as fully-protected by the OS) if we
        // hold POST_NOTIFICATIONS at runtime. Pojav requests this from its
        // launcher; we never did. Without the perm Android can SIGKILL our
        // :runelitegame process when backgrounded — which is exactly the bug
        // we've been chasing. Request it the first time the game activity
        // starts.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4242);
            }
        }
        // Kick the foreground service first so Android doesn't reap :runelitegame
        // while we're backgrounded — without this, switching apps for ~10s and
        // back causes a SIGSEGV in libjvm.so as the JVM dies mid-AWT-dispatch.
        try {
            ContextCompat.startForegroundService(this,
                    new Intent(this, RuneLiteGameService.class));
        } catch (Throwable t) {
            Log.w("RuneLiteGame", "could not start RuneLiteGameService", t);
        }
        // Clear any stale pause sentinel from a prior session that exited abnormally.
        setAgentPaused(false);
        // Square Cacio managed-screen so both orientations fit without
        // restarting the JVM. The size has two competing constraints:
        //   - smaller than the device's longer edge × 60% (cap CPU cost)
        //   - large enough that the *shorter* visible side stays above
        //     RuneLite's ~800px minimum window width (otherwise RuneLite
        //     pack()s back larger and the agent's resize loop fights it,
        //     leaving the UI clipped — that's what looked "stretched")
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int longerEdge = Math.max(dm.widthPixels, dm.heightPixels);
        int shorterEdge = Math.min(dm.widthPixels, dm.heightPixels);
        int minCanvasForRuneLite = 800 * longerEdge / Math.max(1, shorterEdge);
        int canvasDim = Math.max((longerEdge * 3) / 5, minCanvasForRuneLite);
        AWTCanvasView.setManagedScreenSize(canvasDim, canvasDim);
        // Single source of truth: AWTCanvasView.refreshSize() computes the
        // visible region from the parent view's measured pixels and fires
        // this listener for us to push the same numbers to the JVM-side
        // agent. The activity no longer guesses dimensions from
        // DisplayMetrics (which lagged the actual screen on rotation).
        AWTCanvasView.setVisibleRegionListener((vw, vh) ->
                writeInputRequest("RESIZE " + vw + " " + vh));
        AWTCanvasView.TRANSPARENT_BACKGROUND = true;
        setContentView(R.layout.activity_runelite_game);

        mCanvas = findViewById(R.id.rl_awt_canvas);
        mGlSurface = findViewById(R.id.rl_gl_surface);
        mPointer = findViewById(R.id.rl_mouse_pointer);
        mKeyboardInput = findViewById(R.id.rl_keyboard_input);
        mDrawer = findViewById(R.id.rl_drawer);
        mLogger = findViewById(R.id.rl_logger);

        // rl_gl_surface was wired up for the GPU-plugin / hybrid GL plan, which
        // was abandoned on the grounds that there is no librlawt.so for Android.
        // That reasoning is being revisited: the APK already ships zink over
        // Vulkan, libglxshim.so for the GLX entry points rlawt calls, and Mesa
        // EGL, and the zink path already asks Mesa for 4.6COMPAT, which is above
        // the 4.3 the GPU plugin needs for its compute shaders. What is missing
        // is a librlawt built against a Surface instead of an X11 Drawable.
        //
        // "GL probe" in the drawer arms that measurement for the next launch, so
        // the port gets decided on what the device reports rather than on what
        // the APK contains. Outside of a probe run the surface stays hidden: the
        // software renderer does not need one, since AWTCanvasView takes the
        // frame straight off the AWT side.
        //
        // Note for whoever wires this up for real: leaving the GL surface's
        // SurfaceHolder.Callback registered used to fire setupBridgeWindow on
        // every background/resume, and that path calls ANativeWindow_fromSurface
        // without ever releasing the previous reference. The resulting leak was
        // once blamed for the libjvm SIGSEGV, which turned out to be Memory
        // Tagging instead, but the leak is real and still needs a release.
        if (glProbeArmed()) {
            armGlProbe();
        } else {
            mGlSurface.setVisibility(View.GONE);
        }

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
                if (mDrawer.isDrawerOpen(android.view.Gravity.START)) {
                    mDrawer.closeDrawers();
                } else {
                    Tools.dialogForceClose(RuneLiteGameActivity.this);
                }
            }
        });

        // Activity is locked to landscape — the Cacio bitmap is landscape-aspect
        // and stretching it to fill any other orientation looks wrong. Let the
        // canvas fill the screen.
        mCanvas.post(() -> {
            ViewGroup.LayoutParams lp = mCanvas.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mCanvas.setLayoutParams(lp);
            applyGestureExclusionRects();
        });

        // Launch RuneLite once the canvas is laid out. We used to also wait
        // on the GL surface being ready, but the GL surface is now disabled
        // (see above), so there's nothing to wait for there.
        mCanvas.post(() -> launchRuneLite(getIntent().getStringExtra(EXTRA_JAVA_ARGS)));
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
        menu.setOnClickListener(v -> mDrawer.openDrawer(android.view.Gravity.START));

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
        findViewById(R.id.rl_btn_gl_probe).setOnClickListener(v -> {
            mDrawer.closeDrawers();
            boolean armed = !glProbeArmed();
            if (armed) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    glProbeMarker().createNewFile();
                } catch (IOException e) {
                    Toast.makeText(this, "could not arm the probe: " + e, Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                glProbeMarker().delete();
            }
            new android.app.AlertDialog.Builder(this)
                .setMessage(armed
                    ? "GL probe armed. Close the game and start it again: it will bring up the"
                      + " desktop GL stack and report what this device gives back."
                    : "GL probe disarmed. The next launch is a normal one.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
        });
        findViewById(R.id.rl_btn_jagex_logout).setOnClickListener(v -> {
            mDrawer.closeDrawers();
            new android.app.AlertDialog.Builder(this)
                .setMessage(R.string.jagex_log_out_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    net.kdt.pojavlaunch.jagex.JagexAccount.clear(this);
                    Tools.dialogForceClose(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
        findViewById(R.id.rl_btn_force_close).setOnClickListener(v -> {
            mDrawer.closeDrawers();
            Tools.dialogForceClose(this);
        });
    }

    // --- GL probe -------------------------------------------------------
    // Armed by a marker file rather than a live toggle, because the renderer has
    // to be chosen before the JVM starts and the JVM outlives this activity.

    private java.io.File glProbeMarker() {
        return new java.io.File(getFilesDir(), "gl-probe");
    }

    private boolean glProbeArmed() {
        return glProbeMarker().exists();
    }

    /** Show the GL surface and, once it exists, ask the bridge what it can give us. */
    private void armGlProbe() {
        mGlSurface.setVisibility(View.VISIBLE);
        mGlSurface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            private boolean done;

            @Override
            public void surfaceCreated(android.view.SurfaceHolder holder) {
                if (done) return;
                done = true;
                JREUtils.setupBridgeWindow(holder.getSurface());
                new Thread(() -> {
                    String result;
                    try {
                        // The surface exists long before the JVM launch thread
                        // has applied the renderer environment, so set it here
                        // rather than depending on that ordering. The context
                        // itself needs no JVM.
                        android.system.Os.setenv("AMETHYST_RENDERER",
                                "opengles3_desktopgl_zink_kopper", true);
                        if (android.system.Os.getenv("FORCE_VSYNC") == null) {
                            android.system.Os.setenv("FORCE_VSYNC", "false", true);
                        }
                        JREUtils.loadGraphicsLibrary();

                        result = JREUtils.probeDesktopGL();
                    } catch (Throwable t) {
                        result = "probe threw: " + t;
                    }
                    Log.i("RuneLiteGame", "GL probe:\n" + result);
                    // Also to disk: the JVM writes a lot when it is unhappy, and
                    // the logcat buffer does not survive that.
                    try {
                        java.io.File out = new java.io.File(
                                getExternalFilesDir(null), "gl-probe-result.txt");
                        try (java.io.FileWriter w = new java.io.FileWriter(out)) {
                            w.write(result);
                        }
                        Log.i("RuneLiteGame", "GL probe written to " + out);
                    } catch (Throwable t) {
                        Log.w("RuneLiteGame", "could not write the probe result", t);
                    }
                    final String shown = result;
                    mUiHandler.post(() -> new android.app.AlertDialog.Builder(RuneLiteGameActivity.this)
                        .setTitle("GL probe")
                        .setMessage(shown)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
                }, "GLProbe").start();
            }

            @Override
            public void surfaceChanged(android.view.SurfaceHolder holder, int f, int w, int h) { }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                // Deliberately not releasing the bridge window. pojavInit acquires
                // it as well, and releasing here took the JVM down with
                // SIGBUS inside releaseBridgeWindow on the way out. The probe runs
                // once per launch, so one window reference is not worth a crash.
            }
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

        // 2-finger gesture: camera + zoom. Release any in-progress 1-finger drag state first,
        // and cancel any pending long-press timer so a right-click doesn't fire mid-pinch.
        if (pointers >= 2) {
            mUiHandler.removeCallbacks(mLongPressFire);
            if (mLeftButtonHeld) {
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                mLeftButtonHeld = false;
            }
            if (mCameraDragging) {
                releaseAllArrows();
                mCameraDragging = false;
            }
            mDidMove = true; // 2-finger gesture means no follow-up click on release
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
                mDidMove = false;
                mDownX = x;
                mDownY = y;
                mDownTimeMs = System.currentTimeMillis();
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
                    // Any meaningful movement disqualifies the release from firing a click.
                    if (dist > TAP_SLOP_PX) mDidMove = true;
                    // Cancel pending long-press if the finger has wandered too far.
                    if (dist > LONG_PRESS_SLOP_PX) {
                        mUiHandler.removeCallbacks(mLongPressFire);
                    }
                    if (dist > DRAG_START_PX) {
                        // Entering drag/camera mode — kill the long-press timer NOW.
                        // Otherwise a slow drag (8-60px over 200ms+) would have the
                        // timer fire mid-drag and pop a right-click context menu
                        // over the rotation, breaking camera.
                        mUiHandler.removeCallbacks(mLongPressFire);
                        if (mUiZoneTouch) {
                            AWTInputBridge.sendMousePos(
                                    (int) MathUtils.map(mDownX, 0, mCanvas.getWidth(), 0, AWTCanvasView.AWT_VISIBLE_WIDTH),
                                    (int) MathUtils.map(mDownY, 0, mCanvas.getHeight(), 0, AWTCanvasView.AWT_VISIBLE_HEIGHT));
                            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                            mLeftButtonHeld = true;
                            sendScaledMousePosition(x, y);
                        } else {
                            mCameraDragging = true;
                            mLastTouchX = x;
                            mLastTouchY = y;
                            // Pull AWT focus onto the OSRS Canvas before any arrow
                            // keys fly. Otherwise the plugin sidebar's search text
                            // field — auto-focused when the sidebar is open —
                            // swallows LEFT/RIGHT as cursor movement.
                            writeInputRequest("FOCUSGAME");
                            updateCameraArrowsFromDelta(x - mDownX, y - mDownY);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mUiHandler.removeCallbacks(mLongPressFire);
                long heldMs = System.currentTimeMillis() - mDownTimeMs;
                if (mCameraDragging) {
                    releaseAllArrows();
                    mCameraDragging = false;
                } else if (mLeftButtonHeld) {
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    mLeftButtonHeld = false;
                } else if (mLongPressFired) {
                    // long-press timer already fired the right-click; nothing more to do.
                } else if (!mDidMove && heldMs >= LONG_PRESS_MS) {
                    // Fallback right-click: held long enough without significant motion,
                    // but the Handler.postDelayed timer didn't get its callback in time.
                    fireRightClick();
                } else if (!mDidMove) {
                    // Short tap → left click.
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                }
                break;
        }
        return true;
    }

    /** Custom long-press timer — fires unless cancelled by ACTION_UP or finger drift > slop. */
    private final Runnable mLongPressFire = () -> {
        mLongPressFired = true;
        fireRightClick();
    };

    /** Right-click. After decompiling cacio-tta.jar's CTCRobotPeer:
     *    mousePress(input) calls buttonDownToButtonMask(input), which is
     *    bit-shifted incorrectly: for BUTTON3_DOWN_MASK (4096) it produces
     *    modifier bit 64 instead of BUTTON3_MASK (4). RuneLite's right-click
     *    check sees a garbage modifier and doesn't recognize the event.
     *  Fix: pass the OLD-style BUTTON3_MASK (= 4 = META_MASK) as input. Cacio
     *  preserves it correctly because the decoder does (input & 28) first
     *  which catches the low bits cleanly. As a bonus, BUTTON3_MASK input
     *  causes Cacio's mouseRelease to set popupTrigger=true (the (input & 4)
     *  check), which is what triggers context menus on Linux/AWT. */
    private void fireRightClick() {
        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_MASK);
    }

    /** Sentinel file the JVM-side agent watches. While it exists, the agent
     *  pauses its maximize sweep AND drops incoming wheel/right-click events
     *  instead of dispatching them. Pause/resume the agent in lockstep with
     *  the activity so AWT mutations don't race with surface destroy/recreate. */
    private void setAgentPaused(boolean paused) {
        try {
            File extDir = getExternalFilesDir(null);
            if (extDir == null) return;
            File sentinel = new File(extDir, ".runelitedroid_paused");
            if (paused) {
                if (!sentinel.exists()) sentinel.createNewFile();
            } else {
                if (sentinel.exists()) sentinel.delete();
            }
        } catch (Throwable t) {
            Log.w("RuneLiteGame", "setAgentPaused(" + paused + ") failed", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        setAgentPaused(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setAgentPaused(false);
    }


    /** Append one IPC line for the JVM-side input bridge agent to consume.
     *  External-files dir is shared between our process and the :runelitegame
     *  JVM process (same UID), and matches the agent's user.home setting. */
    private void writeInputRequest(String line) {
        try {
            File extDir = getExternalFilesDir(null);
            if (extDir == null) return;
            File req = new File(extDir, ".runelitedroid_input");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(req, true)) {
                fos.write((line + "\n").getBytes());
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mCanvas != null) {
            // Reset to match_parent first so the previous orientation's
            // explicit pixel layoutParams don't push the view off-screen
            // before refreshSize() runs.
            android.view.ViewGroup.LayoutParams lp = mCanvas.getLayoutParams();
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            mCanvas.setLayoutParams(lp);
            mCanvas.post(() -> mCanvas.refreshSize());
        }
    }

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
            // Same reason as one-finger camera: pull focus onto OSRS Canvas so
            // sidebar text fields don't eat arrow keys mid-rotation.
            writeInputRequest("FOCUSGAME");
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
            long nowMs = System.currentTimeMillis();
            if (nowMs - mLastWheelWriteMs >= MIN_WHEEL_GAP_MS) {
                int ticks = (int) (distDelta / PINCH_PIXELS_PER_TICK);
                // Caciocavallo's CTCAndroidInput doesn't handle EVENT_TYPE_SCROLL, so the
                // AWT-bridge path is a no-op. Instead, write a request line to the
                // input-bridge file; the window-maximizer agent (which lives inside the
                // JVM) reads it and posts a MouseWheelEvent directly into AWT's event
                // queue. Negate ticks because AWT wheel convention is +y = scroll down.
                writeInputRequest("WHEEL " + (-ticks));
                mLastPinchDistance += ticks * PINCH_PIXELS_PER_TICK;
                mLastWheelWriteMs = nowMs;
            }
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
                (int) MathUtils.map(x, 0, w, 0, AWTCanvasView.AWT_VISIBLE_WIDTH),
                (int) MathUtils.map(y, 0, h, 0, AWTCanvasView.AWT_VISIBLE_HEIGHT));
    }

    /** Reserve the top portion of both left and right edges for our app's taps.
     *  Android 10+ steals taps within ~20dp of the screen edges for system gestures
     *  (back swipe especially), which makes the right-edge RuneLite UI (sidebar
     *  toggle, plugin chevron, X close) unresponsive. setSystemGestureExclusionRects
     *  tells the OS we'll handle touches in those rects. Android caps each rect at
     *  200dp tall per edge, which is plenty to cover RuneLite's right-edge buttons. */
    private void applyGestureExclusionRects() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return;
        try {
            int density = (int) getResources().getDisplayMetrics().density;
            int edgeStripPx = 36 * density;
            int barHeightPx = 200 * density;
            java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>();
            rects.add(new android.graphics.Rect(0, 0, edgeStripPx, barHeightPx));
            rects.add(new android.graphics.Rect(mCanvas.getWidth() - edgeStripPx, 0,
                    mCanvas.getWidth(), barHeightPx));
            mCanvas.setSystemGestureExclusionRects(rects);
        } catch (Throwable t) {
            Log.w("RuneLiteGame", "setSystemGestureExclusionRects failed", t);
        }
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

    private File extractAudioJar() {
        File dst = new File(getFilesDir(), "runelite_audio.jar");
        try (java.io.InputStream in = getAssets().open("components/runelite_audio/runelite_audio.jar");
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst;
        } catch (Throwable t) {
            Log.e("RuneLiteGame", "extractAudioJar failed", t);
            return null;
        }
    }

    private void launchRuneLite(String javaArgs) {
        if (sJvmLaunched) {
            Log.i("RuneLiteGame", "JVM already launched once in this process — skipping re-launch");
            return;
        }
        sJvmLaunched = true;
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
        // Skip Pojav's methods_injector_agent for our launch — it's an LWJGL2
        // OpenAL compat patcher for Minecraft mods, doesn't apply to RuneLite,
        // and installs a ClassFileTransformer that runs on every class load.
        System.setProperty("pojav.skip.methodsInjector", "1");
        // Picking a renderer has to happen before the JVM environment is built:
        // it decides both AMETHYST_RENDERER and which graphics library gets
        // dlopen'd. Only for a probe run, so a normal launch keeps the process
        // free of the GL stack entirely.
        if (glProbeArmed()) {
            Tools.LOCAL_RENDERER = "opengles3_desktopgl_zink_kopper";
            Log.i("RuneLiteGame", "GL probe armed, renderer=" + Tools.LOCAL_RENDERER);
        }
        new Thread(() -> {
            try {
                JREUtils.redirectAndPrintJRELog();
                List<String> argList = new ArrayList<>(Arrays.asList(javaArgs.split(" ")));
                List<String> javaArgList = new ArrayList<>();
                Tools.getCacioJavaArgs(javaArgList, runtime.javaVersion == 8, this);
                // -XX:TieredStopAtLevel=1 used to be set here, together with SerialGC,
                // to stop the JVM faulting at libjvm.so+0xa14ca0 a few seconds into AWT
                // activity. The guess at the time was a bad C2 optimisation or a GC
                // barrier moving AWT image buffers. Both were wrong: the tombstone says
                // SIGSEGV / SEGV_MTESERR, so it was Memory Tagging catching a real
                // memory error, now opted out of in the manifest. A per-frame JNI local
                // reference leak in awt_bridge.c has been fixed since as well.
                //
                // Disabling C2 is very expensive here. OSRS renders in software through
                // Caciocavallo, so its rasteriser is the hottest numeric loop in the
                // process, and that is exactly the code C2 exists to optimise. Running
                // it on the C1 baseline compiler was costing far more than it bought.
                //
                // SerialGC went with it. It stops the world for every collection, and
                // startup is where the allocation happens: class loading, plugin init,
                // the AWT peers. Those pauses are exactly what the first seconds feel
                // like. G1 is the default and keeps its pauses bounded.
                //
                // The rest is warm-up. Until C2 has compiled the rasteriser the game
                // runs on C1, which is the state this build just stopped shipping
                // permanently, so it is worth reaching the compiled code sooner.
                // Defaults are 200/5000/15000; these ask for C2 after roughly a fifth
                // of that, at the cost of compiling a few methods that never get hot.
                //
                // IgnoreUnrecognizedVMOptions first, because a flag this JRE does not
                // know would otherwise stop the JVM from starting at all, and the JRE
                // is a third-party Android build of OpenJDK.
                javaArgList.add("-XX:+IgnoreUnrecognizedVMOptions");
                javaArgList.add("-XX:Tier4MinInvocationThreshold=150");
                javaArgList.add("-XX:Tier4InvocationThreshold=1000");
                javaArgList.add("-XX:Tier4CompileThreshold=3000");
                // Sync-extract the window-maximizer agent into our own files dir so we
                // never race the async unpackComponents flow.
                File agentJar = extractAgentJar();
                if (agentJar != null) {
                    javaArgList.add("-javaagent:" + agentJar.getAbsolutePath());
                    Log.i("RuneLiteGame", "javaagent=" + agentJar.getAbsolutePath() + " size=" + agentJar.length());
                } else {
                    Log.w("RuneLiteGame", "window-maximizer agent could not be extracted");
                }
                // RunavaAudio SPI provider for javax.sound.sampled. Has to land on
                // the *bootstrap* class path — that's what makes the AudioSystem
                // ServiceLoader actually see the MixerProvider on FCL's OpenJDK 25.
                // Prior attempts to plumb it via system classpath or runtime
                // Instrumentation.appendToSystemClassLoaderSearch never worked.
                File audioJar = extractAudioJar();
                if (audioJar != null) {
                    javaArgList.add("-Xbootclasspath/a:" + audioJar.getAbsolutePath());
                    Log.i("RuneLiteGame", "audio bootcp=" + audioJar.getAbsolutePath()
                            + " size=" + audioJar.length());
                    // The JVM can't see Android's native lib dir via java.library.path;
                    // hand RunavaSourceDataLine the absolute .so path so it can System.load.
                    String soPath = getApplicationInfo().nativeLibraryDir + "/librunava_audio.so";
                    javaArgList.add("-Drunava.audio.library=" + soPath);
                    Log.i("RuneLiteGame", "runava.audio.library=" + soPath);
                } else {
                    Log.w("RuneLiteGame", "audio SPI jar could not be extracted; sound will be silent");
                }
                javaArgList.addAll(argList);
                System.out.println("[RuneLiteGameActivity] launching JVM with args: " + javaArgList);
                JREUtils.launchJavaVM(this, runtime, null, javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
            } catch (Throwable t) {
                Log.e("RuneLiteGame", "JVM launch failed", t);
                runOnUiThread(() -> Tools.showError(this, t, true));
            }
        }, "RuneLiteJREMain").start();
    }
}

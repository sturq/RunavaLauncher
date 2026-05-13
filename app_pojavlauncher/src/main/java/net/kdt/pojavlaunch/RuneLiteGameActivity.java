package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
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

import org.lwjgl.glfw.CallbackBridge;

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
    private ImageView mPointer;
    private TouchCharInput mKeyboardInput;
    private DrawerLayout mDrawer;
    private LoggerView mLogger;
    private GestureDetector mGestures;

    private boolean mVirtualMouseEnabled;
    private float mLastTouchX, mLastTouchY;

    // Two-finger gesture state for camera rotate (middle-mouse drag) + zoom (scroll wheel).
    private boolean mTwoFingerActive;
    private float mLastMidX, mLastMidY;
    private float mLastPinchDistance;
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
        mPointer = findViewById(R.id.rl_mouse_pointer);
        mKeyboardInput = findViewById(R.id.rl_keyboard_input);
        mDrawer = findViewById(R.id.rl_drawer);
        mLogger = findViewById(R.id.rl_logger);

        MainActivity.GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        mKeyboardInput.setCharacterSender(new AwtCharSender());
        mDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        mGestures = new GestureDetector(this, new GestureListener());

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

        // Start the JVM after layout so the canvas surface is ready.
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
     * Touch mapping:
     *  - 1 finger tap = left click (via GestureListener.onSingleTapUp)
     *  - 1 finger long-press = right click
     *  - 1 finger drag = mouse hover/drag (position tracking)
     *  - 2 fingers pan = middle-mouse drag (camera rotate in OSRS)
     *  - 2 fingers pinch = mouse-wheel scroll (camera zoom)
     *
     *  Virtual mouse mode replaces direct touch with relative-movement pointer.
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();
        int pointers = event.getPointerCount();

        // 2-finger camera/zoom takes precedence — only consume gestures with one finger down.
        if (pointers >= 2) {
            handleTwoFinger(event, action);
            return true;
        }
        // Releasing the second finger ends the camera drag cleanly.
        if (mTwoFingerActive && (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK, false);
            mTwoFingerActive = false;
        }
        if (mTwoFingerActive) return true; // ignore stray single-finger events during the gesture

        mGestures.onTouchEvent(event);
        float x = event.getX(), y = event.getY();

        if (mVirtualMouseEnabled) {
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
        } else {
            // Direct touch: send mouse position on every touch event so AWT tracks the finger.
            sendScaledMousePosition(x, y);
        }
        return true;
    }

    private void handleTwoFinger(MotionEvent event, int action) {
        float x1 = event.getX(0), y1 = event.getY(0);
        float x2 = event.getX(1), y2 = event.getY(1);
        float midX = (x1 + x2) / 2f;
        float midY = (y1 + y2) / 2f;
        float distance = (float) Math.hypot(x2 - x1, y2 - y1);

        if (!mTwoFingerActive) {
            // Second finger just came down — anchor the gesture and press middle button at the midpoint.
            mTwoFingerActive = true;
            mLastMidX = midX;
            mLastMidY = midY;
            mLastPinchDistance = distance;
            sendScaledMousePosition(midX, midY);
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK, true);
            return;
        }

        // Translate pinch into mouse-wheel ticks. Positive = zoom in (fingers spread), negative = zoom out.
        float distDelta = distance - mLastPinchDistance;
        if (Math.abs(distDelta) >= PINCH_PIXELS_PER_TICK) {
            int ticks = (int) (distDelta / PINCH_PIXELS_PER_TICK);
            // Scroll convention: AWT/Java negative-y = scroll up = zoom in. OSRS follows that.
            try {
                CallbackBridge.sendScroll(0.0, -ticks);
            } catch (Throwable ignored) {}
            mLastPinchDistance += ticks * PINCH_PIXELS_PER_TICK;
        }

        // Translate two-finger pan into middle-button drag — drives OSRS camera rotation.
        if (midX != mLastMidX || midY != mLastMidY) {
            sendScaledMousePosition(midX, midY);
            mLastMidX = midX;
            mLastMidY = midY;
        }
    }

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

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK);
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
            return true;
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

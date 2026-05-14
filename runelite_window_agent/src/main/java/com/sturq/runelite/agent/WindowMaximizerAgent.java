package com.sturq.runelite.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;

/**
 * Java agent that force-maximizes top-level Swing/AWT Frames. Loaded via
 * -javaagent in the RuneLite launch path so RuneLite's main JFrame fills
 * the Caciocavallo virtual screen instead of sitting at its default size
 * with black around it.
 *
 * Daemon thread polls Frame.getFrames() and sets MAXIMIZED_BOTH on anything
 * visible that isn't already at the screen size. This is the layout that
 * worked for the user's mining session at b46aec77b.
 *
 * Background-crash workaround: while the Android activity is paused it
 * writes a sentinel file ($user.home/.runelitedroid_paused). Both the
 * maximize sweep and the input bridge check for that file and skip all
 * AWT mutation while it exists. AWT paint events from the EDT still run,
 * but we don't add new race targets from our daemon threads.
 */
public class WindowMaximizerAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        startPoller();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        startPoller();
    }

    /** Sentinel file the Android activity drops while paused. While present,
     *  the agent doesn't touch AWT — no resizes, no wheel events, no clicks.
     *  We resolve it lazily from user.home, same dir as the input request file. */
    private static volatile File sPausedSentinel;

    private static File pausedSentinel() {
        File f = sPausedSentinel;
        if (f == null) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) {
                f = new File(home, ".runelitedroid_paused");
                sPausedSentinel = f;
            }
        }
        return f;
    }

    private static boolean isPaused() {
        File f = pausedSentinel();
        return f != null && f.exists();
    }

    private static void startPoller() {
        Thread t = new Thread(() -> {
            // Burn the first few seconds with a tighter loop so the first frame opens maxed.
            long deadline = System.currentTimeMillis() + 30_000L;
            while (true) {
                try {
                    if (!isPaused()) sweep();
                } catch (Throwable ignored) {
                    // Don't let the poller die — AWT may still be coming up.
                }
                try {
                    Thread.sleep(System.currentTimeMillis() < deadline ? 250L : 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "WindowMaximizerAgent");
        t.setDaemon(true);
        t.start();
        startInputBridge();
        System.out.println("[WindowMaximizerAgent] poller started");
    }

    /** File-based IPC for input events the Activity can't deliver via the AWT
     *  bridge (notably mouse wheel — Caciocavallo's CTCAndroidInput doesn't
     *  handle EVENT_TYPE_SCROLL). Activity writes a line to:
     *      $user.home/.runelitedroid_input
     *  Format per line:
     *      WHEEL <ticks>     -> dispatch MouseWheelEvent with that rotation count
     *      RIGHTCLICK        -> dispatch right-click at the focused window's center
     *  Agent polls every 50ms, processes complete lines, truncates. */
    private static void startInputBridge() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            System.out.println("[WindowMaximizerAgent] no user.home, input bridge disabled");
            return;
        }
        final File request = new File(home, ".runelitedroid_input");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    // Always process the file — lifecycle commands like ICONIFY /
                    // DEICONIFY must run during pause/resume transitions so we
                    // can signal AWT to idle. handleInputLine() drops user-input
                    // commands (WHEEL/RIGHTCLICK) on its own when paused.
                    if (request.exists() && request.length() > 0) {
                        String content;
                        try (RandomAccessFile raf = new RandomAccessFile(request, "rw")) {
                            byte[] buf = new byte[(int) Math.min(raf.length(), 1024)];
                            raf.readFully(buf);
                            raf.setLength(0); // consume by truncating
                            content = new String(buf).trim();
                        }
                        for (String line : content.split("\\n+")) {
                            handleInputLine(line.trim());
                        }
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(50L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }, "InputBridgePoller");
        t.setDaemon(true);
        t.start();
    }

    private static void handleInputLine(String line) {
        if (line.isEmpty()) return;
        try {
            // Lifecycle commands run regardless of pause state — they're what
            // takes us into / out of the paused state. User-input commands
            // (WHEEL, RIGHTCLICK) drop while paused so we don't post AWT
            // events while the activity is in the background.
            if (line.equals("ICONIFY")) {
                setFrameState(Frame.ICONIFIED);
            } else if (line.equals("DEICONIFY")) {
                setFrameState(Frame.NORMAL);
            } else if (isPaused()) {
                // drop user input
            } else if (line.startsWith("WHEEL ")) {
                int ticks = Integer.parseInt(line.substring(6).trim());
                postWheel(ticks);
            } else if (line.equals("RIGHTCLICK")) {
                postRightClick();
            }
        } catch (Throwable t) {
            System.out.println("[WindowMaximizerAgent] bad input line '" + line + "': " + t);
        }
    }

    /** Tell every visible Frame whether the OS-level window is minimized. Pojav
     *  does the equivalent for GLFW via nativeSetWindowAttrib(VISIBLE, 0/1) on
     *  pause/resume — Minecraft's render loop reads that and idles. AWT has no
     *  GLFW, but Frame.setExtendedState(ICONIFIED) is the standard signal that
     *  Swing's RepaintManager and Timer machinery honor: paints get coalesced,
     *  the EDT idles, the JVM stops doing heavy AWT work in the background.
     *  Run on the EDT so it serializes with whatever paint/event is in flight. */
    private static void setFrameState(int newState) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            for (Frame f : Frame.getFrames()) {
                if (f == null || !f.isDisplayable()) continue;
                try {
                    // Preserve MAXIMIZED_BOTH bits when toggling ICONIFIED.
                    int cur = f.getExtendedState();
                    int next;
                    if (newState == Frame.ICONIFIED) {
                        next = cur | Frame.ICONIFIED;
                    } else {
                        next = cur & ~Frame.ICONIFIED;
                    }
                    if (next != cur) {
                        f.setExtendedState(next);
                        System.out.println("[WindowMaximizerAgent] frame state '"
                                + f.getTitle() + "' " + Integer.toHexString(cur)
                                + " -> " + Integer.toHexString(next));
                    }
                } catch (Throwable t) {
                    System.out.println("[WindowMaximizerAgent] setFrameState failed: " + t);
                }
            }
        });
    }

    private static Window pickTargetWindow() {
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (w != null && w.isVisible()) return w;
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) return f;
        }
        return null;
    }

    /** Find the deepest visible Component under the center of the target window.
     *  Wheel/mouse listeners are attached to specific Components (the OSRS Canvas
     *  inside RuneLite's JFrame, for example), not to the Window itself, so
     *  dispatching to the Window is a no-op. */
    private static Component pickWheelTarget() {
        Window w = pickTargetWindow();
        if (w == null) return null;
        int cx = w.getWidth() / 2, cy = w.getHeight() / 2;
        if (w instanceof Container) {
            Component deep = findDeepestAt((Container) w, cx, cy);
            if (deep != null) return deep;
        }
        return w;
    }

    private static Component findDeepestAt(Container container, int x, int y) {
        Component direct = container.findComponentAt(x, y);
        if (direct != null && direct != container) return direct;
        Component[] kids = container.getComponents();
        for (Component k : kids) {
            if (!k.isVisible()) continue;
            int kx = x - k.getX(), ky = y - k.getY();
            if (kx < 0 || ky < 0 || kx > k.getWidth() || ky > k.getHeight()) continue;
            if (k instanceof Container) {
                Component sub = findDeepestAt((Container) k, kx, ky);
                if (sub != null) return sub;
            }
            return k;
        }
        return container;
    }

    /** Hard rate-limit: a burst of wheel events reproducibly crashed libjvm.so
     *  back when we used Component.dispatchEvent directly. Keep the limit even
     *  though we're now on the system event queue — defense in depth. */
    private static volatile long sLastWheelMs = 0L;
    private static final long MIN_WHEEL_GAP_MS = 60L;

    private static void postWheel(final int ticks) {
        long now = System.currentTimeMillis();
        if (now - sLastWheelMs < MIN_WHEEL_GAP_MS) return;
        sLastWheelMs = now;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (isPaused()) return;
            Component target = pickWheelTarget();
            if (target == null) return;
            int x = target.getWidth() / 2, y = target.getHeight() / 2;
            long when = System.currentTimeMillis();
            MouseWheelEvent e = new MouseWheelEvent(
                    target, MouseEvent.MOUSE_WHEEL, when, 0,
                    x, y, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    Math.abs(ticks), ticks);
            // Post via the system event queue rather than Component.dispatchEvent so
            // AWT serializes the wheel event alongside paint events on the EDT.
            try {
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e);
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] postEvent WHEEL failed: " + t);
            }
        });
    }

    private static void postRightClick() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (isPaused()) return;
            Component target = pickWheelTarget();
            if (target == null) return;
            int x = target.getWidth() / 2, y = target.getHeight() / 2;
            long when = System.currentTimeMillis();
            MouseEvent down = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when, 0,
                    x, y, 1, true, MouseEvent.BUTTON3);
            MouseEvent up = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when + 1, 0,
                    x, y, 1, false, MouseEvent.BUTTON3);
            try {
                java.awt.EventQueue eq = Toolkit.getDefaultToolkit().getSystemEventQueue();
                eq.postEvent(down);
                eq.postEvent(up);
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] postEvent RIGHTCLICK failed: " + t);
            }
        });
    }

    private static void sweep() {
        Frame[] frames = Frame.getFrames();
        if (frames.length == 0) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (screen == null || screen.width <= 0 || screen.height <= 0) return;
        for (Frame f : frames) {
            if (f == null) continue;
            // Frame.getFrames() returns only Frames (Dialog extends Window directly,
            // not Frame), so popups (FatalErrorDialog etc.) are never in this array.
            if (!f.isVisible()) continue;
            try {
                int curW = f.getWidth();
                int curH = f.getHeight();
                int curX = f.getX();
                int curY = f.getY();
                if (curW != screen.width || curH != screen.height || curX != 0 || curY != 0) {
                    f.setLocation(0, 0);
                    f.setSize(screen.width, screen.height);
                    f.setExtendedState(f.getExtendedState() | Frame.MAXIMIZED_BOTH);
                    f.validate();
                    System.out.println("[WindowMaximizerAgent] resize '" + f.getTitle()
                            + "' " + curW + "x" + curH + " @ " + curX + "," + curY
                            + " -> " + screen.width + "x" + screen.height + " @ 0,0"
                            + " (state=" + Integer.toHexString(f.getExtendedState()) + ")");
                }
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] resize failed: " + t);
            }
        }
    }
}

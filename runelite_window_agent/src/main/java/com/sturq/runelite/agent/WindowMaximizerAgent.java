package com.sturq.runelite.agent;

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
 * Implementation: a daemon thread that polls Frame.getFrames() and sets
 * MAXIMIZED_BOTH on anything visible that isn't already maximized. Event-
 * listener approach (addAWTEventListener) was unreliable at premain time
 * because AWT isn't fully initialized yet. Polling is dumb but robust.
 */
public class WindowMaximizerAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        startPoller();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        startPoller();
    }

    private static void startPoller() {
        Thread t = new Thread(() -> {
            // Burn the first few seconds with a tighter loop so the first frame opens maxed.
            long deadline = System.currentTimeMillis() + 30_000L;
            while (true) {
                try {
                    sweep();
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
        // DISABLED: posting MouseWheelEvent/MouseEvent from a daemon thread into
        // AWT's event queue was correlating with a SIGSEGV in libjvm.so on the
        // user's device. Until we can run the injection on EDT cleanly without
        // crashing the JVM, leave the input-bridge file watcher off — falls back
        // to whatever path Caciocavallo's AWT bridge handles (which for scroll
        // is none, but at least the JVM stays up).
        // startInputBridge();
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
            if (line.startsWith("WHEEL ")) {
                int ticks = Integer.parseInt(line.substring(6).trim());
                postWheel(ticks);
            } else if (line.equals("RIGHTCLICK")) {
                postRightClick();
            }
        } catch (Throwable t) {
            System.out.println("[WindowMaximizerAgent] bad input line '" + line + "': " + t);
        }
    }

    private static Window pickTargetWindow() {
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (w != null && w.isVisible()) return w;
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) return f;
        }
        return null;
    }

    private static void postWheel(int ticks) {
        Window w = pickTargetWindow();
        if (w == null) return;
        int x = w.getWidth() / 2, y = w.getHeight() / 2;
        long when = System.currentTimeMillis();
        MouseWheelEvent e = new MouseWheelEvent(
                w, MouseEvent.MOUSE_WHEEL, when, 0,
                x, y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                Math.abs(ticks), ticks);
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e);
        System.out.println("[WindowMaximizerAgent] posted WHEEL " + ticks + " to " + w);
    }

    private static void postRightClick() {
        Window w = pickTargetWindow();
        if (w == null) return;
        int x = w.getWidth() / 2, y = w.getHeight() / 2;
        long when = System.currentTimeMillis();
        MouseEvent down = new MouseEvent(w, MouseEvent.MOUSE_PRESSED, when, 0,
                x, y, 1, true, MouseEvent.BUTTON3);
        MouseEvent up = new MouseEvent(w, MouseEvent.MOUSE_RELEASED, when + 1, 0,
                x, y, 1, false, MouseEvent.BUTTON3);
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(down);
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(up);
        System.out.println("[WindowMaximizerAgent] posted RIGHTCLICK to " + w);
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
                // Always call setBounds — don't trust setExtendedState because some
                // Caciocavallo peers report MAXIMIZED_BOTH but don't actually resize.
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

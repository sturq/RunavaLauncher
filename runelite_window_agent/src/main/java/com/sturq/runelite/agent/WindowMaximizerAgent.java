package com.sturq.runelite.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
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
            // Stop after 30s: every JFrame we'll ever see has appeared, and continuing
            // to fight RuneLite's ContainableFrame past that point produces a tug-of-war
            // that piles up AWT events.
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                try { sweepOnEdt(); } catch (Throwable ignored) {}
                try { Thread.sleep(500L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }, "WindowMaximizerAgent");
        t.setDaemon(true);
        t.start();
        startInputBridge();
        System.out.println("[WindowMaximizerAgent] poller started");
    }

    private static void sweepOnEdt() {
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try { sweep(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** Hand sweep() off to the EDT. All Frame.setSize / setExtendedState mutations have
     *  to run on the AWT event-dispatch thread, otherwise they race with paint/layout
     *  in Cacio's component-peer pipeline and reproducibly crash libjvm.so+0xa14ca0.
     *  invokeLater is non-blocking; if the EDT is busy we just skip this tick. */
    private static void sweepOnEdt() {
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try { sweep(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
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
        // Fall back to walking children manually if findComponentAt punted.
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

    /** Last-seen wheel event timestamp. Hard rate-limit: ignore wheel ticks that
     *  arrive within MIN_WHEEL_GAP_MS of the last one. Spamming AWT with many
     *  back-to-back wheel events in a tight burst was reproducibly crashing
     *  libjvm.so+0xa14ca0 — same pc, two captures in a row. Slowing them down
     *  keeps Cacio's component-peer paint path off the EDT-dispatch hot loop. */
    private static volatile long sLastWheelMs = 0L;
    private static final long MIN_WHEEL_GAP_MS = 60L;

    private static void postWheel(final int ticks) {
        long now = System.currentTimeMillis();
        if (now - sLastWheelMs < MIN_WHEEL_GAP_MS) {
            // Drop. The Android side spams a tick per pinch frame; ~16 ticks/sec is plenty.
            return;
        }
        sLastWheelMs = now;
        // Defer to EDT — concurrent AWT mutation from the daemon thread that polls
        // the file was triggering a SIGSEGV in libjvm.so previously.
        javax.swing.SwingUtilities.invokeLater(() -> {
            Component target = pickWheelTarget();
            if (target == null) return;
            // Coords are in the target Component's coord space — center it.
            int x = target.getWidth() / 2, y = target.getHeight() / 2;
            long when = System.currentTimeMillis();
            MouseWheelEvent e = new MouseWheelEvent(
                    target, MouseEvent.MOUSE_WHEEL, when, 0,
                    x, y, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    Math.abs(ticks), ticks);
            // Post via the system event queue rather than calling Component.dispatchEvent
            // directly. Direct dispatch fires listeners synchronously and bypasses the
            // EDT's serialization with Cacio's component-peer paint path — that race
            // was reproducibly faulting libjvm.so under a burst of pinch ticks (same
            // pc=libjvm.so+0xa14ca0 across runs). postEvent queues the event onto the
            // EDT alongside paint events; AWT serializes them naturally.
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e);
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] postEvent WHEEL failed: " + t);
            }
            System.out.println("[WindowMaximizerAgent] posted WHEEL " + ticks
                    + " to " + target.getClass().getSimpleName());
        });
    }

    private static void postRightClick() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            Component target = pickWheelTarget();
            if (target == null) return;
            int x = target.getWidth() / 2, y = target.getHeight() / 2;
            long when = System.currentTimeMillis();
            MouseEvent down = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when, 0,
                    x, y, 1, true, MouseEvent.BUTTON3);
            MouseEvent up = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when + 1, 0,
                    x, y, 1, false, MouseEvent.BUTTON3);
            try {
                java.awt.EventQueue eq = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
                eq.postEvent(down);
                eq.postEvent(up);
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] postEvent RIGHTCLICK failed: " + t);
            }
            System.out.println("[WindowMaximizerAgent] posted RIGHTCLICK to "
                    + target.getClass().getSimpleName());
        });
    }

    /** Set of Frames we've already maximized at least once. RuneLite's ContainableFrame
     *  resizes the JFrame on its own (sidebar-open / -close especially), and re-maximizing
     *  on every change starts a tug-of-war that piles up AWT events fast enough to fault
     *  libjvm. So: maximize the very first time we see a Frame, then leave it alone. */
    private static final java.util.Set<Frame> sMaximized =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static void sweep() {
        Frame[] frames = Frame.getFrames();
        if (frames.length == 0) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (screen == null || screen.width <= 0 || screen.height <= 0) return;
        for (Frame f : frames) {
            if (f == null) continue;
            if (!f.isVisible()) continue;
            if (sMaximized.contains(f)) continue;
            try {
                int curW = f.getWidth();
                int curH = f.getHeight();
                int curX = f.getX();
                int curY = f.getY();
                // Clamp into Cacio's managed-screen bounds. RuneLite opens at
                // 767x528 @ 511,41 with a 1278x568 Cacio screen, so the bottom-right
                // corner sits at (1278, 569) — one pixel below the screen height.
                // Cacio's native peer-paint buffer is sized exactly to the screen,
                // and any pixel outside it is an OOB write that segfaults libjvm
                // at +0xa14ca0 (five identical captures, varying triggers).
                //
                // setBounds is one call so it doesn't cross the EDT boundary mid-
                // mutation. We deliberately don't call setExtendedState (that path
                // is what tripped the early MAXIMIZED_BOTH crashes) or validate
                // (let AWT re-layout naturally on the size change).
                if (curW != screen.width || curH != screen.height || curX != 0 || curY != 0) {
                    f.setBounds(0, 0, screen.width, screen.height);
                    System.out.println("[WindowMaximizerAgent] setBounds '" + f.getTitle()
                            + "' " + curW + "x" + curH + " @ " + curX + "," + curY
                            + " -> " + screen.width + "x" + screen.height + " @ 0,0");
                }
                sMaximized.add(f);
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] setBounds failed: " + t);
            }
        }
    }
}

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
     *  the agent doesn't touch AWT - no resizes, no wheel events, no clicks.
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
                    // Don't let the poller die - AWT may still be coming up.
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
        System.out.println("[WindowMaximizerAgent] agent build=audio-probe-v2");
        startInputBridge();
        startStaleRepaintNudger();
        probeAudioSpi();
        System.out.println("[WindowMaximizerAgent] poller started");
    }

    /** Log every javax.sound.sampled.spi.MixerProvider the JRE can find. If our
     *  RunavaAudioMixerProvider is in the list, the -Xbootclasspath/a: wiring
     *  is good and audio should be working. If only the JRE's own stubs
     *  show up, the SPI scan never saw our jar - that's the layer to fix. */
    private static void probeAudioSpi() {
        try {
            java.util.ServiceLoader<javax.sound.sampled.spi.MixerProvider> loader =
                    java.util.ServiceLoader.load(javax.sound.sampled.spi.MixerProvider.class);
            int found = 0;
            boolean sawRunava = false;
            for (javax.sound.sampled.spi.MixerProvider p : loader) {
                found++;
                String klass = p.getClass().getName();
                if (klass.contains("Runava")) sawRunava = true;
                System.out.println("[WindowMaximizerAgent] mixerprovider: " + klass);
                try {
                    for (javax.sound.sampled.Mixer.Info info : p.getMixerInfo()) {
                        System.out.println("[WindowMaximizerAgent]   mixer: " + info.getName()
                                + " / " + info.getVendor() + " / " + info.getDescription());
                    }
                } catch (Throwable t) {
                    System.out.println("[WindowMaximizerAgent]   getMixerInfo failed: " + t);
                }
            }
            System.out.println("[WindowMaximizerAgent] mixerproviders found=" + found
                    + " runava=" + sawRunava);
        } catch (Throwable t) {
            System.out.println("[WindowMaximizerAgent] audio SPI probe failed: " + t);
        }
    }

    /** Force a full-frame repaint on every visible JFrame periodically. Cacio's
     *  TTA backend only composites a component's pixels onto the managed-screen
     *  bitmap when the component reports a dirty region. RuneLite's plugin
     *  sidebar icons only repaint themselves on state changes - between those,
     *  their region stays "clean" in Cacio's view and our AWTCanvasView reads
     *  stale pixels for that area. Result: icons appear to disappear until you
     *  click and the click handler triggers a repaint.
     *
     *  The original implementation pulsed at 10 Hz which was wildly overkill -
     *  icons rarely go stale, and 10 forced full-frame repaints per second
     *  steal a noticeable slice of EDT/CPU time. 2.5 Hz keeps icons fresh
     *  within ~400ms of any state change without dominating the EDT. */
    private static void startStaleRepaintNudger() {
        javax.swing.Timer timer = new javax.swing.Timer(400, e -> {
            if (isPaused()) return;
            try {
                for (Frame f : Frame.getFrames()) {
                    if (f != null && f.isVisible() && f.isShowing()) {
                        f.repaint();
                    }
                }
            } catch (Throwable ignored) {}
        });
        timer.setRepeats(true);
        // EDT may not be alive yet when the agent's premain runs - kick it from
        // SwingUtilities so the Timer is created on a live EventQueue.
        javax.swing.SwingUtilities.invokeLater(timer::start);
    }

    /** File-based IPC for input events the Activity can't deliver via the AWT
     *  bridge (notably mouse wheel - Caciocavallo's CTCAndroidInput doesn't
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
                    // Always process the file - lifecycle commands like ICONIFY /
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
            if (isPaused()) return;
            if (line.startsWith("WHEEL ")) {
                int ticks = Integer.parseInt(line.substring(6).trim());
                postWheel(ticks);
            } else if (line.equals("RIGHTCLICK")) {
                postRightClick();
            } else if (line.equals("FOCUSGAME")) {
                focusGameCanvas();
            } else if (line.startsWith("RESIZE ")) {
                String[] parts = line.substring(7).trim().split("\\s+");
                if (parts.length == 2) {
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    setTargetSize(w, h);
                }
            }
            // Lifecycle commands (ICONIFY / DEICONIFY / suspend AWT threads) were
            // removed: every attempt to signal AWT on activity pause TRIGGERED the
            // libjvm SIGSEGV we were trying to prevent. The Cacio peer path that
            // setExtendedState reaches hits a use-after-free in JavaThread. The
            // safer move is to do nothing on pause - let RuneLite's repaint timer
            // run, accept Android may freeze the process via its own cached-app
            // freezer, and rely on auto-restart for the rare crashes that still fire.
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
     *  though we're now on the system event queue - defense in depth. */
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

    /** Walk the focused Window's tree for a java.awt.Canvas - RuneLite uses one
     *  for the OSRS game viewport - and pull focus onto it. When the plugin
     *  sidebar is open the search text field auto-focuses; arrow keys from a
     *  camera drag then go to that text field instead of the canvas, so the
     *  camera doesn't move. Activity sends FOCUSGAME at the start of each
     *  camera drag to put focus back on the game. */
    private static Component findGameCanvas(Container c) {
        for (Component child : c.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof java.awt.Canvas) return child;
            if (child instanceof Container) {
                Component found = findGameCanvas((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void focusGameCanvas() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (isPaused()) return;
            try {
                Window w = pickTargetWindow();
                if (!(w instanceof Container)) return;
                Component canvas = findGameCanvas((Container) w);
                if (canvas != null) canvas.requestFocusInWindow();
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] focusGameCanvas failed: " + t);
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

    /** Target frame size set by RESIZE IPC from the Android activity. Defaults
     *  to the Cacio managed screen size (the full square canvas) but the
     *  activity overrides this on every orientation change so the JFrame
     *  fills only the visible portion of the canvas. */
    private static volatile int sTargetW = 0;
    private static volatile int sTargetH = 0;

    private static void setTargetSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        sTargetW = w;
        sTargetH = h;
        // Force an immediate sweep so the user doesn't see the stale aspect
        // for the next poll interval after rotation.
        javax.swing.SwingUtilities.invokeLater(WindowMaximizerAgent::sweep);
    }

    /** Where the game canvas sits on the Cacio screen, as "x y w h".
     *
     *  With the GPU plugin on, that rectangle is drawn by OpenGL into a
     *  separate layer underneath, and Cacio fills the same area with opaque
     *  black — which covers the scene completely. The launcher needs the
     *  rectangle so it can leave a transparent hole there instead of copying
     *  black over the top. It is also most of the screen, so not copying it is
     *  the bulk of the per-frame cost gone.
     *
     *  A file rather than a return value because this runs inside the game JVM
     *  and the reader is Android-side, which is how the existing input IPC
     *  works too. */
    private static void reportGameCanvasBounds() {
        String home = System.getProperty("user.home");
        if (home == null) return;
        try {
            for (Frame f : Frame.getFrames()) {
                if (f == null || !f.isVisible() || !f.isShowing()) continue;
                Component canvas = findGameCanvas(f);
                if (canvas == null || !canvas.isShowing()) continue;
                java.awt.Point p = canvas.getLocationOnScreen();
                String line = p.x + " " + p.y + " " + canvas.getWidth() + " " + canvas.getHeight();
                if (line.equals(sLastCanvasBounds)) return;
                sLastCanvasBounds = line;
                java.io.File out = new java.io.File(home, ".runelitedroid_canvas");
                try (java.io.FileWriter w = new java.io.FileWriter(out, false)) {
                    w.write(line);
                }
                System.out.println("[WindowMaximizerAgent] game canvas at " + line);
                return;
            }
        } catch (Throwable ignored) {}
    }
    private static String sLastCanvasBounds = "";

    private static void sweep() {
        // The canvas rectangle now comes from rlawt, which sees it change in
        // the same frame the scene does. Reporting it from here as well meant
        // two writers to one file and a rectangle that lagged a rotation.
        Frame[] frames = Frame.getFrames();
        if (frames.length == 0) return;
        int targetW = sTargetW;
        int targetH = sTargetH;
        if (targetW <= 0 || targetH <= 0) {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            if (screen == null || screen.width <= 0 || screen.height <= 0) return;
            targetW = screen.width;
            targetH = screen.height;
        }
        for (Frame f : frames) {
            if (f == null) continue;
            // Frame.getFrames() returns only Frames (Dialog extends Window directly,
            // not Frame), so popups (FatalErrorDialog etc.) are never in this array.
            if (!f.isVisible()) continue;
            // Only the window that holds the game. RuneLite's loader puts up a
            // small 'RuneLite Launcher' frame during startup, and this used to
            // stretch that to full screen on every rotation — which is the
            // loading box that covered the menu button, and the JVM stopped
            // dead in the middle of plugin loading right after doing it
            // repeatedly. Nothing here has any business resizing it.
            if (findGameCanvas(f) == null) continue;
            try {
                int curW = f.getWidth();
                int curH = f.getHeight();
                int curX = f.getX();
                int curY = f.getY();
                // Small tolerance - RuneLite's pack() and layout managers
                // sometimes drift the size by a couple of px and we don't
                // want to start a resize-fight that flashes the UI every
                // sweep tick.
                // Size the frame's *content* to the target, not the frame, and
                // hang the decoration off the edges of the screen.
                //
                // Sizing the frame itself left the title bar and the bottom
                // border inside the visible area, where they are black bars
                // above and below the game: 24 rows and 60 rows of a 2244-row
                // screen. Nothing in this project reads Frame.getInsets(), no
                // coordinate maths depends on the decoration, and the only
                // control it offers is a close button that quits RuneLite, so
                // there is nothing to keep.
                //
                // Placing it rather than calling setUndecorated, because that
                // needs the frame non-displayable and so a dispose() - and the
                // canvas hanging off this frame is the one the GPU plugin holds
                // an EGL surface against.
                java.awt.Insets in = f.getInsets();
                int wantX = -in.left;
                int wantY = -in.top;
                int wantW = targetW + in.left + in.right;
                int wantH = targetH + in.top + in.bottom;
                if (Math.abs(curW - wantW) > 4 || Math.abs(curH - wantH) > 4
                        || curX != wantX || curY != wantY) {
                    // Don't MAXIMIZED_BOTH - that snaps the frame to Cacio's
                    // full screen size and undoes our orientation-fit.
                    f.setBounds(wantX, wantY, wantW, wantH);
                    f.validate();
                    System.out.println("[WindowMaximizerAgent] resize '" + f.getTitle()
                            + "' " + curW + "x" + curH + " @ " + curX + "," + curY
                            + " -> " + wantW + "x" + wantH + " @ " + wantX + "," + wantY
                            + " (insets " + in.top + "," + in.left + "," + in.bottom + "," + in.right
                            + " content " + targetW + "x" + targetH + ")");
                }
            } catch (Throwable t) {
                System.out.println("[WindowMaximizerAgent] resize failed: " + t);
            }
        }
    }
}

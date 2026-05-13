package com.sturq.runelite.agent;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
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
        System.out.println("[WindowMaximizerAgent] poller started");
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

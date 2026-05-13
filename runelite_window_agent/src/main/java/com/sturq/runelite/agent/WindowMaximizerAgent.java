package com.sturq.runelite.agent;

import java.awt.AWTEvent;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;

/**
 * Java agent that maximizes top-level Swing/AWT Frames as soon as they open.
 * Loaded via -javaagent:runelite_window_agent.jar in the RuneLite launch path,
 * so RuneLite's main JFrame fills the Caciocavallo virtual screen instead of
 * sitting at its default ~960x600 size with black dead space around it.
 *
 * Skips dialogs (FatalErrorDialog, settings popups) — those should keep their
 * natural size.
 */
public class WindowMaximizerAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        install();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install();
    }

    private static void install() {
        try {
            Toolkit tk = Toolkit.getDefaultToolkit();
            tk.addAWTEventListener(new AWTEventListener() {
                @Override
                public void eventDispatched(AWTEvent event) {
                    if (!(event instanceof WindowEvent)) return;
                    int id = event.getID();
                    if (id != WindowEvent.WINDOW_OPENED && id != WindowEvent.WINDOW_ACTIVATED) return;
                    Window window = ((WindowEvent) event).getWindow();
                    if (!(window instanceof Frame)) return; // skip JDialog etc.
                    Frame frame = (Frame) window;
                    try {
                        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) {
                            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                            frame.setBounds(0, 0, screen.width, screen.height);
                            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
                        }
                    } catch (Throwable ignored) {
                        // Headless / size policies — best-effort.
                    }
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        } catch (Throwable t) {
            // Don't break the JVM if AWT isn't fully initialized at premain time.
            System.err.println("WindowMaximizerAgent install failed: " + t);
        }
    }
}

package net.kdt.pojavlaunch;

/**
 * Native entry point for the desktop GL capability probe.
 *
 * Separate from JREUtils on purpose. JREUtils loads libpojavexec from a static
 * initializer, and touching it while the application class is still bringing the
 * process up gives "recursive attempt to load library" and the receiver never
 * gets instantiated. The load here happens on first use instead, by which point
 * the process is fully up.
 */
public final class GlProbe {
    private static boolean loaded;

    private GlProbe() {}

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        System.loadLibrary("pojavexec");
        loaded = true;
    }

    public static native String probeDesktopGL(String nativeLibraryDir, android.view.Surface surface);
}

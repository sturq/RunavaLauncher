package net.kdt.pojavlaunch;

/**
 * Where the Cacio canvas, the scene drawable and the screen agree on sizes.
 *
 * Deliberately plain arithmetic with no Android types: this is the part of GPU
 * mode that has been wrong most often, and keeping it here is what makes it
 * checkable without a device. The formula used to live in two places that had
 * to be kept in step by hand, which is how a version of it ended up producing
 * odd edges.
 */
public final class SceneGeometry {
    private SceneGeometry() {}

    /**
     * RuneLite will not lay its window out narrower than this. Given less it
     * pack()s itself back up, and if something keeps resizing it down again the
     * two fight for the whole session.
     */
    public static final int RUNELITE_MIN_WIDTH = 800;

    /**
     * Every render dimension is even. Pojav applies the same rule to all of its
     * own in {@code Tools.getDisplayFriendlyRes}; odd sizes fall naturally out
     * of the aspect division below, and the only GPU-mode configuration that
     * ever put a frame on screen was one where both edges happened to be even.
     */
    public static int even(int value) {
        if (value < 2) return 2;
        return (value % 2 == 0) ? value : value - 1;
    }

    /**
     * Side of the square Cacio managed screen. Square so both orientations fit
     * without restarting the JVM.
     *
     * Capped at 60% of the longer edge, never below what keeps the shorter
     * visible side above RuneLite's minimum. Both renderers use this: fewer,
     * larger pixels scaled up to the screen make the interface readable on a
     * phone, and cost both the client and the per-frame copy proportionally
     * less. Drawing at the display's own resolution instead is sharper and
     * leaves the HUD too small to use.
     */
    public static int cacioSquare(int longEdge, int shortEdge) {
        int minForRuneLite = RUNELITE_MIN_WIDTH * longEdge / Math.max(1, shortEdge);
        return even(Math.max((longEdge * 3) / 5, minForRuneLite));
    }

    /** Visible width of that square for a view of the given size. */
    public static int visibleWidth(int square, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return even(square);
        int w = viewWidth >= viewHeight ? square : square * viewWidth / viewHeight;
        return even(w);
    }

    /** Visible height of that square for a view of the given size. */
    public static int visibleHeight(int square, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return even(square);
        int h = viewWidth >= viewHeight ? square * viewHeight / viewWidth : square;
        return even(h);
    }
}

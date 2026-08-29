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
     * GPU mode takes the whole longer edge, because RuneLite's GPU plugin sizes
     * its final blit as canvas x GraphicsConfiguration.getDefaultTransform() and
     * Caciocavallo's transform is fixed at 1:1 — so a canvas smaller than the
     * drawable puts the scene in a corner and leaves the rest black.
     *
     * The software path caps at 60% instead, since there every pixel is
     * rasterised on the CPU, but never below what keeps the shorter visible side
     * above RuneLite's minimum.
     */
    public static int cacioSquare(int longEdge, int shortEdge, boolean gpuMode) {
        int minForRuneLite = RUNELITE_MIN_WIDTH * longEdge / Math.max(1, shortEdge);
        int square = gpuMode ? longEdge : Math.max((longEdge * 3) / 5, minForRuneLite);
        return even(square);
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

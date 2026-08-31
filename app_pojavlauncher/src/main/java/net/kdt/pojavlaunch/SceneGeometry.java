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

    /**
     * The tallest canvas the game will accept. Measured, not looked up: given a
     * content area 983 rows high the canvas came out 983 and filled it, and given
     * one 2219 rows high it came out 2160 and did not. Filling in one case and
     * stopping at a round number in the other is a cap, not a subtraction.
     *
     * It matters because OpenGL anchors its viewport at the bottom of the window
     * while the canvas hangs from the top. A canvas shorter than the window is
     * therefore a scene drawn that much too low: on a 2244-row screen, 2244 - 2160
     * = 84 rows, which is the black bar above the game and the click that lands
     * below the finger. Keeping the drawing surface inside the cap removes both,
     * because then the canvas fills it and there is no gap to misalign.
     */
    public static final int CLIENT_MAX_CANVAS_HEIGHT = 2160;

    /**
     * How much smaller than the view the surfaces render. Everything on screen
     * comes out larger by the inverse - game, interface and sidebar together,
     * because both layers are scaled up to the view by the same factor and
     * touch maps through the same numbers. One mechanism, no stacking with the
     * stretched-mode plugin.
     *
     * Bounded below by what the client will lay out, so the effective factor
     * stops at whichever floor is hit first: about 1.26x in portrait, where
     * the width floor binds, and 2x in landscape, where the height floor does,
     * on a 1008x2244 screen.
     */
    public static final float RENDER_SCALE = 0.5f;

    /**
     * The client will not lay its window out shorter than the fixed-mode
     * canvas plus a row of chrome; forcing less starts the same pack() fight
     * the width floor exists for.
     */
    public static final int RUNELITE_MIN_HEIGHT = 504;

    /** The factor actually applied: the requested scale, pushed back up until
     *  both of the client's layout floors are respected, and never above 1. */
    private static float effectiveScale(int fullW, int fullH) {
        float s = RENDER_SCALE;
        if (fullW > 0 && s * fullW < RUNELITE_MIN_WIDTH) s = RUNELITE_MIN_WIDTH / (float) fullW;
        if (fullH > 0 && s * fullH < RUNELITE_MIN_HEIGHT) s = RUNELITE_MIN_HEIGHT / (float) fullH;
        return s > 1f ? 1f : s;
    }

    /** Visible width of that square for a view of the given size. */
    public static int visibleWidth(int square, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return even(square);
        int w = viewWidth >= viewHeight ? square : square * viewWidth / viewHeight;
        int h = viewWidth >= viewHeight ? square * viewHeight / viewWidth : square;
        float s = effectiveScale(w, h);
        w = (int) (w * s + 0.5f);
        h = (int) (h * s + 0.5f);
        if (h > CLIENT_MAX_CANVAS_HEIGHT) w = w * CLIENT_MAX_CANVAS_HEIGHT / h;
        return even(w);
    }

    /** Visible height of that square for a view of the given size. */
    public static int visibleHeight(int square, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return even(square);
        int w = viewWidth >= viewHeight ? square : square * viewWidth / viewHeight;
        int h = viewWidth >= viewHeight ? square * viewHeight / viewWidth : square;
        // Both edges move together so the aspect still matches the view and
        // the surface scales up to it without stretching.
        float s = effectiveScale(w, h);
        h = (int) (h * s + 0.5f);
        if (h > CLIENT_MAX_CANVAS_HEIGHT) h = CLIENT_MAX_CANVAS_HEIGHT;
        return even(h);
    }
}

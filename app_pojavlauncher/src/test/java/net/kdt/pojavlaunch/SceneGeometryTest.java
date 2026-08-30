package net.kdt.pojavlaunch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The checks that would have caught a day of black frames.
 *
 * Every one of these failures looked identical on a phone — a dark screen and
 * somebody saying "still black" — and each cost a full build, transfer, install
 * and launch round to learn nothing. They are arithmetic, so they belong here.
 */
public class SceneGeometryTest {

    // Pixel 8, immersive, and the emulator's default.
    private static final int PIXEL_LONG = 2244, PIXEL_SHORT = 1008;

    @Test
    public void everyEdgeIsEven() {
        {
            int square = SceneGeometry.cacioSquare(PIXEL_LONG, PIXEL_SHORT);
            assertEven("square", square);
            // Both orientations: the view is the screen either way round.
            assertEven("portrait width",
                    SceneGeometry.visibleWidth(square, PIXEL_SHORT, PIXEL_LONG));
            assertEven("portrait height",
                    SceneGeometry.visibleHeight(square, PIXEL_SHORT, PIXEL_LONG));
            assertEven("landscape width",
                    SceneGeometry.visibleWidth(square, PIXEL_LONG, PIXEL_SHORT));
            assertEven("landscape height",
                    SceneGeometry.visibleHeight(square, PIXEL_LONG, PIXEL_SHORT));
        }
    }

    /**
     * Both renderers draw smaller than the screen and are scaled up. That is
     * what makes the interface readable on a phone, and it is why the two
     * layers need the same factor: the AWT layer gets it from its own smaller
     * buffer, the scene layer from a composite transform.
     */
    @Test
    public void everythingIsScaledUp() {
        int square = SceneGeometry.cacioSquare(PIXEL_LONG, PIXEL_SHORT);
        int visW = SceneGeometry.visibleWidth(square, PIXEL_SHORT, PIXEL_LONG);
        int visH = SceneGeometry.visibleHeight(square, PIXEL_SHORT, PIXEL_LONG);
        assertTrue("visible region " + visW + "x" + visH + " is not smaller than the screen",
                visW < PIXEL_SHORT && visH <= PIXEL_LONG);
        double sx = (double) PIXEL_SHORT / visW, sy = (double) PIXEL_LONG / visH;
        assertTrue("the two axes scale differently: " + sx + " vs " + sy,
                Math.abs(sx - sy) < 0.02);
    }

    /**
     * The software path scales down for CPU cost, but never so far that
     * RuneLite refuses the width — that is the resize war, hundreds of
     * WindowMaximizerAgent lines per session.
     */
    @Test
    public void softwareModeStaysAboveRuneLitesFloor() {
        int square = SceneGeometry.cacioSquare(PIXEL_LONG, PIXEL_SHORT);
        int shortSide = Math.min(
                SceneGeometry.visibleWidth(square, PIXEL_SHORT, PIXEL_LONG),
                SceneGeometry.visibleHeight(square, PIXEL_LONG, PIXEL_SHORT));
        // Even-rounding may shave one pixel off the floor; that is fine, a
        // whole window of slack is not.
        assertTrue("shorter visible side " + shortSide + " is below RuneLite's floor",
                shortSide >= SceneGeometry.RUNELITE_MIN_WIDTH - 2);
    }

    /** The visible region keeps the view's shape, or the picture is stretched. */
    @Test
    public void aspectSurvives() {
        int square = SceneGeometry.cacioSquare(PIXEL_LONG, PIXEL_SHORT);
        double view = (double) PIXEL_SHORT / PIXEL_LONG;
        double visible = (double) SceneGeometry.visibleWidth(square, PIXEL_SHORT, PIXEL_LONG)
                / SceneGeometry.visibleHeight(square, PIXEL_SHORT, PIXEL_LONG);
        assertTrue("aspect drifted: view " + view + " vs visible " + visible,
                Math.abs(view - visible) < 0.01);
    }

    /** A view that has not been measured yet must not produce a zero edge. */
    @Test
    public void unmeasuredViewIsSurvivable() {
        int square = SceneGeometry.cacioSquare(PIXEL_LONG, PIXEL_SHORT);
        assertTrue(SceneGeometry.visibleWidth(square, 0, 0) >= 2);
        assertTrue(SceneGeometry.visibleHeight(square, 0, 0) >= 2);
        assertEquals(2, SceneGeometry.even(1));
        assertEquals(2, SceneGeometry.even(0));
    }

    private static void assertEven(String what, int value) {
        assertTrue(what + " is odd: " + value, value % 2 == 0);
    }

    /** A portrait view taller than the client's canvas cap must come back inside
     *  it, with both edges scaled together so the aspect still matches the view.
     *  Left uncapped, the canvas stops at 2160 inside a 2244-row window and the
     *  scene is drawn 84 rows low - the black bar and the click offset. */
    @Test
    public void visibleRegionStaysInsideTheClientCanvasCap() {
        int square = SceneGeometry.even(2244);
        int w = SceneGeometry.visibleWidth(square, 1008, 2244);
        int h = SceneGeometry.visibleHeight(square, 1008, 2244);
        assertTrue("height must not exceed the cap, was " + h,
                h <= SceneGeometry.CLIENT_MAX_CANVAS_HEIGHT);
        assertEquals("height should sit at the cap", 2160, h);
        double viewAspect = 1008.0 / 2244.0;
        double gotAspect = (double) w / (double) h;
        assertTrue("aspect " + gotAspect + " should match the view's " + viewAspect,
                Math.abs(gotAspect - viewAspect) < 0.01);
    }

    /** Landscape is already inside the cap and must not be touched: the canvas
     *  filled a 983-row content area exactly when measured. */
    @Test
    public void landscapeIsLeftAlone() {
        int square = SceneGeometry.even(2244);
        assertEquals(2244, SceneGeometry.visibleWidth(square, 2244, 1008));
        assertEquals(1008, SceneGeometry.visibleHeight(square, 2244, 1008));
    }
}

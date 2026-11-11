package org.videolan.vlc.gui.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLC Redesign: the interface size found for a screen (Settings > Interface > Interface size, "Automatic"): the
 * design's 1920 page over the page the screen suits, a third finer than the system's own, between 1280 and 1920 dp,
 * to 5 %. The same rule on every app.
 */
class RdInterfaceScaleTest {

    @Test
    fun aTvIsOneAndAHalfTimesTheDesignWhateverItsPanel() {
        // a TV is 960 dp across to the system: 1080p at 320 dpi, 4K at 640, 720p at 213
        assertEquals(150, rdAutoInterfaceScale(1920, 320))
        assertEquals(150, rdAutoInterfaceScale(3840, 640))
        assertEquals(150, rdAutoInterfaceScale(1280, 213))
    }

    @Test
    fun aTabletIsALittleLargerThanTheDesign() {
        // 1280 dp across (1920 px at 240 dpi): a 1706 dp page
        assertEquals(115, rdAutoInterfaceScale(1920, 240))
        // 1340 dp across (2560 px at 306 dpi... a large tablet): a 1784 dp page
        assertEquals(110, rdAutoInterfaceScale(2560, 306))
    }

    @Test
    fun aDesktopLikeScreenIsTheDesignItself() {
        assertEquals(100, rdAutoInterfaceScale(1920, 160))
        assertEquals(100, rdAutoInterfaceScale(3840, 160))
        assertEquals(100, rdAutoInterfaceScale(2560, 213))
    }

    @Test
    fun aSmallScreenNeverGetsLessThanA1280Page() {
        // a phone on its side, 800 dp across: 150 %, not more
        assertEquals(150, rdAutoInterfaceScale(2400, 480))
        assertEquals(150, rdAutoInterfaceScale(800, 160))
    }

    @Test
    fun anUnknownScreenIsTheDesign() {
        assertEquals(100, rdAutoInterfaceScale(0, 320))
        assertEquals(100, rdAutoInterfaceScale(1920, 0))
    }

    @Test
    fun everySizeFoundIsOneTheRuleAllows() {
        for (px in intArrayOf(800, 1280, 1920, 2560, 3840)) for (dpi in intArrayOf(120, 160, 213, 240, 320, 480, 640)) {
            val size = rdAutoInterfaceScale(px, dpi)
            assertTrue("$px px at $dpi dpi: $size", size in 100..150 && size % 5 == 0)
        }
    }
}

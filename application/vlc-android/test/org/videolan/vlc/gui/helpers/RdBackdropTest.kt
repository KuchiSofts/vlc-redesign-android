package org.videolan.vlc.gui.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * VLC Redesign: the veil over the blurred cover of the music player. White texts read at 4.5:1 on the bright parts
 * of any cover; a dark cover keeps the veil of the design.
 */
class RdBackdropTest {

    /** The contrast (WCAG) of white on a part of [luminance] seen through a black veil of [veil] */
    private fun contrastOfWhite(luminance: Double, veil: Double): Double =
        1.05 / (luminance * (1 - veil).pow(2.2) + 0.05)

    @Test
    fun luminanceIsTheOneOfWcag() {
        assertEquals(0.0, RdBackdrop.luminance(0, 0, 0), 1e-9)
        assertEquals(1.0, RdBackdrop.luminance(255, 255, 255), 1e-9)
        // #969696, the grey of the design's titles
        assertEquals(0.3050, RdBackdrop.luminance(150, 150, 150), 0.001)
        assertTrue(RdBackdrop.luminance(0, 255, 0) > RdBackdrop.luminance(255, 0, 0))
        assertTrue(RdBackdrop.luminance(255, 0, 0) > RdBackdrop.luminance(0, 0, 255))
    }

    @Test
    fun theBrightPartsAreTheBrightTenthNotAHighlight() {
        // one white dot on a black cover: not a bright cover
        val dot = DoubleArray(144) { if (it == 0) 1.0 else 0.0 }
        assertEquals(0.0, RdBackdrop.brightParts(dot), 1e-9)
        // a fifth of the cover is white (a sky): it is
        val sky = DoubleArray(144) { if (it < 29) 1.0 else 0.05 }
        assertEquals(1.0, RdBackdrop.brightParts(sky), 1e-9)
        assertEquals(-1.0, RdBackdrop.brightParts(DoubleArray(0)), 1e-9)
        // from pixels
        assertEquals(1.0, RdBackdrop.brightParts(IntArray(144) { 0xFFFFFFFF.toInt() }), 1e-9)
        assertEquals(0.0, RdBackdrop.brightParts(IntArray(144) { 0xFF000000.toInt() }), 1e-9)
    }

    @Test
    fun aDarkCoverKeepsTheVeilOfTheDesign() {
        assertEquals(0.4, RdBackdrop.veil(0.0), 1e-9)
        assertEquals(0.4, RdBackdrop.veil(0.1), 1e-9)
        assertEquals(0.4, RdBackdrop.veil(-1.0), 1e-9)
        // (a medium one too: the veil of the design is enough for it)
        assertEquals(0.4, RdBackdrop.veil(0.5), 1e-9)
    }

    @Test
    fun whiteReadsOnTheBrightPartsOfAnyCover() {
        var bright = 0.0
        while (bright <= 1.0) {
            val veil = RdBackdrop.veil(bright)
            assertTrue("bright $bright veil $veil", veil in 0.4..0.6)
            assertTrue("bright $bright: ${contrastOfWhite(bright, veil)}", contrastOfWhite(bright, veil) >= 4.49)
            bright += 0.05
        }
        // a white cover: 2.9:1 under the old veil, 4.5:1 now
        assertTrue(contrastOfWhite(1.0, 0.4) < 3.0)
        assertEquals(4.5, contrastOfWhite(1.0, RdBackdrop.veil(1.0)), 0.02)
    }

    @Test
    fun withoutAVeilOfTheDesignItIsOnlyWhatTheTextsNeed() {
        // the desktop: no veil over a dark cover
        assertEquals(0.0, RdBackdrop.veil(0.1, 0.0), 1e-9)
        assertEquals(0.5375, RdBackdrop.veil(1.0, 0.0), 0.001)
    }
}

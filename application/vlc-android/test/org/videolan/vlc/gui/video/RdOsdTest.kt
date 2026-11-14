package org.videolan.vlc.gui.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** VLC Redesign: On-Screen Messages (RdOsd), the desktop's osd-duration */
class RdOsdTest {

    @Test
    fun theDefaultChangesNothing() {
        assertEquals(1000L, RdOsd.duration(1000, RdOsd.DEFAULT_TENTHS))
        assertEquals(5000L, RdOsd.duration(5000, RdOsd.DEFAULT_TENTHS))
    }

    @Test
    fun everyMessageKeepsItsShare() {
        assertEquals(2500L, RdOsd.duration(1000, 25))
        assertEquals(12500L, RdOsd.duration(5000, 25))
        assertEquals(100L, RdOsd.duration(1000, 1))
    }

    @Test
    fun outOfRangeIsHeldToTheDesktopsRange() {
        assertEquals(100L, RdOsd.duration(1000, 0))
        assertEquals(10000L, RdOsd.duration(1000, 500))
    }

    @Test
    fun secondsInTheLanguagesNumbers() {
        assertEquals("1.5", RdOsd.secondsText(15, Locale.US))
        assertEquals("1,5", RdOsd.secondsText(15, Locale.GERMANY))
        assertEquals("10.0", RdOsd.secondsText(100, Locale.US))
    }
}

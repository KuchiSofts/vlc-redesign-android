package org.videolan.vlc.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** VLC Redesign: the default subtitle delay (RdSubDelay), the desktop's sub-delay */
class RdSubDelayTest {

    @Test
    fun microsecondsForLibvlc() {
        assertEquals(0L, RdSubDelay.micros(0))
        assertEquals(1_500_000L, RdSubDelay.micros(15))
        assertEquals(-5_000_000L, RdSubDelay.micros(-50))
        // (the desktop's range: -5 to +5 s)
        assertEquals(5_000_000L, RdSubDelay.micros(999))
    }

    @Test
    fun theRowsText() {
        assertEquals("0", RdSubDelay.text(0, Locale.US))
        assertEquals("+1.5", RdSubDelay.text(15, Locale.US))
        assertEquals("+2", RdSubDelay.text(20, Locale.US))
        assertEquals("−0.5", RdSubDelay.text(-5, Locale.US))
        assertEquals("+1,5", RdSubDelay.text(15, Locale.GERMANY))
    }
}

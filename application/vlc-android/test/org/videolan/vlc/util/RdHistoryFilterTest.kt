package org.videolan.vlc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VLC Redesign: Settings › Privacy › Never remember items matching (RdHistoryFilter) */
class RdHistoryFilterTest {

    @Test
    fun emptyMatchesNothing() {
        assertFalse(RdHistoryFilter.matches("", "file:///sdcard/a.mkv", "a"))
        assertFalse(RdHistoryFilter.matches(null, "file:///sdcard/a.mkv", "a"))
        assertFalse(RdHistoryFilter.matches("   ", "file:///sdcard/a.mkv", "a"))
    }

    @Test
    fun regularExpressionOnAddressOrName() {
        assertTrue(RdHistoryFilter.matches("private|secret", "file:///sdcard/Private/a.mkv", "a"))
        assertTrue(RdHistoryFilter.matches("^Holiday", "content://media/1", "holiday 2026"))
        assertFalse(RdHistoryFilter.matches("^Holiday", "content://media/1", "My holiday"))
        assertTrue(RdHistoryFilter.matches("\\.ts$", "http://example.org/live.ts", null))
    }

    @Test
    fun addressIsReadAsWritten() {
        assertTrue(RdHistoryFilter.matches("My Films", "file:///sdcard/My%20Films/a.mkv", "a"))
        // a "+" of the name stays a "+"
        assertTrue(RdHistoryFilter.matches("C\\+\\+", "file:///sdcard/C%2B%2B%20talk.mp4", null))
    }

    @Test
    fun invalidExpressionIsLookedForAsText() {
        assertTrue(RdHistoryFilter.matches("[draft", "file:///sdcard/[draft] cut.mp4", null))
        assertFalse(RdHistoryFilter.matches("[draft", "file:///sdcard/final.mp4", null))
    }
}

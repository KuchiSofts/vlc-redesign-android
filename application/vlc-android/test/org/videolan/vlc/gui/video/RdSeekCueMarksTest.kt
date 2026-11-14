package org.videolan.vlc.gui.video

import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.vlc.util.SubtitleCues

/** VLC Redesign: Show subtitle lines on the seek bar (RdSeekCueMarks.marks): place, merge, minimum width, delay */
class RdSeekCueMarksTest {

    private fun cue(start: Long, end: Long) = SubtitleCues.Cue(start, end, "line")

    @Test
    fun placedOnTheTrack() {
        // a 100 s video on a 1000 px track starting at 10 px: 1 s = 10 px
        val marks = RdSeekCueMarks.marks(listOf(cue(10_000, 12_000)), 100_000, 0, 10f, 1000f, 0f, 1f, 2f)
        assertEquals(listOf(110f to 20f), marks)
    }

    @Test
    fun closeLinesMergeAndShortOnesKeepAWidth() {
        val marks = RdSeekCueMarks.marks(listOf(cue(10_000, 10_010), cue(10_100, 10_110), cue(50_000, 50_001)),
            100_000, 0, 0f, 1000f, 0f, 1f, 2f)
        // 100.0..100.1 and 101.0..101.1 (1 px apart, under 2 px): one mark of 101.1 - 100 = 1.1 px at least 1 px each
        assertEquals(2, marks.size)
        assertEquals(100f, marks[0].first, 0.01f)
        assertEquals(2f, marks[0].second, 0.01f)
        assertEquals(500f, marks[1].first, 0.01f)
        assertEquals(1f, marks[1].second, 0.01f)
    }

    @Test
    fun onlyWhatIsStillToCome() {
        val lines = listOf(cue(10_000, 20_000), cue(60_000, 61_000))
        // played up to 150 px: the first line is cut there, the second stays
        val marks = RdSeekCueMarks.marks(lines, 100_000, 0, 0f, 1000f, 150f, 1f, 2f)
        assertEquals(listOf(150f to 50f, 600f to 10f), marks)
    }

    @Test
    fun theDelayMovesThem() {
        val marks = RdSeekCueMarks.marks(listOf(cue(10_000, 11_000)), 100_000, 5_000, 0f, 1000f, 0f, 1f, 2f)
        assertEquals(listOf(150f to 10f), marks)
        assertEquals(emptyList<Pair<Float, Float>>(), RdSeekCueMarks.marks(listOf(cue(1, 2)), 0, 0, 0f, 1000f, 0f, 1f, 2f))
    }
}

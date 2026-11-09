package org.videolan.vlc.gui.video

import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.vlc.gui.video.RdKeyFeedbackRules.SeekSum

/**
 * VLC Redesign (#72): what a key or the remote shows in the player instead of the controls: the setting read (its
 * default the controls: nobody's player changes), the seeks pressed in a row added up, the pill's texts.
 */
class RdKeyFeedbackRulesTest {

    @Test
    fun theSetting() {
        assertEquals("controls", RdKeyFeedbackRules.mode(null))
        assertEquals("controls", RdKeyFeedbackRules.mode(""))
        assertEquals("controls", RdKeyFeedbackRules.mode("something else"))
        assertEquals("controls", RdKeyFeedbackRules.mode("controls"))
        assertEquals("progress", RdKeyFeedbackRules.mode("progress"))
        assertEquals("message", RdKeyFeedbackRules.mode("message"))
    }

    @Test
    fun seeksInARowAddUp() {
        val sum = SeekSum(1500)
        assertEquals(10_000L, sum.add(10_000, 1_000))
        assertEquals(20_000L, sum.add(10_000, 2_000))
        assertEquals(30_000L, sum.add(10_000, 3_400))
        // (more than 1.5 s after the last: a new count)
        assertEquals(10_000L, sum.add(10_000, 5_000))
        // (the other way: a new count)
        assertEquals(-10_000L, sum.add(-10_000, 5_200))
        assertEquals(-20_000L, sum.add(-10_000, 5_300))
        sum.reset()
        assertEquals(5_000L, sum.add(5_000, 5_400))
    }

    @Test
    fun aRunSaysHowFarTheVideoReallyMoved() {
        // at 0:05, three jumps of −10 s: the video went to 0:00, −5 s (not −30 s)
        val sum = SeekSum(1500)
        sum.add(-10_000, 1_000, from = 5_000)
        assertEquals(-5_000L, sum.moved(60_000))
        sum.add(-10_000, 1_200, from = 0)
        sum.add(-10_000, 1_400, from = 0)
        assertEquals(-5_000L, sum.moved(60_000))
        // near the end (0:55 of 1:00), three of +10 s: +5 s; the start is the position at the run's first key
        sum.add(10_000, 5_000, from = 55_000)
        sum.add(10_000, 5_100, from = 60_000)
        sum.add(10_000, 5_200, from = 60_000)
        assertEquals(5_000L, sum.moved(60_000))
        // inside the video: the keys' sum; the length unknown: no end to keep it under
        sum.add(10_000, 9_000, from = 20_000)
        sum.add(10_000, 9_100, from = 30_000)
        assertEquals(20_000L, sum.moved(60_000))
        assertEquals(20_000L, sum.moved(0))
        // a new run (the other way) starts from where the video is then
        sum.add(-10_000, 9_200, from = 40_000)
        assertEquals(-10_000L, sum.moved(60_000))
        assertEquals(-5_000L, RdKeyFeedbackRules.realMove(5_000, -30_000, 60_000))
        assertEquals(0L, RdKeyFeedbackRules.realMove(0, -10_000, 60_000))
        assertEquals(3_000L, RdKeyFeedbackRules.realMove(57_000, 30_000, 60_000))
        assertEquals(30_000L, RdKeyFeedbackRules.realMove(57_000, 30_000, 0))
        // no move at all: "0 s", no sign
        assertEquals("0 s", RdKeyFeedbackRules.seekText(0) { "$it s" })
        assertEquals("0 s", RdKeyFeedbackRules.seekText(-400) { "$it s" })
    }

    @Test
    fun thePillsTexts() {
        val seconds = { n: String -> "$n s" }
        assertEquals("+10 s", RdKeyFeedbackRules.seekText(10_000, seconds))
        assertEquals("−10 s", RdKeyFeedbackRules.seekText(-10_000, seconds))
        assertEquals("+59 s", RdKeyFeedbackRules.seekText(59_999, seconds))
        assertEquals("+1:30", RdKeyFeedbackRules.seekText(90_000, seconds))
        assertEquals("−5:00", RdKeyFeedbackRules.seekText(-300_000, seconds))
        assertEquals("+1:02:03", RdKeyFeedbackRules.seekText(3_723_000, seconds))
        // the language's seconds ("10 שנ׳")
        assertEquals("+10 שנ׳", RdKeyFeedbackRules.seekText(10_000) { "$it שנ׳" })
        // the times as the seek bar writes them, the pill as the Samsung TV app's
        assertEquals("00:00:00", RdKeyFeedbackRules.timeText(-5))
        assertEquals("00:01:23", RdKeyFeedbackRules.timeText(83_000))
        assertEquals("01:02:03", RdKeyFeedbackRules.timeText(3_723_000))
        assertEquals("+30 s  00:02:50 / 00:25:40", RdKeyFeedbackRules.seekPill(30_000, 170_000, 1_540_000, seconds))
        assertEquals("+1:10  00:02:50 / 00:25:40", RdKeyFeedbackRules.seekPill(70_000, 170_000, 1_540_000, seconds))
        assertEquals("−10 s  00:02:50", RdKeyFeedbackRules.seekPill(-10_000, 170_000, 0, seconds))
        // a right-to-left language: the signed number and the times kept left to right ("+30", not "30+"), the
        // words around them in the language's direction
        val ltr = { t: String -> "[$t]" }
        assertEquals("[+30] שנ׳  [00:02:50 / 00:25:40]", RdKeyFeedbackRules.seekPill(30_000, 170_000, 1_540_000, { "$it שנ׳" }, ltr))
        assertEquals("[−1:10]  [00:02:50]", RdKeyFeedbackRules.seekPill(-70_000, 170_000, 0, seconds, ltr))
        assertEquals("[+1:02:03]", RdKeyFeedbackRules.seekText(3_723_000, seconds, ltr))
        // the pill read from a sofa: 2.2 % of a TV's height (about 24 px at 1080p, 48 at 2160p); 16 sp elsewhere
        assertEquals(23.76f, RdKeyFeedbackRules.pillTextPx(true, 1080, 32f), 0.01f)
        assertEquals(47.52f, RdKeyFeedbackRules.pillTextPx(true, 2160, 32f), 0.01f)
        assertEquals(15.84f, RdKeyFeedbackRules.pillTextPx(true, 720, 32f), 0.01f)
        assertEquals(12f, RdKeyFeedbackRules.pillTextPx(true, 240, 32f), 0.01f)
        assertEquals(32f, RdKeyFeedbackRules.pillTextPx(false, 1080, 32f), 0.01f)
        assertEquals("1.5×", RdKeyFeedbackRules.rateText(1.5f))
        assertEquals("2×", RdKeyFeedbackRules.rateText(2f))
        assertEquals("0.75×", RdKeyFeedbackRules.rateText(0.75f))
        assertEquals("1.33×", RdKeyFeedbackRules.rateText(1.3333f))
        assertEquals("1×", RdKeyFeedbackRules.rateText(1.0001f))
    }
}

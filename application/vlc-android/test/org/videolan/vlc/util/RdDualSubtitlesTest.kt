package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLC Redesign: the dual subtitles (the desktop's "Show both languages at once"): the second language's lines read
 * from its file (SRT, WebVTT, ASS), the line of the moment with the subtitle delay, which file is the second language's.
 */
class RdDualSubtitlesTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        <i>Hello</i> there

        2
        00:00:04,000 --> 00:00:06,000
        Second line
        on two rows

        3
        00:01:10,250 --> 00:01:12,000
        {\an8}Up top
    """.trimIndent()

    @Test
    fun theLinesOfAnSrtAreReadWithoutTheirTags() {
        val cues = SubtitleCueParser.parse(srt)
        assertEquals(3, cues.size)
        assertEquals(1000L, cues[0].start)
        assertEquals(3500L, cues[0].end)
        assertEquals("Hello there", cues[0].text)
        assertEquals("Second line\non two rows", cues[1].text)
        assertEquals(70250L, cues[2].start)
        assertEquals("Up top", cues[2].text)
    }

    @Test
    fun theLinesOfAWebVttAndAnAssAreRead() {
        val vtt = "WEBVTT\n\n00:00:02.000 --> 00:00:04.000 align:start\nבוקר טוב\n\n01:02.500 --> 01:04.000\nShort time\n"
        val v = SubtitleCueParser.parse(vtt)
        assertEquals(2, v.size)
        assertEquals("בוקר טוב", v[0].text)
        assertEquals(62500L, v[1].start)
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,Hi, you\\Nthere\n"
        val a = SubtitleCueParser.parse(ass)
        assertEquals(1, a.size)
        assertEquals(5100L, a[0].start)
        assertEquals("Hi, you\nthere", a[0].text)
    }

    @Test
    fun theLineOfTheMomentIsShownAndNothingBetweenLines() {
        val cues = SubtitleCueParser.parse(srt)
        assertEquals("", RdDualSubtitles.textAt(cues, 500))
        assertEquals("Hello there", RdDualSubtitles.textAt(cues, 1000))
        assertEquals("Hello there", RdDualSubtitles.textAt(cues, 3499))
        // (its end: gone)
        assertEquals("", RdDualSubtitles.textAt(cues, 3500))
        assertEquals("Second line\non two rows", RdDualSubtitles.textAt(cues, 5000))
        assertEquals("Up top", RdDualSubtitles.textAt(cues, 71000))
        assertEquals("", RdDualSubtitles.textAt(cues, 999_999))
        assertEquals("", RdDualSubtitles.textAt(emptyList(), 1000))
    }

    @Test
    fun linesOnAtTheSameTimeAreShownTogetherInTheirOrder() {
        val cues = listOf(SubtitleCues.Cue(1000, 9000, "Long"), SubtitleCues.Cue(2000, 3000, "Short"), SubtitleCues.Cue(4000, 5000, "Later"))
        assertEquals("Long\nShort", RdDualSubtitles.textAt(cues, 2500))
        assertEquals("Long", RdDualSubtitles.textAt(cues, 3500))
        assertEquals("Long\nLater", RdDualSubtitles.textAt(cues, 4500))
    }

    @Test
    fun theSubtitleDelayMovesTheLinesAsTheFirstLanguages() {
        val cues = SubtitleCueParser.parse(srt)
        // +2 s (later): at 3 s of the video, the file's 1 s
        assertEquals(1000L, RdDualSubtitles.cueTime(3000, 2_000_000))
        assertEquals("Hello there", RdDualSubtitles.textAt(cues, RdDualSubtitles.cueTime(3000, 2_000_000)))
        // −1.5 s (earlier): at 2.5 s of the video, the file's 4 s
        assertEquals("Second line\non two rows", RdDualSubtitles.textAt(cues, RdDualSubtitles.cueTime(2500, -1_500_000)))
        assertEquals(5000L, RdDualSubtitles.cueTime(5000, 0))
    }

    @Test
    fun theSecondLanguagesFileIsADownloadedOneElseOneBesideTheVideo() {
        val downloaded = listOf(Pair("/subs/ice.heb.srt", "he"), Pair("/subs/ice.eng.srt", "en"))
        val beside = listOf(Pair("/films/Ice.en.srt", "en"), Pair("/films/Subs/French.fre.srt", "fr"), Pair("/films/Ice.pt-BR.srt", "pt-BR"))
        assertEquals("/subs/ice.eng.srt", RdDualSubtitles.fileOf("en", downloaded, beside))
        assertEquals("/films/Subs/French.fre.srt", RdDualSubtitles.fileOf("fr", downloaded, beside))
        // (pt-BR is a Portuguese one)
        assertEquals("/films/Ice.pt-BR.srt", RdDualSubtitles.fileOf("pt", downloaded, beside))
        assertNull(RdDualSubtitles.fileOf("ar", downloaded, beside))
        assertNull(RdDualSubtitles.fileOf("", downloaded, beside))
    }

    @Test
    fun theSecondLineSitsAboveTheFirstLanguagesLines() {
        val usual = RdDualSubtitles.bottomOffset(1080, 100, 0, 1080)
        // two lines of a 16th of the picture and a little more
        assertTrue(usual in 190..230)
        // larger subtitles, higher; the subtitles moved up, higher still
        assertTrue(RdDualSubtitles.bottomOffset(1080, 200, 0, 1080) > usual)
        assertTrue(RdDualSubtitles.bottomOffset(1080, 100, 100, 1080) == usual + 100)
        // (a picture drawn at half its size: the margin at half too)
        assertTrue(RdDualSubtitles.bottomOffset(540, 100, 100, 1080) == RdDualSubtitles.bottomOffset(540, 100, 0, 1080) + 50)
        assertEquals(0, RdDualSubtitles.bottomOffset(0, 100, 0, 1080))
    }
}

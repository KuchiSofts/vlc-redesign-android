package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLC Redesign 0.5.9: the subtitle files beside a video, as the desktop reads them: a file that is only forced lines
 * or an advert's few lines is no subtitle of the film (the search still runs); a release's subtitles folder ("Subs/
 * Hebrew.heb.srt") gives the user's language by itself.
 */
class SubtitleFilesTest {

    private fun srt(cues: Int) = (1..cues).joinToString("\n\n") { "$it\n00:00:0$it,000 --> 00:00:0$it,500\nline $it" }.toByteArray()

    @Test
    fun cuesAreCounted() {
        assertEquals(3, OnlineSubtitles.countCues("Film.srt", srt(3)))
        // a UTF-16 file: its zero bytes dropped first
        assertEquals(2, OnlineSubtitles.countCues("Film.srt", String(srt(2)).toByteArray(Charsets.UTF_16LE)))
        assertEquals(2, OnlineSubtitles.countCues("Film.ass", "[Events]\nDialogue: 0,0:00:01.00,a\n  Dialogue: 0,0:00:02.00,b\nComment: x".toByteArray()))
        assertEquals(-1, OnlineSubtitles.countCues("Film.sub", "{1}{2}x".toByteArray()))
    }

    @Test
    fun anAdvertOrForcedLinesAreNoSubtitleOfTheFilm() {
        // the YTS advert: 3 cues for 103 minutes
        assertTrue(OnlineSubtitles.isPartialSubtitleFile("Against.The.Ice.2022.1080p.WEBRip.x264.AAC5.1-[YTS.MX].srt", 3, 103.0))
        assertTrue(OnlineSubtitles.isPartialSubtitleFile("English (Forced).eng.srt", 400, 103.0))
        // Sintel's own: 20 cues for 15 minutes (a short film), a quiet film's 0.5 cue a minute, an unread format
        assertFalse(OnlineSubtitles.isPartialSubtitleFile("Sintel.2010.en.srt", 20, 15.0))
        assertFalse(OnlineSubtitles.isPartialSubtitleFile("All.Is.Lost.srt", 53, 106.0))
        assertFalse(OnlineSubtitles.isPartialSubtitleFile("Film.sub", -1, 120.0))
    }

    @Test
    fun theLanguageAFileNameStates() {
        assertEquals("he", OnlineSubtitles.languageOfSubtitleFile("Hebrew.heb.srt"))
        assertEquals("en", OnlineSubtitles.languageOfSubtitleFile("English (SDH).eng.srt"))
        assertEquals("he", OnlineSubtitles.languageOfSubtitleFile("Film.he.srt"))
        assertEquals("fr", OnlineSubtitles.languageOfSubtitleFile("French.srt"))
        assertEquals("", OnlineSubtitles.languageOfSubtitleFile("Against.The.Ice.2022.1080p.WEBRip.x264.AAC5.1-[YTS.MX].srt"))
        assertEquals("", OnlineSubtitles.languageOfSubtitleFile("Hebrew.heb.txt.mp4"))
    }

    @Test
    fun aSubsFolderGivesThePlainFileOfTheLanguage() {
        val subs = listOf(
            OnlineSubtitles.SubFile("English (Forced).eng.srt", 5),
            OnlineSubtitles.SubFile("English (SDH).eng.srt", 1400),
            OnlineSubtitles.SubFile("English.eng.srt", 1200),
            OnlineSubtitles.SubFile("Hebrew.heb.srt", 980),
            OnlineSubtitles.SubFile("Arabic.ara.srt", 2))
        assertEquals("English.eng.srt", OnlineSubtitles.subsFolderPick(subs, "en", 103.0))
        assertEquals("Hebrew.heb.srt", OnlineSubtitles.subsFolderPick(subs, "he", 103.0))
        // an SDH one when it is the only one; never a forced or a partial one
        assertEquals("English (SDH).eng.srt", OnlineSubtitles.subsFolderPick(subs.filter { it.name != "English.eng.srt" }, "en", 103.0))
        assertNull(OnlineSubtitles.subsFolderPick(subs, "ar", 103.0))
        assertNull(OnlineSubtitles.subsFolderPick(subs, "de", 103.0))
    }
}

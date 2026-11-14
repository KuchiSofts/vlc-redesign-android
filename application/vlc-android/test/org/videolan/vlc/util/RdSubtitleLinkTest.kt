package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VLC Redesign: Add Subtitles › From Link (RdSubtitleLink.check): the desktop's rules for a link */
class RdSubtitleLinkTest {

    @Test
    fun directHttpsSubtitleFiles() {
        assertEquals(RdSubtitleLink.Check.Ok("https://example.org/subs/film.en.srt", "film.en.srt"),
            RdSubtitleLink.check("  https://example.org/subs/film.en.srt "))
        for (ext in listOf("ass", "ssa", "sub", "vtt", "ttml", "smi", "SRT"))
            assertTrue(ext, RdSubtitleLink.check("https://example.org/a.$ext") is RdSubtitleLink.Check.Ok)
    }

    @Test
    fun refused() {
        for (bad in listOf("http://example.org/a.srt", "https://example.org/a.zip", "https://example.org/",
                "https://user:secret@example.org/a.srt", "ftp://example.org/a.srt", "file:///sdcard/a.srt", "not a link", ""))
            assertEquals(bad, RdSubtitleLink.Check.NotSubtitle, RdSubtitleLink.check(bad))
    }

    @Test
    fun theNameIsAFileName() {
        val ok = RdSubtitleLink.check("https://example.org/dir/My%20Film.srt") as RdSubtitleLink.Check.Ok
        assertEquals("My Film.srt", ok.name)
    }
}

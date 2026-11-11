package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.RdSubtitleChoices.Kind
import org.videolan.vlc.util.RdSubtitleChoices.Local
import org.videolan.vlc.util.RdSubtitleChoices.Online
import org.videolan.vlc.util.RdSubtitleChoices.Source
import org.videolan.vlc.util.RdSubtitleChoices.Variant

/**
 * VLC Redesign: the player's Subtitles panel makes its choices as the desktop's SubtitleTracksPanel: the user's
 * languages first, each with its count; the language shown; the media's own tracks before those found online; where
 * each track comes from.
 */
class RdSubtitleChoicesTest {

    private val names = mapOf("he" to "Hebrew", "en" to "English", "fr" to "French", "ar" to "Arabic", "de" to "German", "" to "")
    private fun name(code: String) = names[code] ?: code

    private fun local(id: String, language: String, checked: Boolean = false, kind: Kind = Kind.VIDEO) =
        Local(id, "Track $id", language, checked, Source(kind))

    private fun online(id: String, language: String, provider: String = "OpenSubtitles") = Online(id, "Release $id", language, provider)

    @Test
    fun theUsersLanguagesComeFirstThenThePickedThenTheMediasThenByName() {
        val locals = listOf(local("1", "fr"), local("2", "de"), local("3", ""), local("4", "en", checked = true))
        val online = listOf(online("a", "he"), online("b", "he"), online("c", "ar"), online("d", "en"))
        val list = RdSubtitleChoices.languages(locals, online, listOf("he", "en"), picked = listOf("de"), name = ::name)
        assertEquals(listOf("he", "en", "de", "fr", "ar", ""), list.map { it.code })
        assertEquals(listOf(2, 2, 1, 1, 1, 1), list.map { it.count })
        assertEquals(listOf(0, 1, -1, -1, -1, -1), list.map { it.wanted })
    }

    @Test
    fun theOthersAreSortedByTheirNames() {
        val online = listOf(online("a", "fr"), online("b", "ar"), online("c", "de"))
        val list = RdSubtitleChoices.languages(emptyList(), online, listOf("he", ""), emptyList(), ::name)
        assertEquals(listOf("ar", "fr", "de"), list.map { it.code })
    }

    @Test
    fun theLanguageShownIsTheChosenOneElseTheShownTracksElseTheUsers() {
        val locals = listOf(local("1", "fr"), local("2", "en", checked = true))
        val online = listOf(online("a", "he"))
        val languages = RdSubtitleChoices.languages(locals, online, listOf("he"), emptyList(), ::name)
        assertEquals("fr", RdSubtitleChoices.shownLanguage("fr", languages, locals))
        // a language chosen that no longer has any: the track shown
        assertEquals("en", RdSubtitleChoices.shownLanguage("de", languages, locals))
        // nothing shown: the user's
        val off = locals.map { it.copy(checked = false) }
        assertEquals("he", RdSubtitleChoices.shownLanguage(null, languages, off))
        // no language of the user's: the first
        val other = RdSubtitleChoices.languages(off, emptyList(), listOf("he"), emptyList(), ::name)
        assertEquals("en", RdSubtitleChoices.shownLanguage(null, other, off))
        assertNull(RdSubtitleChoices.shownLanguage(null, emptyList(), emptyList()))
    }

    @Test
    fun aLanguagesSubtitlesAreItsTracksThenThoseFoundOnline() {
        val locals = listOf(local("1", "he", kind = Kind.OPENED), local("2", "en"), local("3", "he"))
        val online = listOf(online("w1", "he", "Wizdom"), online("o1", "en"), online("w2", "he", "Wizdom"))
        val v = RdSubtitleChoices.variants("he", locals, online)
        assertEquals(listOf("1", "3", "w1", "w2"), v.map { if (it is Variant.Track) it.local.id else (it as Variant.Result).online.id })
        assertTrue(RdSubtitleChoices.variants(null, locals, online).isEmpty())
        // a language picked on the left: its track, else its first found online
        assertEquals("1", (RdSubtitleChoices.bestOf("he", locals, online) as Variant.Track).local.id)
        assertEquals("o1", (RdSubtitleChoices.bestOf("en", emptyList(), online) as Variant.Result).online.id)
        assertNull(RdSubtitleChoices.bestOf("fr", locals, online))
    }

    @Test
    fun downloadsReadAsTheDesktopSaysThem() {
        assertEquals(Pair("51.8", true), RdSubtitleChoices.downloadsCount(51760))
        assertEquals(Pair("13.6", true), RdSubtitleChoices.downloadsCount(13649))
        assertEquals(Pair("2", true), RdSubtitleChoices.downloadsCount(1999))
        assertEquals(Pair("243", false), RdSubtitleChoices.downloadsCount(243))
        assertNull(RdSubtitleChoices.downloadsCount(0))
    }

    @Test
    fun aTracksLanguageIsItsCodeElseItsNameElseItsSources() {
        assertEquals("he", RdSubtitleChoices.languageOf("heb", "Track 1"))
        assertEquals("en", RdSubtitleChoices.languageOf("und", "Track 2 - [English]"))
        assertEquals("he", RdSubtitleChoices.languageOf(null, "Track 2 - [Hebrew]"))
        assertEquals("pt-BR", RdSubtitleChoices.languageOf("", "Track 3", "pob"))
        assertEquals("", RdSubtitleChoices.languageOf(null, "Track 4"))
    }

    @Test
    fun theTracksAddedTakeTheFilesInTheirOrder() {
        val pending = mutableListOf(Source(Kind.ONLINE, "Ice.he.srt", "Wizdom", "he"), Source(Kind.OPENED, "Hebrew.heb.srt"))
        val sidecars = mutableListOf(Pair("Ice.en.srt", "en"))
        val languages = mapOf("1" to "fr", "2" to "en", "3" to "he", "4" to "he")
        // the video's own was there first: seen before
        val known = mapOf("1" to Source(Kind.VIDEO))
        val out = RdSubtitleChoices.attribute(listOf("1", "2", "3", "4"), languages, known, pending, sidecars)
        assertEquals(Source(Kind.VIDEO), out["1"])
        // (a track of another language than the file added: the video's own, then the files in their order)
        assertEquals(Source(Kind.OPENED, "Hebrew.heb.srt"), out["2"])
        assertEquals(Source(Kind.ONLINE, "Ice.he.srt", "Wizdom", "he"), out["3"])
        assertEquals(Source(Kind.VIDEO), out["4"])
        assertTrue(pending.isEmpty())
        // a new track of a sidecar's language with nothing pending: that file beside the video
        val again = RdSubtitleChoices.attribute(listOf("5"), mapOf("5" to "en"), emptyMap(), mutableListOf(), sidecars)
        assertEquals(Source(Kind.FILE, "Ice.en.srt", language = "en"), again["5"])
        assertTrue(sidecars.isEmpty())
        // the video's own track of another language appearing before the file added: not taken for it
        val early = mutableListOf(Source(Kind.ONLINE, "Ice.he.srt", "Wizdom", "he"))
        val first = RdSubtitleChoices.attribute(listOf("6"), mapOf("6" to "en"), emptyMap(), early, mutableListOf())
        assertEquals(Source(Kind.VIDEO), first["6"])
        assertEquals(1, early.size)
    }
}

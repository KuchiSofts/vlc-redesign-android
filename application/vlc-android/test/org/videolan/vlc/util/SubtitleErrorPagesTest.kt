package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.vlc.util.OnlineSubtitles.Problem
import org.videolan.vlc.util.OnlineSubtitles.Response
import java.io.File

/**
 * VLC Redesign: an error page in place of a subtitle service's results (a server's or a proxy's HTML page, a browser
 * check, JSON that says its error, plain text, an answer cut short or empty) is that service's failure with its words,
 * shown in the services line, never "nothing found"; what says it found nothing is no failure. The same rule as the
 * desktop's and the Samsung TV app's. Fixtures: test/fixtures/subtitles/errorpages.
 */
class SubtitleErrorPagesTest {

    private fun fixture(name: String): ByteArray = listOf("test/fixtures/subtitles/errorpages/$name", "application/vlc-android/test/fixtures/subtitles/errorpages/$name")
        .map { File(it) }.first { it.exists() }.readBytes()

    private fun ok(name: String) = Response(200, fixture(name), null)

    @Test
    fun errorPagesAreTheServicesFailure() {
        assertEquals(Problem.BadAnswer("SubDL", "Just a moment..."), OnlineSubtitles.answerProblem("SubDL", ok("cloudflare.html")))
        assertEquals(Problem.BadAnswer("SubSource", "502 Bad Gateway"), OnlineSubtitles.answerProblem("SubSource", ok("nginx-502.html")))
        // (no title: its heading; a byte-order mark and entities)
        assertEquals(Problem.BadAnswer("Jimaku", "Down for maintenance & upgrades"), OnlineSubtitles.answerProblem("Jimaku", ok("maintenance.html")))
        // (a page saying "not found" is a server's error in place of JSON, not "nothing found")
        assertEquals(Problem.BadAnswer("Wizdom", "404 Not Found"), OnlineSubtitles.answerProblem("Wizdom", ok("not-found.html")))
        // JSON that says its error: its kind, or its words
        assertEquals(Problem.KeyRefused("SubSource"), OnlineSubtitles.answerProblem("SubSource", ok("key.json")))
        assertEquals(Problem.LoginRefused("Titlovi"), OnlineSubtitles.answerProblem("Titlovi", ok("key.json"), login = true))
        assertEquals(Problem.Busy("OpenSubtitles.com"), OnlineSubtitles.answerProblem("OpenSubtitles.com", ok("limit.json")))
        assertEquals(Problem.BadAnswer("BetaSeries", "Service temporarily unavailable"), OnlineSubtitles.answerProblem("BetaSeries", ok("errors.json")))
        // cut short, empty, text in place of JSON
        assertEquals(Problem.BadAnswer("Addic7ed", ""), OnlineSubtitles.answerProblem("Addic7ed", ok("cut.json")))
        assertEquals(Problem.BadAnswer("Subs.ro", ""), OnlineSubtitles.answerProblem("Subs.ro", Response(200, ByteArray(0), null)))
        assertEquals(Problem.Busy("Assrt"), OnlineSubtitles.answerProblem("Assrt", ok("rate.txt")))
        assertEquals(Problem.BadAnswer("Legendas.net", "Service Unavailable"), OnlineSubtitles.answerProblem("Legendas.net", ok("unavailable.txt")))
    }

    @Test
    fun answersThatReadAreNoFailure() {
        // what says it found nothing, an empty list, "errors": [] and "error": null or false, a list
        assertNull(OnlineSubtitles.answerProblem("SubDL", ok("nothing.json")))
        assertNull(OnlineSubtitles.answerProblem("SubSource", ok("ok-empty.json")))
        assertNull(OnlineSubtitles.answerProblem("BetaSeries", ok("ok-errors-empty.json")))
        assertNull(OnlineSubtitles.answerProblem("Wizdom", ok("ok-list.json")))
        // (a failure of the connection or a status is told by the status, not here)
        assertNull(OnlineSubtitles.answerProblem("SubDL", Response(502, fixture("nginx-502.html"), null)))
        assertNull(OnlineSubtitles.answerProblem("SubDL", Response(0, ByteArray(0), "timeout")))
        // a service of web pages: its own page is its answer, a browser check is its failure
        assertNull(OnlineSubtitles.answerProblem("YIFY", ok("yify-page.html"), html = true))
        assertEquals(Problem.BadAnswer("YIFY", "Just a moment..."), OnlineSubtitles.answerProblem("YIFY", ok("cloudflare.html"), html = true))
        assertEquals(Problem.BadAnswer("YIFY", "502 Bad Gateway"), OnlineSubtitles.answerProblem("YIFY", ok("nginx-502.html"), html = true))
    }

    /** each service's check of an answer of 200: an error page counts (it said nothing before: "nothing found") */
    @Test
    fun eachServiceTellsIt() {
        assertEquals(Problem.BadAnswer("SubSource", "502 Bad Gateway"), KeyedSubtitles.subSourceProblem(ok("nginx-502.html")))
        assertEquals(Problem.BadAnswer("SubDL", "Just a moment..."), KeyedSubtitles.subDlProblem(ok("cloudflare.html")))
        assertNull(KeyedSubtitles.subDlProblem(ok("nothing.json")))
        assertEquals(Problem.BadAnswer("Assrt", "Down for maintenance & upgrades"), KeyedSubtitles.assrtProblem(ok("maintenance.html")))
        assertNull(KeyedSubtitles.assrtProblem(Response(200, """{"status": 0, "sub": {"subs": []}}""".toByteArray(), null)))
        assertEquals(Problem.BadAnswer("Jimaku", "502 Bad Gateway"), KeyedSubtitles.jimakuProblem(ok("nginx-502.html")))
        assertNull(KeyedSubtitles.jimakuProblem(ok("ok-list.json")))
        assertEquals(Problem.BadAnswer("BetaSeries", "Service temporarily unavailable"), KeyedSubtitles.betaSeriesProblem(ok("errors.json")))
        assertNull(KeyedSubtitles.betaSeriesProblem(ok("ok-errors-empty.json")))
        // (its "nothing of that id" stays nothing found)
        assertNull(KeyedSubtitles.betaSeriesProblem(Response(200, """{"errors": [{"code": 4001, "text": "No show"}]}""".toByteArray(), null)))
        assertEquals(Problem.KeyRefused("Subs.ro"), KeyedSubtitles.subsRoProblem(ok("key.json")))
        assertNull(KeyedSubtitles.subsRoProblem(Response(200, """{"status": 200, "items": []}""".toByteArray(), null)))
    }
}

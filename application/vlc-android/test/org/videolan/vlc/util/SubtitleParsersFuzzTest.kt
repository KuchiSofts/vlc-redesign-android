package org.videolan.vlc.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.OnlineSubtitles.Query
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * VLC Redesign, online subtitles: the services' answers and the downloads fed what a broken or hostile server may
 * send (random text, cut and mutated JSON, numbers out of range, nulls, corrupt and huge archives, a zip-bomb): no
 * exception may escape, nothing may hang, and an archive never unpacks past its limit.
 */
class SubtitleParsersFuzzTest {

    private val r = Random(59)

    init {
        OnlineSubtitles.warn = { }
    }
    private val q = Query(imdb = "tt0133093", title = "The Voyage", year = 1999, languages = listOf("en", "he"))
    private val ep = Query(imdb = "tt0903747", title = "Northern Bay", series = true, season = 1, episode = 2, languages = listOf("en"))
    private val noise = "{}[]\":,\\0123456789-+.eEnulltruefalse<>/=&;\n\r\t ÿ€\u0000�".toCharArray()

    private val samples = listOf(
        """{"data":[{"id":1,"attributes":{"language":"en","release":"The.Voyage.1999.1080p","files":[{"file_id":5,"file_name":"a.srt"}],"download_count":9}}]}""",
        """{"subtitles":[{"id":"1","lang":"English","release_name":"x","url":"/s/1.zip","season":1,"episode":2,"releases":["a","b"]}],"status":true}""",
        """{"results":[{"subtitleId":7,"language":"arabic","releaseInfo":["The.Voyage"],"hearingImpaired":0,"files":3}],"movie":{"id":3}}""",
        """{"status":0,"sub":{"subs":[{"id":11,"videoname":"x","native_name":"y","lang":{"langlist":{"langchs":true}},"filelist":[{"f":"a.ass","url":"u"}]}]}}""",
        """[{"id":1,"name":"Show","entries":[{"id":2,"name":"x.srt","url":"u","last_modified":"2026-11-08T10:00:00Z"}]}]""",
        """{"show":{"id":5},"subtitles":[{"id":1,"language":"VF","source":"addic7ed","file":"x.srt","url":"u","episode":{"season":1,"episode":2}}]}""",
        """{"status":200,"items":[{"id":1,"title":"x","year":1999,"language":"ro","downloadLink":"u","translator":"t"}]}""",
        """{"data":[{"id":1,"title":"x","language":"pt-BR","downloads":3,"files":[{"file":"a.srt"}]}],"success":true}""",
        """{"SubtitleResults":[{"Id":1,"Lang":"hr","Release":"x","Link":"u","Season":1,"Episode":2}],"ResultsFound":1}""")

    private fun mutate(s: String): String {
        val b = StringBuilder(s)
        when (r.nextInt(5)) {
            0 -> b.setLength(r.nextInt(b.length))
            1 -> repeat(1 + r.nextInt(8)) { b.setCharAt(r.nextInt(b.length), noise[r.nextInt(noise.size)]) }
            2 -> return s.replace(Regex("\\d+")) { listOf("99999999999999999999", "-1", "1e309", "NaN", "")[r.nextInt(5)] }
            3 -> return s.replace(Regex(":\\s*(\"[^\"]*\"|\\d+|true|false)")) { listOf(": null", ": []", ": {}", ": \"\"")[r.nextInt(4)] }
            else -> b.insert(r.nextInt(b.length + 1), String(CharArray(1 + r.nextInt(30)) { noise[r.nextInt(noise.size)] }))
        }
        return b.toString()
    }

    private fun inputs() = listOf("", "null", "[]", "{}", "{\"data\":null}", "<html>busy</html>") +
        List(600) { mutate(samples[r.nextInt(samples.size)]) } + List(100) { String(CharArray(r.nextInt(200)) { noise[r.nextInt(noise.size)] }) }

    private inline fun quick(what: String, block: () -> Unit) {
        val t = System.nanoTime()
        block()
        assertTrue(what, (System.nanoTime() - t) / 1_000_000 < 2000)
    }

    @Test
    fun keyedServicesAnswers() {
        for (s in inputs()) quick("keyed") {
            for (query in listOf(q, ep)) {
                KeyedSubtitles.subSourceMovie(s, query); KeyedSubtitles.parseSubSource(s, query); KeyedSubtitles.parseSubDl(s, query)
                KeyedSubtitles.parseTitlovi(s, query); KeyedSubtitles.parseAssrt(s, query); KeyedSubtitles.jimakuEntry(s, query)
                KeyedSubtitles.parseSubsRo(s, query); KeyedSubtitles.parseLegendas(s, query)
            }
            KeyedSubtitles.assrtFile(s, 1, 2); KeyedSubtitles.parseJimaku(s, 1); KeyedSubtitles.betaSeriesShow(s)
            KeyedSubtitles.parseBetaSeries(s); KeyedSubtitles.isoTime(s)
            OnlineSubtitles.parseName(s, s); OnlineSubtitles.simplified(s); OnlineSubtitles.normalize(s)
            OnlineSubtitles.titleSimilarity(s, "The Voyage")
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return o.toByteArray()
    }

    private fun gzip(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        GZIPOutputStream(o).use { it.write(d) }
        return o.toByteArray()
    }

    @Test
    fun downloadsThatAreBrokenOrHostile() {
        val result = OnlineSubtitles.Result(id = "1", provider = "opensubtitles", language = "en", release = "The.Voyage", fileName = "x.srt", url = "u")
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()
        val cases = ArrayList<ByteArray>()
        cases += ByteArray(0)
        cases += "Rar!xxxx".toByteArray()
        cases += byteArrayOf(0x1F, 0x8B.toByte(), 1, 2, 3)                                 // a gzip cut short
        cases += byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 9, 9, 9)            // a zip cut short
        cases += zip()                                                                      // an empty zip
        cases += zip("../../evil.srt" to srt, "folder/" to ByteArray(0))                    // zip-slip names
        cases += zip("a.nfo" to "nfo".toByteArray(), "b.jpg" to ByteArray(100))             // no subtitle in it
        cases += zip("big.srt" to ByteArray(40 shl 20) { 'a'.code.toByte() })              // 40 MB of one letter
        cases += gzip(ByteArray(60 shl 20))                                                 // a gzip bomb (60 MB of zeros)
        cases += ByteArray(5 shl 20).also { r.nextBytes(it) }                               // 5 MB of noise
        repeat(200) { cases += ByteArray(r.nextInt(2048)).also { b -> r.nextBytes(b) } }
        val z = zip("x.srt" to srt)
        repeat(200) { cases += z.copyOf().also { b -> repeat(4) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() } } }
        for (c in cases) quick("download of ${c.size} bytes") {
            val (u, p) = OnlineSubtitles.subtitleOf(c, result, "The.Voyage.1999.mkv")
            assertTrue(u != null || p != null)
            // never more text than the limit
            if (u != null) assertTrue("${u.text.length}", u.text.length <= 32 shl 20)
        }
        // a good one still unpacks
        assertNotNull(OnlineSubtitles.subtitleOf(zip("x.srt" to srt), result, "The.Voyage.1999.mkv").first)
    }

    /** a release name cut to fit a file name: by its UTF-8 bytes (80 Chinese characters were 240 bytes: ENAMETOOLONG) */
    @Test
    fun aReleaseNameThatFitsAFileName() {
        val chinese = "电".repeat(80)
        val cut = OnlineSubtitles.utf8Prefix(chinese, 150)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 150)
        org.junit.Assert.assertEquals(50, cut.length)
        org.junit.Assert.assertEquals("The.Voyage", OnlineSubtitles.utf8Prefix("The.Voyage", 150))
        // (a character of four bytes is never cut in two)
        val emoji = "a😀b"
        org.junit.Assert.assertEquals("a", OnlineSubtitles.utf8Prefix(emoji, 4))
        org.junit.Assert.assertEquals("a😀", OnlineSubtitles.utf8Prefix(emoji, 5))
    }

    @Test
    fun charsetsOfAnything() {
        repeat(1500) {
            val b = ByteArray(r.nextInt(4096)).also { r.nextBytes(it) }
            if (r.nextInt(4) == 0 && b.size > 4) { b[0] = 0xFF.toByte(); b[1] = 0xFE.toByte() }
            SubtitleCharsetDetector.detect(b, listOf("he", "ar", "ru", "zh", "ja", "el")[r.nextInt(6)].let { listOf(it) }, null)
            SubtitleCharsetDetector.detect(b, emptyList(), "UTF-8")
        }
        val t = System.nanoTime()
        SubtitleCharsetDetector.detect(ByteArray(8 shl 20).also { r.nextBytes(it) }, listOf("ru"), null)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 10_000)
    }
}

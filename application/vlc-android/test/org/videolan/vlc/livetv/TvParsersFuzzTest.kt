package org.videolan.vlc.livetv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * VLC Redesign, Live TV: the parsers fed what a broken or hostile server may send (random bytes, the fixtures cut
 * anywhere and mutated, numbers out of range, nulls, a huge playlist): none of them may throw, hang or take long
 * (a parser's exception on the worker thread would end the Live TV engine, or the app).
 */
class TvParsersFuzzTest {

    private val r = Random(58)

    private fun fixtures(): List<String> = listOf("test/fixtures/livetv/tvaccounts", "application/vlc-android/test/fixtures/livetv/tvaccounts")
        .map { File(it) }.first { it.isDirectory }.listFiles()!!.sortedBy { it.name }.map { it.readText() }

    private val noise = "{}[]\":,\\0123456789-+.eEnulltruefalse<>/=#&;\n\r\t ÿ€\u0000�#EXTINF:-1,tvg-id=\"\"".toCharArray()

    private fun randomText(n: Int) = String(CharArray(n) { noise[r.nextInt(noise.size)] })

    /** a valid answer cut anywhere, bytes swapped, numbers made absurd, values made null */
    private fun mutate(s: String): String {
        if (s.isEmpty()) return randomText(20)
        val b = StringBuilder(s)
        when (r.nextInt(6)) {
            0 -> b.setLength(r.nextInt(b.length))
            1 -> repeat(1 + r.nextInt(8)) { b.setCharAt(r.nextInt(b.length), noise[r.nextInt(noise.size)]) }
            2 -> return s.replace(Regex("\\d+")) { listOf("99999999999999999999", "-1", "1e309", "NaN", "0x10", "")[r.nextInt(6)] }
            3 -> return s.replace(Regex(":\\s*(\"[^\"]*\"|\\d+)")) { if (r.nextBoolean()) ": null" else ": []" }
            4 -> b.insert(r.nextInt(b.length + 1), randomText(1 + r.nextInt(40)))
            else -> return s.repeat(1 + r.nextInt(3))
        }
        return b.toString()
    }

    private fun inputs(): List<String> {
        val base = fixtures()
        val out = ArrayList<String>()
        out += listOf("", " ", "null", "[]", "{}", "[null]", "{\"Items\":null}", "{\"Items\":[null,1,\"x\"]}", "<rsp stat=\"fail\"/>")
        repeat(400) { out += mutate(base[r.nextInt(base.size)]) }
        repeat(100) { out += randomText(r.nextInt(300)) }
        return out
    }

    private inline fun quick(what: String, block: () -> Unit) {
        val t = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("$what took $ms ms", ms < 2000)
    }

    @Test
    fun accountAnswers() {
        val channels = listOf(Channel().also { it.name = "Demo Open Movies" }, Channel().also { it.name = "" })
        for (s in inputs()) quick("accounts") {
            TvAccounts.hdhrChannels(s); TvAccounts.hdhrGuide(s)
            TvAccounts.mbChannels(s, "jf:", { it }, { a, b -> a + b }); TvAccounts.mbPrograms(s, "jf:")
            TvAccounts.signedIn(s, "x"); TvAccounts.npValue(s, "sid"); TvAccounts.npChannels(s, "http://h", "sid"); TvAccounts.npListings(s, "1")
            TvAccounts.sdTokenOf(TvAnswer(200, s), "u"); TvAccounts.sdAirings(s); TvAccounts.sdPrograms(s)
            try { TvAccounts.sdMatch(channels, org.json.JSONArray(s)) } catch (e: org.json.JSONException) { }
            TvAccounts.isoSeconds(s); TvAccounts.stateOf(TvAnswer(r.nextInt(600), s))
            TvAccounts.address(s, "jellyfin"); TvAccounts.hostOf(s); TvAccounts.withCredentials(s, "u", "p")
            TvHttp.parseChallenge(s); TvHttp.digest(TvHttp.parseChallenge("Digest $s"), TvAuth("u", "p"), "GET", "/", "c", 1)
        }
    }

    @Test
    fun playlistsAndXtream() {
        val m3u = "#EXTM3U url-tvg=\"http://g/x.xml\"\n#EXTINF:-1 tvg-id=\"a\" tvg-chno=\"1\" group-title=\"G\" catchup=\"default\" catchup-days=\"7\",A\nhttp://h/a.ts\n"
        val account = Xtream.Account("http://h", "u", "p")
        for (s in inputs() + List(200) { mutate(m3u) }) quick("m3u/xtream") {
            M3u.parse(s, "http://base/list.m3u")
            M3u.isChannelList(s)
            M3u.displayGroup(s)
            val info = Xtream.parseAccountInfo(s)
            val cats = Xtream.parseCategories(s)
            Xtream.parseLiveStreams(s, account, cats, info); Xtream.parseVodStreams(s, account, cats); Xtream.parseSeries(s, cats)
            Xtream.parseSeriesInfo(s, account)
        }
    }

    @Test
    fun guideMatchingAndDiscovery() {
        for (s in inputs()) quick("match/discovery") {
            EpgMatch.normalize(s, null); EpgMatch.idKey(s); EpgMatch.countryOfId(s)
            EpgMatch.table(s, "gb")
            Xmltv.guideKey(s)
            TvDiscovery.mbReply(s, "jellyfin"); TvDiscovery.hdhrApi(s); TvDiscovery.isChannelsDvr(s)
            TvDiscovery.Ports.of(s)
            val bytes = s.toByteArray()
            TvDiscovery.hdhrReply(bytes, bytes.size, "1.2.3.4")
        }
        // random packets, a long value's length past the end
        repeat(2000) {
            val b = ByteArray(r.nextInt(64)).also { r.nextBytes(it) }
            if (b.size > 2 && r.nextBoolean()) { b[0] = 0; b[1] = 3 }
            TvDiscovery.hdhrReply(b, b.size, "")
        }
    }

    /** a bare address's name (android.net.Uri's lastPathSegment before, a stub on the JVM) */
    @Test
    fun theNameOfABareAddress() {
        org.junit.Assert.assertEquals("Channel 1.ts", M3u.lastSegment("http://host/live/Channel%201.ts?token=x#f"))
        org.junit.Assert.assertEquals("live", M3u.lastSegment("http://host/live/"))
        org.junit.Assert.assertEquals("a+b.m3u8", M3u.lastSegment("https://h:8080/a+b.m3u8"))
        org.junit.Assert.assertEquals(null, M3u.lastSegment("http://host"))
        org.junit.Assert.assertEquals(null, M3u.lastSegment("http://host/"))
        org.junit.Assert.assertEquals("x.ts", M3u.lastSegment("/sdcard/x.ts"))
        org.junit.Assert.assertEquals("bad%zz", M3u.lastSegment("http://h/bad%zz"))
        org.junit.Assert.assertEquals("x.ts", M3u.parse("#EXTM3U\nhttp://h/x.ts\n").channels.single().name)
    }

    /** a 60,000-channel playlist (a big provider's): parsed in time, without running out of memory */
    @Test
    fun aHugePlaylist() {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until 60_000) sb.append("#EXTINF:-1 tvg-id=\"ch$i.us\" tvg-logo=\"http://logo/$i.png\" group-title=\"Group ${i % 40}\",Channel $i HD\nhttp://stream.example/live/u/p/$i.ts\n")
        val t = System.nanoTime()
        val p = M3u.parse(sb.toString(), "")
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("${p.channels.size} channels", p.channels.size == 60_000)
        assertTrue("took $ms ms", ms < 15_000)
        // and a match of all of them against a country's table
        val table = EpgMatch.table((0 until 5000).joinToString("\n") { "Ch$it.us\tChannel $it" }, "us")
        val t2 = System.nanoTime()
        val m = EpgMatch.match(p.channels, mapOf("us" to table), listOf("us"))
        assertTrue("found ${m.found}", m.found >= 5000)
        assertTrue("matched in ${(System.nanoTime() - t2) / 1_000_000} ms", (System.nanoTime() - t2) / 1_000_000 < 15_000)
    }
}

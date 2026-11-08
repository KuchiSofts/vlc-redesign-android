package org.videolan.vlc.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * VLC Redesign, Live TV guides: programmes that overlap (the rule of every app of the redesign). A long programme
 * holding shorter ones (a film night, a sports block) shows around them, the shorter win their slots; at an overlap
 * of their ends the shorter keeps it (of two as long, the later); the same programme twice is shown once; a guide
 * without overlaps stays as it is. Fixture: test/fixtures/livetv/guides/overlaps.xml (read here as Xmltv sorts it).
 */
class GuideOverlapsTest {

    private fun file(path: String): File = listOf(path, "application/vlc-android/$path").map { File(it) }.first { it.exists() }

    private val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun t(s: String) = format.parse("$s +0000")!!.time / 1000

    /** the fixture's channels, each list sorted by start as Xmltv leaves it */
    private val guide: Map<String, List<Entry>> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file("test/fixtures/livetv/guides/overlaps.xml"))
        val out = HashMap<String, ArrayList<Entry>>()
        val nodes = doc.getElementsByTagName("programme")
        for (i in 0 until nodes.length) {
            val p = nodes.item(i) as Element
            val title = (p.getElementsByTagName("title").item(0) as Element).textContent
            out.getOrPut(p.getAttribute("channel")) { ArrayList() }
                .add(Entry(format.parse(p.getAttribute("start"))!!.time / 1000, format.parse(p.getAttribute("stop"))!!.time / 1000, title, "", "", "", 0L, 0))
        }
        out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    // (the times written in UTC whatever the machine's zone)
    private val hm = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun shown(channel: String) = guide.getValue(channel).withoutOverlaps().map {
        "${it.title} ${hm.format(it.start * 1000)}-${hm.format(it.stop * 1000)}"
    }

    @Test
    fun aLongProgrammeShowsAroundTheShorterOnes() {
        assertEquals(listOf("Film A 20:00-22:00", "Film Night 22:00-22:15", "Film B 22:15-00:00", "Film C 00:00-01:45", "Film Night 01:45-02:00"),
            shown("filmnight"))
        assertEquals(listOf("Sports Block 14:00-14:30", "Match 1 14:30-16:00", "Match 2 16:00-17:30", "Sports Block 17:30-18:00", "News 18:00-18:30"),
            shown("sports"))
        assertEquals(listOf("Long 05:00-06:00", "A 06:00-07:00", "Long 07:00-08:00", "B 08:00-09:00", "Long 09:00-10:00"), shown("gaps"))
    }

    @Test
    fun overlapsAtTheEndsAndTwins() {
        // the shorter keeps the overlap; of two as long, the one that starts later
        assertEquals(listOf("Show A 10:00-11:00", "Show B 11:00-12:00", "Show C 12:00-13:00", "Show D 13:00-14:05"), shown("overrun"))
        assertEquals(listOf("Same 08:00-09:00", "Next 09:00-10:00"), shown("dupes"))
    }

    @Test
    fun aGuideWithoutOverlapsStaysAsItIs() {
        val clean = guide.getValue("clean")
        assertSame(clean, clean.withoutOverlaps())
        assertSame(emptyList<Entry>(), emptyList<Entry>().withoutOverlaps())
    }

    /** what is on and what comes next, found again in every channel (the lookups need one programme at a time) */
    @Test
    fun theLookupsAfter() {
        val night = guide.getValue("filmnight").withoutOverlaps()
        assertEquals("Film Night", night[night.indexAt(t("20261108220500"))].title)
        assertEquals("Film B", night[night.indexAt(t("20261108230000"))].title)
        assertEquals("Film Night", night[night.indexAt(t("20261109015000"))].title)
        assertEquals(-1, night.indexAt(t("20261109020000")))
        for (list in guide.values.map { it.withoutOverlaps() }) {
            for (i in 1 until list.size) {
                assertTrue(list[i - 1].stop <= list[i].start)
                assertTrue(list[i].start < list[i].stop)
            }
            // (a programme's end found by the binary search over the ends)
            for ((i, e) in list.withIndex()) assertEquals(i, list.firstEndingAfter(e.start))
        }
    }

    /** a large guide made of blocks with programmes inside, quickly (a week of 2,000 channels is read at once) */
    @Test
    fun manyProgrammes() {
        val list = ArrayList<Entry>()
        var s = 0L
        repeat(20_000) {
            list.add(Entry(s, s + 7200, "block", "", "", "", 0L, 0))
            list.add(Entry(s + 600, s + 3000, "inside", "", "", "", 0L, 0))
            s += 7200
        }
        list.sortBy { it.start }
        val started = System.nanoTime()
        val out = list.withoutOverlaps()
        assertTrue("${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
        assertEquals(60_000, out.size)
        for (i in 1 until out.size) assertTrue(out[i - 1].stop <= out[i].start)
    }
}

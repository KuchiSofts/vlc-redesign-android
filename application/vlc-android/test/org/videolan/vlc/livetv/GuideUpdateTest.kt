package org.videolan.vlc.livetv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * VLC Redesign, Live TV guides: an update where one guide fails or comes cut short leaves its channels' programmes as
 * they were; a guide that comes whole replaces them, an empty <tv/> too; a guide cut short every time is downloaded
 * again a few times, then by age (the rules of every app of the redesign). The guide reader tells a whole guide from
 * one cut short, plain or gzip-compressed, and from an error page.
 */
class GuideUpdateTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val android = Xmltv.newParser

    @Before
    fun parser() {
        Xmltv.newParser = { KXmlParser() }
    }

    @After
    fun back() {
        Xmltv.newParser = android
    }

    private fun guide(programmes: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"test\">\n")
        append("  <channel id=\"one\"><display-name>One</display-name></channel>\n")
        for (i in 0 until programmes) {
            val h = 1794124800L + i * 1800L
            append("  <programme start=\"${stamp(h)}\" stop=\"${stamp(h + 1800)}\" channel=\"one\"><title>Programme $i</title>")
            append("<desc>A made-up programme number $i of the test guide.</desc></programme>\n")
        }
        append("</tv>\n")
    }

    private fun stamp(secs: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date(secs * 1000)) + " +0000"
    }

    private fun read(bytes: ByteArray): GuideRead {
        val f = File(dir.root, "g" + System.nanoTime() + ".xml")
        f.writeBytes(bytes)
        return Xmltv.parse(f, GuideFilter(), null)
    }

    private fun gz(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun aWholeGuideIsWhole() {
        val r = read(guide(40).toByteArray())
        assertTrue(r.whole)
        assertFalse(r.cut)
        assertEquals("", r.error)
        assertEquals(40, r.programmes)
        val z = read(gz(guide(40).toByteArray()))
        assertTrue(z.whole)
        assertFalse(z.cut)
        assertEquals(40, z.programmes)
    }

    @Test
    fun aGuideCutShortIsCut() {
        val text = guide(400).toByteArray()
        // in the middle of a programme
        val mid = read(text.copyOf(text.size * 6 / 10))
        assertTrue(mid.cut)
        assertEquals("", mid.error)
        assertTrue(mid.programmes in 1..399)
        // right after a programme: every tag closed but the guide's own
        val s = guide(10)
        val atEnd = read(s.substring(0, s.lastIndexOf("</tv>")).toByteArray())
        assertTrue(atEnd.cut)
        assertEquals(10, atEnd.programmes)
        // compressed, cut short
        val z = gz(text)
        val zr = read(z.copyOf(z.size * 6 / 10))
        assertTrue(zr.cut)
        assertTrue(zr.programmes in 1..399)
    }

    @Test
    fun anEmptyGuideIsNoCutGuide() {
        for (text in listOf("<tv/>", "<?xml version=\"1.0\"?>\n<tv generator-info-name=\"x\"></tv>\n", "<tv>\n  <channel id=\"one\"/>\n</tv>")) {
            val r = read(text.toByteArray())
            assertTrue(text, r.whole)
            assertFalse(text, r.cut)
            assertEquals(text, "", r.error)
            assertEquals(text, 0, r.programmes)
        }
    }

    @Test
    fun anErrorPageIsNoGuide() {
        for (text in listOf("<html><head><title>502 Bad Gateway</title></head><body>502</body></html>", "Service Unavailable", "")) {
            val r = read(text.toByteArray())
            assertFalse(text, r.whole)
            assertFalse(text, r.cut)
            assertTrue(text, r.error.isNotEmpty())
            assertEquals(text, 0, r.programmes)
        }
    }

    @Test
    fun whatTheChannelsKeep() {
        // a reading that failed with nothing keeps the last programmes
        assertTrue(GuideUpdate.keepOld(failed = true, newProgrammes = 0, oldProgrammes = 120))
        // a whole guide replaces them, an empty one too (its channels have none then)
        assertFalse(GuideUpdate.keepOld(failed = false, newProgrammes = 0, oldProgrammes = 120))
        assertFalse(GuideUpdate.keepOld(failed = false, newProgrammes = 80, oldProgrammes = 120))
        assertFalse(GuideUpdate.keepOld(failed = true, newProgrammes = 0, oldProgrammes = 0))
        // a new download cut short, or no guide (an error page), is dropped for the last whole one; with none before,
        // the part that came is used
        assertTrue(GuideUpdate.dropNew(cut = true, unread = false, hasWhole = true))
        assertTrue(GuideUpdate.dropNew(cut = false, unread = true, hasWhole = true))
        assertFalse(GuideUpdate.dropNew(cut = false, unread = false, hasWhole = true))
        assertFalse(GuideUpdate.dropNew(cut = true, unread = false, hasWhole = false))
    }

    @Test
    fun aGuideCutShortEveryTimeIsTriedAFewTimes() {
        val hour = 3600_000L
        val tries = GuideUpdate.CutTries(3)
        val url = "http://example.com/guide.xml"
        assertTrue(tries.mayTry(url, 0))
        tries.cut(url, 0, 24 * hour)
        assertTrue(tries.mayTry(url, hour))
        tries.cut(url, hour, 24 * hour)
        assertTrue(tries.mayTry(url, 2 * hour))
        tries.cut(url, 2 * hour, 24 * hour)
        // three in a row: not every half hour any more, but at the guide's next update
        assertFalse(tries.mayTry(url, 3 * hour))
        assertFalse(tries.mayTry(url, 25 * hour))
        assertTrue(tries.mayTry(url, 26 * hour))
        // a whole one starts the count again; another guide has its own
        tries.cut(url, 27 * hour, 24 * hour)
        tries.whole(url)
        tries.cut(url, 28 * hour, 24 * hour)
        tries.cut(url, 29 * hour, 24 * hour)
        assertTrue(tries.mayTry(url, 30 * hour))
        assertTrue(tries.mayTry("http://example.com/other.xml", 30 * hour))
        // no updates by age (0): nothing to wait for
        val manual = GuideUpdate.CutTries(1)
        manual.cut(url, 0, 0)
        assertTrue(manual.mayTry(url, 1))
    }
}

package org.videolan.vlc.livetv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * VLC Redesign, Live TV guides on a device of little memory: only the channels of the playlists and the days around
 * now are kept; a guide with more programmes than the device holds keeps fewer days ahead (never less than one), then
 * stops, what was read kept and the file still whole (not "cut short"); a reading that takes too long stops the same
 * way; Stop drops it. The index read back keeps only the days asked for. A guide compressed twice (a .xml.gz a server
 * sent with gzip) reads. Made-up channels and programmes only.
 */
class GuideLimitsTest {

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

    /** a made-up time: 2026-11-09 00:00 UTC */
    private val now = 1794182400L

    private fun stamp(secs: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date(secs * 1000)) + " +0000"
    }

    /** channels "c0".."c<n>", each with a programme an hour from a day back to [daysAhead] days ahead (as most guides: by channel) */
    private fun guide(channels: Int, daysAhead: Int): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"test\">\n")
        for (c in 0 until channels) append("  <channel id=\"c$c\"><display-name>Test channel $c</display-name></channel>\n")
        for (c in 0 until channels) {
            var t = now - 86400L
            while (t < now + daysAhead * 86400L) {
                append("  <programme start=\"${stamp(t)}\" stop=\"${stamp(t + 3600)}\" channel=\"c$c\"><title>Show ${(t / 3600) % 7}</title>")
                append("<sub-title>Episode ${(t / 3600) % 5}</sub-title><desc>A made-up programme.</desc></programme>\n")
                t += 3600
            }
        }
        append("</tv>\n")
    }.toByteArray()

    private fun file(bytes: ByteArray): File = File(dir.root, "g" + System.nanoTime() + ".xml").apply { writeBytes(bytes) }

    private fun gz(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun filter(vararg channels: String) = GuideFilter().apply {
        keys.addAll(channels.map { Xmltv.guideKey(it) })
        from = now - 86400L
        until = now + 7 * 86400L
    }

    @Test
    fun onlyTheChannelsAndTheDaysAsked() {
        val r = Xmltv.parse(file(guide(10, 10)), filter("c1", "c2").apply { from = now - 3600; until = now + 2 * 86400L }, null)
        assertTrue(r.whole)
        assertEquals(setOf("c1", "c2"), r.lists.keys)
        // from the programme that ends at now - 1 h to the one starting at now + 2 days
        for (list in r.lists.values) {
            assertTrue(list.all { it.stop >= now - 3600 && it.start <= now + 2 * 86400L })
            assertEquals(2 * 24 + 3, list.size)
        }
        assertEquals(0L, r.shortenedUntil)
        assertFalse(r.limited)
    }

    @Test
    fun tooManyProgrammesKeepFewerDaysAhead() {
        // 20 channels, 8 days of an hour each (with the day back): 3,840 programmes for 2,000 allowed
        val f = filter(*Array(20) { "c$it" }).apply { maxProgrammes = 2000; minUntil = now + 86400L }
        val r = Xmltv.parse(file(guide(20, 7)), f, null)
        assertTrue(r.whole)
        assertFalse(r.cut)
        assertFalse(r.limited)
        assertEquals("", r.error)
        assertTrue(r.programmes <= 2000)
        assertTrue(r.shortenedUntil in (now + 86400L) until (now + 7 * 86400L))
        // every channel kept, none past the end it was shortened to
        assertEquals(20, r.lists.size)
        assertTrue(r.lists.values.all { l -> l.all { it.start <= r.shortenedUntil } })
        assertEquals(r.programmes, r.lists.values.sumOf { it.size })
    }

    @Test
    fun atOneDayAheadTheReadingStopsWhatWasReadKept() {
        // 100 channels of 2 days each are 4,900 programmes; 500 allowed: one day ahead is still too many
        val f = filter(*Array(100) { "c$it" }).apply { maxProgrammes = 500; minUntil = now + 86400L }
        val r = Xmltv.parse(file(guide(100, 7)), f, null)
        assertTrue(r.limited)
        // stopped by the limits: neither an error nor a guide cut short (the last whole one would be read instead)
        assertFalse(r.cut)
        assertEquals("", r.error)
        // (the one that went past the limit included)
        assertTrue(r.programmes in 1..501)
        assertTrue(r.lists.size in 1 until 100)
        assertEquals(now + 86400L, r.shortenedUntil)
    }

    @Test
    fun aReadingTooLongStopsWhatWasReadKept() {
        val f = filter(*Array(50) { "c$it" }).apply { deadline = System.nanoTime() - 1 }
        val r = Xmltv.parse(file(guide(50, 7)), f, null)
        assertTrue(r.limited)
        assertFalse(r.cut)
        assertEquals("", r.error)
    }

    @Test
    fun stopDropsTheReading() {
        val f = filter(*Array(50) { "c$it" }).apply { cancel = AtomicBoolean(true) }
        val r = Xmltv.parse(file(guide(50, 7)), f, null)
        assertEquals("cancelled", r.error)
        assertFalse(r.limited)
    }

    @Test
    fun theProgressIsTold() {
        var bytes = 0L
        var channels = 0
        val f = filter(*Array(300) { "c$it" }).apply { progress = { b, c -> bytes = b; channels = c } }
        // (told every half second at most: a reading slowed down a little to see it)
        val slow = file(guide(300, 3))
        val started = System.nanoTime()
        Xmltv.newParser = {
            object : KXmlParser() {
                override fun next(): Int {
                    if (System.nanoTime() - started < 1_500_000_000L) Thread.sleep(0, 20_000)
                    return super.next()
                }
            }
        }
        val r = Xmltv.parse(slow, f, null)
        assertTrue(r.whole)
        assertTrue(bytes > 0)
        assertTrue(channels in 1..300)
    }

    @Test
    fun aGuideCompressedTwiceReads() {
        val r = Xmltv.parse(file(gz(gz(guide(3, 2)))), filter("c0", "c1", "c2"), null)
        assertTrue(r.whole)
        assertEquals("", r.error)
        assertEquals(3, r.lists.size)
    }

    @Test
    fun theIndexKeepsTheDaysAskedAndWhatTheLimitsDid() {
        val xml = file(guide(5, 7))
        val f = filter("c0", "c1", "c2", "c3", "c4").apply { until = now + 8 * 86400L; maxProgrammes = 600; minUntil = now + 86400L }
        val read = Xmltv.parse(xml, f, null)
        assertTrue(read.shortenedUntil > 0)
        val desc = File(xml.parentFile, "x-1.desc").apply { writeText("") }
        val index = GuideIndex.Index().apply {
            descName = desc.name
            for ((k, v) in read.lists) lists[k] = v
            shortenedUntil = read.shortenedUntil
            limited = read.limited
        }
        val sig = GuideIndex.signatureOf(f, 7, "back 1, at most 600")
        val idx = File(dir.root, "g.idx")
        assertTrue(GuideIndex.write(idx, xml, sig, index))
        // the next day: from now, a day ahead
        val back = GuideIndex.read(idx, xml, sig, now, now + 86400L)
        assertNotNull(back)
        back!!
        assertEquals(read.shortenedUntil, back.shortenedUntil)
        assertEquals(5, back.lists.size)
        assertTrue(back.lists.values.all { l -> l.all { it.stop >= now && it.start <= now + 86400L } })
        assertEquals(26, back.lists.getValue("c0").size)
        // made for other channels or limits: not this one
        assertNull(GuideIndex.read(idx, xml, GuideIndex.signatureOf(f, 7, "back 1, at most 700")))
        // a damaged index is no index
        idx.writeBytes(idx.readBytes().copyOf(200))
        assertNull(GuideIndex.read(idx, xml, sig))
    }
}

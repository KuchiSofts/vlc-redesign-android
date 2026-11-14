package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** VLC Redesign: the lyrics of a song (RdLyrics): .lrc files and the ID3 tag's SYLT and USLT */
class RdLyricsTest {

    @Test
    fun lrc() {
        val l = RdLyrics.parseLrc("[ti:Song]\n[ar:Someone]\n[00:01.50]First\n[00:03.00][00:10.25]Again\n\n[00:05]Five")
        assertTrue(l.synced)
        assertEquals(listOf(1500L, 3000L, 5000L, 10250L), l.lines.map { it.timeMs })
        assertEquals(listOf("First", "Again", "Five", "Again"), l.lines.map { it.text })
        assertEquals(-1, l.indexAt(1000))
        assertEquals(1, l.indexAt(4000))
        assertEquals(3, l.indexAt(99_000))
        // [offset:+500] shows every line half a second sooner
        assertEquals(1000L, RdLyrics.parseLrc("[offset:+500]\n[00:01.50]A").lines[0].timeMs)
        // words without times: plain lyrics
        val plain = RdLyrics.parseLrc("Line one\nLine two")
        assertFalse(plain.synced)
        assertEquals(2, plain.lines.size)
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private val head23 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0)

    @Test
    fun usltUtf8() {
        val body = ByteArrayOutputStream().apply {
            write(3); write("heb".toByteArray()); write(0)
            write("שורה ראשונה\nשורה שנייה".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val l = RdLyrics.parseId3(head23, frame("TIT2", byteArrayOf(0, 'x'.code.toByte())) + frame("USLT", body))
        assertNotNull(l)
        assertFalse(l!!.synced)
        assertEquals(listOf("שורה ראשונה", "שורה שנייה"), l.lines.map { it.text })
    }

    @Test
    fun syltWinsOverUsltUtf16() {
        val uslt = ByteArrayOutputStream().apply { write(0); write("eng".toByteArray()); write(0); write("plain".toByteArray()) }.toByteArray()
        val sylt = ByteArrayOutputStream().apply {
            write(1); write("eng".toByteArray()); write(2); write(1)
            write(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0))
            for ((t, ms) in listOf("Hello" to 1200, "World" to 3400)) {
                write("﻿$t".toByteArray(Charsets.UTF_16LE)); write(byteArrayOf(0, 0))
                write(byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte()))
            }
        }.toByteArray()
        val l = RdLyrics.parseId3(head23, frame("USLT", uslt) + frame("SYLT", sylt))!!
        assertTrue(l.synced)
        assertEquals(listOf(1200L, 3400L), l.lines.map { it.timeMs })
        assertEquals(listOf("Hello", "World"), l.lines.map { it.text })
    }
}

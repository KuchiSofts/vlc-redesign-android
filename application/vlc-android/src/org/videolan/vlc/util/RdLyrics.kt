/*
 * VLC Redesign: the lyrics of a song, as the desktop's music view shows them (player_controller.cpp: the ID3 tag's
 * SYLT, synchronized, else USLT): libvlc 3 for Android gives no such tag, so they are read here, from the song's own
 * ID3v2 tag (SYLT with the time of each line, else USLT) or from an .lrc file beside it ([mm:ss.xx] lines), the .lrc
 * first (the user's own). Lines with a time follow the song; lines without are shown as they are.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object RdLyrics {
    /** a line and, for synchronized lyrics, when it starts (ms) */
    data class Line(val timeMs: Long?, val text: String)

    class Lyrics(val lines: List<Line>) {
        val synced get() = lines.isNotEmpty() && lines.all { it.timeMs != null }

        /** the line sung at [timeMs] (synchronized lyrics), -1 before the first */
        fun indexAt(timeMs: Long): Int {
            if (!synced) return -1
            var found = -1
            for (i in lines.indices) if (lines[i].timeMs!! <= timeMs) found = i else break
            return found
        }
    }

    /** the tag of [audio] read at most this far (a cover picture can be large: the lyrics come before or after it) */
    private const val MAX_TAG = 16 * 1024 * 1024

    fun load(audio: File): Lyrics? {
        lrcBeside(audio)?.let { lrc -> parseLrc(lrc.readText(Charsets.UTF_8)).takeIf { it.lines.isNotEmpty() }?.let { return it } }
        return try {
            RandomAccessFile(audio, "r").use { f ->
                val head = ByteArray(10)
                if (f.length() < 10) return null
                f.readFully(head)
                if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
                val size = syncsafe(head, 6)
                if (size <= 0 || size > MAX_TAG) return null
                val tag = ByteArray(size)
                f.readFully(tag)
                parseId3(head, tag)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** "song.lrc" (or "song.LRC") in the folder of "song.mp3" */
    fun lrcBeside(audio: File): File? {
        val base = audio.nameWithoutExtension
        return audio.parentFile?.listFiles()?.firstOrNull { it.isFile && it.extension.equals("lrc", true) && it.nameWithoutExtension == base }
    }

    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** an .lrc file: "[01:02.50]text" (several times on a line too), [offset:ms]; other tags ([ar:], [ti:]) left out */
    fun parseLrc(text: String): Lyrics {
        var offset = 0L
        val timed = ArrayList<Line>()
        val plain = ArrayList<Line>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            OFFSET.find(raw)?.let { offset = it.groupValues[1].toLong(); return@let }
            val times = TIME.findAll(raw).toList()
            if (times.isNotEmpty() && raw.trimStart().startsWith("[")) {
                val words = raw.substring(times.last().range.last + 1).trim()
                for (m in times) {
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    timed.add(Line(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, words))
                }
            } else if (!raw.trimStart().startsWith("[") && raw.isNotBlank()) plain.add(Line(null, raw.trim()))
        }
        // (an .lrc's offset is how much earlier the lines come: a positive one shows them sooner)
        if (timed.isNotEmpty()) return Lyrics(timed.map { it.copy(timeMs = (it.timeMs!! - offset).coerceAtLeast(0)) }.sortedBy { it.timeMs })
        return Lyrics(plain)
    }

    private fun syncsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** an ID3v2.2, 2.3 or 2.4 tag ([head] its 10 bytes, [tag] the rest): its SYLT, else its USLT */
    fun parseId3(head: ByteArray, tag: ByteArray): Lyrics? {
        val major = head[3].toInt()
        var data = tag
        // (the whole tag unsynchronized: FF 00 back to FF)
        if (head[5].toInt() and 0x80 != 0 && major < 4) data = unsync(data)
        var pos = 0
        // (an extended header is passed over)
        if (head[5].toInt() and 0x40 != 0 && data.size >= 4) pos += if (major == 4) syncsafe(data, 0) else be32(data, 0) + 4
        var sylt: Lyrics? = null
        var uslt: Lyrics? = null
        val idLen = if (major == 2) 3 else 4
        val headLen = if (major == 2) 6 else 10
        while (pos + headLen <= data.size) {
            val id = String(data, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (major) {
                2 -> ((data[pos + 3].toInt() and 0xff) shl 16) or ((data[pos + 4].toInt() and 0xff) shl 8) or (data[pos + 5].toInt() and 0xff)
                4 -> syncsafe(data, pos + 4)
                else -> be32(data, pos + 4)
            }
            val start = pos + headLen
            if (size <= 0 || start + size > data.size) break
            var body = data.copyOfRange(start, start + size)
            if (major == 4 && data[pos + 9].toInt() and 0x02 != 0) body = unsync(body)
            when (id) {
                "SYLT", "SLT" -> if (sylt == null) sylt = parseSylt(body)
                "USLT", "ULT" -> if (uslt == null) uslt = parseUslt(body)
            }
            pos = start + size
        }
        return sylt?.takeIf { it.lines.isNotEmpty() } ?: uslt?.takeIf { it.lines.isNotEmpty() }
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xff.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun charset(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** the end of a text that starts at [from] (its terminator: one 0, two for UTF-16) */
    private fun textEnd(b: ByteArray, from: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < b.size) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
                i += 2
            }
            return b.size
        }
        var i = from
        while (i < b.size && b[i] != 0.toByte()) i++
        return i
    }

    private fun terminatorLen(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decode(b: ByteArray, from: Int, to: Int, encoding: Int) =
        if (to <= from) "" else String(b, from, to - from, charset(encoding)).trim('﻿', '\u0000')

    /** USLT: encoding, language, descriptor, the words (one text, lines kept) */
    fun parseUslt(b: ByteArray): Lyrics? {
        if (b.size < 5) return null
        val enc = b[0].toInt()
        val descEnd = textEnd(b, 4, enc)
        val text = decode(b, descEnd + terminatorLen(enc), b.size, enc)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { Line(null, it.trim()) }
            .dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
        return Lyrics(lines)
    }

    /** SYLT: encoding, language, time format (2: ms; 1: MPEG frames, not used here), type, descriptor, then
     *  [text, time]... */
    fun parseSylt(b: ByteArray): Lyrics? {
        if (b.size < 7) return null
        val enc = b[0].toInt()
        if (b[4].toInt() != 2) return null
        var pos = textEnd(b, 6, enc) + terminatorLen(enc)
        val lines = ArrayList<Line>()
        while (pos < b.size) {
            val end = textEnd(b, pos, enc)
            val text = decode(b, pos, end, enc)
            val timeAt = end + terminatorLen(enc)
            if (timeAt + 4 > b.size) break
            // (a line's text may start with a line break: the new line)
            lines.add(Line(be32(b, timeAt).toLong() and 0xffffffffL, text.trim('\n', '\r', ' ')))
            pos = timeAt + 4
        }
        return Lyrics(lines.sortedBy { it.timeMs })
    }
}

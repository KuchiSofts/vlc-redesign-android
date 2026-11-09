/*
 * VLC Redesign, Live TV: the programme guide (EPG) of the channels, read from XMLTV, a gzip-compressed one
 * inflated as it is read (a guide of hundreds of megabytes is never held whole in memory). Only the channels of
 * the playlists and the days around now are kept; the descriptions go to a file of their own as they are read.
 * A port of the desktop's modules/misc/iptv/guide.cpp.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** What the guide is asked to keep. */
class GuideFilter {
    /** the keys of the channels wanted (see Xmltv.guideKey); empty: every channel */
    val keys = HashSet<String>()
    /** programmes ending before are dropped */
    var from = 0L
    /** programmes starting after are dropped (0: none) */
    var until = 0L
    /** the languages kept ("he", "en"), lower case; empty: all of them */
    val languages = HashSet<String>()
    /** set from another thread to stop the reading */
    var cancel: AtomicBoolean? = null
    /**
     * the most programmes kept (0: no limit; a TV of 1 GB holds a few hundred thousand): past it the days ahead are
     * shortened a day at a time down to [minUntil], the programmes past the new end dropped; at [minUntil] the reading
     * stops, what was read kept
     */
    var maxProgrammes = 0
    /** the nearest end the days ahead are shortened to (seconds since the epoch; 0: never shortened) */
    var minUntil = 0L
    /** when the reading stops, what was read kept (System.nanoTime(); 0: never) */
    var deadline = 0L
    /** told now and then while reading: the bytes of XML read, the channels with programmes */
    var progress: ((Long, Int) -> Unit)? = null
}

/** A guide as read: the programmes by the channel's key, the ids by display name, the languages. */
class GuideRead {
    val lists = HashMap<String, ArrayList<Entry>>()
    val idByName = HashMap<String, String>()
    val languages = HashSet<String>()
    var error = ""
    var programmes = 0
    /** the guide's root closed (</tv>, or <tv/>): it came whole */
    var whole = false
    /** it stops before its end (a download cut short): its programmes up to there only */
    var cut = false
    /** the reading stopped by the limits (the programmes, the time): what was read kept, the file itself is whole */
    var limited = false
    /** the end the days ahead were shortened to (the programmes allowed), 0 when they were not */
    var shortenedUntil = 0L
}

class CancelledException : IOException("cancelled")

/** the reading stopped by the limits of the filter (not an error: what was read is kept) */
private class LimitReached : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

object Xmltv {

    /** the XML reader (Android's; another one in the unit tests, where Android's is a stub) */
    internal var newParser: () -> XmlPullParser = { Xml.newPullParser() }

    private val SUFFIXES = arrayOf("uhd", "fhd", "hd", "sd", "4k", "hevc", "h265", "+1", "orig", "original")

    /**
     * The key a channel or a guide entry is matched by: the id when there is one, else the name, both folded
     * (case, separators, "HD"/"FHD" suffixes, a country prefix "IL: ").
     */
    fun guideKey(idOrName: String): String {
        var key = idOrName.trim().lowercase(Locale.ROOT)
        // a country prefix "IL: " is part of a name only
        run {
            var i = 0
            while (i < key.length && key[i] in 'a'..'z') ++i
            var j = i
            while (j < key.length && key[j].isWhitespace()) ++j
            if (i in 2..3 && j < key.length && (key[j] == ':' || key[j] == '|') && j + 1 < key.length && key[j + 1].isWhitespace())
                key = key.substring(j + 1)
        }
        // the separators as one space
        val folded = StringBuilder(key.length)
        for (c in key) {
            val separator = c.isWhitespace() || c == '.' || c == '_' || c == '-' || c == ':' || c == '|'
            if (!separator) folded.append(c)
            else if (folded.isNotEmpty() && folded[folded.length - 1] != ' ') folded.append(' ')
        }
        key = folded.toString().trim()
        // the quality at the end ("HD", "FHD", "4K", "+1"...), twice
        repeat(2) {
            for (s in SUFFIXES) {
                if (key.length > s.length && key.endsWith(s)) {
                    val before = key[key.length - s.length - 1]
                    if (!before.isLetterOrDigit() || s == "+1") {
                        key = key.dropLast(s.length).trim()
                        break
                    }
                }
            }
        }
        return key
    }

    private val utc: ThreadLocal<Calendar> = object : ThreadLocal<Calendar>() {
        // (ThreadLocal.withInitial is Android 8)
        override fun initialValue(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
    }

    /** "20261025203000 +0300" -> seconds since the epoch, UTC (0 on an error). */
    fun parseTime(text: String): Long {
        val t = text.trim()
        if (t.length < 12) return 0
        fun num(from: Int, len: Int): Int {
            var v = 0
            for (k in from until from + len) {
                val c = t[k]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            return v
        }
        val y = num(0, 4); val mo = num(4, 2); val d = num(6, 2); val h = num(8, 2); val mi = num(10, 2)
        val s = if (t.length >= 14 && t[12].isDigit()) num(12, 2) else 0
        if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31 || h < 0 || h > 23 || mi < 0 || mi > 59 || s < 0) return 0
        val cal = utc.get()!!
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, s)
        var secs = cal.timeInMillis / 1000
        val plus = t.indexOf('+')
        val minus = t.indexOf('-', 12)
        val sign = maxOf(plus, minus)
        if (sign > 0 && t.length >= sign + 5) {
            val zh = t.substring(sign + 1, sign + 3).toIntOrNull() ?: 0
            val zm = t.substring(sign + 3, sign + 5).toIntOrNull() ?: 0
            val offset = zh * 3600 + zm * 60
            secs -= if (t[sign] == '+') offset else -offset
        }
        return secs
    }

    /** A stream that fails past limit bytes: a decompression bomb. */
    private class LimitedStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        private var count = 0L
        override fun read(): Int {
            val c = super.read()
            if (c >= 0 && ++count > limit) throw IOException("the guide is too large")
            return c
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("the guide is too large")
            }
            return n
        }
    }

    /** The file read inflated when it is gzip-compressed (several members one after the other too). */
    fun open(file: File, limit: Long = 4L * 1024 * 1024 * 1024): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 256 * 1024)
        if (!isGzip(raw)) return raw
        val inflated = BufferedInputStream(LimitedStream(GZIPInputStream(raw, 256 * 1024), limit), 256 * 1024)
        // a .xml.gz a server sent compressed once more (asked for gzip): inflated twice
        return if (isGzip(inflated)) BufferedInputStream(LimitedStream(GZIPInputStream(inflated, 256 * 1024), limit), 256 * 1024) else inflated
    }

    private fun isGzip(s: BufferedInputStream): Boolean {
        s.mark(4)
        val b0 = s.read()
        val b1 = s.read()
        s.reset()
        return b0 == 0x1f && b1 == 0x8b
    }

    /** the bytes read through it (the XML read so far, for the progress) */
    private class CountingStream(source: InputStream) : FilterInputStream(source) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) ++count }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Reads an XMLTV guide (a file, gzip-compressed or not); the descriptions are written to desc as they come,
     * their place kept in each entry. Runs for seconds on a large guide: on a worker thread.
     */
    fun parse(file: File, filter: GuideFilter, desc: OutputStream?): GuideRead {
        val out = GuideRead()
        try {
            open(file).use { parse(CountingStream(it), filter, desc, out) }
        } catch (e: LimitReached) {
            out.limited = true
        } catch (e: CancelledException) {
            out.error = "cancelled"
        } catch (e: Exception) {
            if (out.programmes == 0) out.error = e.message ?: e.javaClass.simpleName
        }
        // one that stops before its end: cut short, its programmes up to there kept (an empty <tv/> is whole); stopped
        // before any programme, or no XML at all: no guide (one the limits stopped came whole: what was read is kept)
        if (out.error.isEmpty() && !out.whole && !out.limited) {
            if (out.programmes > 0) out.cut = true else out.error = "not an XMLTV guide"
        }
        // sorted, the missing ends taken from the next start, and one programme at a time (a long one holding shorter
        // ones shows around them: withoutOverlaps)
        for (entry in out.lists.entries) {
            val list = entry.value
            list.sortBy { it.start }
            for (i in list.indices) {
                val e = list[i]
                if (e.stop <= e.start) {
                    val stop = if (i + 1 < list.size) list[i + 1].start else e.start + 3600
                    list[i] = Entry(e.start, stop, e.title, e.subtitle, e.category, e.episode, e.descOffset, e.descLength)
                }
            }
            val single = list.withoutOverlaps()
            if (single !== list) entry.setValue(ArrayList(single))
            else list.trimToSize()
        }
        return out
    }

    private fun parse(input: CountingStream, filter: GuideFilter, desc: OutputStream?, out: GuideRead) {
        val xml = newParser()
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xml.setInput(input, null)
        // a guide id -> the key its programmes are kept under ("" dropped)
        val keyOfId = HashMap<String, String>()
        val all = filter.keys.isEmpty()
        // the same titles and categories over and over: shared
        val pool = HashMap<String, String>()
        fun shared(s: String): String {
            if (s.isEmpty()) return ""
            return pool.getOrPut(s) { s }
        }
        var descOffset = 0L
        var sinceCheck = 0
        var lastProgress = System.nanoTime()
        fun keyFor(id: String): String = keyOfId.getOrPut(id) {
            // a programme of a channel the header did not list
            val k = guideKey(id)
            if (all || filter.keys.contains(k)) k else ""
        }
        var event = xml.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                // (an error page given in place of the guide is no guide, not an empty one)
                if (xml.depth == 1 && xml.name != "tv") throw IOException("not an XMLTV guide")
                if (++sinceCheck >= 4096) {
                    sinceCheck = 0
                    if (filter.cancel?.get() == true) throw CancelledException()
                    val now = System.nanoTime()
                    // (a guide of gigabytes on a slow TV: what was read by then is kept)
                    if (filter.deadline != 0L && now - filter.deadline > 0) throw LimitReached()
                    if (filter.progress != null && now - lastProgress > 500_000_000L) {
                        lastProgress = now
                        filter.progress?.invoke(input.count, out.lists.size)
                    }
                }
                when (xml.name) {
                    "channel" -> {
                        val id = xml.getAttributeValue(null, "id") ?: ""
                        val idKey = guideKey(id)
                        var key = if (all || filter.keys.contains(idKey)) idKey else ""
                        val depth = xml.depth
                        // its display names: a channel without tvg-id is matched by one
                        while (true) {
                            val e = xml.next()
                            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xml.depth == depth)) break
                            if (e == XmlPullParser.START_TAG && xml.name == "display-name") {
                                val nameKey = guideKey(readText(xml))
                                out.idByName[nameKey] = idKey
                                if (key.isEmpty() && filter.keys.contains(nameKey)) key = nameKey
                            }
                        }
                        keyOfId[id] = key
                    }
                    "programme" -> {
                        val key = keyFor(xml.getAttributeValue(null, "channel") ?: "")
                        val start = parseTime(xml.getAttributeValue(null, "start") ?: "")
                        val stop = parseTime(xml.getAttributeValue(null, "stop") ?: "")
                        if (key.isEmpty() || start <= 0 || (stop in 1 until filter.from) || (filter.until > 0 && start > filter.until)) {
                            skip(xml)
                        } else {
                            val p = Programme()
                            p.start = start
                            p.stop = stop
                            readProgramme(xml, p, filter, out)
                            // only the languages asked for (a programme in none of them is kept when the guide
                            // names no language)
                            if (filter.languages.isEmpty() || p.language.isEmpty() || filter.languages.contains(p.language)) {
                                var offset = -1L
                                var length = 0
                                if (desc != null && p.description.isNotEmpty()) {
                                    val bytes = p.description.toByteArray(Charsets.UTF_8)
                                    desc.write(bytes)
                                    offset = descOffset
                                    length = bytes.size
                                    descOffset += bytes.size
                                }
                                // (the episodes' titles and numbers repeat too: shared, a guide of 500,000 programmes
                                // held 40 MB of copies)
                                out.lists.getOrPut(key) { ArrayList() }.add(
                                    Entry(p.start, p.stop, shared(p.title), shared(p.subtitle), shared(p.category), shared(p.episode), offset, length))
                                ++out.programmes
                                if (filter.maxProgrammes > 0 && out.programmes > filter.maxProgrammes && !shorten(out, filter)) throw LimitReached()
                            }
                        }
                    }
                }
            }
            // the root's end: the guide came whole
            if (event == XmlPullParser.END_TAG && xml.depth == 1 && xml.name == "tv") out.whole = true
            event = xml.next()
        }
    }

    /**
     * More programmes than allowed: the days ahead shortened a day at a time (never below filter.minUntil), the
     * programmes past the new end dropped. False when they can't be shortened more: the reading stops there.
     */
    internal fun shorten(out: GuideRead, filter: GuideFilter): Boolean {
        while (out.programmes > filter.maxProgrammes) {
            if (filter.minUntil <= 0L || filter.until <= filter.minUntil) return false
            filter.until = maxOf(filter.minUntil, filter.until - 86400L)
            val until = filter.until
            var n = 0
            val lists = out.lists.values.iterator()
            while (lists.hasNext()) {
                val list = lists.next()
                list.removeAll { it.start > until }
                if (list.isEmpty()) lists.remove() else n += list.size
            }
            out.programmes = n
            out.shortenedUntil = until
        }
        return true
    }

    private fun readProgramme(xml: XmlPullParser, p: Programme, filter: GuideFilter, out: GuideRead) {
        // a guide may give each text in several languages: the first one, or the first in a language asked for
        var titleWanted = false
        var descWanted = false
        val depth = xml.depth
        while (true) {
            val e = xml.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xml.depth == depth)) break
            if (e != XmlPullParser.START_TAG) continue
            val name = xml.name
            val lang = (xml.getAttributeValue(null, "lang") ?: "").lowercase(Locale.ROOT)
            val wanted = lang.isNotEmpty() && filter.languages.contains(lang)
            when {
                name == "title" && (p.title.isEmpty() || (wanted && !titleWanted)) -> {
                    p.title = simplified(readText(xml))
                    p.language = lang
                    titleWanted = wanted
                    if (lang.isNotEmpty()) out.languages.add(lang)
                }
                name == "sub-title" && p.subtitle.isEmpty() -> p.subtitle = simplified(readText(xml))
                name == "desc" && (p.description.isEmpty() || (wanted && !descWanted)) -> {
                    p.description = readText(xml).trim().take(2000)
                    descWanted = wanted
                }
                name == "category" && p.category.isEmpty() -> p.category = simplified(readText(xml))
                name == "episode-num" && p.episode.isEmpty() -> {
                    // "onscreen" (S01E02) reads as it is; xmltv_ns is "season.episode.part", counted from 0; the
                    // other systems are skipped (original-air-date, a provider's ids)
                    val system = xml.getAttributeValue(null, "system") ?: ""
                    val text = simplified(readText(xml))
                    if (system == "xmltv_ns") {
                        val parts = text.split('.')
                        val season = (parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0) + 1
                        val episode = (parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0) + 1
                        if (!parts.getOrNull(1)?.trim().isNullOrEmpty())
                            p.episode = if (parts[0].trim().isEmpty()) "E$episode" else "S${season}E$episode"
                    } else if (system.isEmpty() || system == "onscreen" || system == "common") p.episode = text
                }
                else -> skip(xml)
            }
        }
    }

    /** The text of the element the parser is on, up to its end (the end tag consumed). */
    private fun readText(xml: XmlPullParser): String {
        val depth = xml.depth
        var text: StringBuilder? = null
        var single: String? = null
        while (true) {
            val e = xml.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xml.depth == depth)) break
            if (e == XmlPullParser.TEXT || e == XmlPullParser.ENTITY_REF || e == XmlPullParser.CDSECT) {
                val t = xml.text ?: continue
                if (single == null && text == null) single = t
                else {
                    if (text == null) text = StringBuilder(single!!)
                    text.append(t)
                }
            }
        }
        return text?.toString() ?: single ?: ""
    }

    private fun skip(xml: XmlPullParser) {
        val depth = xml.depth
        while (true) {
            val e = xml.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && xml.depth == depth)) break
        }
    }

    private fun simplified(s: String): String {
        if (s.isEmpty()) return s
        var needs = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '\n' || c == '\r' || c == '\t' || (c == ' ' && (i == 0 || i == s.length - 1 || s[i - 1] == ' '))) { needs = true; break }
        }
        if (!needs) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c.isWhitespace()) { if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ') } else sb.append(c)
        }
        return sb.toString().trim()
    }
}

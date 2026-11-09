/*
 * VLC Redesign, Live TV: the index of a guide, what was read of its XMLTV file for these channels, kept next to
 * it (as the desktop's service.cpp GuideIndex). A guide of 200 MB takes many seconds to read again at every
 * start; its index a fraction of a second. It holds every programme from the first day kept on, the texts once
 * each, and names the file of the descriptions it goes with.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object GuideIndex {
    private const val MAGIC = 0x56544749   // "VTGI"
    // (2: the programmes without overlaps; 3: what the limits kept; an older index is read again from its guide)
    private const val VERSION = 3

    class Index {
        val lists = HashMap<String, List<Entry>>()
        val idByName = HashMap<String, String>()
        val languages = HashSet<String>()
        var descName = ""
        /** the end the days ahead were shortened to (the programmes a device holds), 0 when they were not */
        var shortenedUntil = 0L
        /** the reading stopped by the limits: the programmes of part of the channels only */
        var limited = false
    }

    /** What the index was made for: the channels, the languages, the days (and the limits: [extra]). */
    fun signatureOf(filter: GuideFilter, days: Int, extra: String = ""): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(days.toString().toByteArray())
        if (extra.isNotEmpty()) md.update(("/" + extra).toByteArray())
        md.update("\n\n".toByteArray())
        md.update(filter.languages.sorted().joinToString("\n").toByteArray())
        md.update("\n\n".toByteArray())
        md.update(filter.keys.sorted().joinToString("\n").toByteArray())
        return md.digest()
    }

    fun write(path: File, xmltv: File, signature: ByteArray, index: Index): Boolean {
        val desc = File(xmltv.parentFile, index.descName)
        // (the texts are shared by the reading, one copy each: found by identity, a map of 500,000 texts' copies held
        // 20 MB more as the index was written)
        val ids = java.util.IdentityHashMap<String, Int>()
        val texts = ArrayList<String>()
        fun idOf(text: String) = ids.getOrPut(text) { texts.add(text); texts.size - 1 }
        // the texts first (their ids come from the entries)
        for (list in index.lists.values) for (e in list) { idOf(e.title); idOf(e.subtitle); idOf(e.category); idOf(e.episode) }
        val tmp = File(path.path + ".tmp")
        return try {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(xmltv.length())
                out.writeLong(xmltv.lastModified())
                out.writeInt(signature.size); out.write(signature)
                writeString(out, index.descName)
                out.writeLong(if (desc.exists()) desc.length() else -1L)
                out.writeLong(index.shortenedUntil)
                out.writeBoolean(index.limited)
                out.writeInt(index.languages.size)
                for (l in index.languages) writeString(out, l)
                out.writeInt(index.idByName.size)
                for ((k, v) in index.idByName) { writeString(out, k); writeString(out, v) }
                out.writeInt(texts.size)
                for (t in texts) writeString(out, t)
                out.writeInt(index.lists.size)
                for ((key, list) in index.lists) {
                    writeString(out, key)
                    out.writeInt(list.size)
                    for (e in list) {
                        out.writeLong(e.start); out.writeLong(e.stop)
                        out.writeInt(ids[e.title]!!); out.writeInt(ids[e.subtitle]!!)
                        out.writeInt(ids[e.category]!!); out.writeInt(ids[e.episode]!!)
                        out.writeLong(e.descOffset); out.writeInt(e.descLength)
                    }
                }
            }
            tmp.renameTo(path) || run { path.delete(); tmp.renameTo(path) }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    /**
     * The index when it is the one of this XMLTV file and these channels, null otherwise; only the programmes between
     * [from] and [until] (seconds; 0: no bound) kept. Read as a stream: an index of 500,000 programmes was read whole
     * into memory first, then copied for the days kept.
     */
    fun read(path: File, xmltv: File, signature: ByteArray, from: Long = 0L, until: Long = 0L): Index? {
        if (!path.exists()) return null
        val size = path.length()
        return try {
            DataInputStream(BufferedInputStream(path.inputStream(), 256 * 1024)).use { b ->
                fun str(): String {
                    val n = b.readInt()
                    if (n < 0 || n > size) throw IOException("bad index")
                    if (n == 0) return ""
                    val bytes = ByteArray(n)
                    b.readFully(bytes)
                    return String(bytes, Charsets.UTF_8)
                }
                if (b.readInt() != MAGIC || b.readInt() != VERSION) return null
                if (b.readLong() != xmltv.length() || b.readLong() != xmltv.lastModified()) return null
                val sigLen = b.readInt()
                if (sigLen != signature.size) return null
                val sig = ByteArray(sigLen).also { b.readFully(it) }
                if (!sig.contentEquals(signature)) return null
                val index = Index()
                index.descName = str()
                val descSize = b.readLong()
                // the descriptions it points into, as they were written
                val desc = File(xmltv.parentFile, index.descName)
                if (index.descName.isEmpty() || index.descName.contains('/') || (if (desc.exists()) desc.length() else -1L) != descSize) return null
                index.shortenedUntil = b.readLong()
                index.limited = b.readBoolean()
                repeat(count(b.readInt(), size)) { index.languages.add(str()) }
                repeat(count(b.readInt(), size)) { val k = str(); index.idByName[k] = str() }
                val textCount = count(b.readInt(), size)
                val texts = Array(textCount) { str() }
                val keys = count(b.readInt(), size)
                for (k in 0 until keys) {
                    val key = str()
                    val n = b.readInt()
                    if (n < 0 || n > (1 shl 24)) return null
                    var list: ArrayList<Entry>? = null
                    for (i in 0 until n) {
                        val start = b.readLong()
                        val stop = b.readLong()
                        val title = b.readInt(); val subtitle = b.readInt(); val category = b.readInt(); val episode = b.readInt()
                        val offset = b.readLong()
                        val length = b.readInt()
                        if (title !in 0 until textCount || subtitle !in 0 until textCount || category !in 0 until textCount || episode !in 0 until textCount) return null
                        // the days kept only
                        if ((from > 0L && stop > 0L && stop < from) || (until > 0L && start > until)) continue
                        if (list == null) list = ArrayList(n)
                        list.add(Entry(start, stop, texts[title], texts[subtitle], texts[category], texts[episode], offset, length))
                    }
                    if (list != null) {
                        list.trimToSize()
                        index.lists[key] = list
                    }
                }
                index
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun count(n: Int, size: Long): Int = if (n < 0 || n > size) throw IOException("bad index") else n

    /** A description of the file of a guide's descriptions. */
    fun readDescription(path: String, offset: Long, length: Int): String {
        if (offset < 0 || length <= 0 || path.isEmpty()) return ""
        return try {
            java.io.RandomAccessFile(path, "r").use { f ->
                f.seek(offset)
                val b = ByteArray(length)
                f.readFully(b)
                String(b, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }
}

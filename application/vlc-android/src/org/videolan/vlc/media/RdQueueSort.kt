/*
 * VLC Redesign: the queue's Sort, as the desktop's (its keys: Title, Duration, Artist, Album, Album Artist, Genre,
 * Date, Track Number, Disc Number, URL, File modified; ascending or descending). The order is computed here, and the
 * queue gets it by moves, so the playing item keeps playing and the views follow (PlaylistManager.sortQueue).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.media

import org.videolan.medialibrary.interfaces.media.MediaWrapper
import java.text.Collator
import java.util.Locale

object RdQueueSort {
    /** The keys, in the desktop's order (the strings are its texts) */
    enum class Key { TITLE, DURATION, ARTIST, ALBUM, ALBUM_ARTIST, GENRE, DATE, TRACK_NUMBER, DISC_NUMBER, URL, FILE_MODIFIED }

    /** The fields one sort key reads, so the order can be computed (and tested) without media objects */
    data class Fields(val title: String?, val length: Long, val artist: String?, val album: String?, val albumArtist: String?,
                      val genre: String?, val year: Int, val track: Int, val disc: Int, val url: String?, val modified: Long)

    fun fields(m: MediaWrapper) = Fields(m.title, m.length, m.artistName, m.albumName, m.albumArtistName, m.genre,
            m.releaseYear, m.trackNumber, m.discNumber, m.uri?.toString(), m.lastModified)

    private val collator: Collator by lazy { Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY } }

    /** Empty texts and unknown numbers go last, whatever the direction */
    private fun text(a: String?, b: String?): Int {
        val x = a.orEmpty().trim()
        val y = b.orEmpty().trim()
        return when {
            x.isEmpty() && y.isEmpty() -> 0
            x.isEmpty() -> 1
            y.isEmpty() -> -1
            else -> collator.compare(x, y)
        }
    }

    private fun number(a: Long, b: Long) = when {
        a <= 0 && b <= 0 -> 0
        a <= 0 -> 1
        b <= 0 -> -1
        else -> a.compareTo(b)
    }

    fun compare(key: Key, a: Fields, b: Fields): Int = when (key) {
        Key.TITLE -> text(a.title, b.title)
        Key.DURATION -> number(a.length, b.length)
        Key.ARTIST -> text(a.artist, b.artist)
        Key.ALBUM -> text(a.album, b.album)
        Key.ALBUM_ARTIST -> text(a.albumArtist, b.albumArtist)
        Key.GENRE -> text(a.genre, b.genre)
        Key.DATE -> number(a.year.toLong(), b.year.toLong())
        Key.TRACK_NUMBER -> number(a.track.toLong(), b.track.toLong())
        Key.DISC_NUMBER -> number(a.disc.toLong(), b.disc.toLong())
        Key.URL -> text(a.url, b.url)
        Key.FILE_MODIFIED -> number(a.modified, b.modified)
    }

    /** The new order: the old indices in their sorted order (stable: equal items keep their order) */
    fun order(items: List<Fields>, key: Key, descending: Boolean): List<Int> {
        val unknownLast = { f: Fields -> isUnknown(key, f) }
        return items.indices.sortedWith { i, j ->
            val ui = unknownLast(items[i])
            val uj = unknownLast(items[j])
            when {
                ui != uj -> if (ui) 1 else -1
                else -> compare(key, items[i], items[j]).let { if (descending) -it else it }
            }
        }
    }

    private fun isUnknown(key: Key, f: Fields) = when (key) {
        Key.TITLE -> f.title.isNullOrBlank()
        Key.DURATION -> f.length <= 0
        Key.ARTIST -> f.artist.isNullOrBlank()
        Key.ALBUM -> f.album.isNullOrBlank()
        Key.ALBUM_ARTIST -> f.albumArtist.isNullOrBlank()
        Key.GENRE -> f.genre.isNullOrBlank()
        Key.DATE -> f.year <= 0
        Key.TRACK_NUMBER -> f.track <= 0
        Key.DISC_NUMBER -> f.disc <= 0
        Key.URL -> f.url.isNullOrBlank()
        Key.FILE_MODIFIED -> f.modified <= 0
    }

    /** The moves (from, to) that turn the list into [order], each one as MediaWrapperList.move takes it */
    fun moves(order: List<Int>): List<Pair<Int, Int>> {
        val at = order.indices.toMutableList()
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in order.indices) {
            val j = at.indexOf(order[i])
            if (j != i) {
                out += j to i
                at.removeAt(j)
                at.add(i, order[i])
            }
        }
        return out
    }
}

/*
 * VLC Redesign: Export Playlist, as the desktop's (MLPlaylistContextMenu.qml): the playlist as an M3U8 file where the
 * user chooses (the system's "save as"). Its lines: #EXTM3U, then #EXTINF (the length in seconds, "artist - title")
 * and the address of each item (a file as its path, the others as their address).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import java.lang.ref.WeakReference

object RdPlaylistExport {
    private const val TAG = "VLC/RdPlaylistExport"

    /** One item of the file: the path of a local file, the address of anything else */
    data class Entry(val uri: String, val title: String?, val artist: String?, val lengthMs: Long)

    fun entry(m: MediaWrapper) = Entry(m.uri?.toString().orEmpty(), m.title, m.artistName, m.length)

    /** The M3U8 text (UTF-8, as its extension says) */
    fun m3u8(entries: List<Entry>): String = buildString {
        append("#EXTM3U\n")
        for (e in entries) {
            if (e.uri.isEmpty()) continue
            val name = listOfNotNull(e.artist?.trim()?.takeIf { it.isNotEmpty() }, e.title?.trim()?.takeIf { it.isNotEmpty() })
                .joinToString(" - ").replace('\n', ' ').replace('\r', ' ')
            val seconds = if (e.lengthMs > 0) e.lengthMs / 1000 else -1
            append("#EXTINF:").append(seconds).append(',').append(name).append('\n')
            append(location(e.uri)).append('\n')
        }
    }

    /** "file:///sdcard/Music/a%20b.mp3" -> "/sdcard/Music/a b.mp3"; any other address as it is */
    fun location(uri: String): String {
        if (!uri.startsWith("file://")) return uri
        return try {
            java.net.URLDecoder.decode(uri.removePrefix("file://").replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            uri
        }
    }

    /** A name the "save as" can offer: the playlist's, without what a file name cannot hold */
    fun fileName(title: String?) = (title?.trim().orEmpty().ifEmpty { "playlist" })
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_").take(100) + ".m3u8"

    /** one "save as" at a time, under a name the activity made again can ask its answer by */
    private const val KEY = "rd_playlist_export"
    /** the id of the playlist the "save as" on screen is for (0: none): it outlives the activity and the process */
    private const val KEY_PENDING = "rd_playlist_export_pending"
    private var launcher: ActivityResultLauncher<String>? = null
    private var host: WeakReference<FragmentActivity>? = null

    /**
     * The answer of a "save as" opened before [activity] was made again (the phone turned while the picker was up,
     * or the app closed behind it) is asked for again: it went to the old activity's one-time key, and the file the
     * picker had made stayed empty with no word.
     */
    fun attach(activity: FragmentActivity) {
        if (host?.get() === activity) return
        if (Settings.getInstance(activity).getLong(KEY_PENDING, 0L) != 0L) register(activity)
    }

    private fun register(activity: FragmentActivity): ActivityResultLauncher<String> {
        host = WeakReference(activity)
        val made = activity.activityResultRegistry.register(KEY, ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val settings = Settings.getInstance(activity)
            val id = settings.getLong(KEY_PENDING, 0L)
            settings.putSingle(KEY_PENDING, 0L)
            launcher?.unregister()
            launcher = null
            host = null
            if (uri == null || id == 0L) return@register
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        // (read again by its id: the activity that asked may be gone, the playlist is not)
                        val tracks = Medialibrary.getInstance().getPlaylist(id, true, false)?.tracks
                        if (tracks == null) false
                        else {
                            val text = m3u8(tracks.map { entry(it) })
                            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "The playlist could not be written", e)
                        false
                    }
                }
                UiTools.snacker(activity, activity.getString(if (ok) R.string.rd_playlist_exported else R.string.rd_playlist_export_failed))
            }
        }
        launcher = made
        return made
    }

    fun export(activity: FragmentActivity, playlist: Playlist) {
        // (registered before the id is kept: an answer parked for an older "save as" is given to that one)
        val made = register(activity)
        Settings.getInstance(activity).putSingle(KEY_PENDING, playlist.id)
        try {
            made.launch(fileName(playlist.title))
        } catch (e: Exception) {
            made.unregister()
            launcher = null
            host = null
            Settings.getInstance(activity).putSingle(KEY_PENDING, 0L)
            Log.w(TAG, "No file picker: ${e.message}")
            UiTools.snacker(activity, activity.getString(R.string.rd_no_file_picker_save))
        }
    }
}

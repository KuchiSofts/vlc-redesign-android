/*
 * VLC Redesign: the lines of the subtitle track shown, read from where they come from (a file beside the video or
 * added to it, else the video's own track), for the sync timeline (SubtitleSyncDialog) and the marks on the seek bar
 * (RdSeekCueMarks). Local files only: a stream's subtitles are not read again.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.repository.SlaveRepository
import java.io.File

object RdCueLoader {
    private const val TAG = "VLC/RdCueLoader"

    /** What tells the subtitle track shown apart: the video, the track chosen, how many there are */
    fun key(service: PlaybackService, uri: Uri): String {
        val tracks = service.spuTracks?.count { it.getId() != "-1" } ?: 0
        return "$uri|${service.spuTrack}|$tracks"
    }

    /**
     * The lines of the track shown (none chosen: null); called on the main thread for the track list, the reading
     * on IO
     */
    suspend fun load(context: Context, service: PlaybackService, media: MediaWrapper, uri: Uri): List<SubtitleCues.Cue>? {
        val tracks = service.spuTracks?.filter { it.getId() != "-1" } ?: emptyList()
        val index = tracks.indexOfFirst { it.getId() == service.spuTrack }
        if (index < 0) return null
        val name = tracks[index].getName()
        val ctx = context.applicationContext
        return withContext(Dispatchers.IO) {
            if (uri.scheme != "file") return@withContext emptyList()
            val video = File(uri.path ?: return@withContext emptyList())
            // the files the player opened beside the tracks of the video: those next to it, those added
            val added = SlaveRepository.getInstance(ctx).getSlaves(media.location)
                    .mapNotNull { Uri.parse(it.uri).path?.let { p -> File(p) } }
            val files = (SubtitleCues.siblings(video) + added).distinctBy { it.absolutePath }
            val embedded = (tracks.size - files.size).coerceAtLeast(0)
            if (BuildConfig.DEBUG) Log.d(TAG, "track $index/${tracks.size} \"$name\", files ${files.map { it.name }}")
            if (index < embedded) SubtitleCues.readEmbedded(ctx, uri, index)
            else {
                // the file whose name the track's carries, else in the order the player added them
                val file = files.firstOrNull { name.contains(it.name) || name.contains(it.nameWithoutExtension) }
                        ?: files.getOrNull(index - embedded) ?: files.firstOrNull()
                file?.let { SubtitleCues.readFile(it) } ?: emptyList()
            }
        }
    }
}

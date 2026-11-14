/*
 * VLC Redesign: the desktop's two queue buttons in the player's menu: Sort (its keys; the key chosen again turns the
 * order around, as the desktop's sort control) and Add files to the queue (the system's file picker).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.media.RdQueueSort

object RdQueueActions {
    private const val TAG = "VLC/RdQueueActions"
    private const val KEY_SORT = "rd_queue_sort_key"
    private const val KEY_SORT_DESC = "rd_queue_sort_desc"

    private val NAMES = mapOf(
        RdQueueSort.Key.TITLE to R.string.rd_sort_title, RdQueueSort.Key.DURATION to R.string.rd_sort_duration,
        RdQueueSort.Key.ARTIST to R.string.rd_sort_artist, RdQueueSort.Key.ALBUM to R.string.rd_sort_album,
        RdQueueSort.Key.ALBUM_ARTIST to R.string.rd_sort_album_artist, RdQueueSort.Key.GENRE to R.string.rd_sort_genre,
        RdQueueSort.Key.DATE to R.string.rd_sort_date, RdQueueSort.Key.TRACK_NUMBER to R.string.rd_sort_track_number,
        RdQueueSort.Key.DISC_NUMBER to R.string.rd_sort_disc_number, RdQueueSort.Key.URL to R.string.rd_sort_url,
        RdQueueSort.Key.FILE_MODIFIED to R.string.rd_sort_file_modified)

    fun sort(activity: FragmentActivity, service: PlaybackService) {
        val settings = Settings.getInstance(activity)
        val keys = RdQueueSort.Key.entries
        val current = keys.firstOrNull { it.name == settings.getString(KEY_SORT, null) }
        val descending = settings.getBoolean(KEY_SORT_DESC, false)
        val labels = keys.map { key ->
            activity.getString(NAMES.getValue(key)) + if (key == current) (if (descending) "  ↓" else "  ↑") else ""
        }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rd_sort_queue)
            .setItems(labels) { _, which ->
                val key = keys[which]
                // (the key sorted by last time: the other way round, as the desktop's control)
                val desc = key == current && !descending
                settings.putSingle(KEY_SORT, key.name)
                settings.putSingle(KEY_SORT_DESC, desc)
                service.playlistManager.sortQueue(key, desc)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        RdTvFocusRing.attach(dialog.window)
    }

    fun addFiles(activity: FragmentActivity, service: PlaybackService) {
        // (registered for this one pick, at any time: a registry entry without a lifecycle, removed once it answered)
        var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register("rd_add_files_" + System.nanoTime(), ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            launcher?.unregister()
            if (uris.isNullOrEmpty()) return@register
            val media = uris.mapNotNull { uri: Uri ->
                // (kept readable after a restart of the app: the queue is saved and opened again)
                try {
                    activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(TAG, "No lasting permission for $uri: ${e.message}")
                }
                MLServiceLocator.getAbstractMediaWrapper(uri)
            }
            // (the video player stopped while the picker covered it and gets its queue back once it is in front again:
            // the files are added after that, to the queue as it is then, else they would replace it)
            activity.lifecycleScope.launch {
                var waited = 0L
                while ((!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                                || PlaybackService.instance?.playlistManager?.hasCurrentMedia() != true) && waited < 8000L) {
                    kotlinx.coroutines.delay(100L)
                    waited += 100L
                }
                // (a little more: the player puts its queue back in one go, from the service it connects to)
                kotlinx.coroutines.delay(300L)
                (PlaybackService.instance ?: service).append(media)
                UiTools.snacker(activity, activity.resources.getQuantityString(R.plurals.rd_files_added_queue, media.size, media.size))
            }
        }
        try {
            launcher.launch(arrayOf("video/*", "audio/*"))
        } catch (e: Exception) {
            // (a TV without the system's file picker)
            launcher.unregister()
            Log.w(TAG, "No file picker: ${e.message}")
            UiTools.snacker(activity, activity.getString(R.string.rd_no_file_picker))
        }
    }
}

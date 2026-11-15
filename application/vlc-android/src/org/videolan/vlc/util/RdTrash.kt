/*
 * VLC Redesign: a media file deleted from the app goes to the system's trash (Android 11+), as on the desktop, where
 * Delete File From System moves it to the Recycle Bin or the Trash (#46): MediaStore keeps it 30 days, then removes
 * it. A file MediaStore does not know (not indexed, a folder, a storage it does not serve) is not trashed: the
 * caller asks before deleting it for good.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.WorkerThread
import java.io.File

object RdTrash {
    /** the system has a trash for media files (Android 11+) */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** whether [uri] is a local file (file:// or a bare path), the only kind trashed */
    fun isLocalFile(uri: Uri): Boolean = (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE) && !uri.path.isNullOrEmpty()

    /**
     * Moves the local file [uri] to the trash; true once it is there (it is no longer at its path).
     * False when it could not be: no trash (Android 10 and older), not a local file, unknown to MediaStore, or
     * refused (the app may not change it).
     */
    @WorkerThread
    fun moveToTrash(resolver: ContentResolver, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isLocalFile(uri)) return false
        val path = uri.path ?: return false
        val file = File(path)
        if (!file.isFile) return false
        val files = MediaStore.Files.getContentUri("external")
        return try {
            val id = resolver.query(files, arrayOf(MediaStore.MediaColumns._ID), MediaStore.MediaColumns.DATA + "=?", arrayOf(file.path), null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
            resolver.update(ContentUris.withAppendedId(files, id), values, null, null) > 0 && !file.exists()
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
    }
}

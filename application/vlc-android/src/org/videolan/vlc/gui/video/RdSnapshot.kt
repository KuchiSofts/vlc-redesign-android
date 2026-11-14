/*
 * VLC Redesign: the desktop's Snapshots settings (Settings › Video › Snapshots) for the player's snapshot: the
 * folder, the file name prefix, the format, the size, numbered files instead of the date, and the preview after
 * the capture (VLC's snapshot-path, -prefix, -format, -width, -height, -sequential, -preview).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RdSnapshot {
    private const val TAG = "VLC/RdSnapshot"
    const val KEY_DIR = "rd_snapshot_dir"
    const val KEY_PREFIX = "rd_snapshot_prefix"
    const val KEY_FORMAT = "rd_snapshot_format"
    const val KEY_WIDTH = "rd_snapshot_width"
    const val KEY_HEIGHT = "rd_snapshot_height"
    const val KEY_SEQUENTIAL = "rd_snapshot_sequential"
    const val KEY_PREVIEW = "rd_snapshot_preview"
    private const val KEY_COUNTER = "rd_snapshot_counter"
    const val DEFAULT_PREFIX = "vlc_"

    /** The folder under the shared storage: Pictures › Screenshots (as before), Pictures › VLC or DCIM › VLC */
    fun relativeDir(prefs: SharedPreferences): String = when (prefs.getString(KEY_DIR, "screenshots")) {
        "pictures" -> "${Environment.DIRECTORY_PICTURES}/VLC"
        "dcim" -> "${Environment.DIRECTORY_DCIM}/VLC"
        else -> "${Environment.DIRECTORY_PICTURES}/${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Environment.DIRECTORY_SCREENSHOTS else "Screenshots"}"
    }

    fun png(prefs: SharedPreferences) = prefs.getString(KEY_FORMAT, "jpg") == "png"

    /** A file name made only of what a file name may hold (the prefix is the user's text) */
    fun cleanPrefix(prefix: String?): String =
        (prefix ?: DEFAULT_PREFIX).replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_").take(64)

    /** "vlc_20261114_101500.jpg", or "vlc_00012.png" when the files are numbered (the next number is kept) */
    fun fileName(prefs: SharedPreferences, now: Date = Date()): String {
        val prefix = cleanPrefix(prefs.getString(KEY_PREFIX, DEFAULT_PREFIX))
        val ext = if (png(prefs)) "png" else "jpg"
        if (prefs.getBoolean(KEY_SEQUENTIAL, false)) {
            val n = prefs.getInt(KEY_COUNTER, 1)
            prefs.edit { putInt(KEY_COUNTER, n + 1) }
            return String.format(Locale.US, "%s%05d.%s", prefix, n, ext)
        }
        return "$prefix${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.$ext"
    }

    /**
     * The size asked for: a width (0: the video's own) and a height (0: in proportion to the width, -1: the
     * video's own), as VLC's snapshot-width and snapshot-height
     */
    fun targetSize(width: Int, height: Int, wantedWidth: Int, wantedHeight: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val w = if (wantedWidth > 0) wantedWidth else width
        val h = when {
            wantedHeight > 0 -> wantedHeight
            wantedHeight == 0 && wantedWidth > 0 -> (height.toLong() * w / width).toInt().coerceAtLeast(1)
            else -> height
        }
        return w to h
    }

    fun scaled(bitmap: Bitmap, prefs: SharedPreferences): Bitmap {
        val wantedWidth = prefs.getString(KEY_WIDTH, "")?.trim()?.toIntOrNull()?.takeIf { it in 1..8192 } ?: 0
        val wantedHeight = prefs.getString(KEY_HEIGHT, "")?.trim()?.toIntOrNull()?.takeIf { it in -1..8192 } ?: 0
        val (w, h) = targetSize(bitmap.width, bitmap.height, wantedWidth, wantedHeight)
        return if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** Writes the snapshot where the settings say; the file it is in the shared storage, or null */
    fun save(context: Context, bitmap: Bitmap, prefs: SharedPreferences): File? {
        val dir = relativeDir(prefs)
        val name = fileName(prefs)
        val png = png(prefs)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStorageDirectory(), "$dir/$name")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.applicationContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { bitmap.compress(format, if (png) 100 else 95, it) } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { bitmap.compress(format, if (png) 100 else 95, it) }
                // (in the gallery, without a second copy)
                android.media.MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "The snapshot could not be saved", e)
            null
        }
    }
}

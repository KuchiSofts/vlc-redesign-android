/*
 * VLC Redesign: Add Subtitles › From Link, as the desktop's (mlsubtitleassociations_link.cpp): a direct HTTPS link to
 * a subtitle file (.srt, .ass, .ssa, .sub, .vtt, .ttml or .smi), downloaded (20 MB at most, 30 s without data or
 * 90 s in all, never sent on to plain HTTP) into the app's own folder and added to the video playing.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.vlc.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object RdSubtitleLink {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val KEEP_MS = 90L * 24 * 60 * 60 * 1000
    private val EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "vtt", "ttml", "smi")

    /** What is wrong with a link: nothing (its file name) or why */
    sealed class Check {
        data class Ok(val url: String, val name: String) : Check()
        object NotSubtitle : Check()
    }

    /** An HTTPS link without a user name or password whose file is a subtitle; pure, for the tests */
    fun check(text: String): Check {
        val uri = try {
            URI(text.trim())
        } catch (e: Exception) {
            return Check.NotSubtitle
        }
        if (!"https".equals(uri.scheme, true) || uri.host.isNullOrEmpty() || uri.userInfo != null) return Check.NotSubtitle
        val name = uri.path.orEmpty().substringAfterLast('/')
        if (name.isEmpty() || name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return Check.NotSubtitle
        // (a file name, no folder of the device: the name of the path's last part only)
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_").take(120)
        return Check.Ok(uri.toASCIIString(), safe)
    }

    sealed class Result {
        data class Saved(val file: File) : Result()
        object TooBig : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun download(context: Context, link: Check.Ok): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val connection = URL(link.url).openConnection() as HttpURLConnection
            RdTls.apply(connection)
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                // (Java follows a redirect only to the same protocol: never from HTTPS to HTTP)
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
                connection.setRequestProperty("User-Agent", "VLC-Media-Player-Redesign-Android/${BuildConfig.RD_VERSION_NAME}")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) return@withContext Result.Failed("HTTP $status")
                if (connection.contentLengthLong > MAX_BYTES) return@withContext Result.TooBig
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) return@withContext Result.TooBig
                        if (System.currentTimeMillis() - started > 90_000L) return@withContext Result.Failed("timeout")
                    }
                }
                // (the app's own internal folder: no shared storage between the download and the player)
                val folder = File(context.filesDir, "subtitles/links").apply { mkdirs() }
                // (links of long ago don't pile up: those not used for 90 days go)
                val old = System.currentTimeMillis() - KEEP_MS
                folder.listFiles()?.forEach { if (it.isFile && it.lastModified() < old) it.delete() }
                val file = File(folder, link.name)
                file.writeBytes(out.toByteArray())
                Result.Saved(file)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

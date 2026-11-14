/*
 * VLC Redesign: the app's own update check. Once a day it reads the releases of
 * KuchiSofts/vlc-redesign on GitHub; when the newest release that carries an Android APK is newer
 * than this app, the user is offered to download it (Android installs it over this one: every
 * release is signed with the same key). Nothing is downloaded or installed by itself.
 * A desktop-only release (no APK) does not hide the last Android one: the list is read, not
 * only GitHub's "latest" release.
 */
package org.videolan.vlc.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object RdUpdateCheck {
    private const val TAG = "VLC/RdUpdateCheck"
    /** the switch in Settings › General and in the privacy step of the welcome (on by default) */
    const val KEY_CHECK = "rd_check_updates"
    private const val KEY_LAST = "rd_update_last_check"
    /** a version the user chose to skip: not offered again by the daily check */
    const val KEY_SKIPPED = "rd_update_skipped"
    /** the newest releases first; a few desktop-only ones in a row still leave the last APK in the page (ten: the
     *  notes and files of 30 made 1.4 MB to download each day) */
    private const val RELEASES = "https://api.github.com/repos/KuchiSofts/vlc-redesign/releases?per_page=10"
    const val RELEASES_PAGE = "https://github.com/KuchiSofts/vlc-redesign/releases"
    private val INTERVAL = TimeUnit.DAYS.toMillis(1)

    data class Release(val version: String, val apkUrl: String, val pageUrl: String)

    /** What a check asked for by the user found (About, Settings) */
    sealed class Result {
        object UpToDate : Result()
        data class Available(val release: Release) : Result()
        /** GitHub could not be reached (offline, timeout) */
        object Offline : Result()
        /** GitHub answered with an HTTP status other than 200 (an outage) */
        data class Unexpected(val status: Int) : Result()
        /** GitHub was asked too often from this network (60 an hour without an account, shared by every device behind
         *  one address): asked again after [resetAt] (ms since the epoch), or later when it did not say */
        data class RateLimited(val resetAt: Long?) : Result()
    }

    private class HttpStatus(val status: Int, val rateLimited: Boolean = false, val resetAt: Long? = null) : Exception("HTTP $status")

    /** GitHub's rate limit: 429, or 403 with no requests left (x-ratelimit-remaining 0); pure, for the tests */
    fun isRateLimited(status: Int, remaining: String?): Boolean = status == 429 || status == 403 && remaining?.trim() == "0"

    /** x-ratelimit-reset (seconds since the epoch) in ms, when it is still to come */
    fun resetAt(header: String?, now: Long = System.currentTimeMillis()): Long? =
        header?.trim()?.toLongOrNull()?.times(1000L)?.takeIf { it > now }

    /** The newer release to offer, or null (switched off, checked today, skipped, none, offline) */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val settings = Settings.getInstance(context)
        if (!settings.getBoolean(KEY_CHECK, true)) return@withContext null
        val now = System.currentTimeMillis()
        if (now - settings.getLong(KEY_LAST, 0L) in 0 until INTERVAL) return@withContext null
        val release = try {
            fetch()
        } catch (e: Exception) {
            Log.w(TAG, "No update check: ${e.message}")
            return@withContext null
        }
        settings.putSingle(KEY_LAST, now)
        release?.takeIf { isNewer(it.version, BuildConfig.RD_VERSION_NAME) && it.version != settings.getString(KEY_SKIPPED, null) }
    }

    /** A check the user asked for: now, whatever the switch, the day or a skipped version say */
    suspend fun checkNow(context: Context): Result = withContext(Dispatchers.IO) {
        val release = try {
            fetch()
        } catch (e: HttpStatus) {
            return@withContext if (e.rateLimited) Result.RateLimited(e.resetAt) else Result.Unexpected(e.status)
        } catch (e: Exception) {
            Log.w(TAG, "No update check: ${e.message}")
            return@withContext Result.Offline
        }
        Settings.getInstance(context).putSingle(KEY_LAST, System.currentTimeMillis())
        if (release != null && isNewer(release.version, BuildConfig.RD_VERSION_NAME)) Result.Available(release) else Result.UpToDate
    }

    private fun fetch(): Release? {
        val connection = URL(RELEASES).openConnection() as HttpURLConnection
        org.videolan.vlc.util.RdTls.apply(connection)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "VLC-Media-Player-Redesign-Android/${BuildConfig.RD_VERSION_NAME}")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw HttpStatus(status,
                    isRateLimited(status, connection.getHeaderField("x-ratelimit-remaining")),
                    resetAt(connection.getHeaderField("x-ratelimit-reset")))
            return pick(JSONArray(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The newest published release (not a draft, not a pre-release) that has the Android APK
     * `vlc-redesign-<version>-android.apk`; the order of the list does not matter
     */
    fun pick(releases: JSONArray): Release? {
        var best: Release? = null
        for (r in 0 until releases.length()) {
            val json = releases.optJSONObject(r) ?: continue
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) continue
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isEmpty()) continue
            val assets = json.optJSONArray("assets") ?: continue
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") != "vlc-redesign-$version-android.apk") continue
                val url = asset.optString("browser_download_url")
                if (url.isNotEmpty() && (best == null || isNewer(version, best.version)))
                    best = Release(version, url, json.optString("html_url"))
                break
            }
        }
        return best
    }

    /** "0.5.1" > "0.5.0", "0.10.0" > "0.9.2"; anything after a "-" is ignored */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/*
 * VLC Redesign: Settings › Privacy › Never remember items matching (the desktop's redesign/recentplay-filter): a
 * regular expression, case-insensitive; the items whose address or name matches it are not saved in the history,
 * nor their playback position. A text that is no valid expression is looked for as it is.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.SharedPreferences

object RdHistoryFilter {
    const val KEY = "rd_history_filter"

    @Volatile private var cached: Pair<String, Regex>? = null

    private fun regexOf(pattern: String): Regex {
        cached?.let { if (it.first == pattern) return it.second }
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
        }
        cached = pattern to regex
        return regex
    }

    /** True when [pattern] (empty: nothing) matches one of the item's [address] or [name] */
    fun matches(pattern: String?, address: String?, name: String?): Boolean {
        val p = pattern?.trim().orEmpty()
        if (p.isEmpty()) return false
        val regex = regexOf(p)
        return listOfNotNull(address, name, address?.let { decoded(it) }).any { it.isNotEmpty() && regex.containsMatchIn(it) }
    }

    /** "file:///sdcard/My%20Films/a.mkv" as it is written: "file:///sdcard/My Films/a.mkv" */
    private fun decoded(address: String) = try {
        java.net.URLDecoder.decode(address.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        address
    }

    fun excluded(prefs: SharedPreferences, address: String?, name: String?) =
        matches(prefs.getString(KEY, ""), address, name)
}

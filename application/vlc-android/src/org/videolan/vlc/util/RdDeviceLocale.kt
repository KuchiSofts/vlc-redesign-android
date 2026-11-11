/*
 * VLC Redesign: what the device is set to, for the lists of languages and countries (RdLocaleChoices): its own
 * languages (the system's, not the app's: the app can be set to another language), and its country (where its time
 * zone is, else its languages').
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.res.Resources
import android.os.Build
import java.util.Locale
import java.util.TimeZone

object RdDeviceLocale {

    /** The device's languages as tags ("he-IL", "en-US"), in its order. */
    fun languageTags(): List<String> {
        val config = Resources.getSystem().configuration
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) (0 until config.locales.size()).map { config.locales[it] }
                      else listOf(@Suppress("DEPRECATION") config.locale)
        return locales.filterNotNull().map { it.toLanguageTag() }.ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
    }

    /** The region of the device's time zone ("Asia/Jerusalem" -> "IL"), "" when it has none or can't be told. */
    fun zoneRegion(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ""
        return try { android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id) ?: "" } catch (e: Exception) { "" }
    }

    /** The device's country ("IL"), "" when nothing tells it. */
    fun country(): String = RdLocaleChoices.deviceCountry(zoneRegion(), languageTags())
}

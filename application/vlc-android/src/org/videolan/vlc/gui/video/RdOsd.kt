/*
 * VLC Redesign: Settings › Subtitles › On-Screen Messages, as the desktop's "Show feedback for shortcuts" (osd) and
 * "Show feedback for" (osd-duration): the player's messages over the video (speed, delays, zoom, a track
 * changed...) shown or not, and for how long. The desktop shows each one the same time (1 s by default, 0.1 to 10 s);
 * here a message the app shows longer (a hint of 5 s) keeps its share: the setting scales them all.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.content.SharedPreferences
import java.util.Locale

object RdOsd {
    const val KEY = "rd_osd"
    /** in tenths of a second, as the desktop's slider (1 = 0.1 s ... 100 = 10 s) */
    const val KEY_DURATION = "rd_osd_duration"
    const val DEFAULT_TENTHS = 10

    fun shown(settings: SharedPreferences) = settings.getBoolean(KEY, true)

    /** how long a message the app shows [duration] ms by default stays */
    fun duration(duration: Int, tenths: Int): Long = duration.toLong() * tenths.coerceIn(1, 100) / DEFAULT_TENTHS

    fun duration(duration: Int, settings: SharedPreferences): Long = duration(duration, settings.getInt(KEY_DURATION, DEFAULT_TENTHS))

    /** the row's value: "1.5" (seconds, in the language's numbers) */
    fun secondsText(tenths: Int, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", tenths.coerceIn(1, 100) / 10f)
}

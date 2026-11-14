/*
 * VLC Redesign: Settings › Subtitles › Timing › Subtitle delay, as the desktop's sub-delay (-5 to +5 s, in tenths
 * of a second): the delay a video without one of its own starts with (PlaylistManager.loadMediaMeta), as the audio's
 * global delay does.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.media

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.abs

object RdSubDelay {
    const val KEY = "rd_sub_delay"

    /** the delay in µs, as libvlc takes it */
    fun micros(tenths: Int): Long = tenths.coerceIn(-50, 50) * 100_000L

    fun micros(settings: SharedPreferences): Long = micros(settings.getInt(KEY, 0))

    /** the row's value: "+1.5", "0", "−2" (seconds, a true minus sign, the language's numbers) */
    fun text(tenths: Int, locale: Locale = Locale.getDefault()): String {
        val t = tenths.coerceIn(-50, 50)
        if (t == 0) return "0"
        val number = if (t % 10 == 0) String.format(locale, "%d", abs(t) / 10) else String.format(locale, "%.1f", abs(t) / 10f)
        return (if (t > 0) "+" else "−") + number
    }
}

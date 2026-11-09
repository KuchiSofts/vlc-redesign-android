/*
 * VLC Redesign: the desktop's dual subtitles (Settings › Subtitles › "Show both languages at once"): the second
 * language shown above the first one. libvlc 3 shows one subtitle track, so the player draws the second one itself
 * from its file (a subtitle downloaded for the video, a file beside it, its Subs folder's): the line of the moment,
 * following the playback time and the subtitle delay. These are the rules, without anything of Android's.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

object RdDualSubtitles {
    /** Settings › Subtitles: the second language shown too (with a second language only) */
    const val KEY_SHOW_BOTH = "rd_sub_show_both"

    /** the longest a line is looked back for (a line lasts less) */
    private const val LOOK_BACK_MS = 60_000L

    /**
     * The file's time of the moment: the player's [timeMs] less the subtitle delay ([delayUs], µs; later subtitles
     * have a positive delay), as libvlc shows the first language.
     */
    fun cueTime(timeMs: Long, delayUs: Long): Long = timeMs - delayUs / 1000L

    /** the text shown at [timeMs] (the file's time): the lines on then, in their order; "" for none */
    fun textAt(cues: List<SubtitleCues.Cue>, timeMs: Long): String {
        if (cues.isEmpty()) return ""
        // the last line started by then (the lines are in the order of their start)
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= timeMs) {
                last = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (last < 0) return ""
        val on = ArrayList<SubtitleCues.Cue>()
        var i = last
        while (i >= 0 && timeMs - cues[i].start <= LOOK_BACK_MS) {
            if (cues[i].end > timeMs && cues[i].text.isNotBlank()) on.add(cues[i])
            i--
        }
        return on.reversed().joinToString("\n") { it.text }.trim()
    }

    /**
     * The file of the second language: of the subtitles downloaded for the video ([downloaded]: path, language), then
     * the files beside it and in its Subs folder ([beside]: path, language); null when it has none.
     */
    fun fileOf(language: String, downloaded: List<Pair<String, String>>, beside: List<Pair<String, String>>): String? {
        if (language.isEmpty()) return null
        fun same(code: String) = code == language || code.substringBefore('-') == language.substringBefore('-')
        return downloaded.firstOrNull { same(it.second) }?.first ?: beside.firstOrNull { same(it.second) }?.first
    }

    /**
     * Where the second line sits: its bottom this far above the bottom of the video's picture (px), clear of the
     * first language's two lines (libvlc's text: a 16th of the picture's height at 100 %, [scalePercent] of it, plus
     * the subtitles' own margin, [marginPx] of the picture) on a picture [videoHeightPx] high.
     */
    fun bottomOffset(videoHeightPx: Int, scalePercent: Int, marginPx: Int, pictureHeightPx: Int): Int {
        if (videoHeightPx <= 0) return 0
        val line = videoHeightPx / 16f * scalePercent / 100f
        val margin = if (pictureHeightPx > 0) marginPx * videoHeightPx.toFloat() / pictureHeightPx else 0f
        return (videoHeightPx * 0.04f + 2.3f * line + margin).toInt()
    }
}

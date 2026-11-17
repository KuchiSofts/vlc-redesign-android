/*
 * VLC Redesign: the name a group of videos is shown with. The library names a group by the start the names of its
 * videos share: the episodes of a show give "My Show S01E" (S01E01, S01E02...), "Show 1x0" or "Show Episode".
 * Shown as the show's name, without what is left of an episode's number (#108; the desktop's MLGroup the same); a
 * name with nothing else stays as it is. (Every bracket escaped: Android's regex is ICU's.)
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

object RdShowName {
    private val marker = Regex(
        "(?:^|[\\s._\\-\\(\\[])(?:S\\d{0,2}(?:\\s*E\\d{0,3})?|Season\\s*\\d*|\\d{1,2}x\\d{0,3}" +
            "|E(?:p(?:isode)?)?\\.?\\s*\\d{0,4}|Part\\s*\\d*)$", RegexOption.IGNORE_CASE)
    private val tail = Regex("[\\s._\\-\\u2013\\u2014\\(\\[]+$")

    fun of(name: String?): String {
        if (name == null) return ""
        val shown = name.replace(marker, "").replace(tail, "").trim()
        return shown.ifEmpty { name }
    }

    /** the title a card shows (video_grid_card / video_list_card bind it): a group's show name, else the title */
    @JvmStatic
    fun ofItem(item: org.videolan.medialibrary.media.MediaLibraryItem?): String? =
        if (item is org.videolan.medialibrary.interfaces.media.VideoGroup) of(item.title) else item?.title
}

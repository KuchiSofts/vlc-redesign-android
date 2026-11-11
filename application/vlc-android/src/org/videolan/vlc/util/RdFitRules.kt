/*
 * VLC Redesign: the arithmetic of what fits a window: whole cards in a row, a dialog inside the window's edges, a
 * popup beside its control. Plain numbers (px in, px out), shared by the rows, the dialogs and the menus (RdFit).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

object RdFitRules {

    /** How many cards and how wide each: see [cards]. */
    class Cards(val count: Int, val width: Int)

    /**
     * The whole cards that fit [available] with [gap] between them, each at least [base] wide: as many as fit at
     * their own size, then grown a little so the row ends with the last card's edge (the desktop's rows: no card
     * cut by the window). A row narrower than one card gets one card of its width.
     */
    fun cards(available: Int, base: Int, gap: Int): Cards {
        if (available <= 0 || base <= 0) return Cards(1, base.coerceAtLeast(1))
        val count = ((available + gap) / (base + gap)).coerceAtLeast(1)
        val width = ((available - (count - 1) * gap) / count).coerceAtLeast(1)
        // (a card grows to a third more than its design at most: one card is not stretched across a page that
        // holds almost two; what is left stays empty at the row's end)
        return Cards(count, width.coerceAtMost(maxOf(base * MAX_GROWTH_PERCENT / 100, 1)))
    }

    /** The most a card grows over its design size to fill its row, in percent. */
    const val MAX_GROWTH_PERCENT = 135

    /** A size wanted (a dialog's, a popup's) kept inside [window] less [margin] on both sides. */
    fun size(wanted: Int, window: Int, margin: Int): Int {
        val most = (window - 2 * margin).coerceAtLeast(1)
        return wanted.coerceIn(1, most)
    }

    /**
     * Where a popup of [size] starts so that it stays inside [from]..[to] less [margin]: at [wanted] when it fits
     * there, else moved back in (its far edge first, then its near one: a popup larger than the room starts at the
     * room's start).
     */
    fun place(wanted: Int, size: Int, from: Int, to: Int, margin: Int): Int {
        val first = from + margin
        val last = to - margin - size
        return if (last < first) first else wanted.coerceIn(first, last)
    }

    /**
     * The room for a list that opens under (or over) its control: [below] when the list fits there or there is
     * more room than above, else above. Returns the height it may take, negative when it opens above.
     */
    fun dropHeight(wanted: Int, anchorTop: Int, anchorBottom: Int, top: Int, bottom: Int, margin: Int): Int {
        val below = bottom - margin - anchorBottom
        val above = anchorTop - margin - top
        return if (wanted <= below || below >= above) wanted.coerceAtMost(below.coerceAtLeast(1))
        else -wanted.coerceAtMost(above.coerceAtLeast(1))
    }
}

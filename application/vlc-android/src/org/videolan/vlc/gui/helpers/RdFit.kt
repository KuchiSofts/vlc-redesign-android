/*
 * VLC Redesign: everything sized from the window it is shown in. A dialog, a sheet, a menu or a list never leaves
 * the window (its edges kept clear by a margin), whatever the interface size (RdTvDesignScale) or the screen; a row
 * of cards shows whole cards. The base classes use these (VLCBottomSheetDialogFragment, every DialogFragment
 * through BaseActivity, RdDropdown, RdContextMenu, RdTvMenu), so a dialog needs no sizes of its own.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.util.RdFitRules

object RdFit {

    /** The gap kept between a dialog and the window's edge, in dp. */
    const val MARGIN = 16

    /** The window of [activity] on the screen, in px (the whole screen when it is not laid out yet). */
    fun frame(activity: Activity?): Rect {
        val decor = activity?.window?.decorView
        if (decor != null && decor.width > 0 && decor.height > 0) {
            val at = IntArray(2).also { decor.getLocationOnScreen(it) }
            return Rect(at[0], at[1], at[0] + decor.width, at[1] + decor.height)
        }
        val dm = (activity?.resources ?: android.content.res.Resources.getSystem()).displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** [wanted] px across, never wider than the window of [activity] less the margin on both sides. */
    fun width(activity: Activity?, wanted: Int, margin: Int = MARGIN.dp) = RdFitRules.size(wanted, frame(activity).width(), margin)

    /** [wanted] px high, never taller than the window of [activity] less the margin on both sides. */
    fun height(activity: Activity?, wanted: Int, margin: Int = MARGIN.dp) = RdFitRules.size(wanted, frame(activity).height(), margin)

    /**
     * A dialog's window kept inside the screen: a size it was given in px (a design's 1048 x 631 at a large
     * interface size, a tall form on a small screen) is cut to the screen less the margin, now and whenever it is
     * laid out again. Its content scrolls inside (the dialogs' own lists and scroll views).
     */
    fun dialog(window: Window?) {
        window ?: return
        val decor = window.decorView
        if (decor.getTag(R.id.rd_fit_listener) != null) return
        fun fit() {
            val dm = decor.resources.displayMetrics
            val lp = window.attributes ?: return
            val margin = MARGIN.dp
            val maxW = dm.widthPixels - 2 * margin
            val maxH = dm.heightPixels - 2 * margin
            if (maxW <= 0 || maxH <= 0) return
            // (a size in px only: MATCH_PARENT and WRAP_CONTENT are the window manager's, already inside the screen)
            val w = if (lp.width > maxW) maxW else lp.width
            val h = if (lp.height > maxH) maxH else lp.height
            if (w != lp.width || h != lp.height) window.setLayout(w, h)
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        decor.setTag(R.id.rd_fit_listener, listener)
        decor.addOnLayoutChangeListener(listener)
        fit()
    }

    /**
     * A dialog drawn at the design's own places ([width] x [height] px, its parts at fixed points): shown at that
     * size when the screen holds it with the margin, else the whole card smaller by one factor, so that nothing in
     * it moves or is cut (the design's 1048 x 631 window at the largest interface sizes). Sets the content and the
     * window's size.
     */
    fun design(dialog: android.app.Dialog, card: View, width: Int, height: Int) {
        val dm = dialog.context.resources.displayMetrics
        val margin = MARGIN.dp
        val k = minOf(1f, (dm.widthPixels - 2 * margin) / width.toFloat(), (dm.heightPixels - 2 * margin) / height.toFloat())
        if (k >= 1f) {
            dialog.setContentView(card, ViewGroup.LayoutParams(width, height))
            dialog.window?.setLayout(width, height)
            return
        }
        val w = (width * k).toInt()
        val h = (height * k).toInt()
        val box = android.widget.FrameLayout(dialog.context)
        // (from its own top left corner, whatever the reading direction: the card mirrors itself inside)
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        card.pivotX = 0f; card.pivotY = 0f
        card.scaleX = k; card.scaleY = k
        box.addView(card, android.widget.FrameLayout.LayoutParams(width, height, android.view.Gravity.TOP or android.view.Gravity.LEFT))
        box.clipChildren = false
        dialog.setContentView(box, ViewGroup.LayoutParams(w, h))
        dialog.window?.setLayout(w, h)
    }

    /**
     * A row that runs past its width ends in a short fade instead of a hard cut (the chips of the Live TV page,
     * the tabs and filters of its dialogs, a row of cards of mixed sizes).
     */
    fun fade(row: View, length: Int = 24.dp) {
        row.isHorizontalFadingEdgeEnabled = true
        row.setFadingEdgeLength(length)
    }

    /** The cards of a row: [RdFitRules.cards] for the row's width less what is kept at its two ends. */
    fun cards(rowWidth: Int, base: Int, gap: Int) = RdFitRules.cards(rowWidth, base, gap)

    /**
     * A row of cards of one size moves by whole cards: let go by the finger between two cards, it comes to rest
     * with a card at its start (the remote's focus already moves it one card at a time).
     */
    fun wholeCards(list: RecyclerView) {
        if (list.getTag(R.id.rd_fit_listener) != null) return
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findViewByPosition(lm.findFirstVisibleItemPosition()) ?: return
                val rtl = rv.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val lp = first.layoutParams as ViewGroup.MarginLayoutParams
                // how far the first card shown is past the row's start, and its whole step
                val past = if (rtl) (first.right + lp.rightMargin) - (rv.width - rv.paddingRight) else rv.paddingLeft - (first.left - lp.leftMargin)
                val step = first.width + lp.leftMargin + lp.rightMargin
                if (past <= 0 || step <= 0) return
                val forward = past > step / 2
                // (the row's end: nothing further to show, it rests there)
                if (forward && !rv.canScrollHorizontally(if (rtl) -1 else 1)) return
                val by = if (forward) step - past else -past
                rv.smoothScrollBy(if (rtl) -by else by, 0)
            }
        }
        list.setTag(R.id.rd_fit_listener, listener)
        list.addOnScrollListener(listener)
    }
}

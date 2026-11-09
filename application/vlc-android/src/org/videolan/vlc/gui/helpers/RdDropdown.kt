/*
 * VLC Redesign: the list a dropdown opens (SettingsDropdown.qml, the Samsung TV app's lists): every choice in a
 * scrolling list under the control, the one chosen in the accent, the device's own ones (a language or a country
 * it is set to, RdLocaleChoices) marked "On this device". It opens on [focus]: the remote's Up / Down move in it,
 * OK picks, Back closes; on a touch screen it opens scrolled there. Settings' dropdowns, the Live TV catalog's
 * filters, the guide's languages and the welcome's language all open this one.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import org.videolan.vlc.R
import org.videolan.vlc.util.RdLocaleChoices

object RdDropdown {

    /** One row: its words, whether it is the one chosen, whether the device is set to it. */
    class Row(val label: CharSequence, val checked: Boolean = false, val detected: Boolean = false)

    /** The rows of a list of [RdLocaleChoices], the one of [current] chosen. */
    fun rows(choices: List<RdLocaleChoices.Choice>, current: String?): List<Row> =
        choices.map { Row(it.label, it.value == (current ?: ""), it.detected) }

    /** "English  ·  On this device", the mark in the dimmer colour */
    private fun words(ctx: Context, row: Row): CharSequence {
        if (!row.detected) return row.label
        val mark = ctx.getString(R.string.rd_on_this_device)
        return SpannableStringBuilder(row.label).append("  ·  ").apply {
            val start = length
            append(mark)
            setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.rd_fg_a2)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Opens the list under [anchor], at least [minWidth] px wide (else the anchor's width, and wider for the device's
     * mark), on the row [focus]; [onPick] gets the row picked.
     */
    fun show(anchor: View, rows: List<Row>, focus: Int, minWidth: Int = 0, onPick: (Int) -> Unit): ListPopupWindow {
        val ctx = anchor.context
        val popup = ListPopupWindow(ctx)
        popup.anchorView = anchor
        val texts = rows.map { words(ctx, it) }
        popup.setAdapter(object : ArrayAdapter<CharSequence>(ctx, R.layout.rd_pref_choice, android.R.id.text1, texts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.isActivated = rows.getOrNull(position)?.checked == true
                return v
            }
        })
        popup.setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.rd_menu_background))
        // (the D-pad: the focused choice as the TV's lists show it, the accent at 25 %; the platform's full accent
        // under the choice's own colour left its words unreadable)
        if (!anchor.isInTouchMode) popup.setListSelector(ContextCompat.getDrawable(ctx, R.drawable.rd_tv_focus_selector))
        val dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, ctx.resources.displayMetrics)
        val marked = if (rows.any { it.detected }) (260 * dp).toInt() else 0
        popup.width = maxOf(anchor.width, (180 * dp).toInt(), minWidth, marked)
        // (a long list stays on the screen: at most what fits between the control and the window's edge)
        popup.isModal = true
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPick(position)
        }
        popup.show()
        if (focus in rows.indices) popup.listView?.let { list ->
            list.setSelection(focus)
            // (the remote: the row it opens on has the focus, so OK picks it at once)
            if (!anchor.isInTouchMode) list.post { list.setSelection(focus); list.requestFocus() }
        }
        return popup
    }
}

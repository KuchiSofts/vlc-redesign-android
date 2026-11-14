/*
 * VLC Redesign: Settings › Controls › Keyboard Shortcuts, as the desktop's list (Menu › Keyboard Shortcuts): what each
 * key of a keyboard, a game controller or a TV remote does in the video player (VideoPlayerActivity.onKeyDown,
 * PlayerKeyListenerDelegate). The actions are VLC's own words for its hotkeys (translated with VLC); the keys stay as
 * they are printed on the keys, left to right in every language (a remote's media keys do what they say, not
 * listed). Android's keys are fixed: no editor.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.RdTvFocusRing

object RdShortcuts {

    /** the keys, as printed on them, and what they do (the list's order: playing, moving, sound, picture, subtitles) */
    val rows: List<Pair<String, Int>> = listOf(
        "Space, OK" to R.string.rd_key_play_pause,
        "S" to R.string.rd_key_stop,
        "N, CH+" to R.string.rd_key_next,
        "P, CH−" to R.string.rd_key_previous,
        "Shift+←" to R.string.rd_key_very_short_back,
        "Shift+→" to R.string.rd_key_very_short_forward,
        "←, Alt+←" to R.string.rd_key_short_back,
        "→, Alt+→" to R.string.rd_key_short_forward,
        "Ctrl+←" to R.string.rd_key_medium_back,
        "Ctrl+→" to R.string.rd_key_medium_forward,
        "Ctrl+Alt+←" to R.string.rd_key_long_back,
        "Ctrl+Alt+→" to R.string.rd_key_long_forward,
        "+, Shift+=" to R.string.rd_key_faster,
        "−" to R.string.rd_key_slower,
        "=" to R.string.rd_key_normal_rate,
        "Ctrl+↑, Vol+" to R.string.rd_key_volume_up,
        "Ctrl+↓, Vol−" to R.string.rd_key_volume_down,
        "M" to R.string.rd_key_mute,
        "J" to R.string.rd_key_audio_delay_down,
        "K" to R.string.rd_key_audio_delay_up,
        "Ctrl+E" to R.string.equalizer,
        "A, Z, C" to R.string.rd_key_aspect,
        "Shift+S" to R.string.rd_key_snapshot,
        "Shift+R" to R.string.rd_key_record,
        "V, CC" to R.string.tracks,
        "G" to R.string.rd_key_sub_delay_down,
        "H" to R.string.rd_key_sub_delay_up,
        "Shift+H" to R.string.rd_key_sync_audio,
        "Shift+J" to R.string.rd_key_sync_subtitle,
        "Shift+K" to R.string.rd_key_sync_apply,
        "Ctrl+0" to R.string.rd_key_sub_scale_reset,
        "B" to R.string.add_bookmark,
        "T, Ctrl+H" to R.string.rd_key_show_controls,
        "O, Menu" to R.string.rd_key_options,
    )

    fun show(context: Context) {
        val d = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt() }
        val font = ResourcesCompat.getFont(context, R.font.inter)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24f), d(8f), d(24f), d(8f))
        }
        for ((keys, action) in rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, d(6f), 0, d(6f))
                // (a TV remote moves through the rows)
                isFocusable = true
                contentDescription = "${context.getString(action)}: $keys"
            }
            row.addView(TextView(context).apply {
                text = context.getString(action)
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.rd_fg_1))
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(16f) })
            row.addView(TextView(context).apply {
                text = keys
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.rd_fg_a1))
                // (the keys as on the keyboard: left to right in every language)
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            list.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rd_keyboard_shortcuts)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.ok, null)
            .show()
        RdTvFocusRing.attach(dialog.window)
    }
}

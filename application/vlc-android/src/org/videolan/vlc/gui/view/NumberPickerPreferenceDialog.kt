/*
 * ************************************************************************
 *  NumberPickerPreferenceDialog.kt
 * *************************************************************************
 * Copyright © 2021 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui.view

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.NumberPicker
import androidx.preference.PreferenceDialogFragmentCompat
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.RdFit

class NumberPickerPreferenceDialog : PreferenceDialogFragmentCompat() {
    private lateinit var numberPicker: NumberPicker

    override fun onCreateDialogView(context: Context): View {
        val container = layoutInflater.inflate(R.layout.pref_number_picker, null)
        numberPicker = container.findViewById(R.id.number_picker)
        numberPicker.minValue = NumberPickerPreference.MIN_VALUE
        numberPicker.maxValue = NumberPickerPreference.MAX_VALUE
        // (the TV's narrower card: the wheel under the title, not beside its last word)
        if (Settings.showTvUi) container.setPadding(container.paddingLeft, 12.dp, container.paddingRight, container.paddingBottom)

        return container
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        numberPicker.value = (preference as NumberPickerPreference).getPersistedInt()
    }

    /**
     * The TV: a card as wide as its wheel needs (it was stretched over most of the screen), and OK on the wheel takes
     * the number (Up and Down turn it; OK did nothing there, the buttons a Left away).
     */
    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        if (!Settings.showTvUi) return
        RdFit.card(dialog, 420.dp)
        var down = false
        dialog.setOnKeyListener { _, code, event ->
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!ok || !numberPicker.hasFocus()) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) down = true
            else if (event.action == KeyEvent.ACTION_UP && down) (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
            true
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            numberPicker.clearFocus()
            val newValue: Int = numberPicker.value
            if (preference.callChangeListener(newValue)) {
                (preference as NumberPickerPreference).doPersistInt(newValue)
                preference.summary
            }
        }
    }

    companion object {
        fun newInstance(key: String): NumberPickerPreferenceDialog {
            val fragment = NumberPickerPreferenceDialog()
            val bundle = Bundle(1)
            bundle.putString(ARG_KEY, key)
            fragment.arguments = bundle

            return fragment
        }
    }
}
/*
 * VLC Redesign: the What's new card of this release, as the desktop's (shown once after an update, opened again
 * from About).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import org.videolan.tools.KEY_SHOW_WHATS_NEW
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R

class RdWhatsNewDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = false

    private lateinit var ok: View

    /** OK has the focus: the remote's OK or Back closes the card, Up goes to the changes (they scroll) */
    override fun initialFocusedView(): View = ok

    /** A card of text reads best at about 70 characters a line: 640 dp, less on a narrow window (RdFit) */
    override fun windowWidth(): Int {
        val base = super.windowWidth()
        return if (base == ViewGroup.LayoutParams.MATCH_PARENT) base else org.videolan.vlc.gui.helpers.RdFit.width(activity, 640.dp, 24.dp)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.dialog_rd_whats_new, container, false)
        ok = root.findViewById(R.id.rd_whats_new_ok)
        ok.setOnClickListener { dismiss() }
        val title = root.findViewById<TextView>(R.id.rd_whats_new_title)
        title.text = getString(R.string.rd_about_whats_new, BuildConfig.RD_VERSION_NAME)
        val list = root.findViewById<LinearLayout>(R.id.rd_whats_new_list)
        val ctx = requireContext()
        resources.getStringArray(R.array.rd_whats_new_items).forEach { item ->
            list.addView(TextView(ctx).apply {
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                // the lines after the first start under the words, not back under the bullet (the reading side's
                // margin: on the right in Hebrew or Arabic)
                val bullet = "•  "
                text = android.text.SpannableString(bullet + item).apply {
                    setSpan(android.text.style.LeadingMarginSpan.Standard(0, paint.measureText(bullet).toInt()), 0, length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setLineSpacing(0f, 1.3f)
                // an item not translated yet reads in its own direction
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                setPadding(10.dp, 6.dp, 10.dp, 6.dp)
                // the remote reads down the changes: each one takes the focus and is scrolled into sight
                if (Settings.showTvUi) { isFocusable = true; setBackgroundResource(R.drawable.rd_tv_focus_selector) }
            })
        }
        root.findViewById<CheckBox>(R.id.rd_whats_new_never).apply {
            isChecked = !Settings.getInstance(ctx).getBoolean(KEY_SHOW_WHATS_NEW, true)
            setOnCheckedChangeListener { _, isChecked -> Settings.getInstance(ctx).putSingle(KEY_SHOW_WHATS_NEW, !isChecked) }
        }
        return root
    }
}

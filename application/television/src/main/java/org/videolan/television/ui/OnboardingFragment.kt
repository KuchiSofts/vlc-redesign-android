/*
 * ************************************************************************
 *  OnboardingFragment.kt
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

package org.videolan.television.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import org.videolan.resources.MOBILE_MAIN_ACTIVITY
import org.videolan.resources.util.canReadStorage
import org.videolan.television.R
import org.videolan.tools.KEY_TV_ONBOARDING_DONE
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.util.Permissions

/**
 * VLC Redesign: the TV layout's onboarding as the design's dialog (OnboardingDialog.qml, the TV app's onboarding): three steps
 * (welcome, the media access, all set) with Back and Next, every one reached with the D-pad, Back steps back.
 */
class OnboardingFragment : Fragment() {
    private var step = 0
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var back: TextView
    private lateinit var next: TextView

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(step - 1)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.rd_tv_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.rd_ob_title)
        text = view.findViewById(R.id.rd_ob_text)
        back = view.findViewById(R.id.rd_ob_back)
        next = view.findViewById(R.id.rd_ob_next)
        back.setOnClickListener { show(step - 1) }
        next.setOnClickListener { if (step < PAGES - 1) show(step + 1) else finish() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        show(savedInstanceState?.getInt(STEP) ?: 0)
        next.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // the media access may have been answered in the system's dialog: the page that asks it (still "VLC needs
        // your permission" after Allow) and the last one say so (show(1) from page 1 asks nothing again)
        if (::text.isInitialized && step > 0) show(step)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STEP, step)
    }

    private fun show(page: Int) {
        val previous = step
        step = page.coerceIn(0, PAGES - 1)
        val activity = requireActivity()
        when (step) {
            0 -> {
                title.text = getString(R.string.rd_ob_welcome)
                text.text = getString(R.string.rd_ob_welcome_text)
            }
            2 -> {
                // the desktop's privacy step: the online subtitles (on, as there) and the update check, each a row
                // the remote turns with OK
                title.text = getString(org.videolan.vlc.R.string.rd_ob_online_title)
                text.text = getString(org.videolan.vlc.R.string.rd_os_auto_help)
            }
            1 -> {
                title.text = getString(R.string.onboarding_scan_title)
                text.text = getString(if (canReadStorage(activity)) R.string.onboarding_permission_given else R.string.permission_media)
                if (previous == 0 && !Permissions.canReadStorage(activity)) Permissions.checkReadStoragePermission(activity)
            }
            else -> {
                title.text = getString(R.string.onboarding_all_set)
                text.text = if (canReadStorage(activity)) getString(R.string.onboarding_permission_given)
                else "${getString(R.string.permission_expanation_no_allow)}\n${getString(R.string.permission_expanation_allow)}"
            }
        }
        privacyRows(step == 2)
        val focusWasBack = back.hasFocus()
        back.visibility = if (step > 0) View.VISIBLE else View.GONE
        next.text = getString(if (step == PAGES - 1) R.string.start_vlc else R.string.rd_next)
        if (step == 0 || !focusWasBack) next.requestFocus()
        backCallback.isEnabled = step > 0
    }

    private var rows: android.widget.LinearLayout? = null

    /** the privacy page's rows (the online subtitles, on by default as on the desktop; the update check) */
    private fun privacyRows(shown: Boolean) {
        val parent = text.parent as? android.widget.RelativeLayout ?: return
        if (!shown) { rows?.visibility = View.GONE; return }
        rows?.let { it.visibility = View.VISIBLE; return }
        val ctx = requireContext()
        val settings = Settings.getInstance(ctx)
        if (!settings.contains(org.videolan.vlc.util.OnlineSubtitles.KEY_AUTO)) settings.putSingle(org.videolan.vlc.util.OnlineSubtitles.KEY_AUTO, true)
        val box = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; id = View.generateViewId() }
        fun row(label: String, key: String, def: Boolean) {
            val r = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(0, 4.dp(ctx), 0, 4.dp(ctx))
            }
            r.addView(TextView(ctx).apply {
                this.text = label
                textSize = 8f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, org.videolan.vlc.R.color.rd_dark_fg_1))
                typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, org.videolan.vlc.R.font.inter)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val toggle = org.videolan.vlc.gui.preferences.rd.RdToggleView(ctx).apply { isChecked = settings.getBoolean(key, def) }
            r.addView(toggle)
            r.contentDescription = label
            r.setOnClickListener { toggle.toggle(); settings.putSingle(key, toggle.isChecked) }
            box.addView(r)
        }
        row(getString(org.videolan.vlc.R.string.rd_os_auto), org.videolan.vlc.util.OnlineSubtitles.KEY_AUTO, true)
        row(getString(org.videolan.vlc.R.string.rd_check_updates), org.videolan.vlc.util.RdUpdateCheck.KEY_CHECK, true)
        parent.addView(box, android.widget.RelativeLayout.LayoutParams(text.layoutParams.width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(android.widget.RelativeLayout.BELOW, text.id)
            addRule(android.widget.RelativeLayout.ALIGN_START, text.id)
            topMargin = 10.dp(ctx)
        })
        rows = box
    }

    private fun Int.dp(ctx: android.content.Context) = (this * ctx.resources.displayMetrics.density).toInt()

    private fun finish() {
        val activity = requireActivity()
        Settings.getInstance(activity).putSingle(KEY_TV_ONBOARDING_DONE, true)
        activity.finish()
        // VLC Redesign: the TV layout is the design's own pages (the tablet layout)
        startActivity(Intent(Intent.ACTION_VIEW).setClassName(activity, MOBILE_MAIN_ACTIVITY))
    }

    private companion object {
        // welcome, the media, the privacy (the online subtitles, the update check), all set
        const val PAGES = 4
        const val STEP = "rd_ob_step"
    }
}

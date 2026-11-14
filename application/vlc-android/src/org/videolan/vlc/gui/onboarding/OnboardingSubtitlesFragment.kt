/*
 * VLC Redesign: the welcome's "Subtitle languages" step, as the desktop's (SubtitleLanguagesStep.qml): the default
 * language of the subtitles (Settings › Subtitles › Preferred language) and the second one, used when a video has
 * none in the first, not even online (OnlineSubtitles.KEY_SECOND). The lists are those of Settings
 * (RdLocaleChoices.rows: the device's languages first). Finding subtitles online stays on the next step (privacy).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.onboarding

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.tools.KEY_SUBTITLE_PREFERRED_LANGUAGE
import org.videolan.tools.LocaleUtils
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.RdTvFocusRing
import org.videolan.vlc.gui.preferences.rd.rdDp
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.RdDeviceLocale
import org.videolan.vlc.util.RdLocaleChoices
import org.videolan.tools.LocaleUtils.getLocales

class OnboardingSubtitlesFragment : OnboardingFragment() {
    private lateinit var titleView: TextView
    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.onboarding_subtitles, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.subtitles_title)
        val rows = view.findViewById<LinearLayout>(R.id.subtitles_rows)
        val ctx = requireContext()
        val settings = Settings.getInstance(ctx)
        val pair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.no_track_preference), requireActivity().getLocales())
        val choices = RdLocaleChoices.rows(pair.localeEntryValues.toList(), pair.localeEntries.toList(), RdDeviceLocale.languageTags())
        if (choices.isEmpty()) return
        // the default language: the one chosen, else the interface's (as OnlineSubtitles.preferredLanguage reads it)
        picker(rows, getString(R.string.rd_ob_default_language), choices, settings.getString(KEY_SUBTITLE_PREFERRED_LANGUAGE, null),
                preferDetected = true) { settings.putSingle(KEY_SUBTITLE_PREFERRED_LANGUAGE, it) }
        // the second: never chosen, English unless English is the first (the desktop's default)
        picker(rows, getString(R.string.rd_ob_second_language), choices, OnlineSubtitles.secondLanguage(ctx)) {
            settings.putSingle(OnlineSubtitles.KEY_SECOND, it)
        }
    }

    /** a row with the language chosen under its name; a tap opens the list (the TV's remote too) */
    private fun picker(parent: LinearLayout, label: String, choices: List<RdLocaleChoices.Choice>, current: String?,
                       preferDetected: Boolean = false, changed: (String) -> Unit) {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        // (the value saved may be another code of the same language than the list's: "he" and "iw")
        val saved = current?.takeIf { it.isNotEmpty() }?.let { RdLocaleChoices.match(choices.map { c -> c.value }, it) } ?: current
        var chosen = RdLocaleChoices.openAt(choices, saved, preferDetected)
        // (nothing chosen yet: the row says what applies, the list opens at it, nothing is saved until picked)
        val valueView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_dark_fg_a3))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            text = choices[chosen].label
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d(56f)
            setPadding(d(4f), d(6f), d(4f), d(6f))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_settings_sidebar_item)
            contentDescription = "$label, ${choices[chosen].label}"
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_dark_fg_1))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        row.addView(valueView)
        row.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(label)
                .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), chosen) { dlg, which ->
                    chosen = which
                    valueView.text = choices[which].label
                    row.contentDescription = "$label, ${choices[which].label}"
                    changed(choices[which].value)
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            RdTvFocusRing.attach(dialog.window)
        }
        parent.addView(row)
    }

    companion object {
        fun newInstance() = OnboardingSubtitlesFragment()
    }
}

/*
 * VLC Redesign: the lyrics of the song playing, as the desktop's music view shows them (LyricsFlickable.qml): every
 * line, the one sung in the accent and kept in the middle while the song plays (synchronized lyrics); a tap on a line
 * goes there. Opened from the music player's menu (Lyrics) when the song has lyrics (RdLyrics: its ID3 tag or an
 * .lrc file beside it). The lyrics of the song playing are kept in [current].
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.audio

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.dialogs.VLCBottomSheetDialogFragment
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.util.RdLyrics

class RdLyricsSheet : VLCBottomSheetDialogFragment() {
    private lateinit var scroll: NestedScrollView
    private lateinit var title: TextView
    private val rows = ArrayList<TextView>()
    private var shown = -2
    // the last key on a line (the remote moving through them): its focus is left where the user put it a while
    private var lastKey = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            follow()
            handler.postDelayed(this, 250L)
        }
    }

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED
    override fun needToManageOrientation() = false
    override fun initialFocusedView(): View = title
    override fun allowRemote() = true

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(8f))
        }
        title = TextView(ctx).apply {
            text = getString(R.string.rd_lyrics)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_1))
            isFocusable = true
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        root.addView(title)
        val lines = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(120f))
        }
        val lyrics = current?.second
        val font = ResourcesCompat.getFont(ctx, R.font.inter)
        lyrics?.lines?.forEach { line ->
            val row = TextView(ctx).apply {
                text = line.text.ifEmpty { " " }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = font
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a2))
                setPadding(0, dp(6f), 0, dp(6f))
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                // (a tap on a line of synchronized lyrics goes there, as on the desktop)
                if (line.timeMs != null) {
                    isFocusable = true
                    setBackgroundResource(R.drawable.rd_option_row_bg)
                    setOnClickListener { PlaybackService.instance?.setTime(line.timeMs) }
                    setOnKeyListener { _, _, _ -> lastKey = android.os.SystemClock.uptimeMillis(); false }
                }
            }
            rows.add(row)
            lines.addView(row)
        }
        scroll = NestedScrollView(ctx).apply {
            addView(lines)
            // (a line scrolled under the title fades out instead of being cut)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32f))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt()))
        return root
    }

    override fun onStart() {
        super.onStart()
        if (current?.second?.synced == true) handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    /** the line sung in the accent, kept in the middle of the window */
    private fun follow() {
        val lyrics = current?.second ?: return
        val service = PlaybackService.instance ?: return
        val i = lyrics.indexAt(service.getTime())
        if (i == shown) return
        val ctx = context ?: return
        rows.getOrNull(shown)?.setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a2))
        shown = i
        val row = rows.getOrNull(i) ?: return
        row.setTextColor(ctx.rdAccent())
        // (the remote's focus on a line goes with the song, unless a key moved it a moment ago: it was left on the
        // first line, half under the title, as the window scrolled to the line sung)
        if (row.isFocusable && rows.any { it.isFocused } && android.os.SystemClock.uptimeMillis() - lastKey > 2500L) row.requestFocus()
        scroll.smoothScrollTo(0, (row.top - scroll.height / 2 + row.height / 2).coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "rd_lyrics"

        /** the song playing (its location) and its lyrics, null when it has none */
        @Volatile
        var current: Pair<String, RdLyrics.Lyrics?>? = null
            private set

        /** reads the lyrics of [location] once (a local file: its tag or an .lrc beside it) */
        suspend fun load(location: String?): RdLyrics.Lyrics? {
            if (location == null) return null
            current?.let { if (it.first == location) return it.second }
            val uri = Uri.parse(location)
            val lyrics = if (uri.scheme == "file" && uri.path != null) withContext(Dispatchers.IO) { RdLyrics.load(java.io.File(uri.path!!)) } else null
            current = location to lyrics?.takeIf { it.lines.isNotEmpty() }
            return current?.second
        }

        fun has(location: String?) = location != null && current?.let { it.first == location && it.second != null } == true

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null && !manager.isStateSaved) RdLyricsSheet().show(manager, TAG)
        }
    }
}

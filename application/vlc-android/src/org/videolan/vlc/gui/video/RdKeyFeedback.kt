/*
 * VLC Redesign (#72): what a key, the remote or a gesture shows in the player instead of the controls, as VLC 3 does
 * with its hotkeys (Settings > Interface > Playback Controls > "When you use a key or the remote"), the same as the
 * desktop's and the Samsung TV app's:
 *  - Show the controls: the player as it was (the default: nobody's player changes unless they choose);
 *  - Show only the progress: a slim line on the bottom edge (the played part in the accent colour) and a small pill
 *    at the top centre (never over the subtitles): what was done and the time, "+30 s  00:02:50 / 00:25:40",
 *    "Paused", "1.5x";
 *  - Show only a message: the pill alone.
 * Gone 1.5 s after the last action; seeks in a row add up ("+1:10" past a minute), the other way starts again. The
 * volume and the brightness keep their own indicator (never two displays). A tap, OK, Up/Down and the menu key still
 * show the controls. The pill's words follow the language; its times and the line run as the seek bar does (left
 * to right). It is announced to TalkBack as the player's messages are.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.rdAccent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The setting and the texts, pure (unit tests). */
object RdKeyFeedbackRules {
    const val KEY = "rd_key_feedback"
    const val CONTROLS = "controls"
    const val PROGRESS = "progress"
    const val MESSAGE = "message"
    const val SHOWN_MS = 1500L

    /** the setting's value, anything else the default (the controls, as before) */
    fun mode(value: String?): String = when (value) {
        PROGRESS, MESSAGE -> value
        else -> CONTROLS
    }

    /** "+10 s", "−1:30", "+1:02:03" (a true minus sign); [seconds] puts a number of seconds in the language's words */
    fun seekText(ms: Long, seconds: (String) -> String): String = seekText(ms, seconds, ltr = { it })

    /** [ltr] keeps a signed number left to right in a right-to-left language ("+30", not "30+") */
    fun seekText(ms: Long, seconds: (String) -> String, ltr: (String) -> String): String {
        val sign = if (ms < 0) "−" else "+"
        val s = abs(ms) / 1000
        return when {
            s < 60 -> seconds(ltr(sign + s))
            s < 3600 -> ltr(sign + "%d:%02d".format(Locale.ROOT, s / 60, s % 60))
            else -> ltr(sign + "%d:%02d:%02d".format(Locale.ROOT, s / 3600, (s / 60) % 60, s % 60))
        }
    }

    /** a position as the seek bar writes it: "00:02:50" */
    fun timeText(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        return "%02d:%02d:%02d".format(Locale.ROOT, s / 3600, (s / 60) % 60, s % 60)
    }

    /** the pill of a seek: "+30 s  00:02:50 / 00:25:40" (no length known: the time alone) */
    fun seekPill(total: Long, position: Long, length: Long, seconds: (String) -> String): String =
        seekPill(total, position, length, seconds, ltr = { it })

    /** the words in the language's direction; the times, as the seek bar, left to right in any language ([ltr]) */
    fun seekPill(total: Long, position: Long, length: Long, seconds: (String) -> String, ltr: (String) -> String): String =
        seekText(total, seconds, ltr) + "  " + ltr(timeText(position) + if (length > 0) " / " + timeText(length) else "")

    /** "1.5×", "2×", "0.75×" */
    fun rateText(rate: Float): String {
        val r = (rate * 100).roundToInt() / 100f
        val text = if (r == r.toInt().toFloat()) r.toInt().toString() else "%.2f".format(Locale.ROOT, r).trimEnd('0').trimEnd('.')
        return text + "×"
    }

    /** The seeks pressed in a row, added up (the same way, within [windowMs] of the last one). */
    class SeekSum(private val windowMs: Long = SHOWN_MS) {
        private var total = 0L
        private var last = -1L

        fun add(delta: Long, now: Long): Long {
            val inRow = last >= 0 && now - last <= windowMs && total != 0L && (total > 0) == (delta > 0)
            total = if (inRow) total + delta else delta
            last = now
            return total
        }

        fun reset() {
            total = 0
            last = -1
        }
    }
}

/** The feedback of a player: its views made the first time it is shown, over the video. */
class RdKeyFeedback(private val player: VideoPlayerActivity) {

    private var root: FrameLayout? = null
    private var pill: TextView? = null
    private var line: ProgressLine? = null
    private val sum = RdKeyFeedbackRules.SeekSum()
    /** the position the line shows (a seek target until the player gets there), -1: the player's */
    private var shownPosition = -1L
    private var shownUntil = 0L

    private val mode get() = RdKeyFeedbackRules.mode(Settings.getInstance(player).getString(RdKeyFeedbackRules.KEY, null))

    /** a key, the remote or a gesture shows this, not the controls */
    val active get() = mode != RdKeyFeedbackRules.CONTROLS && !player.isInPictureInPictureMode

    private val hide = Runnable { hideNow() }
    private val tick = object : Runnable {
        override fun run() {
            val l = line ?: return
            if (l.visibility != View.VISIBLE) return
            updateLine()
            l.postDelayed(this, 250)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, player.resources.displayMetrics).roundToInt()

    private fun ensureViews() {
        if (root != null) return
        val parent = player.findViewById<ViewGroup>(R.id.player_root) ?: return
        val r = FrameLayout(player).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val l = ProgressLine(player, player.rdAccent()).apply {
            id = R.id.rd_fs_progress
            visibility = View.GONE
        }
        r.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28f), Gravity.BOTTOM))
        val p = TextView(player).apply {
            id = R.id.rd_fs_pill
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            try { typeface = ResourcesCompat.getFont(player, R.font.inter) } catch (e: Exception) { }
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xB8000000.toInt()) }
            maxLines = 1
            visibility = View.GONE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        r.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        parent.addView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = r
        pill = p
        line = l
    }

    private fun show(text: String, position: Long = -1L) {
        ensureViews()
        val p = pill ?: return
        val l = line ?: return
        val progress = mode == RdKeyFeedbackRules.PROGRESS
        // the pill above the line, or alone at the top (out of the subtitles' way)
        (p.layoutParams as FrameLayout.LayoutParams).apply {
            // (at the top centre: never over the subtitles; inside a TV's safe area)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(if (player.isTv) 40f else 28f)
            p.layoutParams = this
        }
        p.text = text
        p.visibility = View.VISIBLE
        p.alpha = 1f
        shownPosition = position
        shownUntil = SystemClock.elapsedRealtime() + RdKeyFeedbackRules.SHOWN_MS
        if (progress) {
            l.visibility = View.VISIBLE
            l.removeCallbacks(tick)
            updateLine()
            l.postDelayed(tick, 250)
        } else l.visibility = View.GONE
        player.rootView?.announceForAccessibility(text)
        p.removeCallbacks(hide)
        p.postDelayed(hide, RdKeyFeedbackRules.SHOWN_MS)
    }

    private fun updateLine() {
        val l = line ?: return
        val service = player.service ?: return
        val length = service.length
        val time = if (shownPosition >= 0 && SystemClock.elapsedRealtime() < shownUntil - RdKeyFeedbackRules.SHOWN_MS + 400) shownPosition else service.getTime()
        l.fraction = if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
        l.invalidate()
    }

    fun hideNow() {
        pill?.visibility = View.GONE
        line?.let { it.visibility = View.GONE; it.removeCallbacks(tick) }
        sum.reset()
    }

    /** the controls shown (a tap, OK): no feedback beside them */
    fun onControlsShown() = hideNow()

    /** a number or the times kept left to right inside the language's direction (the app's language, not the phone's) */
    private val bidi by lazy(LazyThreadSafetyMode.NONE) {
        BidiFormatter.getInstance(player.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    }

    private fun ltr(text: String): String = bidi.unicodeWrap(text, TextDirectionHeuristics.LTR)

    /** a seek of [delta] ms to [target]: "+30 s  00:02:50 / 00:25:40", those in a row added up */
    fun seek(delta: Long, target: Long) {
        val total = sum.add(delta, SystemClock.elapsedRealtime())
        show(pill(total, target), target)
    }

    /** a swipe that seeks: its jump so far (not added up: the gesture says the whole of it) */
    fun swipeSeek(jump: Long, target: Long) {
        sum.reset()
        show(pill(jump, target), target)
    }

    private fun pill(total: Long, target: Long) = RdKeyFeedbackRules.seekPill(total, target, player.service?.length ?: 0L,
        seconds = { player.getString(R.string.rd_fs_seconds, it) }, ltr = { ltr(it) })

    fun paused(paused: Boolean) = show(player.getString(if (paused) R.string.rd_fs_paused else R.string.rd_fs_playing))

    fun rate(rate: Float) = show(ltr(RdKeyFeedbackRules.rateText(rate)))

    fun message(text: String) = show(text)

    /** The line: the played part in the accent, the rest a light track, over a soft shade from the bottom edge. */
    class ProgressLine(context: Context, private val accent: Int) : View(context) {
        var fraction = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density
        private var shade: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            shade = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val lineH = 4 * density
            paint.shader = shade
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.color = 0x4DFFFFFF
            canvas.drawRect(0f, h - lineH, w, h, paint)
            paint.color = accent
            // (left to right, as the player's seek bar, whatever the language)
            canvas.drawRect(0f, h - lineH, w * fraction, h, paint)
        }
    }
}

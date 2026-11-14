/*
 * VLC Redesign: the desktop's "Show subtitle lines on the seek bar" (SliderBar.qml, subtitle_cues.cpp): where the
 * lines of the subtitle shown are, still to come, as short grey marks on the bar's track after the played part (the
 * subtitle delay moves them); lines closer than 2 dp make one mark, a mark is at least 1 dp wide. Drawn in the seek
 * bar's overlay, so the bar's own drawables stay as they are.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.SeekBar
import org.videolan.vlc.util.SubtitleCues

class RdSeekCueMarks(val bar: SeekBar, private val delayMs: () -> Long) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B6B6B.toInt() }
    private val density = bar.resources.displayMetrics.density
    private var cues: List<SubtitleCues.Cue> = emptyList()
    private var lengthMs = 0L
    private var attached = false
    private val layout = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> setBounds(0, 0, r - l, b - t) }

    /** The lines to mark and the video's length; none: nothing drawn */
    fun set(lines: List<SubtitleCues.Cue>, length: Long) {
        cues = lines
        lengthMs = length
        if (lines.isNotEmpty() && length > 0 && !attached) {
            setBounds(0, 0, bar.width, bar.height)
            bar.overlay.add(this)
            bar.addOnLayoutChangeListener(layout)
            attached = true
        } else if ((lines.isEmpty() || length <= 0) && attached) detach()
        invalidateSelf()
        bar.invalidate()
    }

    fun detach() {
        if (!attached) return
        bar.overlay.remove(this)
        bar.removeOnLayoutChangeListener(layout)
        attached = false
    }

    /** The marks: (x, width) in the bar, the merged ones together; pure, for the tests */
    companion object {
        fun marks(cues: List<SubtitleCues.Cue>, lengthMs: Long, delayMs: Long, trackStart: Float, trackWidth: Float,
                  fromX: Float, minWidth: Float, merge: Float): List<Pair<Float, Float>> {
            if (lengthMs <= 0 || trackWidth <= 0f) return emptyList()
            val out = mutableListOf<Pair<Float, Float>>()
            var curStart = -1f
            var curEnd = -1f
            for (cue in cues) {
                val a = trackStart + (cue.start + delayMs).toFloat() / lengthMs * trackWidth
                var b = trackStart + (cue.end + delayMs).toFloat() / lengthMs * trackWidth
                if (b < fromX || a > trackStart + trackWidth) continue
                val start = maxOf(a, fromX)
                b = minOf(maxOf(b, start + minWidth), trackStart + trackWidth)
                if (curStart >= 0f && start - curEnd <= merge) curEnd = maxOf(curEnd, b)
                else {
                    if (curStart >= 0f) out += curStart to (curEnd - curStart)
                    curStart = start
                    curEnd = b
                }
            }
            if (curStart >= 0f) out += curStart to (curEnd - curStart)
            return out
        }
    }

    override fun draw(canvas: Canvas) {
        if (cues.isEmpty() || lengthMs <= 0) return
        val trackStart = bar.paddingLeft.toFloat()
        val trackWidth = (bar.width - bar.paddingLeft - bar.paddingRight).toFloat()
        if (trackWidth <= 0f) return
        val max = bar.max.coerceAtLeast(1)
        // after the played part and the thumb (the desktop's clip at the fill's end)
        val thumb = (bar.thumb?.intrinsicWidth ?: 0) / 2f
        val played = trackStart + bar.progress.toFloat() / max * trackWidth + thumb
        val h = 4f * density
        val top = (bar.height - h) / 2f
        for ((x, w) in marks(cues, lengthMs, delayMs(), trackStart, trackWidth, played, density, 2f * density))
            canvas.drawRect(x, top, x + w, top + h, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

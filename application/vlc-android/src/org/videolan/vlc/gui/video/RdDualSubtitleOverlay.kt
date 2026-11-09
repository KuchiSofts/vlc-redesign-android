/*
 * VLC Redesign: the desktop's dual subtitles on Android (Settings › Subtitles › "Show both languages at once"). libvlc
 * 3 shows one subtitle track: the second language is drawn here, above the first one, from its file (a subtitle of
 * that language downloaded for the video, a file beside it or in its Subs folder; else, when the user lets the
 * services be asked by themselves, the best one found online, downloaded and added to the video's tracks as the
 * desktop's loads it alongside). Its line of the moment follows the playback time and the subtitle delay, at the
 * size of the subtitles; hidden while the first language shown is that same language.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.RdDualSubtitles
import org.videolan.vlc.util.SubtitleCues
import java.io.File

class RdDualSubtitleOverlay(private val activity: VideoPlayerActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var view: OutlinedText? = null
    private var cues: List<SubtitleCues.Cue> = emptyList()
    private var uri: Uri? = null
    private var language = ""
    private var job: Job? = null
    private var running = false
    private var sameShown = false
    private var lastCheck = 0L
    private val location = IntArray(2)
    private val frameLocation = IntArray(2)

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 100L)
        }
    }

    private fun second(): String {
        val settings = Settings.getInstance(activity)
        if (!settings.getBoolean(RdDualSubtitles.KEY_SHOW_BOTH, false)) return ""
        val second = OnlineSubtitles.normalize(settings.getString(OnlineSubtitles.KEY_SECOND, null))
        return if (second == OnlineSubtitles.preferredLanguage(activity)) "" else second
    }

    /** the video playing (again): its second language looked for, its lines shown */
    fun start() {
        val now = activity.service?.currentMediaWrapper?.uri
        val wanted = second()
        if (wanted.isEmpty() || now == null) {
            stop()
            uri = null
            return
        }
        if (now == uri && wanted == language) {
            if (!running) resume()
            return
        }
        stop()
        uri = now
        language = wanted
        cues = emptyList()
        find(now, wanted)
        resume()
    }

    private fun resume() {
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        job?.cancel()
        job = null
        view?.visibility = View.GONE
    }

    /** the file of the second language: there already, else found online (by itself, as the desktop's) */
    private fun find(forUri: Uri, wanted: String) {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            val ctx = activity.applicationContext
            var searched = false
            // (the downloaded subtitles are added a moment after the video starts, the search takes a while)
            for (attempt in 0 until 60) {
                if (activity.service?.currentMediaWrapper?.uri != forUri) return@launch
                val path = RdDualSubtitles.fileOf(wanted, activity.rdDownloadedSubtitleFiles(), withContext(Dispatchers.IO) { activity.rdLocalSubtitleFiles() })
                if (path != null) {
                    load(File(path))
                    return@launch
                }
                val finder = activity.rdSubtitleFinder
                val outcome = finder.outcome
                val best = outcome?.results?.firstOrNull { it.result.language == wanted && it.result.id !in activity.rdLoadedSubtitleIds() }
                if (outcome != null && best != null && finder.loadingId == null) {
                    val (file, _) = OnlineSubtitles.download(ctx, best.result, outcome.media)
                    if (file != null && activity.service?.currentMediaWrapper?.uri == forUri) {
                        Log.i(TAG, "dual subtitles: ${best.result.language} of ${best.result.provider} loaded alongside")
                        activity.rdAddOnlineSubtitle(file, best.result, select = false)
                        load(file)
                    }
                    return@launch
                }
                // (no search of its own: the automatic one runs when the video has none of the first language)
                if (!searched && attempt >= 6 && outcome == null && finder.current() == RdSubtitleFinder.State.IDLE && finder.searchesByItself()) {
                    searched = true
                    finder.search()
                }
                if (outcome != null && !finder.busy() && finder.loadingId == null && best == null && attempt > 8) return@launch
                delay(1500L)
            }
        }
    }

    private suspend fun load(file: File) {
        val ctx = activity.applicationContext
        cues = withContext(Dispatchers.IO) {
            // (Hebrew or Arabic marks written for left-to-right players: the fixed copy's lines)
            SubtitleCues.readFile(OnlineSubtitles.rtlFixedCopy(ctx, file))
        }
        Log.i(TAG, "dual subtitles: ${cues.size} lines of $language from ${file.name}")
    }

    private fun ensureView(): OutlinedText? {
        view?.let { return it }
        val frame = activity.findViewById<FrameLayout>(R.id.player_surface_frame) ?: return null
        val text = OutlinedText(activity)
        frame.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        view = text
        return text
    }

    private fun update() {
        val service = activity.service
        if (service == null || cues.isEmpty() || service.currentMediaWrapper?.uri != uri) {
            view?.visibility = View.GONE
            return
        }
        // (the first language shown is this one already: not twice)
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck > 1000L) {
            lastCheck = now
            sameShown = activity.rdSubtitleLocals().firstOrNull { it.checked }?.language?.substringBefore('-') == language.substringBefore('-')
        }
        val line = if (sameShown) "" else RdDualSubtitles.textAt(cues, RdDualSubtitles.cueTime(service.getTime(), service.spuDelay))
        val text = ensureView() ?: return
        val video = activity.findViewById<View>(R.id.surface_video)
        if (line.isEmpty() || video == null || video.height == 0) {
            text.visibility = View.GONE
            return
        }
        val frame = text.parent as? View ?: return
        video.getLocationInWindow(location)
        frame.getLocationInWindow(frameLocation)
        val settings = Settings.getInstance(activity)
        val scale = org.videolan.tools.SubtitleScale.get(settings)
        val pictureHeight = service.currentVideoTrack?.getHeight() ?: 0
        // the size of the first language's text (libvlc's: a 16th of the picture at 100 %), a little smaller
        text.setStyle(video.height / 16f * scale / 100f * 0.9f, (video.width * 0.9f).toInt())
        text.setText(line)
        text.visibility = View.VISIBLE
        val bottom = location[1] - frameLocation[1] + video.height -
            RdDualSubtitles.bottomOffset(video.height, scale, settings.getInt("rd_sub_margin", 0), pictureHeight)
        text.translationY = (bottom - text.measuredHeight.coerceAtLeast(text.height)).toFloat()
    }

    /** a subtitle line: white, a black outline, centred, as libvlc draws the first one */
    @SuppressLint("ViewConstructor")
    class OutlinedText(context: Context) : View(context) {
        private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private var text = ""
        private var size = 0f
        private var maxWidth = 0
        private var fillLayout: StaticLayout? = null
        private var strokeLayout: StaticLayout? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setStyle(textSize: Float, width: Int) {
            if (textSize == size && width == maxWidth) return
            size = textSize
            maxWidth = width
            fill.textSize = textSize
            stroke.textSize = textSize
            stroke.strokeWidth = textSize * 0.14f
            rebuild()
        }

        fun setText(value: String) {
            if (value == text) return
            text = value
            rebuild()
        }

        @Suppress("DEPRECATION")
        private fun layout(paint: TextPaint) = StaticLayout(text, paint, maxWidth.coerceAtLeast(1), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)

        private fun rebuild() {
            fillLayout = layout(fill)
            strokeLayout = layout(stroke)
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val l = fillLayout
            setMeasuredDimension(maxWidth.coerceAtLeast(1), (l?.height ?: 0) + (stroke.strokeWidth * 2).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(0f, stroke.strokeWidth)
            strokeLayout?.draw(canvas)
            fillLayout?.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private const val TAG = "VLC/DualSubtitles"
    }
}

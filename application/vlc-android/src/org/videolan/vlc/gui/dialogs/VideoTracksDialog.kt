/*
 * ************************************************************************
 *  AddToGroupDialog.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
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

package org.videolan.vlc.gui.dialogs

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.videolan.tools.DependencyProvider
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.tools.setGone
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.databinding.PlayerOverlayTracksBinding
import org.videolan.vlc.getDisableTrack
import org.videolan.vlc.gui.dialogs.adapters.TrackAdapter
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.gui.view.RdScaleFrame
import org.videolan.vlc.util.SeriesPreset
import org.videolan.vlc.util.isTalkbackIsEnabled
import org.videolan.vlc.isVLC4

class VideoTracksDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = true

    // VLC Redesign: the desktop's panel, above the control bar's button (the Subtitles / tracks button)
    override fun anchorId(): Int = R.id.player_overlay_tracks

    private lateinit var binding: PlayerOverlayTracksBinding

    // the panel's scale: the desktop's sizes (half a dp a design px on a 1080p TV), 1 dp elsewhere
    private val scale by lazy { RdScaleFrame.panelScale(requireContext()) }

    // (the tab shown has the focus as the panel opens, as on the desktop)
    override fun initialFocusedView(): View = tabViews.getOrNull(rdSection) ?: binding.rdTracksTabs

    override fun allowRemote() = true

    /** the desktop's panel: Window Background, radius 6, the window's 1 px line */
    override fun floatingBackground(view: View): Drawable = GradientDrawable().apply {
        val unit = resources.displayMetrics.density * scale
        setColor(ContextCompat.getColor(view.context, R.color.rd_dark_bg_b1))
        setStroke(maxOf(1, (unit + 0.5f).toInt()), ContextCompat.getColor(view.context, R.color.rd_dark_window_border_token))
        cornerRadius = 6 * unit
    }

    // (the desktop's 560 high on a TV; elsewhere about half the screen, as the other panels)
    override fun anchoredMaxHeight(): Int = if (scale < 1f) (560 * resources.displayMetrics.density * scale).toInt() else Int.MAX_VALUE

    // (the TV layout: the desktop's 560 at every interface size, not the half screen that held less and less of it)
    override fun anchoredHeight(): Int = if (org.videolan.tools.Settings.showTvUi) (560 * resources.displayMetrics.density * scale).toInt() else 0

    override fun anchoredScale(): Float = scale

    lateinit var menuItemListener: (VideoTrackOption) -> Unit
    lateinit var trackSelectionListener: (String, TrackType) -> Unit

    private var subsPanel: RdSubtitlesPanel? = null

    private fun onServiceChanged(service: PlaybackService?) {
        service?.let { playbackService ->
            // VLC Redesign: each kind of track has its own tab, so a single video track still shows in its tab
            rdService = playbackService
            updateTiming()
            readLook()

            playbackService.videoTracks?.let { trackList ->
                val trackAdapter = TrackAdapter(generateTrackList(trackList), trackList.firstOrNull { it.getId() == playbackService.videoTrack }, getString(R.string.track_video))
                trackAdapter.setOnTrackSelectedListener { track ->
                    trackSelectionListener.invoke(track.getId(), TrackType.VIDEO)
                }
                binding.videoTracks.trackList.adapter = trackAdapter
                binding.videoTracks.trackTitle.contentDescription = getString(R.string.talkback_video_tracks)
            }
            playbackService.audioTracks?.let { trackList ->
                val trackAdapter = TrackAdapter(generateTrackList(trackList), trackList.firstOrNull { it.getId() == playbackService.audioTrack }, getString(R.string.track_audio))
                trackAdapter.setOnTrackSelectedListener { track ->
                    rdChanged = true
                    trackSelectionListener.invoke(track.getId(), TrackType.AUDIO)
                }
                binding.audioTracks.trackList.adapter = trackAdapter
                binding.audioTracks.trackTitle.contentDescription = getString(R.string.talkback_audio_tracks)
            }
            applyMediaKind(playbackService)
            setupSeries(playbackService)
            subsPanel?.start()
        }
    }

    /**
     * Create a track list containing a fake "Disable track" track
     *
     * @param trackList the base track list
     * @return a complete track list
     */
    @Suppress("UNCHECKED_CAST")
    private fun generateTrackList(trackList: Array<out VlcTrack>) = if (isVLC4()) {
        val tempTracks = ArrayList<VlcTrack>()
        tempTracks.add(getDisableTrack(requireActivity()))
        trackList.forEach {
            tempTracks.add(it)
        }
        tempTracks.toList().toTypedArray()
    } else trackList as Array<VlcTrack>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = PlayerOverlayTracksBinding.inflate(layoutInflater, container, false)
        // (on a TV the panel is laid out at the design's sizes and drawn at half)
        return RdScaleFrame.wrap(requireContext(), binding.root)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.audioTracks.trackTitle.text = getString(R.string.audio)
        binding.videoTracks.trackTitle.text = getString(R.string.video)

        binding.audioTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())
        binding.videoTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())

        binding.videoTracks.trackMore.setGone()

        //prevent focus
        binding.tracksSeparator3.isEnabled = false
        binding.tracksSeparator2.isEnabled = false

        // the sheet of a phone held upright: the desktop panel's colour, its top corners rounded
        binding.rdTracksRoot.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(requireContext(), R.color.rd_dark_bg_b1))
            setStroke(1.dp, ContextCompat.getColor(requireContext(), R.color.rd_dark_window_border_token))
            val r = 14.dp.toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

        generateSeparator(binding.audioTracks.options)
        generateOptionItem(binding.audioTracks.options, getString(R.string.audio_delay), R.drawable.ic_delay, VideoTrackOption.AUDIO_DELAY)
        generateSeparator(binding.audioTracks.options, true)
        binding.audioTracks.options.setAnimationUpdateListener {
            binding.audioTracks.trackMore.rotation = if (binding.audioTracks.options.isCollapsed) 180F - (180F * it) else 180F * it
        }

        binding.audioTracks.trackMore.setOnClickListener {
            binding.audioTracks.options.toggle()
        }

        if (requireActivity().isTalkbackIsEnabled()) {
            binding.audioTracks.options.onReady {
                binding.audioTracks.trackMore.setGone()
                binding.audioTracks.options.lock()
            }
        }

        (activity as? VideoPlayerActivity)?.let { player ->
            subsPanel = RdSubtitlesPanel(rdSubsView, player,
                selectTrack = { id ->
                    rdChanged = true
                    trackSelectionListener.invoke(id, TrackType.SPU)
                },
                pickFile = {
                    menuItemListener.invoke(VideoTrackOption.SUB_PICK)
                    dismiss()
                },
                openServices = { openSettings("rd_sub_services_help") })
        }
        setupTabs()
        setupFooter()
        setupTiming()
        setupLook()
        super.onViewCreated(view, savedInstanceState)
        PlaybackService.serviceFlow.onEach { onServiceChanged(it) }.launchIn(lifecycleScope)
    }

    // VLC Redesign: the tabs of the desktop panel (Video, Audio, Subtitles: its icon and its name, the accent line
    // under the one shown), opening on Subtitles; each hidden when the media has nothing for it
    private var rdTabsKey: String? = null
    private var tabViews: List<View> = emptyList()
    private val tabIcons = listOf(Pair(R.drawable.rd_ic_track_video, R.drawable.rd_ic_track_video_active),
        Pair(R.drawable.rd_ic_track_audio, R.drawable.rd_ic_track_audio_active),
        Pair(R.drawable.rd_ic_track_subtitles, R.drawable.rd_ic_track_subtitles_active))

    private fun setupTabs() {
        val ctx = binding.rdTracksTabs.context
        val accent = ctx.rdAccent()
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        tabViews = listOf(R.string.video, R.string.audio, R.string.subtitles).mapIndexed { i, label ->
            val tab = FrameLayout(ctx)
            tab.isFocusable = true
            tab.isClickable = true
            // (A5 under the finger, as the desktop's under the pointer; a remote's focus is the TV's ring)
            tab.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(ContextCompat.getColor(ctx, R.color.rd_dark_bg_a5)))
            }
            tab.contentDescription = getString(label)
            val content = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = ImageView(ctx).apply { tag = "icon"; scaleType = ImageView.ScaleType.FIT_CENTER }
            content.addView(icon, LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(8) })
            val text = TextView(ctx).apply {
                tag = "text"
                text = getString(label)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tab.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val line = View(ctx).apply { tag = "line"; setBackgroundColor(accent) }
            tab.addView(line, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2), Gravity.BOTTOM).apply { marginStart = px(12); marginEnd = px(12) })
            tab.setOnClickListener { showSection(i) }
            binding.rdTracksTabs.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tab
        }
        // (the icons when the panel is 320 wide or more, as the desktop's)
        binding.rdTracksTabs.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val wide = v.width >= 320 * d
            tabViews.forEach { t -> t.findViewWithTag<View>("icon")?.let { if (it.isVisible != wide) v.post { it.isVisible = wide } } }
        }
        configureTabs(hasVideo = true, subtitles = true)
    }

    private fun paintTabs() {
        val ctx = binding.rdTracksTabs.context
        tabViews.forEachIndexed { i, tab ->
            val on = i == rdSection
            tab.findViewWithTag<TextView>("text")?.setTextColor(if (on) ctx.rdAccent() else ContextCompat.getColor(ctx, R.color.rd_dark_fg_a2))
            tab.findViewWithTag<ImageView>("icon")?.setImageResource(if (on) tabIcons[i].second else tabIcons[i].first)
            tab.findViewWithTag<View>("line")?.visibility = if (on) View.VISIBLE else View.INVISIBLE
            tab.isSelected = on
        }
    }

    private var rdStream = false

    // (the series preset under the audio tracks and under the subtitles: a local file only)
    private fun sections() = listOf(listOf<View>(binding.videoTracks.root),
            if (rdStream) listOf<View>(binding.audioTracks.root) else listOf<View>(binding.audioTracks.root, binding.rdSeries.root),
            if (rdStream) listOf(rdSubsView) else listOf(rdSubsView, binding.rdSubTiming.root, binding.rdSeries.root, binding.rdSubLook.root))

    // the Subtitles tab's panel (rd_subtitles_panel)
    private val rdSubsView: View get() = binding.root.findViewById(R.id.rd_subs)

    private var rdSection = 2

    private fun showSection(index: Int) {
        rdSection = index
        val shown = sections().getOrElse(index) { emptyList() }
        (sections().flatten() + binding.rdSeries.root + binding.rdSubTiming.root + binding.rdSubLook.root).distinct()
            .forEach { it.visibility = if (it in shown) View.VISIBLE else View.GONE }
        paintTabs()
    }

    // The series preset: on, the choices are kept for the episodes of the series (and again as they change here)
    private var rdSeriesKey: String? = null
    private var rdChanged = false
    private var rdSeriesBound = false

    private fun setupSeries(service: PlaybackService) {
        val series = binding.rdSeries
        val key = SeriesPreset.key(service.currentMediaWrapper?.uri)
        if (rdSeriesBound && key == rdSeriesKey) return
        rdSeriesBound = true
        rdSeriesKey = key
        series.rdSeriesSwitch.setOnCheckedChangeListener(null)
        series.rdSeriesSwitch.isEnabled = key != null
        series.rdSeriesSwitch.isChecked = key != null && SeriesPreset.has(requireContext(), key)
        series.rdSeriesDetail.text = if (key == null) getString(R.string.rd_series_play_local) else getString(R.string.rd_series_applies, SeriesPreset.label(key))
        series.rdSeriesSwitch.setOnCheckedChangeListener { _, on ->
            val k = rdSeriesKey ?: return@setOnCheckedChangeListener
            if (on) {
                if (SeriesPreset.save(requireContext(), service)) series.rdSeriesDetail.text = getString(R.string.rd_series_saved, SeriesPreset.label(k))
            } else {
                SeriesPreset.remove(requireContext(), k)
                series.rdSeriesDetail.text = getString(R.string.rd_series_removed)
            }
        }
    }

    override fun onDestroyView() {
        subsPanel?.stop()
        subsPanel = null
        // a choice changed here while the series keeps its choices: kept again
        val key = rdSeriesKey
        val service = rdService
        if (rdChanged && key != null && service != null && SeriesPreset.has(requireContext(), key)) SeriesPreset.save(requireContext(), service)
        super.onDestroyView()
    }

    /**
     * The owner's rule for every platform: subtitles are for films. Audio only: no Subtitles tab at all (and no
     * Video tab). A Live TV channel or a live stream: only its own subtitle tracks (on, off, language) when it
     * carries any, never a file, an online search or the timing. A video on a share, a media server or behind a
     * link is a film as a file is (KuchiSofts/vlc-redesign#73: every address but a file's was taken for a live
     * stream, its Subtitles tab and its online search gone).
     */
    private fun configureTabs(hasVideo: Boolean, subtitles: Boolean) {
        val key = "$hasVideo/$subtitles"
        if (key == rdTabsKey) return
        rdTabsKey = key
        val ids = buildList { if (hasVideo) add(0); add(1); if (subtitles) add(2) }
        tabViews.forEachIndexed { i, tab -> tab.isVisible = i in ids }
        val target = rdSection.takeIf { it in ids } ?: ids.last()
        val hadFocus = tabViews.any { it.hasFocus() }
        showSection(target)
        if (hadFocus) tabViews.getOrNull(target)?.requestFocus()
    }

    private fun applyMediaKind(service: PlaybackService) {
        val media = service.currentMediaWrapper
        val hasVideo = service.videoTracksCount > 0
        val stream = media != null && org.videolan.vlc.util.OnlineSubtitles.isLive(media.uri?.scheme, service.length,
            org.videolan.vlc.livetv.TvPlayback.isCurrent(media.uri?.toString()))
        val streamHasSubs = (service.spuTracks?.size ?: 0) > 0
        rdStream = stream
        subsPanel?.let { if (it.fileFeatures == stream) it.fileFeatures = !stream }
        configureTabs(hasVideo, hasVideo && (!stream || streamHasSubs))
        showSection(rdSection)
    }

    /** the footer: Playback (the playback speed) and the settings of the tab shown */
    private fun setupFooter() {
        binding.rdTracksPlayback.setOnClickListener {
            val fm = parentFragmentManager
            dismiss()
            if (fm.findFragmentByTag("playback_speed") == null) PlaybackSpeedDialog.newInstance().show(fm, "playback_speed")
        }
        binding.rdTracksPreferences.setOnClickListener {
            openSettings(when (rdSection) {
                0 -> "opengl"
                1 -> "aout"
                else -> "subtitle_preferred_language"
            })
        }
    }

    /** Settings at the row of [key] (its page); this panel closes */
    private fun openSettings(key: String) {
        val player = activity ?: return
        player.lifecycleScope.launch {
            try {
                org.videolan.vlc.gui.preferences.PreferencesActivity.launchWithPref(player, key)
            } catch (e: Exception) {
                // (the row not found: the settings open on their first page)
                player.startActivity(android.content.Intent(player, org.videolan.vlc.gui.preferences.PreferencesActivity::class.java))
            }
        }
        dismissAllowingStateLoss()
    }

    private var rdService: PlaybackService? = null
    private var voiceMark = -1L
    private var textMark = -1L

    // Subtitle timing: the delay slider (ms, the player takes µs) and the voice/text marks
    private fun setupTiming() {
        val timing = binding.rdSubTiming
        timing.rdSubDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                rdService?.setSpuDelay(value.toLong() * 1000L)
                rdChanged = true
            }
            showDelay(value.toLong())
        }
        timing.rdSubDelayReset.setOnClickListener {
            rdService?.setSpuDelay(0L)
            rdChanged = true
            voiceMark = -1L
            textMark = -1L
            updateTiming()
        }
        timing.rdSubMarkVoice.setOnClickListener {
            voiceMark = if (voiceMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }
        timing.rdSubMarkText.setOnClickListener {
            textMark = if (textMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }
        // the sync timeline takes the panel's place (the video stays visible above it)
        timing.rdSubSyncOpen.setOnClickListener {
            val fm = parentFragmentManager
            dismiss()
            if (fm.findFragmentByTag("subtitle_sync") == null) SubtitleSyncDialog().show(fm, "subtitle_sync")
        }
    }

    /**
     * Position and size (the desktop's rows: − value + Reset, the buttons repeat while held). The size: 25 to 500 %
     * (SubtitleScale), the steps of the desktop's Ctrl + wheel; the position: up from the bottom of the video by 1 %
     * of its height a step ("Usual" at the bottom), the vertical position of Settings › Subtitles. The playing video
     * takes them a moment after the last change (VideoPlayerActivity: libvlc 3 reads them when it starts).
     */
    private fun setupLook() {
        val look = binding.rdSubLook
        repeatWhileHeld(look.rdSubSizeLower) { applySize(org.videolan.tools.SubtitleScale.step(sizeShown, false)) }
        repeatWhileHeld(look.rdSubSizeHigher) { applySize(org.videolan.tools.SubtitleScale.step(sizeShown, true)) }
        look.rdSubSizeReset.setOnClickListener { applySize(org.videolan.tools.SubtitleScale.DEFAULT) }
        repeatWhileHeld(look.rdSubPositionLower) { applyPosition(positionShown - 1) }
        repeatWhileHeld(look.rdSubPositionHigher) { applyPosition(positionShown + 1) }
        look.rdSubPositionReset.setOnClickListener { applyPosition(0) }
        readLook()
    }

    /** the size and the position as kept (the position in % of the video playing) */
    private fun readLook() {
        val settings = org.videolan.tools.Settings.getInstance(requireContext())
        sizeShown = org.videolan.tools.SubtitleScale.get(settings)
        positionShown = percentOf(settings.getInt("rd_sub_margin", 0))
        updateLook()
    }

    private var sizeShown = org.videolan.tools.SubtitleScale.DEFAULT
    private var positionShown = 0

    // the video's height, in its pixels (the position's steps are 1 % of it)
    private fun videoHeight() = rdService?.currentVideoTrack?.getHeight()?.takeIf { it > 0 } ?: 1080

    private fun percentOf(margin: Int) = Math.round(margin * 100f / videoHeight())

    // the highest the position goes: 60 % as on the desktop, within the 400 px libvlc is given
    private fun maxPosition() = minOf(60, 400 * 100 / videoHeight())

    private fun applySize(scale: Int) {
        val value = org.videolan.tools.SubtitleScale.clamp(scale)
        rdChanged = true
        sizeShown = value
        (activity as? VideoPlayerActivity)?.setSubtitleScale(value, osd = false)
            ?: org.videolan.tools.Settings.getInstance(requireContext()).putSingle(org.videolan.tools.KEY_SUBTITLES_TEXT_SCALE, value)
        updateLook()
    }

    private fun applyPosition(percent: Int) {
        val value = percent.coerceIn(0, maxPosition())
        positionShown = value
        val margin = Math.round(value * videoHeight() / 100f)
        (activity as? VideoPlayerActivity)?.setSubtitleMargin(margin)
            ?: org.videolan.tools.Settings.getInstance(requireContext()).putSingle("rd_sub_margin", margin)
        updateLook()
    }

    private fun updateLook() {
        if (!::binding.isInitialized) return
        val look = binding.rdSubLook
        // (isolated: "100%" and "+5%" in a right to left language too)
        look.rdSubSizeValue.text = String.format(java.util.Locale.getDefault(), "⁦%d%%⁩", sizeShown)
        look.rdSubSizeLower.isEnabled = sizeShown > org.videolan.tools.SubtitleScale.MIN
        look.rdSubSizeHigher.isEnabled = sizeShown < org.videolan.tools.SubtitleScale.MAX
        look.rdSubSizeReset.isEnabled = sizeShown != org.videolan.tools.SubtitleScale.DEFAULT
        look.rdSubPositionValue.text = if (positionShown == 0) getString(R.string.rd_subs_usual)
            else String.format(java.util.Locale.getDefault(), "⁦+%d%%⁩", positionShown)
        look.rdSubPositionLower.isEnabled = positionShown > 0
        look.rdSubPositionHigher.isEnabled = positionShown < maxPosition()
        look.rdSubPositionReset.isEnabled = positionShown != 0
    }

    /** a click, and again and again while the button is held (the desktop's autoRepeat) */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatWhileHeld(button: View, action: () -> Unit) {
        val again = object : Runnable {
            override fun run() {
                if (!button.isPressed || !button.isEnabled) return
                action()
                button.postDelayed(this, 120L)
            }
        }
        button.setOnClickListener { action() }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.postDelayed(again, 500L)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(again)
            }
            false
        }
    }

    /** Once both are marked: the text came (text - voice) late, so the subtitles move that much earlier. */
    private fun applyMarks() {
        val service = rdService ?: return
        if (voiceMark >= 0 && textMark >= 0) {
            val delay = (service.spuDelay / 1000L + (voiceMark - textMark)).coerceIn(-10000L, 10000L)
            service.setSpuDelay(delay / 50L * 50L * 1000L)
            rdChanged = true
            voiceMark = -1L
            textMark = -1L
        }
        updateTiming()
    }

    private fun updateTiming() {
        if (!::binding.isInitialized) return
        val timing = binding.rdSubTiming
        val delay = ((rdService?.spuDelay ?: 0L) / 1000L).coerceIn(-10000L, 10000L) / 50L * 50L
        timing.rdSubDelaySlider.value = delay.toFloat()
        showDelay(delay)
        timing.rdSubMarkVoice.text = getString(if (voiceMark >= 0) R.string.rd_voice_marked else R.string.rd_mark_voice_heard)
        timing.rdSubMarkText.text = getString(if (textMark >= 0) R.string.rd_text_marked else R.string.rd_mark_text_seen)
        timing.rdSubMarkVoice.isChecked = voiceMark >= 0
        timing.rdSubMarkText.isChecked = textMark >= 0
        timing.rdSubDelayReset.isEnabled = delay != 0L || voiceMark >= 0 || textMark >= 0
    }

    private fun showDelay(ms: Long) {
        binding.rdSubTiming.rdSubDelayValue.text = String.format(java.util.Locale.US, "%+d ms", ms)
    }

    private fun generateSeparator(parent: ViewGroup, margin: Boolean = false) {
        val view = View(context)
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.white_transparent_50))
        val lp = LinearLayout.LayoutParams(-1, 1.dp)

        lp.marginStart = if (margin) 56.dp else 16.dp
        lp.marginEnd = 16.dp
        lp.topMargin = 8.dp
        lp.bottomMargin = 8.dp
        view.layoutParams = lp
        parent.addView(view)
    }

    private fun generateOptionItem(parent: ViewGroup, title: String, @DrawableRes icon: Int, optionId: VideoTrackOption) {
        val view = layoutInflater.inflate(R.layout.player_overlay_track_option_item, null)
        view.findViewById<TextView>(R.id.option_title).text = title
        view.findViewById<ImageView>(R.id.option_icon).setImageBitmap(requireContext().getBitmapFromDrawable(icon))
        view.setOnClickListener {
            menuItemListener.invoke(optionId)
            dismiss()
        }
        parent.addView(view)
    }

    companion object : DependencyProvider<Any>() {

        const val TAG = "VLC/SavePlaylistDialog"
    }

    enum class TrackType {
        VIDEO, AUDIO, SPU
    }

    enum class VideoTrackOption {
        SUB_DELAY, SUB_PICK, SUB_DOWNLOAD, AUDIO_DELAY
    }
}

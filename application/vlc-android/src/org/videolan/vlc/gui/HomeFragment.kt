/*
 * HomeFragment.kt
 *
 * VLC Redesign: the Home page of the library (Figma 6261:15078, desktop docs/wiki/Library.md "Home").
 * Continue Watching, Listen Again, Frequently Visited Folders, Favorites, New Videos and New Music, the newest
 * first, one row of cards each; a section with nothing in it is hidden.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.videolan.vlc.gui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.tools.retrieveParent
import org.videolan.vlc.R
import org.videolan.tools.dp
import org.videolan.tools.rdWidthDp
import org.videolan.vlc.gui.tablet.RdTvPrebuild
import org.videolan.vlc.gui.tablet.RdTvWindow
import org.videolan.vlc.databinding.RdHomeCardBinding
import org.videolan.vlc.gui.browser.KEY_MEDIA
import org.videolan.vlc.gui.dialogs.CtxActionReceiver
import org.videolan.vlc.gui.dialogs.SavePlaylistDialog
import org.videolan.vlc.gui.dialogs.showContext
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.UiTools.addToPlaylist
import org.videolan.vlc.gui.helpers.getAudioIconDrawable
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.askStoragePermission
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.gui.helpers.loadImage
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.ContextOption
import org.videolan.vlc.util.ContextOption.*
import org.videolan.vlc.util.FlagSet
import org.videolan.vlc.gui.livetv.TvNavigation
import org.videolan.vlc.util.Pins
import org.videolan.vlc.util.share
import org.videolan.vlc.util.showParentFolder

private const val MAX_CARDS = 20

class HomeFragment : BaseFragment(), CtxActionReceiver, Medialibrary.HistoryCb, Medialibrary.MediaCb,
        Medialibrary.OnMedialibraryReadyListener {

    /** One card: a media, or a folder (a directory media) of Frequently Visited Folders. */
    private class Card(val media: MediaWrapper, val folder: Boolean = false, val playCount: Long = 0L, val pin: Pins.Pin? = null)

    private enum class Kind { VIDEO, AUDIO, FOLDER, MIXED }

    /** The TV layout: room kept around a card inside its row, in dp (none: the remote's ring is drawn outside the card) */
    private val RING = 0

    private inner class Section(root: View, titleRes: Int, val kind: Kind, onMore: (() -> Unit)?) {
        val view: View = root
        val list: RecyclerView = root.findViewById(R.id.rd_section_list)
        val adapter = CardAdapter(this)
        var cards: List<Card> = emptyList()

        init {
            root.findViewById<TextView>(R.id.rd_section_title).setText(titleRes)
            val header = root.findViewById<View>(R.id.rd_section_header)
            if (onMore == null) {
                root.findViewById<View>(R.id.rd_section_chevron).visibility = View.GONE
                header.isClickable = false
                header.background = null
            } else header.setOnClickListener { onMore() }
            list.layoutManager = object : LinearLayoutManager(root.context, HORIZONTAL, false) {
                /**
                 * The remote past the last whole card: the row's cards fill it exactly, so the next card is laid out
                 * beyond the row's edge and not "touching" it, which the list takes for nothing to go to (the focus
                 * then left the row for the title bar). The card before or after the focused one is the answer.
                 */
                override fun onFocusSearchFailed(focused: View, direction: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): View? {
                    super.onFocusSearchFailed(focused, direction, recycler, state)?.let { return it }
                    val item = findContainingItemView(focused) ?: return null
                    val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val step = when (direction) {
                        View.FOCUS_RIGHT -> if (rtl) -1 else 1
                        View.FOCUS_LEFT -> if (rtl) 1 else -1
                        else -> return null
                    }
                    return findViewByPosition(getPosition(item) + step)
                }
            }
            list.adapter = adapter
            if (RdTvWindow.isOn(root.context)) {
                // the design's section (.sh-sec): the title 20 in FG/2 at 28 with its chevron 24, the cards 21 under
                // it, 76 between a row and the next title
                (header.layoutParams as ViewGroup.MarginLayoutParams).apply { height = 24.dp; topMargin = 0; marginStart = 24.dp }
                header.setPaddingRelative(4.dp, 0, 4.dp, 0)
                root.findViewById<TextView>(R.id.rd_section_title).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f); setTextColor(RdTvWindow.FG_2); includeFontPadding = false
                }
                root.findViewById<View>(R.id.rd_section_chevron).layoutParams = (root.findViewById<View>(R.id.rd_section_chevron).layoutParams as ViewGroup.MarginLayoutParams).apply { width = 24.dp; height = 24.dp; marginStart = 6.dp }
                (list.layoutParams as ViewGroup.MarginLayoutParams).topMargin = (21 - RING).dp
                // the row from 28 to 20 before the page's end, as the header over it; its cards are sized to fill it
                // (cardWidth), so the window cuts none: the row itself is the clip, not the page's edge
                // (each card has RING dp around it for the remote's ring, so the row starts and ends that much earlier
                // and later: the pictures still run from 28 to 20 before the page's end)
                list.setPaddingRelative(0, 0, 0, 0)
                list.clipToPadding = true
                (list.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = (28 - RING).dp; marginEnd = (20 - RING).dp }
                org.videolan.vlc.gui.helpers.RdFit.wholeCards(list)
                list.setFadingEdgeLength(24.dp)
                // (76 on the design's own page; at a larger interface size the page holds fewer dp down: 28, so that
                // two rows are whole at rest, not one and three quarters with the second one's cards cut)
                (root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (if (org.videolan.tools.rdPageScale > 1.01f) 28 else 76 - 2 * RING).dp
            }
        }

        /**
         * The TV layout: a card's width in this row, the design's ([base] px) grown a little so that whole cards
         * fill the row at every interface size (a 4th card cut by the screen's edge at 150 %).
         */
        fun cardWidth(base: Int): Int {
            // a row of both shapes (favourites) cannot end on a card's edge for each: the design's sizes, a fade
            if (list.isHorizontalFadingEdgeEnabled) return base
            val row = (list.layoutManager?.width ?: 0).takeIf { it > 0 } ?: list.width.takeIf { it > 0 }
                    ?: ((list.resources.configuration.rdWidthDp - RdTvWindow.MAIN_START - 28 - 20 + 2 * RING).dp)
            return org.videolan.vlc.gui.helpers.RdFit.cards(row - 2 * RING.dp, base, 20.dp).width
        }

        private var signature = ""

        fun update(newCards: List<Card>) {
            // the library reports every thumbnail it makes as a change: redraw only when the cards changed
            val newSignature = newCards.joinToString("|") { c ->
                val m = c.media
                "${m.id}:${m.uri}:${m.title}:${m.displayTime}:${m.seen}:${m.isFavorite}:${m.artworkMrl}"
            }
            if (newSignature == signature && view.visibility == (if (newCards.isEmpty()) View.GONE else View.VISIBLE)) return
            signature = newSignature
            cards = newCards
            // (a row of videos and songs together ends in a fade: its cards are of two widths)
            list.isHorizontalFadingEdgeEnabled = newCards.any { it.media.type == MediaWrapper.TYPE_VIDEO && !it.folder } && newCards.any { it.media.type != MediaWrapper.TYPE_VIDEO || it.folder }
            adapter.notifyDataSetChanged()
            view.visibility = if (newCards.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private inner class CardHolder(val binding: RdHomeCardBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * The TV layout, the remote along a row: the card before or after takes the focus at once, the row moved by whole
     * cards so that it is in sight. Left to the focus search, a quick second press while the row was still sliding
     * found no card (the next one lies beyond the row's edge) and the focus left the row for its heading or the
     * title bar.
     */
    private fun rowKey(section: Section, card: View, code: Int, event: android.view.KeyEvent): Boolean {
        if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT && code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val list = section.list
        val lm = list.layoutManager as? LinearLayoutManager ?: return false
        val rtl = list.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val forward = (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) != rtl
        val at = lm.getPosition(card)
        val next = at + if (forward) 1 else -1
        // the row's first card: on to the sidebar (the window's own rule); its last: the focus stays
        if (next < 0) return false
        if (next >= section.cards.size) return true
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return true
        list.stopScroll()
        fun bring(): Boolean {
            val v = lm.findViewByPosition(next) ?: return false
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            // whole in the row: by as much as it is past the row's end or before its start
            val over = v.right - (list.width - list.paddingRight)
            val under = list.paddingLeft - v.left
            if (over > 0) list.scrollBy(over, 0) else if (under > 0) list.scrollBy(-under, 0)
            return lp.width != 0 && v.requestFocus()
        }
        if (!bring()) {
            // not laid out yet (the row holds whole cards only): the row moved one card on, then the focus
            val step = card.width + (card.layoutParams as ViewGroup.MarginLayoutParams).let { it.leftMargin + it.rightMargin }
            list.scrollBy(if (forward != rtl) step else -step, 0)
            if (!bring()) list.post { bring() }
        }
        return true
    }

    private inner class CardAdapter(val section: Section) : RecyclerView.Adapter<CardHolder>() {
        init { setHasStableIds(true) }

        override fun getItemCount() = section.cards.size

        // (the same card keeps its view, and the remote's focus, when the row is drawn again: the library reports
        // every thumbnail it makes, and the focus fell off the row to its heading)
        override fun getItemId(position: Int): Long {
            val c = section.cards[position]
            return ((c.media.uri?.toString() ?: c.media.title ?: "") + "|" + c.folder + "|" + (c.pin != null)).hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                CardHolder(RdTvPrebuild.bind(parent, R.layout.rd_home_card) { RdHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false) })

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = section.cards[position]
            val media = card.media
            val b = holder.binding
            val res = b.root.resources
            val video = !card.folder && media.type == MediaWrapper.TYPE_VIDEO
            // a 16:9 picture for a video, a square one for a song or a folder
            val tv = RdTvWindow.isOn(b.root.context)
            // (a short window, the interface at 150 % and more: five covers a little under the design's size, not four
            // grown by a fifth, so the row under the first shows its covers and names before any scrolling)
            val square = if (res.displayMetrics.heightPixels / res.displayMetrics.density < 760f) 184.dp else 200.dp
            val width = if (tv) section.cardWidth(if (video) 316.dp else square) else res.getDimensionPixelSize(if (video) R.dimen.rd_home_video_width else R.dimen.rd_home_square_width)
            val height = if (tv && video) width * 178 / 316 else if (video) width * 9 / 16 else width
            if (tv) {
                // the design's card (.sh-card): no frame around it, 20 to the next; the picture radius 8; the name
                // 16 in FG/2, 20 under the picture; the artist 16 in FG/A1, 10 under the name
                // (RING around the card: the remote's ring went through the name's first letter and along its feet)
                (b.root.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = 0; marginEnd = (20 - 2 * RING).dp }
                b.root.setPadding(RING.dp, RING.dp, RING.dp, RING.dp)
                b.rdCoverCard.radius = 8.dp.toFloat()
                for ((t, top) in listOf(b.rdTitle to 20, b.rdSubtitle to 10)) {
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
                    t.includeFontPadding = false
                    t.setLineSpacing(0f, 1f)
                    (t.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top.dp
                }
            }
            b.rdCoverCard.layoutParams = b.rdCoverCard.layoutParams.apply { this.width = width; this.height = height }
            b.rdTitle.layoutParams = b.rdTitle.layoutParams.apply { this.width = width }
            b.rdSubtitle.layoutParams = b.rdSubtitle.layoutParams.apply { this.width = width }
            b.isFolder = card.folder
            b.media = media
            b.title = media.title
            b.subtitle = when {
                card.folder -> null
                media.type == MediaWrapper.TYPE_AUDIO -> media.artistName
                else -> null
            }
            b.seen = video && media.seen > 0L && section.kind != Kind.VIDEO
            val progress = if (video && media.length > 0L && media.displayTime > 0L) (media.displayTime * 1000L / media.length).toInt().coerceIn(0, 1000) else 0
            b.progress = progress
            b.rdProgress.layoutParams = b.rdProgress.layoutParams.apply { this.width = width * progress / 1000 }
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.cover = if (card.folder) null else if (card.pin != null) UiTools.getDefaultPlaylistDrawable(b.root.context)
                    else if (video) UiTools.getDefaultVideoDrawable(b.root.context)
                    else getAudioIconDrawable(b.root.context, MediaWrapper.TYPE_AUDIO, true)
            if (!card.folder) b.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.executePendingBindings()
            if (tv) {
                // the design's folder glyph (shell.css .sh-card-thumb.ph: 48 in #8F8F8F)
                b.rdFolderGlyph.setImageResource(R.drawable.rd_tvd_folder)
                b.rdFolderGlyph.setColorFilter(RdTvWindow.ICON)
                b.rdFolderGlyph.layoutParams = b.rdFolderGlyph.layoutParams.also { it.width = 48.dp; it.height = 48.dp }
                b.rdTitle.setTextColor(if (b.seen == true) 0xFF6B6B6B.toInt() else RdTvWindow.FG_2)
                b.rdSubtitle.setTextColor(0xFF6B6B6B.toInt())
            }
            if (!card.folder && card.pin == null) loadImage(b.rdCover, media, width, card = true)
            b.root.contentDescription = media.title
            if (tv) b.root.setOnKeyListener { v, code, event -> rowKey(section, v, code, event) }
            b.root.setOnClickListener { onCardClick(section, holder.bindingAdapterPosition) }
            b.root.setOnLongClickListener {
                onCardLongClick(section, holder.bindingAdapterPosition)
                true
            }
        }
    }

    private lateinit var sections: List<Section>
    private lateinit var pinned: Section
    private lateinit var continueWatching: Section
    private lateinit var listenAgain: Section
    private lateinit var folders: Section
    private lateinit var favorites: Section
    private lateinit var newVideos: Section
    private lateinit var newMusic: Section
    private lateinit var emptyView: View
    private var loadJob: Job? = null
    private var ctxSection: Section? = null

    override fun getTitle() = getString(R.string.home)

    override fun hasFAB() = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            RdTvPrebuild.inflate(inflater, R.layout.rd_home_fragment, container)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.rd_home_empty)
        // the design's Home: the first title 35 under the header's line (.sh-home)
        if (RdTvWindow.isOn(view.context)) view.findViewById<View>(R.id.rd_home_scroll).let { it.setPadding(it.paddingLeft, (if (org.videolan.tools.rdPageScale > 1.01f) 24 else 36).dp, it.paddingRight, 40.dp) }
        continueWatching = Section(view.findViewById(R.id.rd_home_continue), R.string.rd_continue_watching, Kind.VIDEO) { openSecondary(SecondaryActivity.HISTORY) }
        listenAgain = Section(view.findViewById(R.id.rd_home_listen), R.string.rd_listen_again, Kind.AUDIO) { openSecondary(SecondaryActivity.HISTORY) }
        folders = Section(view.findViewById(R.id.rd_home_folders), R.string.rd_frequent_folders, Kind.FOLDER) { selectTab(R.id.nav_directories) }
        favorites = Section(view.findViewById(R.id.rd_home_favorites), R.string.favorites, Kind.MIXED, null)
        newVideos = Section(view.findViewById(R.id.rd_home_new_videos), R.string.rd_new_videos, Kind.MIXED) { selectTab(R.id.nav_video) }
        newMusic = Section(view.findViewById(R.id.rd_home_new_music), R.string.rd_new_music, Kind.MIXED) { selectTab(R.id.nav_audio) }
        pinned = Section(view.findViewById(R.id.rd_home_pinned), R.string.rd_pinned, Kind.FOLDER, null)
        sections = listOf(pinned, continueWatching, listenAgain, folders, favorites, newVideos, newMusic)
        // a folder or a playlist pinned or unpinned anywhere
        // (the view's scope: a view made again kept the old one's collectors; the current pins are onStart's refresh)
        viewLifecycleOwner.lifecycleScope.launch { Pins.pins(view.context).drop(1).collect { if (it != null) refresh() } }
        // moved or hidden in Edit Sidebar
        viewLifecycleOwner.lifecycleScope.launch { Pins.layout.drop(1).collect { refresh() } }
    }

    /** The pins, on Home only when the window has no sidebar to show them; in Edit Sidebar's order, the hidden left out */
    private fun pinnedCards(context: android.content.Context): List<Card> {
        if (context.resources.getBoolean(R.bool.rd_tablet_sidebar)) return emptyList()
        return TvNavigation.shownPins(context).map { pin ->
            val uri = if (pin.kind == Pins.FOLDER) Uri.parse(pin.id) else Uri.parse("${pin.kind}:${pin.id}")
            val media = MLServiceLocator.getAbstractMediaWrapper(uri).apply { title = pin.title; type = MediaWrapper.TYPE_DIR }
            Card(media, folder = pin.kind != Pins.PLAYLIST, pin = pin)
        }
    }

    override fun onStart() {
        super.onStart()
        (activity as? ContentActivity)?.setTabLayoutVisibility(false)
        Medialibrary.getInstance().addHistoryCb(this)
        Medialibrary.getInstance().addMediaCb(this)
        Medialibrary.getInstance().addOnMedialibraryReadyListener(this)
        refresh()
    }

    override fun onStop() {
        Medialibrary.getInstance().removeHistoryCb(this)
        Medialibrary.getInstance().removeMediaCb(this)
        Medialibrary.getInstance().removeOnMedialibraryReadyListener(this)
        super.onStop()
    }

    // The library calls these from its own threads, often in bursts while it scans: refresh once it settles.
    private var pendingRefresh: Job? = null
    private fun scheduleRefresh() {
        lifecycleScope.launch {
            pendingRefresh?.cancel()
            pendingRefresh = launch {
                delay(600L)
                refresh()
            }
        }
    }

    override fun onHistoryModified() = scheduleRefresh()
    override fun onMedialibraryReady() = scheduleRefresh()
    override fun onMedialibraryIdle() = scheduleRefresh()
    override fun onMediaAdded() = scheduleRefresh()
    override fun onMediaModified() = scheduleRefresh()
    override fun onMediaDeleted(id: LongArray?) = scheduleRefresh()
    override fun onMediaConvertedToExternal(id: LongArray?) = scheduleRefresh()

    private class HomeData(val continueWatching: List<Card>, val listenAgain: List<Card>, val folders: List<Card>,
                           val favorites: List<Card>, val newVideos: List<Card>, val newMusic: List<Card>)

    fun refresh() {
        val context = context ?: return
        val historyOn = Settings.getInstance(context).getBoolean(PLAYBACK_HISTORY, true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val ml = Medialibrary.getInstance()
                if (!ml.isInitiated) return@withContext null
                // (a file once: opened from another app it is in the history a second time, by its address alone)
                val history = (if (historyOn) ml.history(Medialibrary.HISTORY_TYPE_LOCAL).toList() else emptyList())
                        .distinctBy { m -> m.uri?.path?.takeIf { it.isNotEmpty() && (m.uri?.scheme == null || m.uri?.scheme == "file") } ?: m.location ?: m }
                HomeData(
                        history.filter { it.type == MediaWrapper.TYPE_VIDEO }.take(MAX_CARDS).map { Card(it) },
                        history.filter { it.type == MediaWrapper.TYPE_AUDIO }.take(MAX_CARDS).map { Card(it) },
                        frequentFolders(history),
                        (ml.getVideos(Medialibrary.SORT_DEFAULT, false, false, true) + ml.getAudio(Medialibrary.SORT_DEFAULT, false, false, true))
                                .take(MAX_CARDS).map { Card(it) },
                        // newest first (the library's getRecentAudio() can come back empty: sort by insertion date)
                        ml.getPagedVideos(Medialibrary.SORT_INSERTIONDATE, true, false, false, MAX_CARDS, 0).map { Card(it) },
                        ml.getPagedAudio(Medialibrary.SORT_INSERTIONDATE, true, false, false, MAX_CARDS, 0).map { Card(it) })
            }
            if (!isAdded) return@launch
            if (data == null) {
                // The library has not started: without access to the device's files it never does, and the page
                // stayed blank. It says why and asks; one still starting comes back by onMedialibraryReady.
                showEmpty(context, libraryStarted = false)
                return@launch
            }
            pinned.update(pinnedCards(context))
            continueWatching.update(data.continueWatching)
            listenAgain.update(data.listenAgain)
            folders.update(data.folders)
            favorites.update(data.favorites)
            newVideos.update(data.newVideos)
            newMusic.update(data.newMusic)
            showEmpty(context, libraryStarted = true)
        }
    }

    /**
     * The words of a Home without a card. No access to the device's files: why, and "Grant permission" (the whole
     * text asks for it, with a tap or OK on the remote). A library that has started and holds nothing: "No media
     * files found". One that is still starting says nothing yet.
     */
    private fun showEmpty(context: android.content.Context, libraryStarted: Boolean) {
        val text = emptyView as? TextView ?: return
        val allowed = org.videolan.vlc.util.Permissions.canReadStorage(context)
        val empty = sections.all { it.cards.isEmpty() }
        if (!empty || (!libraryStarted && allowed)) {
            text.visibility = View.GONE
            return
        }
        if (allowed) {
            text.setText(R.string.nomedia)
            text.setOnClickListener(null)
            text.isClickable = false
            text.isFocusable = false
            text.background = null
        } else {
            val ask = getString(R.string.permission_ask_again)
            val words = getString(R.string.permission_media) + "\n\n" + ask
            text.text = android.text.SpannableString(words).apply {
                val start = words.length - ask.length
                setSpan(android.text.style.ForegroundColorSpan(context.rdAccent()), start, words.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, words.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.isFocusable = true
            if (Settings.showTvUi) text.setBackgroundResource(R.drawable.rd_tv_focus_selector)
            else android.util.TypedValue().let { v ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, v, true)
                text.setBackgroundResource(v.resourceId)
            }
            text.setOnClickListener { activity?.askStoragePermission(false, null) }
        }
        text.visibility = View.VISIBLE
    }

    /** The folders of the media played, the most played first (local files only). */
    private fun frequentFolders(history: List<MediaWrapper>): List<Card> {
        val counts = LinkedHashMap<String, Pair<Uri, Long>>()
        for (media in history) {
            if (media.uri?.scheme != "file") continue
            val parent = media.uri.retrieveParent() ?: continue
            val key = parent.toString()
            val previous = counts[key]?.second ?: 0L
            counts[key] = parent to previous + maxOf(1L, media.seen)
        }
        return counts.values.sortedByDescending { it.second }.take(MAX_CARDS).map { (uri, count) ->
            val dir = MLServiceLocator.getAbstractMediaWrapper(uri).apply { type = MediaWrapper.TYPE_DIR }
            Card(dir, folder = true, playCount = count)
        }
    }

    private fun onCardClick(section: Section, position: Int) {
        val card = section.cards.getOrNull(position) ?: return
        val activity = activity ?: return
        card.pin?.let { Pins.open(activity, it); return }
        if (card.folder) {
            val intent = Intent(activity.applicationContext, SecondaryActivity::class.java)
            intent.putExtra(KEY_MEDIA, card.media)
            intent.putExtra("fragment", SecondaryActivity.FILE_BROWSER)
            startActivity(intent)
            return
        }
        when (section.kind) {
            // the songs play on, one after the other, as the desktop's Listen Again
            Kind.AUDIO -> MediaUtils.openList(activity, section.cards.map { it.media }, position)
            else -> MediaUtils.openMedia(activity, card.media)
        }
    }

    private fun onCardLongClick(section: Section, position: Int) {
        val card = section.cards.getOrNull(position) ?: return
        if (card.pin != null) {
            ctxSection = section
            showContext(requireActivity(), this, position, card.media, FlagSet(ContextOption::class.java).apply { add(ContextOption.CTX_UNPIN) })
            return
        }
        if (card.folder) return
        val media = card.media
        ctxSection = section
        val flags = FlagSet(ContextOption::class.java).apply {
            addAll(CTX_PLAY, CTX_APPEND, CTX_PLAY_NEXT, CTX_ADD_TO_PLAYLIST, CTX_INFORMATION, CTX_GO_TO_FOLDER, CTX_SHARE)
            add(if (media.isFavorite) CTX_FAV_REMOVE else CTX_FAV_ADD)
            if (media.type == MediaWrapper.TYPE_VIDEO) {
                add(CTX_PLAY_AS_AUDIO)
                add(if (media.seen > 0L) CTX_MARK_AS_UNPLAYED else CTX_MARK_AS_PLAYED)
            }
        }
        showContext(requireActivity(), this, position, media, flags)
    }

    override fun onCtxAction(position: Int, option: ContextOption) {
        val section = ctxSection ?: return
        section.cards.getOrNull(position)?.pin?.let { pin ->
            if (option == ContextOption.CTX_UNPIN) Pins.unpin(requireContext(), pin)
            return
        }
        val media = section.cards.getOrNull(position)?.media ?: return
        val activity = activity ?: return
        when (option) {
            CTX_PLAY -> onCardClick(section, position)
            CTX_PLAY_AS_AUDIO -> {
                media.addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
                MediaUtils.openMedia(activity, media)
            }
            CTX_APPEND -> MediaUtils.appendMedia(activity, media)
            CTX_PLAY_NEXT -> MediaUtils.insertNext(activity, media.tracks)
            CTX_ADD_TO_PLAYLIST -> activity.addToPlaylist(media.tracks, SavePlaylistDialog.KEY_NEW_TRACKS)
            CTX_INFORMATION -> showInfoDialog(media)
            CTX_GO_TO_FOLDER -> showParentFolder(media)
            CTX_SHARE -> lifecycleScope.launch { (activity as? AppCompatActivity)?.share(media) }
            CTX_FAV_ADD, CTX_FAV_REMOVE -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { media.isFavorite = option == CTX_FAV_ADD }
                refresh()
            }
            CTX_MARK_AS_PLAYED, CTX_MARK_AS_UNPLAYED -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { media.playCount = if (option == CTX_MARK_AS_PLAYED) 1L else 0L }
                refresh()
            }
            else -> {}
        }
    }

    private fun openSecondary(fragment: String) {
        val i = Intent(requireActivity(), SecondaryActivity::class.java)
        i.putExtra("fragment", fragment)
        requireActivity().startActivityForResult(i, SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
    }

    private fun selectTab(id: Int) {
        (activity as? MainActivity)?.navigationView?.firstOrNull { it.visibility == View.VISIBLE }?.selectedItemId = id
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
    override fun onDestroyActionMode(mode: ActionMode?) {}
}

/*
 * VLC Redesign: the Subtitles tab of the player's panel, the desktop's SubtitleTracksPanel: what the online search
 * found ("Film Title (2022) · 5 found", a busy dot while it runs) with the title search, refresh, settings and
 * add buttons; every service asked and what it found; the title search inside the panel; the languages on the left
 * (Off; each with its own name, "Default", its count; the accent bar on the one shown) and the subtitles of that
 * language on the right: the video's tracks (a check on the one shown, where it comes from), then those found
 * online (the service, its downloads, HI, "For this file"). A language or a subtitle picked shows at once; one found
 * online is downloaded and added first. A live stream or a channel shows its own tracks only.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.gui.video.RdSubtitleFinder
import org.videolan.vlc.gui.video.RdSubtitleFinder.State
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.RdCompat
import org.videolan.vlc.util.RdSubtitleChoices
import org.videolan.vlc.util.RdSubtitleChoices.Kind
import org.videolan.vlc.util.RdSubtitleChoices.Variant
import java.util.Locale

class RdSubtitlesPanel(
    private val root: View,
    private val activity: VideoPlayerActivity,
    /** a track chosen ("-1": off), as the panel's other lists choose theirs */
    private val selectTrack: (String) -> Unit,
    /** the add button: a subtitle file picked for the video */
    private val pickFile: () -> Unit,
    /** the settings button: Settings › Subtitles › Subtitle services */
    private val openServices: () -> Unit
) {
    private val ctx: Context = root.context
    private val finder: RdSubtitleFinder = activity.rdSubtitleFinder
    private val status: TextView = root.findViewById(R.id.rd_subs_status)
    private val busy: View = root.findViewById(R.id.rd_subs_busy)
    private val busyDot: View = root.findViewById(R.id.rd_subs_busy_dot)
    private val searchButton: ImageView = root.findViewById(R.id.rd_subs_search)
    private val refreshButton: ImageView = root.findViewById(R.id.rd_subs_refresh)
    private val settingsButton: ImageView = root.findViewById(R.id.rd_subs_settings)
    private val addButton: ImageView = root.findViewById(R.id.rd_subs_add)
    private val services: TextView = root.findViewById(R.id.rd_subs_services)
    private val why: TextView = root.findViewById(R.id.rd_subs_why)
    private val titleBox: View = root.findViewById(R.id.rd_subs_title_box)
    private val titleField: EditText = root.findViewById(R.id.rd_subs_title_field)
    private val titleBusy: TextView = root.findViewById(R.id.rd_subs_title_busy)
    private val titleRows: LinearLayout = root.findViewById(R.id.rd_subs_title_rows)
    private val languageList: LinearLayout = root.findViewById(R.id.rd_subs_languages)
    private val variantList: LinearLayout = root.findViewById(R.id.rd_subs_variants)
    private val variantScroll: NestedScrollView = root.findViewById(R.id.rd_subs_variant_scroll)
    private val empty: TextView = root.findViewById(R.id.rd_subs_empty)

    /** a film or a video: files, online search and timing; a live stream or a channel: its own tracks only */
    var fileFeatures = true
        set(value) {
            field = value
            refresh()
        }

    // the language chosen on the left (null: the one of the track shown)
    private var chosen: String? = null
    private var downloadError: String? = null
    private val clearError = Runnable { downloadError = null; refresh() }
    private var languagesKey = ""
    private var variantsKey = ""
    private var titlesKey = ""
    private var pulse: ValueAnimator? = null
    private val listener: () -> Unit = { refresh() }
    private var started = false
    private val accent = ctx.rdAccent()

    // the tracks change a moment after a choice, and as files are added: looked at while the panel shows
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            root.postDelayed(this, 700L)
        }
    }

    init {
        busyDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        for (b in listOf(searchButton, refreshButton, settingsButton, addButton)) {
            b.background = headerButtonBackground()
            b.setColorFilter(ContextCompat.getColor(ctx, R.color.rd_dark_fg_a2))
        }
        searchButton.setOnClickListener {
            val show = !titleBox.isVisible
            titleBox.isVisible = show
            if (show) {
                if (titleField.text.isEmpty()) titleField.setText(finder.outcome?.media?.title.orEmpty())
                titleField.requestFocus()
                titleField.setSelection(titleField.text.length)
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(titleField, 0)
            }
        }
        refreshButton.setOnClickListener { if (!finder.busy()) finder.search() }
        settingsButton.setOnClickListener { openServices() }
        addButton.setOnClickListener { pickFile() }
        titleField.setOnEditorActionListener { _, action, event ->
            // (a keyboard's Enter comes down and up: one search, not two)
            if (action == EditorInfo.IME_ACTION_SEARCH && event == null || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event == null || event.action == KeyEvent.ACTION_UP) finder.findTitle(titleField.text.toString())
                true
            } else false
        }
        titleField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP) {
                titleBox.isVisible = false
                searchButton.requestFocus()
                true
            } else false
        }
    }

    /** the panel shows: the search starts for a video without results when the user lets it run by itself */
    fun start() {
        if (started) return
        started = true
        finder.addListener(listener)
        if (fileFeatures && finder.current() == State.IDLE && finder.searchesByItself()) finder.search()
        refresh()
        root.postDelayed(tick, 700L)
    }

    fun stop() {
        started = false
        finder.removeListener(listener)
        root.removeCallbacks(tick)
        root.removeCallbacks(clearError)
        pulse?.cancel()
        pulse = null
    }

    private fun languageName(code: String): String {
        if (code.isEmpty()) return ctx.getString(R.string.rd_os_unknown_language)
        val ui = RdCompat.primaryLocale(ctx.resources.configuration)
        val name = Locale.forLanguageTag(if (code == "he") "iw" else code).getDisplayName(ui).ifEmpty { code }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ui) else it.toString() }
    }

    private fun onlineOf(ranked: OnlineSubtitles.Ranked) = RdSubtitleChoices.Online(
        ranked.result.id, ranked.result.release.ifEmpty { ctx.getString(R.string.subtitles) }, ranked.result.language,
        OnlineSubtitles.provider(ranked.result.provider)?.name ?: ranked.result.provider, ranked.result.downloads,
        ranked.result.exact, ranked.hearingImpaired, ranked.result.foreignOnly)

    fun refresh() {
        if (!started) return
        val state = finder.current()
        val outcome = finder.outcome
        val locals = activity.rdSubtitleLocals()
        val loaded = activity.rdLoadedSubtitleIds()
        val online = if (fileFeatures) outcome?.results.orEmpty().filter { it.result.id !in loaded }.map { onlineOf(it) } else emptyList()
        val settings = Settings.getInstance(ctx)
        val wanted = listOf(OnlineSubtitles.preferredLanguage(ctx), OnlineSubtitles.normalize(settings.getString(OnlineSubtitles.KEY_SECOND, null)))
        val languages = RdSubtitleChoices.languages(locals, online, wanted, OnlineSubtitles.pickedLanguages(ctx), ::languageName)
        val shown = RdSubtitleChoices.shownLanguage(chosen, languages, locals)
        val anyChecked = locals.any { it.checked }
        showHeader(state, outcome)
        showTitleSearch()
        showLanguages(languages, shown, anyChecked)
        val variants = RdSubtitleChoices.variants(shown, locals, online)
        showVariants(variants)
        empty.text = when {
            variants.isNotEmpty() -> ""
            finder.busy() -> ctx.getString(R.string.rd_os_looking)
            anyChecked || languages.isNotEmpty() -> ""
            else -> ctx.getString(R.string.rd_subs_none_yet)
        }
        empty.isVisible = empty.text.isNotEmpty()
    }

    private fun showHeader(state: State, outcome: OnlineSubtitles.Outcome?) {
        val searching = state == State.IDENTIFYING || state == State.SEARCHING || finder.loadingId != null
        busy.isVisible = searching && fileFeatures
        if (busy.isVisible && pulse == null) pulse = ObjectAnimator.ofFloat(busyDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        } else if (!busy.isVisible) {
            pulse?.cancel()
            pulse = null
        }
        for (b in listOf(searchButton, refreshButton, settingsButton, addButton)) b.isVisible = fileFeatures
        // (dimmed while it searches, not disabled: a remote's focus on it stays there)
        refreshButton.alpha = if (state != State.IDENTIFYING && state != State.SEARCHING) 1f else 0.4f
        refreshButton.contentDescription = ctx.getString(if (state == State.IDLE) R.string.rd_os_search else R.string.rd_os_search_again)
        val error = downloadError
        status.text = when {
            error != null -> error
            !fileFeatures -> ctx.getString(R.string.subtitles)
            state == State.IDENTIFYING -> ctx.getString(R.string.rd_os_identifying)
            state == State.SEARCHING -> ctx.getString(R.string.rd_os_searching)
            state == State.READY && finder.loadingId != null -> ctx.getString(R.string.rd_os_downloading)
            state == State.READY && outcome != null ->
                ctx.getString(R.string.rd_os_found, outcome.matchTitle.ifEmpty { outcome.media.title }, outcome.results.size.toString())
            (state == State.NOT_FOUND || state == State.FAILED) && outcome != null -> when {
                !outcome.anyAnswered -> RdOnlineSubtitlesSheet.problemText(ctx, finder.problem ?: OnlineSubtitles.Problem.Offline)
                outcome.match == null && outcome.media.title.isEmpty() -> ctx.getString(R.string.rd_os_err_unidentified)
                outcome.match == null -> ctx.getString(R.string.rd_os_err_not_found_title, outcome.media.title)
                else -> ctx.getString(R.string.rd_os_err_none_for, outcome.matchTitle)
            }
            else -> ctx.getString(R.string.rd_subs_of_video)
        }
        status.setTextColor(when {
            error != null -> accent
            else -> ContextCompat.getColor(ctx, R.color.rd_dark_fg_a3)
        })
        // every service asked, what each found; a dash for one that didn't answer, and why under the line
        val asked = outcome?.asked.orEmpty()
        services.text = asked.joinToString("  ·  ") { if (it.failed) "${it.name} –" else "${it.name} ${it.count}" }
        services.isVisible = fileFeatures && asked.isNotEmpty() && (state == State.READY || state == State.NOT_FOUND)
        val reasons = asked.mapNotNull { a -> a.problem?.let { RdOnlineSubtitlesSheet.problemText(ctx, it) } }.distinct()
        why.text = reasons.joinToString("\n")
        why.isVisible = services.isVisible && reasons.isNotEmpty()
    }

    private fun showTitleSearch() {
        if (!fileFeatures) titleBox.isVisible = false
        titleBusy.text = when {
            finder.titleBusy -> ctx.getString(R.string.rd_os_searching)
            finder.titleNotFound != null -> ctx.getString(R.string.rd_os_err_not_found_title, finder.titleNotFound)
            else -> ""
        }
        titleBusy.isVisible = titleBusy.text.isNotEmpty()
        val titles = finder.titleResults
        val key = titles.joinToString("|") { it.first.imdb + it.first.title }
        if (key == titlesKey) return
        titlesKey = key
        titleRows.removeAllViews()
        titles.forEachIndexed { i, (match, detail) ->
            val row = Row(ctx, accent)
            row.title.text = if (match.year > 0) "${match.title} (${match.year})" else match.title
            row.setDetail(detail)
            row.setOnClickListener {
                titleBox.isVisible = false
                titleField.clearFocus()
                finder.useTitle(i)
            }
            titleRows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)))
        }
        if (titles.isNotEmpty() && !root.isInTouchMode) titleRows.getChildAt(0)?.requestFocus()
    }

    private fun showLanguages(languages: List<RdSubtitleChoices.Language>, shown: String?, anyChecked: Boolean) {
        val key = languages.joinToString("|") { "${it.code}:${it.count}:${it.wanted}" } + "/" + shown + "/" + anyChecked
        if (key == languagesKey) return
        languagesKey = key
        val focused = languageList.findFocus()?.let { f -> (0 until languageList.childCount).map { languageList.getChildAt(it) }.firstOrNull { it == f } }?.tag
        languageList.removeAllViews()
        val off = Row(ctx, accent)
        off.tag = OFF
        off.title.text = ctx.getString(R.string.rd_off)
        off.isActivated = !anyChecked
        off.setOnClickListener {
            chosen = null
            selectTrack("-1")
            root.postDelayed({ refresh() }, 250L)
        }
        columnKeys(off, languages = true)
        languageList.addView(off, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)))
        for (l in languages) {
            val row = Row(ctx, accent)
            row.tag = l.code
            val name = languageName(l.code)
            val native = if (l.code.isEmpty()) "" else OnlineSubtitles.nativeName(l.code)
            row.title.text = name
            val detail = if (native.isNotEmpty() && !native.equals(name, ignoreCase = true)) native else ""
            val marker = when (l.wanted) {
                0 -> ctx.getString(R.string.rd_default_value)
                1 -> ctx.getString(R.string.rd_os_second_short)
                else -> ""
            }
            // (as the desktop writes it: "עברית · Default", the language's own words read first)
            row.setDetail(if (marker.isNotEmpty()) (if (detail.isNotEmpty()) "$detail · $marker" else marker) else detail)
            row.setCount(l.count)
            row.isActivated = l.code == shown
            row.setOnClickListener { chooseLanguage(l.code) }
            columnKeys(row, languages = true)
            languageList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)))
        }
        focused?.let { tag -> (0 until languageList.childCount).map { languageList.getChildAt(it) }.firstOrNull { it.tag == tag }?.requestFocus() }
    }

    private fun showVariants(variants: List<Variant>) {
        val key = variants.joinToString("|") {
            when (it) {
                is Variant.Track -> "t${it.local.id}:${it.local.checked}:${it.local.source}"
                is Variant.Result -> "o${it.online.id}:${finder.loadingId == it.online.id}"
            }
        }
        if (key == variantsKey) return
        val sameLanguage = variantsKey.isNotEmpty()
        variantsKey = key
        val focused = variantList.findFocus()?.let { f -> (0 until variantList.childCount).map { variantList.getChildAt(it) }.firstOrNull { it == f } }?.tag
        variantList.removeAllViews()
        for (v in variants) {
            val row = Row(ctx, accent)
            when (v) {
                is Variant.Track -> {
                    row.tag = "t" + v.local.id
                    row.title.text = v.local.title
                    row.setDetail(sourceText(v.local.source))
                    row.setChecked(v.local.checked)
                    row.setOnClickListener {
                        chosen = v.local.language
                        selectTrack(v.local.id)
                        root.postDelayed({ refresh() }, 250L)
                    }
                }
                is Variant.Result -> {
                    val r = v.online
                    row.tag = "o" + r.id
                    row.title.text = r.release
                    val downloads = RdSubtitleChoices.downloadsCount(r.downloads)?.let { (n, k) ->
                        ctx.getString(if (k) R.string.rd_os_downloads_k else R.string.rd_os_downloads, n)
                    }
                    row.setDetail(listOfNotNull(r.provider, downloads, if (r.foreignOnly) ctx.getString(R.string.rd_os_foreign) else null).joinToString(" · "))
                    row.setBusy(finder.loadingId == r.id)
                    row.setHearingImpaired(r.hearingImpaired)
                    if (r.exact) row.setBadge(ctx.getString(R.string.rd_os_for_file))
                    row.setOnClickListener { load(r) }
                }
            }
            columnKeys(row, languages = false)
            variantList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)))
        }
        if (!sameLanguage) variantScroll.scrollTo(0, 0)
        focused?.let { tag -> (0 until variantList.childCount).map { variantList.getChildAt(it) }.firstOrNull { it.tag == tag }?.requestFocus() }
    }

    /**
     * The remote between the columns, as the desktop's keys: towards the other column, its row shown (the language
     * shown, the subtitle shown) else its first; towards the panel's edge, nothing (the focus stays in the columns);
     * down from a column's last row, what is under the columns.
     */
    private fun columnKeys(row: View, languages: Boolean) {
        row.setOnKeyListener { v, keyCode, event ->
            // (down from a column's last row: what is under the columns, not the other column's next row)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val list = v.parent as? ViewGroup ?: return@setOnKeyListener false
                if (list.indexOfChild(v) != list.childCount - 1) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val columns = root.findViewById<View>(R.id.rd_subs_columns)
                    val top = v.rootView as? ViewGroup ?: return@setOnKeyListener false
                    val r = android.graphics.Rect()
                    columns.getDrawingRect(r)
                    top.offsetDescendantRectToMyCoords(columns, r)
                    r.top = r.bottom - 1
                    android.view.FocusFinder.getInstance().findNextFocusFromRect(top, r, View.FOCUS_DOWN)?.requestFocus(View.FOCUS_DOWN)
                }
                return@setOnKeyListener true
            }
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            // (the languages are at the start: the left, the right in a right to left layout)
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val towardsEnd = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl
            if (languages == towardsEnd) {
                val other = if (languages) variantList else languageList
                val rows = (0 until other.childCount).map { other.getChildAt(it) }
                (rows.firstOrNull { it.isActivated || (it as? Row)?.isChecked() == true } ?: rows.firstOrNull())?.requestFocus()
            }
            true
        }
    }

    private fun sourceText(source: RdSubtitleChoices.Source): String = when (source.kind) {
        Kind.VIDEO -> ctx.getString(R.string.rd_subs_in_video)
        Kind.FILE -> if (source.name.isNotEmpty()) ctx.getString(R.string.rd_subs_next_to, source.name) else ctx.getString(R.string.rd_subs_next_to_plain)
        Kind.ONLINE -> if (source.provider.isNotEmpty()) ctx.getString(R.string.rd_os_downloaded_from, source.provider) else ctx.getString(R.string.downloaded)
        Kind.OPENED -> if (source.name.isNotEmpty()) ctx.getString(R.string.rd_subs_opened, source.name) else ctx.getString(R.string.rd_subs_opened_plain)
    }

    /** a language picked: its first track of the video, else its first found online */
    private fun chooseLanguage(code: String) {
        chosen = code
        OnlineSubtitles.learnLanguage(ctx.applicationContext, code)
        val locals = activity.rdSubtitleLocals()
        val loaded = activity.rdLoadedSubtitleIds()
        val online = finder.outcome?.results.orEmpty().filter { it.result.id !in loaded }.map { onlineOf(it) }
        when (val best = RdSubtitleChoices.bestOf(code, locals, if (fileFeatures) online else emptyList())) {
            is Variant.Track -> if (!best.local.checked) selectTrack(best.local.id)
            is Variant.Result -> load(best.online)
            null -> {}
        }
        refresh()
        root.postDelayed({ refresh() }, 250L)
    }

    private fun load(r: RdSubtitleChoices.Online) {
        if (finder.loadingId != null) return
        val result = finder.outcome?.results?.firstOrNull { it.result.id == r.id }?.result ?: return
        chosen = r.language
        OnlineSubtitles.learnLanguage(ctx.applicationContext, r.language)
        finder.load(result) { problem ->
            if (problem != null) {
                downloadError = RdOnlineSubtitlesSheet.problemText(ctx, problem)
                root.removeCallbacks(clearError)
                root.postDelayed(clearError, 6000L)
            }
            refresh()
        }
    }

    private fun headerButtonBackground() = StateListDrawable().apply {
        val hover = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(ContextCompat.getColor(ctx, R.color.rd_dark_bg_a1)) }
        addState(intArrayOf(android.R.attr.state_pressed), hover)
        addState(intArrayOf(android.R.attr.state_focused), hover)
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * A row of the lists (the desktop's RowButton): a check for the subtitle shown (or the busy dot of the one being
     * downloaded), the name and under it where it comes from, HI, a badge, the count of a language; the row shown
     * (activated) is lighter with the accent bar at its start.
     */
    class Row(context: Context, private val accent: Int) : LinearLayout(context) {
        private val density = context.resources.displayMetrics.density
        private fun px(v: Float) = v * density
        private fun dpi(v: Int) = (v * density + 0.5f).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val tv = Settings.showTvUi
        private val slot = FrameLayout(context)
        private val check = ImageView(context)
        private var busyDot: View? = null
        private val texts = LinearLayout(context)
        val title = TextView(context)
        private val detail = TextView(context)
        private val hi = TextView(context)
        private val badge = TextView(context)
        private val count = TextView(context)
        private var checked = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setWillNotDraw(false)
            val inter = ResourcesCompat.getFont(context, R.font.inter)
            check.setImageResource(R.drawable.rd_ic_track_check_active)
            check.scaleType = ImageView.ScaleType.FIT_CENTER
            check.visibility = View.INVISIBLE
            slot.addView(check, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(slot, LayoutParams(dpi(20), dpi(20)).apply { marginStart = dpi(6) })
            texts.orientation = VERTICAL
            for ((t, size, color) in listOf(Triple(title, 14f, R.color.rd_dark_fg_a1), Triple(detail, 11f, R.color.rd_dark_fg_a3))) {
                t.typeface = inter
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                t.setTextColor(ContextCompat.getColor(context, color))
                t.isSingleLine = true
                t.ellipsize = TextUtils.TruncateAt.END
                t.includeFontPadding = false
                t.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                texts.addView(t, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            detail.setPadding(0, dpi(1), 0, 0)
            detail.visibility = View.GONE
            addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(6) })
            hi.text = context.getString(R.string.rd_subs_hi_short)
            hi.contentDescription = context.getString(R.string.rd_os_hi)
            hi.typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            hi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            hi.setTextColor(ContextCompat.getColor(context, R.color.rd_dark_fg_a2))
            hi.visibility = View.GONE
            addView(hi, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(6) })
            badge.typeface = inter
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            badge.setTextColor(accent)
            badge.gravity = Gravity.CENTER
            badge.setPadding(dpi(5), 0, dpi(5), 0)
            badge.includeFontPadding = false
            badge.background = GradientDrawable().apply {
                cornerRadius = px(4f)
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, (0.18f * 255).toInt()))
            }
            badge.visibility = View.GONE
            addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(18)).apply { marginStart = dpi(6); marginEnd = dpi(4) })
            count.typeface = inter
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            count.setTextColor(ContextCompat.getColor(context, R.color.rd_dark_fg_a3))
            count.visibility = View.GONE
            addView(count, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(6); marginEnd = dpi(8) })
            updateColors()
        }

        fun setDetail(text: String) {
            detail.text = text
            detail.isVisible = text.isNotEmpty()
        }

        fun setCount(n: Int) {
            count.text = if (n > 0) String.format(Locale.getDefault(), "%d", n) else ""
            count.isVisible = n > 0
        }

        fun setChecked(on: Boolean) {
            checked = on
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
            updateColors()
        }

        fun isChecked() = checked

        fun setHearingImpaired(on: Boolean) { hi.isVisible = on }

        fun setBadge(text: String) {
            badge.text = text
            badge.isVisible = text.isNotEmpty()
        }

        fun setBusy(on: Boolean) {
            if (on && busyDot == null) {
                val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }
                slot.addView(dot, FrameLayout.LayoutParams(dpi(8), dpi(8), Gravity.CENTER))
                ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                    duration = 600L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
                busyDot = dot
            }
            busyDot?.isVisible = on
        }

        override fun setActivated(activated: Boolean) {
            super.setActivated(activated)
            updateColors()
        }

        private fun updateColors() {
            title.setTextColor(ContextCompat.getColor(context, if (isActivated || checked) R.color.rd_dark_fg_1 else R.color.rd_dark_fg_a1))
        }

        // (read out as one: the name, where it comes from, HI, the badge, the count)
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.contentDescription = listOf(title.text, detail.text, if (hi.isVisible) hi.contentDescription else "", badge.text, count.text)
                .filter { !it.isNullOrEmpty() }.joinToString(", ")
            info.isCheckable = true
            info.isChecked = checked || isActivated
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val fill = when {
                isActivated -> ContextCompat.getColor(context, R.color.rd_dark_bg_a3)
                isPressed || isFocused -> ContextCompat.getColor(context, R.color.rd_dark_bg_a5)
                else -> Color.TRANSPARENT
            }
            val r = px(4f)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            if (fill != Color.TRANSPARENT) {
                paint.style = Paint.Style.FILL
                paint.color = fill
                canvas.drawRoundRect(rect, r, r, paint)
            }
            // the keyboard's focus (the TV draws its own ring around the row)
            if (isFocused && !tv && !isInTouchMode) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = px(1f)
                paint.color = accent
                rect.inset(px(0.5f), px(0.5f))
                canvas.drawRoundRect(rect, r, r, paint)
            }
            if (isActivated) {
                // (at the start of the row: the right in a right to left one)
                val w = px(3f)
                val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - w else 0f
                rect.set(left, px(6f), left + w, height - px(6f))
                paint.style = Paint.Style.FILL
                paint.color = accent
                canvas.drawRoundRect(rect, w / 2, w / 2, paint)
            }
            super.onDraw(canvas)
        }
    }

    companion object {
        private const val ROW_HEIGHT = 36
        private const val OFF = "\u0002off"
    }
}

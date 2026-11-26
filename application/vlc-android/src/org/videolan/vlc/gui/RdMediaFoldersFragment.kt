/*****************************************************************************
 * RdMediaFoldersFragment.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *****************************************************************************/
package org.videolan.vlc.gui

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.util.getFromMl
import org.videolan.vlc.R
import org.videolan.tools.Settings
import org.videolan.vlc.gui.helpers.RdStyle
import org.videolan.vlc.gui.helpers.RdTvFocusRing
import org.videolan.vlc.gui.helpers.loadImage
import org.videolan.vlc.media.MediaUtils

/**
 * The videos or the songs of the library by folder (discussion #132): at each level the folders that hold media of
 * that kind somewhere below (DCIM for its Camera and Screen Recordings), then the media of the folder itself. Built
 * from the library's folders of media (a flat list), so folders without any media never show.
 */
class RdMediaFoldersFragment : Fragment() {

    companion object {
        private const val KEY_TYPE = "rd_folders_type"
        private const val KEY_PATH = "rd_folders_path"
        /** a folder's media are asked page by page, up to what Play all takes */
        private const val PAGE = 500
        private const val MAX_MEDIA = 10000

        fun newInstance(type: Int) = RdMediaFoldersFragment().apply {
            arguments = Bundle().apply { putInt(KEY_TYPE, type) }
        }
    }

    private sealed class Row {
        class Up(val title: String) : Row()
        class Dir(val path: String, val name: String, val count: Int) : Row()
        class Media(val media: MediaWrapper, val index: Int) : Row()
    }

    private val type by lazy { arguments?.getInt(KEY_TYPE, Folder.TYPE_FOLDER_VIDEO) ?: Folder.TYPE_FOLDER_VIDEO }
    private val video get() = type == Folder.TYPE_FOLDER_VIDEO
    // the folder shown (an mrl ending with '/'), null: the top
    private var path: String? = null
    private var rows: List<Row> = emptyList()
    private var media: List<MediaWrapper> = emptyList()
    private var loadJob: Job? = null
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    // (discussion #132) Play all of the folder shown, its subfolders too when asked; the order of its media
    private lateinit var bar: LinearLayout
    private lateinit var subfolders: SwitchCompat
    private class SortOption(val sort: Int, val desc: Boolean, val label: String)
    private val sortKey get() = "rd_folders_sort_$type"
    private val subKey get() = "rd_folders_subfolders_$type"
    private fun sortOptions(): List<SortOption> {
        val name = getString(R.string.sortby_name)
        // (the names of Display settings' order)
        val added = getString(R.string.sortby_last_modified_date)
        val modified = getString(R.string.rd_modified)
        val length = getString(R.string.sortby_length)
        return listOf(
            SortOption(Medialibrary.SORT_FILENAME, false, "$name: ${getString(R.string.sort_alpha_asc)}"),
            SortOption(Medialibrary.SORT_FILENAME, true, "$name: ${getString(R.string.sort_alpha_desc)}"),
            SortOption(Medialibrary.SORT_INSERTIONDATE, true, "$added: ${getString(R.string.sort_date_desc)}"),
            SortOption(Medialibrary.SORT_INSERTIONDATE, false, "$added: ${getString(R.string.sort_date_asc)}"),
            SortOption(Medialibrary.SORT_LASTMODIFICATIONDATE, true, "$modified: ${getString(R.string.sort_date_desc)}"),
            SortOption(Medialibrary.SORT_LASTMODIFICATIONDATE, false, "$modified: ${getString(R.string.sort_date_asc)}"),
            SortOption(Medialibrary.SORT_DURATION, true, "$length: ${getString(R.string.sortby_length_desc)}"),
            SortOption(Medialibrary.SORT_DURATION, false, "$length: ${getString(R.string.sortby_length_asc)}"))
    }
    private fun sortIndex() = Settings.getInstance(requireContext()).getInt(sortKey, 0).coerceIn(0, 7)
    private fun sortOption() = sortOptions()[sortIndex()]

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            goUp()
        }
    }

    private fun d(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        path = savedInstanceState?.getString(KEY_PATH)
        val ctx = requireContext()
        val root = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = RowAdapter()
            clipToPadding = false
            setPadding(0, d(8f), 0, d(88f))
        }
        empty = TextView(ctx).apply {
            text = getString(R.string.nomedia)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a1))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setPadding(d(32f), 0, d(32f), 0)
            visibility = View.GONE
        }
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // above the list: Play all, its subfolders too or not, the order
        val font = ResourcesCompat.getFont(ctx, R.font.inter)
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(d(16f), d(10f), d(8f), d(2f))
            visibility = View.GONE
        }
        val play = TextView(ctx).apply {
            text = getString(R.string.play_all)
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_play_all)?.mutate()
            icon?.setTint(ContextCompat.getColor(ctx, android.R.color.white))
            icon?.setBounds(0, 0, d(18f), d(18f))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = d(6f)
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(d(12f), d(7f), d(14f), d(7f))
            background = GradientDrawable().apply {
                cornerRadius = d(6f).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.rd_accent))
            }
            isFocusable = true
            setOnClickListener { playAll() }
        }
        subfolders = SwitchCompat(ctx).apply {
            text = getString(R.string.rd_include_subfolders)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a1))
            maxLines = 2
            RdStyle.toggle(this)
            isChecked = Settings.getInstance(ctx).getBoolean(subKey, false)
            setOnCheckedChangeListener { _, on -> Settings.getInstance(ctx).edit().putBoolean(subKey, on).apply() }
        }
        val sort = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_sort)
            setColorFilter(ContextCompat.getColor(ctx, R.color.rd_fg_1))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.sortby)
            val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0)
            attrs.recycle()
            isFocusable = true
            setOnClickListener { chooseSort() }
        }
        bar.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(subfolders, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = d(16f) })
        bar.addView(sort, LinearLayout.LayoutParams(d(44f), d(44f)).apply { marginStart = d(8f) })
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun chooseSort() {
        val ctx = context ?: return
        val options = sortOptions()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sortby)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), sortIndex()) { dialog, which ->
                Settings.getInstance(ctx).edit().putInt(sortKey, which).apply()
                dialog.dismiss()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        RdTvFocusRing.attach(dialog.window)
    }

    /** The media of the folder shown (its subfolders' too when asked; all of them at the top), in the chosen order */
    private fun playAll() {
        val ctx = context ?: return
        val here = path ?: roots.singleOrNull()
        val withSub = here == null || subfolders.isChecked
        val opt = sortOption()
        val tops = roots
        viewLifecycleOwner.lifecycleScope.launch {
            val all = ctx.getFromMl {
                getFolders(type, Medialibrary.SORT_DEFAULT, false, false, false, 100000, 0)
                    .filter { !it.mMrl.isNullOrEmpty() }
                    .associateBy { if (it.mMrl.endsWith("/")) it.mMrl else it.mMrl + "/" }
                    .filterKeys { p -> if (here == null) tops.any { p.startsWith(it) } else if (withSub) p.startsWith(here) else p == here }
                    // (folder by folder, each one's media in the chosen order: the folder itself first)
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER.thenBy { it })
                    .flatMap { (_, f) -> mediaOf(f, opt.sort, opt.desc) }
            }
            if (all.isNotEmpty()) MediaUtils.openList(ctx, all.take(10000), 0)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        // (the music pager keeps every page laid out: Back goes up only while this page is the one on screen)
        view.addOnAttachStateChangeListener(watch)
        if (view.isAttachedToWindow) watch.onViewAttachedToWindow(view)
        load()
    }

    // (the window's observer outlives this page: the two listeners come off when the view leaves the window; left
    // there, every visit to Folders kept its page and its list in memory and ran once more on each layout)
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { updateBack() }
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { updateBack() }
    private val watch = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
            v.viewTreeObserver.addOnScrollChangedListener(onScroll)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
            v.viewTreeObserver.removeOnScrollChangedListener(onScroll)
        }
    }

    /** every media of [f] of this page's kind, in the chosen order (the library hands them out page by page) */
    private fun mediaOf(f: Folder, sort: Int, desc: Boolean): List<MediaWrapper> {
        val out = ArrayList<MediaWrapper>()
        while (out.size < MAX_MEDIA) {
            val page = f.media(type, sort, desc, false, false, PAGE, out.size) ?: break
            out.addAll(page)
            if (page.size < PAGE) break
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        updateBack()
        // the library may have changed meanwhile (a scan)
        load()
    }

    override fun onPause() {
        super.onPause()
        back.isEnabled = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PATH, path)
    }

    private fun onScreen(): Boolean {
        val v = view ?: return false
        if (!isResumed || !v.isShown) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.width() > v.width / 2
    }

    private fun updateBack() {
        back.isEnabled = path != null && onScreen()
    }

    /** Back: the folder above (true), or already at the top (false) */
    fun goUp(): Boolean {
        val p = path ?: return false
        path = parentOf(p)
        load()
        return true
    }

    private var roots: List<String> = emptyList()
    /** the library has answered once: [roots] is what it holds, not the empty list of the start */
    private var loaded = false

    private fun parentOf(p: String): String? {
        if (roots.any { it == p }) return null
        // (a folder no top holds any more, its drive taken out or its last file gone: the top is next)
        if (loaded && roots.none { p.startsWith(it) }) return null
        val trimmed = p.removeSuffix("/")
        val parent = trimmed.substring(0, trimmed.lastIndexOf('/') + 1)
        // above the folder the top shows: the top
        if (roots.size == 1 && parent.length <= roots[0].length) return null
        return parent
    }

    private fun nameOf(p: String) = Uri.decode(p.removeSuffix("/").substringAfterLast('/'))

    private fun load() {
        loadJob?.cancel()
        val ctx = context ?: return
        val shown = path
        val opt = sortOption()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = ctx.getFromMl {
                val folders = getFolders(type, Medialibrary.SORT_DEFAULT, false, false, false, 100000, 0)
                    .filter { !it.mMrl.isNullOrEmpty() }
                val byPath = folders.associateBy { if (it.mMrl.endsWith("/")) it.mMrl else it.mMrl + "/" }
                val tops = (foldersList ?: emptyArray()).map { if (it.endsWith("/")) it else "$it/" }
                    .filter { top -> byPath.keys.any { it.startsWith(top) } }
                // the place shown: the one top itself when there is a single one
                val here = shown ?: tops.singleOrNull()
                val dirs = HashMap<String, Int>()
                if (here == null) {
                    for (top in tops) dirs[top] = byPath.filterKeys { it.startsWith(top) }.values.sumOf { it.mediaCount(type) }
                } else {
                    for ((p, f) in byPath) {
                        if (p == here || !p.startsWith(here)) continue
                        val child = here + p.removePrefix(here).substringBefore('/') + "/"
                        dirs[child] = (dirs[child] ?: 0) + f.mediaCount(type)
                    }
                }
                val own = here?.let { byPath[it] }?.let { mediaOf(it, opt.sort, opt.desc) } ?: emptyList()
                Triple(tops, dirs, own)
            }
            if (path != shown) return@launch
            roots = result.first
            loaded = true
            media = result.third
            val out = ArrayList<Row>()
            if (path != null) out.add(Row.Up(nameOf(path!!)))
            // (the folders by name; Z → A turns them round too)
            val byName = compareBy<Map.Entry<String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { nameOf(it.key) }
            result.second.entries
                .filter { it.value > 0 }
                .sortedWith(if (opt.sort == Medialibrary.SORT_FILENAME && opt.desc) byName.reversed() else byName)
                .forEach { out.add(Row.Dir(it.key, if (path == null && roots.size > 1) storageName(it.key) else nameOf(it.key), it.value)) }
            media.forEachIndexed { i, m -> out.add(Row.Media(m, i)) }
            rows = out
            list.adapter?.notifyDataSetChanged()
            list.scrollToPosition(0)
            empty.visibility = if (out.none { it !is Row.Up }) View.VISIBLE else View.GONE
            bar.visibility = if (out.any { it !is Row.Up }) View.VISIBLE else View.GONE
            updateBack()
        }
    }

    private fun storageName(p: String) =
        if (p.contains("/emulated/")) getString(R.string.internal_memory) else nameOf(p)

    private fun open(row: Row) {
        when (row) {
            is Row.Up -> goUp()
            is Row.Dir -> {
                path = row.path
                load()
            }
            is Row.Media -> MediaUtils.openList(requireContext(), media, row.index)
        }
    }

    private inner class RowHolder(val box: LinearLayout, val icon: ImageView, val title: TextView, val sub: TextView) :
        RecyclerView.ViewHolder(box)

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val ctx = parent.context
            val font = ResourcesCompat.getFont(ctx, R.font.inter)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = d(56f)
                setPadding(d(16f), d(6f), d(16f), d(6f))
                isFocusable = true
                isClickable = true
                val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attrs.getDrawable(0)
                attrs.recycle()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(icon, LinearLayout.LayoutParams(d(40f), d(40f)).apply { marginEnd = d(16f) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(ctx).apply {
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_1))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val sub = TextView(ctx).apply {
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a1))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            texts.addView(title)
            texts.addView(sub)
            box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return RowHolder(box, icon, title, sub)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            val ctx = holder.box.context
            holder.icon.colorFilter = null
            holder.icon.setImageDrawable(null)
            holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            when (row) {
                is Row.Up -> {
                    holder.icon.setImageResource(R.drawable.ic_arrow_back)
                    holder.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.rd_fg_1))
                    holder.icon.scaleY = 1f
                    // (the arrow points the other way in a right to left layout)
                    holder.icon.scaleX = if (holder.box.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
                    holder.title.text = row.title
                    holder.sub.text = getString(R.string.rd_up_one_folder)
                    holder.sub.visibility = View.VISIBLE
                }
                is Row.Dir -> {
                    holder.icon.scaleX = 1f
                    holder.icon.setImageResource(R.drawable.rd_tvd_folder)
                    holder.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.rd_fg_a1))
                    holder.title.text = row.name
                    holder.sub.text = resources.getQuantityString(
                        if (video) R.plurals.videos_quantity else R.plurals.track_quantity, row.count, row.count)
                    holder.sub.visibility = View.VISIBLE
                }
                is Row.Media -> {
                    holder.icon.scaleX = 1f
                    holder.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    loadImage(holder.icon, row.media)
                    holder.title.text = row.media.title
                    holder.sub.text = if (row.media.length > 0) Tools.millisToString(row.media.length) else ""
                    holder.sub.visibility = if (holder.sub.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            }
            holder.box.contentDescription = holder.title.text
            holder.box.setOnClickListener { rows.getOrNull(holder.bindingAdapterPosition)?.let { open(it) } }
        }
    }
}

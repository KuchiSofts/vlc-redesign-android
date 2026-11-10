/*
 * VLC Redesign, Live TV: the channels of the TV page, a row each (TvChannelRow.qml: the number, the logo, the
 * name, what is on with its progress, what is next, the archive and the favourite as small marks) or as cards
 * (the logo on the well, the name and what is on).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.livetv.KEY_IPTV_DESCRIPTIONS
import org.videolan.vlc.livetv.KEY_IPTV_NEXT
import org.videolan.vlc.livetv.KEY_IPTV_PROGRESS
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.TvChannel
import org.videolan.vlc.livetv.TvPlayback

internal class TvChannelAdapter(
    private val context: Context,
    private val onPlay: (TvChannel) -> Unit,
    private val onMenu: (TvChannel, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var grid = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }
    var items: List<TvChannel> = emptyList()
        private set
    /** the next column shows from this width (a phone in portrait has no room for it) */
    var wide = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    private val settings = Settings.getInstance(context)
    private var showNext = true
    private var showProgress = true
    private var showDescription = true

    fun submit(list: List<TvChannel>) {
        showNext = settings.getBoolean(KEY_IPTV_NEXT, true)
        showProgress = settings.getBoolean(KEY_IPTV_PROGRESS, true)
        showDescription = settings.getBoolean(KEY_IPTV_DESCRIPTIONS, true)
        items = list
        notifyDataSetChanged()
    }

    /** the clock moved: what is on and its progress again */
    fun tick() = notifyItemRangeChanged(0, items.size, TICK)

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 1) CardHolder(context) else RowHolder(context)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val c = items[position]
        when (holder) {
            is RowHolder -> holder.bind(c)
            is CardHolder -> holder.bind(c)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(TICK)) {
            val c = items[position]
            when (holder) {
                is RowHolder -> holder.bindNow(c)
                is CardHolder -> holder.bindNow(c)
            }
        } else super.onBindViewHolder(holder, position, payloads)
    }

    private fun rowBackground(ctx: Context) = RippleDrawable(ColorStateList.valueOf(ctx.tvColor(R.color.rd_ripple_dark)),
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), rounded(ctx.tvAttr(R.attr.rd_accent_bright), ctx.tvDp(6f)))
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx.tvColor(R.color.rd_bg_a5), ctx.tvDp(6f), 1, ctx.tvAccent))
            addState(intArrayOf(), rounded(0, ctx.tvDp(6f)))
        }, rounded(0xFFFFFFFF.toInt(), ctx.tvDp(6f)))

    inner class RowHolder(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
        private val row = itemView as LinearLayout
        private val number = ctx.tvText("", 14f, ctx.tvColor(R.color.rd_fg_a3)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; textAlignment = View.TEXT_ALIGNMENT_GRAVITY }
        private val logo = TvLogoView(ctx, 40f)
        private val name = ctx.tvText("", 14f, ctx.tvColor(R.color.rd_fg_a1))
        private val nowTitle = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a3))
        private val progress = TvProgress(ctx, ctx.tvColor(R.color.rd_divider_dim), ctx.tvAccent)
        private val desc = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a4))
        private val nextBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val nextTime = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a4))
        private val nextTitle = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a3))
        private val marks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        private val fav = ImageView(ctx).apply { setImageResource(R.drawable.rd_tv_favorite_on) }
        private var badge: View? = null
        private var channel: TvChannel? = null

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = ctx.tvPx(56f)
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.tvPx(2f) }
            row.background = rowBackground(ctx)
            row.isFocusable = true
            row.isClickable = true
            row.setPadding(0, ctx.tvPx(6f), 0, ctx.tvPx(6f))
            row.addView(number, LinearLayout.LayoutParams(ctx.tvPx(40f), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(logo, LinearLayout.LayoutParams(ctx.tvPx(40f), ctx.tvPx(40f)).apply { marginStart = ctx.tvPx(12f) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(name)
            val nowRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nowRow.addView(nowTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            nowRow.addView(progress, LinearLayout.LayoutParams(ctx.tvPx(60f), ctx.tvPx(3f)).apply { marginStart = ctx.tvPx(8f) })
            texts.addView(nowRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(4f) })
            texts.addView(desc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(3f) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.tvPx(12f); marginEnd = ctx.tvPx(12f) })
            nextBox.addView(nextTime)
            nextBox.addView(nextTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(4f) })
            row.addView(nextBox, LinearLayout.LayoutParams(ctx.tvPx(200f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(12f) })
            marks.addView(fav, LinearLayout.LayoutParams(ctx.tvPx(18f), ctx.tvPx(18f)).apply { marginStart = ctx.tvPx(6f) })
            row.addView(marks, LinearLayout.LayoutParams(ctx.tvPx(70f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(10f) })
            row.setOnClickListener { channel?.let(onPlay) }
            row.setOnLongClickListener { v -> channel?.let { onMenu(it, v) }; true }
        }

        fun bind(c: TvChannel) {
            val ctx = itemView.context
            channel = c
            number.text = if (c.number > 0) c.number.toString() else ""
            logo.set(c.logo, c.name)
            name.text = c.name
            badge?.let { marks.removeView(it) }
            badge = null
            if (c.catchupDays > 0) {
                badge = ctx.tvBadge(ctx.resources.getQuantityString(R.plurals.rd_tv_days_short, c.catchupDays, c.catchupDays), R.drawable.rd_ic_recent).also {
                    it.contentDescription = ctx.resources.getQuantityString(R.plurals.rd_tv_catchup_tip, c.catchupDays, c.catchupDays)
                    marks.addView(it, 0)
                }
            }
            // the same room on every row on a wide window (the next column stays in line); on a phone only the marks
            fav.visibility = if (LiveTv.isFavorite(c.key)) View.VISIBLE else if (wide) View.INVISIBLE else View.GONE
            val lp = marks.layoutParams as LinearLayout.LayoutParams
            val w = if (wide) ctx.tvPx(70f) else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.width != w) { lp.width = w; marks.layoutParams = lp }
            bindNow(c)
        }

        fun bindNow(c: TvChannel) {
            val ctx = itemView.context
            val t = System.currentTimeMillis() / 1000
            val now = c.now(t)
            val current = TvPlayback.current.value == c.key
            row.isActivated = current
            name.setTextColor(if (current) ctx.tvAccent else ctx.tvColor(R.color.rd_fg_a1))
            nowTitle.text = when {
                now != null -> TvTexts.time(ctx, now.start) + "  " + now.title
                c.hasGuide -> ""
                else -> ctx.getString(R.string.rd_tv_no_guide)
            }
            progress.visibility = if (now != null && showProgress) View.VISIBLE else View.GONE
            if (now != null) progress.fraction = (t - now.start).toFloat() / maxOf(1L, now.stop - now.start)
            fun showDesc(text: String) {
                val d = text.replace('\n', ' ').take(200)
                desc.text = d
                desc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            }
            if (showDescription && now != null && now.descLength > 0) {
                // (read off the main thread when not known yet; shown if the row still shows this programme)
                val wanted = c.key + ":" + now.start
                desc.tag = wanted
                val known = LiveTv.description(c, now) { text -> if (desc.tag == wanted) showDesc(text) }
                showDesc(known ?: "")
            } else {
                desc.tag = null
                showDesc("")
            }
            val next = if (showNext && wide) c.next(t) else null
            nextBox.visibility = if (wide && showNext) View.VISIBLE else View.GONE
            nextTime.text = if (next != null) ctx.getString(R.string.rd_tv_next_at, TvTexts.time(ctx, next.start)) else ""
            nextTitle.text = next?.title ?: ""
            itemView.contentDescription = listOfNotNull(c.number.takeIf { it > 0 }?.toString(), c.name, now?.title).joinToString(", ")
        }
    }

    /**
     * A channel as the desktop's card (TvDisplay.qml's grid): the well 16:9 with a radius of 12, the logo 64 in its
     * middle, the number in its corner, the catch-up mark in the other, what is on as a line of 4 along its foot
     * (from the left: a time axis), then the name and what is on. 20 between the cards, 20 under them. The channel
     * playing has the accent's well and name; the remote's focus the accent's name and a line round the well.
     */
    inner class CardHolder(ctx: Context) : RecyclerView.ViewHolder(LinearLayout(ctx)) {
        private val card = itemView as LinearLayout
        private val picture = CardPicture(ctx)
        private val logo = TvLogoView(ctx, 64f).apply { wellVisible = false }
        private val number = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a3))
        private val badgeBox = FrameLayout(ctx)
        private val name = ctx.tvText("", 14f, ctx.tvColor(R.color.rd_fg_a1))
        private val nowTitle = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a3))
        private val progress = CardProgress(ctx, ctx.tvAccent)
        private var channel: TvChannel? = null
        private var current = false

        init {
            card.orientation = LinearLayout.VERTICAL
            card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // (half the 20 between two cards on each side, the 20 under a row)
            card.setPadding(ctx.tvPx(10f), 0, ctx.tvPx(10f), ctx.tvPx(20f))
            card.isFocusable = true
            card.isClickable = true
            // (a touch shows on the card; nothing is drawn behind it otherwise, as on the desktop)
            card.background = RippleDrawable(ColorStateList.valueOf(ctx.tvColor(R.color.rd_ripple_dark)), null, rounded(0xFFFFFFFF.toInt(), ctx.tvDp(12f)))
            picture.addView(logo, FrameLayout.LayoutParams(ctx.tvPx(64f), ctx.tvPx(64f), Gravity.CENTER))
            picture.addView(number, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(ctx.tvPx(10f), ctx.tvPx(10f), ctx.tvPx(10f), 0) })
            picture.addView(badgeBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(ctx.tvPx(8f), ctx.tvPx(8f), ctx.tvPx(8f), 0) })
            picture.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.tvPx(4f), Gravity.BOTTOM))
            card.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            nowTitle.maxLines = 1
            nowTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(14f) })
            card.addView(nowTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(4f) })
            card.setOnClickListener { channel?.let(onPlay) }
            card.setOnLongClickListener { v -> channel?.let { onMenu(it, v) }; true }
            card.setOnFocusChangeListener { _, _ -> look() }
        }

        fun bind(c: TvChannel) {
            val ctx = itemView.context
            channel = c
            logo.set(c.logo, c.name)
            number.text = if (c.number > 0) c.number.toString() else ""
            name.text = c.name
            badgeBox.removeAllViews()
            if (c.catchupDays > 0) badgeBox.addView(ctx.tvBadge(ctx.resources.getQuantityString(R.plurals.rd_tv_days_short, c.catchupDays, c.catchupDays), R.drawable.rd_ic_recent))
            bindNow(c)
        }

        fun bindNow(c: TvChannel) {
            val ctx = itemView.context
            val t = System.currentTimeMillis() / 1000
            val now = c.now(t)
            current = TvPlayback.current.value == c.key
            look()
            nowTitle.text = now?.title ?: if (c.hasGuide) "" else ctx.getString(R.string.rd_tv_no_guide)
            progress.fraction = if (now != null && showProgress) (t - now.start).toFloat() / maxOf(1L, now.stop - now.start) else 0f
            itemView.contentDescription = listOfNotNull(c.number.takeIf { it > 0 }?.toString(), c.name, now?.title).joinToString(", ")
        }

        /** the well and the name of the channel playing, of the one the remote is on, of the others */
        private fun look() {
            val ctx = itemView.context
            val focused = card.isFocused
            picture.setLook(if (current) ctx.tvAttr(R.attr.rd_accent_bright) else ctx.tvColor(R.color.rd_tv_logo_well),
                if (focused) ctx.tvAccent else 0)
            name.setTextColor(if (current || focused) ctx.tvAccent else ctx.tvColor(R.color.rd_fg_a1))
        }
    }

    /** The card's well: as wide as its card, 16:9, its corners cut round (what is on runs along its foot into them). */
    private class CardPicture(context: Context) : FrameLayout(context) {
        private val radius = context.tvDp(12f)
        private val line = context.tvDp(1.5f)
        private var fill = 0
        private var stroke = 0

        init {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        fun setLook(fill: Int, stroke: Int) {
            if (fill == this.fill && stroke == this.stroke && background != null) return
            this.fill = fill
            this.stroke = stroke
            background = rounded(fill, radius)
            foreground = if (stroke != 0) rounded(0, radius, line.toInt().coerceAtLeast(1), stroke) else null
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
        }
    }

    /** What is on, as a line along the well's foot: from the left in every language (a time axis). */
    private class CardProgress(context: Context, color: Int) : View(context) {
        var fraction = 0f
            set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
        private val paint = android.graphics.Paint().apply { this.color = color }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (fraction > 0f) canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
        }
    }

    companion object {
        private val TICK = Any()
    }
}

/** The groups of the channels: All, the favourites, the ones watched last, then the provider's. */
internal class TvChipAdapter(private val context: Context, private val onChosen: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class Chip(val name: String, val label: String, val count: Int)
    var chips: List<Chip> = emptyList()
    var selected = ""

    fun submit(list: List<Chip>, chosen: String) {
        if (list == chips && chosen == selected) return
        chips = list
        selected = chosen
        notifyDataSetChanged()
    }

    override fun getItemCount() = chips.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(FrameLayout(context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.tvPx(32f)).apply { marginEnd = context.tvPx(8f) }
    }) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val chip = chips[position]
        val box = holder.itemView as FrameLayout
        box.removeAllViews()
        val label = if (chip.count > 0 && chip.name != "favorites") chip.label + "  " + chip.count else chip.label
        box.addView(context.tvChip(label, chip.name == selected) { onChosen(chip.name) }.apply { contentDescription = chip.label },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

internal fun TextView.setStartAligned() { textAlignment = View.TEXT_ALIGNMENT_VIEW_START }

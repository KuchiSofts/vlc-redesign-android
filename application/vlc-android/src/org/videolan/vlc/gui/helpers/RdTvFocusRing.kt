/*
 * VLC Redesign: the D-pad focus of the TV layout as the TV app draws it (core.js .focus, the desktop's keyboard focus):
 * a 2 px line of FG/1 around whatever has the focus, on every page of the tablet layout, whatever the view's own
 * focused state shows (most of them were made for touch and show none, or a faint one).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R

object RdTvFocusRing {
    private val NONE = Any()

    fun attach(activity: Activity) = attach(activity.window)

    /** A dialog's or a sheet's window as well (its own view tree). */
    fun attach(window: Window?) {
        if (!Settings.showTvUi || window == null) return
        val decor = window.decorView
        if (decor.getTag(R.id.rd_tv_focus_listener) != null) return
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            old?.let { clear(it) }
            new?.let { show(it) }
        }
        decor.setTag(R.id.rd_tv_focus_listener, listener)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        decor.findFocus()?.let { show(it) }
    }

    private fun ring(view: View): Drawable = Ring(view, GradientDrawable().apply {
        setColor(0)
        setStroke(2.dp, ContextCompat.getColor(view.context, R.color.rd_dark_fg_1))
        cornerRadius = 6.dp.toFloat()
    })

    /**
     * The ring around the view as it is on screen: a one-line text (a button, a centred title) is scrolled within
     * itself, and a foreground drawn where the view's scrolled content is was out of sight (no ring on the buttons).
     */
    private class Ring(private val view: View, private val line: GradientDrawable) : Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.translate(view.scrollX.toFloat(), view.scrollY.toFloat())
            line.setBounds(0, 0, view.width, view.height)
            line.draw(canvas)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { line.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { line.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * What the ring goes around: a text field in a box of its own (the search's, with its icon) the box, else the
     * view itself.
     */
    private fun target(view: View): View {
        if (view !is android.widget.EditText) return view
        val box = view.parent as? android.view.ViewGroup ?: return view
        return if (box.background != null && box.childCount <= 3 && box.height <= view.height * 2 + 16.dp) box else view
    }

    private fun show(focused: View) {
        // a control that shows its focus itself (the player's play pill)
        if (focused.getTag(R.id.rd_tv_focus_skip) == true) return
        // a list that has the focus itself (between its rows, its rows made again): a row takes it, the one at the
        // focus before or the first shown (a list holding the focus showed none: where the remote was could not
        // be told)
        if (focused is androidx.recyclerview.widget.RecyclerView) {
            focused.postDelayed({
                if (focused.isFocused && focused.isAttachedToWindow) {
                    val lm = focused.layoutManager
                    val row = (0 until (lm?.childCount ?: 0)).asSequence().mapNotNull { lm?.getChildAt(it) }
                        .firstOrNull { it.isShown && (it.isFocusable || (it as? android.view.ViewGroup)?.hasFocusable() == true) }
                    // (an empty list, an empty playlist's page: the focus to what is above it, the page's header)
                    if (row?.requestFocus() != true) (focused.focusSearch(View.FOCUS_UP) ?: focused.focusSearch(View.FOCUS_LEFT))?.requestFocus()
                }
            }, 120)
            return
        }
        // a text field: the ring too, around its box (on a TV it shows no caret until it is typed in: the search
        // had the focus and nothing showed it)
        val view = target(focused)
        if (view.getTag(R.id.rd_tv_focus_saved) != null) return
        // (the box remembered by the field: the ring taken off the same view)
        if (view !== focused) focused.setTag(R.id.rd_tv_focus_listener, view)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            view.setTag(R.id.rd_tv_focus_saved, view.foreground ?: NONE)
            view.foreground = ring(view)
        } else {
            // a foreground is Android 6 (FrameLayout's only before): the ring is drawn in the view's overlay,
            // over its content as well, with the view's size
            val r = ring(view).apply { setBounds(0, 0, view.width, view.height) }
            view.overlay.add(r)
            view.setTag(R.id.rd_tv_focus_saved, OverlayRing(r))
        }
    }

    private class OverlayRing(val drawable: Drawable)

    private fun clear(focused: View) {
        val view = (focused.getTag(R.id.rd_tv_focus_listener) as? View)?.also { focused.setTag(R.id.rd_tv_focus_listener, null) } ?: focused
        val saved = view.getTag(R.id.rd_tv_focus_saved) ?: return
        if (saved is OverlayRing) view.overlay.remove(saved.drawable)
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) view.foreground = saved as? Drawable
        view.setTag(R.id.rd_tv_focus_saved, null)
    }
}

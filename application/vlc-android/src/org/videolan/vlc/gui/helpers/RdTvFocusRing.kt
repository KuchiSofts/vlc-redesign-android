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
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R

object RdTvFocusRing {

    /** How far outside the focused view the line is drawn, in dp: clear of a card's name, of a button's words. */
    private const val OUTSET = 3

    fun attach(activity: Activity) = attach(activity.window)

    /**
     * One ring a window (a dialog's or a sheet's window as well: its own view tree), drawn over the window's content
     * around whatever has the focus. It is drawn by the window, not by the focused view: a view's own foreground was
     * cut by the view's edges (the line went through a card's name, a button at a row's start lost one side) and
     * stayed on a list's row that was recycled while it had the focus (two rings on a page, one of them stale).
     */
    fun attach(window: Window?) = attach(window?.decorView)

    /**
     * The dialogs made with a builder (a confirmation, a text to fill in), which no fragment announces: every window
     * of the app that is a dialog's and has no ring yet gets one. Called when an activity's window loses the focus
     * (a menu's popup is left alone: it has its own highlight).
     */
    fun attachToDialogs() {
        if (!Settings.showTvUi) return
        val roots: List<View> = try {
            if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews().toList()
            else {
                val global = Class.forName("android.view.WindowManagerGlobal")
                val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null))
                (views as? List<*>)?.toList()?.filterIsInstance<View>() ?: emptyList()
            }
        } catch (e: Throwable) {
            emptyList()
        }
        for (root in roots) if (root.javaClass.simpleName == "DecorView") attach(root)
    }

    fun attach(decor: View?) {
        if (!Settings.showTvUi || decor == null) return
        if (decor.getTag(R.id.rd_tv_focus_listener) != null) return
        val ring = Ring(decor)
        decor.setTag(R.id.rd_tv_focus_listener, ring)
        decor.overlay.add(ring.line)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(ring)
        decor.viewTreeObserver.addOnPreDrawListener(ring)
        decor.viewTreeObserver.addOnWindowFocusChangeListener(ring)
        decor.findFocus()?.let { ring.onGlobalFocusChanged(null, it) }
    }

    private class Ring(val decor: View) : ViewTreeObserver.OnGlobalFocusChangeListener, ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        val line = GradientDrawable().apply {
            setColor(0)
            setStroke(2.dp, ContextCompat.getColor(decor.context, R.color.rd_dark_fg_1))
            cornerRadius = (6 + OUTSET).dp.toFloat()
            setBounds(0, 0, 0, 0)
        }
        private var target: java.lang.ref.WeakReference<View>? = null
        private val at = Rect()
        private val last = Rect()

        override fun onGlobalFocusChanged(old: View?, new: View?) {
            target = null
            if (new != null && new.getTag(R.id.rd_tv_focus_skip) != true) {
                if (new is androidx.recyclerview.widget.RecyclerView) handOn(new)
                else {
                    target = java.lang.ref.WeakReference(around(new))
                    // a platform list or grid (the colour picker's swatches) moves a selection of its own inside: shown
                    // with the ring's line (its own mark could not be seen: the remote moved blind)
                    if (new is android.widget.AbsListView && new.getTag(R.id.rd_tv_focus_listener) == null) {
                        new.setTag(R.id.rd_tv_focus_listener, true)
                        new.setDrawSelectorOnTop(true)
                        new.selector = GradientDrawable().apply {
                            setColor(0)
                            setStroke(2.dp, ContextCompat.getColor(new.context, R.color.rd_dark_fg_1))
                            cornerRadius = 8.dp.toFloat()
                        }
                    }
                    // a window that opens with the focus on a scroll box (a dialog's buttons, asked before they were
                    // laid out; a picker's grid) or on its title: OK did nothing there, and nothing showed where the
                    // remote was
                    val box = new is android.widget.ScrollView || new is android.widget.HorizontalScrollView || new is androidx.core.widget.NestedScrollView
                    val label = old == null && new is android.widget.TextView && new !is android.widget.EditText && !new.isClickable
                            && !new.isLongClickable && new.movementMethod == null
                    if (box || label) passOn(new)
                }
            }
            decor.invalidate()
        }

        /**
         * The ring of the window the remote is in only: a window opened over this one (Settings, a dialog, a menu)
         * left this one's ring showing through its veil, a second ring on the screen.
         */
        override fun onWindowFocusChanged(hasFocus: Boolean) = decor.invalidate()

        /** Before every frame: the ring where the focused view is now (a list scrolled, a row moved), or nowhere. */
        override fun onPreDraw(): Boolean {
            val view = target?.get()
            if (view == null || !decor.hasWindowFocus() || !view.isAttachedToWindow || !view.isShown || !(view.hasFocus() || view.isFocused) || !view.getGlobalVisibleRect(at)) {
                at.setEmpty()
            } else {
                // (a card, its name at its very edge: twice as far out as around a button or a row)
                val out = if (view.height > 96.dp && view.width < decor.width / 2) (2 * OUTSET).dp else OUTSET.dp
                // (inside the window: a row as wide as the page keeps its line on the screen)
                // (a row as wide as its page, its words from its very edge, a Settings row: clear of them at the sides)
                val side = if (view.height <= 96.dp && view.width >= decor.width / 2) maxOf(out, 8.dp) else out
                at.set(maxOf(at.left - side, 1), maxOf(at.top - out, 1), minOf(at.right + side, decor.width - 1), minOf(at.bottom + out, decor.height - 1))
            }
            if (at != last) {
                last.set(at)
                line.bounds = at
                decor.invalidate()
            }
            return true
        }

        /**
         * From a scroll box to the first thing in it that takes the focus (a box of text alone keeps it: the remote
         * scrolls it); from a title to the first thing in the window that can be pressed.
         */
        private fun passOn(view: View) {
            view.postDelayed({
                if (!view.isFocused || !view.isAttachedToWindow) return@postDelayed
                val all = ArrayList<View>().also { decor.addFocusables(it, View.FOCUS_FORWARD) }.filter { it !== view && it.isShown }
                val next = if (view is android.view.ViewGroup) all.firstOrNull { v -> generateSequence(v.parent) { it.parent }.any { it === view } }
                else all.firstOrNull { it.isClickable }
                next?.requestFocus()
            }, 80)
        }

        /**
         * A list that has the focus itself (between its rows, its rows made again): a row takes it, the first shown
         * (a list holding the focus showed none: where the remote was could not be told).
         */
        private fun handOn(list: androidx.recyclerview.widget.RecyclerView) {
            list.postDelayed({
                if (list.isFocused && list.isAttachedToWindow) {
                    val lm = list.layoutManager
                    val row = (0 until (lm?.childCount ?: 0)).asSequence().mapNotNull { lm?.getChildAt(it) }
                        .firstOrNull { it.isShown && (it.isFocusable || (it as? android.view.ViewGroup)?.hasFocusable() == true) }
                    // (an empty list, an empty playlist's page: the focus to what is above it, the page's header)
                    if (row?.requestFocus() != true) (list.focusSearch(View.FOCUS_UP) ?: list.focusSearch(View.FOCUS_LEFT))?.requestFocus()
                }
            }, 120)
        }

        /**
         * What the ring goes around: a text field in a box of its own (the search's, with its icon) the box, else the
         * view itself.
         */
        private fun around(view: View): View {
            if (view !is android.widget.EditText) return view
            val box = view.parent as? android.view.ViewGroup ?: return view
            return if (box.background != null && box.childCount <= 3 && box.height <= view.height * 2 + 16.dp) box else view
        }
    }
}

/*
 * VLC Redesign, the TV layout: the library's pages made ahead of their visit. A page's first visit inflated its
 * layout and every card it shows in one go on the main thread (Music: 28 cards; one frame of 450-650 ms on an
 * Android TV, the main thread busy before it). While the window is idle after the start, one view at a time is
 * inflated ahead (the pages' layouts and their cards, in the sidebar's order) and the page takes those instead of
 * inflating them: the same layouts, inflated by the same activity into the same kind of parent, so nothing changes
 * on screen; a page that comes before its views are ready inflates them as before. The views a page took are made
 * again once the window is idle again (the Navigator builds a page anew on each visit).
 *
 * Only the library's window (MainActivity) in the TV layout: phones and tablets inflate as before.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.tablet

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.ViewStubCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.MainActivity
import java.lang.ref.WeakReference

object RdTvPrebuild {

    private const val TAG = "VLC/RdTvPrebuild"

    /**
     * A layout made ahead: how many to keep ready; [list] a list's item (else a page, in a fragment's container).
     * [keep] false: a screen of another window (Recent, Settings) that cannot take it; inflated [count] times and
     * let go, so that its first inflation there finds the code run and the resources loaded.
     */
    private class Plan(val layout: Int, var count: Int, val list: Boolean, val keep: Boolean = true) {
        var made = 0
    }

    /**
     * The sidebar's order (Videos, Music, Network, Playlists, then Home: the start page, built again on a return).
     * The counts: what a page shows at once in the design's window (4 rows of 7 artists or albums, 4 of 5 videos,
     * the rows of Home); a page that takes more (a list instead of the grid, more on screen) raises its count.
     */
    private fun defaultPlans() = mutableListOf(
            Plan(R.layout.video_browser, 1, false),
            Plan(R.layout.video_grid, 1, false),
            Plan(R.layout.video_grid_card, 20, true),
            Plan(R.layout.audio_browser, 1, false),
            Plan(R.layout.audio_browser_card_item, 28, true),
            Plan(R.layout.main_browser_fragment, 1, false),
            Plan(R.layout.playlists_fragment, 1, false),
            Plan(R.layout.rd_home_fragment, 1, false),
            Plan(R.layout.rd_home_card, 16, true),
            // Recent's rows and the Settings dialog's (windows of their own)
            Plan(R.layout.history_item, 2, true, keep = false),
            Plan(R.layout.rd_pref_row, 2, true, keep = false),
            Plan(R.layout.rd_pref_category, 1, true, keep = false),
            Plan(R.layout.rd_pref_toggle, 1, false, keep = false),
    )

    /** After the window's start; after the last key or the last view a page took; between two views */
    private const val START_DELAY = 2500L
    private const val QUIET = 1000L
    private const val STEP = 48L
    /** The player's controls: after the video started to play */
    private const val PLAYER_DELAY = 1500L
    /** The most of one list's item kept ready (what a page took in one go, its burst) */
    private const val MAX_COUNT = 40
    private const val BURST_GAP = 1000L

    private var owner: WeakReference<MainActivity>? = null
    private var plans = defaultPlans()
    private val ready = HashMap<Int, ArrayDeque<View>>()
    private val failed = HashSet<Int>()
    private val burst = HashMap<Int, Int>()
    private var lastTake = 0L
    private var lastBusy = 0L
    /** The memory was short: nothing made until the window comes back in front */
    private var lean = false
    private var listParent: RecyclerView? = null
    private var pageParent: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val step = Runnable { work() }

    /** The library's window in the TV layout: its pages are made ahead while it is idle and in front. */
    fun start(activity: MainActivity) {
        if (!RdTvWindow.isOn(activity)) return
        clear()
        owner = WeakReference(activity)
        // the app left (its window hidden) or the memory short: what was made is let go, made again on the return
        val memory = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
                ready.clear()
                lean = true
            }
            override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        activity.registerComponentCallbacks(memory)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                lean = false
                schedule(START_DELAY)
            }
            override fun onPause(owner: LifecycleOwner) = handler.removeCallbacks(step)
            override fun onDestroy(owner: LifecycleOwner) {
                activity.unregisterComponentCallbacks(memory)
                if (this@RdTvPrebuild.owner?.get() === activity) clear()
            }
        })
    }

    /** A key of the remote: the window answers it first, nothing is made for a while */
    fun onKey() {
        lastBusy = SystemClock.uptimeMillis()
    }

    /** The views made so far let go (Settings changed): made again when the window is idle */
    fun reset() {
        ready.clear()
        if (owner?.get() != null) schedule(START_DELAY)
    }

    /**
     * A view of [layout] made ahead for [parent] (a list or a fragment's container of the library's window in the
     * TV layout), or null: the caller inflates it as before.
     */
    fun take(parent: ViewGroup?, layout: Int): View? {
        val activity = owner?.get() ?: return null
        if (parent == null || parent.context !== activity || Looper.myLooper() !== Looper.getMainLooper()) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastTake > BURST_GAP) burst.clear()
        lastTake = now
        lastBusy = now
        // what a page takes in one go is what to keep ready for it
        val list = parent is RecyclerView
        val taken = (burst[layout] ?: 0) + 1
        burst[layout] = taken
        val plan = plans.firstOrNull { it.layout == layout } ?: Plan(layout, 0, list).also { plans.add(it) }
        if (plan.list && taken > plan.count) plan.count = minOf(taken, MAX_COUNT)
        else if (!plan.list && plan.count == 0) plan.count = 1
        val v = ready[layout]?.removeFirstOrNull()
        schedule(QUIET)
        return v
    }

    /** [inflater].inflate([layout], [parent], false), on the view made ahead when there is one */
    fun inflate(inflater: LayoutInflater, layout: Int, parent: ViewGroup?): View =
            take(parent, layout) ?: inflater.inflate(layout, parent, false)

    /** The binding of [layout] for [parent]: on the view made ahead when there is one, else [inflate]'s */
    inline fun <T : ViewDataBinding> bind(parent: ViewGroup?, layout: Int, inflate: () -> T): T =
            take(parent, layout)?.let { DataBindingUtil.bind<T>(it) } ?: inflate()

    private fun schedule(delay: Long) {
        handler.removeCallbacks(step)
        handler.postDelayed(step, delay)
    }

    private fun work() {
        val activity = owner?.get() ?: return
        if (lean || activity.isFinishing || activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val wait = lastBusy + QUIET - SystemClock.uptimeMillis()
        if (wait > 0) {
            schedule(wait)
            return
        }
        // a frame or another message waiting: after it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Looper.getMainLooper().queue.isIdle) {
            schedule(STEP)
            return
        }
        val plan = plans.firstOrNull { it.layout !in failed && (if (it.keep) ready[it.layout]?.size ?: 0 else it.made) < it.count } ?: return
        // (inflated into the kind of parent the page gives: a list's grid, or a fragment's frame)
        val parent: ViewGroup = if (plan.list) listParent ?: RecyclerView(activity).apply { layoutManager = GridLayoutManager(activity, 1) }.also { listParent = it }
                else pageParent ?: FrameLayout(activity).also { pageParent = it }
        try {
            val v = LayoutInflater.from(activity).inflate(plan.layout, parent, false)
            if (plan.keep) ready.getOrPut(plan.layout) { ArrayDeque() }.addLast(v) else plan.made++
        } catch (e: Exception) {
            // (never again in this window: the page inflates it itself)
            failed.add(plan.layout)
            if (BuildConfig.DEBUG) Log.w(TAG, "could not make ${activity.resources.getResourceEntryName(plan.layout)} ahead", e)
        }
        schedule(STEP)
    }

    /** A stub's layout inflated ahead: the stub's inflater hands it over the first time the stub inflates */
    private class Ahead(private val base: LayoutInflater, private var view: View?, private val layout: Int) : LayoutInflater(base, base.context) {
        override fun cloneInContext(newContext: Context): LayoutInflater = base.cloneInContext(newContext)
        override fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View {
            val v = view
            if (v != null && resource == layout && !attachToRoot && v.parent == null) {
                view = null
                return v
            }
            return base.inflate(resource, root, attachToRoot)
        }
    }

    /**
     * The player's controls made ahead (the TV layout): the first key that showed them inflated both bars in that
     * frame (the main thread busy before it). Once the video plays and the window is idle, the stubs' layouts are
     * inflated, one per idle moment, and given to the stubs, which take them when the controls first show.
     */
    fun stubsAhead(activity: Activity, vararg stubs: Int) {
        if (!RdTvWindow.isOn(activity)) return
        val ref = WeakReference(activity)
        val todo = ArrayDeque(stubs.toList())
        handler.postDelayed(object : Runnable {
            override fun run() {
                val a = ref.get() ?: return
                if (a.isFinishing || a.isDestroyed) return
                val wait = lastBusy + QUIET - SystemClock.uptimeMillis()
                if (wait > 0) {
                    handler.postDelayed(this, wait)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Looper.getMainLooper().queue.isIdle) {
                    handler.postDelayed(this, STEP)
                    return
                }
                // (a stub already inflated is not in the window any more)
                val stub = a.findViewById<ViewStubCompat>(todo.removeFirstOrNull() ?: return)
                val parent = stub?.parent as? ViewGroup
                if (stub != null && parent != null && stub.layoutResource != 0 && stub.layoutInflater !is Ahead) try {
                    val base = stub.layoutInflater ?: LayoutInflater.from(stub.context)
                    stub.layoutInflater = Ahead(base, base.inflate(stub.layoutResource, parent, false), stub.layoutResource)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "could not make a stub ahead", e)
                }
                if (todo.isNotEmpty()) handler.postDelayed(this, STEP)
            }
        }, PLAYER_DELAY)
    }

    private fun clear() {
        handler.removeCallbacks(step)
        ready.clear()
        failed.clear()
        burst.clear()
        lean = false
        plans = defaultPlans()
        listParent = null
        pageParent = null
        owner = null
    }
}

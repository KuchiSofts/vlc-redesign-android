/*
 * VLC Redesign: Settings in the TV layout as the design's dialog (Figma "Settings - *", 1048 x 865, the desktop's
 * SettingsDialog.qml) over the library, as the Samsung TV app draws it (vlc-redesign-tizen app/js/settings.js and
 * css/dialogs.css): the card #0E0E0E radius 14 with its 1 px #4C4A48 line, the bar of 50 (#090909) with the page's
 * title at 240 and the close button at 1002, 9; the sidebar #181818 radius 6 at 8, 58 (200 x 799); the page
 * #0F0F0F from 216 under the bar, its content at 240 (784 wide). The TV has no search in Settings (the owner's
 * choice, as on the Samsung TV app). PreferencesActivity hands itself over to this one in the TV layout; the
 * results go to whoever opened Settings (FLAG_ACTIVITY_FORWARD_RESULT).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.appbar.AppBarLayout
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.preferences.rd.RdPage
import org.videolan.vlc.gui.preferences.rd.RdSettingsSidebar

class TvPreferencesActivity : PreferencesActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val content = findViewById<ViewGroup>(android.R.id.content)
        val page = content.getChildAt(0)
        content.removeView(page)

        // the design's window, 1048 x 865, on its 1920 x 1080 page; at a larger interface size (fewer dp across and
        // down) what the screen holds less the margin, its page and sidebar following (RdFit)
        val dm = resources.displayMetrics
        val cardW = org.videolan.vlc.util.RdFitRules.size(1048.dp, dm.widthPixels, org.videolan.vlc.gui.helpers.RdFit.MARGIN.dp)
        // (clear of the title bar above and as much below: its top edge cut through the app's name)
        val cardH = org.videolan.vlc.util.RdFitRules.size(865.dp, dm.heightPixels, (org.videolan.vlc.gui.tablet.RdTvWindow.TITLE_BAR_HEIGHT + 8).dp)
        // the library under a veil of 50 % black (.dlg-veil); Back or the close button closes
        // (70 %: at 50 the page's pictures still shone beside the window)
        val veil = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()) }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(0xFF0E0E0E.toInt()) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 14.dp.toFloat())
            }
            clipToOutline = true
        }
        // the page's background beside the sidebar
        card.addView(View(this).apply { setBackgroundColor(0xFF0F0F0F.toInt()) },
                FrameLayout.LayoutParams(cardW - 216.dp, cardH - 50.dp).apply { marginStart = 216.dp; topMargin = 50.dp })
        card.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(0); setStroke(1.dp, 0xFF4C4A48.toInt()) }
            isFocusable = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        veil.addView(card, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))
        content.addView(veil, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.setBackgroundColor(Color.TRANSPARENT)

        // the bar: 50 high, #090909, the title (Inter 16, FG/2) at 240, no back arrow; the close button
        val appbar = findViewById<AppBarLayout>(R.id.appbar)
        appbar.setBackgroundColor(0xFF090909.toInt())
        appbar.elevation = 0f
        appbar.post { appbar.elevation = 0f }
        val toolbar = findViewById<Toolbar>(R.id.main_toolbar)
        toolbar.layoutParams = toolbar.layoutParams.apply { height = 50.dp }
        toolbar.minimumHeight = 50.dp
        toolbar.navigationIcon = null
        toolbar.setContentInsetsAbsolute(0, 0)
        toolbar.setTitleTextColor(0xFF969696.toInt())
        toolbar.titleMarginStart = 232.dp
        toolbar.findViewById<View>(R.id.toolbar_icon)?.visibility = View.GONE
        toolbar.findViewById<View>(R.id.toolbar_vlc_title)?.visibility = View.GONE
        toolbar.post { styleTitle(toolbar) }
        // (the bar stays where it is: a scrolled page slid it away, and the rows went up under the close button, the
        // sidebar with them)
        for (i in 0 until appbar.childCount) (appbar.getChildAt(i).layoutParams as? AppBarLayout.LayoutParams)?.scrollFlags = 0
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.rd_ic_close)
            setColorFilter(0xFF8F8F8F.toInt())
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            contentDescription = getString(R.string.close)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(0) }
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(32.dp, 32.dp).apply { marginStart = cardW - 46.dp; topMargin = 9.dp })

        // the sidebar at 8, 8 under the bar (200 x 799, #181818, radius 6); the page's content at 240, 784 wide
        findViewById<View>(R.id.rd_settings_sidebar)?.let { side ->
            (side.layoutParams as LinearLayout.LayoutParams).apply { width = 200.dp; height = cardH - 66.dp; setMargins(8.dp, 8.dp, 0, 8.dp) }
            side.background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFF181818.toInt()) }
            side.clipToOutline = true
        }
        // the page's rows end 16 above the window's edge (the last row's focus frame stood on it), sub-pages too
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment, v: View, savedInstanceState: Bundle?) {
                (f as? androidx.preference.PreferenceFragmentCompat)?.listView?.let { list ->
                    list.clipToPadding = false
                    // (a sub-page starts with a row, not with a section's heading: 12 under the title bar)
                    val top = if (f is org.videolan.vlc.gui.preferences.rd.RdSettingsPage) list.paddingTop else maxOf(list.paddingTop, 12.dp)
                    list.setPadding(list.paddingLeft, top, list.paddingRight, maxOf(list.paddingBottom, 16.dp))
                }
            }
        }, false)
        // Back closed a sub-page: the focus on the row that opened it
        supportFragmentManager.addOnBackStackChangedListener {
            if (subOpen && supportFragmentManager.backStackEntryCount == 0) {
                subOpen = false
                if (!window.decorView.isInTouchMode) enterPage(position = subFrom)
            }
        }
        findViewById<View>(R.id.fragment_placeholder)?.let { pane ->
            // (815 high: under the bar, as the sidebar; the coordinator gave it the card's whole height, its last 50
            // under the card's edge, where a row the remote moved to stayed out of sight and the focus was lost)
            (pane.layoutParams as LinearLayout.LayoutParams).apply { width = cardW - 264.dp; height = cardH - 50.dp; weight = 0f; marginStart = 32.dp }
        }
    }

    private fun styleTitle(toolbar: Toolbar) {
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is TextView && v.text == toolbar.title) {
                v.typeface = ResourcesCompat.getFont(this, R.font.inter)
                v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                v.includeFontPadding = false
            }
        }
    }

    /** No search in the TV's Settings (as the Samsung TV app) */
    override fun onCreateOptionsMenu(menu: Menu) = true

    private val sidebarPane get() = supportFragmentManager.findFragmentById(R.id.rd_settings_sidebar) as? RdSettingsSidebar

    private fun holdsSlider(v: View): Boolean = v is android.widget.AbsSeekBar || v.javaClass.simpleName.contains("Slider")
            || (v is ViewGroup && (0 until v.childCount).any { holdsSlider(v.getChildAt(it)) })

    private fun paneOf(v: View?): ViewGroup? {
        v ?: return null
        val above = generateSequence(v.parent) { it.parent }.toList()
        return sequenceOf(R.id.rd_settings_sidebar, R.id.fragment_placeholder)
                .mapNotNull { findViewById<ViewGroup>(it) }.firstOrNull { p -> above.any { it === p } }
    }

    private val pageList get() = (supportFragmentManager.findFragmentById(R.id.fragment_placeholder) as? androidx.preference.PreferenceFragmentCompat)?.listView

    /**
     * The focus into the page: on its row at [position] of its list, else on its first row. A page just opened has
     * no rows yet: until they are laid out the focus waits on this page's row of the sidebar (the window gave it to
     * the sidebar's first row when the rows that had it went, and the page follows the sidebar's focus: General
     * opened in place of the page asked for).
     */
    fun enterPage(tries: Int = 8, position: Int = -1) {
        val pane = findViewById<ViewGroup>(R.id.fragment_placeholder) ?: return
        // (never from inside a fragment change, never after the window was left)
        if (!supportFragmentManager.isStateSaved) runCatching { supportFragmentManager.executePendingTransactions() }
        val list = pageList
        val row = (if (position >= 0) list?.findViewHolderForAdapterPosition(position)?.itemView?.takeIf { it.hasFocusable() } else null)
                ?: list?.let { l -> (0 until l.childCount).map { l.getChildAt(it) }.firstOrNull { it.hasFocusable() } }
        if (row?.requestFocus(View.FOCUS_DOWN) == true) return
        if (paneOf(currentFocus) !== pane) sidebarPane?.focusCurrent()
        if (tries > 0) pane.postDelayed({ if (!isFinishing && !isDestroyed && paneOf(currentFocus) !== pane) enterPage(tries - 1, position) }, 50)
    }

    /** a sub-page (Remote access, Parental control) is over the page, opened from the row at this place of its list */
    private var subOpen = false
    private var subFrom = -1

    /** Its first row takes the focus (the row that opened it is gone with its page) */
    override fun openSubScreen(fragment: androidx.fragment.app.Fragment) {
        val list = pageList
        subFrom = list?.focusedChild?.let { list.getChildAdapterPosition(it) } ?: -1
        super.openSubScreen(fragment)
        subOpen = true
        if (!window.decorView.isInTouchMode) enterPage()
    }

    override fun openPage(page: RdPage, scrollTo: String?) {
        // (the sidebar's doing: the focus stays where the remote is)
        subOpen = false
        super.openPage(page, scrollTo)
    }

    /**
     * The remote in the window's two panes: where an arrow key takes the focus, decided before the system moves it.
     * Down on the last row of the page, or of the sidebar, stays on it: the search for a row below went on into the
     * other pane (from the page's last row to the sidebar's "Network", and from the sidebar's last row to the page).
     * Into the page (from the sidebar, or from the close button) the focus lands on its first row, not on the row
     * that happens to stand beside, and the page is the one of the sidebar's row left; back to the sidebar it lands on
     * the page's own row, not on its neighbour's (the page follows the sidebar's focus: that would open another).
     * Left and Right on a row with nothing beside it stay (they jumped up to the close button).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val side = findViewById<ViewGroup>(R.id.rd_settings_sidebar)
        val page = findViewById<ViewGroup>(R.id.fragment_placeholder)
        if (side == null || page == null || event.action != KeyEvent.ACTION_DOWN
                || code !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return super.dispatchKeyEvent(event)
        val focused = currentFocus ?: return super.dispatchKeyEvent(event)
        val before = paneOf(focused)
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (before === side && code == (if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val key = focused.tag as? String
            // (About is a screen of its own: nothing beside it)
            if (key == RdSettingsSidebar.ABOUT) return true
            val target = key?.let { RdPage.of(it) }
            if (target != null && sidebarPane?.isCurrent(key) == false && !supportFragmentManager.isStateSaved) openPage(target)
            enterPage()
            return true
        }
        // (a text field moves its caret, a slider its value: theirs first)
        if (super.dispatchKeyEvent(event)) return true
        if (currentFocus !== focused) return false
        val across = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val next = focused.focusSearch(when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }) ?: return false
        if (next === focused) return false
        val after = paneOf(next)
        return when {
            after === before -> false
            // (a slider keeps Left and Right at its ends too: one press more than its lowest value left the page)
            before === page && across && holdsSlider(focused) -> true
            // out of the page sideways: the sidebar's row of this page, or nowhere
            before === page && across -> { if (after === side) sidebarPane?.focusCurrent(); true }
            // up and down stay in their pane (above the first row is the close button)
            before != null && after != null -> true
            // from the title bar's button: the page's first row, the sidebar's current row
            before == null && after === page -> { enterPage(0); true }
            before == null && after === side -> { sidebarPane?.focusCurrent(); true }
            else -> false
        }
    }
}

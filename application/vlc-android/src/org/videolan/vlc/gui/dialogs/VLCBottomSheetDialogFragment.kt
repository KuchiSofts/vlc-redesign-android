package org.videolan.vlc.gui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.BaseInputConnection
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.resources.AndroidDevices
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.tablet.TabletSheets
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.gui.video.VideoPlayerActivity.Companion.videoRemoteFlow
import org.videolan.vlc.util.isTalkbackIsEnabled

abstract class VLCBottomSheetDialogFragment : BottomSheetDialogFragment() {

    // VLC Redesign: the TV layout's sheets take their theme for themselves (onCreateDialog). Laid on the activity, it
    // stayed there after the sheet: the next page of the TV layout's settings (a Leanback list) could no longer be
    // built and the app crashed (Settings > Interface > Sleep timer, then any page)

    private var videoRemoteJob: Job? = null
    var onDismissListener: DialogInterface.OnDismissListener? = null

    fun inflate(inflater: LayoutInflater, container: ViewGroup?, @LayoutRes layout: Int): View? {
        return inflater.inflate(layout, container, false)

    }

    override fun onStart() {
        // VLC Redesign: the TV layout's focus line in the sheets as well
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog?.window)
        if (allowRemote()) {
            shouldInterceptRemote.postValue(true)
            videoRemoteJob = lifecycleScope.launch {
                videoRemoteFlow.collect { action ->
                    when (action) {
                        "up" -> KeyEvent.KEYCODE_DPAD_UP
                        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                        "center" -> KeyEvent.KEYCODE_DPAD_CENTER
                        "back" -> KeyEvent.KEYCODE_BACK
                        else -> null
                    }?.let { keyCode ->
                        simulateKeyPress(keyCode)
                        videoRemoteFlow.emit(null)
                    }
                }
            }
        }
        super.onStart()
    }

    override fun onStop() {
        if (allowRemote()) shouldInterceptRemote.postValue(false)
        videoRemoteJob?.cancel()
        super.onStop()
    }

    /**
     * Apply bottom margin for Android TV
     */
    private fun applyOverscanMargin(view: View) {
        val vm = requireActivity().resources.getDimensionPixelSize(org.videolan.resources.R.dimen.tv_overscan_vertical)
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom + vm)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launchWhenStarted {
            dialog?.window?.setLayout(windowWidth(), ViewGroup.LayoutParams.MATCH_PARENT)
            val anchored = anchoredWanted()
            if (anchored) applyAnchored(view)
            else if (useSideSheet()) applySideSheet(view)
            // VLC Redesign, tablets: a centred framed window instead of a sheet on the bottom edge (gui/tablet)
            else if (TabletSheets.wanted(dialog) && !AndroidDevices.isTv) dialog?.let { TabletSheets.applyCentered(it, view) }
            dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                val bsb = BottomSheetBehavior.from(it)
                if (bsb.state == BottomSheetBehavior.STATE_COLLAPSED) bsb.state = getDefaultState()
            }
            dialog?.findViewById<View>(R.id.touch_outside)?.isFocusable = false
            dialog?.findViewById<View>(R.id.touch_outside)?.isFocusableInTouchMode = false
            if (AndroidDevices.isTv && !anchored) applyOverscanMargin(view)
        }


    }

    /** The sheet's width (a wider window for an editor shown centred on a tablet) */
    open fun windowWidth() = resources.getDimensionPixelSize(R.dimen.default_context_width)

    /**
     * VLC Redesign: the control bar's button this panel belongs to (0: none). The panel then opens as the desktop's,
     * anchored above that button at the bar (on a TV, a tablet, a phone on its side), not in the middle of the
     * screen; a phone upright keeps its sheet from the bar, the closest native way.
     */
    open fun anchorId(): Int = 0

    private fun anchoredWanted(): Boolean {
        if (anchorId() == 0 || activity !is VideoPlayerActivity) return false
        val anchor = activity?.findViewById<View>(anchorId()) ?: return false
        if (!anchor.isShown || anchor.width == 0) return false
        val cfg = resources.configuration
        return AndroidDevices.isTv || Settings.showTvUi || cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.smallestScreenWidthDp >= 600
    }

    /**
     * The desktop's player panel (its Video / Audio / Subtitles panel, 600 x 560 at 1920 x 1080): a floating A3 panel
     * over the video, its bottom 8 above the button, its end edge 14 past the button's, a third of the screen wide
     * (300 to 440) and about half its height; the video not dimmed under it.
     */
    private fun applyAnchored(view: View) {
        val dialog = dialog ?: return
        val anchor = activity?.findViewById<View>(anchorId()) ?: return
        val window = dialog.window ?: return
        val root = activity?.window?.decorView ?: return
        val d = resources.displayMetrics.density
        val screenW = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val gap = (8 * d).toInt()
        val past = (14 * d).toInt()
        val width = (screenW * 0.31f).toInt().coerceIn((300 * d).toInt(), (440 * d).toInt()).coerceAtMost(screenW - 2 * gap)
        val height = (screenH * 0.55f).toInt().coerceAtLeast((280 * d).toInt()).coerceAtMost(at[1] - 2 * gap)
        window.setLayout(width, height)
        // the side of the button: its own edge of the screen (the bar is not mirrored in Hebrew or Arabic)
        val onRight = at[0] + anchor.width / 2 > screenW / 2
        window.setGravity(Gravity.BOTTOM or if (onRight) Gravity.RIGHT else Gravity.LEFT)
        window.attributes = window.attributes.apply {
            x = if (onRight) (screenW - (at[0] + anchor.width) - past).coerceAtLeast(gap) else (at[0] - past).coerceAtLeast(gap)
            y = (screenH - at[1] + gap).coerceAtLeast(gap)
        }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                this.height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(0, 0, 0, 0)
            }
            sheet.background = null
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                isDraggable = false
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> { this.height = ViewGroup.LayoutParams.MATCH_PARENT }
        view.background = ContextCompat.getDrawable(view.context, R.drawable.rd_panel_float)
        view.clipToOutline = true
    }

    /**
     * VLC Redesign: in landscape the player's panels are side sheets at the end of the window (the desktop's
     * player panels), a floating A3 panel with radius 14 and the 1 px window border, over the full height
     */
    open fun useSideSheet() = activity is VideoPlayerActivity && !AndroidDevices.isTv
            && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applySideSheet(view: View) {
        val dialog = dialog ?: return
        val width = minOf(resources.getDimensionPixelSize(R.dimen.rd_side_sheet_width), resources.displayMetrics.widthPixels / 2)
        val margin = resources.getDimensionPixelSize(R.dimen.rd_side_sheet_margin)
        dialog.window?.setLayout(width + 2 * margin, ViewGroup.LayoutParams.MATCH_PARENT)
        // the end of the window in the reading direction (the desktop's panels: on the left in Hebrew or Arabic);
        // the window manager does not mirror a relative gravity: resolved here
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL ||
                android.text.TextUtils.getLayoutDirectionFromLocale(org.videolan.vlc.util.RdCompat.primaryLocale(resources.configuration)) == View.LAYOUT_DIRECTION_RTL
        dialog.window?.setGravity((if (rtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL)
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(margin, 0, margin, 0)
            }
            // the sheet behaviour keeps the sheet on the bottom edge: make it the parent's height less both
            // margins and lift it by one margin, so it floats with the same gap above and below
            (sheet.parent as? View)?.let { parent ->
                parent.post {
                    val top = ViewCompat.getRootWindowInsets(parent)?.let {
                        // the player shows and hides the status bar: keep its room either way
                        maxOf(it.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).top, it.getInsets(WindowInsetsCompat.Type.displayCutout()).top)
                    } ?: 0
                    if (parent.height > top + 2 * margin) {
                        sheet.updateLayoutParams<ViewGroup.MarginLayoutParams> { height = parent.height - top - 2 * margin }
                        sheet.translationY = -margin.toFloat()
                    }
                }
            }
            sheet.background = null
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                isDraggable = false
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> { height = ViewGroup.LayoutParams.MATCH_PARENT }
        view.background = ContextCompat.getDrawable(view.context, R.drawable.rd_panel_float)
        view.clipToOutline = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val bottomSheetDialog = if (Settings.showTvUi) BottomSheetDialog(ContextThemeWrapper(requireContext(), R.style.Theme_VLC_Black_BottomSheet), theme)
        else super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        if (AndroidDevices.isChromeBook) bottomSheetDialog.setOnShowListener {
            bottomSheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { bottomSheet ->
                    BottomSheetBehavior.from(bottomSheet).isDraggable = false
                }
        }
        return bottomSheetDialog
    }

    private fun simulateKeyPress(key: Int) {
        view?.let {
            val inputConnection = BaseInputConnection(
                it,
                true
            )
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, key)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, key)
            inputConnection.sendKeyEvent(downEvent)
            inputConnection.sendKeyEvent(upEvent)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun onResume() {
        super.onResume()

        initialFocusedView().isFocusable = true
        initialFocusedView().isFocusableInTouchMode = true

        initialFocusedView().requestFocus()
        if (requireActivity().isTalkbackIsEnabled()) initialFocusedView().sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!needToManageOrientation()) {
            super.onConfigurationChanged(newConfig)
            return
        }
        if (isAdded) {
            dismiss()
        }
        super.onConfigurationChanged(newConfig)
        if (isAdded) {
            show(parentFragmentManager.beginTransaction().setReorderingAllowed(false), tag)
        }
    }

    /**
     * Default state for the [BottomSheetBehavior]
     * Should be one of [BottomSheetBehavior.STATE_EXPANDED],[BottomSheetBehavior.STATE_COLLAPSED],[BottomSheetBehavior.STATE_HALF_EXPANDED]
     */
    abstract fun getDefaultState(): Int

    /**
     * Sends true if the fragments needs to be re-created when Activity is not recreated onConfigurationChanged
     */
    abstract fun needToManageOrientation(): Boolean

    /**
     * The initial view to be focused to avoid BottomSheetDialogFragment to steal it
     * Both fields [isFocusable] and [isFocusableInTouchMode] will be set to true
     */
    abstract fun initialFocusedView(): View

    open fun allowRemote() = false

    companion object {
        val shouldInterceptRemote = MutableLiveData(false)
    }


}
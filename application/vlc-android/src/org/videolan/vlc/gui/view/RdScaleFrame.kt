/*
 * VLC Redesign: a panel drawn at a scale, its only child laid out larger and drawn smaller: the player's panel of
 * the TV is the desktop's at its own sizes (1 design px = 1 px of the 1080p screen, half a dp), its layouts written
 * in design px as dp. Touch, the D-pad and the focus ring follow the child's scale.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.view

import android.content.Context
import android.view.View
import android.widget.FrameLayout

class RdScaleFrame(context: Context, private val scale: Float) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (scale == 1f || childCount == 0) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0)
        fun inner(spec: Int): Int {
            val mode = MeasureSpec.getMode(spec)
            return if (mode == MeasureSpec.UNSPECIFIED) spec else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(spec) / scale).toInt(), mode)
        }
        child.measure(inner(widthMeasureSpec), inner(heightMeasureSpec))
        setMeasuredDimension(resolveSize((child.measuredWidth * scale).toInt(), widthMeasureSpec),
            resolveSize((child.measuredHeight * scale).toInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (scale == 1f || childCount == 0) return super.onLayout(changed, left, top, right, bottom)
        val child = getChildAt(0)
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
    }

    companion object {
        /**
         * The scale of the player's panels: on a TV, 1 design px is a 1920th of its screen's width (half a dp on a
         * 1080p TV at density 2), as the desktop at 1920 x 1080; elsewhere 1 dp.
         */
        fun panelScale(context: Context): Float {
            val dm = context.resources.displayMetrics
            // the TV layout: its dp are already the design's px at the interface size chosen (RdTvDesignScale), so
            // the panel is drawn as it is; smaller only at the sizes whose screen cannot hold the desktop's panel
            // (560 high) above the control bar. (It was drawn at the design's own size whatever the interface
            // size: a panel of tiny letters at 150 % and more.)
            if (org.videolan.tools.Settings.showTvUi && org.videolan.tools.rdDensityOverride != null)
                return (minOf(dm.widthPixels, dm.heightPixels) * 0.8f / (560f * dm.density)).coerceIn(0.5f, 1f)
            if (!org.videolan.resources.AndroidDevices.isTv) return 1f
            return (maxOf(dm.widthPixels, dm.heightPixels) / 1920f / dm.density).coerceIn(0.3f, 1f)
        }

        fun wrap(context: Context, content: View): View {
            val scale = panelScale(context)
            if (scale == 1f) return content
            return RdScaleFrame(context, scale).apply { addView(content) }
        }
    }
}

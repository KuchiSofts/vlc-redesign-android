/*
 * VLC Redesign: the TV layout as the design's own pages (the tablet layout: the sidebar, Home, the library, Settings,
 * the players) at the design's size on a TV, as the TV app draws them on its 1920 x 1080 page: 1 design px = 1 dp
 * on 1920 px across, whatever the TV's own density (a 1080p Android TV is xhdpi, 960 dp across; a 4K one the same).
 * The activities of the TV layout take this configuration (density, size in dp) before their resources are made,
 * so the tablet's resources (sw600dp, the sidebar from 900 dp) and its sizes apply as on a 1920 px wide window.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.tools.Settings
import org.videolan.tools.KEY_RD_INTERFACE_SCALE
import org.videolan.tools.rdDensityOverride
import org.videolan.tools.rdPageScale
import kotlin.math.roundToInt

/** The design's page across, in px: 1 dp each at the TV's scale. */
private const val DESIGN_WIDTH = 1920

/** The sizes Settings offers, in percent of the design's (0: found for the screen). */
val RD_INTERFACE_SCALES = intArrayOf(0, 100, 110, 125, 150, 175, 200)

/**
 * The interface size that suits this screen, in percent of the design's size. The design is a desktop's window, read
 * from an arm's length; the system's own density says how large this screen wants its controls (a TV, watched from
 * the sofa, is 960 dp across whatever its panel; a tablet 1280 dp or so; a desktop-like window 1920 dp). The pages
 * are drawn a third finer than the system's own (they are the desktop's, denser than a TV's own pages), never
 * smaller than the design nor so large that less than a 1280 dp page is left: 150 % on a TV, about 110 % on a
 * tablet, 100 % on a desktop-like screen.
 */
fun rdAutoInterfaceScale(longSidePx: Int, systemDpi: Int): Int {
    if (longSidePx <= 0 || systemDpi <= 0) return 100
    val systemDp = longSidePx * DisplayMetrics.DENSITY_DEFAULT / systemDpi
    val page = (systemDp * 4 / 3).coerceIn(1280, DESIGN_WIDTH)
    // (to 5 %: the sizes stay on whole pixels more often)
    return ((DESIGN_WIDTH * 100f / page) / 5f).roundToInt() * 5
}

/** The interface size in use: the one chosen in Settings, else the one found for the screen. */
fun Context.rdInterfaceScale(): Int {
    val chosen = try { Settings.getInstance(this).getString(KEY_RD_INTERFACE_SCALE, "0")?.toIntOrNull() ?: 0 } catch (e: Exception) { 0 }
    if (chosen in 75..300) return chosen
    val metrics = rdRealMetrics()
    return rdAutoInterfaceScale(maxOf(metrics.widthPixels, metrics.heightPixels), metrics.densityDpi)
}

/** The whole screen (the bars are hidden in the TV layout), not the window less a tablet's navigation bar. */
private fun Context.rdRealMetrics() = DisplayMetrics().also {
    @Suppress("DEPRECATION")
    (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
}

/** This context at the design's scale when the TV layout is on, else unchanged. */
fun Context.rdTvDesignScale(): Context {
    if (!Settings.showTvUi) {
        rdDensityOverride = null
        rdPageScale = 1f
        return this
    }
    val metrics = rdRealMetrics()
    val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
    if (longSide <= 0) return this
    // the design's own density: its resources (the desktop's page, w1600dp) are chosen as on a 1920 dp page at
    // every interface size, the window itself holding fewer dp when the size is larger
    val designDpi = (DisplayMetrics.DENSITY_DEFAULT * longSide / DESIGN_WIDTH).coerceAtLeast(DisplayMetrics.DENSITY_LOW / 2)
    val percent = rdInterfaceScale()
    val dpi = (designDpi * percent / 100).coerceAtLeast(DisplayMetrics.DENSITY_LOW / 2)
    val config = Configuration(resources.configuration)
    val landscape = config.orientation != Configuration.ORIENTATION_PORTRAIT
    config.densityDpi = dpi
    // the TV layout is the design's dark pages whatever the system says: its resources (texts, panels) are the
    // night ones from the start
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    config.screenWidthDp = (if (landscape) longSide else shortSide) * DisplayMetrics.DENSITY_DEFAULT / designDpi
    config.screenHeightDp = (if (landscape) shortSide else longSide) * DisplayMetrics.DENSITY_DEFAULT / designDpi
    config.smallestScreenWidthDp = shortSide * DisplayMetrics.DENSITY_DEFAULT / designDpi
    // the sizes computed in code (Int.dp) at the same scale as the resources
    rdDensityOverride = dpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
    rdPageScale = dpi.toFloat() / designDpi
    return createConfigurationContext(config)
}

/**
 * The TV layout's resources kept the night ones. Back from the player or from Settings, the activity's configuration
 * came back as the system's (a TV has no dark mode: "day"), and a window opened then (the TV sources, a channel's
 * menu) drew the light theme's dark texts on the design's dark panels: titles that could not be read.
 */
fun Activity.rdTvKeepNight() {
    if (!Settings.showTvUi) return
    val now = resources.configuration
    if (now.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) return
    val night = Configuration(now).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES }
    @Suppress("DEPRECATION")
    resources.updateConfiguration(night, resources.displayMetrics)
}

/** The TV layout over the whole screen, as on a TV: a tablet's status and navigation bars hidden, back with a swipe. */
fun Activity.rdTvFullScreenIfTv() {
    if (!Settings.showTvUi) return
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

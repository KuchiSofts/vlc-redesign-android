/*
 * VLC Redesign: the backdrop of the music player (the blurred cover) and the texts over it.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.videolan.vlc.gui.helpers

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The same rule as the desktop's backdrop (ArtworkBackdrop.qml, MainCtx::imageBrightParts): in the dark theme the
 * white texts over the blurred cover read at 4.5:1 (WCAG) on the bright parts of the cover, whatever the cover. A
 * white cover under the old veil of 40 % left them at 2.9:1.
 */
object RdBackdrop {

    /** The veil of the design over any cover (dark theme): never less */
    const val LEAST_VEIL = 0.4

    /** White (luminance 1) reads at 4.5:1 on a luminance of this at most: 1.05 / 4.5 - 0.05 */
    const val MOST_LUMINANCE = 0.1833

    /** The relative luminance (WCAG, 0 black to 1 white) of an sRGB colour, its channels 0 to 255 */
    fun luminance(red: Int, green: Int, blue: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel.coerceIn(0, 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /**
     * How bright the bright parts of a picture are: of the luminances of its areas (a small copy of it, a pixel an
     * area), the 90th of 100. A few highlights do not count, a bright tenth does. -1 for no area.
     */
    fun brightParts(luminances: DoubleArray): Double {
        if (luminances.isEmpty()) return -1.0
        val sorted = luminances.sortedArray()
        return sorted[min(sorted.size - 1, sorted.size * 9 / 10)]
    }

    /** [brightParts] of ARGB pixels (a small copy of the cover) */
    fun brightParts(pixels: IntArray): Double =
        brightParts(DoubleArray(pixels.size) { luminance((pixels[it] shr 16) and 0xFF, (pixels[it] shr 8) and 0xFF, pixels[it] and 0xFF) })

    /**
     * The black veil (0 to 1) under which white texts read at 4.5:1 on parts of the luminance [bright], and
     * [least] at least. The veil mixes in sRGB: the luminance goes with its power 2.2. An unknown brightness
     * (negative) gets [least].
     */
    fun veil(bright: Double, least: Double = LEAST_VEIL): Double {
        if (bright <= MOST_LUMINANCE) return least
        return max(least, 1 - (MOST_LUMINANCE / min(bright, 1.0)).pow(1 / 2.2))
    }
}

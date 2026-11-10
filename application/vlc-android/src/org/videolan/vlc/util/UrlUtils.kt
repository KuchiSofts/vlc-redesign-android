/*
 * ************************************************************************
 *  UrlUtils.kt
 * *************************************************************************
 * Copyright © 2022 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.videolan.vlc.R


/**
 * False when a web link would only reach the TV's placeholder ("You don't have an app that can do this"): an Android
 * TV without a browser answers every link with it, so starting the link looked like a button that does nothing.
 * (The manifest's <queries> lets the app see who takes a link, on Android 11 and later.)
 */
fun Context.rdLinkOpens(url: String): Boolean = try {
    val found = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, url.toUri()), PackageManager.MATCH_DEFAULT_ONLY)
    found?.activityInfo?.packageName?.contains("frameworkpackagestubs") != true
} catch (e: Exception) {
    true
}

fun Context.openLinkIfPossible(url: String, size: Int = 512) {
    try {
        if (!rdLinkOpens(url)) throw IllegalStateException("No web browser found")
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
        val image = ImageView(this)
        image.setImageBitmap(UrlUtils.generateQRCode(url, size))
        val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_web_browser))
                .setMessage(getString(R.string.no_web_browser_message, url))
                .setView(image)
                .setPositiveButton(R.string.ok) { _, _ ->

                }
                .show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
    }
}

object UrlUtils {

    fun generateQRCode(url: String, size: Int = 512): Bitmap {
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    it.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }
}

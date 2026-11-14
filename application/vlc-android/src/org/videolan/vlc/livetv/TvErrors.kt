/*
 * VLC Redesign: why a Live TV source or guide could not be loaded, said in the interface's language. The loaders keep
 * a short reason, as the desktop's (modules/misc/iptv: "no answer", "no channels", "HTTP 404"...), never the system's
 * own text: that one was English in every language and named the addresses of the server and of the device ("failed
 * to connect to /192.0.2.5 (port 8080) from /192.0.2.23 (port 38654) after 20000ms"). [text] says a reason.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.content.Context
import org.videolan.vlc.R

object TvErrors {
    const val NO_ANSWER = "no answer"
    const val NOT_FOUND = "address not found"
    const val UNREACHABLE = "server unreachable"
    const val SECURE = "secure connection failed"
    const val FAILED = "could not be downloaded"
    const val GUIDE_UNREADABLE = "the guide could not be read"

    private val HTTP = Regex("HTTP (\\d{3})")

    /** the loaders' own reasons, kept as they are (the others: [FAILED]) */
    private val OWN = setOf(
        NO_ANSWER, NOT_FOUND, UNREACHABLE, SECURE, FAILED, GUIDE_UNREADABLE, "cancelled",
        "the file cannot be read", "the file cannot be written", "too many redirections",
        "the file is too large", "the answer is too large", "the guide is too large",
        "not an M3U playlist", "no channels", "not an XMLTV guide", GuideUpdate.CUT,
        "the account could not be read", "sign in first", "no_answer", "busy", "wrong"
    )

    /** the reason of [e]: a network failure by its kind, the loaders' own reasons as they are, else [fallback] */
    fun reason(e: Throwable, fallback: String = FAILED): String = when (e) {
        is java.net.SocketTimeoutException -> NO_ANSWER
        is java.net.UnknownHostException -> NOT_FOUND
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.PortUnreachableException -> UNREACHABLE
        is javax.net.ssl.SSLException -> SECURE
        else -> e.message?.takeIf { it in OWN || HTTP.matches(it) } ?: fallback
    }

    /** [reason] said in the interface's language (a text that is not a reason, as it is) */
    fun text(ctx: Context, reason: String): String {
        HTTP.matchEntire(reason)?.let { m ->
            val code = m.groupValues[1].toInt()
            return when (code) {
                401, 403 -> ctx.getString(R.string.rd_tv_err_refused, code)
                404, 410 -> ctx.getString(R.string.rd_tv_err_missing, code)
                else -> ctx.getString(R.string.rd_tv_err_http, code)
            }
        }
        val id = when (reason) {
            NO_ANSWER, "no_answer" -> R.string.rd_tv_err_no_answer
            NOT_FOUND -> R.string.rd_tv_err_not_found
            UNREACHABLE -> R.string.rd_tv_err_unreachable
            SECURE -> R.string.rd_tv_err_secure
            FAILED, "?" -> R.string.rd_tv_err_failed
            "busy" -> R.string.rd_tv_err_busy
            "wrong" -> R.string.rd_tv_err_wrong
            "cancelled" -> R.string.rd_tv_err_cancelled
            "the file cannot be read" -> R.string.rd_tv_err_unreadable
            "the file cannot be written" -> R.string.rd_tv_err_unsaved
            "too many redirections" -> R.string.rd_tv_err_redirections
            "the file is too large", "the answer is too large", "the guide is too large" -> R.string.rd_tv_err_too_large
            "not an M3U playlist" -> R.string.rd_tv_err_not_m3u
            "no channels" -> R.string.rd_tv_err_no_channels
            "not an XMLTV guide" -> R.string.rd_tv_err_not_xmltv
            GUIDE_UNREADABLE -> R.string.rd_tv_err_guide_unreadable
            GuideUpdate.CUT -> R.string.rd_tv_err_cut
            "the account could not be read" -> R.string.rd_tv_err_account
            "sign in first" -> R.string.rd_tv_err_sign_in
            else -> return reason
        }
        return ctx.getString(id)
    }
}

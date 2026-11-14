/*
 * VLC Redesign: the parts of the desktop's About (RedesignAboutDialog.qml) that the Android About
 * shows too: a check for updates now, the other platforms ("Also on"), "Suggest an idea",
 * "Report a problem" (the repository's bug form, filled in) and this app's own source.
 */
package org.videolan.vlc.gui.helpers

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.videolan.tools.Settings
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.util.RdUpdateCheck
import org.videolan.vlc.util.openLinkIfPossible
import java.net.URLEncoder
import java.util.Locale

object RdAbout {
    const val REPOSITORY = "https://github.com/KuchiSofts/vlc-redesign"
    /** the source of this app (GPL): the fork of VLC for Android, not VideoLAN's repository */
    const val ANDROID_SOURCE = "https://github.com/KuchiSofts/vlc-redesign-android"
    private const val REPORT_FORM = "$REPOSITORY/issues/new?template=bug_report.yml"
    private const val IDEA_FORM = "$REPOSITORY/discussions/new?category=ideas"
    /** the bug form's choice for this app (.github/ISSUE_TEMPLATE/bug_report.yml) */
    const val INSTALL_TYPE = "Android app (APK)"
    /**
     * A TV without a browser shows the link as a QR code, and a QR code holds about 2,900 bytes:
     * longer reports lose their last lines first (the desktop's rule, with its own limit of 8,000)
     */
    const val MAX_URL = 1800

    fun fill(activity: FragmentActivity, v: View) {
        v.findViewById<TextView>(R.id.rd_also_on)?.let { tv ->
            val line = try {
                activity.assets.open("redesign/platforms.json").bufferedReader().use { alsoOn(it.readText()) }
            } catch (e: Exception) {
                ""
            }
            tv.text = activity.getString(R.string.rd_also_on, line)
            tv.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<View>(R.id.rd_about_idea_container)?.setOnClickListener { activity.openLinkIfPossible(ideaUrl()) }
        v.findViewById<View>(R.id.about_report_container)?.setOnClickListener {
            activity.openLinkIfPossible(reportUrl(environment(activity)))
        }
        v.findViewById<View>(R.id.about_sources_container)?.setOnClickListener { activity.openLinkIfPossible(ANDROID_SOURCE) }
        fillUpdate(activity, v)
    }

    private fun fillUpdate(activity: FragmentActivity, v: View) {
        val button = v.findViewById<MaterialButton>(R.id.rd_update_button) ?: return
        val state = v.findViewById<TextView>(R.id.rd_update_state) ?: return
        var found: RdUpdateCheck.Release? = null
        // (the button keeps the focus while it checks: switched off, a TV's remote lost its place)
        var checking = false
        fun show(text: String, failed: Boolean) {
            state.text = text
            state.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            state.setTextColor(ContextCompat.getColor(activity, if (failed) R.color.rd_accent_red_main else R.color.rd_fg_2))
        }
        button.setOnClickListener {
            found?.let {
                activity.openLinkIfPossible(it.apkUrl)
                return@setOnClickListener
            }
            if (checking) return@setOnClickListener
            checking = true
            show(activity.getString(R.string.rd_update_checking), false)
            activity.lifecycleScope.launch {
                val result = RdUpdateCheck.checkNow(activity)
                checking = false
                if (!v.isAttachedToWindow) return@launch
                when (result) {
                    is RdUpdateCheck.Result.Available -> {
                        found = result.release
                        show(activity.getString(R.string.rd_update_available, result.release.version), false)
                        state.setTextColor(button.currentTextColor)
                        button.setText(R.string.download)
                    }
                    RdUpdateCheck.Result.UpToDate -> show(activity.getString(R.string.rd_update_latest), false)
                    RdUpdateCheck.Result.Offline -> show(activity.getString(R.string.rd_update_offline), true)
                    is RdUpdateCheck.Result.Unexpected -> show(activity.getString(R.string.rd_update_unexpected, result.status.toString()), true)
                }
            }
        }
    }

    /**
     * "Windows 0.6.4 · Linux 0.6.4 · …" from platforms.json (extras/juxtopposed/platforms/render.py of the
     * desktop repository, copied into assets at each release): every available platform but this one
     */
    fun alsoOn(platformsJson: String, self: String = "android"): String {
        val platforms = JSONObject(platformsJson).optJSONArray("platforms") ?: return ""
        val items = mutableListOf<String>()
        for (i in 0 until platforms.length()) {
            val p = platforms.optJSONObject(i) ?: continue
            if (p.optString("id") == self || !p.optBoolean("available")) continue
            val name = p.optString("short")
            if (name.isEmpty()) continue
            val version = if (p.isNull("version")) "" else p.optString("version")
            items += if (version.isEmpty()) name else "$name $version"
        }
        return items.joinToString(" · ")
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    fun ideaUrl(version: String = BuildConfig.RD_VERSION_NAME) =
        "$IDEA_FORM&body=" + encode("\n\n---\nVLC Redesign $version (Android)")

    /** The bug form with the version, the kind of copy, the system and these lines filled in */
    fun reportUrl(environment: List<String>, version: String = BuildConfig.RD_VERSION_NAME,
                  system: String = "Android ${android.os.Build.VERSION.RELEASE}"): String {
        val start = "$REPORT_FORM&version=${encode(version)}&install=${encode(INSTALL_TYPE)}&windows=${encode(system)}"
        var lines = environment
        var url = start + "&environment=" + encode(lines.joinToString("\n"))
        while (url.length > MAX_URL && lines.size > 1) {
            lines = lines.dropLast(1)
            url = start + "&environment=" + encode(lines.joinToString("\n") + "\n(cut)")
        }
        return if (url.length > MAX_URL) start else url
    }

    /** What the desktop's report gives that Android has: versions, device, layout, theme, language */
    fun environment(context: Context): List<String> {
        val settings = Settings.getInstance(context)
        val abi = UiTools.installedAbi(context)
        val tv = Settings.showTvUi
        val theme = when (settings.getString("app_theme", "-1")) {
            "1" -> "Light"
            "2" -> "Dark"
            else -> "Follow the system"
        }
        return listOf(
            "VLC Redesign ${BuildConfig.RD_VERSION_NAME} (Android ${UiTools.abiLabel(abi)})",
            "Based on libVLC ${UiTools.libvlcVersion()}",
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "ABI: $abi",
            "Layout: ${if (tv) "TV" else if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"}",
            "Theme: $theme",
            "Accent: ${settings.getString("rd_accent", "orange")}",
            "Language: ${Locale.getDefault().toLanguageTag()}${if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) ", right to left" else ""}"
        )
    }
}

package org.videolan.vlc.gui.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/** VLC Redesign, About: the "Also on" line and the links of "Suggest an idea" and "Report a problem" (RdAbout) */
class RdAboutTest {

    private val platforms = """
        {"platforms": [
          {"id": "windows", "short": "Windows", "available": true, "version": "0.6.4"},
          {"id": "android", "short": "Android", "available": true, "version": "0.6.5"},
          {"id": "samsung-tv", "short": "Samsung TV", "available": true, "version": "0.6.1"},
          {"id": "ios", "short": "iPhone/iPad", "available": false, "version": null},
          {"id": "nameless", "short": "", "available": true, "version": "1.0"}
        ]}
    """.trimIndent()

    @Test
    fun alsoOnListsTheOtherAvailablePlatforms() {
        assertEquals("Windows 0.6.4 · Samsung TV 0.6.1", RdAbout.alsoOn(platforms))
        assertEquals("Android 0.6.5 · Samsung TV 0.6.1", RdAbout.alsoOn(platforms, self = "windows"))
        assertEquals("", RdAbout.alsoOn("{}"))
    }

    /** the copy in assets is the desktop's site/platforms.json: it must read, and Android is not in its own line */
    @Test
    fun theAssetCopyReads() {
        val asset = listOf("assets/redesign/platforms.json", "application/vlc-android/assets/redesign/platforms.json")
            .map { File(it) }.firstOrNull { it.exists() } ?: return
        val line = RdAbout.alsoOn(asset.readText())
        assertTrue(line, line.contains("Windows"))
        assertFalse(line, line.contains("Android"))
    }

    private fun decoded(url: String) = URLDecoder.decode(url, "UTF-8")

    @Test
    fun ideaLinkNamesTheVersion() {
        val url = RdAbout.ideaUrl("0.6.5")
        assertTrue(url.startsWith("https://github.com/KuchiSofts/vlc-redesign/discussions/new?category=ideas&body="))
        assertTrue(decoded(url).endsWith("VLC Redesign 0.6.5 (Android)"))
        assertFalse("spaces are %20, not +", url.contains("+"))
    }

    @Test
    fun reportLinkFillsTheBugForm() {
        val url = RdAbout.reportUrl(listOf("VLC Redesign 0.6.5 (Android ARM64)", "Theme: Dark"), "0.6.5", "Android 14")
        assertTrue(url.startsWith("https://github.com/KuchiSofts/vlc-redesign/issues/new?template=bug_report.yml&"))
        val text = decoded(url)
        assertTrue(text, text.contains("&version=0.6.5&"))
        assertTrue(text, text.contains("&install=Android app (APK)&"))
        assertTrue(text, text.contains("&windows=Android 14&"))
        assertTrue(text, text.endsWith("&environment=VLC Redesign 0.6.5 (Android ARM64)\nTheme: Dark"))
    }

    /** a TV without a browser shows the link as a QR code: a long report loses its last lines, never the form */
    @Test
    fun longReportIsCut() {
        val lines = (1..200).map { "Line $it with some words in it" }
        val url = RdAbout.reportUrl(lines, "0.6.5", "Android 14")
        assertTrue(url.length <= RdAbout.MAX_URL)
        val text = decoded(url)
        assertTrue(text, text.contains("Line 1 with"))
        assertTrue(text, text.endsWith("(cut)"))
        val huge = RdAbout.reportUrl(listOf("x".repeat(5000)), "0.6.5", "Android 14")
        assertFalse(huge.contains("environment="))
    }
}

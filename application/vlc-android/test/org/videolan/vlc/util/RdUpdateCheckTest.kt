package org.videolan.vlc.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VLC Redesign, the update check: which release counts as newer than the app (isNewer), which release of the list is offered (pick) */
class RdUpdateCheckTest {

    @Test
    fun newerPatchMinorMajor() {
        assertTrue(RdUpdateCheck.isNewer("0.5.1", "0.5.0"))
        assertTrue(RdUpdateCheck.isNewer("0.6.0", "0.5.9"))
        assertTrue(RdUpdateCheck.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun numbersNotText() {
        assertTrue(RdUpdateCheck.isNewer("0.10.0", "0.9.2"))
        assertFalse(RdUpdateCheck.isNewer("0.9.2", "0.10.0"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(RdUpdateCheck.isNewer("0.5.0", "0.5.0"))
        assertFalse(RdUpdateCheck.isNewer("0.4.1", "0.5.0"))
        assertFalse(RdUpdateCheck.isNewer("0.5", "0.5.0"))
    }

    @Test
    fun suffixIgnored() {
        assertFalse(RdUpdateCheck.isNewer("0.5.0-beta", "0.5.0"))
        assertTrue(RdUpdateCheck.isNewer("0.5.1-rc1", "0.5.0"))
    }

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("html_url", "https://example.org/releases/tag/$tag")
            .put("assets", JSONArray().apply {
                for (a in assets) put(JSONObject().put("name", a).put("browser_download_url", "https://example.org/download/$tag/$a"))
            })

    /** 0.6.4 had no APK: the update check must still find 0.6.3's (it read only "latest" before) */
    @Test
    fun desktopOnlyReleaseDoesNotHideTheLastApk() {
        val list = JSONArray()
            .put(release("v0.6.4", "vlc-redesign-0.6.4-win64.zip", "vlc-redesign-0.6.4-macos-arm64.dmg"))
            .put(release("v0.6.3", "vlc-redesign-0.6.3-win64.zip", "vlc-redesign-0.6.3-android.apk"))
            .put(release("v0.6.2", "vlc-redesign-0.6.2-android.apk"))
        val found = RdUpdateCheck.pick(list)
        assertEquals("0.6.3", found?.version)
        assertEquals("https://example.org/download/v0.6.3/vlc-redesign-0.6.3-android.apk", found?.apkUrl)
        assertEquals("https://example.org/releases/tag/v0.6.3", found?.pageUrl)
    }

    @Test
    fun draftsPrereleasesAndOtherApksAreSkipped() {
        val list = JSONArray()
            .put(release("v0.7.0", "vlc-redesign-0.7.0-android.apk", draft = true))
            .put(release("v0.6.9", "vlc-redesign-0.6.9-android.apk", prerelease = true))
            // an APK of another version or name is not this release's app
            .put(release("v0.6.8", "vlc-redesign-0.6.7-android.apk", "vlc-redesign-0.6.8-android-tv.apk"))
            .put(release("v0.6.5", "vlc-redesign-0.6.5-android.apk"))
        assertEquals("0.6.5", RdUpdateCheck.pick(list)?.version)
    }

    @Test
    fun orderOfTheListDoesNotMatter() {
        val list = JSONArray()
            .put(release("v0.6.1", "vlc-redesign-0.6.1-android.apk"))
            .put(release("v0.10.0", "vlc-redesign-0.10.0-android.apk"))
            .put(release("v0.9.2", "vlc-redesign-0.9.2-android.apk"))
        assertEquals("0.10.0", RdUpdateCheck.pick(list)?.version)
    }

    @Test
    fun noApkAnywhereGivesNothing() {
        assertNull(RdUpdateCheck.pick(JSONArray()))
        assertNull(RdUpdateCheck.pick(JSONArray().put(release("v0.6.4", "vlc-redesign-0.6.4-win64.zip"))))
        // broken entries are passed over
        assertNull(RdUpdateCheck.pick(JSONArray().put("text").put(JSONObject().put("tag_name", ""))))
    }
}

package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * VLC Redesign (KuchiSofts/vlc-redesign#73): the online subtitles of a video that isn't on the device (a link, a
 * share, a media server, a provider's file): the name it is searched by (the file's in the address, decoded, without
 * its query; else the title the source gives it), what it is remembered by, and its hash from two parts of it.
 */
class NetworkMediaTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun theNameInTheAddress() {
        assertEquals("Sintel (2010).mp4", OnlineSubtitles.nameInAddress("http://10.0.2.2:8767/films/Sintel%20(2010).mp4"))
        assertEquals("Northern.Bay.S01E02.720p.mkv", OnlineSubtitles.nameInAddress("https://cdn.example.com/tv/Northern.Bay.S01E02.720p.mkv?token=a%2Fb&exp=1#t=10"))
        assertEquals("Sintel.2010.1080p.mp4", OnlineSubtitles.nameInAddress("smb://192.168.1.5/media/Films/Sintel.2010.1080p.mp4"))
        assertEquals("Sintel.mkv", OnlineSubtitles.nameInAddress("nfs://nas/export/Sintel.mkv"))
        assertEquals("23.mp4", OnlineSubtitles.nameInAddress("http://192.168.1.2:8200/MediaItems/23.mp4"))
        // a provider's document: its path inside the last part
        assertEquals("Sintel (2010).mp4", OnlineSubtitles.nameInAddress("content://com.android.externalstorage.documents/document/primary%3AMovies%2FSintel%20(2010).mp4"))
        // a "+" is a plus in a path
        assertEquals("Love+Death.S01E01.mkv", OnlineSubtitles.nameInAddress("http://h/Love+Death.S01E01.mkv"))
        assertNull(OnlineSubtitles.nameInAddress("http://host/"))
        assertNull(OnlineSubtitles.nameInAddress("http://host"))
    }

    @Test
    fun theNameSearched() {
        // the file's name in the address
        assertEquals("Sintel (2010).mp4", OnlineSubtitles.mediaName("http://h/Sintel%20(2010).mp4?x=1", "Sintel (2010).mp4"))
        assertEquals("Sintel.2010.1080p.mp4", OnlineSubtitles.mediaName("smb://nas/share/Sintel.2010.1080p.mp4", null))
        // a media server's number, a stream's word: the title the source gives
        assertEquals("Sintel (2010)", OnlineSubtitles.mediaName("http://192.168.1.2:8200/MediaItems/23.mp4", "Sintel (2010)"))
        assertEquals("Northern Bay S01E02", OnlineSubtitles.mediaName("https://h/watch?v=12", "Northern Bay S01E02"))
        // a title that is only the address: the address's last part
        assertEquals("23.mp4", OnlineSubtitles.mediaName("http://192.168.1.2:8200/MediaItems/23.mp4", "http://192.168.1.2:8200/MediaItems/23.mp4"))
        assertEquals("watch", OnlineSubtitles.mediaName("https://h/watch", ""))
        // read as a file name then
        assertEquals("Sintel", OnlineSubtitles.parseName(OnlineSubtitles.mediaName("http://h/Sintel%20(2010).mp4", null)).title)
        assertEquals(2010, OnlineSubtitles.parseName(OnlineSubtitles.mediaName("http://h/Sintel%20(2010).mp4", null)).year)
        val episode = OnlineSubtitles.parseName(OnlineSubtitles.mediaName("smb://nas/tv/Northern.Bay.S01E02.720p.mkv", null))
        assertEquals("Northern Bay", episode.title)
        assertEquals(1, episode.season)
        assertEquals(2, episode.episode)
    }

    @Test
    fun aFilmWhereverItIs() {
        // a video on the device, on a share, on a media server or behind a link (a length known): subtitles as a film
        assertEquals(false, OnlineSubtitles.isLive("file", 0, false))
        assertEquals(false, OnlineSubtitles.isLive("content", 0, false))
        assertEquals(false, OnlineSubtitles.isLive("http", 129_000, false))
        assertEquals(false, OnlineSubtitles.isLive("smb", 129_000, false))
        assertEquals(false, OnlineSubtitles.isLive("upnp", 129_000, false))
        // a live stream (no length), a Live TV channel (even with one): none
        assertEquals(true, OnlineSubtitles.isLive("http", 0, false))
        assertEquals(true, OnlineSubtitles.isLive("rtsp", -1, false))
        assertEquals(true, OnlineSubtitles.isLive("http", 129_000, true))
    }

    @Test
    fun rememberedByTheAddressWithoutItsQuery() {
        assertEquals("https://cdn.example.com/Sintel.mp4", OnlineSubtitles.addressKey("https://cdn.example.com/Sintel.mp4?token=abc&exp=99"))
        assertEquals("smb://nas/share/Sintel.mp4", OnlineSubtitles.addressKey("smb://nas/share/Sintel.mp4"))
    }

    @Test
    fun hashedFromTwoParts() {
        assertEquals(21697080L, OnlineSubtitles.rangeTotal("bytes 0-65535/21697080"))
        assertEquals(21697080L, OnlineSubtitles.rangeTotal("bytes 21631544-21697079/21697080"))
        assertNull(OnlineSubtitles.rangeTotal("bytes 0-65535/*"))
        assertNull(OnlineSubtitles.rangeTotal(null))
        // the same hash as the file's own, from its first and its last 64 KiB
        val bytes = Random(73).nextBytes(300_000)
        val f = File(dir.root, "video.bin").apply { writeBytes(bytes) }
        val whole = OnlineSubtitles.openSubtitlesHash(f)
        val parts = OnlineSubtitles.hashOf(bytes.copyOfRange(0, 65536), bytes.copyOfRange(bytes.size - 65536, bytes.size), bytes.size.toLong())
        assertEquals(whole, parts)
        assertEquals(300_000L, parts!!.second)
        // too small, or a part short: none
        assertNull(OnlineSubtitles.hashOf(ByteArray(65536), ByteArray(65536), 1000))
        assertNull(OnlineSubtitles.hashOf(ByteArray(100), ByteArray(65536), 300_000))
    }
}

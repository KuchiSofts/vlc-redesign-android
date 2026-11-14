package org.videolan.vlc.gui.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/** VLC Redesign: Export Playlist (RdPlaylistExport): the M3U8 text and the offered file name */
class RdPlaylistExportTest {

    @Test
    fun m3u8Lines() {
        val text = RdPlaylistExport.m3u8(listOf(
            RdPlaylistExport.Entry("file:///storage/emulated/0/Music/My%20Song.mp3", "My Song", "Artist", 215_400),
            RdPlaylistExport.Entry("https://example.org/live.m3u8", "Live", null, 0),
            RdPlaylistExport.Entry("", "nothing", null, 0)))
        assertEquals("#EXTM3U\n" +
                "#EXTINF:215,Artist - My Song\n/storage/emulated/0/Music/My Song.mp3\n" +
                "#EXTINF:-1,Live\nhttps://example.org/live.m3u8\n", text)
    }

    @Test
    fun localPathKeepsItsPlus() {
        assertEquals("/sdcard/C++ talk.mp4", RdPlaylistExport.location("file:///sdcard/C%2B%2B%20talk.mp4"))
        assertEquals("content://media/external/1", RdPlaylistExport.location("content://media/external/1"))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("Road trip.m3u8", RdPlaylistExport.fileName("Road trip"))
        assertEquals("a_b_c.m3u8", RdPlaylistExport.fileName("a/b:c"))
        assertEquals("playlist.m3u8", RdPlaylistExport.fileName("  "))
    }
}

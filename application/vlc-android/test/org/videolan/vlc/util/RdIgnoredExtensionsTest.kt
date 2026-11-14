package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.tools.rdIgnoredExtensions

/** VLC Redesign: Settings › Library › Ignored file extensions (the desktop's ignore-filetypes): the row's text cleaned */
class RdIgnoredExtensionsTest {

    @Test
    fun cleaned() {
        assertEquals("mp4,mkv", rdIgnoredExtensions(" mp4, .MKV "))
        assertEquals("nfo,txt,utf-8", rdIgnoredExtensions("nfo;txt  utf-8,,"))
        assertEquals("srt", rdIgnoredExtensions("srt,SRT,.srt"))
    }

    @Test
    fun nothingOrNonsense() {
        assertEquals("", rdIgnoredExtensions(null))
        assertEquals("", rdIgnoredExtensions(""))
        assertEquals("", rdIgnoredExtensions(" , ; "))
        // (no option text slipped into libvlc's list)
        assertEquals("mp4", rdIgnoredExtensions("mp4,--no-video,a=b"))
    }
}

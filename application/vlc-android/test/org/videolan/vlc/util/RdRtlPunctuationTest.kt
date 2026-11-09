package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VLC Redesign 0.5.9: Hebrew and Arabic lines written for left-to-right players (the final mark first, the dialogue
 * dash last) moved back as the desktop's FixRtlPunctuation does, a film's real lines among them.
 */
class RdRtlPunctuationTest {

    private fun fix(line: String, pre: Boolean = false) = RdRtlPunctuation.fixLine(line, pre)

    @Test
    fun theSureCasesAreMoved() {
        assertEquals("כדי להשיב את הממצאים שנשארו מאחור.", fix(".כדי להשיב את הממצאים שנשארו מאחור"))
        assertEquals("מה אייברסן זומם?", fix("?מה אייברסן זומם"))
        assertEquals("-קדימה.", fix(".קדימה-"))
        assertEquals("<i>שלום!</i>", fix("<i>!שלום</i>"))
        assertEquals("مرحبا؟".replace('؟', '?'), fix("?مرحبا"))
    }

    @Test
    fun threeDotsOnlyInAPreReversedFile() {
        assertEquals("...אני תהיתי", fix("...אני תהיתי"))
        assertEquals("אני תהיתי...", fix("...אני תהיתי", pre = true))
    }

    @Test
    fun aCorrectLineIsLeftAlone() {
        assertEquals("כדי להשיב.", fix("כדי להשיב."))
        assertEquals("- אלבמה -", fix("- אלבמה -"))
        assertEquals("-מה?", fix("-מה?"))
        assertEquals("Hello.", fix("Hello."))
        assertEquals(".Hello", fix(".Hello"))
    }

    @Test
    fun aWholeFile() {
        val reversed = "1\n00:00:01,000 --> 00:00:02,000\n.שלום לכולם\n\n2\n00:00:03,000 --> 00:00:04,000\n?מה שלומך\n\n3\n00:00:05,000 --> 00:00:06,000\n...אני תהיתי\n\n4\n00:00:07,000 --> 00:00:08,000\n!טוב\n"
        val fixed = RdRtlPunctuation.fixSubtitleText(reversed, "srt")
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nשלום לכולם.\n\n2\n00:00:03,000 --> 00:00:04,000\nמה שלומך?\n\n3\n00:00:05,000 --> 00:00:06,000\nאני תהיתי...\n\n4\n00:00:07,000 --> 00:00:08,000\nטוב!\n", fixed)
        // a correct file, an English one, the user's direction forced: unchanged
        assertEquals(fixed, RdRtlPunctuation.fixSubtitleText(fixed, "srt"))
        val english = "1\n00:00:01,000 --> 00:00:02,000\n.Hello\n"
        assertEquals(english, RdRtlPunctuation.fixSubtitleText(english, "srt"))
        assertEquals(reversed, RdRtlPunctuation.fixSubtitleText(reversed, "srt", forcedLtr = true))
    }
}

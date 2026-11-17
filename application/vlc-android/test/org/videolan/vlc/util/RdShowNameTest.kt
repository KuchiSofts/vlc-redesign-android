/*
 * VLC Redesign: RdShowName (a group of episodes shown by its show's name, #108).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RdShowNameTest {
    @Test
    fun episodeMarkersGo() {
        assertEquals("My Show", RdShowName.of("My Show S01E"))
        assertEquals("My Show", RdShowName.of("My Show S0"))
        assertEquals("Another Show", RdShowName.of("Another Show "))
        assertEquals("Other Show", RdShowName.of("Other Show Episode"))
        assertEquals("Show", RdShowName.of("Show 1x0"))
        assertEquals("Show", RdShowName.of("Show - Ep"))
        assertEquals("Show", RdShowName.of("Show.S02E"))
        assertEquals("Show", RdShowName.of("Show ["))
    }

    @Test
    fun otherNamesStay() {
        assertEquals("Titane", RdShowName.of("Titane"))
        assertEquals("Mass Effect", RdShowName.of("Mass Effect"))
        assertEquals("S01E", RdShowName.of("S01E"))
        assertEquals("", RdShowName.of(null))
    }
}

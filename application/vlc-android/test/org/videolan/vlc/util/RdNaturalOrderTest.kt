/*
 * VLC Redesign: RdNaturalOrder (the episodes of a show in their order, #108).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdNaturalOrderTest {
    @Test
    fun numbersAsNumbers() {
        val sorted = listOf("Show Episode 10", "Show Episode 2", "Show Episode 1").sortedWith(RdNaturalOrder)
        assertEquals(listOf("Show Episode 1", "Show Episode 2", "Show Episode 10"), sorted)
    }

    @Test
    fun seasonsThenEpisodes() {
        val sorted = listOf("Show S02E01", "Show S01E10", "Show S01E02").sortedWith(RdNaturalOrder)
        assertEquals(listOf("Show S01E02", "Show S01E10", "Show S02E01"), sorted)
    }

    @Test
    fun leadingZerosAndCase() {
        assertTrue(RdNaturalOrder.compare("show 01", "Show 1") < 0)
        assertTrue(RdNaturalOrder.compare("Show 9", "show 010") < 0)
        assertEquals(0, RdNaturalOrder.compare("Show", "Show"))
        assertTrue(RdNaturalOrder.compare("Show", "Show 1") < 0)
    }
}

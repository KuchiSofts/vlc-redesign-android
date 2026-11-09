package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLC Redesign: what fits a window. A row shows whole cards, a dialog or a menu stays inside the window with its
 * margin, a list opens on the side of its control that has the room.
 */
class RdFitRulesTest {

    @Test
    fun theDesignsRowHoldsFiveVideoCardsAndNoSliverOfASixth() {
        // the design's page: 1920 - 209 (sidebar) - 28 - 20 = 1663 px of row, cards of 316, 20 apart
        val row = RdFitRules.cards(1663, 316, 20)
        assertEquals(5, row.count)
        assertTrue(row.width >= 316)
        // the five cards and their four gaps end at the row's end (less than a card's count of px left over)
        val used = row.count * row.width + (row.count - 1) * 20
        assertTrue(used <= 1663 && 1663 - used < row.count)
    }

    @Test
    fun everyInterfaceSizeEndsOnACardsEdge() {
        // the row in dp at 100, 110, 125, 150, 175 and 200 % of a 1920 x 1080 screen
        for (percent in intArrayOf(100, 110, 125, 150, 175, 200)) for (base in intArrayOf(316, 200)) {
            val available = 1920 * 100 / percent - 209 - 28 - 20
            val row = RdFitRules.cards(available, base, 20)
            val used = row.count * row.width + (row.count - 1) * 20
            assertTrue("$percent %: a card of ${row.width}", row.width >= base && row.width <= base * 135 / 100)
            // the row ends on the last card's edge, unless its cards are at the most they grow
            assertTrue("$percent %: $used of $available", used <= available && (available - used < row.count || row.width == base * 135 / 100))
        }
    }

    @Test
    fun aCardGrowsByAThirdAtMost() {
        // room for almost two cards: one card, a third larger than its design, not one stretched across the row
        val row = RdFitRules.cards(640, 316, 20)
        assertEquals(1, row.count)
        assertEquals(316 * 135 / 100, row.width)
    }

    @Test
    fun theDesignsOwnPageKeepsTheDesignsCards() {
        // 1920 x 1080 at 100 %: the Figma frame's five cards of 316 (the row's 3 px left over shared among them)
        assertEquals(316, RdFitRules.cards(1663, 316, 20).width)
        assertEquals(7, RdFitRules.cards(1663, 200, 20).count)
    }

    @Test
    fun aRowNarrowerThanACardShowsOneCardOfItsWidth() {
        val row = RdFitRules.cards(250, 316, 20)
        assertEquals(1, row.count)
        assertEquals(250, row.width)
        assertEquals(1, RdFitRules.cards(0, 316, 20).count)
    }

    @Test
    fun aSizeIsKeptInsideTheWindowLessItsMargins() {
        assertEquals(1048, RdFitRules.size(1048, 1920, 16))
        // the design's 1048 x 631 dialog at 200 %: 2096 px wanted on a 1920 px screen
        assertEquals(1888, RdFitRules.size(2096, 1920, 16))
        assertEquals(1048, RdFitRules.size(1262, 1080, 16))
        assertEquals(1, RdFitRules.size(500, 20, 16))
    }

    @Test
    fun aPopupIsMovedBackInsideTheWindow() {
        // fits where it is wanted
        assertEquals(100, RdFitRules.place(100, 300, 0, 1920, 8))
        // would pass the far edge: its end at the margin
        assertEquals(1612, RdFitRules.place(1800, 300, 0, 1920, 8))
        // before the near edge
        assertEquals(8, RdFitRules.place(-40, 300, 0, 1920, 8))
        // larger than the room: from the room's start
        assertEquals(8, RdFitRules.place(500, 3000, 0, 1920, 8))
    }

    @Test
    fun aListOpensWhereItHasTheRoom() {
        // under its control when it fits there
        assertEquals(300, RdFitRules.dropHeight(300, 100, 140, 0, 1080, 8))
        // above a control near the bottom, when there is more room above
        assertEquals(-300, RdFitRules.dropHeight(300, 900, 940, 0, 1080, 8))
        // too long for either side: the larger one, cut to it
        assertEquals(532, RdFitRules.dropHeight(2000, 500, 540, 0, 1080, 8))
        assertEquals(-592, RdFitRules.dropHeight(2000, 600, 640, 0, 1080, 8))
    }
}

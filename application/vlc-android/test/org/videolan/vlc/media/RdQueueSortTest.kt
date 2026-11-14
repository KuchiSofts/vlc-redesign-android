package org.videolan.vlc.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** VLC Redesign: the queue's Sort (RdQueueSort): the order of each key and the moves that make it */
class RdQueueSortTest {

    private fun f(title: String? = null, length: Long = 0, artist: String? = null, track: Int = 0, year: Int = 0, url: String? = null) =
        RdQueueSort.Fields(title, length, artist, null, null, null, year, track, 0, url, 0)

    @Test
    fun titleAscendingAndDescending() {
        val items = listOf(f("beta"), f("Alpha"), f("gamma"))
        assertEquals(listOf(1, 0, 2), RdQueueSort.order(items, RdQueueSort.Key.TITLE, false))
        assertEquals(listOf(2, 0, 1), RdQueueSort.order(items, RdQueueSort.Key.TITLE, true))
    }

    @Test
    fun unknownValuesStayLast() {
        val items = listOf(f(length = 0), f(length = 300), f(length = 100))
        assertEquals(listOf(2, 1, 0), RdQueueSort.order(items, RdQueueSort.Key.DURATION, false))
        assertEquals(listOf(1, 2, 0), RdQueueSort.order(items, RdQueueSort.Key.DURATION, true))
        val tracks = listOf(f(track = 3), f(), f(track = 1))
        assertEquals(listOf(2, 0, 1), RdQueueSort.order(tracks, RdQueueSort.Key.TRACK_NUMBER, false))
    }

    @Test
    fun equalItemsKeepTheirOrder() {
        val items = listOf(f("b", artist = "X"), f("a", artist = "X"), f("c", artist = "A"))
        assertEquals(listOf(2, 0, 1), RdQueueSort.order(items, RdQueueSort.Key.ARTIST, false))
    }

    /** the moves applied to the list in turn give the order (as MediaWrapperList.move does them) */
    @Test
    fun movesMakeTheOrder() {
        val order = listOf(3, 0, 4, 1, 2)
        val list = mutableListOf(0, 1, 2, 3, 4)
        for ((from, to) in RdQueueSort.moves(order)) {
            val x = list.removeAt(from)
            list.add(to, x)
        }
        assertEquals(order, list)
        assertEquals(emptyList<Pair<Int, Int>>(), RdQueueSort.moves(listOf(0, 1, 2)))
    }
}

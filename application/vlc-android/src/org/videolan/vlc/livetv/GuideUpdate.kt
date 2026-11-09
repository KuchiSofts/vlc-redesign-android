/*
 * VLC Redesign, Live TV: the rules of a guide's update, the same in every app of the redesign. A guide that fails or
 * comes cut short leaves its channels' programmes as they were (the last whole guide stays); a guide that comes whole
 * replaces them, an empty <tv/> too (its channels have no programmes then); a guide turned off or removed takes its
 * programmes with it. A guide cut short every time is downloaded again a few times, then by age as any other.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

object GuideUpdate {
    /** the downloads in a row that may come cut short before the next one waits for the guide's time to update */
    const val CUT_TRIES = 3

    /** the error a guide that came cut short shows (the last whole one is used) */
    const val CUT = "the guide came cut short"

    /**
     * Whether the channels keep the programmes of the guide's last reading: the new reading failed and has none (a
     * download that failed with no file, a file that doesn't read, an error page). A whole guide, empty or not,
     * replaces them.
     */
    fun keepOld(failed: Boolean, newProgrammes: Int, oldProgrammes: Int): Boolean = failed && newProgrammes == 0 && oldProgrammes > 0

    /**
     * Whether a new download is dropped for the last whole guide ([hasWhole]: it was set aside): it came cut short, or
     * it is no guide that reads ([unread]: an error page, a broken file). With none before, the part that came is
     * better than nothing.
     */
    fun dropNew(cut: Boolean, unread: Boolean, hasWhole: Boolean): Boolean = (cut || unread) && hasWhole

    /** The guides whose downloads came cut short in a row; after [tries] the next one waits for the guide's next update. */
    class CutTries(private val tries: Int = CUT_TRIES) {
        private val count = HashMap<String, Int>()
        private val waitUntil = HashMap<String, Long>()

        /** a download of [url] came cut short at [now]; [intervalMs]: the guides' update interval (0: none) */
        @Synchronized
        fun cut(url: String, now: Long, intervalMs: Long) {
            val n = (count[url] ?: 0) + 1
            if (n >= tries) {
                count.remove(url)
                if (intervalMs > 0) waitUntil[url] = now + intervalMs
            } else count[url] = n
        }

        /** a download of [url] came whole: its count starts again */
        @Synchronized
        fun whole(url: String) {
            count.remove(url)
            waitUntil.remove(url)
        }

        /** whether the update by age may download [url] at [now] (a pressed Update always does) */
        @Synchronized
        fun mayTry(url: String, now: Long): Boolean {
            val until = waitUntil[url] ?: return true
            if (now < until) return false
            waitUntil.remove(url)
            return true
        }
    }
}

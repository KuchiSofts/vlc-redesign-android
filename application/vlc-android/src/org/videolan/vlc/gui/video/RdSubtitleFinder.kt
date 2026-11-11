/*
 * VLC Redesign: the online subtitles of the video playing, as the desktop's SubtitleFinder keeps them for its
 * Subtitles panel: identifying the video, searching the services, what was found (kept while the video plays, the
 * panel closed and opened again), the title the user gave instead, and the subtitle being downloaded. The automatic
 * search of the player gives its results here too, so the panel shows them at once.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.Settings
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.OnlineSubtitles.Problem

class RdSubtitleFinder(private val activity: VideoPlayerActivity) {

    enum class State { IDLE, IDENTIFYING, SEARCHING, READY, NOT_FOUND, FAILED }

    var state = State.IDLE
        private set
    var outcome: OnlineSubtitles.Outcome? = null
        private set
    /** the problem of a search that found nothing (null: the title, its absence, said by the panel) */
    var problem: Problem? = null
        private set
    /** the id of the subtitle being downloaded */
    var loadingId: String? = null
        private set
    var titleBusy = false
        private set
    var titleResults: List<Pair<OnlineSubtitles.Match, String>> = emptyList()
        private set
    /** a title searched for that wasn't found */
    var titleNotFound: String? = null
        private set
    /** the ids of the results downloaded and added to the video (shown with its tracks, not again in the results) */
    val loadedIds = HashSet<String>()

    private var uri: Uri? = null
    private var media: OnlineSubtitles.Media? = null
    private var job: Job? = null
    private var titleJob: Job? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.toList().forEach { it() }

    /** the video playing: another one forgets what was found */
    private fun follow(): Uri? {
        val now = activity.service?.currentMediaWrapper?.uri
        if (now != uri) {
            job?.cancel()
            titleJob?.cancel()
            uri = now
            media = null
            outcome = null
            problem = null
            loadingId = null
            titleBusy = false
            titleResults = emptyList()
            titleNotFound = null
            loadedIds.clear()
            state = State.IDLE
        }
        return now
    }

    /** the state for the video playing now */
    fun current(): State {
        follow()
        return state
    }

    /** whether the services are asked by themselves (Settings › Subtitles, the welcome's privacy step) */
    fun searchesByItself() = Settings.getInstance(activity).getBoolean(OnlineSubtitles.KEY_AUTO, false)

    fun busy() = state == State.IDENTIFYING || state == State.SEARCHING

    /** what the automatic search found for [forUri] */
    fun adopt(forUri: Uri, m: OnlineSubtitles.Media, found: OnlineSubtitles.Outcome) {
        if (follow() != forUri || busy()) return
        media = m
        show(found)
    }

    /** identifies the video (unless [match] is the title the user gave) and asks the services */
    fun search(match: OnlineSubtitles.Match? = null) {
        val forUri = follow() ?: return
        val mw = activity.service?.currentMediaWrapper ?: return
        val ctx = activity.applicationContext
        job?.cancel()
        state = State.IDENTIFYING
        problem = null
        changed()
        job = activity.lifecycleScope.launch {
            val m = media ?: withContext(Dispatchers.IO) { OnlineSubtitles.mediaOf(forUri, mw.title, context = ctx) }.also { media = it }
            val found = match ?: withContext(Dispatchers.IO) { OnlineSubtitles.identify(ctx, m) }
            if (uri != forUri) return@launch
            state = State.SEARCHING
            changed()
            val out = OnlineSubtitles.search(ctx, m, found)
            if (uri != forUri) return@launch
            show(out)
        }
    }

    private fun show(out: OnlineSubtitles.Outcome) {
        outcome = out
        state = when {
            out.results.isNotEmpty() -> State.READY
            !out.anyAnswered -> State.FAILED
            else -> State.NOT_FOUND
        }
        problem = if (!out.anyAnswered) out.error ?: Problem.Offline else null
        changed()
    }

    /** "The video is another one": the titles of that name */
    fun findTitle(text: String) {
        if (text.isBlank()) return
        follow()
        titleJob?.cancel()
        titleBusy = true
        titleResults = emptyList()
        titleNotFound = null
        changed()
        titleJob = activity.lifecycleScope.launch {
            val titles = withContext(Dispatchers.IO) { OnlineSubtitles.findTitles(text) }
            titleBusy = false
            titleResults = titles
            titleNotFound = if (titles.isEmpty()) text.trim() else null
            changed()
        }
    }

    /** the title chosen: kept for the file, the services asked for it */
    fun useTitle(index: Int) {
        val match = titleResults.getOrNull(index)?.first ?: return
        val ctx = activity.applicationContext
        titleResults = emptyList()
        titleNotFound = null
        val forUri = follow() ?: return
        val mw = activity.service?.currentMediaWrapper ?: return
        activity.lifecycleScope.launch {
            val m = media ?: withContext(Dispatchers.IO) { OnlineSubtitles.mediaOf(forUri, mw.title, context = ctx) }.also { media = it }
            OnlineSubtitles.rememberMatch(ctx, m, match)
            search(match)
        }
    }

    /**
     * Downloads a result, adds it to the video and shows it (kept for the file); [done] gets the problem when it
     * could not be loaded.
     */
    fun load(result: OnlineSubtitles.Result, done: (Problem?) -> Unit) {
        val forUri = follow() ?: return
        val m = media ?: outcome?.media ?: return
        if (loadingId != null) return
        val ctx = activity.applicationContext
        loadingId = result.id
        changed()
        activity.lifecycleScope.launch {
            val (file, problem) = OnlineSubtitles.download(ctx, result, m)
            loadingId = null
            if (uri != forUri) return@launch
            if (file == null) {
                changed()
                done(problem ?: Problem.Unpack)
                return@launch
            }
            loadedIds.add(result.id)
            activity.rdAddOnlineSubtitle(file, result, select = true)
            changed()
            done(null)
        }
    }

    /** a result was downloaded and added by the automatic search */
    fun markLoaded(id: String) {
        loadedIds.add(id)
        changed()
    }
}

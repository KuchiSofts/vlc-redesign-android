/*
 * VLC Redesign: the choices of the player's Subtitles panel, as the desktop's SubtitleTracksPanel makes them: the
 * languages on the left (the user's first and second, then the ones they pick, then the media's own, then the
 * others by name, "Unknown language" last; each with how many subtitles it has), the subtitles of the language shown
 * on the right (the media's own tracks, then those found online that are not loaded yet, in the order of the search:
 * those made for this very file first).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import java.util.Locale
import kotlin.math.roundToInt

object RdSubtitleChoices {

    /** where a track of the media comes from, as said under it */
    enum class Kind { VIDEO, FILE, ONLINE, OPENED }

    /** [name]: the file's name (a file next to the video, a file opened); [provider]: the service it came from;
     *  [language]: the language it was added as ("" unknown) */
    data class Source(val kind: Kind, val name: String = "", val provider: String = "", val language: String = "")

    /** a subtitle track of the media: its id for the player, its name, its language ("" unknown), shown or not */
    data class Local(val id: String, val title: String, val language: String, val checked: Boolean, val source: Source)

    /** a subtitle found online, not loaded yet */
    data class Online(val id: String, val release: String, val language: String, val provider: String, val downloads: Int = 0,
                      val exact: Boolean = false, val hearingImpaired: Boolean = false, val foreignOnly: Boolean = false)

    /** a language of the left column: [wanted] 0 the user's first, 1 the second, -1 another */
    data class Language(val code: String, val count: Int, val wanted: Int)

    /** a row of the right column */
    sealed class Variant {
        data class Track(val local: Local) : Variant()
        data class Result(val online: Online) : Variant()
    }

    /**
     * The languages, the desktop's order: the user's first and second, then the ones they picked before ([picked],
     * the latest first), then the media's own, then the others by [name], the unknown one ("") last.
     */
    fun languages(locals: List<Local>, online: List<Online>, wanted: List<String>, picked: List<String>,
                  name: (String) -> String): List<Language> {
        val counts = LinkedHashMap<String, Int>()
        for (t in locals) counts[t.language] = (counts[t.language] ?: 0) + 1
        for (r in online) counts[r.language] = (counts[r.language] ?: 0) + 1
        val want = wanted.filter { it.isNotEmpty() }.distinct()
        fun rank(code: String): Int {
            val w = want.indexOf(code)
            return when {
                w >= 0 -> w
                code.isEmpty() -> 1000
                code in picked -> 5
                locals.any { it.language == code } -> 10
                else -> 20
            }
        }
        val usedOrder = { code: String -> picked.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it } }
        return counts.entries.map { Language(it.key, it.value, want.indexOf(it.key)) }
            .sortedWith(compareBy<Language>({ rank(it.code) }, { usedOrder(it.code) }, { name(it.code).lowercase(Locale.ROOT) }))
    }

    /**
     * The language shown on the right: the one chosen (when it still has subtitles), else the one of the track shown,
     * else the user's, else the first; null when there is none.
     */
    fun shownLanguage(chosen: String?, languages: List<Language>, locals: List<Local>): String? {
        if (chosen != null && languages.any { it.code == chosen }) return chosen
        locals.firstOrNull { it.checked }?.let { return it.language }
        return (languages.firstOrNull { it.wanted >= 0 } ?: languages.firstOrNull())?.code
    }

    /** the subtitles of [language]: the media's own tracks first, then those found online */
    fun variants(language: String?, locals: List<Local>, online: List<Online>): List<Variant> {
        if (language == null) return emptyList()
        return locals.filter { it.language == language }.map { Variant.Track(it) } +
            online.filter { it.language == language }.map { Variant.Result(it) }
    }

    /** the best of a language picked on the left: its first track of the media, else its first found online */
    fun bestOf(language: String, locals: List<Local>, online: List<Online>): Variant? =
        locals.firstOrNull { it.language == language }?.let { Variant.Track(it) }
            ?: online.firstOrNull { it.language == language }?.let { Variant.Result(it) }

    /** "51.8" for 51 760 downloads (shown "51.8k downloads"), "243" for 243; null for none */
    fun downloadsCount(count: Int): Pair<String, Boolean>? = when {
        count <= 0 -> null
        count >= 1000 -> {
            val tenths = (count / 100.0).roundToInt()
            Pair(if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}", true)
        }
        else -> Pair(count.toString(), false)
    }

    // "Track 2 - [Hebrew]": the language in its name (every bracket escaped: Android's ICU)
    private val bracketRe = Regex("""\[([^\]]+)\]""")

    /** a track's language: the player's code of it, else the language in its name, else the one of its source */
    fun languageOf(code: String?, title: String, sourceLanguage: String = ""): String {
        OnlineSubtitles.normalize(code).takeIf { it.isNotEmpty() && it != "und" }?.let { return it }
        bracketRe.find(title)?.groupValues?.get(1)?.let { OnlineSubtitles.fromEnglishName(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return OnlineSubtitles.normalize(sourceLanguage)
    }

    /**
     * The sources of the tracks: [known] the ones of the tracks seen before (by id), [pending] the files added since,
     * in the order they were added: each becomes the next new track of its language (a track of another language
     * that appears meanwhile is the video's own); a new track with none pending is the video's own, or a file next
     * to it when [sidecars] (the files beside the video: name, language) has one of its language left.
     */
    fun attribute(ids: List<String>, languages: Map<String, String>, known: Map<String, Source>, pending: MutableList<Source>,
                  sidecars: MutableList<Pair<String, String>>): Map<String, Source> {
        val out = LinkedHashMap<String, Source>()
        for (id in ids) {
            known[id]?.let { out[id] = it; continue }
            val language = languages[id] ?: ""
            val added = pending.indexOfFirst { it.language.isEmpty() || language.isEmpty() || it.language == language }
            if (added >= 0) {
                out[id] = pending.removeAt(added)
                continue
            }
            val beside = sidecars.indexOfFirst { language.isNotEmpty() && it.second == language }
            out[id] = if (beside >= 0) Source(Kind.FILE, sidecars.removeAt(beside).first, language = language) else Source(Kind.VIDEO)
        }
        return out
    }
}

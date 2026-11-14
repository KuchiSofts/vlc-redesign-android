/*
 * VLC Redesign: the order of every list of languages or countries (Settings' languages, the subtitles', the Live TV
 * catalog's filters, the guide's languages): what the device is set to first, its languages in its own order and
 * its country, then every other one from A to Z in the interface's language. A list opens on the value chosen, else
 * on the first of the device's. The device's country is where its time zone is (a phone in Israel set to "English
 * (United States)" is in Israel), else the first of its languages that names one.
 *
 * Plain Kotlin (the JVM tests): what the device says comes from RdDeviceLocale.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import java.text.Collator
import java.util.Locale

object RdLocaleChoices {

    /** A row of a list: its value, its words, and whether the device is set to it. */
    data class Choice(val value: String, val label: String, val detected: Boolean = false)

    /** "iw-IL", "he_IL", "he-rIL" -> ("he", "IL"); the older codes Java keeps (iw, in, ji) as today's */
    fun split(tag: String?): Pair<String, String> {
        val parts = (tag ?: "").trim().replace('_', '-').replace("-r", "-").split('-').filter { it.isNotEmpty() }
        val language = when (val l = parts.getOrNull(0)?.lowercase(Locale.ROOT) ?: "") { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> l }
        // (a script, "zh-Hant-TW": the region is the part of two letters or three digits)
        val region = parts.drop(1).firstOrNull { it.length == 2 && it.all { c -> c.isLetter() } || it.length == 3 && it.all { c -> c.isDigit() } }
            ?.uppercase(Locale.ROOT) ?: ""
        return language to region
    }

    /** A country code of two letters ("il" -> "IL"), else "" ("001", the world, is no country). */
    fun country(code: String?): String {
        val c = (code ?: "").trim().uppercase(Locale.ROOT)
        return if (c.length == 2 && c.all { it in 'A'..'Z' }) c else ""
    }

    /** The device's country: its time zone's, else the first of its languages that names one ("" when none does). */
    fun deviceCountry(zoneRegion: String?, locales: List<String>): String =
        country(zoneRegion).ifEmpty { locales.map { country(split(it).second) }.firstOrNull { it.isNotEmpty() } ?: "" }

    /**
     * The value of [values] the device's [tag] is: the same language and region ("pt-BR"), else the language alone
     * ("en" for "en-US"), else the first of the same language ("zh-CN" for "zh-SG"); null when none is.
     */
    fun match(values: Collection<String>, tag: String): String? {
        val (language, region) = split(tag)
        if (language.isEmpty()) return null
        val same = values.filter { it.isNotEmpty() && split(it).first == language }
        return same.firstOrNull { region.isNotEmpty() && split(it).second == region }
            ?: same.firstOrNull { split(it).second.isEmpty() }
            ?: same.firstOrNull()
    }

    /** The values of [values] the device's languages [tags] are, in the device's order, each once. */
    fun detectedLanguages(values: Collection<String>, tags: List<String>): List<String> =
        tags.mapNotNull { match(values, it) }.distinct()

    /** The values of [values] that are the country [country] ("il" in a list of "IL"...). */
    fun detectedCountries(values: Collection<String>, country: String): List<String> {
        val c = country(country)
        return if (c.isEmpty()) emptyList() else values.filter { country(it) == c }.take(1)
    }

    /**
     * The list as a picker shows it: [head] first as it is (All, None, Device default), then the [items] the device
     * is set to ([detected], in that order), then the others by their words in [ui]'s order.
     */
    fun order(head: List<Choice>, items: List<Pair<String, String>>, detected: List<String>, ui: Locale = Locale.getDefault()): List<Choice> {
        val collator = Collator.getInstance(ui).apply { strength = Collator.SECONDARY }
        val byValue = LinkedHashMap<String, String>()
        for ((value, label) in items) if (value !in byValue && head.none { it.value == value }) byValue[value] = label
        val first = detected.distinct().filter { it in byValue }.map { Choice(it, byValue.getValue(it), true) }
        val rest = byValue.filterKeys { k -> first.none { it.value == k } }.map { (v, l) -> Choice(v, l) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        return head + first + rest
    }

    /** A language list as Settings shows it: its first row (no preference) as it is, each language once, the device's
     *  first ([deviceTags], RdDeviceLocale), then the others in the interface's order (Settings, the welcome's step) */
    fun rows(values: List<String>, names: List<String>, deviceTags: List<String>, ui: Locale = Locale.getDefault()): List<Choice> {
        if (values.isEmpty()) return emptyList()
        val head = listOf(Choice(values[0], names[0]))
        val seen = HashSet<Pair<String, String>>()
        val items = (1 until values.size).filter { seen.add(split(values[it])) }.map { values[it] to names[it] }
        return order(head, items, detectedLanguages(items.map { it.first }, deviceTags), ui)
    }

    /** Where a list opens: the value chosen (when it is in it), else the first of the device's, else the first row. */
    fun openAt(choices: List<Choice>, current: String?, preferDetected: Boolean = false): Int {
        val chosen = if (current.isNullOrEmpty() && preferDetected) -1 else choices.indexOfFirst { it.value == (current ?: "") }
        if (chosen >= 0) return chosen
        val detected = choices.indexOfFirst { it.detected }
        return if (detected >= 0) detected else 0
    }
}

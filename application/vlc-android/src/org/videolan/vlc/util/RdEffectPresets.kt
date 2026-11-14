/*
 * VLC Redesign: the named presets of the Effects window, as the desktop's (EffectPresets.qml): the values of the
 * effects (RdEffects: every "rd_fx_" setting, audio and video) kept under a name, saved, applied, updated, renamed
 * and deleted. The equalizer keeps its own presets (its panel). Kept in the settings as JSON: [{"name", "values"}],
 * in the order they were made; a value is the setting as it was (on/off, a number, a choice), a setting left out is
 * at its default.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import org.json.JSONArray
import org.json.JSONObject

object RdEffectPresets {
    const val KEY = "rd_fx_presets"
    /** the preset the window shows as chosen (its name), "" for none */
    const val KEY_CHOSEN = "rd_fx_preset_chosen"
    const val PREFIX = "rd_fx_"
    /** the longest name kept (a longer one is cut), as the desktop's */
    const val NAME_LENGTH = 120

    class Preset(val name: String, val values: Map<String, Any>)

    /** why a name can't be given, null when it can: [except] is the preset renamed (its own name is fine) */
    enum class NameError { EMPTY, DEFAULT, TAKEN }

    /** one line, whatever was pasted, cut to [NAME_LENGTH] */
    fun normalName(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(NAME_LENGTH)

    fun nameError(presets: List<Preset>, text: String, defaultName: String, except: String? = null): NameError? {
        val name = normalName(text)
        if (name.isEmpty()) return NameError.EMPTY
        if (name.equals(defaultName, ignoreCase = true)) return NameError.DEFAULT
        if (presets.any { it.name.equals(name, ignoreCase = true) && it.name != except }) return NameError.TAKEN
        return null
    }

    /** the effects' own settings among all the app's (not the presets themselves) */
    fun isEffect(key: String) = key.startsWith(PREFIX) && key != KEY && key != KEY_CHOSEN

    /** the effects as they are now: every effect setting that is set */
    fun snapshot(all: Map<String, *>): Map<String, Any> =
        all.filterKeys { isEffect(it) }.mapNotNull { (k, v) ->
            when (v) {
                is Boolean, is Int, is Long, is Float, is String -> k to v
                else -> null
            }
        }.toMap()

    fun parse(json: String?): List<Preset> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Preset>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = normalName(o.optString("name"))
            if (name.isEmpty() || out.any { it.name.equals(name, ignoreCase = true) }) continue
            val values = LinkedHashMap<String, Any>()
            o.optJSONObject("values")?.let { v ->
                for (k in v.keys()) {
                    if (!isEffect(k)) continue
                    when (val x = v.get(k)) {
                        is Boolean, is String -> values[k] = x
                        // (numbers come back as Integer or Double: the settings keep floats)
                        is Number -> values[k] = x.toFloat()
                    }
                }
            }
            out.add(Preset(name, values))
        }
        return out
    }

    fun serialize(presets: List<Preset>): String = JSONArray().apply {
        for (p in presets) put(JSONObject().put("name", p.name).put("values", JSONObject().apply {
            for ((k, v) in p.values) put(k, if (v is Int || v is Long) (v as Number).toFloat() else v)
        }))
    }.toString()

    /** "Custom Preset 01", the first free one ([format] puts the number in the language's words) */
    fun freeName(presets: List<Preset>, format: (String) -> String): String {
        var i = 1
        while (true) {
            val name = format(if (i < 10) "0$i" else "$i")
            if (presets.none { it.name.equals(name, ignoreCase = true) }) return name
            i++
        }
    }
}

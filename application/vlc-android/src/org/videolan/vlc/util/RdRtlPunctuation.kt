/*
 * VLC Redesign: Hebrew and Arabic subtitle lines written for players that lay every line out left to right (the
 * desktop's FixRtlPunctuation in modules/codec/subsdec.c, the Samsung TV app's fixRtlPunctuation in subs.js). Many
 * sites' files (Wizdom's among them) have the final mark first and the dialogue dash last (".כדי להשיב", "?מה-"):
 * laid out right to left, as Android lays them, the marks show at the wrong end. They are moved back, line by line.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

object RdRtlPunctuation {

    private const val MARKS = ".,!?:;…"
    private const val DASHES = "-–—"

    /** a letter of a right-to-left script: Hebrew, Arabic, Syriac, Thaana, N'Ko... and their presentation forms */
    fun isRtlLine(line: String) = line.any { c ->
        c.code in 0x0590..0x08FF || c.code in 0xFB1D..0xFDFF || c.code in 0xFE70..0xFEFC
    }

    /** a line split as it is laid out: tags and spaces before, the text, tags and spaces after */
    private class Parts(val lead: String, val core: String, val tail: String)

    private fun split(line: String): Parts {
        var start = 0
        var end = line.length
        // leading tags ("<i>", "{\an8}") and spaces
        while (start < end) {
            val c = line[start]
            if (c == ' ' || c == '\t') { start++; continue }
            if (c == '<' || c == '{') {
                val close = line.indexOf(if (c == '<') '>' else '}', start)
                if (close in start until end) { start = close + 1; continue }
            }
            break
        }
        // trailing tags and spaces
        while (end > start) {
            val c = line[end - 1]
            if (c == ' ' || c == '\t' || c == '\r') { end--; continue }
            if (c == '>' || c == '}') {
                val open = line.lastIndexOf(if (c == '>') '<' else '{', end - 1)
                if (open >= start) { end = open; continue }
            }
            break
        }
        return Parts(line.substring(0, start), line.substring(start, end), line.substring(end))
    }

    private fun leadingMarks(core: String) = core.takeWhile { it in MARKS }
    private fun trailingDashes(core: String) = core.takeLastWhile { it in DASHES }

    /** the lines of a whole file "pre-reversed": at least 3 right-to-left lines start with a mark, and more than twice
     *  as many start with one as end with one */
    fun isPreReversed(lines: List<String>): Boolean {
        var starts = 0
        var ends = 0
        for (line in lines) {
            if (!isRtlLine(line)) continue
            val core = split(line).core
            if (core.isEmpty()) continue
            if (core[0] in MARKS) starts++
            if (core.last() in MARKS) ends++
        }
        return starts >= 3 && starts > 2 * ends
    }

    /** one line, fixed when it is one of the reversed kind (unchanged otherwise) */
    fun fixLine(line: String, preReversed: Boolean): String {
        if (!isRtlLine(line)) return line
        val p = split(line)
        val core = p.core
        if (core.isEmpty() || core[0] in DASHES) return line
        val marks = leadingMarks(core)
        val dashes = trailingDashes(core.substring(marks.length))
        val body = core.substring(marks.length, core.length - dashes.length)
        if (marks.isEmpty() && dashes.isEmpty()) return line
        if (body.isEmpty() || body.trimEnd().lastOrNull()?.let { it in ".,!?:;" } == true) return line
        val sure = marks.isNotEmpty() && (marks.length <= 2 || marks.any { it != '.' })
        if (!sure && !preReversed) return line
        return p.lead + dashes.reversed() + body + marks.reversed() + p.tail
    }

    /**
     * An SRT or WebVTT file's text lines fixed (the numbers, the times and the blank lines as they are); [forcedLtr]:
     * the user's text direction forced to left to right, nothing changed.
     */
    fun fixSubtitleText(text: String, extension: String, forcedLtr: Boolean = false): String {
        if (forcedLtr || extension.lowercase(java.util.Locale.ROOT) !in setOf("srt", "vtt")) return text
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split("\r\n", "\n")
        val textLines = lines.filter { isTextLine(it) }
        if (textLines.none { isRtlLine(it) }) return text
        val pre = isPreReversed(textLines)
        return lines.joinToString(newline) { if (isTextLine(it)) fixLine(it, pre) else it }
    }

    /** a line of a cue's text: not its number, its times, a blank or the WebVTT header */
    private fun isTextLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || "-->" in t || t.all { it.isDigit() }) return false
        if (t.startsWith("WEBVTT") || t.startsWith("NOTE") || t.startsWith("STYLE")) return false
        return true
    }
}

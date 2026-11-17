/*
 * VLC Redesign: titles in their natural order, the numbers in them as numbers: "Episode 2" before "Episode 10"
 * (the library's order puts 10 first), the rest without case (#108; the desktop's naturalCompare, mlhelper.cpp).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

object RdNaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                // a run of digits each: the shorter number (without its leading zeros) is the smaller, then digit
                // by digit
                var si = i
                var sj = j
                while (si < a.length && a[si] == '0') si++
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                var ej = sj
                while (ei < a.length && a[ei].isDigit()) ei++
                while (ej < b.length && b[ej].isDigit()) ej++
                if (ei - si != ej - sj) return if (ei - si < ej - sj) -1 else 1
                val digits = a.substring(si, ei).compareTo(b.substring(sj, ej))
                if (digits != 0) return digits
                i = ei
                j = ej
                continue
            }
            val ca = a[i].lowercaseChar()
            val cb = b[j].lowercaseChar()
            if (ca != cb) return if (ca < cb) -1 else 1
            i++
            j++
        }
        if (i < a.length || j < b.length) return if (i < a.length) 1 else -1
        return a.compareTo(b, ignoreCase = true)
    }
}

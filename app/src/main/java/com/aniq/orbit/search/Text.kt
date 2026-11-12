package com.aniq.orbit.search

import java.text.Normalizer

object Text {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, accents removed, punctuation turned into single spaces. */
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
        return n.lowercase().replace(nonWord, " ").trim()
    }

    /** First letter of each word, plus capitals inside words ("YouTube Music" -> "ytm"). */
    fun initials(label: String): String {
        val sb = StringBuilder()
        var prevLetter = false
        for (c in label) {
            if (c.isLetterOrDigit()) {
                if (!prevLetter || c.isUpperCase()) sb.append(c.lowercaseChar())
                prevLetter = true
            } else {
                prevLetter = false
            }
        }
        return sb.toString()
    }
}

object Matcher {
    /** Higher is better; -1 means no match. `q` and `target` must already be normalized. */
    fun score(q: String, target: String, initials: String): Int {
        if (q.isEmpty() || target.isEmpty()) return -1
        if (target == q) return 1000
        if (target.startsWith(q)) return 900 - (target.length - q.length).coerceAtMost(100)
        val word = target.indexOf(" $q")
        if (word >= 0) return 800 - word.coerceAtMost(100)
        if (q.length >= 2 && initials.startsWith(q)) return 760
        val sub = target.indexOf(q)
        if (sub >= 0) return 600 - sub.coerceAtMost(100)
        if (q.length < 2) return -1
        // Letters in order with gaps ("smnts" -> "samsung notes").
        var from = 0
        var last = -1
        var gaps = 0
        for (c in q) {
            if (c == ' ') continue
            val f = target.indexOf(c, from)
            if (f < 0) return -1
            if (last >= 0) gaps += f - last - 1
            last = f
            from = f + 1
        }
        return (320 - gaps * 12).coerceAtLeast(40)
    }
}

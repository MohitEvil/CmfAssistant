package com.demon.cmfassistant.intent

/**
 * Cleans up what speech recognition hears so the parsers see one spelling for each thing:
 * "wi-fi", "wi fi", "why fi" -> "wifi", "d p i" -> "dpi", "developer option" -> "developer options", "four four four" -> "444".
 */
object Canon {
    private class Rule(val rx: Regex, val to: String)

    private fun r(p: String, to: String) = Rule(Regex(p), to)

    private val rules = listOf(
        r("\\b(?:wi ?fi|why ?fi|wy ?fi|wife ?i|wifey|wi ?fy|y ?fi|vi ?fi|wai ?fai|why fie|wi fie)\\b", "wifi"),
        r("\\b(?:blue ?tooth|blu ?tooth|blue tooth|bluetooh|blutooth)\\b", "bluetooth"),
        r("\\bhot ?spot\\b", "hotspot"),
        r("\\b(?:air ?plane|aero ?plane) mode\\b", "airplane mode"),
        r("\\b(?:air ?plane|aero ?plane)\\b", "airplane"),
        r("\\bd ?p ?i\\b", "dpi"),
        r("\\bn ?f ?c\\b", "nfc"),
        r("\\bg ?p ?s\\b", "gps"),
        r("\\bu ?s ?b\\b", "usb"),
        r("\\ba ?d ?b\\b", "adb"),
        r("\\bv ?p ?n\\b", "vpn"),
        r("\\b(?:developers?|develop|dev|developer's) (?:options?|mode|settings?|menu|tools?)\\b", "developer options"),
        r("\\bdeveloper\\b(?! options)", "developer options"),
        r("\\b(?:whats ?app|what ?s app|watsapp|whatsap|what's up app)\\b", "whatsapp"),
        r("\\byou ?tube\\b", "youtube"),
        r("\\bface ?book\\b", "facebook"),
        r("\\binsta ?gram\\b", "instagram"),
        r("\\bplay ?store\\b", "play store"),
        r("\\bsmall(?:est)? (?:with|width)\\b", "smallest width"),
        r("\\b(?:screen|display) (?:density|dpi)\\b", "dpi"),
        r("\\bdo not disturb\\b|\\bdont disturb\\b", "do not disturb")
    )

    fun phrase(text: String): String {
        var s = text.lowercase().replace("’", "").replace("'", "").replace('-', ' ')
        for (rule in rules) s = rule.rx.replace(s, rule.to)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    // ---- Spoken numbers ----

    private val ones = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4, "for" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val strictOnes = ones.filterKeys { it !in setOf("to", "too", "for", "oh") }
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /** Turns runs of spoken numbers into digits: "four four four" and "four forty four" both become 444. */
    fun digits(text: String): String {
        val tokens = text.split(" ")
        val out = ArrayList<String>()
        var i = 0
        fun value(t: String): Int? = t.toIntOrNull() ?: strictOnes[t] ?: tens[t]
        while (i < tokens.size) {
            val t = tokens[i]
            if (value(t) == null && t != "hundred" && t != "thousand") { out.add(t); i++; continue }
            var j = i
            val run = ArrayList<String>()
            while (j < tokens.size) {
                val u = tokens[j]
                if (value(u) != null || u == "hundred" || u == "thousand" || (u == "and" && run.isNotEmpty() && j + 1 < tokens.size && value(tokens[j + 1]) != null)) {
                    run.add(u); j++
                } else break
            }
            out.add(runToNumber(run.filter { it != "and" }))
            i = j
        }
        return out.joinToString(" ")
    }

    private fun runToNumber(run: List<String>): String {
        if (run.size == 1) return (run[0].toIntOrNull() ?: strictOnes[run[0]] ?: tens[run[0]] ?: 0).toString().let { if (run[0] == "hundred") "100" else if (run[0] == "thousand") "1000" else it }
        fun v(t: String): Int = t.toIntOrNull() ?: strictOnes[t] ?: tens[t] ?: 0
        val simple = run.all { it.toIntOrNull() != null || (strictOnes[it] ?: 99) in 0..9 }
        if (simple) return run.joinToString("") { v(it).toString() }
        if (run.contains("hundred") || run.contains("thousand")) {
            var total = 0
            var cur = 0
            for (t in run) when (t) {
                "hundred" -> cur = (if (cur == 0) 1 else cur) * 100
                "thousand" -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                else -> cur += v(t)
            }
            return (total + cur).toString()
        }
        // "four forty four" -> 4 then 44
        if (run.size >= 2 && (strictOnes[run[0]] ?: 99) in 1..9 && tens.containsKey(run[1])) {
            val rest = run.drop(1).sumOf { v(it) }
            return run[0].let { v(it).toString() } + rest.toString()
        }
        return run.sumOf { v(it) }.toString()
    }
}

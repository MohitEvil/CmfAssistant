package com.demon.cmfassistant.intent

/** "wait 10 seconds", "wait a few seconds", "pause for 2 minutes", "send the last photo to Demon on WhatsApp". */
object WaitPhrases {
    private val I = RegexOption.IGNORE_CASE
    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "sixty" to 60, "half" to 30)

    private val timed = Regex("^(?:wait|pause|sleep|hold on|hang on|delay)(?: for)?(?: about| around| another| (?:for )?like)?\\s+(\\d+(?:\\.\\d+)?|[a-z]+)(?:\\s+(?:and a half|more))?\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?)\\b.*$", I)
    private val vague = Regex("^(?:wait|pause|hold on|hang on)(?: for)?(?: (?:a|some|few|a few|several))?(?: (?:second|seconds|moment|minute|while|bit|sec|time))?(?: and then)?$", I)

    fun seconds(s: String): Int? {
        val t = s.trim().trimEnd('.', '!', '?', ',')
        val m = timed.find(t)
        if (m != null) {
            val raw = m.groupValues[1].lowercase()
            val n: Double? = raw.toDoubleOrNull() ?: words[raw]?.toDouble()
            if (n != null) {
                val unit = m.groupValues[2].lowercase()
                val mult = when { unit.startsWith("h") -> 3600; unit.startsWith("m") -> 60; else -> 1 }
                return (n * mult).toInt().coerceIn(1, 300)
            }
        }
        if (vague.matches(t)) return 5
        Regex("^wait(?: for)? (?:a )?few seconds", I).find(t)?.let { return 5 }
        Regex("^wait(?: for)? (?:some|a couple of|couple) seconds", I).find(t)?.let { return 4 }
        return null
    }

    fun parse(s: String): Command? {
        seconds(s)?.let { return Command.Wait(it) }
        Regex("^(?:send|share|forward)\\s+(?:it|that|this|the (?:last|latest|recent|new) (?:photo|picture|pic|image|selfie)|(?:the )?(?:photo|picture|pic|image|selfie))\\s+to\\s+(.+?)(?:\\s+(?:on|in|via|using|through)\\s+(.+))?$", I).find(s.trim().trimEnd('.', '!'))?.let { m ->
            val who = m.groupValues[1].trim()
            if (who.isNotEmpty()) return Command.SharePhoto(who, m.groupValues[2].trim().ifEmpty { null })
        }
        return null
    }
}

package com.demon.cmfassistant.intent

import java.util.Calendar

/**
 * Local, offline command parser. No network and no LLM: it matches a fixed set of phrases,
 * which keeps the THINKING state instant and the battery cost near zero.
 */
object IntentEngine {

    private val R = RegexOption.IGNORE_CASE

    private val leadIn = Regex(
        "^(?:(?:hey|ok|okay)\\s+(?:cmf|nothing|google)\\s*,?\\s*)?(?:(?:please|can you|could you|would you)\\s+)*",
        R
    )

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30
    )

    private val flashlightWord = Regex("\\b(flash ?light|torch)\\b")
    private val offWords = Regex("\\b(off|disable|kill|stop)\\b")
    private val onWords = Regex("\\b(on|enable|start|light up)\\b")

    private const val NUM = "(\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty)"
    private const val UNIT = "(seconds?|secs?|minutes?|mins?|hours?|hrs?)"
    private val timerAfter = Regex("\\btimer\\b.*?\\b$NUM\\s*$UNIT\\b")
    private val timerBefore = Regex("\\b$NUM\\s*$UNIT\\b.*\\btimer\\b")

    private val alarmRegex = Regex(
        "\\b(?:alarm|wake me(?: up)?)\\b.*?\\b(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm|a m|p m)?\\b"
    )

    private val callRegex = Regex("^(?:call|phone|dial|ring)\\s+(.+)$", R)
    private val smsRegex = Regex(
        "^(?:send\\s+(?:a\\s+)?(?:message|text|sms|msg)\\s+to|text|message|sms|msg)\\s+(.+?)\\s+(?:saying|that says|says|that)\\s+(.+)$",
        R
    )
    private val searchRegex = Regex("^(?:search(?: the web| google)?(?: for)?|google|look up)\\s+(.+)$", R)
    private val questionRegex = Regex("^(?:who|what|when|where|why|how)\\b.+", R)
    private val navigateRegex = Regex("^(?:navigate to|directions to|take me to|route to|navigate)\\s+(.+)$", R)
    private val openRegex = Regex("^(?:open|launch|start|run)\\s+(.+)$", R)

    fun parse(raw: String, now: Calendar = Calendar.getInstance()): Command {
        var s = raw.trim()
            .replace('’', '\'')
            .replace(Regex("a\\.m\\.?", R), "am")
            .replace(Regex("p\\.m\\.?", R), "pm")
            .replace(Regex("[.!?]+$"), "")
        s = leadIn.replace(s, "").trim()
        s = s.replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return Command.Unknown(raw)
        val l = s.lowercase().replace("'", "")

        // Flashlight
        if (flashlightWord.containsMatchIn(l)) {
            val on = when {
                offWords.containsMatchIn(l) -> false
                onWords.containsMatchIn(l) -> true
                else -> null
            }
            return Command.Flashlight(on)
        }

        // Phone controls: brightness, rotation, DND, ringer, settings pages, notifications and more
        SystemIntents.parse(s, l)?.let { return it }

        // Timer
        if (l.contains("half an hour") && l.contains("timer")) return Command.SetTimer(1800)
        (timerAfter.find(l) ?: timerBefore.find(l))?.let { m ->
            val n = number(m.groupValues[1])
            val secs = n?.let { it * unitSeconds(m.groupValues[2]) }
            if (secs != null && secs in 1..86_400) return Command.SetTimer(secs)
        }

        // Alarm
        alarmRegex.find(l)?.let { m ->
            val h0 = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val mer = m.groupValues[3].replace(" ", "")
            if (h0 in 0..23 && min in 0..59) {
                return Command.SetAlarm(resolveHour(h0, min, mer, now), min)
            }
        }

        // Messaging and calls (use the original casing for names and message text)
        smsRegex.find(s)?.let { m ->
            return Command.SendSms(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        callRegex.find(s)?.let { m -> return Command.Call(m.groupValues[1].trim()) }

        // Volume
        if (Regex("\\bunmute\\b").containsMatchIn(l)) return Command.Volume(VolumeAction.UP)
        if (Regex("\\bmute\\b").containsMatchIn(l)) return Command.Volume(VolumeAction.MUTE)
        if (l.contains("volume") || l == "louder" || l == "quieter" || l == "softer") {
            val pct = Regex("(\\d{1,3})\\s*(?:percent|%)?").find(l)?.groupValues?.get(1)?.toIntOrNull()
            if (l.contains("volume") && pct != null) {
                return Command.Volume(VolumeAction.SET, pct.coerceIn(0, 100))
            }
            if (Regex("\\b(down|decrease|lower|quieter|softer|reduce|less)\\b").containsMatchIn(l)) {
                return Command.Volume(VolumeAction.DOWN)
            }
            if (Regex("\\b(up|increase|raise|louder|higher|more)\\b").containsMatchIn(l)) {
                return Command.Volume(VolumeAction.UP)
            }
        }

        // Media keys
        Regex("^(play|pause|resume|stop)(?: the)?(?: music| song| songs| media| video| playback)?$").find(l)?.let { m ->
            val key = if (m.groupValues[1] == "pause" || m.groupValues[1] == "stop") MediaKey.PAUSE else MediaKey.PLAY
            return Command.Media(key)
        }
        if (Regex("^(?:next|skip)(?: the)?(?: song| track)?$").containsMatchIn(l)) return Command.Media(MediaKey.NEXT)
        if (Regex("^(?:play )?(?:the )?previous(?: song| track)?$").containsMatchIn(l)) {
            return Command.Media(MediaKey.PREVIOUS)
        }

        // Info
        if (Regex("\\bbattery\\b").containsMatchIn(l)) return Command.Battery
        if (Regex("\\bwhat(?:s| is)?\\s+the time\\b|\\bwhat time is it\\b|\\bcurrent time\\b|^time$").containsMatchIn(l)) {
            return Command.TimeNow
        }
        if (Regex("\\bwhat(?:s| is)?\\s+(?:the\\s+)?(?:todays\\s+)?date\\b|\\bwhat day is (?:it|today)\\b|\\btodays date\\b")
                .containsMatchIn(l)
        ) {
            return Command.DateToday
        }

        // Settings panels and camera
        if (Regex("\\bbluetooth\\b").containsMatchIn(l)) return Command.OpenSettings(SettingsPage.BLUETOOTH)
        if (Regex("\\b(wi ?fi|internet|wireless)\\b").containsMatchIn(l)) return Command.OpenSettings(SettingsPage.INTERNET)
        if (Regex("^(?:open )?(?:phone |system )?settings$").containsMatchIn(l)) return Command.OpenSettings(SettingsPage.GENERAL)
        if (Regex("\\bcamera\\b|\\b(?:take|click) a (?:photo|picture|selfie|pic)\\b").containsMatchIn(l)) return Command.Camera

        // Maps and web
        navigateRegex.find(s)?.let { m -> return Command.Navigate(m.groupValues[1].trim()) }
        searchRegex.find(s)?.let { m -> return Command.Search(m.groupValues[1].trim()) }
        if (questionRegex.matches(s)) return Command.Question(s)

        // Open an app (last, because it is the most generic pattern)
        openRegex.find(l)?.let { m ->
            val name = m.groupValues[1].removePrefix("the ").removeSuffix(" app").trim()
            if (name.isNotEmpty()) return Command.OpenApp(name)
        }

        return Command.Unknown(raw)
    }

    private fun number(word: String): Int? = word.toIntOrNull() ?: numberWords[word]

    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("sec") -> 1
        unit.startsWith("min") -> 60
        else -> 3600
    }

    /** With no am/pm, pick whichever reading of the hour comes next after [now]. */
    private fun resolveHour(h0: Int, minute: Int, meridiem: String, now: Calendar): Int {
        when (meridiem) {
            "pm" -> return if (h0 < 12) h0 + 12 else h0
            "am" -> return if (h0 == 12) 0 else h0
        }
        if (h0 !in 1..12) return h0
        val am = h0 % 12
        val pm = am + 12
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun delta(h: Int) = ((h * 60 + minute - nowMin) + 1440) % 1440
        return if (delta(am) <= delta(pm)) am else pm
    }
}

package com.demon.cmfassistant.intent

import com.demon.cmfassistant.auto.Macro
import java.util.Calendar

/** Tasker-style grammar: "open YouTube every day at 10 am", "news briefing every morning except Sunday", "keep the screen awake for 6 hours". */
object MacroPhrases {
    private val I = RegexOption.IGNORE_CASE

    private val dayNames = mapOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY
    )
    private const val DAY = "(?:sunday|monday|tuesday|wednesday|thursday|friday|saturday|sun|mon|tues?|wed|thur?s?|fri|sat)s?"
    private const val DAYLIST = "$DAY(?:\\s*(?:,|and|&|or)\\s*(?:and\\s*)?$DAY)*"
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = "(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
    private val numWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)

    private fun days(list: String): Set<Int> =
        Regex("[a-z]+", I).findAll(list).mapNotNull { dayNames[it.value.lowercase().take(3)] }.toSet()

    private fun numberWordsToDigits(s: String): String {
        var t = s
        val w = numWords.keys.joinToString("|")
        t = Regex("\\b(at )($w)\\b", I).replace(t) { "${it.groupValues[1]}${numWords[it.groupValues[2].lowercase()]}" }
        t = Regex("\\b($w)(?=\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|o'?clock)\\b)", I).replace(t) { numWords[it.groupValues[1].lowercase()].toString() }
        return t
    }

    /** Removes the first match of [re] from [s] and reports whether it matched. */
    private class Cut(var s: String) {
        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = (s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
            return m
        }
    }

    private val trigger = Regex("\\b(every|daily|each|weekdays?|weekends?|except|only on|tomorrow|tonight|today|in \\d+|after \\d+|on $DAY|next $DAY|\\d\\s*(?:am|pm)|at \\d|noon|midnight|$DAY|\\d{1,2}(?:st|nd|rd|th)? $MONTH)\\b", I)

    fun parse(raw: String): Command? {
        keepAwake(raw)?.let { return it }
        manage(raw)?.let { return it }
        say(raw)?.let { return it }
        var s = raw.trim().trimEnd('.', '!', '?').replace("’", "'").replace(Regex("\\s+"), " ")
        if (s.length < 8 || s.length > 220 || !trigger.containsMatchIn(s)) return null
        s = numberWordsToDigits(s)
        val c = Cut(s)

        // --- recurrence ---
        var daySet: Set<Int>? = null
        var daily = false
        var part: String? = null
        c.take(Regex("\\b(?:on\\s+)?(?:every )?(?:work ?days|week ?days?)\\b", I))?.let { daySet = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) }
        if (daySet == null) c.take(Regex("\\b(?:on |every )?week ?ends?\\b", I))?.let { daySet = setOf(Calendar.SATURDAY, Calendar.SUNDAY) }
        var except: Set<Int>? = null
        c.take(Regex("\\b(?:except|excluding|but not|apart from|other than|skip(?:ping)?|not on)(?: for)?(?: on)?\\s+($DAYLIST)\\b", I))?.let { except = days(it.groupValues[1]) }
        c.take(Regex("\\bonly (?:on |every )?($DAYLIST)\\b", I))?.let { daySet = days(it.groupValues[1]) }
        if (daySet == null) c.take(Regex("\\bevery ($DAYLIST)\\b", I))?.let { daySet = days(it.groupValues[1]) }
        if (daySet == null) c.take(Regex("\\bon ($DAYLIST)\\b", I))?.let { daySet = days(it.groupValues[1]) }
        if (daySet == null) c.take(Regex("\\b($DAY)s\\b", I))?.let { daySet = days(it.groupValues[1]) }
        c.take(Regex("\\bevery (morning|afternoon|evening|night)\\b", I))?.let { daily = true; part = it.groupValues[1].lowercase() }
        if (part == null) c.take(Regex("\\b(?:in the |this )?(morning|afternoon|evening|night)s\\b", I))?.let { daily = true; part = it.groupValues[1].lowercase() }
        c.take(Regex("\\b(?:every ?day|daily|each day|everyday|each morning|each evening|each night|every single day)\\b", I))?.let { daily = true }
        val recurring = daily || daySet != null || except != null

        // --- one-off date ---
        var dateMs: Long? = null
        var explicitDate = false
        val now = Calendar.getInstance()
        fun dayAt(offset: Int) = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        var relative: Long? = null
        if (!recurring) {
            c.take(Regex("\\b(?:in|after)\\s+(\\d+(?:\\.\\d+)?|an?|half an?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b", I))?.let {
                val q = it.groupValues[1].lowercase()
                val n = when { q.startsWith("half") -> 0.5; q == "a" || q == "an" -> 1.0; else -> q.toDouble() }
                val unit = it.groupValues[2].lowercase()
                val mult = when { unit.startsWith("s") -> 1000.0; unit.startsWith("m") -> 60_000.0; unit.startsWith("h") -> 3_600_000.0; else -> 86_400_000.0 }
                relative = (n * mult).toLong()
            }
            c.take(Regex("\\bday after tomorrow\\b", I))?.let { dateMs = dayAt(2).timeInMillis; explicitDate = true }
            if (dateMs == null) c.take(Regex("\\btomorrow\\b", I))?.let { dateMs = dayAt(1).timeInMillis; explicitDate = true }
            if (dateMs == null) c.take(Regex("\\btonight\\b", I))?.let { dateMs = dayAt(0).timeInMillis; part = "night"; explicitDate = true }
            if (dateMs == null) c.take(Regex("\\btoday\\b", I))?.let { dateMs = dayAt(0).timeInMillis; explicitDate = true }
            if (dateMs == null) c.take(Regex("\\bnext ($DAY)\\b", I))?.let {
                val want = dayNames[it.groupValues[1].lowercase().take(3)]!!
                var off = 1
                while (dayAt(off).get(Calendar.DAY_OF_WEEK) != want) off++
                dateMs = dayAt(off).timeInMillis; explicitDate = true
            }
            if (dateMs == null) c.take(Regex("\\b(?:on )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? ($MONTH)\\b", I))?.let { dateMs = dateOf(it.groupValues[1].toInt(), it.groupValues[2], now); explicitDate = true }
            if (dateMs == null) c.take(Regex("\\b(?:on )?($MONTH) (\\d{1,2})(?:st|nd|rd|th)?\\b", I))?.let { dateMs = dateOf(it.groupValues[2].toInt(), it.groupValues[1], now); explicitDate = true }
        }

        // --- time of day ---
        var hour = -1
        var minute = 0
        fun setTime(h: Int, m: Int, ap: String?) {
            var hh = h
            val a = ap?.lowercase()?.replace(".", "")
            if (a == "pm" && hh < 12) hh += 12
            if (a == "am" && hh == 12) hh = 0
            if (a == null && hh < 12 && (part == "evening" || part == "night" || part == "afternoon")) hh += 12
            hour = hh % 24; minute = m
        }
        c.take(Regex("\\bat\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s*o'?clock)?(?!\\d)", I))?.let { setTime(it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "0" }.toInt(), it.groupValues[3].ifEmpty { null }) }
        if (hour < 0) c.take(Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?!\\w)", I))?.let { setTime(it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "0" }.toInt(), it.groupValues[3]) }
        if (hour < 0) c.take(Regex("\\b(\\d{1,2}):(\\d{2})\\b", I))?.let { setTime(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        if (hour < 0) c.take(Regex("\\b(?:at )?noon\\b", I))?.let { hour = 12 }
        if (hour < 0) c.take(Regex("\\b(?:at )?midnight\\b", I))?.let { hour = 0 }
        if (hour in 0..23 && minute !in 0..59) return null
        if (hour < 0 && relative == null) {
            hour = when (part) { "morning" -> 7; "afternoon" -> 15; "evening" -> 18; "night" -> 21; else -> -1 }
        }

        if (!recurring && dateMs == null && relative == null && hour < 0) return null
        if (!recurring && dateMs != null && hour < 0) hour = 9
        if (recurring && hour < 0) return null

        // --- action ---
        var action = c.s
            .replace(Regex("\\b(?:in the|this|every|each|at|on|for|from|and|then)\\s*$", I), "")
            .replace(Regex("^(?:please|hey|ok|can you|could you|i want you to|i want to|i need you to|make sure you|just|so)\\s+", I), "")
            .replace(Regex("^(?:at|on|every|each|and|then|,|to)\\s+", I), "")
            .trim(' ', ',', '.')
        action = action.replace(Regex("^(?:schedule|automate|set up an automation to|set a task to|create a task to|repeat)\\s+(?:to\\s+)?", I), "").trim()
        if (action.length < 3) return null
        if (Regex("\\b(?:alarm|timer)\\b", I).containsMatchIn(action) && Regex("^(?:set|make|create|add|start|wake|schedule|cancel|delete|remove|show|stop)", I).containsMatchIn(action)) return null
        val remind = Regex("^remind me (?:to |about |that )?(.+)$", I).find(action)
        if (remind != null) {
            if (!recurring && !explicitDate) return null
            action = "say Reminder: " + remind.groupValues[1].trim()
        }
        if (Regex("^(?:what|who|how|why|where|when|which)\\b", I).containsMatchIn(action) && !recurring && !explicitDate) return null

        val finalDays: Set<Int> = when {
            except != null -> (daySet ?: Calendar.SUNDAY.rangeTo(Calendar.SATURDAY).toSet()) - except!!
            daySet != null -> daySet!!
            else -> emptySet()
        }
        if (recurring && finalDays.isEmpty() && except != null) return null

        val id = System.currentTimeMillis()
        if (recurring) return Command.AddMacro(Macro(id, action, hour, minute, finalDays))
        val at = (if (relative != null) Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + relative!! }
        else Calendar.getInstance().apply {
            if (dateMs != null) timeInMillis = dateMs!!
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (dateMs == null && timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        })
        if (at.timeInMillis <= System.currentTimeMillis()) return null
        return Command.AddMacro(Macro(id, action, at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), emptySet(), if (relative != null) at.timeInMillis else at.timeInMillis))
    }

    private fun dateOf(day: Int, month: String, now: Calendar): Long {
        val m = months.indexOf(month.lowercase().take(3))
        val c = (now.clone() as Calendar).apply { set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, day) }
        if (c.timeInMillis < now.timeInMillis - 86_400_000L) c.add(Calendar.YEAR, 1)
        return c.timeInMillis
    }

    private fun keepAwake(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?').replace("’", "'")
        if (Regex("^(?:stop|cancel|end|disable|turn off|let)\\b.*\\b(?:awake|keep(?:ing)? (?:the )?screen|stay(?:ing)? (?:on|awake)|screen (?:sleep|turn off|time ?out))", I).containsMatchIn(s) ||
            Regex("^let (?:the |my )?(?:screen|phone|display) (?:sleep|turn off|time ?out)", I).containsMatchIn(s)) return Command.KeepAwake(0)
        val hint = Regex("\\b(?:keep|hold|leave|stay|stop|don't let|do not let|prevent|make sure)\\b.*\\b(?:awake|screen (?:on|from)|display (?:on|from)|(?:screen|display|phone) (?:to )?(?:stay|remain)|(?:screen|phone|display) (?:from )?(?:sleep|turning off|timing out|locking))", I)
        if (!hint.containsMatchIn(s) && !Regex("^stay awake", I).containsMatchIn(s) && !Regex("^(?:keep|leave) (?:the |my )?(?:screen|display) on", I).containsMatchIn(s)) return null
        if (Regex("\\b(?:every|daily|tomorrow|at \\d|\\d\\s*(?:am|pm))\\b", I).containsMatchIn(s)) return null
        val w = numWords.keys.joinToString("|")
        val m = Regex("(\\d+(?:\\.\\d+)?|an?|half an?|$w)\\s*(?:and a half\\s*)?(hours?|hrs?|h\\b|minutes?|mins?)", I).find(s)
        var mins = 60
        if (m != null) {
            val q = m.groupValues[1].lowercase()
            val n = when { q.startsWith("half") -> 0.5; q == "a" || q == "an" -> 1.0; numWords.containsKey(q) -> numWords[q]!!.toDouble(); else -> q.toDouble() }
            val hours = m.groupValues[2].lowercase().startsWith("h")
            mins = (n * if (hours) 60 else 1).toInt() + if (Regex("and a half", I).containsMatchIn(m.value)) 30 else 0
        }
        return Command.KeepAwake(mins.coerceIn(1, 24 * 60))
    }

    private fun manage(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?').replace("’", "'")
        val things = "(?:automations?|macros?|routines?|scheduled tasks?|schedules?|scheduled commands?|recurring tasks?)"
        if (Regex("^(?:what|which|list|show|read|tell me)(?: me)?(?: all)?(?: of)?(?: my| the| our)?(?: scheduled| active| upcoming)? $things(?: do i have| are there| i have| are set| are scheduled| are active| do we have)?$", I).matches(s) ||
            Regex("^what(?:'s| is| do i have) (?:scheduled|automated)$", I).matches(s)) return Command.MacroManage("LIST")
        if (Regex("^(?:delete|remove|clear|cancel|stop|erase)(?: all)?(?: of)?(?: my| the)?(?: scheduled| active)? $things$", I).matches(s)) return Command.MacroManage("CLEAR_ALL")
        if (Regex("^(?:delete|remove|cancel|erase) (?:the )?(?:last|latest|newest) (?:one|$things)$", I).matches(s)) return Command.MacroManage("DELETE_LAST")
        Regex("^(?:delete|remove|cancel|stop|erase) (?:the |my )?(.+?) $things$", I).find(s)?.let { return Command.MacroManage("DELETE", it.groupValues[1].trim()) }
        if (Regex("^(?:pause|disable|turn off|suspend)(?: all)?(?: of)?(?: my| the)? $things$", I).matches(s)) return Command.MacroManage("PAUSE")
        if (Regex("^(?:resume|enable|turn on|unpause|restart)(?: all)?(?: of)?(?: my| the)? $things$", I).matches(s)) return Command.MacroManage("RESUME")
        return null
    }

    private fun say(raw: String): Command? {
        val m = Regex("^(?:say|announce|speak)\\s+[\"“']?(.+?)[\"”']?$", I).find(raw.trim().trimEnd('.', '!', '?')) ?: return null
        val t = m.groupValues[1].trim()
        if (Regex("\\b(?:every|daily|at \\d|tomorrow)\\b", I).containsMatchIn(t) || Regex("^(?:the news|news)", I).containsMatchIn(t)) return null
        return if (t.isNotEmpty()) Command.Say(t) else null
    }
}

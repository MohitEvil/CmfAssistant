package com.demon.cmfassistant.intent

import com.demon.cmfassistant.auto.Macro
import java.util.Calendar

/** Deep system phrases and cross-app jobs. */
object PrivPhrases {
    private val I = RegexOption.IGNORE_CASE

    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?').replace("’", "'").replace(Regex("\\s+"), " ")
        if (s.length > 160) return null

        // Cross-app jobs
        Regex("^do this in the apps?:\\s*(.+)$", I).find(s)?.let { return Command.Task(it.groupValues[1]) }
        if (Regex("^(?:order|get me|i want|i'd like|i would like)\\s+.+\\s+(?:from|on|using|via)\\s+(?:swiggy|zomato|blinkit|zepto|instamart|bigbasket|dominos|domino's|kfc|mcdonald's|mcdonalds)\\b", I).containsMatchIn(s) ||
            Regex("^(?:book|get|call|order|find)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:ride|cab|taxi|auto|bike taxi)\\s*(?:to|for)\\s+.+", I).containsMatchIn(s) ||
            Regex("^(?:book|reserve|make a reservation|find)\\s+(?:me\\s+)?(?:a\\s+)?(?:table|reservation)s?\\s*(?:for\\s+\\d+\\s*(?:people|persons|guests)?\\s*)?(?:at|in)\\s+.+", I).containsMatchIn(s) ||
            Regex("^order\\s+(?:food|dinner|lunch|breakfast|pizza|biryani|burger)\\b.*", I).containsMatchIn(s)) return Command.Task(s)

        // Location permission audit
        if (Regex("\\b(?:audit|check|review|list|show|which|what)\\b.*\\b(?:apps?|permissions?)\\b.*\\blocation\\b|\\blocation (?:permissions?|access) (?:audit|check|review)\\b|\\bapps? (?:that )?(?:can )?(?:use|see|track|access) my location\\b|\\bwho (?:can )?(?:see|track|use|access) my location\\b|^audit location permissions?$", I).containsMatchIn(s)) return Command.Priv("LOC_AUDIT")
        Regex("^(?:revoke|remove|take away|stop|deny|disable|turn off)\\s+(?:the\\s+)?location(?: permission| access)?\\s+(?:from|for|of)\\s+(.+)$", I).find(s)?.let { return Command.Priv("REVOKE_LOC", it.groupValues[1].trim()) }
        Regex("^(?:stop|don't let|do not let|block)\\s+(.+?)\\s+(?:from )?(?:tracking|using|accessing|seeing) (?:my )?location$", I).find(s)?.let { return Command.Priv("REVOKE_LOC", it.groupValues[1].trim()) }

        // Storage
        if (Regex("^(?:how much )?(?:storage|space|free space|disk)(?: is)?(?: left| free| available| do i have| remaining)?$|^how much (?:storage|space)(?: do i have)?(?: left| free)?$|^(?:check|show) (?:my )?storage$|^storage left$", I).containsMatchIn(s)) return Command.Priv("STORAGE")
        if (Regex("^(?:clear|clean|free up|empty|delete)\\s+(?:all\\s+)?(?:my\\s+)?(?:the\\s+)?(?:app\\s+)?(?:cache|caches|junk|storage|space|temporary files)(?:\\s+(?:of )?(?:all )?apps)?$|^(?:free up|clear) (?:some )?(?:storage|space)$|^clear cache$", I).containsMatchIn(s)) return Command.Priv("CLEAR_CACHE_ALL")
        Regex("^(?:clear|delete|wipe|erase|reset)\\s+(?:all\\s+)?(?:the\\s+)?(?:data|storage)\\s+(?:of|for|from)\\s+(.+)$", I).find(s)?.let { return Command.Priv("CLEAR_DATA", it.groupValues[1].trim()) }

        // Animations
        if (Regex("^(?:turn off|disable|remove|kill|stop)\\s+(?:all\\s+)?(?:the\\s+)?animations?$|^animations?\\s+off$|^speed up (?:the )?(?:phone|animations?)$", I).containsMatchIn(s)) return Command.Priv("ANIMATIONS", "off")
        if (Regex("^(?:turn on|enable|restore|bring back)\\s+(?:the\\s+)?animations?$|^animations?\\s+on$", I).containsMatchIn(s)) return Command.Priv("ANIMATIONS", "on")

        // Background apps, restart
        if (Regex("^(?:close|kill|stop|clear)\\s+(?:all\\s+)?(?:the\\s+)?(?:background apps|background processes|everything in the background)$", I).containsMatchIn(s)) return Command.Priv("KILL_ALL")
        if (Regex("^(?:reboot|restart)(?: my| the)?(?: phone| device)?$", I).containsMatchIn(s)) return Command.Priv("REBOOT")

        // Shell
        Regex("^(?:run|execute)\\s+(?:the\\s+)?(?:adb\\s+|shell\\s+)?(?:shell\\s+)?command\\s+(.+)$|^adb shell\\s+(.+)$|^shell\\s+(.+)$", I).find(s)?.let { m ->
            val cmd = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).trim()
            if (cmd.isNotEmpty()) return Command.Priv("SHELL", cmd)
        }
        Regex("^tap (?:at )?(?:coordinates? |point |position )?(\\d{1,4})\\s*(?:,|and|comma)?\\s*(\\d{1,4})$", I).find(s)?.let { return Command.Priv("TAP", "${it.groupValues[1]},${it.groupValues[2]}") }

        dnd(s)?.let { return it }
        return null
    }

    /** "Do not disturb until 7 am", "silence the phone for 2 hours": on now, and a one-off task switches it off later. */
    private fun dnd(s: String): Command? {
        val m = Regex("^(?:turn on|enable|start|switch on|put on|set)\\s+(?:the\\s+)?(?:do not disturb|dnd|silent mode|don't disturb)\\s+(?:until|till|for)\\s+(?:the next\\s+)?(.+)$", I).find(s) ?: return null
        val tail = m.groupValues[1].trim()
        val now = System.currentTimeMillis()
        val until: Long = run {
            Regex("^(\\d+(?:\\.\\d+)?|an?|half an?)\\s*(hours?|hrs?|h|minutes?|mins?)$", I).find(tail)?.let { d ->
                val q = d.groupValues[1].lowercase()
                val n = when { q.startsWith("half") -> 0.5; q == "a" || q == "an" -> 1.0; else -> q.toDouble() }
                val mult = if (d.groupValues[2].lowercase().startsWith("h")) 3_600_000.0 else 60_000.0
                return@run now + (n * mult).toLong()
            }
            Regex("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$", I).find(tail)?.let { t ->
                var h = t.groupValues[1].toInt()
                val min = t.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = t.groupValues[3].lowercase()
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                if (h > 23 || min > 59) return@run 0L
                val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
                return@run c.timeInMillis
            }
            0L
        }
        if (until <= now) return null
        val c = Calendar.getInstance().apply { timeInMillis = until }
        val off = Macro(now, "turn off do not disturb", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), emptySet(), until)
        return Command.Chain(listOf(Command.Dnd(true), Command.AddMacro(off)))
    }
}

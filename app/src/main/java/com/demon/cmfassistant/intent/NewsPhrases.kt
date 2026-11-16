package com.demon.cmfassistant.intent

/** "Any new news?", "news briefing", "latest news about cricket". */
object NewsPhrases {
    private val I = RegexOption.IGNORE_CASE

    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (s.length > 90) return null
        Regex("^(?:(?:say|give|tell|read|show|get|fetch|find|bring) (?:me )?(?:the |some |today's |todays |all )?|any |what(?:'s| is)(?: the| in the| on the)? |whats )?(?:(?:latest|new|recent|top|breaking|today's|todays|current|fresh) )*(?:news|headlines|updates)(?: briefing| update| summary| bulletin)?(?: (?:about|on|for|regarding|of|from) (.+?))?(?: (?:today|now|please|right now|for me))?(?: (?:i should know|i missed|do you have|is there|have you got))?$", I).find(s)?.let { m ->
            val topic = m.groupValues[1].trim()
            if (Regex("^(?:me|us|you)$", I).matches(topic)) return Command.News("")
            return Command.News(topic)
        }
        Regex("^(?:news briefing|brief me|morning briefing|daily briefing|brief me on the news|what(?:'s| is) happening(?: in the world| today| around the world| now)?|what(?:'s| is) going on(?: in the world| today)?|what happened today|anything new in the world|is there any news|is there any new news|any news today)$", I).find(s)?.let { return Command.News("") }
        Regex("^(?:what(?:'s| is) )?(?:the )?(?:latest|new) (?:on|about) (.+)$", I).find(s)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.split(" ").size <= 5 && !Regex("\\b(?:your|my|app|update|version|phone|software)\\b", I).containsMatchIn(t)) return Command.News(t)
        }
        return null
    }
}

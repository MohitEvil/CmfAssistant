package com.demon.cmfassistant.intent

/**
 * "Open Telegram and message Demon hi", "open settings and search developer options and tap the first result":
 * splits one sentence into steps and turns each into a command. In-app steps (search, message) use the screen
 * of whatever app the earlier steps opened.
 */
object Chain {
    private val I = RegexOption.IGNORE_CASE

    private const val VERBS = "open|launch|start|search|find|look|type|write|enter|tap|click|press|hit|scroll|go|send|msg|message|text|sms|" +
        "select|choose|long|hold|double|swipe|turn|switch|set|call|play|take|read|back|home|zoom|copy|paste|close|exit|navigate|google|" +
        "submit|pinch|drag|pick|check|uncheck|toggle|enable|disable|clear|delete|share|save|add|create|new"

    private val sep = Regex("\\s*(?:,|;|->|→|>)?\\s*\\b(?:and then|and after that|after that|and also|then|and)\\b\\s+(?=(?:$VERBS)\\b)|\\s*(?:->|→|>)\\s*", I)
    private val messageVerb = Regex("^(?:msg|message|text|sms|send (?:a )?(?:message|text)|whatsapp)\\b", I)
    private val hint = Regex("\\b(and|then|after that)\\b|->|→|>", I)

    private val leading = Regex("^(?:please|now|just|then|and|also|go ahead and|can you|could you)\\s+", I)

    private fun clean(p: String): String {
        var t = p.trim().trimEnd('.', ',', '!', '?')
        while (true) {
            val n = leading.replace(t, "")
            if (n == t) break
            t = n
        }
        return t.trim()
    }

    private fun split(s: String): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        while (pos < s.length) {
            val rest = s.substring(pos)
            // A message's text can contain "and": once a message step starts, the rest of the sentence is its body.
            if (out.isNotEmpty() && messageVerb.containsMatchIn(clean(rest))) { out.add(rest); break }
            val m = sep.find(s, pos)
            if (m == null) { out.add(rest); break }
            out.add(s.substring(pos, m.range.first))
            pos = m.range.last + 1
        }
        return out.map { clean(it) }.filter { it.isNotEmpty() }
    }

    private const val APPS = "whatsapp|telegram|messages|message|signal|instagram|messenger|sms|gmail|slack|discord"

    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?')
        // "send hi to demon on telegram", "message demon hi on whatsapp"
        Regex("^(?:send|text|message|msg)\\s+(?:a\\s+)?(?:message\\s+|text\\s+)?[\"'“‘]?(.+?)[\"'”’]?\\s+to\\s+[\"'“‘]?(\\w+)[\"'”’]?\\s+(?:on|in|via|using|through)\\s+($APPS)$", I).find(s)?.let { m ->
            return Command.Chain(listOf(Command.OpenApp(m.groupValues[3]), Command.UiMessage(m.groupValues[2], m.groupValues[1].trim())))
        }
        Regex("^(?:message|msg|text)\\s+[\"'“‘]?(\\w+)[\"'”’]?\\s+(?:saying\\s+|that\\s+)?(.+?)\\s+(?:on|in|via|using|through)\\s+($APPS)$", I).find(s)?.let { m ->
            return Command.Chain(listOf(Command.OpenApp(m.groupValues[3]), Command.UiMessage(m.groupValues[1], m.groupValues[2].trim())))
        }
        if (!hint.containsMatchIn(s)) return null
        val parts = split(s)
        if (parts.size < 2) return null
        val steps = ArrayList<Command>()
        for ((i, p) in parts.withIndex()) {
            val c = step(p, hasPrev = i > 0) ?: return null
            steps.add(c)
        }
        // A chain of one real step plus fillers isn't a chain.
        return if (steps.size >= 2) Command.Chain(steps) else null
    }

    private fun step(p: String, hasPrev: Boolean): Command? {
        // "message demon hi", "msg 'demon' saying hello"
        Regex("^(?:msg|message|text|sms|send (?:a )?(?:message|text) to|whatsapp)\\s+(?:to\\s+)?(?:[\"'“‘]([^\"'”’]+)[\"'”’]|(\\S+))\\s*(?:saying|that says|says|that|:|,|with)?\\s*(.+)$", I).find(p)?.let { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
            val body = m.groupValues[3].trim().trim('"', '\'', '“', '”')
            if (name.isNotEmpty() && body.isNotEmpty()) return Command.UiMessage(name, body)
        }
        // "search developer options" after something is open: search inside that app
        if (hasPrev) {
            Regex("^(?:search|find|look for|look up|search for)(?: for)?\\s+(.+?)(?:\\s+(?:in|on|using) (?:the |its )?(?:search )?(?:bar|box|field))?$", I).find(p)?.let { m ->
                val q = m.groupValues[1].trim()
                if (!Regex("^(?:the )?(?:search )?(?:bar|box|field|icon|button)$", I).matches(q)) {
                    val pick = Regex("\\b(?:and )?(?:open|select|tap|click|choose)\\b", I).containsMatchIn(p)
                    return Command.UiSearch(q, pick)
                }
            }
            Regex("^(?:then )?(?:open|select|tap|click|choose|pick)(?: on)? (?:the )?(?:first|top|best|closest) (?:result|match|one|option)$", I).find(p)?.let {
                return Command.UiTap("@first")
            }
        }
        val c = IntentEngine.parse(p)
        return when (c) {
            is Command.Unknown -> null
            is Command.Question -> null
            else -> c
        }
    }
}

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
        "submit|pinch|drag|pick|check|uncheck|toggle|enable|disable|clear|delete|share|save|add|create|new|wait|pause|install|download|update|snap|capture|mute|unmute|lock"

    private val sep = Regex("\\s*(?:,|;|->|→|>)?\\s*\\b(?:and then|and after that|after that|afterwards|and also|then|and)\\b[,]?\\s+(?=(?:$VERBS)\\b)|\\s*(?:->|→|>)\\s*|\\s*[,;]\\s*(?=(?:$VERBS)\\b)", I)
    private val messageVerb = Regex("^(?:msg|message|text|sms|send (?:a )?(?:message|text)|whatsapp)\\b", I)
    private val hint = Regex("\\b(and|then|after that|afterwards)\\b|->|→|>|[,;]\\s*(?:$VERBS)\\b", I)

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
        val fixed = resolveIt(steps)
        // A chain of one real step plus fillers isn't a chain.
        return if (fixed.size >= 2) Command.Chain(fixed) else null
    }

    private val itWords = setOf("it", "that", "this", "them")
    private val installWords = setOf("install", "update", "download", "get", "install it", "update it", "download it")
    private val playStore = Regex("play ?store|google play|app ?store", I)

    private fun isInstallTap(c: Command): Boolean = when (c) {
        is Command.UiTap -> c.label.trim().lowercase() in installWords
        is Command.UiSmart -> c.act == ScreenAct.TAP && c.target.trim().lowercase() in installWords
        is Command.InstallApp -> c.name == "@it"
        else -> false
    }

    /**
     * Cleans up a list of steps, whoever wrote it (grammar, Needle 3 or the cloud AI):
     * "turn it off" after the flashlight means the flashlight, and "search X in Play Store ... install it" becomes one install.
     */
    fun normalize(steps: List<Command>): List<Command> {
        val out = ArrayList<Command>()
        var last: Command? = null
        for (c in steps) {
            if (isInstallTap(c)) {
                // find what was searched for
                var q: String? = null
                var i = out.size - 1
                while (i >= 0) {
                    val p = out[i]
                    val found = when (p) {
                        is Command.UiSearch -> p.query
                        is Command.AppSearch -> p.query
                        is Command.SmartSearch -> p.query
                        else -> null
                    }
                    if (found != null && q == null) q = found
                    if (p is Command.Wait || p is Command.UiSearch || p is Command.AppSearch || p is Command.SmartSearch || p is Command.UiTap && p.label == "@first" ||
                        (p is Command.OpenApp && playStore.containsMatchIn(p.name)) || (p is Command.LaunchPackage && p.pkg.contains("vending"))) { out.removeAt(i); i-- } else break
                }
                out.add(if (q != null) Command.InstallApp(q) else Command.UiSmart("Install", ScreenAct.TAP))
                last = out.last()
                continue
            }
            val r: Command = when {
                c is Command.UiSwitch && c.label.trim().lowercase() in itWords && c.on != null -> when (val l = last) {
                    is Command.Flashlight -> Command.Flashlight(c.on)
                    is Command.Toggle -> Command.Toggle(l.key, c.on)
                    else -> c
                }
                else -> c
            }
            out.add(r)
            if (r !is Command.Wait) last = r
        }
        return out
    }

    private fun resolveIt(steps: List<Command>) = normalize(steps)

    private fun step(p: String, hasPrev: Boolean): Command? {
        WaitPhrases.parse(p)?.let { return it }
        Regex("^(?:turn|switch)\\s+(?:(?:it|that|this)\\s+(on|off)|(on|off)\\s+(?:it|that|this))$", I).find(p)?.let { m ->
            return Command.UiSwitch("it", (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase() == "on")
        }
        if (Regex("^(?:install|download|update|get)(?: it| that| this| the app| the application| it too)?$", I).matches(p)) return Command.InstallApp("@it")
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

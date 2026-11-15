package com.demon.cmfassistant.intent

/** Notifications ("read the second notification", "reply to Demon saying ok") and weather. [l] is lower case. */
object HelperPhrases {
    private fun has(l: String, p: String) = Regex(p).containsMatchIn(l)

    /** first / 2nd / last / second last / most recent -> 1-based from the newest; negative counts from the oldest. */
    private fun nth(s: String): Int? = when {
        has(s, "\\b(second|2nd) (to )?last\\b|\\bpenultimate\\b") -> -2
        has(s, "\\b(third|3rd) (to )?last\\b") -> -3
        has(s, "\\b(last|oldest|bottom)\\b") -> -1
        has(s, "\\b(first|1st|latest|newest|recent|most recent|top|new)\\b") -> 1
        has(s, "\\b(second|2nd)\\b") -> 2
        has(s, "\\b(third|3rd)\\b") -> 3
        has(s, "\\b(fourth|4th)\\b") -> 4
        has(s, "\\b(fifth|5th)\\b") -> 5
        else -> null
    }

    private val notWho = setOf("it", "that", "this", "you", "he", "she", "they", "someone", "anyone", "everyone", "who", "everybody")

    private fun who(s: String) = s.trim().removePrefix("the ").removePrefix("my ").removeSuffix("'s").trim().takeIf { it.isNotEmpty() && it !in notWho }

    fun parse(s: String, l0: String): Command? {
        val t = l0.replace("'", "").replace("’", "").replace(Regex("\\s+"), " ").trim()
        if (t.length > 120) return null
        if (NotifyState.active()) pending(s, t)?.let { return it }
        weather(t)?.let { return it }
        followUp(t)?.let { return it }
        if (!has(t, "notification|notifications|message|messages|reply|respond|saying|said|texted|sent me|send me|need.*answer|answer.*need|history")) return null

        // history
        if (has(t, "notification history|history of (?:my )?notifications|notifications history|past notifications|old notifications|earlier notifications|missed notifications|notification log|what did i miss")) {
            if (has(t, "^(?:open|go to|launch|take me to|show me the)\\b.*(?:history|log)\\b") && !has(t, "\\b(?:read|tell|speak)\\b")) return null
            val w = Regex("\\b(?:from|of|for|on)\\s+(?!my |the )([a-z][a-z ]*?)$").find(t)?.groupValues?.get(1)?.trim()
            return Command.Notif(NotifOp.HISTORY, who = w.orEmpty().takeIf { it !in setOf("today", "yesterday") }.orEmpty())
        }
        // who sent notifications from an app
        Regex("^who(?: all)?(?: is| are| was| were| has| have| sent| send| texted| messaged| messaging| texting| sending| pinged)*(?: me)?(?: the| a| any| new)*(?: notifications?| messages?| texts?)?(?: on| in| from| of| for)(?: the)? (.+?)(?: app)?$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.WHO, who = it) }
        }
        // what does the notification from X say
        Regex("^what (?:does|do|did) (?:the )?(?:notifications?|messages?)(?: from| of| on| in)? (.+?) (?:say|says|said)$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ, who = it) }
        }
        Regex("^what (?:the )?notifications? says? (?:from|on|in) (.+)$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ, who = it) }
        }

        // reply
        Regex("^(?:reply|respond|answer)(?: to)?(?: the)? (.+?) (?:recent |new )?notification(?: (?:with|saying|say|that|by saying|and say|text|message)\\s+(.+))?$").find(t)?.let { m ->
            val n = nth(m.groupValues[1])
            if (n != null) return Command.Notif(NotifOp.REPLY, nth = n, text = m.groupValues[2].trim())
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.REPLY, who = it, nth = 1, text = m.groupValues[2].trim()) }
        }
        Regex("^(?:reply|respond|answer|message|text|tell)(?: to)? (.+?) (?:saying|with|say|that|and say|and tell (?:him|her|them))\\s+(.+)$").find(t)?.let { m ->
            if (has(m.groupValues[1], "^(?:the )?(first|second|third|last|latest|newest|recent|most recent|2nd|3rd)")) {
                nth(m.groupValues[1])?.let { return Command.Notif(NotifOp.REPLY, nth = it, text = m.groupValues[2].trim()) }
            } else if (has(t, "^(?:reply|respond|answer)")) {
                who(m.groupValues[1])?.let { return Command.Notif(NotifOp.REPLY, who = it, nth = 1, text = m.groupValues[2].trim()) }
            }
        }
        Regex("^(?:reply|respond|answer)(?: to)? (.+)$").find(t)?.let { m ->
            val w = m.groupValues[1]
            if (!has(w, "notification")) who(w)?.let { return Command.Notif(NotifOp.REPLY, who = it, nth = 1) }
        }

        // open / click / read by position
        Regex("^(?:click|tap|press|open|select|go to)(?: on)?(?: the)? (.+?) (?:recent |new )?notification$").find(t)?.let { m ->
            nth(m.groupValues[1])?.let { return Command.Notif(NotifOp.OPEN, nth = it) }
        }
        Regex("^(?:open|click|tap)(?: on)?(?: the| my)? notification (?:from|of) (.+)$").find(t)?.let { m -> who(m.groupValues[1])?.let { return Command.Notif(NotifOp.OPEN, who = it, nth = 1) } }
        Regex("^(?:read|show|tell me|what(?:s| is)?|speak)(?: me)?(?: out)?(?: the)? (.+?) (?:recent |new )?notification(?: out| aloud)?$").find(t)?.let { m ->
            nth(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ_NTH, nth = it) }
        }

        // clear
        Regex("^(?:clear|dismiss|remove|delete)(?: the| my| all| every)* (.+?) (?:recent |new )?notification$").find(t)?.let { m ->
            nth(m.groupValues[1])?.let { return Command.Notif(NotifOp.CLEAR, nth = it) }
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.CLEAR, who = it) }
        }
        Regex("^(?:clear|dismiss|remove|delete)(?: all| my| the| every| new)* notifications?(?: from| of| for)?(?: the)? ?(.*)$").find(t)?.let { m ->
            val w = who(m.groupValues[1])
            return if (w == null) Command.Notif(NotifOp.CLEAR_ALL) else Command.Notif(NotifOp.CLEAR, who = w)
        }
        Regex("^(?:clear|dismiss|remove|delete)(?: all| my| the)* (.+?) notifications?$").find(t)?.let { m -> who(m.groupValues[1])?.let { return Command.Notif(NotifOp.CLEAR, who = it) } }

        // anything from X / did I get ...
        Regex("^(?:did|have) i (?:get|got|receive|received|have)(?: any| a| an)?(?: new)?(?: notifications?| messages?)?(?: or messages?| or notifications?)? from (.+?)(?: today| yet| recently| lately)?$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.FROM, who = it) }
        }
        Regex("^(?:any|is there|are there|check for|do i have)(?: any| a| an)?(?: new)?(?: notifications?| messages?) from (.+?)(?: today| yet| now)?$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.FROM, who = it) }
        }

        // what is X saying
        Regex("^(?:what(?:s| is| was| did| has)?)(?: the)? (.+?) (?:saying|say|said|texted|messaged|sent|written|wrote)(?: me| to me)?$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ, who = it) }
        }
        Regex("^(?:read|show|tell me)(?: me)?(?: the| my| new)* (?:messages?|notifications?|texts?) from (.+)$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ, who = it) }
        }

        // "what notification from telegram", "read notifications from instagram", "show the messages on whatsapp"
        Regex("^(?:what|which|read|show|tell me|give me|check|list)(?: me)?(?: new| recent| all| the| my| unread)*(?: notifications?| messages?| texts?)(?: did i get| do i have| are there| came| i got| i have)?(?: from| on| in| of| for)(?: the)? (.+?)(?: app)?$").find(t)?.let { m ->
            who(m.groupValues[1])?.let { return Command.Notif(NotifOp.READ, who = it) }
        }

        // who needs a reply
        if (!has(t, "^reply") && (has(t, "\\b(need|needs|should|must|have to|waiting|wants?)\\b.*\\b(reply|replies|respond|response|answer)\\b") || has(t, "\\bwho (should|do) i (reply|respond|answer)\\b") || has(t, "\\bunanswered\\b|\\bpending repl"))) {
            return Command.Notif(NotifOp.NEEDS_REPLY)
        }

        // summary
        if (has(t, "^(?:show|read|check|list|tell me|give me|what are|whats|what is|speak)(?: me)?(?: all| my| the| any| new| recent| latest| unread| out| every)* notifications?(?: out| aloud| now| i have| i got| please)*$") ||
            has(t, "^(?:do i have|did i (?:get|got|receive)|have i (?:got|received)|any|are there)(?: any| new| recent| unread| a| notifications?)* notifications?(?: i got| i have| i received| i missed)?(?: now| today| yet| so far)?$")
        ) return Command.Notif(NotifOp.SUMMARY)
        return null
    }


    private val yes = Regex("^(?:yes|yeah|yep|yup|sure|ok|okay|please|reply|go ahead|do it|yes please|yes reply|sure reply)$")
    private val no = Regex("^(?:no|nope|nah|skip|no thanks|dont|dont reply|not now|leave it|cancel|never mind|nevermind|stop|dont send)$")

    /** While a reply is being offered or dictated. */
    private fun pending(s: String, t: String): Command? {
        val who = "@" + NotifyState.key
        if (NotifyState.stage == 1) {
            if (yes.matches(t)) return Command.Notif(NotifOp.REPLY_ASK)
            if (no.matches(t)) return Command.Notif(NotifOp.DROP)
            Regex("^(?:reply|say|tell (?:him|her|them)|send|write|answer)(?: to (?:him|her|them))?(?: saying| with| that)?\\s+(.+)$", RegexOption.IGNORE_CASE).find(s.trim())?.let { m ->
                return Command.Notif(NotifOp.REPLY, who = who, text = m.groupValues[1].trim())
            }
            return null
        }
        if (no.matches(t)) return Command.Notif(NotifOp.DROP)
        val body = s.trim().replace(Regex("^(?:reply|say|tell (?:him|her|them)|send|write)(?: to (?:him|her|them))?(?: saying| with| that)?\\s+", RegexOption.IGNORE_CASE), "").trim()
        return if (body.isEmpty()) null else Command.Notif(NotifOp.REPLY, who = who, text = body)
    }

    /** "read them", "who sent them" right after a notification question. */
    private fun followUp(t: String): Command? {
        val w = NotifyState.recentWho() ?: return null
        if (has(t, "^(?:read|show|tell me|what(?:s| do| does| did)?)(?: me)?(?: them| it| those| these| that| the (?:messages?|notifications?|texts?))(?: say| says| out| aloud)?$")) return Command.Notif(NotifOp.READ, who = w)
        if (has(t, "^who(?: sent| send| are they| is it| was it| are these| texted| messaged)(?: them| it| those)?$")) return Command.Notif(NotifOp.WHO, who = w)
        return null
    }

    private fun weather(t: String): Command? {
        if (!has(t, "\\b(weather|temperature|forecast|how (hot|cold|warm)|will it rain|going to rain|is it (raining|hot|cold|sunny|cloudy|humid)|umbrella|rain today|rain tomorrow)\\b")) return null
        if (has(t, "^(open|launch|start|install|uninstall|show the|go to) .*app\\b|^open weather\\b")) return null
        val place = Regex("\\b(?:in|at|for|of)\\s+([a-z][a-z .]*?)(?:\\s+(?:right now|now|today|tomorrow|currently|tonight|this evening|this morning))?$").find(t)?.groupValues?.get(1)?.trim()
            ?.takeIf { it !in setOf("my area", "here", "my city", "my location", "the morning", "the evening", "the afternoon") && !has(it, "^(the )?(next|coming|this|few)\\b") }
        return Command.Weather(place, has(t, "\\btomorrow\\b"))
    }
}

package com.demon.cmfassistant.intent

import com.demon.cmfassistant.access.ScreenMemory

/**
 * Spoken ways to work with what is on screen: "the second toggle", "long press the stop button in front of Telegram",
 * "what's right after that", "keep scrolling down", "turn it on", "click on it". [l] is lower case; [s] keeps the original case.
 */
object UiPhrases {
    private const val VERB = "long press|long tap|long click|press and hold|tap and hold|click and hold|touch and hold|hold|double tap|double click|tap|click|press|hit|touch|select|choose|open"
    private const val ORD = "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|1st|2nd|3rd|4th|5th|6th"
    private const val REG = "top left|top right|bottom left|bottom right|upper left|upper right|lower left|lower right|top|bottom|upper|lower|left|right|middle|center|centre"
    private const val REL = "in front of|in front|infront of|ahead of|right after|just after|after|before|behind|next to|beside|besides|nearby|near|close to|in below of|in below|below of|below|underneath|beneath|under|in above of|in above|above of|above|on top of|over|to the right of|on the right of|at the right of|right of|to the left of|on the left of|at the left of|left of"

    private fun has(l: String, p: String) = Regex(p).containsMatchIn(l)

    private fun clean(l: String): String {
        var t = l.replace("'", "").replace("’", "").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(?:(?:hey|ok|okay|please|can you|could you|would you|will you|just|now|kindly|go ahead and)\\s+)+"), "")
        t = t.replace(Regex("\\s+(?:please|now|for me|ok|okay)$"), "")
        return t.trim()
    }

    private fun actOf(v: String) = when {
        v.startsWith("long") || v.contains("hold") -> ScreenAct.LONG
        v.startsWith("double") -> ScreenAct.DOUBLE
        else -> ScreenAct.TAP
    }

    private fun relKey(r: String) = when {
        r.contains("after") -> "after"
        r.contains("before") -> "before"
        r.contains("front") || r.contains("ahead") -> "front"
        r.contains("behind") || r.contains("next to") || r.contains("beside") || r.contains("near") || r.contains("close") -> "near"
        r.contains("below") || r.contains("under") || r.contains("beneath") -> "below"
        r.contains("above") || r.contains("on top") || r == "over" -> "above"
        r.contains("right") -> "right"
        else -> "left"
    }

    private fun ordinal(w: String): Int? = when (w) {
        "first", "1st" -> 1; "second", "2nd" -> 2; "third", "3rd" -> 3; "fourth", "4th" -> 4; "fifth", "5th" -> 5
        "sixth", "6th" -> 6; "seventh" -> 7; "eighth" -> 8; "ninth" -> 9; "tenth" -> 10; "last" -> -1
        else -> null
    }

    private fun region(w: String): String? = when (w) {
        "top", "upper" -> "top"; "bottom", "lower" -> "bottom"; "left" -> "left"; "right" -> "right"
        "top left", "upper left" -> "topleft"; "top right", "upper right" -> "topright"
        "bottom left", "lower left" -> "bottomleft"; "bottom right", "lower right" -> "bottomright"
        "middle", "center", "centre" -> "middle"
        else -> null
    }

    private val noWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twice" to 2, "thrice" to 3)

    fun parse(s: String, l0: String): Command? {
        val t = clean(l0)
        if (t.isEmpty() || t.length > 120) return null

        // Answer to "which one?"
        if (ScreenMemory.hasOptions()) {
            Regex("^(?:the |that |this )?($ORD|$REG)(?: one| option| match| result)?$").find(t)?.let { m ->
                val w = m.groupValues[1]
                return Command.UiSmart("", ScreenAct.TAP, nth = ordinal(w), region = if (ordinal(w) == null) region(w) else null)
            }
            Regex("^(?:number |option |no )?(\\d)$").find(t)?.let { return Command.UiSmart("", ScreenAct.TAP, nth = it.groupValues[1].toInt()) }
            // Said the name of one of the options that were just listed
            ScreenMemory.options.firstOrNull { com.demon.cmfassistant.action.Targeting.score(t, it.label) >= 88 }?.let { return Command.UiSmart(it.label, ScreenAct.TAP) }
        }

        // "click the stop button in front of telegram"
        Regex("^($VERB)(?: on| onto)?(?: the| a| an)? (.+?) ($REL) (?:the |this |that |my )?(.+)$").find(t)?.let { m ->
            val target = m.groupValues[2].trim()
            if (target.isNotEmpty() && target !in setOf("it", "that", "this") && m.groupValues[1] != "open") {
                return Command.UiSmart(target, actOf(m.groupValues[1]), rel = relKey(m.groupValues[3]), anchor = m.groupValues[4].trim())
            }
        }
        // "what is right after that", "which button is below telegram"
        Regex("^(?:what|which)(?: button| icon| option| item| thing| one| text)?(?: is| was)?(?: there)?(?: on)? ($REL) (?:the |this |that |my )?(.+)$").find(t.replace(Regex("^(what|which)s "), "$1 is "))?.let { m ->
            return Command.UiSmart("", ScreenAct.ASK, rel = relKey(m.groupValues[1]), anchor = m.groupValues[2].trim())
        }
        Regex("^(?:find|see|show me|where is|is there|do you see|you see|look for|locate)(?: me)? (?:a |an |the )?(.+?) ($REL) (?:the |this |that |my )?(.+?)(?: on (?:the |this )?(?:screen|page))?$").find(t)?.let { m ->
            return Command.UiSmart(m.groupValues[1].trim(), ScreenAct.ASK, rel = relKey(m.groupValues[2]), anchor = m.groupValues[3].trim())
        }

        // "tap the second toggle", "long press the top right button", "click the last one"
        Regex("^($VERB)(?: on| onto)?(?: the| a)? ($ORD|$REG)(?: most)?(?: (one|ones))?(?: (.+))?$").find(t)?.let { m ->
            val w = m.groupValues[2]
            val ord = ordinal(w)
            val rest = m.groupValues[4].trim()
            if (m.groupValues[1] != "open" && (ord != null || rest.isNotEmpty() || m.groupValues[3].isNotEmpty())) {
                return Command.UiSmart(rest, actOf(m.groupValues[1]), nth = ord, region = if (ord == null) region(w) else null)
            }
        }

        // "click on it", "long press that", "delete it"
        Regex("^($VERB|delete|remove|erase|trash)(?: on| onto)? (?:it|that|this|this one|that one|the same|same one|the same one)$").find(t)?.let { m ->
            val v = m.groupValues[1]
            val needsMemory = v in setOf("open", "select", "choose", "delete", "remove", "erase", "trash")
            if (!needsMemory || ScreenMemory.last() != null) {
                val act = if (v in setOf("delete", "remove", "erase", "trash")) ScreenAct.DELETE else actOf(v)
                return Command.UiSmart("it", act)
            }
        }
        if (ScreenMemory.last() != null && has(t, "^(?:what(?: is|s)? (?:it|that|this)|tell me (?:more )?about (?:it|that)|describe (?:it|that))$")) return Command.UiSmart("it", ScreenAct.ASK)

        scroll(t)?.let { return it }

        if (has(t, "^(?:hide|close|dismiss|put away|remove)(?: the| my)? keyboard$")) return Command.UiMisc("hidekeyboard")
        if (has(t, "^(?:refresh|reload)(?: the| this)?(?: page| screen| feed| list)?$|^pull to refresh$")) return Command.UiMisc("refresh")
        if (has(t, "^next (?:page|tab|slide|photo|image|story|picture)$")) return Command.UiMisc("next")
        if (has(t, "^(?:previous|prev) (?:page|tab|slide|photo|image|story|picture)$")) return Command.UiMisc("prev")
        if (has(t, "^(?:do|repeat) (?:it|that) again$|^once more$|^one more time$|^again$")) return Command.UiRepeat

        // "type hello in the search box"
        Regex("^(?:type|write|enter|input|put)\\s+(.+?)\\s+(?:in|into|on|inside)\\s+(?:the\\s+|this\\s+|that\\s+)?(.+?(?:box|field|bar|input|search|message|chat|comment|reply|note|name|email|password|username|address|url))$", RegexOption.IGNORE_CASE).find(s.replace(Regex("\\s+"), " ").trim())?.let { m ->
            return Command.Chain(listOf(Command.UiTap(m.groupValues[2].trim().lowercase()), Command.UiType(m.groupValues[1].trim())))
        }
        return null
    }

    private fun scroll(t: String): Command? {
        if (!has(t, "\\b(scroll|scrolling|scrolls)\\b") || t.length > 80) return null
        Regex("^scroll(?: down| up)?(?: until you see| till you see| until you find| to find| until| till| to)(?: the| a)? (.+)$").find(t)?.let { m ->
            val x = m.groupValues[1].trim()
            return if (has(x, "^(top|bottom|end|start|beginning)\\b")) null else Command.UiSmart(x, ScreenAct.ASK)
        }
        if (has(t, "\\b(top|bottom|end|beginning)\\b")) return null
        val raw = Regex("\\b(up|down|left|right|upwards?|downwards?)\\b").find(t)?.value
        val keep = has(t, "\\b(keep|continue|continuously|nonstop|non stop|constantly|endlessly|all the way|forever|till the end|until the end)\\b")
        val long = has(t, "\\b(long|far|lot|lots|alot|fast|quickly|big|full|much|more|way|bunch)\\b")
        if (raw == null && !(keep || long)) return null
        val dir = raw?.removeSuffix("s")?.removeSuffix("ward") ?: "down"
        val little = has(t, "\\b(little|bit|slightly|slowly|small|tiny|half)\\b")
        val n: Int? = Regex("\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|twice|thrice)\\s*(?:times|x)\\b").find(t)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: noWords[it] }
            ?: if (has(t, "\\btwice\\b")) 2 else if (has(t, "\\bthrice\\b")) 3 else null
        val plain = !keep && !long && !little && n == null
        if (plain && (dir == "up" || dir == "down")) return null // the plain "scroll down" keeps its existing, proven path
        return Command.UiScrollEx(dir, times = n ?: if (long) 5 else 1, keep = keep)
    }

    private val notSwitches = Regex("\\b(volume|sound|music|song|screen|phone|torch|flashlight|light|lights|display|brightness|alarm|timer|speaker|mic|microphone|assistant|listening|voice|tv|fan|ac)\\b")

    /** Generic switches on whatever screen is open. Tried after the named system switches (wifi, bluetooth, ...), so those win. */
    fun late(s: String, l0: String): Command? {
        val t = clean(l0)
        if (t.isEmpty() || t.length > 80) return null
        fun lab(x: String): String? = x.trim().removePrefix("the ").trim().takeIf { !notSwitches.containsMatchIn(it) && it.length <= 40 }
        Regex("^(?:turn|switch|set|put)\\s+(?:the\\s+)?(.+?)\\s+(on|off)$").find(t)?.let { m ->
            val label = lab(m.groupValues[1]) ?: return null
            return Command.UiSwitch(label, m.groupValues[2] == "on")
        }
        Regex("^(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(.+)$").find(t)?.let { m ->
            val label = lab(m.groupValues[2]) ?: return null
            return Command.UiSwitch(label, m.groupValues[1] == "on")
        }
        Regex("^(enable|disable|activate|deactivate)\\s+(?:the\\s+)?(.+)$").find(t)?.let { m ->
            val label = lab(m.groupValues[2]) ?: return null
            return Command.UiSwitch(label, m.groupValues[1].startsWith("en") || m.groupValues[1] == "activate")
        }
        Regex("^(?:toggle|flip)(?:\\s+(on|off))?(?:\\s+(?:the\\s+)?(.+?))?(?:\\s+(on|off))?$").find(t)?.let { m ->
            val on = (m.groupValues[1].ifEmpty { m.groupValues[3] }).let { if (it.isEmpty()) null else it == "on" }
            val label = if (m.groupValues[2].isEmpty()) "" else (lab(m.groupValues[2]) ?: return null)
            return Command.UiSwitch(label, on)
        }
        Regex("^(?:turn|switch)\\s+(on|off)$").find(t)?.let { return Command.UiSwitch("", it.groupValues[1] == "on") }
        return null
    }
}

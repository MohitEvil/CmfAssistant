package com.demon.cmfassistant.intent

/** Natural phrases for switches, screen control and chores. [l] is lower-case, hyphens and commas turned into spaces. */
object ControlIntents {
    private val R = RegexOption.IGNORE_CASE

    private class Target(val key: String, val rx: Regex)

    private val targets = listOf(
        Target("wifi", Regex("\\b(wi ?fi|wifi|wireless internet)\\b")),
        Target("bluetooth", Regex("\\b(bluetooth|blue tooth)\\b")),
        Target("airplane", Regex("\\b(airplane|aeroplane|flight|plane) mode\\b|\\bairplane\\b")),
        Target("data", Regex("\\b(mobile|cellular|cell|sim) data\\b|\\bmobile internet\\b|\\bdata connection\\b")),
        Target("hotspot", Regex("\\b(hotspot|hot spot|tethering)\\b")),
        Target("nfc", Regex("\\bnfc\\b")),
        Target("location", Regex("\\b(location|gps)\\b")),
        Target("batterysaver", Regex("\\bbattery saver\\b|\\bpower saving( mode)?\\b|\\blow power mode\\b")),
        Target("dark", Regex("\\bdark (mode|theme)\\b|\\bnight mode\\b")),
        Target("usbdebug", Regex("\\b(usb|adb) debugging\\b")),
        Target("stayawake", Regex("\\bstay awake\\b")),
        Target("showtouches", Regex("\\bshow (touches|taps)\\b")),
        Target("dev", Regex("\\b(developer|dev) (options|mode|settings)\\b"))
    )

    private fun has(l: String, p: String) = Regex(p).containsMatchIn(l)

    /** 0 none, 1 on, 2 off, 3 toggle. */
    private fun verb(l: String): Int = when {
        has(l, "\\b(turn|switch|power|shut|set|put)\\b.*\\boff\\b|\\b(disable|deactivate|kill|band|bandh|bund)\\b|\\boff( karo| kar do| kardo)?$") -> 2
        has(l, "\\b(turn|switch|power|set|put)\\b.*\\bon\\b|\\b(enable|activate|start|chalu|chalao|chala)\\b|\\bon( karo| kar do| kardo)?$") -> 1
        has(l, "\\b(toggle|flip)\\b") -> 3
        else -> 0
    }

    fun parse(s: String, l: String): Command? {
        // Display size / smallest width
        if (has(l, "(smallest|minimum|min) width|\\bdpi\\b|screen density|display size|\\bdensity\\b")) {
            Regex("(\\d{3,4})").find(l)?.let { return Command.SetValue(ValueKind.SMALLEST_WIDTH, it.value) }
        }
        // Animations
        if (has(l, "\\banimations?\\b")) {
            when {
                has(l, "\\b(off|disable|remove|stop|no)\\b") -> return Command.SetValue(ValueKind.ANIMATION, "off")
                has(l, "\\b(speed|fast|faster|quick|quicker|half|reduce|snappy)\\b|0 ?5|point five") -> return Command.SetValue(ValueKind.ANIMATION, "0.5")
                has(l, "\\b(on|enable|restore|normal|default|reset)\\b") -> return Command.SetValue(ValueKind.ANIMATION, "1")
            }
        }
        // Whole-phone actions
        val g = when {
            has(l, "^(go back|back|press back|navigate back|previous screen|go to previous( screen| page)?)$") -> GlobalKey.BACK
            has(l, "^(go home|home|go to home( screen)?|press home|show home screen|take me home|home screen)$") -> GlobalKey.HOME
            has(l, "^(recents?|recent apps|show recents?|show recent apps|open recents?|open recent apps|app switcher|switch apps)$") -> GlobalKey.RECENTS
            has(l, "(pull down|drop down|open|show) (the )?(notification (shade|panel|bar|drawer)|status bar)|^(open|pull down) notifications?$") -> GlobalKey.NOTIFICATIONS
            has(l, "quick (settings|panel|toggles|tiles)|control (center|panel)") -> GlobalKey.QUICK_SETTINGS
            has(l, "\\b(screenshot|screen shot|screen capture)\\b") -> GlobalKey.SCREENSHOT
            has(l, "power (menu|options|dialog)|shut ?down menu|restart menu") -> GlobalKey.POWER_MENU
            has(l, "split screen|multi window") -> GlobalKey.SPLIT
            else -> null
        }
        if (g != null) return Command.GlobalAction(g)

        Regex("^(?:scroll|swipe|move|go)\\s+(down|up)(?: a bit| more| a little| the page| page| the screen)?$").find(l)?.let {
            return Command.UiScroll(it.groupValues[1] == "down")
        }
        if (has(l, "read (the |this |my )?(screen|page)|what(s| is) on (the |my )?screen|what do you see|read this")) return Command.ReadScreen

        // App chores
        Regex("^(?:force stop|force close|kill)\\s+(?:the\\s+)?(.+?)(?: app)?$").find(l)?.let { return Command.AppAction(AppOp.FORCE_STOP, it.groupValues[1].trim()) }
        Regex("^clear\\s+(?:the\\s+)?cache\\s+(?:of|for)\\s+(.+)$|^clear\\s+(.+?)\\s+cache$").find(l)?.let {
            val name = it.groupValues[1].ifEmpty { it.groupValues[2] }.removeSuffix(" app").trim()
            return Command.AppAction(AppOp.CLEAR_CACHE, name)
        }

        // Switches
        val t = targets.firstOrNull { it.rx.containsMatchIn(l) } ?: return null
        return when (verb(l)) {
            1 -> Command.Toggle(t.key, true)
            2 -> Command.Toggle(t.key, false)
            3 -> Command.Toggle(t.key, null)
            else -> null
        }
    }

    /** Looser phrases, tried just before "open <app>" so they never steal a clearer command. */
    fun late(s: String, l: String): Command? {
        Regex("^(?:remind me|reminder)\\s+(?:to |about )?(.+?)\\s+(?:at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?|in\\s+(\\d+)\\s*(seconds?|minutes?|mins?|hours?))$", R).find(s.lowercase())?.let { m ->
            val text = m.groupValues[1].trim()
            if (m.groupValues[5].isNotEmpty()) {
                val n = m.groupValues[5].toInt()
                val u = m.groupValues[6]
                val secs = n * when { u.startsWith("sec") -> 1; u.startsWith("min") -> 60; else -> 3600 }
                return Command.Reminder(text, secs, 0, 0)
            }
            var h = m.groupValues[2].toInt()
            val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            val mer = m.groupValues[4]
            if (mer == "pm" && h < 12) h += 12
            if (mer == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return Command.Reminder(text, null, h, min)
        }
        Regex("^(?:tap|click|press|select|hit|touch|choose)(?: on)?(?: the)?\\s+(.+?)(?: button| option| icon| tab)?$").find(l)?.let {
            val label = it.groupValues[1].trim()
            if (label.isNotEmpty() && label !in setOf("play", "pause", "next", "stop", "home", "back")) return Command.UiTap(label)
        }
        Regex("^(?:type|enter|input)\\s+(.+)$", R).find(s)?.let { return Command.UiType(it.groupValues[1].trim()) }
        Regex("^play\\s+(.+?)(?:\\s+on\\s+(.+))?$").find(l)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty() && q !in setOf("music", "song", "songs", "something")) return Command.PlayMusic(q, m.groupValues[2].trim().ifEmpty { null })
        }
        // Hinglish: "youtube kholo", "camera chalao"
        Regex("^(.+?)\\s+(?:kholo|khol do|khol dijiye|khol|chalao|chala do|open karo|open kar do)$").find(l)?.let {
            return Command.OpenApp(it.groupValues[1].trim())
        }
        return null
    }
}

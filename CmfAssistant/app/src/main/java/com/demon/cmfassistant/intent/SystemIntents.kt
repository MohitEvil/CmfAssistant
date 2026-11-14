package com.demon.cmfassistant.intent

import com.demon.cmfassistant.action.SettingsTable

/** Phrases for phone controls beyond the basics. [l] is lower-case with apostrophes removed, [s] keeps the user's casing. */
object SystemIntents {
    private val R = RegexOption.IGNORE_CASE
    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15, "thirty" to 30, "a" to 1, "an" to 1)

    private fun has(l: String, pattern: String) = Regex(pattern).containsMatchIn(l)
    private fun pct(l: String) = Regex("(\\d{1,3})\\s*(?:percent|%)?").find(l)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    private val onW = "\\b(on|enable|start|activate)\\b"
    private val offW = "\\b(off|disable|stop|deactivate)\\b"

    fun parse(s: String, l: String): Command? {
        // Brightness
        if (has(l, "\\bbrightness\\b|\\b(?:dim|brighten)\\b|screen (?:brighter|dimmer)")) {
            return when {
                has(l, "\\bauto(?:matic)?\\b|adaptive") -> Command.Brightness(LevelAction.AUTO)
                has(l, "\\b(max|maximum|full|highest)\\b") -> Command.Brightness(LevelAction.SET, 100)
                has(l, "\\b(min|minimum|lowest)\\b") -> Command.Brightness(LevelAction.SET, 3)
                pct(l) != null -> Command.Brightness(LevelAction.SET, pct(l)!!)
                has(l, "\\b(down|lower|decrease|reduce|dim|dimmer|less|darker)\\b") -> Command.Brightness(LevelAction.DOWN)
                has(l, "\\b(up|raise|increase|brighten|brighter|more|higher)\\b") -> Command.Brightness(LevelAction.UP)
                else -> Command.OpenSetting("display")
            }
        }
        // Rotation
        if (has(l, "\\b(auto ?rotat\\w*|rotation|screen rotate|rotate (?:the )?screen)\\b")) {
            val on = when {
                has(l, "\\block\\b|$offW|portrait") -> false
                has(l, "\\bunlock\\b|$onW") -> true
                else -> null
            }
            return Command.Rotation(on)
        }
        // Screen timeout
        Regex("(?:screen timeout|timeout|screen (?:off|sleep)|sleep after|keep (?:the )?screen on).*?\\b(\\d+|one|two|three|five|ten|fifteen|thirty|a)\\s*(seconds?|secs?|minutes?|mins?|hours?)\\b")
            .find(l)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1]] ?: return@let
                val unit = m.groupValues[2]
                val secs = n * when { unit.startsWith("sec") -> 1; unit.startsWith("min") -> 60; else -> 3600 }
                return Command.ScreenTimeout(secs.coerceIn(15, 36000))
            }
        // Do Not Disturb
        if (has(l, "do not disturb|\\bdnd\\b|dont disturb")) {
            val on = when {
                has(l, offW) -> false
                has(l, onW) -> true
                else -> null
            }
            return Command.Dnd(on)
        }
        // Ringer mode
        if (has(l, "\\b(silent|vibrate|vibration)\\b") && has(l, "\\b(mode|phone|put|set|switch|ringer|go|turn)\\b")) {
            return Command.Ringer(if (has(l, "\\bvibrat\\w*")) RingerMode.VIBRATE else RingerMode.SILENT)
        }
        if (has(l, "\\b(ring mode|ringer|normal mode|sound mode)\\b")) {
            return when {
                has(l, "\\bsilent\\b") -> Command.Ringer(RingerMode.SILENT)
                has(l, "\\bvibrat\\w*") -> Command.Ringer(RingerMode.VIBRATE)
                else -> Command.Ringer(RingerMode.NORMAL)
            }
        }
        // Volume of a specific stream
        Regex("\\b(ring|ringer|ringtone|alarm|notification|call|media|music)\\s+volume\\b|\\bvolume of (?:the )?(ring|ringer|ringtone|alarm|notification|call|media|music)").find(l)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val stream = when (w) {
                "ring", "ringer", "ringtone" -> VolStream.RING
                "alarm" -> VolStream.ALARM
                "notification" -> VolStream.NOTIFICATION
                "call" -> VolStream.CALL
                else -> VolStream.MEDIA
            }
            val p = pct(l.replace(m.value, ""))
            return when {
                p != null -> Command.Volume(VolumeAction.SET, p, stream)
                has(l, "\\b(down|lower|decrease|reduce|less|quieter)\\b") -> Command.Volume(VolumeAction.DOWN, 0, stream)
                has(l, "\\bmute\\b") -> Command.Volume(VolumeAction.MUTE, 0, stream)
                has(l, "\\b(max|full|maximum)\\b") -> Command.Volume(VolumeAction.SET, 100, stream)
                else -> Command.Volume(VolumeAction.UP, 0, stream)
            }
        }
        // Lock
        if (Regex("^(?:lock|turn off|switch off)(?: the| my)?(?: phone| screen| device| display)?$").matches(l) && l != "turn off") {
            return Command.LockScreen
        }
        // Notifications
        if (has(l, "\\b(clear|dismiss|remove)\\b.*\\bnotifications?\\b")) return Command.Notifications(true)
        if (has(l, "\\b(read|check|show|what are|any|whats in)\\b.*\\bnotifications?\\b|^notifications$")) return Command.Notifications(false)
        // Calendar
        if (has(l, "\\bwhats on my (?:calendar|schedule|agenda)\\b|\\bmy (?:schedule|agenda)\\b|\\bevents? (?:today|for today)\\b|\\bcalendar today\\b")) return Command.Agenda
        Regex("^(?:add|create|make|schedule|new)\\s+(?:an?\\s+)?(?:calendar\\s+)?(?:event|meeting|appointment|reminder)\\s*(?:called|named|for|about|:)?\\s*(.*)$", R).find(s)?.let {
            return Command.AddEvent(it.groupValues[1].trim())
        }
        // Email
        Regex("^(?:write |compose |send |draft )?(?:an? )?e-?mail(?: to (\\S+@\\S+|\\S+))?(?: (?:saying|about|that says|that) (.+))?$", R).find(s)?.let {
            return Command.Email(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        // Websites
        Regex("^(?:open|go to|visit|browse)\\s+(?:the\\s+)?(?:website\\s+)?(\\S+\\.(?:com|in|org|net|io|co|app|dev|gov|edu)(?:/\\S*)?)$", R).find(s)?.let {
            return Command.OpenUrl(it.groupValues[1])
        }
        // Wallpaper
        if (has(l, "\\b(change|set|new|pick)\\b.*\\bwallpaper\\b|^wallpapers?$")) return Command.Wallpaper
        // Alarms list
        if (has(l, "\\b(show|list|open|see)\\b.*\\balarms\\b|^alarms$")) return Command.ShowAlarms
        // Status questions
        when {
            has(l, "\\b(storage|free space|disk space)\\b") && has(l, "\\b(how much|left|free|available|space|check|status)\\b") -> return Command.Status(StatusKind.STORAGE)
            has(l, "\\b(ram|memory)\\b") && has(l, "\\b(how much|left|free|available|usage|check|status)\\b") -> return Command.Status(StatusKind.RAM)
            has(l, "\\bam i (?:online|connected)\\b|internet (?:status|connection)|network status|\\bis (?:the )?internet\\b") -> return Command.Status(StatusKind.NETWORK)
            has(l, "\\bis (?:the )?(?:wi ?fi|wifi) (?:on|off|enabled)\\b|wi ?fi status") -> return Command.Status(StatusKind.WIFI)
            has(l, "\\bis (?:the )?bluetooth (?:on|off|enabled)\\b|bluetooth status") -> return Command.Status(StatusKind.BLUETOOTH)
            has(l, "\\bis airplane mode (?:on|off)\\b|airplane mode status") -> return Command.Status(StatusKind.AIRPLANE)
            has(l, "\\bis (?:do not disturb|dnd) (?:on|off)\\b") -> return Command.Status(StatusKind.DND)
            has(l, "\\bwhat(?:s| is) (?:the )?brightness\\b|brightness level") -> return Command.Status(StatusKind.BRIGHTNESS)
        }
        // App info / uninstall
        Regex("^(?:uninstall|delete app|remove app)\\s+(?:the\\s+)?(.+?)(?: app)?$", R).find(s)?.let {
            return Command.AppAction(AppOp.UNINSTALL, it.groupValues[1].trim())
        }
        Regex("^(?:app info|info (?:about|for|of)|details (?:of|for)|show info (?:of|for))\\s+(?:the\\s+)?(.+?)(?: app)?$", R).find(s)?.let {
            return Command.AppAction(AppOp.INFO, it.groupValues[1].trim())
        }
        // Clipboard
        if (has(l, "\\b(read|what(?:s| is)(?: on| in)?) (?:my |the )?clipboard\\b|^paste$")) return Command.ReadClipboard
        Regex("^copy\\s+(.+?)(?:\\s+to (?:the |my )?clipboard)?$", R).find(s)?.let {
            return Command.CopyText(it.groupValues[1].trim())
        }
        // Any settings page: "open display settings", "bluetooth settings", "settings for sound", "airplane mode"
        Regex("^(?:open |show |go to |take me to |launch )?(?:the )?(.+?) settings?$", R).find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && name !in setOf("phone", "system", "open", "show", "go to", "launch", "take me to", "the")) return Command.OpenSetting(name)
        }
        Regex("^(?:open |show |go to )?settings (?:for|of|to) (.+)$", R).find(l)?.let { return Command.OpenSetting(it.groupValues[1].trim()) }
        val bare = l.removePrefix("open ").removePrefix("show ").removePrefix("go to ").removePrefix("the ").trim()
        if (bare != "settings" && bare.length > 2 && SettingsTable.isName(bare) && has(l, "\\b(mode|options|access|saver|data|nfc|vpn|hotspot|tethering|roaming|light)\\b")) {
            return Command.OpenSetting(bare)
        }
        return null
    }
}

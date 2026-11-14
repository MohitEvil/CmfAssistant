package com.demon.cmfassistant.ai

import com.demon.cmfassistant.action.SettingsTable
import com.demon.cmfassistant.intent.Canon

/**
 * Offline know-how about the phone and about this assistant: where settings live, what each one does, what the
 * assistant can do. No network, no AI. Returns null when the question needs real knowledge.
 */
object Knowledge {
    private val paths = mapOf(
        "wifi" to "Settings, Network and internet, Internet. Or pull down quick settings and long-press the Wi-Fi tile.",
        "bluetooth" to "Settings, Connected devices, Connection preferences, Bluetooth.",
        "airplane" to "Settings, Network and internet, Airplane mode.",
        "network" to "Settings, Network and internet.",
        "mobiledata" to "Settings, Network and internet, SIMs.",
        "hotspot" to "Settings, Network and internet, Hotspot and tethering.",
        "vpn" to "Settings, Network and internet, VPN.",
        "nfc" to "Settings, Connected devices, Connection preferences, NFC.",
        "cast" to "Settings, Connected devices, Connection preferences, Cast.",
        "display" to "Settings, Display.",
        "nightlight" to "Settings, Display, Night light or Eye comfort.",
        "sound" to "Settings, Sound and vibration.",
        "vibration" to "Settings, Sound and vibration, Vibration and haptics.",
        "battery" to "Settings, Battery, Battery saver.",
        "batteryusage" to "Settings, Battery, Battery usage.",
        "storage" to "Settings, Storage.",
        "location" to "Settings, Location.",
        "security" to "Settings, Security and privacy.",
        "privacy" to "Settings, Security and privacy, Privacy.",
        "biometric" to "Settings, Security and privacy, Device unlock, Face or fingerprint unlock.",
        "apps" to "Settings, Apps.",
        "allapps" to "Settings, Apps, See all apps.",
        "defaultapps" to "Settings, Apps, Default apps.",
        "notifications" to "Settings, Notifications.",
        "accessibility" to "Settings, Accessibility.",
        "datetime" to "Settings, System, Date and time.",
        "language" to "Settings, System, Languages.",
        "keyboard" to "Settings, System, Keyboard.",
        "accounts" to "Settings, Passwords and accounts.",
        "sync" to "Settings, Passwords and accounts, Automatically sync data.",
        "developer" to "Settings, System, Developer options. If it isn't there, open About phone and tap Build number seven times. I can do that for you.",
        "about" to "Settings, About phone.",
        "update" to "Settings, System, Software update.",
        "assistant" to "Settings, Apps, Default apps, Digital assistant app.",
        "datausage" to "Settings, Network and internet, SIMs, App data usage.",
        "homeapp" to "Settings, Apps, Default apps, Home app.",
        "fontsize" to "Settings, Display, Display size and text."
    )

    private val about = mapOf(
        "wifi" to "Wi-Fi connects you to wireless networks. I can switch it on or off for you.",
        "bluetooth" to "Bluetooth pairs earphones, watches and speakers. I can turn it on or off.",
        "airplane" to "Airplane mode switches off all radios at once.",
        "hotspot" to "Hotspot shares your mobile data with other devices.",
        "developer" to "Developer options hold advanced settings like USB debugging, animation speed and display size (smallest width). I can switch it on and change those values.",
        "nfc" to "NFC is for tap to pay and quick pairing.",
        "location" to "Location lets apps know where you are. GPS and network both feed it.",
        "dnd" to "Do not disturb silences calls and alerts.",
        "battery" to "Battery saver limits background work to make the charge last longer.",
        "storage" to "Storage shows what's using space and lets you clear it.",
        "accessibility" to "Accessibility holds screen readers and helpers. I use it, if you allow it, to tap and scroll for you in the background."
    )

    private val categories = listOf(
        "Apps and settings" to "open any app or settings page, even if you say it a bit differently, find things by name",
        "Switches" to "Wi-Fi, Bluetooth, mobile data, hotspot, airplane mode, NFC, location, battery saver, dark mode, developer options, USB debugging",
        "Sound and screen" to "volume for each stream, silent and vibrate, do not disturb, brightness, rotation, screen timeout, animations, display size",
        "Screen control" to "go back, go home, recents, notifications, quick settings, screenshot, scroll, tap a button, type text, read what's on the screen, find the search bar",
        "Communication" to "call, message, email, contacts by name, calendar events and reminders",
        "Everyday" to "timers, alarms, music, flashlight, camera, battery and storage status, clipboard, maps and web search",
        "Chat" to "small talk, jokes, facts, riddles, simple maths, and how I'm doing"
    )

    fun capabilities(aiOn: Boolean): String =
        "I can " + categories.joinToString(". ") { "${it.first.lowercase()}: ${it.second}" } + "." +
            if (aiOn) " And for general questions I can use AI." else ""

    private fun short(aiOn: Boolean): String =
        "I can open apps and settings, flip switches like Wi-Fi and Bluetooth, control volume and brightness, tap and scroll your screen, read what's on it, set timers and alarms, call and message, play music and chat." +
            if (aiOn) " I can also answer general questions." else " Ask me what I can do with any of those for more."

    private fun entryFor(name: String): SettingsTable.Entry? = SettingsTable.resolve(name)?.takeIf { it.score >= 0.8 }?.entry

    fun answer(raw: String, aiOn: Boolean): String? {
        val t = Canon.phrase(raw).replace(Regex("[?!.]+$"), "").trim()
        if (t.isEmpty()) return null

        // What can you do?
        if (Regex("^(?:what|which) (?:all )?(?:can you|do you|are you able to|are your)(?: do| help| support| know)?(?: with)?$|what are your (?:features|abilities|capabilities|commands|skills)|list (?:your |all )?(?:commands|features|abilities)|what commands|what can i (?:ask|say)|how (?:do i|to|can i) use you|^help me$|^help$|^what do you do$").containsMatchIn(t)) {
            return short(aiOn)
        }
        Regex("what (?:can|do) you (?:do|know) (?:with|about|on) (.+)").find(t)?.let { m ->
            val name = m.groupValues[1]
            categories.firstOrNull { name in it.first.lowercase() || it.first.lowercase() in name }?.let { return "${it.first}: ${it.second}." }
            entryFor(name)?.let { e -> return aboutEntry(e) }
        }
        Regex("^(?:tell me about|explain|what is|whats|what does|what are|what do you know about|describe)(?: the)? (.+?)(?: do| mean| settings?| option| options)?$").find(t)?.let { m ->
            entryFor(m.groupValues[1])?.let { e -> if (e.key in about || e.key in paths) return aboutEntry(e) }
        }

        // Where is a setting?
        Regex("^(?:where (?:is|are|can i find|do i find|would i find|to find)|how (?:do i|can i|to) (?:find|get to|reach|locate|open|enable|turn on|use|access|go to)|which menu has|in which menu is|whats the path (?:to|for)|path to)(?: the| my)? (.+?)(?: settings?| option| options| menu| page)?(?: in (?:the )?(?:phone|settings))?$").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val e = entryFor(name)
            if (e != null) {
                val p = paths[e.key]
                return if (p != null) "${e.label} is usually under $p Say open ${e.label} and I'll take you there." else "Say open ${e.label} and I'll take you there."
            }
        }

        // Can you ...?
        Regex("^(?:can|could|do|are) you (?:also |really )?(?:able to )?(.+)$").find(t)?.let { m ->
            val what = m.groupValues[1]
            return when {
                Regex("(wifi|bluetooth|airplane|hotspot|nfc|location|mobile data|dark mode|battery saver|developer options|usb debugging)").containsMatchIn(what) ->
                    "Yes. Say turn on or turn off, and the name. I switch it by opening its page and tapping the switch, which needs Accessibility access turned on."
                Regex("(back|home|recents|screenshot|scroll|tap|click|type|read|screen|search bar|button)").containsMatchIn(what) ->
                    "Yes, with Accessibility access I can go back or home, scroll, tap buttons, type, take screenshots and read what's on screen."
                Regex("(call|phone|dial)").containsMatchIn(what) -> "Yes. Say call and a name from your contacts."
                Regex("(message|text|sms|whatsapp)").containsMatchIn(what) -> "Yes. Say message, a name, and what to say. I'll check with you before sending."
                Regex("(music|song|play)").containsMatchIn(what) -> "Yes. Say play and a song or artist."
                Regex("(timer|alarm|remind)").containsMatchIn(what) -> "Yes. Try set a timer for ten minutes, or remind me to call mom at six."
                Regex("(kill|close|force stop|stop apps?)").containsMatchIn(what) -> "I can go to the home screen, and force stop an app through its info page. Say force stop, and the app name."
                Regex("(root|wipe|factory reset|delete everything)").containsMatchIn(what) -> "No. I don't have root and I won't wipe or reset your phone."
                Regex("(joke|story|sing|chat|talk|riddle|fact)").containsMatchIn(what) -> "Of course. Ask me for a joke, a riddle or a fun fact, or just talk."
                else -> null
            }
        }
        return null
    }

    private fun aboutEntry(e: SettingsTable.Entry): String {
        val d = about[e.key]
        val p = paths[e.key]
        return buildString {
            append(d ?: "${e.label} is one of the settings pages.")
            if (p != null) append(" You'll usually find it under $p")
            append(" Say open ${e.label} and I'll take you there.")
        }
    }
}

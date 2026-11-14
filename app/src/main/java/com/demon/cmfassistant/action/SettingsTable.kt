package com.demon.cmfassistant.action

import com.demon.cmfassistant.intent.Canon

/** Every Settings page the assistant can open, with the names people use for it. */
object SettingsTable {
    class Entry(val key: String, val label: String, val action: String, val names: List<String>)

    private const val A = "android.settings."

    val all: List<Entry> = listOf(
        Entry("wifi", "Wi-Fi", "PANEL_INTERNET", listOf("wifi", "wi fi", "internet", "wireless", "wi-fi", "wlan", "wifi networks", "wireless internet")),
        Entry("bluetooth", "Bluetooth", A + "BLUETOOTH_SETTINGS", listOf("bluetooth", "bt")),
        Entry("airplane", "Airplane mode", A + "AIRPLANE_MODE_SETTINGS", listOf("airplane", "airplane mode", "flight mode", "flight")),
        Entry("network", "Network", A + "WIRELESS_SETTINGS", listOf("network", "networks", "connections", "wireless and network")),
        Entry("mobiledata", "Mobile network", A + "NETWORK_OPERATOR_SETTINGS", listOf("mobile data", "mobile network", "cellular", "sim", "data")),
        Entry("roaming", "Data roaming", A + "DATA_ROAMING_SETTINGS", listOf("roaming", "data roaming")),
        Entry("hotspot", "Hotspot and tethering", A + "TETHER_SETTINGS", listOf("hotspot", "tethering", "portable hotspot")),
        Entry("vpn", "VPN", A + "VPN_SETTINGS", listOf("vpn")),
        Entry("nfc", "NFC", A + "NFC_SETTINGS", listOf("nfc")),
        Entry("nfcpay", "NFC payment", A + "NFC_PAYMENT_SETTINGS", listOf("nfc payment", "tap to pay", "contactless")),
        Entry("cast", "Cast", A + "CAST_SETTINGS", listOf("cast", "screen cast", "casting")),
        Entry("display", "Display", A + "DISPLAY_SETTINGS", listOf("display", "screen", "brightness", "dark mode", "dark theme")),
        Entry("nightlight", "Night light", A + "NIGHT_DISPLAY_SETTINGS", listOf("night light", "night mode", "eye comfort")),
        Entry("sound", "Sound", A + "SOUND_SETTINGS", listOf("sound", "sounds", "audio", "ringtone", "volume")),
        Entry("battery", "Battery saver", A + "BATTERY_SAVER_SETTINGS", listOf("battery", "battery saver", "power saving", "power saver")),
        Entry("batteryopt", "Battery optimisation", A + "IGNORE_BATTERY_OPTIMIZATION_SETTINGS", listOf("battery optimization", "battery optimisation")),
        Entry("storage", "Storage", A + "INTERNAL_STORAGE_SETTINGS", listOf("storage", "memory", "internal storage")),
        Entry("location", "Location", A + "LOCATION_SOURCE_SETTINGS", listOf("location", "gps")),
        Entry("security", "Security", A + "SECURITY_SETTINGS", listOf("security", "lock screen", "screen lock")),
        Entry("privacy", "Privacy", A + "PRIVACY_SETTINGS", listOf("privacy")),
        Entry("biometric", "Fingerprint and face", A + "BIOMETRIC_ENROLL", listOf("fingerprint", "face unlock", "biometric", "biometrics")),
        Entry("apps", "Apps", A + "APPLICATION_SETTINGS", listOf("apps", "app", "applications")),
        Entry("allapps", "All apps", A + "MANAGE_ALL_APPLICATIONS_SETTINGS", listOf("all apps", "manage apps", "installed apps")),
        Entry("defaultapps", "Default apps", A + "MANAGE_DEFAULT_APPS_SETTINGS", listOf("default apps", "default app", "default browser")),
        Entry("notifications", "Notifications", A + "NOTIFICATION_SETTINGS", listOf("notification", "notifications")),
        Entry("notifaccess", "Notification access", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", listOf("notification access", "notification listener")),
        Entry("dndaccess", "Do Not Disturb access", "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS", listOf("do not disturb access", "dnd access", "do not disturb", "dnd")),
        Entry("accessibility", "Accessibility", A + "ACCESSIBILITY_SETTINGS", listOf("accessibility")),
        Entry("datetime", "Date and time", A + "DATE_SETTINGS", listOf("date", "time", "date and time", "time zone", "timezone")),
        Entry("language", "Language", A + "LOCALE_SETTINGS", listOf("language", "languages", "locale")),
        Entry("keyboard", "Keyboard", A + "INPUT_METHOD_SETTINGS", listOf("keyboard", "input method", "keyboards")),
        Entry("accounts", "Add account", A + "ADD_ACCOUNT_SETTINGS", listOf("account", "accounts", "add account")),
        Entry("sync", "Sync", A + "SYNC_SETTINGS", listOf("sync", "backup and sync", "auto sync")),
        Entry("print", "Printing", "android.settings.ACTION_PRINT_SETTINGS", listOf("print", "printing", "printer")),
        Entry("developer", "Developer options", A + "APPLICATION_DEVELOPMENT_SETTINGS", listOf("developer", "developer options", "developer option", "dev options", "dev settings", "developer mode", "developer menu", "developer tools")),
        Entry("about", "About phone", A + "DEVICE_INFO_SETTINGS", listOf("about", "about phone", "about device", "device info", "phone info")),
        Entry("usage", "Usage access", A + "USAGE_ACCESS_SETTINGS", listOf("usage access", "usage")),
        Entry("overlay", "Display over other apps", "android.settings.action.MANAGE_OVERLAY_PERMISSION", listOf("overlay", "draw over apps", "display over other apps")),
        Entry("writesettings", "Modify system settings", "android.settings.action.MANAGE_WRITE_SETTINGS", listOf("write settings", "modify system settings")),
        Entry("datausage", "Data usage", A + "DATA_USAGE_SETTINGS", listOf("data usage", "data saver", "internet usage")),
        Entry("update", "System update", A + "SYSTEM_UPDATE_SETTINGS", listOf("update", "updates", "system update", "software update", "ota")),
        Entry("homeapp", "Home screen", A + "HOME_SETTINGS", listOf("home screen", "launcher", "default launcher", "home app")),
        Entry("batteryusage", "Battery usage", "android.intent.action.POWER_USAGE_SUMMARY", listOf("battery usage", "battery stats", "battery consumption")),
        Entry("captions", "Captions", A + "CAPTIONING_SETTINGS", listOf("captions", "subtitles", "live caption")),
        Entry("assistant", "Default assistant", A + "VOICE_INPUT_SETTINGS", listOf("assistant", "digital assistant", "default assistant", "voice input")),
        Entry("vibration", "Sound and vibration", A + "SOUND_SETTINGS", listOf("vibration", "vibrate", "haptics", "haptic feedback")),
        Entry("fontsize", "Display and text size", A + "DISPLAY_SETTINGS", listOf("font size", "text size", "display size", "font")),
        Entry("general", "Settings", A + "SETTINGS", listOf("settings", "phone", "system", "main"))
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Exact-name lookup. */
    fun exact(name: String): Entry? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return all.firstOrNull { e -> e.key == n || e.names.any { norm(it) == n } }
    }

    /** Closest entry by spelling, or null if nothing is close enough. */
    fun fuzzy(name: String): Entry? {
        val n = norm(name)
        if (n.length < 3) return null
        var best: Entry? = null
        var score = 0.0
        for (e in all) for (alias in e.names) {
            val s = Fuzzy.similarity(n, norm(alias))
            if (s > score) { score = s; best = e }
        }
        return if (score >= 0.7) best else null
    }

    class Match(val entry: Entry, val score: Double)

    private val filler = setOf("settings", "setting", "page", "menu", "the", "my", "a", "an", "of", "for", "to", "in", "on", "option", "options", "app", "apps", "section", "screen")

    private fun stem(w: String) = w.removeSuffix("s")

    /** Words of [text] without filler, as singular stems: "the developer options page" -> [developer]. */
    private fun keyWords(text: String): List<String> =
        Canon.phrase(text).split(" ").filter { it.isNotEmpty() && it !in filler }.map { stem(it.filter { c -> c.isLetterOrDigit() }) }.filter { it.isNotEmpty() }

    /**
     * Best page for what the user said, forgiving about spelling, plurals, filler words and how speech
     * recognition splits words ("wi-fi", "developer option", "bluetooth settings"). Score 1.0 is certain.
     */
    fun resolve(name: String): Match? {
        val q = Canon.phrase(name)
        val qn = norm(q)
        if (qn.length < 2) return null
        exact(q)?.let { return Match(it, 1.0) }
        val qWords = keyWords(q)
        val qs = qWords.joinToString("")
        var best: Entry? = null
        var score = 0.0
        for (e in all) for (alias in e.names + e.label) {
            val a = keyWords(alias).joinToString("")
            val an = norm(alias)
            var s = 0.0
            if (qs.isNotEmpty() && qs == a) s = 0.97
            else if (qn == an || stem(qn) == stem(an)) s = 0.98
            else if (qs.length >= 3 && a.length >= 3 && (a.contains(qs) || qs.contains(a))) s = 0.86 * minOf(qs.length, a.length) / maxOf(qs.length, a.length) + 0.1
            if (qs.length >= 3 && a.length >= 3) s = maxOf(s, Fuzzy.similarity(qs, a) * 0.98)
            if (s > score) { score = s; best = e }
        }
        return best?.let { Match(it, score) }?.takeIf { it.score >= 0.6 }
    }

    /** True when [text] is exactly one of the page names (used to catch "airplane mode", "hotspot"). */
    fun isName(text: String) = exact(text) != null
}

object Fuzzy {
    /** 1.0 = identical, 0.0 = nothing in common. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

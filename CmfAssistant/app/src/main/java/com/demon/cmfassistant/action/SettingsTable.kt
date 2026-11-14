package com.demon.cmfassistant.action

/** Every Settings page the assistant can open, with the names people use for it. */
object SettingsTable {
    class Entry(val key: String, val label: String, val action: String, val names: List<String>)

    private const val A = "android.settings."

    val all: List<Entry> = listOf(
        Entry("wifi", "Wi-Fi", "PANEL_INTERNET", listOf("wifi", "wi fi", "internet", "wireless", "wi-fi")),
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
        Entry("developer", "Developer options", A + "APPLICATION_DEVELOPMENT_SETTINGS", listOf("developer", "developer options", "dev options")),
        Entry("about", "About phone", A + "DEVICE_INFO_SETTINGS", listOf("about", "about phone", "about device", "device info", "phone info")),
        Entry("usage", "Usage access", A + "USAGE_ACCESS_SETTINGS", listOf("usage access", "usage")),
        Entry("overlay", "Display over other apps", "android.settings.action.MANAGE_OVERLAY_PERMISSION", listOf("overlay", "draw over apps", "display over other apps")),
        Entry("writesettings", "Modify system settings", "android.settings.action.MANAGE_WRITE_SETTINGS", listOf("write settings", "modify system settings")),
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

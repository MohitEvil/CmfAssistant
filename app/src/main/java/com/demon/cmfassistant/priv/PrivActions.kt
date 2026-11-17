package com.demon.cmfassistant.priv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.demon.cmfassistant.action.ActionResult
import com.demon.cmfassistant.intent.Command
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** System controls that go deeper than normal Android access. They use Shizuku or root when granted, and otherwise do what they can without. */
class PrivActions(
    private val context: Context,
    private val findApp: (String) -> Pair<String, String>?,
    private val launch: (Intent) -> Boolean
) {
    /** Runs one shell command through Shizuku or root. False when neither is available or it failed. */
    suspend fun shell(cmd: String): Boolean = Priv.available() && Priv.sh(cmd).ok

    private val locationPerms = listOf(
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"
    )

    private fun gb(b: Long) = "%.1f GB".format(b / 1_073_741_824.0)

    suspend fun run(c: Command.Priv): ActionResult = when (c.op) {
        "LOC_AUDIT" -> locationAudit()
        "STORAGE" -> storage()
        "REVOKE_LOC" -> revokeLocation(c.a)
        "CLEAR_CACHE_ALL" -> clearCache()
        "CLEAR_DATA" -> clearData(c)
        "ANIMATIONS" -> animations(c.a == "on")
        "KILL_ALL" -> needPriv("close all background apps") ?: sh("am kill-all", "Closed the background apps.")
        "REBOOT" -> confirmThen(c, "Restart the phone?", "Restart the phone now?") { needPriv("restart the phone") ?: sh("reboot", "Restarting.") }
        "SHELL" -> confirmThen(c, "Run this command?", "Run the command ${c.a}?", c.a) {
            needPriv("run commands") ?: Priv.sh(c.a, 30).let { r ->
                val t = r.text.take(300).ifBlank { if (r.ok) "Done, no output." else "It failed with code ${r.code}." }
                if (r.ok) ActionResult.Success(t) else ActionResult.Failure(t)
            }
        }
        "TAP" -> needPriv("tap exact coordinates") ?: sh("input tap " + c.a.replace(",", " "), "Tapped.")
        else -> ActionResult.Failure("I don't know that system action.")
    }

    private fun needPriv(doing: String): ActionResult? =
        if (Priv.available()) null else ActionResult.Failure("To $doing I need Shizuku or root. Turn one on in Settings, under Advanced access. Everything else works without it.")

    private suspend fun sh(cmd: String, ok: String): ActionResult {
        val r = Priv.sh(cmd)
        return if (r.ok) ActionResult.Success(ok) else ActionResult.Failure("That didn't work: ${r.text.take(120).ifBlank { "code ${r.code}" }}")
    }

    private suspend fun confirmThen(c: Command.Priv, title: String, spoken: String, detail: String = "", go: suspend () -> ActionResult): ActionResult {
        if (c.b == "ok") return go()
        return ActionResult.Confirm(title, detail.ifBlank { title }, spoken, c.copy(b = "ok"), "Okay, I won't.")
    }

    private fun label(pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Which apps may read your location, and which of those can do it in the background. */
    private fun locationAudit(): ActionResult {
        val pm = context.packageManager
        val fg = ArrayList<String>()
        val bg = ArrayList<String>()
        for (p in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val info = p.applicationInfo ?: continue
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) continue
            val req = p.requestedPermissions ?: continue
            val flags = p.requestedPermissionsFlags ?: continue
            fun granted(name: String): Boolean {
                val i = req.indexOf(name)
                return i >= 0 && (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }
            val name = label(p.packageName)
            if (granted(locationPerms[2])) bg += name
            else if (granted(locationPerms[0]) || granted(locationPerms[1])) fg += name
        }
        if (fg.isEmpty() && bg.isEmpty()) return ActionResult.Success("No installed app currently has location access.")
        val parts = ArrayList<String>()
        if (bg.isNotEmpty()) parts += "${bg.size} can use it all the time: ${bg.sorted().take(8).joinToString(", ")}"
        if (fg.isNotEmpty()) parts += "${fg.size} only while you use them: ${fg.sorted().take(8).joinToString(", ")}"
        return ActionResult.Success("Location access audit. " + parts.joinToString(". ") + ". Say revoke location from an app name to remove one.")
    }

    private suspend fun revokeLocation(name: String): ActionResult {
        val hit = findApp(name) ?: return ActionResult.Failure("I couldn't find an app called $name.")
        if (!Priv.available()) {
            return ActionResult.Success("I can't change permissions myself without Shizuku or root. Opening ${hit.second}'s permissions so you can remove location.") {
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${hit.first}")))
            }
        }
        val cmd = locationPerms.joinToString("; ") { "pm revoke ${hit.first} $it" }
        Priv.sh(cmd)
        return ActionResult.Success("Removed location access from ${hit.second}.")
    }

    private fun storage(): ActionResult {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes
        val total = st.totalBytes
        return ActionResult.Success("You have ${gb(free)} free of ${gb(total)}. ${(100 - free * 100 / total)} percent is used.")
    }

    private suspend fun clearCache(): ActionResult {
        if (!Priv.available()) {
            return ActionResult.Success("Android doesn't let apps clear other apps' caches. Opening storage so you can clean up. With Shizuku or root I can do it in one go.") {
                launch(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }
        }
        val before = StatFs(Environment.getDataDirectory().path).availableBytes
        val r = Priv.sh("pm trim-caches 999999999999", 60)
        val after = StatFs(Environment.getDataDirectory().path).availableBytes
        val freed = (after - before).coerceAtLeast(0)
        return if (r.ok) ActionResult.Success(if (freed > 1_000_000) "Cleared app caches and freed about ${"%.0f".format(freed / 1_048_576.0)} MB." else "Cleared app caches.")
        else ActionResult.Failure("That didn't work: ${r.text.take(100)}")
    }

    private suspend fun clearData(c: Command.Priv): ActionResult {
        val hit = findApp(c.a) ?: return ActionResult.Failure("I couldn't find an app called ${c.a}.")
        if (!Priv.available()) return ActionResult.Success("Opening ${hit.second}'s storage. Tap Clear storage there.") {
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${hit.first}")))
        }
        return confirmThen(c, "Clear all data of ${hit.second}?", "This erases all data of ${hit.second}, like a fresh install. Continue?", "Logs you out and deletes its local data.") {
            sh("pm clear ${hit.first}", "Cleared all data of ${hit.second}.")
        }
    }

    private suspend fun animations(on: Boolean): ActionResult {
        val v = if (on) "1.0" else "0"
        if (!Priv.available()) return ActionResult.Success("I can't change animation speed without Shizuku or root. Opening developer options.") {
            launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        return sh("settings put global window_animation_scale $v; settings put global transition_animation_scale $v; settings put global animator_duration_scale $v",
            if (on) "Animations are back to normal." else "Animations are off, so the phone feels snappier.")
    }

    /** Fast route for plain on/off switches. Returns null when it can't help, so the normal way is used. */
    suspend fun quickToggle(key: String, on: Boolean): ActionResult? {
        if (!Priv.available()) return null
        val v = if (on) 1 else 0
        val cmd = when (key) {
            "wifi" -> "svc wifi ${if (on) "enable" else "disable"}"
            "data" -> "svc data ${if (on) "enable" else "disable"}"
            "bluetooth" -> "cmd bluetooth_manager ${if (on) "enable" else "disable"} || svc bluetooth ${if (on) "enable" else "disable"}"
            "nfc" -> "svc nfc ${if (on) "enable" else "disable"}"
            "airplane" -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
            "location" -> "cmd location set-location-enabled ${on}"
            "batterysaver" -> "settings put global low_power $v"
            "dark" -> "cmd uimode night ${if (on) "yes" else "no"}"
            "usbdebug" -> "settings put global adb_enabled $v"
            "stayawake" -> "settings put global stay_on_while_plugged_in ${if (on) 7 else 0}"
            "showtouches" -> "settings put system show_touches $v"
            else -> return null
        }
        val r = Priv.sh(cmd)
        if (!r.ok) return null
        val name = com.demon.cmfassistant.action.UiControl.specs[key]?.name ?: key
        return ActionResult.Success("$name is now ${if (on) "on" else "off"}.")
    }

    /** Force-stop / clear-cache without opening any settings screen. */
    suspend fun quickAppTask(op: String, pkg: String, label: String): ActionResult? {
        if (!Priv.available()) return null
        val cmd = when (op) {
            "FORCE_STOP" -> "am force-stop $pkg"
            "CLEAR_CACHE" -> "pm trim-caches 999999999999"
            else -> return null
        }
        val r = Priv.sh(cmd, 30)
        if (!r.ok) return null
        return ActionResult.Success(if (op == "FORCE_STOP") "Force stopped $label." else "Cleared caches.")
    }

    fun screenshotName() = "cmf_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
}

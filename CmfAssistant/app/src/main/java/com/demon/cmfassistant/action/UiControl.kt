package com.demon.cmfassistant.action

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.demon.cmfassistant.MainActivity
import com.demon.cmfassistant.PanelActivity
import com.demon.cmfassistant.access.CmfAccess
import com.demon.cmfassistant.intent.AppOp
import com.demon.cmfassistant.intent.GlobalKey
import com.demon.cmfassistant.intent.ValueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drives Android's own screens through the accessibility service. Recipes jump straight to what they need
 * (scroll-search a label, click, type) instead of walking menus step by step, then press Back to return.
 * Without the service, each recipe falls back to opening the right page for the user.
 */
class UiControl(context: Context) {
    private val app = context.applicationContext

    class ToggleSpec(
        val key: String,
        val name: String,
        val action: String,
        val labels: List<String>,
        val path: List<List<String>> = emptyList(),
        val confirm: List<String> = emptyList(),
        val longPage: Boolean = false
    )

    companion object {
        private const val DEV = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

        val specs: Map<String, ToggleSpec> = listOf(
            ToggleSpec("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS", listOf("Use Wi-Fi", "Wi-Fi")),
            ToggleSpec("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS", listOf("Use Bluetooth", "Bluetooth")),
            ToggleSpec("airplane", "Airplane mode", "android.settings.AIRPLANE_MODE_SETTINGS", listOf("Use Airplane mode", "Airplane mode")),
            ToggleSpec("data", "Mobile data", "android.settings.NETWORK_OPERATOR_SETTINGS", listOf("Mobile data")),
            ToggleSpec("hotspot", "Hotspot", "android.settings.TETHER_SETTINGS", listOf("Use Wi-Fi hotspot", "Wi-Fi hotspot"), path = listOf(listOf("Wi-Fi hotspot", "Hotspot"))),
            ToggleSpec("nfc", "NFC", "android.settings.NFC_SETTINGS", listOf("Use NFC", "NFC")),
            ToggleSpec("location", "Location", "android.settings.LOCATION_SOURCE_SETTINGS", listOf("Use location", "Location")),
            ToggleSpec("batterysaver", "Battery Saver", "android.settings.BATTERY_SAVER_SETTINGS", listOf("Use Battery Saver", "Battery Saver")),
            ToggleSpec("dark", "Dark theme", "android.settings.DISPLAY_SETTINGS", listOf("Dark theme", "Dark mode")),
            ToggleSpec("usbdebug", "USB debugging", DEV, listOf("USB debugging"), confirm = listOf("OK", "Allow"), longPage = true),
            ToggleSpec("stayawake", "Stay awake", DEV, listOf("Stay awake"), longPage = true),
            ToggleSpec("showtouches", "Show taps", DEV, listOf("Show taps", "Show touches"), longPage = true)
        ).associateBy { it.key }
    }

    fun available(): Boolean = CmfAccess.instance != null

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun needAccess(doing: String, fallback: Intent? = null) = ActionResult.Success(
        "To $doing myself I need Accessibility access. Opening it now. Turn on CMF Assistant, then ask again."
    ) {
        launch(fallback ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openPageFallback(name: String, intent: Intent) = ActionResult.Success(
        "Opening $name. Turn on Accessibility for me under Phone control access and I'll flip it myself next time."
    ) { launch(intent) }

    /** Makes sure the assistant's own window is out of the way so Back/Tap/Scroll reach the app underneath. */
    private suspend fun clearWay(svc: CmfAccess) {
        if (PanelActivity.hide()) {
            delay(300)
        } else if (MainActivity.resumed) {
            svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(380)
        }
    }

    private suspend fun back(svc: CmfAccess, times: Int) {
        repeat(times) {
            svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(140)
        }
    }

    // ---- Switches ----

    suspend fun toggle(key: String, on: Boolean?): ActionResult = withContext(Dispatchers.Default) {
        if (key == "dev") return@withContext developerOptions(on)
        val spec = specs[key] ?: return@withContext ActionResult.Failure("I don't know how to switch that.")
        val svc = CmfAccess.instance ?: return@withContext openPageFallback(spec.name, Intent(spec.action))
        if (!launch(Intent(spec.action))) return@withContext ActionResult.Failure("This phone has no ${spec.name} page.")

        var depth = 0
        for (step in spec.path) {
            val n = svc.waitFind(step, 3000) ?: return@withContext ActionResult.Failure("I couldn't find ${step.first()} on this phone.")
            svc.click(n)
            depth++
            delay(260)
        }
        val node = (if (spec.longPage) svc.scrollToFind(spec.labels) else svc.waitFind(spec.labels, 3000))
            ?: run {
                back(svc, depth + 1)
                return@withContext ActionResult.Failure("I couldn't find the ${spec.name} switch here. Its label may be different on this phone.")
            }
        val before = svc.switchState(node)
        val want = on ?: !(before ?: false)
        if (before == want) {
            back(svc, depth + 1)
            return@withContext ActionResult.Success("${spec.name} is already ${if (want) "on" else "off"}.")
        }
        svc.click(node)
        if (spec.confirm.isNotEmpty()) svc.waitFind(spec.confirm, 900)?.let { svc.click(it); delay(150) }
        delay(240)
        val after = svc.find(spec.labels)?.let { svc.switchState(it) }
        back(svc, depth + 1)
        if (after != null && after != want) {
            ActionResult.Failure("I tapped it, but ${spec.name} didn't change. It may need a confirmation on screen.")
        } else {
            ActionResult.Success("${spec.name} is now ${if (want) "on" else "off"}.")
        }
    }

    /** Developer options: flips the master switch, or unlocks it by tapping Build number seven times. */
    private suspend fun developerOptions(on: Boolean?): ActionResult {
        val svc = CmfAccess.instance ?: return openPageFallback("Developer options", Intent(DEV))
        launch(Intent(DEV))
        val sw = svc.waitFind(listOf("Use developer options", "Developer options"), 1400)
        val state = sw?.let { svc.switchState(it) }
        if (sw != null && state != null) {
            val want = on ?: !state
            if (state == want) {
                back(svc, 1)
                return ActionResult.Success("Developer options are already ${if (want) "on" else "off"}.")
            }
            svc.click(sw)
            delay(300)
            svc.waitFind(listOf("OK", "Turn off"), 600)?.let { if (!want) { svc.click(it); delay(150) } }
            back(svc, 1)
            return ActionResult.Success("Developer options are now ${if (want) "on" else "off"}.")
        }
        if (on == false) return ActionResult.Success("Developer options are already off.")
        // Not unlocked yet: tap Build number seven times in About phone.
        launch(Intent("android.settings.DEVICE_INFO_SETTINGS"))
        val build = svc.scrollToFind(listOf("Build number"), 20)
            ?: return ActionResult.Failure("I couldn't find Build number in About phone.")
        repeat(7) {
            svc.click(build)
            delay(110)
        }
        delay(300)
        return ActionResult.Success("I tapped Build number seven times. If your phone asks for your PIN or pattern, enter it and developer options will be on.")
    }

    // ---- Values ----

    suspend fun setValue(kind: ValueKind, value: String): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("change that", Intent(DEV))
        if (!launch(Intent(DEV))) return@withContext ActionResult.Failure("Developer options aren't available. Say “turn on developer options” first.")
        when (kind) {
            ValueKind.SMALLEST_WIDTH -> {
                val row = svc.scrollToFind(listOf("Smallest width", "Minimum width"), 24)
                    ?: return@withContext ActionResult.Failure("I couldn't find Smallest width. Are developer options on?")
                svc.click(row)
                svc.waitFind(listOf("dp"), 1500) // the dialog
                delay(150)
                if (!svc.setText(value)) {
                    back(svc, 1)
                    return@withContext ActionResult.Failure("I couldn't type into the Smallest width box.")
                }
                delay(120)
                (svc.waitFind(listOf("OK", "Apply", "Set"), 1500))?.let { svc.click(it) }
                delay(300)
                back(svc, 1)
                ActionResult.Success("Smallest width is now $value.")
            }
            ValueKind.ANIMATION -> {
                val option = when (value) {
                    "off" -> listOf("Animation off")
                    "0.5" -> listOf("Animation scale .5x", "Animation scale 0.5x")
                    else -> listOf("Animation scale 1x")
                }
                for (name in listOf("Window animation scale", "Transition animation scale", "Animator duration scale")) {
                    val row = svc.scrollToFind(listOf(name), 24) ?: continue
                    svc.click(row)
                    val choice = svc.waitFind(option, 1500)
                    if (choice != null) svc.click(choice) else back(svc, 1)
                    delay(180)
                }
                back(svc, 1)
                ActionResult.Success(
                    when (value) {
                        "off" -> "Animations are off. Everything should feel snappier."
                        "0.5" -> "Animations are twice as fast."
                        else -> "Animations are back to normal."
                    }
                )
            }
        }
    }

    // ---- Per-app chores ----

    suspend fun appTask(op: AppOp, pkg: String, label: String): ActionResult = withContext(Dispatchers.Default) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val svc = CmfAccess.instance ?: return@withContext needAccess("do that", details)
        if (!launch(details)) return@withContext ActionResult.Failure("Couldn't open $label's info.")
        when (op) {
            AppOp.FORCE_STOP -> {
                val b = svc.waitFind(listOf("Force stop"), 2500) ?: return@withContext ActionResult.Failure("I couldn't find Force stop for $label.")
                if (!b.isEnabled) {
                    back(svc, 1)
                    return@withContext ActionResult.Success("$label isn't running.")
                }
                svc.click(b)
                svc.waitFind(listOf("OK", "Force stop"), 1500)?.let { svc.click(it) }
                delay(200)
                back(svc, 1)
                ActionResult.Success("Stopped $label.")
            }
            AppOp.CLEAR_CACHE -> {
                val storage = svc.waitFind(listOf("Storage & cache", "Storage and cache", "Storage"), 2500)
                    ?: return@withContext ActionResult.Failure("I couldn't find Storage for $label.")
                svc.click(storage)
                val clear = svc.waitFind(listOf("Clear cache"), 2500)
                    ?: run { back(svc, 2); return@withContext ActionResult.Failure("I couldn't find Clear cache for $label.") }
                svc.click(clear)
                delay(250)
                back(svc, 2)
                ActionResult.Success("Cleared $label's cache.")
            }
            else -> ActionResult.Failure("Not something I can do here.")
        }
    }

    // ---- Anything on screen ----

    suspend fun global(key: GlobalKey): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("do that")
        if (key == GlobalKey.BACK) clearWay(svc) else if (key != GlobalKey.HOME) PanelActivity.hide().also { if (it) delay(250) }
        val ok = when (key) {
            GlobalKey.BACK -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
            GlobalKey.HOME -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            GlobalKey.RECENTS -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GlobalKey.NOTIFICATIONS -> svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GlobalKey.QUICK_SETTINGS -> svc.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GlobalKey.SCREENSHOT -> svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            GlobalKey.POWER_MENU -> svc.global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            GlobalKey.SPLIT -> svc.global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }
        if (ok) ActionResult.Success(
            when (key) {
                GlobalKey.BACK -> "Done."
                GlobalKey.HOME -> "Home."
                GlobalKey.RECENTS -> "Here are your recent apps."
                GlobalKey.NOTIFICATIONS -> "Here are your notifications."
                GlobalKey.QUICK_SETTINGS -> "Quick settings."
                GlobalKey.SCREENSHOT -> "Screenshot taken."
                GlobalKey.POWER_MENU -> "Power menu."
                GlobalKey.SPLIT -> "Split screen."
            }
        ) else ActionResult.Failure("Android wouldn't let me do that here.")
    }

    suspend fun lockScreen(): ActionResult? = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext null
        PanelActivity.hide()
        if (svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) ActionResult.Success("Locked.") else null
    }

    suspend fun tap(label: String): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("tap things")
        clearWay(svc)
        val n = svc.waitFind(listOf(label), 1200) ?: svc.scrollToFind(listOf(label), 8)
            ?: return@withContext ActionResult.Failure("I can't see “$label” on the screen.")
        if (svc.click(n)) ActionResult.Success("Tapped $label.") else ActionResult.Failure("I couldn't tap $label.")
    }

    suspend fun type(text: String): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("type for you")
        clearWay(svc)
        if (svc.setText(text)) ActionResult.Success("Typed it.") else ActionResult.Failure("There's no text box I can type into.")
    }

    suspend fun scroll(forward: Boolean): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("scroll")
        clearWay(svc)
        if (svc.scroll(forward)) ActionResult.Success(if (forward) "Scrolled down." else "Scrolled up.")
        else ActionResult.Failure("Nothing to scroll here.")
    }

    suspend fun readScreen(): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("read the screen")
        clearWay(svc)
        val t = svc.visibleText(14)
        if (t.isEmpty()) ActionResult.Failure("I can't see any text on this screen.")
        else ActionResult.Success("On screen: " + t.joinToString(". ") { it.take(80) })
    }
}

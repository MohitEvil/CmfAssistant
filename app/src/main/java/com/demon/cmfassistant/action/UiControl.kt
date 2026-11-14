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
import com.demon.cmfassistant.access.Ocr
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.AppOp
import com.demon.cmfassistant.intent.ClipOp
import com.demon.cmfassistant.intent.SwipeDir
import com.demon.cmfassistant.intent.TapKind
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
        AppLog.d("ui", "setValue $kind=$value")
        if (!launch(Intent(DEV))) return@withContext ActionResult.Failure("Developer options aren't available. Say “turn on developer options” first.")
        when (kind) {
            ValueKind.SMALLEST_WIDTH -> {
                var row = svc.scrollToFind(listOf("Smallest width", "Minimum width", "Display size"), 24)
                if (row == null) {
                    // Developer options may be locked: unlock them once, then look again.
                    developerOptions(true)
                    launch(Intent(DEV))
                    row = svc.scrollToFind(listOf("Smallest width", "Minimum width"), 24)
                }
                if (row == null) return@withContext ActionResult.Failure("I couldn't find Smallest width. Are developer options on?")
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

    private val searchWords = setOf("search", "search bar", "search box", "search field", "search icon", "search text box", "search input", "address bar", "url bar", "text box", "text field", "input box", "input field", "textbox")

    private fun isSearchLabel(label: String) = label.lowercase().trim() in searchWords

    private var lastTarget = ""

    private val shutterWords = setOf("shutter", "shutterbutton", "takephoto", "takeapicture", "takepicture", "takeaphoto", "capture", "capturebutton", "clickphoto", "clickpicture", "camerabutton", "photobutton")

    suspend fun tap(label: String, kind: TapKind = TapKind.TAP): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("tap things")
        clearWay(svc)
        var target = label.trim()
        if (target.lowercase() in setOf("it", "that", "this", "this one", "that one", "same")) target = lastTarget
        if (target.isEmpty()) return@withContext ActionResult.Failure("Which thing should I ${if (kind == TapKind.LONG) "press and hold" else if (kind == TapKind.DOUBLE) "double tap" else "tap"}?")
        AppLog.d("ui", "tap '$target' kind=$kind")
        if (target == "@first") {
            val f = svc.firstResult()
            return@withContext if (f != null && svc.click(f)) ActionResult.Success("Opened the first result.") else ActionResult.Failure("I can't see a result to open.")
        }
        val key = CmfAccess.norm(target)
        if (key in shutterWords) return@withContext takePhotoNow(svc, selfie = false, openCamera = false)
        lastTarget = target

        fun act(n: android.view.accessibility.AccessibilityNodeInfo): Boolean = when (kind) {
            TapKind.TAP -> svc.click(n)
            TapKind.LONG -> svc.longClick(n)
            TapKind.DOUBLE -> svc.centre(n)?.let { svc.doubleTapAt(it.first, it.second) } ?: false
        }
        val done = when (kind) { TapKind.TAP -> "Tapped $target."; TapKind.LONG -> "Held $target."; TapKind.DOUBLE -> "Double tapped $target." }

        if (isSearchLabel(target) || key == "search") {
            // After typing, "click search" means submit; before typing it means open the search box.
            val button = svc.find(listOf("Search")).takeIf { it != null && !it.isEditable }
            if (button != null && kind == TapKind.TAP && svc.findEditable()?.text?.isNotEmpty() == true && svc.click(button)) return@withContext ActionResult.Success(done)
            val f = svc.findEditable()
            if (f != null) {
                if (kind == TapKind.TAP && f.text?.isNotEmpty() == true && svc.imeEnter()) return@withContext ActionResult.Success("Searching.")
                if (act(f)) return@withContext ActionResult.Success(done)
            }
            svc.findSearchTrigger()?.let { if (act(it.first)) return@withContext ActionResult.Success(done) }
        }
        val n = svc.waitFind(listOf(target), 1000) ?: svc.scrollToFind(listOf(target), 6)
        if (n != null) {
            return@withContext if (act(n)) ActionResult.Success(done) else ActionResult.Failure("I couldn't ${if (kind == TapKind.TAP) "tap" else "press"} $target.")
        }
        // Nothing in the accessibility tree: read the pixels and act where the words are.
        val line = Ocr.read(svc)?.firstOrNull { CmfAccess.norm(it.text).contains(key) }
        if (line != null) {
            val x = line.box.exactCenterX()
            val y = line.box.exactCenterY()
            val ok = when (kind) { TapKind.TAP -> svc.tapAt(x, y); TapKind.LONG -> svc.holdAt(x, y, 800); TapKind.DOUBLE -> svc.doubleTapAt(x, y) }
            if (ok) return@withContext ActionResult.Success(done)
        }
        ActionResult.Failure("I can't see “$target” on this screen.")
    }

    suspend fun swipe(dir: SwipeDir): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("swipe")
        clearWay(svc)
        if (svc.swipe(dir.name.lowercase())) ActionResult.Success("Swiped ${dir.name.lowercase()}.") else ActionResult.Failure("I couldn't swipe here.")
    }

    suspend fun zoom(zoomIn: Boolean): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("zoom")
        clearWay(svc)
        if (svc.pinch(zoomIn)) ActionResult.Success(if (zoomIn) "Zoomed in." else "Zoomed out.") else ActionResult.Failure("I couldn't zoom here.")
    }

    suspend fun clip(op: ClipOp): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("edit text")
        clearWay(svc)
        val ok = svc.clip(op.name.lowercase())
        if (ok) ActionResult.Success(when (op) { ClipOp.SELECT_ALL -> "Selected all."; ClipOp.COPY -> "Copied."; ClipOp.PASTE -> "Pasted."; ClipOp.CUT -> "Cut." })
        else ActionResult.Failure("There's no text box I can do that in.")
    }

    suspend fun enter(): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("press enter")
        clearWay(svc)
        if (svc.imeEnter()) ActionResult.Success("Done.")
        else svc.findSend()?.let { if (svc.click(it)) ActionResult.Success("Done.") else null }
            ?: ActionResult.Failure("I couldn't find an enter key here.")
    }

    suspend fun scrollEdge(top: Boolean): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("scroll")
        clearWay(svc)
        if (svc.scrollEdge(top)) ActionResult.Success(if (top) "At the top." else "At the bottom.") else ActionResult.Failure("Nothing to scroll here.")
    }

    // ---- Doing things inside whatever app is open ----

    /** Gets our own window out of the way. Call before a run of steps. */
    suspend fun prepare() {
        val svc = CmfAccess.instance ?: return
        clearWay(svc)
    }

    /** After launching something, waits (up to 3 s) until a different app is in front and has drawn. */
    suspend fun settleAfterLaunch(previous: String) {
        val svc = CmfAccess.instance ?: return
        val end = System.currentTimeMillis() + 3200
        delay(220)
        while (System.currentTimeMillis() < end) {
            val now = svc.frontPackage()
            if (now.isNotEmpty() && now != previous && now != app.packageName) break
            delay(80)
        }
        delay(350)
    }

    fun frontPackage(): String = CmfAccess.instance?.frontPackage().orEmpty()

    /** Finds the search box (or the search icon and opens it), types [query], submits, and optionally opens the best result. */
    suspend fun search(query: String, pick: Boolean): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("search in apps")
        clearWay(svc)
        AppLog.d("ui", "search '$query' pick=$pick in ${svc.frontPackage()}")
        val trig = svc.waitSearch(2200) ?: return@withContext ActionResult.Failure("I can't find a search box on this screen.")
        if (!trig.second) {
            svc.click(trig.first)
            delay(220)
        } else {
            svc.click(trig.first)
            delay(120)
        }
        val field = svc.waitEditable(1800) ?: return@withContext ActionResult.Failure("I tapped search, but no text box showed up.")
        svc.click(field)
        delay(80)
        if (!svc.setText(query)) return@withContext ActionResult.Failure("I couldn't type into the search box.")
        delay(220)
        if (!svc.imeEnter()) {
            svc.find(listOf("Search"))?.takeIf { !it.isEditable }?.let { svc.click(it) }
        }
        if (!pick) return@withContext ActionResult.Success("Searched for $query.")
        // Wait for results to show, then open the closest match.
        var hit: android.view.accessibility.AccessibilityNodeInfo? = null
        val end = System.currentTimeMillis() + 2600
        while (System.currentTimeMillis() < end && hit == null) {
            delay(150)
            hit = svc.bestMatch(query)
        }
        val node = hit ?: return@withContext ActionResult.Failure("I searched for $query but couldn't see a matching result.")
        if (svc.click(node)) {
            delay(500)
            ActionResult.Success("Opened $query.")
        } else ActionResult.Failure("I found $query but couldn't open it.")
    }

    /** Types [body] into the message box and presses Send. */
    suspend fun send(body: String): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("send messages")
        clearWay(svc)
        val field = svc.waitEditable(2000) ?: return@withContext ActionResult.Failure("I can't find the message box.")
        svc.click(field)
        delay(80)
        if (!svc.setText(body)) return@withContext ActionResult.Failure("I couldn't type the message.")
        delay(250)
        val send = svc.waitFind(listOf("Send", "Send message", "Send button"), 1200)
        val ok = if (send != null) svc.click(send) else svc.imeEnter()
        if (ok) ActionResult.Success("Sent.") else ActionResult.Failure("I typed the message but couldn't find Send.")
    }

    suspend fun message(contact: String, body: String): ActionResult {
        val found = search(contact, pick = true)
        if (found is ActionResult.Failure) return found
        delay(700)
        return send(body)
    }

    // ---- Camera ----

    suspend fun takePhoto(selfie: Boolean): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance
        if (svc == null) {
            // Without screen control the best I can do is open the camera.
            return@withContext ActionResult.Success("Opening the camera. I need Accessibility access to press the shutter for you.") {
                launch(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            }
        }
        takePhotoNow(svc, selfie, openCamera = true)
    }

    private suspend fun takePhotoNow(svc: CmfAccess, selfie: Boolean, openCamera: Boolean): ActionResult {
        val front = svc.frontPackage()
        val inCamera = front.contains("camera", ignoreCase = true)
        if (openCamera && !inCamera) {
            val before = front
            if (!launch(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) {
                return ActionResult.Failure("I couldn't open the camera.")
            }
            settleAfterLaunch(before)
        }
        clearWay(svc)
        if (selfie) {
            svc.waitFind(listOf("Switch camera", "Flip camera", "Switch to front camera", "Selfie", "Front camera", "Rotate camera", "Switch cameras"), 1200)?.let {
                svc.click(it)
                delay(700)
            }
        }
        val shutter = svc.findShutter() ?: svc.waitShutter(2500)
        if (shutter == null) {
            val dm = app.resources.displayMetrics
            if (svc.tapAt(dm.widthPixels / 2f, dm.heightPixels * 0.88f)) {
                return ActionResult.Success("I couldn't see the shutter button, so I tapped where it usually is.")
            }
            return ActionResult.Failure("I couldn't find the shutter button.")
        }
        if (selfie) delay(1500) // a moment to get ready
        val ok = svc.click(shutter)
        delay(500)
        return if (ok) ActionResult.Success(if (selfie) "Selfie taken." else "Photo taken.") else ActionResult.Failure("I couldn't press the shutter.")
    }

    /** "Can you find the search bar?": says whether it is there and where. */
    suspend fun find(label: String): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("look at the screen")
        clearWay(svc)
        val clean = label.removePrefix("the ").removePrefix("a ").trim()
        val node = if (isSearchLabel(clean)) svc.findEditable() ?: svc.findSearchTrigger()?.first ?: svc.find(listOf("Search"))
        else svc.find(listOf(clean.removeSuffix(" button").removeSuffix(" icon").removeSuffix(" option").removeSuffix(" tab"))) ?: svc.scrollToFind(listOf(clean), 5)
        if (node != null) return@withContext ActionResult.Success("Yes, I can see ${if (isSearchLabel(clean)) "a search box" else clean} ${svc.where(node)}. Say tap $clean and I'll press it.")
        val line = Ocr.read(svc)?.firstOrNull { CmfAccess.norm(it.text).contains(CmfAccess.norm(clean)) }
        if (line != null) {
            val dm = app.resources.displayMetrics
            val y = line.box.centerY().toFloat() / dm.heightPixels
            return@withContext ActionResult.Success("Yes, I can see “${line.text}” ${if (y < 0.25f) "at the top" else if (y > 0.75f) "at the bottom" else "in the middle"} of the screen.")
        }
        ActionResult.Failure("I can't see $clean on this screen.")
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

    /** "What's on screen?": the app, the main text, what can be tapped, and any text boxes. Falls back to reading pixels. */
    suspend fun readScreen(): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext needAccess("read the screen")
        clearWay(svc)
        val sc = svc.describeScreen()
        val parts = ArrayList<String>()
        if (sc.app.isNotBlank()) parts.add("You're in ${sc.app}.")
        if (sc.texts.isNotEmpty()) parts.add("I can see: " + sc.texts.take(8).joinToString(". ") { it.take(80) } + ".")
        if (sc.buttons.isNotEmpty()) parts.add("You can tap: " + sc.buttons.take(8).joinToString(", ") + ".")
        if (sc.fields.isNotEmpty()) parts.add("There's a text box" + (if (sc.fields.size > 1) "es" else "") + ".")
        if (sc.texts.size + sc.buttons.size < 2) {
            val ocr = Ocr.read(svc)
            if (!ocr.isNullOrEmpty()) {
                parts.add("Reading the pixels: " + ocr.take(10).joinToString(". ") { it.text.take(80) } + ".")
            }
        }
        if (parts.size <= 1) ActionResult.Failure("I can't make out anything on this screen.")
        else ActionResult.Success(parts.joinToString(" "))
    }
}

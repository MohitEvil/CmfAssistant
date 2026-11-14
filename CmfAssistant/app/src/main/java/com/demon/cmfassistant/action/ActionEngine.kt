package com.demon.cmfassistant.action

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.intent.AppOp
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.MediaKey
import com.demon.cmfassistant.intent.SettingsPage
import com.demon.cmfassistant.intent.VolumeAction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

sealed interface ActionResult {
    /** [launch], when set, still has to run: the caller starts it as the spoken reply begins, so voice and action line up. */
    data class Success(val message: String, val launch: (() -> Unit)? = null) : ActionResult
    data class Failure(val message: String) : ActionResult

    /** Needs a yes / no from the user before running [command]. */
    data class Confirm(
        val title: String,
        val detail: String,
        val spoken: String,
        val command: Command,
        val declineMessage: String,
        val declineAsFailure: Boolean = true
    ) : ActionResult
}

data class Contact(val name: String, val number: String)

sealed interface ContactLookup {
    data class Found(val contact: Contact) : ContactLookup
    data object NoPermission : ContactLookup
    data object NotFound : ContactLookup

    /** No exact match, but this contact is spelled similarly. Ask before using it. */
    data class Fuzzy(val contact: Contact) : ContactLookup
}

/** Runs commands through ordinary Android APIs and intents. No root, no special access. */
class ActionEngine(context: Context) {

    private val app = context.applicationContext
    private val cameraManager = app.getSystemService(CameraManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)

    val system = SystemActions(app)
    private val ui = UiControl(app)

    private var torchOn = false

    private val torchId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    }

    init {
        Thread { runCatching { appIndex() } }.start() // warm the app list so the first "open X" is instant
        // Keeps torchOn in sync when the user toggles the torch elsewhere (quick settings, etc).
        runCatching {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    /** Commands that wait on other apps' screens run here, off the main thread. Everything else is instant. */
    suspend fun runSuspend(cmd: Command): ActionResult = when (cmd) {
        is Command.Toggle -> ui.toggle(cmd.key, cmd.on)
        is Command.SetValue -> ui.setValue(cmd.kind, cmd.value)
        is Command.GlobalAction -> ui.global(cmd.key)
        is Command.UiTap -> ui.tap(cmd.label)
        is Command.UiType -> ui.type(cmd.text)
        is Command.UiScroll -> ui.scroll(cmd.forward)
        Command.ReadScreen -> ui.readScreen()
        Command.LockScreen -> ui.lockScreen() ?: system.lock()
        is Command.AppAction ->
            if (cmd.op == AppOp.FORCE_STOP || cmd.op == AppOp.CLEAR_CACHE) appTask(cmd) else run(cmd)
        else -> run(cmd)
    }

    /** True for commands that take a moment because they drive another screen. */
    fun isSlow(cmd: Command): Boolean = cmd is Command.Toggle || cmd is Command.SetValue || cmd is Command.GlobalAction ||
        cmd is Command.UiTap || cmd is Command.UiType || cmd is Command.UiScroll || cmd === Command.ReadScreen ||
        (cmd is Command.AppAction && (cmd.op == AppOp.FORCE_STOP || cmd.op == AppOp.CLEAR_CACHE))

    private suspend fun appTask(c: Command.AppAction): ActionResult {
        val hit: AppHit = if (c.pkg != null) {
            AppHit(c.pkg, runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(c.pkg, 0)).toString() }.getOrDefault(c.name), true)
        } else {
            (findApp(c.name) ?: findApp(c.name, fuzzy = true)) ?: return ActionResult.Failure("I couldn't find an app called ${c.name}.")
        }
        if (!hit.exact) {
            return ActionResult.Confirm(
                title = (if (c.op == AppOp.FORCE_STOP) "Force stop " else "Clear cache of ") + hit.label + "?",
                detail = "I couldn't find “${c.name}”. Closest match is ${hit.label}.",
                spoken = "Do you mean ${hit.label}?",
                command = c.copy(pkg = hit.pkg),
                declineMessage = "Okay. I won't touch it."
            )
        }
        return ui.appTask(c.op, hit.pkg, hit.label)
    }

    fun run(cmd: Command): ActionResult = when (cmd) {
        is Command.Flashlight -> flashlight(cmd.on)
        is Command.OpenApp -> openApp(cmd.name)
        is Command.LaunchPackage -> launchPackage(cmd.pkg, cmd.label)
        is Command.SetTimer -> setTimer(cmd.seconds)
        is Command.SetAlarm -> setAlarm(cmd.hour, cmd.minute)
        is Command.Call -> call(cmd.target)
        is Command.ComposeSms -> composeSms(cmd)
        is Command.SendSms -> ActionResult.Failure("That message needs your confirmation first.")
        is Command.Volume -> if (cmd.stream == com.demon.cmfassistant.intent.VolStream.MEDIA) volume(cmd) else system.streamVolume(cmd)
        is Command.OpenSetting -> openNamedSetting(cmd.name)
        is Command.Brightness -> system.brightness(cmd)
        is Command.Rotation -> system.rotation(cmd.auto)
        is Command.ScreenTimeout -> system.screenTimeout(cmd.seconds)
        is Command.Dnd -> system.dnd(cmd.on)
        is Command.Ringer -> system.ringer(cmd.mode)
        Command.LockScreen -> system.lock()
        is Command.Notifications -> system.notifications(cmd.clear)
        Command.Agenda -> system.agenda()
        is Command.AddEvent -> system.addEvent(cmd.title)
        is Command.Email -> system.email(cmd.to, cmd.body)
        is Command.OpenUrl -> system.openUrl(cmd.url)
        Command.Wallpaper -> system.wallpaper()
        is Command.Status -> system.status(cmd.kind)
        is Command.AppAction -> appAction(cmd)
        is Command.CopyText -> system.copy(cmd.text)
        Command.ReadClipboard -> system.readClipboard()
        Command.ShowAlarms -> system.showAlarms()
        is Command.Media -> media(cmd.key)
        is Command.Search -> search(cmd.query)
        is Command.Navigate -> navigate(cmd.place)
        is Command.OpenSettings -> openSettings(cmd.page)
        Command.Battery -> battery()
        Command.TimeNow -> ActionResult.Success("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
        Command.DateToday -> ActionResult.Success(
            "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        )
        Command.Camera -> if (launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) {
            ActionResult.Success(pick("Opening the camera.", "Camera's up.", "Here's the camera."))
        } else {
            ActionResult.Failure("No camera app found")
        }
        is Command.PlayMusic -> playMusic(cmd)
        is Command.Reminder -> reminder(cmd)
        is Command.Toggle, is Command.SetValue, is Command.GlobalAction, is Command.UiTap,
        is Command.UiType, is Command.UiScroll, Command.ReadScreen ->
            ActionResult.Failure("That needs the screen-control service.")
        is Command.Question, is Command.Unknown -> ActionResult.Failure("I can't do that yet.")
    }

    // ---- Settings pages and app actions, with "did you mean" confirmation ----

    private fun openNamedSetting(name: String): ActionResult {
        SettingsTable.exact(name)?.let { return system.openSetting(it) }
        val close = SettingsTable.fuzzy(name)
            ?: return ActionResult.Failure("I don't know a settings page called $name.")
        return ActionResult.Confirm(
            title = "Open ${close.label}?",
            detail = "I couldn't find “$name”. Closest match is ${close.label}.",
            spoken = "Do you mean ${close.label} settings?",
            command = Command.OpenSetting(close.key),
            declineMessage = "Okay. I won't open anything."
        )
    }

    private data class AppHit(val pkg: String, val label: String, val exact: Boolean, val cls: String = "")

    private class AppEntry(val label: String, val norm: String, val words: List<String>, val pkg: String, val cls: String)

    @Volatile private var index: List<AppEntry> = emptyList()
    @Volatile private var indexAt = 0L

    /** Launchable apps, read once and kept for two minutes so "open X" does not re-scan the phone every time. */
    private fun appIndex(): List<AppEntry> {
        val now = android.os.SystemClock.uptimeMillis()
        if (index.isEmpty() || now - indexAt > 120_000) rebuildIndex()
        return index
    }

    private fun rebuildIndex() {
        val pm = app.packageManager
        index = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { ri ->
                val label = ri.loadLabel(pm).toString()
                AppEntry(
                    label, norm(label),
                    label.split(Regex("[^A-Za-z0-9]+")).map { norm(it) }.filter { it.length >= 4 },
                    ri.activityInfo.packageName, ri.activityInfo.name
                )
            }
        }.getOrDefault(index)
        indexAt = android.os.SystemClock.uptimeMillis()
    }

    /** Refreshes the list if it is more than 8 seconds old. Returns true if it did. */
    private fun refreshIndexIfStale(): Boolean {
        if (android.os.SystemClock.uptimeMillis() - indexAt < 8_000) return false
        rebuildIndex()
        return true
    }

    /** Best installed app for [name]. With [fuzzy] false only clear (exact, prefix, contains) matches count; true only close spellings. */
    private fun findApp(name: String, fuzzy: Boolean = false): AppHit? {
        val q = alias(norm(name))
        if (q.isEmpty()) return null
        val apps = appIndex()
        if (!fuzzy) {
            var best: AppEntry? = null
            var bestScore = 0
            for (e in apps) {
                val score = when {
                    e.norm == q -> 100
                    e.norm.startsWith(q) -> 80
                    e.norm.length >= 3 && q.startsWith(e.norm) -> 70
                    e.norm.contains(q) -> 60
                    else -> 0
                }
                if (score > bestScore) { best = e; bestScore = score }
            }
            return best?.let { AppHit(it.pkg, it.label, true, it.cls) }
        }
        if (q.length < 4) return null
        var close: AppEntry? = null
        var closeScore = 0.0
        for (e in apps) {
            val sim = (listOf(e.norm) + e.words).maxOf { Fuzzy.similarity(q, it) }
            if (sim > closeScore) { close = e; closeScore = sim }
        }
        return if (close != null && closeScore >= 0.68) AppHit(close.pkg, close.label, false, close.cls) else null
    }

    private fun appAction(c: Command.AppAction): ActionResult {
        val hit: AppHit = if (c.pkg != null) {
            AppHit(c.pkg, runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(c.pkg, 0)).toString() }.getOrDefault(c.name), true)
        } else {
            (findApp(c.name) ?: findApp(c.name, fuzzy = true)) ?: return ActionResult.Failure("I couldn't find an app called ${c.name}.")
        }
        if (!hit.exact) {
            return ActionResult.Confirm(
                title = (if (c.op == AppOp.UNINSTALL) "Uninstall " else "Show info for ") + hit.label + "?",
                detail = "I couldn't find “${c.name}”. Closest match is ${hit.label}.",
                spoken = "Do you mean ${hit.label}?",
                command = c.copy(pkg = hit.pkg),
                declineMessage = "Okay. I won't touch it."
            )
        }
        return when (c.op) {
            AppOp.INFO -> ActionResult.Success("Here's the info for ${hit.label}.") {
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${hit.pkg}")))
            }
            AppOp.UNINSTALL -> ActionResult.Success("Opening the uninstall prompt for ${hit.label}. Confirm it there.") {
                launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${hit.pkg}")))
            }
            else -> ActionResult.Failure("Not something I can do here.")
        }
    }

    // ---- Contacts (used by calls and by the message confirmation card) ----

    fun findContact(query: String): ContactLookup {
        val looksLikeNumber = query.any { it.isDigit() } && query.all { it.isDigit() || it in " +-()" }
        if (looksLikeNumber) {
            val digits = query.filter { it.isDigit() || it == '+' }
            return ContactLookup.Found(Contact(digits, digits))
        }
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactLookup.NoPermission
        }
        val q = query.trim()
        var best: Contact? = null
        runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val candidate = Contact(name, number)
                    val current = best
                    best = when {
                        current == null -> candidate
                        name.equals(q, ignoreCase = true) && !current.name.equals(q, ignoreCase = true) -> candidate
                        !current.name.equals(q, ignoreCase = true) && name.length < current.name.length -> candidate
                        else -> current
                    }
                }
            }
        }
        best?.let { return ContactLookup.Found(it) }
        return fuzzyContact(q)
    }

    /** Looks at every contact and returns the one whose name is spelled most like [q], if it is close enough. */
    private fun fuzzyContact(q: String): ContactLookup {
        val nq = norm(q)
        if (nq.length < 3) return ContactLookup.NotFound
        var best: Contact? = null
        var score = 0.0
        runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val parts = listOf(norm(name)) + name.split(Regex("\\s+")).map { norm(it) }.filter { it.length >= 3 }
                    val sim = parts.maxOf { similarity(nq, it) }
                    if (sim > score) { score = sim; best = Contact(name, number) }
                }
            }
        }
        val b = best
        return if (b != null && score >= 0.7) ContactLookup.Fuzzy(b) else ContactLookup.NotFound
    }

    // ---- Individual actions ----

    private fun flashlight(on: Boolean?): ActionResult {
        val id = torchId ?: return ActionResult.Failure("No flashlight found on this phone")
        val target = on ?: !torchOn
        return try {
            cameraManager.setTorchMode(id, target)
            torchOn = target
            ActionResult.Success(
                if (target) pick("Flashlight on.", "Torch is on.", "Light's on.")
                else pick("Flashlight off.", "Torch is off.", "Light's off.")
            )
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't reach the flashlight. Is the camera in use?")
        }
    }

    private fun openApp(name: String): ActionResult {
        val q = alias(norm(name))
        if (q.isEmpty()) return ActionResult.Failure("Which app should I open?")

        // 1) A clear app match opens straight away.
        var hit = findApp(name, fuzzy = false)
        if (hit == null) {
            // 2) Maybe it is a Settings page ("developer options", "wifi").
            SettingsTable.exact(name)?.let { return system.openSetting(it) }
            // 3) The app may have been installed a moment ago: refresh the list once.
            if (refreshIndexIfStale()) hit = findApp(name, fuzzy = false)
        }
        if (hit != null) return launchHit(hit)

        // 4) A similar-sounding app (speech often mishears names). Ask before opening it.
        findApp(name, fuzzy = true)?.let { close ->
            return ActionResult.Confirm(
                title = "Open ${close.label}?",
                detail = "I couldn't find “$name”. Closest match is ${close.label}.",
                spoken = "Do you mean ${close.label}?",
                command = Command.LaunchPackage(close.pkg, close.label),
                declineMessage = "Okay. I couldn't find an app called $name."
            )
        }
        // 5) Or a similar Settings page.
        SettingsTable.fuzzy(name)?.let { close ->
            return ActionResult.Confirm(
                title = "Open ${close.label}?",
                detail = "I couldn't find “$name”. Closest match is ${close.label} settings.",
                spoken = "Do you mean ${close.label} settings?",
                command = Command.OpenSetting(close.key),
                declineMessage = "Okay. I won't open anything."
            )
        }
        return ActionResult.Failure("I couldn't find an app or setting called $name")
    }

    private fun launchHit(hit: AppHit): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(hit.pkg, hit.cls)
        return ActionResult.Success("Opening ${hit.label}.") { launch(intent) }
    }

    private fun launchPackage(pkg: String, label: String): ActionResult {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionResult.Failure("Couldn't open $label")
        return ActionResult.Success("Opening $label.") { launch(intent) }
    }

    /** 1.0 = identical, 0.0 = nothing in common (edit distance relative to the longer word). */
    private fun similarity(a: String, b: String): Double {
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

    /** Names of launchable apps, used to render "Opening <app>." ahead of time for the offline voice. */
    fun appLabels(): List<String> = appIndex().map { it.label }.filter { it.length in 2..20 }.distinct()

    /** Several ways to say the same thing, so replies sound like a person rather than an echo of the command. */
    private fun pick(vararg options: String): String = options.random()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun alias(q: String) = when (q) {
        "yt" -> "youtube"
        "insta" -> "instagram"
        "fb" -> "facebook"
        "wa" -> "whatsapp"
        "gmaps", "googlemaps" -> "maps"
        else -> q
    }

    private fun setTimer(seconds: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "CMF Assistant")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(intent)) {
            ActionResult.Success(
                pick(
                    "Timer set for ${formatDuration(seconds)}.",
                    "Alright, ${formatDuration(seconds)}, starting now.",
                    "Okay, I'll keep time. ${formatDuration(seconds)}."
                )
            )
        } else {
            ActionResult.Failure("No clock app can set timers")
        }
    }

    private fun setAlarm(hour: Int, minute: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "CMF Assistant")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        val label = SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
        return if (launch(intent)) ActionResult.Success(pick("Alarm set for $label.", "Done. I'll wake you at $label.", "Okay, $label it is.")) else ActionResult.Failure("No clock app can set alarms")
    }

    private fun call(target: String): ActionResult {
        return when (val r = findContact(target)) {
            is ContactLookup.Found -> {
                val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", r.contact.number, null))
                if (launch(intent)) ActionResult.Success(pick("Calling ${r.contact.name}.", "Dialing ${r.contact.name}.")) else ActionResult.Failure("No dialer found")
            }
            is ContactLookup.Fuzzy -> ActionResult.Confirm(
                title = "Call ${r.contact.name}?",
                detail = "I couldn't find “$target”. Closest match is ${r.contact.name}.",
                spoken = "Do you mean ${r.contact.name}?",
                command = Command.Call(r.contact.name),
                declineMessage = "Okay. I won't call anyone."
            )
            ContactLookup.NoPermission -> ActionResult.Failure("Allow Contacts access to call by name")
            ContactLookup.NotFound -> ActionResult.Failure("I couldn't find “$target” in your contacts")
        }
    }

    private fun composeSms(c: Command.ComposeSms): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
            .putExtra("sms_body", c.body)
        return if (launch(intent)) {
            ActionResult.Success(pick("All set. Tap send when you're ready.", "Your message to ${c.displayName} is ready. Just hit send.", "Drafted. Tap send in Messages."))
        } else {
            ActionResult.Failure("No messaging app found")
        }
    }

    private fun volume(cmd: Command.Volume): ActionResult {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        when (cmd.action) {
            VolumeAction.UP -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            VolumeAction.DOWN -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            VolumeAction.MUTE -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            VolumeAction.SET -> audio.setStreamVolume(
                stream, (max * cmd.percent / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI
            )
        }
        if (cmd.action == VolumeAction.MUTE) return ActionResult.Success(pick("Muted.", "Quiet now."))
        val pct = (audio.getStreamVolume(stream) * 100f / max).roundToInt()
        return ActionResult.Success(pick("Volume's at $pct%.", "Okay, $pct%."))
    }

    private fun media(key: MediaKey): ActionResult {
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val label = when (key) {
            MediaKey.PLAY -> "Play"
            MediaKey.PAUSE -> "Pause"
            MediaKey.NEXT -> "Next track"
            MediaKey.PREVIOUS -> "Previous track"
        }
        return ActionResult.Success(
            when (key) {
                MediaKey.PLAY -> pick("Playing.", "Resuming.")
                MediaKey.PAUSE -> pick("Paused.", "Okay, paused.")
                MediaKey.NEXT -> pick("Skipping.", "Next one.")
                MediaKey.PREVIOUS -> pick("Going back.", "Previous track.")
            }
        )
    }

    private fun battery(): ActionResult {
        val bm = app.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val state = if (bm.isCharging) ", charging" else ""
        return ActionResult.Success("Battery's at $pct%$state.")
    }

    private fun search(query: String): ActionResult {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (launch(intent)) return ActionResult.Success(pick("Here's what I found for “$query”.", "Looking up “$query”."))
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        return if (launch(fallback)) ActionResult.Success(pick("Here's what I found for “$query”.", "Looking up “$query”.")) else ActionResult.Failure("No browser found")
    }

    private fun navigate(place: String): ActionResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
        return if (launch(intent)) ActionResult.Success(pick("Pulling up $place on the map.", "Here's $place.")) else ActionResult.Failure("No maps app found")
    }

    private fun openSettings(page: SettingsPage): ActionResult {
        val (intent, message) = when (page) {
            SettingsPage.INTERNET -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) to
                "Here are the Wi-Fi controls. Android won't let apps flip it directly."
            SettingsPage.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to
                "Here's Bluetooth. Android won't let apps flip it directly."
            SettingsPage.GENERAL -> Intent(Settings.ACTION_SETTINGS) to "Here are your settings."
        }
        return if (launch(intent)) ActionResult.Success(message) else ActionResult.Failure("Couldn't open settings")
    }

    private fun playMusic(c: Command.PlayMusic): ActionResult {
        val pkg = c.app?.let { (findApp(it) ?: findApp(it, fuzzy = true))?.pkg }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, c.query)
        if (pkg != null) intent.setPackage(pkg)
        val msg = pick("Playing ${c.query}.", "Putting on ${c.query}.", "Here's ${c.query}.")
        return ActionResult.Success(msg) {
            if (!launch(intent)) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(c.query))))
            }
        }
    }

    private fun reminder(c: Command.Reminder): ActionResult {
        val intent = if (c.seconds != null) {
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.seconds)
        } else {
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, c.hour).putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
        }.putExtra(AlarmClock.EXTRA_MESSAGE, c.text).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(intent)) return ActionResult.Failure("No clock app can set reminders")
        val whenText = if (c.seconds != null) "in ${formatDuration(c.seconds)}" else "at " +
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, c.hour); set(Calendar.MINUTE, c.minute) }.time)
        return ActionResult.Success("Okay, I'll remind you to ${c.text} $whenText.")
    }

    // ---- Helpers ----

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildList {
            if (h > 0) add("$h hr")
            if (m > 0) add("$m min")
            if (s > 0) add("$s sec")
        }.joinToString(" ")
    }
}

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
    data class Success(val message: String) : ActionResult
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
}

/** Runs commands through ordinary Android APIs and intents. No root, no special access. */
class ActionEngine(context: Context) {

    private val app = context.applicationContext
    private val cameraManager = app.getSystemService(CameraManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)

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
        // Keeps torchOn in sync when the user toggles the torch elsewhere (quick settings, etc).
        runCatching {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
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
        is Command.Volume -> volume(cmd)
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
        is Command.Question, is Command.Unknown -> ActionResult.Failure("I can't do that yet.")
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
        return best?.let { ContactLookup.Found(it) } ?: ContactLookup.NotFound
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
        val pm = app.packageManager
        val q = alias(norm(name))
        if (q.isEmpty()) return ActionResult.Failure("Which app should I open?")

        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )

        // 1) A clear match opens straight away.
        var best: ResolveInfo? = null
        var bestScore = 0
        for (ri in apps) {
            val label = norm(ri.loadLabel(pm).toString())
            val score = when {
                label == q -> 100
                label.startsWith(q) -> 80
                label.length >= 3 && q.startsWith(label) -> 70
                label.contains(q) -> 60
                else -> 0
            }
            if (score > bestScore) {
                best = ri
                bestScore = score
            }
        }
        if (best != null) return launchResolved(best)

        // 2) Otherwise look for a similar name (speech often mishears: "namita" vs "namida") and ask.
        var close: ResolveInfo? = null
        var closeScore = 0.0
        if (q.length >= 4) {
            for (ri in apps) {
                val full = ri.loadLabel(pm).toString()
                val candidates = listOf(norm(full)) +
                    full.split(Regex("[^A-Za-z0-9]+")).map { norm(it) }.filter { it.length >= 4 }
                val sim = candidates.maxOf { similarity(q, it) }
                if (sim > closeScore) {
                    close = ri
                    closeScore = sim
                }
            }
        }
        if (close != null && closeScore >= 0.68) {
            val label = close.loadLabel(pm).toString()
            return ActionResult.Confirm(
                title = "Open $label?",
                detail = "I couldn't find “$name”. Closest match is $label.",
                spoken = "Do you mean $label?",
                command = Command.LaunchPackage(close.activityInfo.packageName, label),
                declineMessage = "Okay. I couldn't find an app called $name."
            )
        }
        return ActionResult.Failure("I couldn't find an app called $name")
    }

    private fun launchResolved(ri: ResolveInfo): ActionResult {
        val label = ri.loadLabel(app.packageManager).toString()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
        return if (launch(intent)) ActionResult.Success("Opening $label.") else ActionResult.Failure("Couldn't open $label")
    }

    private fun launchPackage(pkg: String, label: String): ActionResult {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionResult.Failure("Couldn't open $label")
        return if (launch(intent)) ActionResult.Success("Opening $label.") else ActionResult.Failure("Couldn't open $label")
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
    fun appLabels(): List<String> = runCatching {
        val pm = app.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }
            .filter { it.length in 2..20 }
            .distinct()
    }.getOrDefault(emptyList())

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

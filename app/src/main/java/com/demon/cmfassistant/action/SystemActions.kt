package com.demon.cmfassistant.action

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.LevelAction
import com.demon.cmfassistant.intent.RingerMode
import com.demon.cmfassistant.intent.StatusKind
import com.demon.cmfassistant.intent.VolStream
import com.demon.cmfassistant.intent.VolumeAction
import com.demon.cmfassistant.service.AdminReceiver
import com.demon.cmfassistant.service.NotificationFeed
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phone controls that need special access (system settings, Do Not Disturb, device admin, notification access).
 * When access is missing the assistant says so and opens the exact page where the user grants it.
 */
class SystemActions(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private val resolver get() = app.contentResolver

    // ---- Access checks ----

    fun canWriteSettings() = Settings.System.canWrite(app)
    fun hasDndAccess() = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun isAdmin() = (app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminName())
    fun hasNotificationAccess() = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    fun has(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun adminName() = ComponentName(app, AdminReceiver::class.java)

    private fun needs(message: String, intent: Intent) = ActionResult.Success(message) { launch(intent) }

    private fun needWriteSettings() = needs(
        "I need the Modify system settings permission first. Opening it. Turn it on for CMF Assistant, then ask again.",
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}"))
    )

    private fun needDnd() = needs(
        "I need Do Not Disturb access first. Opening it. Allow CMF Assistant, then ask again.",
        Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
    )

    // ---- Display ----

    fun brightness(c: Command.Brightness): ActionResult {
        if (!canWriteSettings()) return needWriteSettings()
        return try {
            if (c.action == LevelAction.AUTO) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return ActionResult.Success("Adaptive brightness is on.")
            }
            val cur = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = when (c.action) {
                LevelAction.UP -> cur + 40
                LevelAction.DOWN -> cur - 40
                else -> (255 * c.percent / 100.0).roundToInt()
            }.coerceIn(1, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
            ActionResult.Success("Brightness at ${(target * 100f / 255).roundToInt()}%.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change brightness.")
        }
    }

    fun rotation(auto: Boolean?): ActionResult {
        if (!canWriteSettings()) return needWriteSettings()
        return try {
            val now = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            val target = auto ?: !now
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
            ActionResult.Success(if (target) "Auto-rotate is on." else "Rotation is locked.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change rotation.")
        }
    }

    fun screenTimeout(seconds: Int): ActionResult {
        if (!canWriteSettings()) return needWriteSettings()
        return try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
            val label = if (seconds >= 60) "${seconds / 60} min" else "$seconds sec"
            ActionResult.Success("Screen will stay on for $label.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change the screen timeout.")
        }
    }

    // ---- Sound ----

    fun dnd(on: Boolean?): ActionResult {
        if (!hasDndAccess()) return needDnd()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val now = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val target = on ?: !now
        return try {
            nm.setInterruptionFilter(
                if (target) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            ActionResult.Success(if (target) "Do Not Disturb is on." else "Do Not Disturb is off.")
        } catch (e: SecurityException) {
            needDnd()
        }
    }

    fun ringer(mode: RingerMode): ActionResult {
        return try {
            audio.ringerMode = when (mode) {
                RingerMode.SILENT -> android.media.AudioManager.RINGER_MODE_SILENT
                RingerMode.VIBRATE -> android.media.AudioManager.RINGER_MODE_VIBRATE
                RingerMode.NORMAL -> android.media.AudioManager.RINGER_MODE_NORMAL
            }
            ActionResult.Success(
                when (mode) {
                    RingerMode.SILENT -> "Phone is silent."
                    RingerMode.VIBRATE -> "Vibrate mode."
                    RingerMode.NORMAL -> "Ringer is back on."
                }
            )
        } catch (e: SecurityException) {
            if (mode == RingerMode.SILENT) needDnd() else ActionResult.Failure("Couldn't change the sound mode.")
        }
    }

    fun streamVolume(c: Command.Volume): ActionResult {
        val stream = when (c.stream) {
            VolStream.MEDIA -> android.media.AudioManager.STREAM_MUSIC
            VolStream.RING -> android.media.AudioManager.STREAM_RING
            VolStream.ALARM -> android.media.AudioManager.STREAM_ALARM
            VolStream.NOTIFICATION -> android.media.AudioManager.STREAM_NOTIFICATION
            VolStream.CALL -> android.media.AudioManager.STREAM_VOICE_CALL
        }
        val name = c.stream.name.lowercase()
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return try {
            when (c.action) {
                VolumeAction.UP -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                VolumeAction.DOWN -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                VolumeAction.MUTE -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                VolumeAction.SET -> audio.setStreamVolume(stream, (max * c.percent / 100.0).roundToInt(), android.media.AudioManager.FLAG_SHOW_UI)
            }
            val pct = (audio.getStreamVolume(stream) * 100f / max).roundToInt()
            ActionResult.Success("The $name volume is at $pct%.")
        } catch (e: SecurityException) {
            needDnd()
        }
    }

    // ---- Lock ----

    fun lock(): ActionResult {
        if (!isAdmin()) {
            return needs(
                "To lock the phone I need Device admin access. It only allows locking the screen. Opening it now.",
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName())
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets CMF Assistant lock the screen when you ask. Nothing else.")
            )
        }
        return ActionResult.Success("Locking.") {
            runCatching { (app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow() }
        }
    }

    // ---- Notifications ----

    fun notifications(clear: Boolean): ActionResult {
        if (!hasNotificationAccess() || NotificationFeed.instance == null) {
            return needs(
                "I need Notification access to do that. Opening it. Allow CMF Assistant, then ask again.",
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            )
        }
        if (clear) {
            return if (NotificationFeed.clearAll()) ActionResult.Success("Cleared your notifications.")
            else ActionResult.Failure("Couldn't clear notifications.")
        }
        val items = NotificationFeed.readable() ?: return ActionResult.Failure("Notification access isn't ready yet.")
        if (items.isEmpty()) return ActionResult.Success("You have no notifications.")
        val n = items.size
        return ActionResult.Success("You have $n recent ${if (n == 1) "notification" else "notifications"}. " + items.joinToString(" ") { "$it." })
    }

    // ---- Calendar ----

    fun agenda(): ActionResult {
        if (!has(android.Manifest.permission.READ_CALENDAR)) return ActionResult.Failure("Allow Calendar access to read your events.")
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start.timeInMillis)
            android.content.ContentUris.appendId(it, end.timeInMillis)
        }.build()
        val lines = ArrayList<String>()
        runCatching {
            resolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                val f = SimpleDateFormat("h:mm a", Locale.getDefault())
                while (c.moveToNext() && lines.size < 6) {
                    val title = c.getString(0).orEmpty().ifBlank { "Untitled" }
                    lines.add(if (c.getInt(2) == 1) "$title, all day" else "$title at ${f.format(java.util.Date(c.getLong(1)))}")
                }
            }
        }
        return if (lines.isEmpty()) ActionResult.Success("Nothing on your calendar today.")
        else ActionResult.Success("Today you have: " + lines.joinToString(". ") + ".")
    }

    fun addEvent(title: String): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        if (title.isNotBlank()) intent.putExtra(CalendarContract.Events.TITLE, title)
        return if (launchOk(intent)) ActionResult.Success(if (title.isBlank()) "Here's a new event." else "New event: $title. Pick a time and save.")
        else ActionResult.Failure("No calendar app found.")
    }

    // ---- Small tools ----

    fun email(to: String, body: String): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to.trim())))
        if (body.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, body)
        return if (launchOk(intent)) ActionResult.Success("Your email is ready.") else ActionResult.Failure("No email app found.")
    }

    fun openUrl(url: String): ActionResult {
        val u = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
        return if (launchOk(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) ActionResult.Success("Opening $url.") else ActionResult.Failure("No browser found.")
    }

    fun wallpaper(): ActionResult =
        if (launchOk(Intent(Intent.ACTION_SET_WALLPAPER))) ActionResult.Success("Here are the wallpapers.") else ActionResult.Failure("No wallpaper picker found.")

    fun showAlarms(): ActionResult =
        if (launchOk(Intent(AlarmClock.ACTION_SHOW_ALARMS))) ActionResult.Success("Here are your alarms.") else ActionResult.Failure("No clock app found.")

    fun copy(text: String): ActionResult {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CMF Assistant", text))
        return ActionResult.Success("Copied.")
    }

    fun readClipboard(): ActionResult {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() }.getOrNull()
        return if (t.isNullOrBlank()) ActionResult.Success("Your clipboard is empty.") else ActionResult.Success("Your clipboard says: ${t.take(200)}")
    }

    fun openSetting(entry: SettingsTable.Entry): ActionResult {
        val intent = if (entry.action == "PANEL_INTERNET") Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(entry.action)
        if (launchOk(intent)) {
            return ActionResult.Success(
                when (entry.key) {
                    "wifi" -> "Here are the Wi-Fi controls. Android won't let apps flip it directly."
                    "bluetooth" -> "Here's Bluetooth. Android won't let apps flip it directly."
                    else -> "Opening ${entry.label}."
                }
            )
        }
        // Some phones hide a page. Fall back to the main settings.
        return if (launchOk(Intent(Settings.ACTION_SETTINGS))) ActionResult.Success("This phone has no ${entry.label} page. Here are your settings.")
        else ActionResult.Failure("Couldn't open ${entry.label}.")
    }

    // ---- Status ----

    fun status(kind: StatusKind): ActionResult = try {
        when (kind) {
            StatusKind.STORAGE -> {
                val st = StatFs(Environment.getDataDirectory().path)
                val free = Formatter.formatFileSize(app, st.availableBytes)
                val total = Formatter.formatFileSize(app, st.totalBytes)
                ActionResult.Success("$free free of $total.")
            }
            StatusKind.RAM -> {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                ActionResult.Success("${Formatter.formatFileSize(app, mi.availMem)} of ${Formatter.formatFileSize(app, mi.totalMem)} memory is free.")
            }
            StatusKind.NETWORK -> {
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ActionResult.Success(
                    when {
                        caps == null -> "You're offline."
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "You're online over Wi-Fi."
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "You're online on mobile data."
                        else -> "You're online."
                    }
                )
            }
            StatusKind.WIFI -> {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                ActionResult.Success(if (wm.isWifiEnabled) "Wi-Fi is on." else "Wi-Fi is off.")
            }
            StatusKind.BLUETOOTH -> {
                val bt = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ActionResult.Success(if (bt?.isEnabled == true) "Bluetooth is on." else "Bluetooth is off.")
            }
            StatusKind.AIRPLANE -> ActionResult.Success(
                if (Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) "Airplane mode is on." else "Airplane mode is off."
            )
            StatusKind.DND -> {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                ActionResult.Success(if (on) "Do Not Disturb is on." else "Do Not Disturb is off.")
            }
            StatusKind.BRIGHTNESS -> {
                val v = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                ActionResult.Success("Brightness is at ${(v * 100f / 255).roundToInt()}%.")
            }
        }
    } catch (e: Exception) {
        ActionResult.Failure("I couldn't check that.")
    }

    private fun launchOk(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun launch(intent: Intent) {
        launchOk(intent)
    }
}

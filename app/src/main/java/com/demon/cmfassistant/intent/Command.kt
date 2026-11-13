package com.demon.cmfassistant.intent

enum class VolumeAction { UP, DOWN, MUTE, SET }
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsPage { INTERNET, BLUETOOTH, GENERAL }
enum class LevelAction { UP, DOWN, SET, AUTO }
enum class VolStream { MEDIA, RING, ALARM, NOTIFICATION, CALL }
enum class RingerMode { SILENT, VIBRATE, NORMAL }
enum class StatusKind { STORAGE, RAM, NETWORK, WIFI, BLUETOOTH, AIRPLANE, DND, BRIGHTNESS }
enum class AppOp { INFO, UNINSTALL }

/** Everything the assistant knows how to do. */
sealed class Command {
    /** Text shown while the command is running (the EXECUTING status line). */
    abstract fun describe(): String

    /** on == null means toggle. */
    data class Flashlight(val on: Boolean?) : Command() {
        override fun describe() = when (on) {
            true -> "Turning flashlight on…"
            false -> "Turning flashlight off…"
            null -> "Toggling flashlight…"
        }
    }

    data class OpenApp(val name: String) : Command() {
        override fun describe() = "Opening $name…"
    }

    data class SetTimer(val seconds: Int) : Command() {
        override fun describe() = "Starting timer…"
    }

    data class SetAlarm(val hour: Int, val minute: Int) : Command() {
        override fun describe() = "Setting alarm…"
    }

    data class Call(val target: String) : Command() {
        override fun describe() = "Finding $target…"
    }

    /** Parsed from speech; resolved to [ComposeSms] (and confirmed by the user) before it runs. */
    data class SendSms(val recipient: String, val body: String) : Command() {
        override fun describe() = "Preparing message…"
    }

    data class ComposeSms(val number: String, val displayName: String, val body: String) : Command() {
        override fun describe() = "Opening Messages…"
    }

    data class Volume(val action: VolumeAction, val percent: Int = 0, val stream: VolStream = VolStream.MEDIA) : Command() {
        override fun describe() = "Adjusting volume…"
    }

    data class Media(val key: MediaKey) : Command() {
        override fun describe() = "Controlling media…"
    }

    data class Search(val query: String) : Command() {
        override fun describe() = "Searching the web…"
    }

    data class Navigate(val place: String) : Command() {
        override fun describe() = "Opening maps…"
    }

    data class OpenSettings(val page: SettingsPage) : Command() {
        override fun describe() = "Opening settings…"
    }

    data object Battery : Command() {
        override fun describe() = "Checking battery…"
    }

    data object TimeNow : Command() {
        override fun describe() = "Checking the time…"
    }

    data object DateToday : Command() {
        override fun describe() = "Checking the date…"
    }

    data object Camera : Command() {
        override fun describe() = "Opening camera…"
    }

    /** Opens an exact installed app (after the user said yes to "Do you mean ...?"). */
    data class LaunchPackage(val pkg: String, val label: String) : Command() {
        override fun describe() = "Opening $label…"
    }

    /** Opens any Settings page by name. [exact] is false when the name was only a close match the user confirmed. */
    data class OpenSetting(val name: String) : Command() {
        override fun describe() = "Opening settings…"
    }

    data class Brightness(val action: LevelAction, val percent: Int = 0) : Command() {
        override fun describe() = "Adjusting brightness…"
    }

    /** auto == null means toggle. */
    data class Rotation(val auto: Boolean?) : Command() {
        override fun describe() = "Changing rotation…"
    }

    data class ScreenTimeout(val seconds: Int) : Command() {
        override fun describe() = "Setting screen timeout…"
    }

    /** on == null means toggle. */
    data class Dnd(val on: Boolean?) : Command() {
        override fun describe() = "Changing Do Not Disturb…"
    }

    data class Ringer(val mode: RingerMode) : Command() {
        override fun describe() = "Changing sound mode…"
    }

    data object LockScreen : Command() {
        override fun describe() = "Locking the phone…"
    }

    /** clear == false reads them aloud, true dismisses them all. */
    data class Notifications(val clear: Boolean) : Command() {
        override fun describe() = "Checking notifications…"
    }

    data object Agenda : Command() {
        override fun describe() = "Checking your calendar…"
    }

    data class AddEvent(val title: String) : Command() {
        override fun describe() = "Opening calendar…"
    }

    data class Email(val to: String, val body: String) : Command() {
        override fun describe() = "Opening email…"
    }

    data class OpenUrl(val url: String) : Command() {
        override fun describe() = "Opening website…"
    }

    data object Wallpaper : Command() {
        override fun describe() = "Opening wallpapers…"
    }

    data class Status(val kind: StatusKind) : Command() {
        override fun describe() = "Checking…"
    }

    /** [pkg] is set once the user confirmed a close match. */
    data class AppAction(val op: AppOp, val name: String, val pkg: String? = null) : Command() {
        override fun describe() = "Finding $name…"
    }

    data class CopyText(val text: String) : Command() {
        override fun describe() = "Copying…"
    }

    data object ReadClipboard : Command() {
        override fun describe() = "Reading clipboard…"
    }

    data object ShowAlarms : Command() {
        override fun describe() = "Opening alarms…"
    }

    /** A question or chat line that isn't a phone command. Answered by the AI, or searched on the web. */
    data class Question(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }

    data class Unknown(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }
}

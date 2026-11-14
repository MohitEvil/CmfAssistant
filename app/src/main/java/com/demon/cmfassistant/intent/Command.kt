package com.demon.cmfassistant.intent

enum class VolumeAction { UP, DOWN, MUTE, SET }
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsPage { INTERNET, BLUETOOTH, GENERAL }
enum class LevelAction { UP, DOWN, SET, AUTO }
enum class VolStream { MEDIA, RING, ALARM, NOTIFICATION, CALL }
enum class RingerMode { SILENT, VIBRATE, NORMAL }
enum class StatusKind { STORAGE, RAM, NETWORK, WIFI, BLUETOOTH, AIRPLANE, DND, BRIGHTNESS }
enum class AppOp { INFO, UNINSTALL, FORCE_STOP, CLEAR_CACHE }
enum class GlobalKey { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, POWER_MENU, SPLIT }
enum class TapKind { TAP, LONG, DOUBLE }
enum class SwipeDir { LEFT, RIGHT, UP, DOWN }
enum class ClipOp { SELECT_ALL, COPY, PASTE, CUT }
enum class ValueKind { SMALLEST_WIDTH, ANIMATION }

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

    /** Flip a system switch (Wi-Fi, Bluetooth, developer options, ...). on == null means toggle. */
    data class Toggle(val key: String, val on: Boolean?) : Command() {
        override fun describe() = "Switching it…"
    }

    data class SetValue(val kind: ValueKind, val value: String) : Command() {
        override fun describe() = "Changing the setting…"
    }

    data class GlobalAction(val key: GlobalKey) : Command(), Screen {
        override fun describe() = "On it…"
    }

    /** Marker for commands that work on whatever app is in front (they need the Accessibility service). */
    interface Screen

    data class UiTap(val label: String, val kind: TapKind = TapKind.TAP) : Command(), Screen {
        override fun describe() = when (kind) {
            TapKind.TAP -> "Tapping $label…"
            TapKind.LONG -> "Pressing and holding $label…"
            TapKind.DOUBLE -> "Double tapping $label…"
        }
    }

    data class UiSwipe(val dir: SwipeDir) : Command(), Screen {
        override fun describe() = "Swiping…"
    }

    data class UiZoom(val zoomIn: Boolean) : Command(), Screen {
        override fun describe() = "Zooming…"
    }

    data class UiClip(val op: ClipOp) : Command(), Screen {
        override fun describe() = "On it…"
    }

    data class UiScrollEdge(val top: Boolean) : Command(), Screen {
        override fun describe() = "Scrolling…"
    }

    /** Press the keyboard's Enter / Search / Send key. */
    data object UiEnter : Command(), Screen {
        override fun describe() = "Submitting…"
    }

    /** In the current app: find the search box, type [query], submit, and optionally open the best result. */
    data class UiSearch(val query: String, val pick: Boolean) : Command(), Screen {
        override fun describe() = "Searching for $query…"
    }

    /** In the current app: type [body] into the message box and press Send. */
    data class UiSend(val body: String) : Command(), Screen {
        override fun describe() = "Sending…"
    }

    /** In a messaging app: find [contact], open the chat, type [body] and send. */
    data class UiMessage(val contact: String, val body: String) : Command(), Screen {
        override fun describe() = "Messaging $contact…"
    }

    data class TakePhoto(val selfie: Boolean) : Command(), Screen {
        override fun describe() = "Taking a picture…"
    }

    /** Several steps spoken in one sentence, run one after the other. */
    data class Chain(val steps: List<Command>) : Command(), Screen {
        override fun describe() = "Working on it…"
    }

    data class UiType(val text: String) : Command(), Screen {
        override fun describe() = "Typing…"
    }

    data class UiScroll(val forward: Boolean) : Command(), Screen {
        override fun describe() = "Scrolling…"
    }

    /** "Find the search bar", "can you see a login button": looks on the current screen. */
    data class UiFind(val label: String) : Command(), Screen {
        override fun describe() = "Looking…"
    }

    /** "Find bluetooth", "find developer options": locates a setting or app and opens it. */
    data class Find(val query: String) : Command() {
        override fun describe() = "Looking for $query…"
    }

    data object ReadScreen : Command(), Screen {
        override fun describe() = "Reading the screen…"
    }

    data class PlayMusic(val query: String, val app: String?) : Command() {
        override fun describe() = "Finding $query…"
    }

    /** Either [seconds] from now, or at [hour]:[minute] when seconds is null. */
    data class Reminder(val text: String, val seconds: Int?, val hour: Int, val minute: Int) : Command() {
        override fun describe() = "Setting a reminder…"
    }

    /** A question or chat line that isn't a phone command. Answered by the AI, or searched on the web. */
    data class Question(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }

    data class Unknown(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }
}

package com.demon.cmfassistant.intent

enum class VolumeAction { UP, DOWN, MUTE, SET }
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsPage { INTERNET, BLUETOOTH, GENERAL }

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

    data class Volume(val action: VolumeAction, val percent: Int = 0) : Command() {
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

    /** A question or chat line that isn't a phone command. Answered by the AI, or searched on the web. */
    data class Question(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }

    data class Unknown(val text: String) : Command() {
        override fun describe() = "Thinking…"
    }
}

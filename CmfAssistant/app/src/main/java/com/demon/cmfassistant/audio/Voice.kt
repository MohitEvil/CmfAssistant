package com.demon.cmfassistant.audio

/** Something that can speak. All callbacks arrive on the main thread. */
interface Voice {
    val available: Boolean
    val isSpeaking: Boolean
    fun speak(raw: String, onDone: () -> Unit)

    /** Like [speak], and also tells you the moment the first sound plays, so an action can start together with the voice. */
    fun speak(raw: String, onStart: () -> Unit, onDone: () -> Unit)
    fun stop()
}

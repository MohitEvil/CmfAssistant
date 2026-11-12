package com.demon.cmfassistant.audio

/** Something that can speak. All callbacks arrive on the main thread. */
interface Voice {
    val available: Boolean
    val isSpeaking: Boolean
    fun speak(raw: String, onDone: () -> Unit)
    fun stop()
}

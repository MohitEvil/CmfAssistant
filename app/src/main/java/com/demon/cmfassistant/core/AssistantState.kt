package com.demon.cmfassistant.core

import android.os.SystemClock
import com.demon.cmfassistant.intent.Command

/** The assistant's visible states. The UI mirrors real events, never arbitrary timers. */
enum class AssistantState {
    IDLE,
    LISTENING,
    RECOGNIZING,
    THINKING,
    SPEAKING,
    CONFIRMING,
    EXECUTING,
    SUCCESS,
    FAILURE
}

/** A question the user must answer (yes / no, by voice or by tapping) before [command] runs. */
data class PendingConfirm(
    val title: String,
    val detail: String,
    val spoken: String,
    val command: Command,
    val declineMessage: String,
    /** True shows the decline as a failure ("couldn't find the app"); false just says it and closes. */
    val declineAsFailure: Boolean = true
)

data class UiState(
    val state: AssistantState = AssistantState.IDLE,
    /** uptimeMillis when [state] was entered; lets one-shot animations (check, shake) time themselves. */
    val since: Long = SystemClock.uptimeMillis(),
    /** What the user said (live while recognizing). */
    val transcript: String = "",
    /** Short status line under the glyph. */
    val status: String = "",
    /** The assistant's spoken answer, shown while SPEAKING. */
    val reply: String = "",
    val pending: PendingConfirm? = null,
    /** True while the microphone is open, including when listening for a yes / no. */
    val micOpen: Boolean = false
)

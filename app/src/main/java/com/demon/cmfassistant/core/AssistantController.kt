package com.demon.cmfassistant.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.action.ActionEngine
import com.demon.cmfassistant.action.ActionResult
import com.demon.cmfassistant.action.ContactLookup
import com.demon.cmfassistant.ai.Brain
import com.demon.cmfassistant.ai.SmallTalk
import com.demon.cmfassistant.ai.Turn
import com.demon.cmfassistant.audio.SpeechEngine
import com.demon.cmfassistant.audio.NeuralVoice
import com.demon.cmfassistant.audio.VoiceCatalog
import com.demon.cmfassistant.audio.VoiceHub
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.feedback.HapticController
import com.demon.cmfassistant.intent.Affirmation
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.IntentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for assistant state.
 *
 * IDLE -> LISTENING -> RECOGNIZING -> THINKING -> one of:
 *   EXECUTING -> SUCCESS | FAILURE                      (phone command)
 *   CONFIRMING (spoken question, listens for yes/no)    (needs a yes first)
 *   SPEAKING (answer) -> LISTENING again for a follow-up (conversation)
 */
class AssistantController(private val context: Context, val settings: Settings) {

    private enum class Mode { COMMAND, FOLLOWUP, CONFIRM }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val haptics = HapticController(context, settings)
    private val actions = ActionEngine(context)
    /** Offline neural voice, with the device voice as a fallback. Also used by the Voice settings screen. */
    val voice = VoiceHub(context, settings)
    private val brain = Brain(settings)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * Smoothed microphone level, 0..1. Kept as Compose state that is only read while drawing,
     * so updating it redraws the glyph without recomposing any layout.
     */
    val amplitude = mutableFloatStateOf(0f)

    private var workJob: Job? = null
    private var resetJob: Job? = null
    private var speakJob: Job? = null

    /** Bumped whenever a new flow starts, so late callbacks from an older flow are ignored. */
    private var flow = 0
    private var mode = Mode.COMMAND
    private var micActive = false
    private var retried = false
    private var lastRmsAt = 0L
    private var engineRetries = 0
    private var lastActive = 0L

    private val history = ArrayList<Turn>()

    private val speech: SpeechEngine = SpeechEngine(context, object : SpeechEngine.Listener {
        override fun onPartial(text: String) {
            if (!micActive) return
            if (mode == Mode.CONFIRM) {
                _ui.update { it.copy(transcript = text) }
            } else {
                transition(AssistantState.RECOGNIZING, "Processing your speech…", text, micOpen = true)
            }
        }

        override fun onFinal(text: String) {
            if (!micActive) return
            micActive = false
            amplitude.floatValue = 0f
            when (mode) {
                Mode.COMMAND -> if (text.isBlank()) noMatch() else heard(text)
                Mode.FOLLOWUP -> if (text.isBlank()) endQuiet() else heard(text)
                Mode.CONFIRM -> handleConfirmAnswer(text)
            }
        }

        override fun onRms(db: Float) {
            if (!micActive || _ui.value.state != AssistantState.LISTENING) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRmsAt < 60) return // ~16 updates per second is plenty
            lastRmsAt = now
            val level = ((db + 2f) / 12f).coerceIn(0f, 1f)
            amplitude.floatValue = amplitude.floatValue * 0.55f + level * 0.45f
        }

        override fun onError(code: Int) {
            if (!micActive) return
            // The recognition service dropped (client error, too many requests, server disconnected).
            // Rebuild the recognizer and try again quietly, up to twice.
            if ((code == 5 || code == 10 || code == 11) && engineRetries < 2) {
                engineRetries++
                val token = flow
                speech.release()
                scope.launch {
                    delay(400)
                    if (micActive && token == flow) speech.start(settings.language, settings.preferOffline)
                }
                return
            }
            micActive = false
            amplitude.floatValue = 0f
            val silent = code == 6 || code == 7 // SPEECH_TIMEOUT, NO_MATCH
            when (mode) {
                Mode.COMMAND -> if (silent) noMatch() else fail(errorMessage(code))
                Mode.FOLLOWUP -> if (silent) endQuiet() else fail(errorMessage(code))
                Mode.CONFIRM -> {
                    val pending = _ui.value.pending
                    if (silent && pending != null) decline(pending) else fail(errorMessage(code))
                }
            }
        }
    })

    private fun errorMessage(code: Int) = when (code) {
        8 -> "The recognizer is busy. Try again in a moment."
        9 -> "Microphone permission needed."
        1, 2, 4 -> "Speech recognition needs internet right now. Try again online."
        5, 10, 11 -> "The speech service dropped. Tap the glyph to try again."
        12, 13 -> "That language isn't available offline. Pick another in Settings."
        SpeechEngine.ERROR_UNAVAILABLE -> "No speech recognition service found on this phone."
        else -> "Speech error ($code). Tap to try again."
    }

    // ---- Public controls ----

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Tap on the glyph: start from rest, finish speaking, interrupt the answer, or answer a question. */
    fun onGlyphTap() {
        when (_ui.value.state) {
            AssistantState.IDLE, AssistantState.SUCCESS, AssistantState.FAILURE -> startListening()
            AssistantState.LISTENING, AssistantState.RECOGNIZING -> speech.stopListening()
            AssistantState.SPEAKING -> beginListening(Mode.FOLLOWUP) // barge-in: stop talking, listen
            AssistantState.CONFIRMING ->
                if (micActive) speech.stopListening() else beginListening(Mode.CONFIRM)
            else -> Unit
        }
    }

    /** Starts a new request. The recent conversation is kept for two minutes so follow-ups still make sense. */
    fun startListening() {
        if (SystemClock.uptimeMillis() - lastActive > 120_000) history.clear()
        beginListening(Mode.COMMAND)
    }

    /** Cancel from any state; always releases the microphone and silences the voice. */
    fun cancel() {
        stopEverything()
        if (_ui.value.state != AssistantState.IDLE) haptics.cancel()
        transition(AssistantState.IDLE, "", "")
    }

    /** Called when the screen goes away: stop the mic and any spoken answer, keep finished results. */
    fun onUiHidden() {
        when (_ui.value.state) {
            AssistantState.LISTENING, AssistantState.RECOGNIZING, AssistantState.THINKING,
            AssistantState.SPEAKING, AssistantState.CONFIRMING -> {
                stopEverything()
                speech.release()
                transition(AssistantState.IDLE, "", "")
            }
            else -> Unit
        }
    }

    /** "Continue" button on the confirmation card. */
    fun confirmPending() {
        val pending = _ui.value.pending ?: return
        silenceInput()
        runPending(pending)
    }

    /** "Cancel" button on the confirmation card. */
    fun dismissPending() {
        val pending = _ui.value.pending
        silenceInput()
        if (pending != null) decline(pending) else cancel()
    }

    /** Used by the Test buttons in Settings: asks the AI one thing and returns its answer or the error. */
    suspend fun testAi(prompt: String): String {
        if (!brain.configured()) return "Add an API key first."
        val result = brain.ask(emptyList(), prompt)
        return result.getOrNull() ?: (result.exceptionOrNull()?.message ?: "No answer.")
    }

    // ---- Listening ----

    private fun beginListening(m: Mode) {
        resetJob?.cancel()
        workJob?.cancel()
        speakJob?.cancel()
        voice.stop()
        flow++
        if (!hasMicPermission()) {
            fail("Microphone permission needed.")
            return
        }
        mode = m
        retried = false
        engineRetries = 0
        amplitude.floatValue = 0f
        when (m) {
            Mode.CONFIRM -> _ui.update { it.copy(micOpen = true, transcript = "") } // keep the question on screen
            Mode.COMMAND -> transition(AssistantState.LISTENING, "Speak naturally…", "", micOpen = true)
            Mode.FOLLOWUP -> transition(AssistantState.LISTENING, "Go ahead, I'm listening…", "", micOpen = true)
        }
        haptics.lightTap()
        micActive = true
        speech.start(settings.language, settings.preferOffline)
    }

    private fun noMatch() {
        if (!retried) {
            retried = true
            transition(AssistantState.LISTENING, "Didn't catch that. Listening again…", "", micOpen = true)
            micActive = true
            scope.launch {
                delay(150)
                if (micActive && _ui.value.state == AssistantState.LISTENING) {
                    speech.start(settings.language, settings.preferOffline)
                }
            }
        } else {
            fail("Didn't catch that. Tap the glyph to try again.")
        }
    }

    private fun heard(text: String) {
        haptics.veryLightTap()
        process(text)
    }

    /** Stops the microphone and the voice without changing what is on screen. */
    private fun silenceInput() {
        flow++
        speakJob?.cancel()
        speech.cancel()
        micActive = false
        amplitude.floatValue = 0f
        voice.stop()
    }

    private fun stopEverything() {
        resetJob?.cancel()
        workJob?.cancel()
        silenceInput()
    }

    // ---- Understanding and doing ----

    private fun process(text: String) {
        workJob?.cancel()
        flow++
        lastActive = SystemClock.uptimeMillis()
        workJob = scope.launch {
            transition(AssistantState.THINKING, "Understanding your request…", text)
            when (val cmd = IntentEngine.parse(text)) {
                is Command.Unknown -> chat(text, question = false)
                is Command.Question -> chat(text, question = true)
                is Command.SendSms -> prepareSms(cmd, text)
                else -> execute(cmd, text)
            }
        }
    }

    private suspend fun execute(cmd: Command, text: String) {
        haptics.lightTap()
        when (val result = actions.run(cmd)) {
            is ActionResult.Success -> {
                val launch = result.launch
                if (launch == null) success(result.message, text) else successLaunching(result.message, text, launch)
            }
            is ActionResult.Failure -> fail(result.message, text)
            is ActionResult.Confirm -> askConfirm(
                PendingConfirm(
                    title = result.title,
                    detail = result.detail,
                    spoken = result.spoken,
                    command = result.command,
                    declineMessage = result.declineMessage,
                    declineAsFailure = result.declineAsFailure
                ),
                text
            )
        }
    }

    private fun prepareSms(cmd: Command.SendSms, text: String) {
        when (val r = actions.findContact(cmd.recipient)) {
            is ContactLookup.Found -> {
                val c = r.contact
                val who = if (c.name == c.number) c.number else c.name
                askConfirm(
                    PendingConfirm(
                        title = "Send this message?",
                        detail = "To $who\n“${cmd.body}”",
                        spoken = "Message to $who: ${cmd.body}. Should I open it in Messages?",
                        command = Command.ComposeSms(c.number, c.name, cmd.body),
                        declineMessage = "Okay, I won't send it.",
                        declineAsFailure = false
                    ),
                    text
                )
            }
            ContactLookup.NoPermission -> fail("Allow Contacts access to message by name.", text)
            ContactLookup.NotFound -> fail("I couldn't find “${cmd.recipient}” in your contacts.", text)
        }
    }

    // ---- Conversation ----

    private suspend fun chat(text: String, question: Boolean) {
        if (brain.configured()) {
            transition(AssistantState.THINKING, "Thinking…", text)
            val result = brain.ask(history, text)
            val reply = result.getOrNull()
            if (reply != null) {
                remember(text, reply)
                respond(text, reply)
            } else if (question) {
                execute(Command.Search(text), text) // AI unreachable: fall back to a web search
            } else {
                fail(result.exceptionOrNull()?.message ?: "I couldn't get an answer.", text)
            }
            return
        }
        val small = SmallTalk.reply(text)
        when {
            small != null -> respond(text, small)
            question -> execute(Command.Search(text), text)
            else -> fail("I can't do that yet. Add an AI key in Settings so I can chat.", text)
        }
    }

    private fun remember(user: String, reply: String) {
        history.add(Turn("user", user))
        history.add(Turn("assistant", reply))
        while (history.size > 12) history.removeAt(0)
    }

    /** Shows and speaks an answer, then opens the mic again for a follow-up. */
    private fun respond(text: String, reply: String) {
        val token = ++flow
        transition(AssistantState.SPEAKING, "", text, reply = reply)
        haptics.veryLightTap()
        say(reply) { if (token == flow) afterReply() }
    }

    private fun afterReply() {
        lastActive = SystemClock.uptimeMillis()
        if (settings.followUp && hasMicPermission()) {
            speakJob = scope.launch {
                delay(250)
                beginListening(Mode.FOLLOWUP)
            }
        } else {
            resetJob = scope.launch {
                delay(600)
                if (_ui.value.state == AssistantState.SPEAKING) transition(AssistantState.IDLE, "", "")
            }
        }
    }

    /** No spoken follow-up needed: the conversation just ends and the screen closes. */
    private fun endQuiet() {
        flow++
        lastActive = SystemClock.uptimeMillis()
        transition(AssistantState.IDLE, "", "")
    }

    // ---- Confirmations ----

    private fun askConfirm(pending: PendingConfirm, text: String) {
        val token = ++flow
        transition(AssistantState.CONFIRMING, "", text, pending = pending)
        haptics.lightTap()
        // With voice on, ask out loud, then listen for yes / no. Otherwise the buttons do the job.
        if (settings.speakReplies && voice.available) {
            voice.speak(pending.spoken) { if (token == flow) beginListening(Mode.CONFIRM) }
        }
    }

    private fun handleConfirmAnswer(text: String) {
        val pending = _ui.value.pending
        if (pending == null) {
            endQuiet()
            return
        }
        haptics.veryLightTap()
        when (Affirmation.classify(text)) {
            Affirmation.Answer.YES -> runPending(pending)
            Affirmation.Answer.NO -> decline(pending)
            Affirmation.Answer.OTHER -> {
                // Maybe they just gave a new command ("open YouTube"). Otherwise treat it as not agreeing.
                val c = IntentEngine.parse(text)
                if (c !is Command.Unknown && c !is Command.Question) process(text) else decline(pending)
            }
        }
    }

    private fun runPending(pending: PendingConfirm) {
        workJob?.cancel()
        flow++
        val text = _ui.value.transcript
        workJob = scope.launch { execute(pending.command, text) }
    }

    private fun decline(pending: PendingConfirm) {
        if (pending.declineAsFailure) {
            fail(pending.declineMessage)
        } else {
            flow++
            haptics.cancel()
            speakStatus(pending.declineMessage)
            transition(AssistantState.IDLE, "", "")
        }
    }

    // ---- Results ----

    private fun success(message: String, transcript: String) {
        flow++
        transition(AssistantState.SUCCESS, message, transcript)
        haptics.success()
        speakStatus(message)
        scheduleIdle(1800)
    }

    /** Shows and speaks the reply, and starts the action (opening an app) the moment the voice begins, so the two line up. */
    private fun successLaunching(message: String, transcript: String, launch: () -> Unit) {
        flow++
        transition(AssistantState.SUCCESS, message, transcript)
        haptics.success()
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = { if (fired.compareAndSet(false, true)) launch() }
        if (settings.speakReplies && voice.available) {
            voice.speak(message, { go() }, {})
            scope.launch {
                delay(350) // never hold the action back longer than this if the voice is slow to start
                go()
            }
        } else {
            go()
        }
        scheduleIdle(1800)
    }

    private fun fail(message: String, transcript: String = _ui.value.transcript) {
        flow++
        transition(AssistantState.FAILURE, message, transcript)
        haptics.failure()
        speakStatus(message)
        scheduleIdle(3200)
    }

    /** Speaks a short status line if voice replies are on. Never starts a follow-up. */
    private fun speakStatus(message: String) {
        if (settings.speakReplies && voice.available) voice.speak(message) {}
    }

    /** Speaks [text] (or, with voice off, waits roughly as long as it takes to read) and then runs [then]. */
    private fun say(text: String, then: () -> Unit) {
        if (settings.speakReplies && voice.available) {
            voice.speak(text, then)
        } else {
            speakJob = scope.launch {
                delay(minOf(8000L, 1500L + text.length * 35L))
                then()
            }
        }
    }

    private fun scheduleIdle(ms: Long) {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(ms)
            var waited = 0L
            while (voice.isSpeaking && waited < 6000) { // let a spoken status finish
                delay(150)
                waited += 150
            }
            val s = _ui.value.state
            if (s == AssistantState.SUCCESS || s == AssistantState.FAILURE) {
                transition(AssistantState.IDLE, "", "")
            }
        }
    }

    init {
        voice.onNeuralReady = { prewarmVoice() }
        if (voice.neural.phase == NeuralVoice.Phase.READY) prewarmVoice()
    }

    /** Renders the short fixed replies (and "Opening <app>.") ahead of time so they start with no wait. */
    private fun prewarmVoice() {
        val model = voice.selectedModel()
        val apps = actions.appLabels().take(if (model?.heavy == true) 100 else 60).map { "Opening $it." }
        voice.prewarm(VoiceCatalog.staticPhrases + apps)
    }

    private fun transition(
        state: AssistantState,
        status: String,
        transcript: String,
        pending: PendingConfirm? = null,
        reply: String = "",
        micOpen: Boolean = false
    ) {
        _ui.update {
            UiState(
                state = state,
                since = if (it.state == state) it.since else SystemClock.uptimeMillis(),
                transcript = transcript,
                status = status,
                reply = reply,
                pending = pending,
                micOpen = micOpen
            )
        }
    }
}

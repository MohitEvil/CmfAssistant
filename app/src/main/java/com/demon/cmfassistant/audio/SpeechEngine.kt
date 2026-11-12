package com.demon.cmfassistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over Android's SpeechRecognizer. It only listens between start() and the final
 * result (or cancel), so the microphone is never open while the assistant is idle.
 *
 * One recognizer is reused between sessions: creating a new one for every turn can make the
 * recognition service drop (error 11). If it does drop, the controller calls release() and retries.
 * All calls must be made on the main thread.
 */
class SpeechEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onRms(db: Float)
        fun onError(code: Int)
    }

    companion object {
        /** Our own code: no recognition service installed. */
        const val ERROR_UNAVAILABLE = -1
    }

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    fun start(languageTag: String, preferOffline: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(ERROR_UNAVAILABLE)
            return
        }
        val gen = ++generation
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        runCatching { r.cancel() }
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == generation

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                if (live()) listener.onRms(rmsdB)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                listener.onFinal(text)
            }

            override fun onError(error: Int) {
                if (live()) listener.onError(error)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        r.startListening(intent)
    }

    /** Stop capturing and deliver whatever was heard so far. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Stop listening and ignore anything still on its way. The recognizer is kept for the next turn. */
    fun cancel() {
        generation++
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Tear the recognizer down completely (screen closed, or the service dropped). */
    fun release() {
        generation++
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}

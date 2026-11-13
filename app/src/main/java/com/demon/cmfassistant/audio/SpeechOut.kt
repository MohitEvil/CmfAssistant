package com.demon.cmfassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.demon.cmfassistant.ai.TextClean
import com.demon.cmfassistant.data.Settings
import java.util.Locale

/**
 * Text-to-speech using the phone's own engine. All callbacks run on the main thread.
 * stop() silences it and drops any pending "done" callback, so stale flows never resume.
 */
class SpeechOut(context: Context, private val settings: Settings) : Voice {

    private class Pending(val text: String, val onStart: () -> Unit, val onDone: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: Pending? = null
    private var focus: AudioFocusRequest? = null
    private var counter = 0
    private val callbacks = HashMap<String, () -> Unit>()
    private val starts = HashMap<String, () -> Unit>()

    /** False if the phone has no working speech engine; callers then just stay silent. */
    override var available = true
        private set

    override val isSpeaking: Boolean get() = tts?.isSpeaking == true

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { utteranceId?.let { starts.remove(it) }?.invoke() }
        }
        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) {}
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setAudioAttributes(attrs)
                    tts?.setOnUtteranceProgressListener(listener)
                    queued?.let { q ->
                        queued = null
                        speak(q.text, q.onStart, q.onDone)
                    }
                } else {
                    available = false
                    queued?.let { q ->
                        queued = null
                        q.onDone()
                    }
                }
            }
        }
    }

    override fun speak(raw: String, onDone: () -> Unit) = speak(raw, {}, onDone)

    override fun speak(raw: String, onStart: () -> Unit, onDone: () -> Unit) {
        val text = TextClean.forSpeech(raw)
        if (text.isBlank() || !available) {
            main.post { onStart(); onDone() }
            return
        }
        if (!ready) {
            queued = Pending(text, onStart, onDone)
            return
        }
        val engine = tts
        if (engine == null) {
            main.post { onStart(); onDone() }
            return
        }
        callbacks.clear()
        starts.clear()
        setLocale(engine, text)
        requestFocus()
        val parts = chunk(text)
        parts.forEachIndexed { i, part ->
            val id = "u${++counter}"
            if (i == 0) starts[id] = onStart
            if (i == parts.lastIndex) callbacks[id] = onDone
            val result = engine.speak(
                part,
                if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                id
            )
            if (result == TextToSpeech.ERROR) {
                abandonFocus()
                callbacks.clear()
                main.post { onStart(); onDone() }
                return
            }
        }
    }

    override fun stop() {
        callbacks.clear()
        starts.clear()
        queued = null
        tts?.stop()
        abandonFocus()
    }

    private fun finish(id: String?) {
        main.post {
            val cb = id?.let { callbacks.remove(it) }
            if (cb != null) {
                abandonFocus()
                cb()
            }
        }
    }

    private fun setLocale(engine: TextToSpeech, text: String) {
        val devanagari = text.any { it in 'ऀ'..'ॿ' }
        val tag = when {
            devanagari -> "hi-IN"
            settings.language.startsWith("hi") -> "en-IN" // Hindi typed in Latin letters reads best with the Indian English voice
            else -> settings.language
        }
        val r = engine.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    private fun chunk(t: String): List<String> {
        if (t.length <= 3500) return listOf(t)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (s in t.split(Regex("(?<=[.!?।])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > 3500) {
                out.add(cur.toString().trim())
                cur = StringBuilder()
            }
            cur.append(s).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}

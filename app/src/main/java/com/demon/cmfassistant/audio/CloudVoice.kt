package com.demon.cmfassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.demon.cmfassistant.data.Provider
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.debug.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Optional AI voice: sends the reply text to a text-to-speech provider (OpenAI style, Groq, ElevenLabs or a custom
 * OpenAI-compatible address) and plays the audio. Tries providers in order; if all fail the caller falls back to the offline voice.
 */
class CloudVoice(private val context: Context, private val settings: Settings) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val dir = File(context.cacheDir, "cloud_voice").also { it.mkdirs() }
    private var player: MediaPlayer? = null
    @Volatile private var token = 0

    val speaking: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun configured(): Boolean = settings.aiVoiceEnabled && settings.voiceProviders.any { it.ready }

    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
        val my = ++token
        val providers = settings.voiceProviders.filter { it.ready }
        pool.execute {
            var file: File? = null
            for (p in providers) {
                if (my != token) return@execute
                file = runCatching { synth(p, text) }.onFailure { AppLog.e("cloudvoice", "${p.name}: ${it.message}") }.getOrNull()
                if (file != null) break
            }
            main.post {
                if (my != token) return@post
                if (file == null) onFail() else play(file, my, onStart, onDone, onFail)
            }
        }
    }

    private fun play(f: File, my: Int, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
        release()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener { if (my == token) { onStart(); it.start() } }
            mp.setOnCompletionListener { if (my == token) { release(); onDone() } }
            mp.setOnErrorListener { _, _, _ -> if (my == token) { release(); onFail() }; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
            onFail()
        }
    }

    /** Speaks a short sample with one provider and returns a one-line result for the settings page. */
    suspend fun test(p: Provider): String {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { synth(p, "Hi, this is how I would sound.") }
        }
        val f = file.getOrNull() ?: return "Couldn't get audio: " + (file.exceptionOrNull()?.message ?: "no sound came back").take(140)
        val my = ++token
        main.post { play(f, my, {}, {}, {}) }
        return "Playing now."
    }

    fun stop() {
        token++
        release()
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private fun synth(p: Provider, text: String): File? {
        val clean = text.take(3800)
        val eleven = p.baseUrl.contains("elevenlabs")
        val groq = p.baseUrl.contains("groq")
        val ext = if (groq) "wav" else "mp3"
        val key = "${p.id}_${p.model}_${p.extra}_${clean.hashCode()}.$ext"
        val cached = File(dir, key)
        if (cached.exists() && cached.length() > 200) return cached

        val base = p.baseUrl.trimEnd('/')
        val url = if (eleven) "$base/text-to-speech/${p.extra.ifBlank { "21m00Tcm4TlvDq8ikWAM" }}?output_format=mp3_44100_64"
        else "$base/audio/speech"
        val body = if (eleven) JSONObject().put("text", clean).put("model_id", p.model)
        else JSONObject().put("model", p.model).put("input", clean).put("voice", p.extra.ifBlank { "alloy" }).put("response_format", ext)

        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (eleven) c.setRequestProperty("xi-api-key", p.key) else c.setRequestProperty("Authorization", "Bearer ${p.key}")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(160)
                throw RuntimeException("HTTP ${c.responseCode} $err")
            }
            cached.outputStream().use { out -> c.inputStream.use { it.copyTo(out) } }
        } finally {
            c.disconnect()
        }
        trim()
        return cached.takeIf { it.length() > 200 }
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > 40) files.take(files.size - 40).forEach { it.delete() }
    }
}

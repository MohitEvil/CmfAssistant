package com.demon.cmfassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.demon.cmfassistant.data.Provider
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.debug.AppLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** One choice in a fetched list: [value] is what gets saved, [label] is what is shown. */
class Pick(val value: String, val label: String)

/**
 * Optional AI voice. Google Gemini, OpenAI and ElevenLabs are STREAMED: raw 16-bit PCM goes straight into an AudioTrack
 * while it is still being generated, so speech starts after the first chunk instead of after the whole clip.
 * Other providers (Groq, custom addresses) download a file first. If everything fails the caller falls back to the offline voice.
 */
class CloudVoice(private val context: Context, private val settings: Settings) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val dir = File(context.cacheDir, "cloud_voice").also { it.mkdirs() }
    private var player: MediaPlayer? = null
    @Volatile private var token = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var pcmPlaying = false
    private val noStyle = HashSet<String>()

    val speaking: Boolean get() = pcmPlaying || runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun configured(): Boolean = settings.aiVoiceEnabled && settings.voiceProviders.any { it.ready }

    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
        val my = ++token
        val providers = settings.voiceProviders.filter { it.ready }
        pool.execute {
            var handled = false
            var file: File? = null
            for (p in providers) {
                if (my != token) return@execute
                try {
                    if (streams(p)) { play(p, text.take(3800), my) { main.post { if (my == token) onStart() } }; handled = true } else file = synth(p, text)
                } catch (e: Exception) {
                    AppLog.e("cloudvoice", "${p.name}: ${e.message}")
                }
                if (handled || file != null) break
            }
            main.post {
                if (my != token) return@post
                when {
                    handled -> onDone()
                    file != null -> playFile(file, my, onStart, onDone, onFail)
                    else -> onFail()
                }
            }
        }
    }

    private fun streams(p: Provider) = p.baseUrl.contains("generativelanguage") || p.baseUrl.contains("elevenlabs") || p.baseUrl.contains("api.openai.com")

    // ---------- streaming PCM ----------

    /** Plays [text] through [p]; blocks until it has been heard. Throws only if nothing was played. */
    private fun play(p: Provider, text: String, my: Int, onFirst: () -> Unit) {
        val cached = File(dir, pcmName(p, text))
        if (cached.exists() && cached.length() > 400) {
            runCatching {
                DataInputStream(cached.inputStream().buffered()).use { inp ->
                    val rate = inp.readInt()
                    val sink = Sink(rate, my, onFirst, null)
                    try { sink.feed(inp.readBytes(), true); sink.finish() } finally { sink.close() }
                }
                return
            }
            cached.delete()
        }
        val rec = ByteArrayOutputStream()
        var sinkRate = 0
        var sink: Sink? = null
        try {
            val onPcm = { bytes: ByteArray, rate: Int ->
                if (sink == null) { sinkRate = rate; sink = Sink(rate, my, onFirst, rec) }
                sink!!.feed(bytes, false)
            }
            when {
                p.baseUrl.contains("generativelanguage") -> googleStream(p, text, my, onPcm)
                p.baseUrl.contains("elevenlabs") -> rawStream(p, text, my, onPcm)
                else -> rawStream(p, text, my, onPcm)
            }
            val s = sink ?: throw RuntimeException("no sound came back")
            s.finish()
            if (my == token && rec.size() > 400) {
                runCatching { DataOutputStream(cached.outputStream().buffered()).use { it.writeInt(sinkRate); it.write(rec.toByteArray()) } }
                trim()
            }
        } catch (e: Exception) {
            if (sink?.started != true) throw e
            AppLog.e("cloudvoice", "stream ended early: ${e.message}")
        } finally {
            sink?.close()
            conn = null
        }
    }

    private fun pcmName(p: Provider, text: String) = "p_${p.id}_${p.model}_${p.extra}_${text.hashCode()}.pcm".replace('/', '_')

    /** Feeds PCM into an AudioTrack: waits for a small cushion before starting (no underruns), softens the first samples (no click). */
    private inner class Sink(val rate: Int, val my: Int, val onFirst: () -> Unit, val rec: ByteArrayOutputStream?) {
        val tr: AudioTrack
        var started = false
        var total = 0L
        private var carry: Int = -1
        private val cushion = rate * 2 * 3 / 10 // ~0.3 s
        private var queued = 0
        private var first = true

        init {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, rate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = tr
        }

        fun feed(raw: ByteArray, replay: Boolean) {
            if (my != token) throw RuntimeException("stopped")
            var data = raw
            if (!replay) rec?.write(raw)
            if (carry >= 0) { data = byteArrayOf(carry.toByte()) + data; carry = -1 }
            if (data.size % 2 == 1) { carry = data.last().toInt() and 0xff; data = data.copyOf(data.size - 1) }
            if (data.isEmpty()) return
            // Gentle gain + fade-in so loud or abrupt starts can't crackle the speaker.
            var i = 0
            while (i + 1 < data.size) {
                var s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
                s = (s * 0.9f).toInt()
                if (first && total < 480) s = (s * (total / 2 / 240f)).toInt()
                data[i] = (s and 0xff).toByte(); data[i + 1] = ((s shr 8) and 0xff).toByte()
                i += 2
                if (first) { total += 2; if (total >= 480) first = false } else total += 2
            }
            var off = 0
            while (off < data.size) {
                if (my != token) throw RuntimeException("stopped")
                val n = tr.write(data, off, data.size - off)
                if (n < 0) throw RuntimeException("audio error $n")
                off += n
                queued += n
                if (!started && queued >= cushion) begin()
            }
        }

        private fun begin() {
            started = true
            pcmPlaying = true
            runCatching { tr.play() }
            onFirst()
        }

        fun finish() {
            if (my != token) return
            if (!started) begin()
            runCatching { tr.stop() } // plays what is queued, then stops
            val frames = total / 2
            val limit = System.currentTimeMillis() + (frames * 1000 / rate) + 1500
            while (my == token && System.currentTimeMillis() < limit && runCatching { tr.playbackHeadPosition }.getOrDefault(Int.MAX_VALUE) < frames) {
                Thread.sleep(25)
            }
        }

        fun close() {
            runCatching { tr.pause(); tr.flush() }
            runCatching { tr.release() }
            if (track === tr) track = null
            if (started) pcmPlaying = false
        }
    }

    private fun open(url: String, p: Provider, body: JSONObject): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000
        c.readTimeout = 25000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        when {
            p.baseUrl.contains("elevenlabs") -> c.setRequestProperty("xi-api-key", p.key)
            p.baseUrl.contains("generativelanguage") -> c.setRequestProperty("x-goog-api-key", p.key)
            else -> c.setRequestProperty("Authorization", "Bearer ${p.key}")
        }
        conn = c
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return c
    }

    private fun httpError(c: HttpURLConnection): RuntimeException {
        val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(160)
        return RuntimeException("HTTP ${c.responseCode} $err")
    }

    /** Gemini TTS over :streamGenerateContent (server-sent events). Each event carries a base64 PCM piece. */
    private fun googleStream(p: Provider, text: String, my: Int, onPcm: (ByteArray, Int) -> Unit) {
        val base = p.baseUrl.trimEnd('/')
        val url = "$base/models/${p.model}:streamGenerateContent?alt=sse"
        fun body(withStyle: Boolean): JSONObject {
            val part = JSONObject().put("text", if (withStyle) VoiceStyle.decorate(text) else text)
            if (withStyle) part.put("speech_metadata", JSONObject().put("style", "Warm, natural, friendly and concise, like a helpful phone assistant"))
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
                .put(
                    "generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", p.extra.ifBlank { "Kore" }))))
                )
        }
        // Only newer TTS models understand speech_metadata; older ones answer 400, so remember that and retry plain.
        var c = open(url, p, body(p.model !in noStyle && p.model.contains("3")))
        if (c.responseCode == 400 && p.model !in noStyle && p.model.contains("3")) {
            noStyle.add(p.model); c.disconnect()
            c = open(url, p, body(false))
        }
        if (c.responseCode !in 200..299) throw httpError(c)
        val rateRx = Regex("rate=(\\d+)")
        c.inputStream.bufferedReader().use { r ->
            var firstChunk = true
            while (my == token) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                val parts = js.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val inl = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    var pcm = Base64.decode(inl.optString("data"), Base64.DEFAULT)
                    val rate = rateRx.find(inl.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                    if (firstChunk) { pcm = stripWav(pcm); firstChunk = false }
                    if (pcm.isNotEmpty()) onPcm(pcm, rate)
                }
            }
        }
    }

    /** OpenAI (response_format pcm) and ElevenLabs (pcm_24000 stream): the body of the response is raw PCM. */
    private fun rawStream(p: Provider, text: String, my: Int, onPcm: (ByteArray, Int) -> Unit) {
        val base = p.baseUrl.trimEnd('/')
        val eleven = p.baseUrl.contains("elevenlabs")
        val url = if (eleven) "$base/text-to-speech/${p.extra.ifBlank { "21m00Tcm4TlvDq8ikWAM" }}/stream?output_format=pcm_24000&optimize_streaming_latency=3"
        else "$base/audio/speech"
        val body = if (eleven) JSONObject().put("text", text).put("model_id", p.model)
        else JSONObject().put("model", p.model).put("input", text).put("voice", p.extra.ifBlank { "alloy" }).put("response_format", "pcm")
        val c = open(url, p, body)
        if (c.responseCode !in 200..299) throw httpError(c)
        var firstChunk = true
        c.inputStream.use { ins -> pump(ins, my) { chunk ->
            var b = chunk
            if (firstChunk) { b = stripWav(b); firstChunk = false }
            if (b.isNotEmpty()) onPcm(b, 24000)
        } }
    }

    private fun pump(ins: InputStream, my: Int, each: (ByteArray) -> Unit) {
        val buf = ByteArray(4096)
        while (my == token) {
            val n = ins.read(buf)
            if (n < 0) break
            if (n > 0) each(buf.copyOf(n))
        }
    }

    /** If a clip starts with a WAV header, drop it, because the header bytes would be played as a burst of noise. */
    private fun stripWav(b: ByteArray): ByteArray {
        if (b.size > 44 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()) {
            var i = 12
            while (i + 8 < b.size) {
                val id = String(b, i, 4)
                val len = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
                if (id == "data") return b.copyOfRange(i + 8, b.size)
                i += 8 + len
            }
            return b.copyOfRange(44, b.size)
        }
        return b
    }

    // ---------- file based (Groq, custom addresses) ----------

    private fun playFile(f: File, my: Int, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
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
        val my = ++token
        val result = kotlinx.coroutines.CompletableDeferred<String>()
        pool.execute {
            try {
                if (streams(p)) {
                    play(p, "Hi, this is how I would sound.", my) { result.complete("Playing now.") }
                } else {
                    val f = synth(p, "Hi, this is how I would sound.") ?: throw RuntimeException("no sound came back")
                    main.post { playFile(f, my, {}, {}, {}) }
                }
                result.complete("Playing now.")
            } catch (e: Exception) {
                result.complete("Couldn't get audio: " + (e.message ?: "no sound came back").take(140))
            }
        }
        return result.await()
    }

    fun stop() {
        token++
        release()
        runCatching { track?.pause(); track?.flush() }
        val c = conn
        if (c != null) pool.execute { runCatching { c.disconnect() } }
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    /** Download-then-play path for providers that can't stream raw audio. */
    private fun synth(p: Provider, text: String): File? {
        val clean = text.take(3800)
        val groq = p.baseUrl.contains("groq")
        val ext = if (groq) "wav" else "mp3"
        val key = "f_${p.id}_${p.model}_${p.extra}_${clean.hashCode()}.$ext".replace('/', '_')
        val cached = File(dir, key)
        if (cached.exists() && cached.length() > 200) return cached
        val base = p.baseUrl.trimEnd('/')
        val body = JSONObject().put("model", p.model).put("input", clean).put("voice", p.extra.ifBlank { "alloy" }).put("response_format", ext)
        val c = open("$base/audio/speech", p, body)
        try {
            if (c.responseCode !in 200..299) throw httpError(c)
            cached.outputStream().use { out -> c.inputStream.use { it.copyTo(out) } }
        } finally {
            c.disconnect()
        }
        trim()
        return cached.takeIf { it.length() > 200 }
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > 60) files.take(files.size - 60).forEach { it.delete() }
    }

    private fun get(url: String, p: Provider): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 20000
            when {
                p.baseUrl.contains("elevenlabs") -> c.setRequestProperty("xi-api-key", p.key)
                p.baseUrl.contains("generativelanguage") -> c.setRequestProperty("x-goog-api-key", p.key)
                else -> c.setRequestProperty("Authorization", "Bearer ${p.key}")
            }
            if (c.responseCode !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(120)
                throw RuntimeException("HTTP ${c.responseCode} $err")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Asks the provider which voice models this key can use. */
    suspend fun fetchModels(p: Provider): Result<List<Pick>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (p.baseUrl.isBlank()) throw RuntimeException("Fill in ADDRESS first.")
            if (p.key.isBlank()) throw RuntimeException("Fill in API KEY first.")
            val base = p.baseUrl.trimEnd('/')
            val ids = ArrayList<String>()
            when {
                base.contains("elevenlabs") -> {
                    val arr = JSONArray(get("$base/models", p))
                    for (i in 0 until arr.length()) arr.getJSONObject(i).optString("model_id").takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
                base.contains("generativelanguage") -> {
                    val arr = JSONObject(get("$base/models?pageSize=200", p)).optJSONArray("models") ?: JSONArray()
                    for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("name").removePrefix("models/"))
                }
                else -> {
                    val arr = JSONObject(get("$base/models", p)).optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("id"))
                }
            }
            val all = ids.filter { it.isNotBlank() }.distinct().sorted()
            val speech = all.filter { it.contains("tts", true) || it.contains("speech", true) || it.contains("voice", true) || it.contains("eleven_", true) || it.contains("orpheus", true) || it.contains("audio", true) }
            val use = speech.ifEmpty { all }
            if (use.isEmpty()) throw RuntimeException("No models came back.")
            use.map { Pick(it, it) }
        }.recoverCatching { throw RuntimeException(it.message ?: "Couldn't fetch models.") }
    }

    /** The voices this provider offers (fetched for ElevenLabs, built in for the others). */
    suspend fun fetchVoices(p: Provider): Result<List<Pick>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val base = p.baseUrl.trimEnd('/')
            when {
                base.contains("elevenlabs") -> {
                    if (p.key.isBlank()) throw RuntimeException("Fill in API KEY first.")
                    val arr = JSONObject(get("$base/voices", p)).getJSONArray("voices")
                    (0 until arr.length()).map { arr.getJSONObject(it) }.map { Pick(it.getString("voice_id"), it.optString("name") + "  ·  " + it.getString("voice_id").take(6) + "…") }
                }
                base.contains("generativelanguage") -> listOf(
                    "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus",
                    "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
                    "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
                ).map { Pick(it, it) }
                base.contains("groq") -> listOf("autumn", "diana", "hannah", "austin", "daniel", "troy").map { Pick(it, it) }
                else -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar").map { Pick(it, it) }
            }
        }.recoverCatching { throw RuntimeException(it.message ?: "Couldn't fetch voices.") }
    }
}

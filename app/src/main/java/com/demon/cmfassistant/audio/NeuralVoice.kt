package com.demon.cmfassistant.audio

import android.content.Context
import com.demon.cmfassistant.debug.AppLog
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demon.cmfassistant.ai.TextClean
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline neural voice using sherpa-onnx (Kitten / Kokoro models).
 *
 * The sherpa-onnx classes are reached by reflection, so the app still builds and runs without them:
 * if the library isn't in this build, [phase] becomes ERROR with a reason and the device voice is used instead.
 *
 * Speed tricks: audio streams to the speaker as each sentence is produced (no waiting for the whole reply),
 * and short phrases are cached on disk, so a repeated "Opening YouTube." starts instantly.
 */
class NeuralVoice(private val context: Context) {

    enum class Phase { NONE, LOADING, READY, ERROR }

    var phase by mutableStateOf(Phase.NONE)
        private set
    var error by mutableStateOf("")
        private set

    /** Called on the main thread once a model has loaded. */
    var onReady: (() -> Unit)? = null

    /** True when the sherpa-onnx library was bundled into this build. */
    val libraryPresent: Boolean by lazy {
        try {
            Class.forName(PKG + "OfflineTts")
            AppLog.d("neural", "sherpa-onnx classes found, native library loaded")
            true
        } catch (t: Throwable) {
            AppLog.e("neural", "sherpa-onnx NOT available (${t.javaClass.simpleName}: ${t.message})", t)
            false
        }
    }

    @Volatile
    var speaking = false
        private set

    /** Written before loading and cleared after the first synthesis. If it's still there on the next start, the app was killed by the engine. */
    private val marker = File(context.filesDir, "voice-loading.marker")

    /** Returns the id of a voice that was loading or synthesizing when the app last died, and clears the marker. */
    fun takeCrashMarker(): String? {
        if (!marker.exists()) return null
        val id = runCatching { marker.readText().trim() }.getOrDefault("")
        marker.delete()
        return id.ifBlank { null }
    }

    /** Puts the voice into an error state without loading it. */
    fun fail(message: String) {
        error = message
        phase = Phase.ERROR
    }

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val synth = Executors.newSingleThreadExecutor()
    private val warm = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val gen = AtomicInteger(0)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var engine: Any? = null
    @Volatile private var rate = 24000
    @Volatile private var loadedId = ""
    @Volatile private var kind = ""
    @Volatile private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    private val memory = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 24
    }
    private val cacheDir = File(context.cacheDir, "neural").apply { mkdirs() }

    // ---- Loading ----

    fun load(model: VoiceModel, dir: File) {
        if (phase == Phase.READY && loadedId == model.id) return
        phase = Phase.LOADING
        error = ""
        AppLog.d("neural", "load requested: ${model.id} (${model.kind}) from ${dir.path}")
        loader.execute {
            try {
                if (!libraryPresent) throw IllegalStateException("The neural engine isn't included in this build.")
                runCatching { marker.writeText(model.id) }
                val (tts, sr) = build(model, dir)
                val old: Any?
                synchronized(lock) {
                    old = engine
                    engine = tts
                    rate = sr
                    loadedId = model.id
                    kind = model.kind
                }
                old?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }
                AppLog.d("neural", "READY: ${model.id}, sample rate $sr")
                main.post {
                    phase = Phase.READY
                    onReady?.invoke()
                }
            } catch (t: Throwable) {
                val root = if (t is InvocationTargetException) t.cause ?: t else t
                marker.delete()
                AppLog.e("neural", "load FAILED for ${model.id}", root)
                main.post {
                    phase = Phase.ERROR
                    error = root.message ?: root.javaClass.simpleName
                }
            }
        }
    }

    fun release() {
        gen.incrementAndGet()
        val old: Any?
        synchronized(lock) {
            old = engine
            engine = null
            loadedId = ""
        }
        old?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }
        phase = Phase.NONE
    }

    // ---- Speaking ----

    /**
     * Returns false if the voice isn't ready (the caller should use another voice).
     * [onFail] runs if synthesis breaks part-way, so the caller can still say the line another way.
     */
    fun speak(raw: String, sid: Int, speed: Float, onDone: () -> Unit, onFail: () -> Unit): Boolean {
        if (phase != Phase.READY) {
            AppLog.d("neural", "speak skipped, voice not ready (phase=$phase)")
            return false
        }
        val text = TextClean.forSpeech(raw)
        AppLog.d("neural", "speak '${text.take(40)}' model=$loadedId sid=$sid speed=$speed")
        if (text.isBlank()) {
            main.post { onDone() }
            return true
        }
        val myGen = gen.incrementAndGet()
        speaking = true
        stopTrack()
        requestFocus()
        synth.execute {
            var t: AudioTrack? = null
            var failed = false
            try {
                val key = cacheKey(text, sid, speed)
                val cached = if (text.length <= CACHE_MAX_CHARS) readCache(key) else null
                AppLog.d("neural", if (cached != null) "cache hit (${cached.size} samples)" else "cache miss, generating")
                val sr = rate
                t = newTrack(sr)
                track = t
                t.play()
                val written = AtomicLong(0)
                if (cached != null) {
                    t.write(cached, 0, cached.size, AudioTrack.WRITE_BLOCKING)
                    written.set(cached.size.toLong())
                } else {
                    val keep = if (text.length <= CACHE_MAX_CHARS) ArrayList<FloatArray>() else null
                    val tr = t
                    synchronized(lock) {
                        generate(text, sid, speed) { samples ->
                            if (gen.get() != myGen) {
                                0
                            } else {
                                tr.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                                written.addAndGet(samples.size.toLong())
                                keep?.add(samples)
                                1
                            }
                        }
                    }
                    if (gen.get() == myGen && written.get() == 0L) throw IllegalStateException("The voice produced no audio for this text.")
                    if (keep != null && gen.get() == myGen && keep.isNotEmpty()) writeCache(key, merge(keep))
                }
                // Wait for the speaker to finish what was queued.
                val deadline = System.currentTimeMillis() + written.get() * 1000L / sr + 2000L
                while (gen.get() == myGen && t.playbackHeadPosition < written.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10)
                }
            } catch (e: Throwable) {
                failed = true
                val root = if (e is InvocationTargetException) e.cause ?: e else e
                AppLog.e("neural", "speak FAILED", root)
                main.post {
                    phase = Phase.ERROR
                    error = root.message ?: root.javaClass.simpleName
                }
            } finally {
                t?.let { tr ->
                    runCatching { tr.stop() }
                    runCatching { tr.release() }
                    if (track === tr) track = null
                }
                if (gen.get() == myGen) {
                    speaking = false
                    abandonFocus()
                    main.post { if (failed) onFail() else onDone() }
                }
            }
        }
        return true
    }

    fun stop() {
        gen.incrementAndGet()
        speaking = false
        stopTrack()
        abandonFocus()
    }

    /** Renders phrases in the background so they play with no wait later. Yields to real speech. */
    fun prewarm(phrases: List<String>, sid: Int, speed: Float) {
        if (phase != Phase.READY) return
        val id = loadedId
        warm.execute {
            for (p in phrases) {
                if (phase != Phase.READY || loadedId != id) return@execute
                val key = cacheKey(p, sid, speed)
                if (readCache(key) != null) continue
                while (speaking) Thread.sleep(200)
                val out = ArrayList<FloatArray>()
                try {
                    synchronized(lock) {
                        generate(p, sid, speed) { samples ->
                            out.add(samples)
                            1
                        }
                    }
                } catch (e: Throwable) {
                    AppLog.e("neural", "prewarm FAILED on '${p.take(30)}'", e)
                    return@execute
                }
                if (out.isEmpty()) {
                    AppLog.e("neural", "prewarm stopped: the voice produced no audio for '${p.take(30)}'")
                    return@execute
                }
                writeCache(key, merge(out))
            }
        }
    }

    // ---- sherpa-onnx (by reflection) ----

    private fun build(model: VoiceModel, dir: File): Pair<Any, Int> {
        val root = File(dir, "")
        AppLog.d("neural", "build ${model.id}: checking files")
        model.requiredFiles().forEach {
            val f = File(root, it)
            AppLog.d("neural", "  $it  " + if (!f.exists()) "MISSING" else if (f.isDirectory) "dir" else "${f.length() / 1024} KB")
        }
        AppLog.d("neural", "folder contains: " + (root.listFiles()?.joinToString { it.name + (if (it.isFile) " ${it.length() / 1024}KB" else "/") } ?: "nothing"))
        val missing = (if (model.resolveModel(root) == null) "model .onnx file" else null) ?: model.requiredFiles().firstOrNull { !File(root, it).exists() }
        if (missing != null) throw IllegalStateException("The voice files are incomplete ($missing). Delete the voice and download it again.")
        fun path(name: String) = File(root, name).absolutePath

        val modelCfg = bean("OfflineTtsModelConfig")
        when (model.kind) {
            "kokoro" -> {
                val k = bean("OfflineTtsKokoroModelConfig")
                set(k, "model", path(model.modelFile))
                set(k, "voices", path("voices.bin"))
                set(k, "tokens", path("tokens.txt"))
                set(k, "dataDir", path("espeak-ng-data"))
                trySet(k, "lexicon", path("lexicon-us-en.txt") + "," + path("lexicon-zh.txt"))
                set(modelCfg, "kokoro", k)
            }
            "supertonic" -> {
                val k = bean("OfflineTtsSupertonicModelConfig")
                set(k, "durationPredictor", path("duration_predictor.int8.onnx"))
                set(k, "textEncoder", path("text_encoder.int8.onnx"))
                set(k, "vectorEstimator", path(model.modelFile))
                set(k, "vocoder", path("vocoder.int8.onnx"))
                set(k, "ttsJson", path("tts.json"))
                set(k, "unicodeIndexer", path("unicode_indexer.bin"))
                set(k, "voiceStyle", path("voice.bin"))
                set(modelCfg, "supertonic", k)
            }
            "matcha" -> {
                val k = bean("OfflineTtsMatchaModelConfig")
                set(k, "acousticModel", path(model.modelFile))
                set(k, "vocoder", path(model.extras.first().second))
                set(k, "tokens", path("tokens.txt"))
                set(k, "dataDir", path("espeak-ng-data"))
                set(modelCfg, "matcha", k)
            }
            "pocket" -> {
                val k = bean("OfflineTtsPocketModelConfig")
                set(k, "lmFlow", path("lm_flow.int8.onnx"))
                set(k, "lmMain", path("lm_main.int8.onnx"))
                set(k, "encoder", path("encoder.onnx"))
                set(k, "decoder", path("decoder.int8.onnx"))
                set(k, "textConditioner", path("text_conditioner.onnx"))
                set(k, "vocabJson", path("vocab.json"))
                set(k, "tokenScoresJson", path("token_scores.json"))
                trySet(k, "voiceEmbeddingCacheCapacity", 25)
                set(modelCfg, "pocket", k)
            }
            "melo" -> {
                val k = bean("OfflineTtsVitsModelConfig")
                set(k, "model", path(model.modelFile))
                set(k, "lexicon", path("lexicon.txt"))
                set(k, "tokens", path("tokens.txt"))
                set(k, "dictDir", path("dict"))
                set(modelCfg, "vits", k)
            }
            else -> {
                val k = bean("OfflineTtsKittenModelConfig")
                val onnx = model.resolveModel(root)!!
                set(k, "model", onnx.absolutePath)
                set(k, "voices", path("voices.bin"))
                set(k, "tokens", path("tokens.txt"))
                if (File(root, "espeak-ng-data").isDirectory) set(k, "dataDir", path("espeak-ng-data"))
                val lex = model.findLexicon(root)
                if (lex != null) {
                    AppLog.d("neural", "using lexicon ${lex.name}")
                    set(k, "lexicon", lex.absolutePath)
                }
                set(modelCfg, "kitten", k)
            }
        }
        set(modelCfg, "numThreads", Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        trySet(modelCfg, "debug", true) // verbose engine messages go to the system log

        val cfg = bean("OfflineTtsConfig")
        set(cfg, "model", modelCfg)
        if (model.kind == "melo") {
            val fsts = listOf("date.fst", "number.fst").map { path(it) }.filter { File(it).exists() }
            if (fsts.isNotEmpty()) trySet(cfg, "ruleFsts", fsts.joinToString(","))
        }
        trySet(cfg, "maxNumSentences", 1) // one sentence per chunk, so audio starts after the first sentence

        AppLog.d("neural", "config built. Creating the engine now. If the app closes right after this line, the native engine crashed while loading the model.")
        val ttsCls = cls("OfflineTts")
        val ctor = ttsCls.constructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == AssetManager::class.java
        }
        val tts = if (ctor != null) ctor.newInstance(null, cfg) else ttsCls.getConstructor(cls("OfflineTtsConfig")).newInstance(cfg)
        AppLog.d("neural", "engine created")
        val sr = ttsCls.getMethod("sampleRate").invoke(tts) as Int
        return tts to sr
    }

    private val refCache = HashMap<Int, Pair<FloatArray, Int>>()

    /** Decodes the bundled 16-bit mono reference clip for a Zani style (cached in memory). */
    private fun referenceFor(sid: Int): Pair<FloatArray, Int> = synchronized(refCache) {
        refCache.getOrPut(sid) {
            val name = VoiceCatalog.zaniRefs[sid.coerceIn(0, VoiceCatalog.zaniRefs.size - 1)]
            val bytes = context.assets.open("voice_refs/$name").use { it.readBytes() }
            val rate = (bytes[24].toInt() and 0xff) or ((bytes[25].toInt() and 0xff) shl 8) or
                ((bytes[26].toInt() and 0xff) shl 16) or ((bytes[27].toInt() and 0xff) shl 24)
            var pos = 12
            var dataAt = 44
            var dataLen = bytes.size - 44
            while (pos + 8 <= bytes.size) { // find the "data" chunk instead of assuming a 44-byte header
                val len = (bytes[pos + 4].toInt() and 0xff) or ((bytes[pos + 5].toInt() and 0xff) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xff) shl 16) or ((bytes[pos + 7].toInt() and 0xff) shl 24)
                if (bytes[pos] == 'd'.code.toByte() && bytes[pos + 1] == 'a'.code.toByte()) {
                    dataAt = pos + 8
                    dataLen = minOf(len, bytes.size - dataAt)
                    break
                }
                pos += 8 + len
            }
            val n = dataLen / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                val v = (bytes[dataAt + 2 * i].toInt() and 0xff) or (bytes[dataAt + 2 * i + 1].toInt() shl 8)
                out[i] = v.toShort() / 32768f
            }
            out to rate
        }
    }

    /** Streams audio chunks to [onChunk]; the callback returns 1 to continue and 0 to stop. */
    private fun generate(text: String, sid: Int, speed: Float, onChunk: (FloatArray) -> Int) {
        val tts = engine ?: throw IllegalStateException("Voice isn't loaded.")
        AppLog.d("neural", "generate start: '${text.take(30)}' kind=$kind sid=$sid. If the app closes after this line, the crash is inside synthesis.")
        var chunks = 0
        val counting: (FloatArray) -> Int = { x -> chunks++; if (chunks == 1) AppLog.d("neural", "first audio chunk (${x.size} samples)"); onChunk(x) }
        val methods = tts.javaClass.methods
        val withConfig = methods.firstOrNull { it.name == "generateWithConfigAndCallback" && it.parameterTypes.size == 3 }
        try {
            if (withConfig != null) {
                val g = bean("GenerationConfig")
                trySet(g, "sid", sid)
                trySet(g, "speed", speed)
                trySet(g, "silenceScale", 0.15f)
                if (kind == "pocket") {
                    val (samples, ref) = referenceFor(sid)
                    set(g, "referenceAudio", samples)
                    set(g, "referenceSampleRate", ref)
                    trySet(g, "numSteps", 5)
                    trySet(g, "extra", mapOf("max_reference_audio_len" to "12", "seed" to "42"))
                }
                if (kind == "supertonic") {
                    trySet(g, "numSteps", 8)
                    trySet(g, "extra", mapOf("lang" to "en"))
                }
                withConfig.invoke(tts, text, g, counting)
                AppLog.d("neural", "generate done, $chunks chunks")
            } else {
                val older = methods.first { it.name == "generateWithCallback" && it.parameterTypes.size == 4 }
                older.invoke(tts, text, sid, speed, counting)
                AppLog.d("neural", "generate done, $chunks chunks")
            }
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        } finally {
            marker.delete() // reached only if the engine didn't kill the app
        }
    }

    private fun cls(name: String): Class<*> = Class.forName(PKG + name)

    private fun bean(name: String): Any = cls(name).getDeclaredConstructor().newInstance()

    private fun set(target: Any, prop: String, value: Any?) {
        val setter = "set" + prop.replaceFirstChar { it.uppercase() }
        val m = target.javaClass.methods.firstOrNull { it.name == setter && it.parameterTypes.size == 1 }
            ?: throw NoSuchMethodException(target.javaClass.simpleName + "." + setter)
        m.invoke(target, value)
    }

    private fun trySet(target: Any, prop: String, value: Any?) {
        runCatching { set(target, prop, value) }
    }

    // ---- Audio output ----

    private fun newTrack(sr: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, sr * 4)) // about one second of audio
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun stopTrack() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
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

    // ---- Phrase cache ----

    private fun cacheKey(text: String, sid: Int, speed: Float): String {
        val raw = "$loadedId|$sid|$speed|$text"
        val d = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): FloatArray? {
        synchronized(memory) { memory[key]?.let { return it } }
        val f = File(cacheDir, "$key.pcm")
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            synchronized(memory) { memory[key] = out }
            out
        }.getOrNull()
    }

    private fun writeCache(key: String, samples: FloatArray) {
        synchronized(memory) { memory[key] = samples }
        runCatching {
            val buf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(samples)
            File(cacheDir, "$key.pcm").writeBytes(buf.array())
        }
    }

    private fun merge(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    companion object {
        private const val PKG = "com.k2fsa.sherpa.onnx."
        private const val CACHE_MAX_CHARS = 90
    }
}

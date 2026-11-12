package com.demon.cmfassistant.audio

import android.content.Context
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
        runCatching { Class.forName(PKG + "OfflineTts"); true }.getOrDefault(false)
    }

    @Volatile
    var speaking = false
        private set

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
        loader.execute {
            try {
                if (!libraryPresent) throw IllegalStateException("The neural engine isn't included in this build.")
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
                main.post {
                    phase = Phase.READY
                    onReady?.invoke()
                }
            } catch (t: Throwable) {
                val root = if (t is InvocationTargetException) t.cause ?: t else t
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
        if (phase != Phase.READY) return false
        val text = TextClean.forSpeech(raw)
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
                    return@execute
                }
                if (out.isNotEmpty()) writeCache(key, merge(out))
            }
        }
    }

    // ---- sherpa-onnx (by reflection) ----

    private fun build(model: VoiceModel, dir: File): Pair<Any, Int> {
        val root = File(dir, "")
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
                set(k, "model", path(model.modelFile))
                set(k, "voices", path("voices.bin"))
                set(k, "tokens", path("tokens.txt"))
                set(k, "dataDir", path("espeak-ng-data"))
                set(modelCfg, "kitten", k)
            }
        }
        set(modelCfg, "numThreads", Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        trySet(modelCfg, "debug", false)

        val cfg = bean("OfflineTtsConfig")
        set(cfg, "model", modelCfg)
        if (model.kind == "melo") {
            val fsts = listOf("date.fst", "number.fst").map { path(it) }.filter { File(it).exists() }
            if (fsts.isNotEmpty()) trySet(cfg, "ruleFsts", fsts.joinToString(","))
        }
        trySet(cfg, "maxNumSentences", 1) // one sentence per chunk, so audio starts after the first sentence

        val ttsCls = cls("OfflineTts")
        val ctor = ttsCls.constructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == AssetManager::class.java
        }
        val tts = if (ctor != null) ctor.newInstance(null, cfg) else ttsCls.getConstructor(cls("OfflineTtsConfig")).newInstance(cfg)
        val sr = ttsCls.getMethod("sampleRate").invoke(tts) as Int
        return tts to sr
    }

    /** Streams audio chunks to [onChunk]; the callback returns 1 to continue and 0 to stop. */
    private fun generate(text: String, sid: Int, speed: Float, onChunk: (FloatArray) -> Int) {
        val tts = engine ?: throw IllegalStateException("Voice isn't loaded.")
        val methods = tts.javaClass.methods
        val withConfig = methods.firstOrNull { it.name == "generateWithConfigAndCallback" && it.parameterTypes.size == 3 }
        try {
            if (withConfig != null) {
                val g = bean("GenerationConfig")
                trySet(g, "sid", sid)
                trySet(g, "speed", speed)
                trySet(g, "silenceScale", 0.15f)
                if (kind == "supertonic") {
                    trySet(g, "numSteps", 8)
                    trySet(g, "extra", mapOf("lang" to "en"))
                }
                withConfig.invoke(tts, text, g, onChunk)
            } else {
                val older = methods.first { it.name == "generateWithCallback" && it.parameterTypes.size == 4 }
                older.invoke(tts, text, sid, speed, onChunk)
            }
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
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

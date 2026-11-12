package com.demon.cmfassistant.audio

import android.content.Context
import com.demon.cmfassistant.debug.AppLog
import android.os.Handler
import android.os.Looper
import com.demon.cmfassistant.data.Settings

/**
 * One place for everything voice. Uses the offline neural voice when it's installed and ready,
 * and quietly falls back to the device voice otherwise (and for Devanagari text, which the neural models don't cover).
 */
class VoiceHub(context: Context, private val settings: Settings) : Voice {

    val system = SpeechOut(context, settings)
    val neural = NeuralVoice(context)
    val manager = VoiceManager(context)

    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the neural voice is ready (used to render common phrases ahead of time). */
    var onNeuralReady: (() -> Unit)?
        get() = neural.onReady
        set(v) { neural.onReady = v }

    override val available: Boolean
        get() = system.available || neural.phase == NeuralVoice.Phase.READY

    override val isSpeaking: Boolean
        get() = neural.speaking || system.isSpeaking

    private val guard = context.getSharedPreferences("voice_guard", Context.MODE_PRIVATE)

    private fun blocked(id: String) = guard.getBoolean("blocked_$id", false)
    private fun setBlocked(id: String, on: Boolean) = guard.edit().putBoolean("blocked_$id", on).apply()

    init {
        // First start with crash protection: voices already downloaded by an older build may be the ones crashing it, so don't auto-load them once.
        if (!guard.contains("guard_v1")) {
            VoiceCatalog.all.forEach { setBlocked(it.id, true) }
            guard.edit().putBoolean("guard_v1", true).apply()
        }
        // If the engine killed the app last time while loading or speaking, don't load that voice again on start-up.
        neural.takeCrashMarker()?.let { id ->
            AppLog.e("voice", "The app was killed while using voice '$id' last time. That voice is switched off until you pick it again or re-download it.")
            setBlocked(id, true)
        }
        manager.onInstalled = { model ->
            setBlocked(model.id, false)
            main.post {
                if (neural.phase != NeuralVoice.Phase.READY) useModel(model)
            }
        }
        loadSelected()
    }

    private fun loadSelected() {
        if (settings.voiceEngine != "neural") return
        val model = VoiceCatalog.byId(settings.voiceModelId) ?: return
        if (!manager.isInstalled(model)) return
        if (blocked(model.id)) {
            neural.fail("This voice crashed the app last time it ran, so it's switched off. Tap USE to try again or delete and re-download it.")
            return
        }
        neural.load(model, manager.dirFor(model))
    }

    fun useModel(model: VoiceModel) {
        setBlocked(model.id, false)
        settings.setVoiceModel(model.id, model.defaultSid)
        settings.setVoiceEngine("neural")
        neural.load(model, manager.dirFor(model))
    }

    fun useDeviceVoice() {
        settings.setVoiceEngine("system")
        neural.stop()
    }

    fun deleteModel(model: VoiceModel) {
        if (settings.voiceModelId == model.id) neural.release()
        setBlocked(model.id, false)
        manager.delete(model)
    }

    fun selectedModel(): VoiceModel? = VoiceCatalog.byId(settings.voiceModelId)

    override fun speak(raw: String, onDone: () -> Unit) {
        val devanagari = raw.any { it in 'ऀ'..'ॿ' }
        if (settings.voiceEngine == "neural" && neural.phase == NeuralVoice.Phase.READY && !devanagari) {
            system.stop()
            val ok = neural.speak(
                raw, settings.voiceSid, settings.voiceSpeed,
                onDone = onDone,
                onFail = { AppLog.d("voice", "neural failed, falling back to phone voice"); system.speak(raw, onDone) } // the neural voice broke: still say it
            )
            if (ok) return
        }
        AppLog.d("voice", "using phone voice (engine=${settings.voiceEngine}, neural=${neural.phase}${if (neural.error.isNotBlank()) ", error=" + neural.error else ""}, devanagari=$devanagari)")
        neural.stop()
        system.speak(raw, onDone)
    }

    override fun stop() {
        neural.stop()
        system.stop()
    }

    /** Pre-renders short phrases so they start with no wait. */
    fun prewarm(phrases: List<String>) {
        if (settings.voiceEngine != "neural") return
        neural.prewarm(phrases, settings.voiceSid, settings.voiceSpeed)
    }
}

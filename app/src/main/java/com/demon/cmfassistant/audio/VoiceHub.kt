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

    init {
        manager.onInstalled = { model ->
            main.post {
                if (neural.phase != NeuralVoice.Phase.READY) useModel(model)
            }
        }
        loadSelected()
    }

    private fun loadSelected() {
        if (settings.voiceEngine != "neural") return
        val model = VoiceCatalog.byId(settings.voiceModelId) ?: return
        if (manager.isInstalled(model)) neural.load(model, manager.dirFor(model))
    }

    fun useModel(model: VoiceModel) {
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

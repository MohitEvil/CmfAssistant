package com.demon.cmfassistant.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Theme choices: follow the system, force AMOLED dark, or force light. */
object ThemeMode {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2
}

/** Ready-made settings for popular AI providers. All but Anthropic speak the OpenAI chat format. */
object AiPresets {
    data class Preset(val id: String, val name: String, val baseUrl: String, val model: String)

    val all = listOf(
        Preset("gemini", "GEMINI", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        Preset("groq", "GROQ", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Preset("openrouter", "OPENROUTER", "https://openrouter.ai/api/v1", "openrouter/auto"),
        Preset("openai", "OPENAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("anthropic", "ANTHROPIC", "https://api.anthropic.com/v1", "claude-haiku-5-5"),
        Preset("custom", "CUSTOM", "", "")
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()
}

/** How the AI should behave. The default is a quiet, minimal assistant that is good at small talk. */
object PersonaPresets {
    const val DEFAULT =
        "You are a minimal, quiet assistant who is good at small talk. Answer in one short sentence whenever you can, " +
            "two at most. Don't over-explain, don't add pleasantries, don't offer extra help, and don't ask follow-up " +
            "questions unless you truly need to. For casual chat be calm, warm and natural, like a friend who doesn't say much. " +
            "Give more detail only when the user asks for it."

    data class Preset(val name: String, val text: String)

    val all = listOf(
        Preset("MINIMAL", DEFAULT),
        Preset(
            "FRIENDLY",
            "You are a warm, upbeat friend. Chat naturally with a little curiosity, in two or three short sentences, " +
                "using casual everyday language. Ask a short question back only when it keeps the conversation going."
        ),
        Preset(
            "WITTY",
            "You are dry-witted and playful, with light teasing that is never mean. Keep replies to one or two short " +
                "sentences. Make the odd small joke, but be genuinely helpful first."
        ),
        Preset(
            "DESI BUDDY",
            "You talk like a close Indian friend, mixing Hindi and English (Hinglish) naturally when the user does. " +
                "Keep it casual, warm and short, one or two sentences."
        ),
        Preset(
            "HINGLISH",
            "You talk in natural Hinglish like a close friend: write Hindi words in Devanagari and English words in English, " +
                "mixed the way people speak, for example: ठीक है, मैं time देख लेता हूँ. Keep it casual and short, one or two sentences."
        ),
        Preset(
            "FORMAL",
            "You are a polite, professional assistant. Use clear, complete sentences with no slang, and keep replies " +
                "to two sentences unless asked for more."
        )
    )
}

/** Small SharedPreferences-backed settings holder whose values are Compose state. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("cmf_settings", Context.MODE_PRIVATE)

    var haptics by mutableStateOf(sp.getBoolean("haptics", true))
        private set
    var animations by mutableStateOf(sp.getBoolean("animations", true))
        private set
    var batterySaver by mutableStateOf(sp.getBoolean("battery_saver", false))
        private set
    var bubble by mutableStateOf(sp.getBoolean("bubble", false))
        private set
    var preferOffline by mutableStateOf(sp.getBoolean("prefer_offline", true))
        private set
    var language by mutableStateOf(sp.getString("language", "en-IN") ?: "en-IN")
        private set
    var theme by mutableIntStateOf(sp.getInt("theme", ThemeMode.SYSTEM))
        private set

    // Voice replies and conversation
    var speakReplies by mutableStateOf(sp.getBoolean("speak_replies", true))
        private set
    var followUp by mutableStateOf(sp.getBoolean("follow_up", true))
        private set

    // Optional AI chat (bring your own key)
    var aiProvider by mutableStateOf(sp.getString("ai_provider", "gemini") ?: "gemini")
        private set
    var aiBaseUrl by mutableStateOf(sp.getString("ai_base", AiPresets.byId("gemini").baseUrl) ?: "")
        private set
    var aiModel by mutableStateOf(sp.getString("ai_model", AiPresets.byId("gemini").model) ?: "")
        private set
    var aiKey by mutableStateOf(sp.getString("ai_key_" + (sp.getString("ai_provider", "gemini") ?: "gemini"), "") ?: "")
        private set

    var aiPersona by mutableStateOf(sp.getString("ai_persona", PersonaPresets.DEFAULT) ?: PersonaPresets.DEFAULT)
        private set

    // Offline neural voice
    var voiceEngine by mutableStateOf(sp.getString("voice_engine", "neural") ?: "neural")
        private set
    var voiceModelId by mutableStateOf(sp.getString("voice_model", "supertonic") ?: "supertonic")
        private set
    var voiceSid by mutableIntStateOf(sp.getInt("voice_sid", 0))
        private set
    var voiceSpeed by mutableStateOf(sp.getFloat("voice_speed", 1.15f))
        private set

    // AI thinking mode (off = faster replies)
    var aiThinking by mutableStateOf(sp.getBoolean("ai_thinking", false))
        private set

    @JvmName("applyVoiceEngine")

    fun setVoiceEngine(v: String) { voiceEngine = v; sp.edit().putString("voice_engine", v).apply() }
    @JvmName("applyVoiceSid")
    fun setVoiceSid(v: Int) { voiceSid = v; sp.edit().putInt("voice_sid", v).apply() }
    @JvmName("applyVoiceSpeed")
    fun setVoiceSpeed(v: Float) { voiceSpeed = v; sp.edit().putFloat("voice_speed", v).apply() }
    @JvmName("applyVoiceModel")
    fun setVoiceModel(id: String, sid: Int) {
        voiceModelId = id
        voiceSid = sid
        sp.edit().putString("voice_model", id).putInt("voice_sid", sid).apply()
    }
    @JvmName("applyAiThinking")
    fun setAiThinking(v: Boolean) { aiThinking = v; sp.edit().putBoolean("ai_thinking", v).apply() }

    @JvmName("applyAiPersona")

    fun setAiPersona(v: String) { aiPersona = v; sp.edit().putString("ai_persona", v).apply() }

    @JvmName("applyHaptics")

    fun setHaptics(v: Boolean) { haptics = v; sp.edit().putBoolean("haptics", v).apply() }
    @JvmName("applyAnimations")
    fun setAnimations(v: Boolean) { animations = v; sp.edit().putBoolean("animations", v).apply() }
    @JvmName("applyBatterySaver")
    fun setBatterySaver(v: Boolean) { batterySaver = v; sp.edit().putBoolean("battery_saver", v).apply() }
    @JvmName("applyBubble")
    fun setBubble(v: Boolean) { bubble = v; sp.edit().putBoolean("bubble", v).apply() }
    @JvmName("applyPreferOffline")
    fun setPreferOffline(v: Boolean) { preferOffline = v; sp.edit().putBoolean("prefer_offline", v).apply() }
    @JvmName("applyLanguage")
    fun setLanguage(v: String) { language = v; sp.edit().putString("language", v).apply() }
    @JvmName("applyTheme")
    fun setTheme(v: Int) { theme = v; sp.edit().putInt("theme", v).apply() }
    @JvmName("applySpeakReplies")
    fun setSpeakReplies(v: Boolean) { speakReplies = v; sp.edit().putBoolean("speak_replies", v).apply() }
    @JvmName("applyFollowUp")
    fun setFollowUp(v: Boolean) { followUp = v; sp.edit().putBoolean("follow_up", v).apply() }

    /** Picking a preset fills in its address and model. Each provider keeps its own key. */
    @JvmName("applyAiProvider")
    fun setAiProvider(id: String) {
        val preset = AiPresets.byId(id)
        aiProvider = id
        if (id != "custom") {
            aiBaseUrl = preset.baseUrl
            aiModel = preset.model
        }
        aiKey = sp.getString("ai_key_$id", "") ?: ""
        sp.edit()
            .putString("ai_provider", id)
            .putString("ai_base", aiBaseUrl)
            .putString("ai_model", aiModel)
            .apply()
    }

    @JvmName("applyAiBaseUrl")

    fun setAiBaseUrl(v: String) { aiBaseUrl = v; sp.edit().putString("ai_base", v).apply() }
    @JvmName("applyAiModel")
    fun setAiModel(v: String) { aiModel = v; sp.edit().putString("ai_model", v).apply() }
    @JvmName("applyAiKey")
    fun setAiKey(v: String) { aiKey = v; sp.edit().putString("ai_key_$aiProvider", v).apply() }

    // Bubble position is not UI state, so it is read and written directly.
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) { sp.edit().putInt("bubble_x", v).apply() }
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) { sp.edit().putInt("bubble_y", v).apply() }

    /** Read without needing a Settings instance (used from BootReceiver). */
    companion object {
        fun bubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences("cmf_settings", Context.MODE_PRIVATE).getBoolean("bubble", false)
    }
}

package com.demon.cmfassistant.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Theme choices: follow the system, force AMOLED dark, or force light. */
object ThemeMode {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2
}

/** One AI provider the assistant can use. The list order is the order they are tried in. */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val key: String,
    val locked: Boolean = false,
    val custom: Boolean = false
) {
    /** Has everything needed to make a request. Providers that aren't ready are skipped. */
    val ready: Boolean get() = key.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
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
    /** Providers in priority order. Edited from the AI chat page. */
    val providers = mutableStateListOf<Provider>().also { it.addAll(loadProviders()) }

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

    // What the assistant calls you (learned from "my name is ...")
    var userName by mutableStateOf(sp.getString("user_name", "") ?: "")
        private set

    // Master switch for AI chat
    var aiEnabled by mutableStateOf(sp.getBoolean("ai_enabled", true))
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
    @JvmName("applyUserName")
    fun setUserName(v: String) { userName = v; sp.edit().putString("user_name", v).apply() }

    @JvmName("applyAiEnabled")
    fun setAiEnabled(v: Boolean) { aiEnabled = v; sp.edit().putBoolean("ai_enabled", v).apply() }

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

    private fun loadProviders(): List<Provider> {
        val raw = sp.getString("ai_providers_v2", null)
        if (raw != null) {
            val out = ArrayList<Provider>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Provider(
                            o.getString("id"), o.optString("name"), o.optString("base"), o.optString("model"),
                            o.optString("key"), o.optBoolean("locked"), o.optBoolean("custom")
                        )
                    )
                }
            }
            if (out.isNotEmpty()) return out
        }
        // First run of this version: bring over whatever was set up before.
        val oldSel = sp.getString("ai_provider", "gemini") ?: "gemini"
        val list = AiPresets.all.filter { it.id != "custom" }.map { pr ->
            val sel = pr.id == oldSel
            Provider(
                pr.id, pr.name,
                if (sel) sp.getString("ai_base", pr.baseUrl) ?: pr.baseUrl else pr.baseUrl,
                if (sel) sp.getString("ai_model", pr.model) ?: pr.model else pr.model,
                sp.getString("ai_key_${pr.id}", "") ?: ""
            )
        }.toMutableList()
        if (oldSel == "custom" || !sp.getString("ai_key_custom", "").isNullOrBlank()) {
            list.add(
                Provider(
                    "custom_old", "CUSTOM", sp.getString("ai_base", "") ?: "", sp.getString("ai_model", "") ?: "",
                    sp.getString("ai_key_custom", "") ?: "", custom = true
                )
            )
        }
        val first = list.indexOfFirst { it.id == oldSel || (oldSel == "custom" && it.id == "custom_old") }
        if (first > 0) list.add(0, list.removeAt(first))
        return list
    }

    private fun persistProviders() {
        val arr = JSONArray()
        providers.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("base", it.baseUrl).put("model", it.model)
                    .put("key", it.key).put("locked", it.locked).put("custom", it.custom)
            )
        }
        sp.edit().putString("ai_providers_v2", arr.toString()).apply()
    }

    fun updateProvider(id: String, change: (Provider) -> Provider) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        providers[i] = change(providers[i])
        persistProviders()
    }

    /** Moves a provider earlier (-1) or later (+1) in the order they are tried. */
    fun moveProvider(id: String, delta: Int) {
        val i = providers.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= providers.size) return
        val p = providers.removeAt(i)
        providers.add(j, p)
        persistProviders()
    }

    fun addCustomProvider(name: String): String {
        val id = "custom_" + System.currentTimeMillis()
        providers.add(Provider(id, name.trim().uppercase().ifBlank { "CUSTOM" }, "", "", "", custom = true))
        persistProviders()
        return id
    }

    fun removeProvider(id: String) {
        providers.removeAll { it.id == id && it.custom }
        persistProviders()
    }

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

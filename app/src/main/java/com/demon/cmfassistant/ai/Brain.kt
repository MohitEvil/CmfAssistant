package com.demon.cmfassistant.ai

import com.demon.cmfassistant.data.PersonaPresets
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Turn(val role: String, val text: String) // role: "user" or "assistant"

class BrainException(message: String) : Exception(message)

/**
 * Optional chat brain. Uses the user's own API key and talks straight to the provider
 * (OpenAI-style chat completions, or Anthropic's messages API). Nothing is sent unless a key is set.
 */
class Brain(private val settings: Settings) {

    private class Reply(val code: Int, val body: String)

    fun configured(): Boolean =
        settings.aiEnabled && settings.aiKey.isNotBlank() && settings.aiModel.isNotBlank() && settings.aiBaseUrl.isNotBlank()

    suspend fun ask(history: List<Turn>, userText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(call(history, userText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrainException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(BrainException("No internet, or the AI address can't be found. Check ADDRESS in Settings."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(BrainException("The AI took too long to answer. Try again."))
        } catch (e: javax.net.ssl.SSLException) {
            Result.failure(BrainException("Secure connection failed. Check the phone's date and time, or try another network."))
        } catch (e: java.net.MalformedURLException) {
            Result.failure(BrainException("The ADDRESS in Settings isn't valid. It should start with https://"))
        } catch (e: IOException) {
            Result.failure(BrainException("Network problem (${e.javaClass.simpleName}: ${e.message?.take(60)})."))
        } catch (e: Exception) {
            Result.failure(BrainException("I couldn't read the AI's answer (${e.javaClass.simpleName})."))
        }
    }

    /** Asks the provider which models this key can use. Returns plain model ids, sorted. */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val base = settings.aiBaseUrl.trim().trimEnd('/')
            if (base.isBlank()) throw BrainException("Fill in ADDRESS first.")
            if (settings.aiKey.isBlank()) throw BrainException("Fill in API KEY first.")
            val anthropic = settings.aiProvider == "anthropic" || base.contains("anthropic.com")
            val conn = URL("$base/models" + if (anthropic) "?limit=100" else "").openConnection() as HttpURLConnection
            val reply = try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 25_000
                if (anthropic) {
                    conn.setRequestProperty("x-api-key", settings.aiKey.trim())
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + settings.aiKey.trim())
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Reply(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
            if (reply.code !in 200..299) throw BrainException(errorMessage(reply.code, reply.body))
            val root = JSONObject(reply.body)
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val id = (o?.optString("id").orEmpty().ifBlank { o?.optString("name").orEmpty() })
                    .removePrefix("models/")
                if (id.isNotBlank()) ids.add(id)
            }
            if (ids.isEmpty()) throw BrainException("No models came back from that address.")
            Result.success(ids.distinct().sorted())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrainException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(BrainException("No internet, or the address can't be found."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(BrainException("The provider took too long to answer."))
        } catch (e: Exception) {
            Result.failure(BrainException("Couldn't fetch models (${e.javaClass.simpleName})."))
        }
    }

    private fun call(history: List<Turn>, userText: String): String {
        val base = settings.aiBaseUrl.trim().trimEnd('/')
        val anthropic = settings.aiProvider == "anthropic" || base.contains("anthropic.com")
        val thinking = settings.aiThinking
        val system = systemPrompt()

        val messages = JSONArray()
        if (!anthropic) messages.put(msg("system", system))
        history.takeLast(10).forEach { messages.put(msg(it.role, it.text)) }
        messages.put(msg("user", userText))

        // Thinking tokens count against the limit, so leave plenty of room or answers get cut off mid-sentence.
        val room = if (thinking) 4096 else 1024

        val reply: Reply = if (anthropic) {
            var r = post(base + "/messages", anthropicBody(messages, system, room, thinking), anthropic = true)
            if (r.code == 400 && thinking) r = post(base + "/messages", anthropicBody(messages, system, room, false), anthropic = true)
            r
        } else {
            val url = base + "/chat/completions"
            var r = post(url, openAiBody(messages, room, thinking, useCompletionTokens = false, extras = true), anthropic = false)
            // Some models reject certain fields. Retry with gentler requests before giving up.
            if (r.code == 400 || r.code == 422) {
                r = post(url, openAiBody(messages, room, thinking, useCompletionTokens = true, extras = true), anthropic = false)
            }
            if (r.code == 400 || r.code == 422) {
                r = post(url, openAiBody(messages, room, thinking, useCompletionTokens = true, extras = false), anthropic = false)
            }
            r
        }
        if (reply.code !in 200..299) throw BrainException(errorMessage(reply.code, reply.body))
        return parse(reply.body, anthropic)
    }

    private fun anthropicBody(messages: JSONArray, system: String, room: Int, thinking: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", settings.aiModel.trim())
            .put("max_tokens", room)
            .put("system", system)
            .put("messages", messages)
        if (thinking) body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
        return body
    }

    private fun openAiBody(
        messages: JSONArray,
        room: Int,
        thinking: Boolean,
        useCompletionTokens: Boolean,
        extras: Boolean
    ): JSONObject {
        val body = JSONObject().put("model", settings.aiModel.trim()).put("messages", messages)
        if (useCompletionTokens) body.put("max_completion_tokens", room) else body.put("max_tokens", room)
        if (!useCompletionTokens) body.put("temperature", 0.7)
        if (extras) addThinkingParams(body, thinking)
        return body
    }

    /** Turns thinking on or off. Each provider spells this differently, so cover the common ones by host and by model name. */
    private fun addThinkingParams(body: JSONObject, thinking: Boolean) {
        val host = settings.aiBaseUrl.lowercase()
        val model = settings.aiModel.lowercase()
        when {
            host.contains("generativelanguage.googleapis.com") ->
                body.put(
                    "reasoning_effort",
                    when {
                        thinking -> "medium"
                        model.contains("2.5-pro") || model.contains("gemini-3") -> "low" // these cannot be fully switched off
                        else -> "none"
                    }
                )
            host.contains("groq.com") -> when {
                model.contains("gpt-oss") -> body.put("reasoning_effort", if (thinking) "medium" else "low")
                model.contains("qwen3") -> body.put("reasoning_effort", if (thinking) "default" else "none")
            }
            host.contains("openrouter.ai") -> {
                body.put("reasoning", JSONObject().put("enabled", thinking))
                if (!thinking) body.put("include_reasoning", false)
            }
            host.contains("openai.com") ->
                if (model.startsWith("gpt-5") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")) {
                    body.put("reasoning_effort", if (thinking) "medium" else if (model.startsWith("gpt-5")) "minimal" else "low")
                }
            host.contains("deepseek.com") ->
                body.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            host.contains("x.ai") ->
                if (model.contains("mini")) body.put("reasoning_effort", if (thinking) "high" else "low")
            host.contains("dashscope") || host.contains("aliyun") ->
                body.put("enable_thinking", thinking)
            host.contains("nvidia.com") ->
                body.put("chat_template_kwargs", JSONObject().put("thinking", thinking).put("enable_thinking", thinking))
            host.contains("11434") || host.contains("ollama") || host.contains("localhost") ->
                body.put("think", thinking)
        }
        if (!thinking && !body.has("reasoning_effort") && !body.has("thinking") && !body.has("reasoning")) {
            // Gateways and custom addresses: decide by model family.
            when {
                model.contains("qwen") || model.contains("qwq") -> {
                    body.put("enable_thinking", false)
                    body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
                model.contains("deepseek") || model.contains("glm") || model.contains("kimi") ->
                    body.put("thinking", JSONObject().put("type", "disabled"))
                model.contains("gpt-oss") -> body.put("reasoning_effort", "low")
                model.contains("gemini-2.5-flash") -> body.put("reasoning_effort", "none")
            }
        }
    }

    /** Models that reason no matter what is sent. Used to warn the user. */
    fun alwaysThinks(): Boolean {
        val m = settings.aiModel.lowercase()
        return m.contains("reasoner") || m.contains("-r1") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
            m.contains("thinking") || m.contains("2.5-pro") || m.contains("gemini-3") || m.contains("qwq") || m.contains("gpt-oss")
    }

    private fun post(url: String, body: JSONObject, anthropic: Boolean): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", settings.aiKey.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + settings.aiKey.trim())
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = try {
                conn.responseCode
            } catch (e: java.net.HttpRetryException) {
                e.responseCode()
            } catch (e: IOException) {
                // Android reports a rejected key (HTTP 401) as an IOException on POST requests.
                if (e.message?.contains("authentication challenge", ignoreCase = true) == true) 401 else throw e
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, anthropic: Boolean): String {
        val json = JSONObject(body)
        val raw: String
        val truncated: Boolean
        if (anthropic) {
            val blocks = json.getJSONArray("content")
            raw = buildString {
                for (i in 0 until blocks.length()) {
                    val b = blocks.getJSONObject(i)
                    if (b.optString("type") == "text") append(b.optString("text"))
                }
            }
            truncated = json.optString("stop_reason") == "max_tokens"
        } else {
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            raw = if (message.isNull("content")) "" else message.optString("content")
            truncated = choice.optString("finish_reason") == "length"
        }
        var reply = TextClean.forDisplay(raw)
        if (truncated) {
            if (reply.isBlank()) {
                throw BrainException("The AI ran out of room while thinking. Turn off Thinking mode in Settings.")
            }
            reply = endAtSentence(reply)
        }
        if (reply.isBlank()) throw BrainException("The AI sent an empty answer.")
        return reply
    }

    /** If the answer was cut off by a length limit, end it cleanly at the last full sentence. */
    private fun endAtSentence(text: String): String {
        val last = text.indexOfLast { it == '.' || it == '!' || it == '?' || it == '।' }
        return if (last >= text.length / 2) text.substring(0, last + 1) else text
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val j = JSONObject(body)
            when (val e = j.opt("error")) {
                is JSONObject -> e.optString("message")
                is String -> e
                else -> ""
            }
        }.getOrDefault("")
        return when (code) {
            400 -> "The AI rejected the request. Check the model name in Settings." +
                if (detail.isNotBlank()) " (${detail.take(70)})" else ""
            401, 403 -> "The AI key was rejected. Check it in Settings."
            404 -> "The AI address or model wasn't found. Check Settings."
            429 -> "The AI is rate limited right now. Try again in a minute."
            else -> if (detail.isNotBlank()) "AI error $code: ${detail.take(80)}" else "The AI service had an error ($code)."
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        val persona = settings.aiPersona.trim().ifEmpty { PersonaPresets.DEFAULT }
        return "You are CMF Assistant, a voice assistant on the user's Android phone. " +
            "How you should behave and sound: $persona " +
            "Your replies are spoken aloud: plain text only, no markdown, no lists, no emojis, no links. " +
            "Use natural punctuation so the voice sounds expressive. Always finish your sentences. " +
            "Reply in the language the user used. If they speak Hindi or Hinglish (Hindi in Latin letters), " +
            "reply the same way, using Devanagari only if they did (unless the persona above says how to write Hindi). " +
            "Be honest when you don't know something. " +
            "Phone actions such as the flashlight, apps, timers, alarms, calls and texts are handled by the phone separately, " +
            "so never claim you did one. You have no live internet, so say so for news, scores or prices. " +
            "Current date and time: $now."
    }
}

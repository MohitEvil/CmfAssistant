package com.demon.cmfassistant.agent

import android.content.Context
import com.demon.cmfassistant.ai.Brain
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a sentence the fixed grammar didn't understand into tool calls. Needle 3 first (instant, offline),
 * then the user's AI provider if one is set up. Several calls become one chain, so cross-app jobs just work.
 */
class Planner(private val context: Context, private val brain: Brain) {

    private fun commandsFrom(calls: JSONArray?): List<Command> {
        val out = ArrayList<Command>()
        if (calls == null) return out
        for (i in 0 until calls.length()) {
            val o = calls.optJSONObject(i) ?: continue
            val args = o.optJSONObject("arguments") ?: JSONObject()
            Tools.toCommand(o.optString("name"), args)?.let { out.add(it) }
        }
        return out
    }

    private fun pack(list: List<Command>): Command? = when {
        list.isEmpty() -> null
        list.size == 1 -> list[0]
        else -> Command.Chain(list)
    }

    suspend fun plan(text: String): Command? {
        // 1. Needle 3, on the phone.
        if (NeedleEngine.bundled(context)) {
            val r = withContext(Dispatchers.Default) { NeedleEngine.complete(context, text) }
            if (r != null) {
                val conf = r.optDouble("confidence", 1.0)
                val cmds = commandsFrom(r.optJSONArray("function_calls"))
                AppLog.d("needle", "'${text.take(40)}' -> ${cmds.size} calls, confidence $conf")
                if (cmds.isNotEmpty() && (conf.isNaN() || conf >= 0.35)) return pack(cmds)
            }
        }
        // 2. The user's own AI, asked to pick tools.
        if (brain.configured() && looksLikeOrder(text)) {
            val system = "You route a phone assistant's requests to tools. Reply with ONLY JSON: {\"calls\":[{\"name\":\"tool\",\"arguments\":{...}}]}. " +
                "Use several calls, in order, for multi-step requests. If the user is just chatting or asking a question, reply {\"calls\":[]}.\nTools:\n" + Tools.promptList()
            val reply = brain.ask(emptyList(), text, system).getOrNull() ?: return null
            val json = reply.substringAfter('{', "").let { if (it.isEmpty()) null else "{" + it.substringBeforeLast('}') + "}" } ?: return null
            val cmds = runCatching { commandsFrom(JSONObject(json).optJSONArray("calls")) }.getOrDefault(emptyList())
            return pack(cmds)
        }
        return null
    }

    private fun looksLikeOrder(t: String): Boolean {
        val w = t.trim().split(Regex("\\s+"))
        if (w.size < 3) return false
        return !Regex("^(?:what|who|why|how|when|where|which|is|are|do|does|did|can you tell|tell me|explain|define)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t.trim())
    }
}

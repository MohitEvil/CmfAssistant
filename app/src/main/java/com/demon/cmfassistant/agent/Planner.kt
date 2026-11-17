package com.demon.cmfassistant.agent

import android.content.Context
import com.demon.cmfassistant.access.CmfAccess
import com.demon.cmfassistant.ai.Brain
import com.demon.cmfassistant.ai.Turn
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a sentence into tool calls. The hybrid order is decided by the controller:
 * Needle 3 (on the phone) -> cloud AI (if switched on) -> fixed grammar -> plain answer. Any plan, from either brain,
 * can hold several calls (open, wait, search, install...) and runs as one chain.
 */
class Planner(private val context: Context, private val brain: Brain) {

    class Plan(val command: Command, val confidence: Double, val source: String)

    /** 1. Needle 3: instant and offline. Null when it isn't in this build or isn't sure. */
    suspend fun needle(text: String): Plan? {
        if (!NeedleEngine.bundled(context)) return null
        val r = withContext(Dispatchers.Default) { NeedleEngine.complete(context, text) } ?: return null
        val conf = r.optDouble("confidence", 1.0).let { if (it.isNaN()) 1.0 else it }
        val cmd = Tools.toPlan(r.optJSONArray("function_calls"))
        AppLog.d("needle", "'${text.take(50)}' -> ${cmd?.javaClass?.simpleName} confidence $conf")
        if (cmd == null || conf < 0.45) return null
        return Plan(cmd, conf, "needle")
    }

    private fun screenContext(): String {
        val svc = CmfAccess.instance ?: return "Screen control is off."
        return runCatching {
            val labels = svc.candidates().map { it.label.trim() }.filter { it.length in 2..40 }.distinct().take(28)
            "App in front: ${svc.frontPackage()}. Visible on screen: " + labels.joinToString(" | ")
        }.getOrDefault("")
    }

    /** 2. The user's own cloud AI, shown the tools, the recent conversation and what is on screen. */
    suspend fun cloud(text: String, history: List<Turn>): Plan? {
        if (!brain.configured()) return null
        val now = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date())
        val system = "You are the planner of a voice assistant on an Android phone (India). Turn the user's request into tool calls. " +
            "Reply with ONLY JSON: {\"calls\":[{\"name\":\"tool\",\"arguments\":{...}}]}. " +
            "Rules: use several calls, in order, for multi-step requests; put a wait call wherever the user says wait; " +
            "to install or update an app use install_app (one call); to tap something visible use tap_on_screen with its exact on-screen label; " +
            "use the conversation so far to resolve words like it, that, him, there; " +
            "if the user is only chatting or asking a question to be answered, reply {\"calls\":[]}. Never invent tools.\n" +
            "Now: $now.\n${screenContext()}\nTools:\n" + Tools.promptList()
        val reply = brain.ask(history, text, system).getOrNull() ?: return null
        val json = reply.substringAfter('{', "").let { if (it.isEmpty()) null else "{" + it.substringBeforeLast('}') + "}" } ?: return null
        val cmd = runCatching { Tools.toPlan(JSONObject(json).optJSONArray("calls")) }.getOrNull() ?: return null
        AppLog.d("cloudplan", "'${text.take(50)}' -> ${cmd.javaClass.simpleName}")
        return Plan(cmd, 0.7, "cloud")
    }
}

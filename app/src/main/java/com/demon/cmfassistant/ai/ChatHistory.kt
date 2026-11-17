package com.demon.cmfassistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved conversation: what you asked and what came back, for chat answers and for jobs done on the phone.
 * Kept on the phone (never sent anywhere by itself) so follow-ups like "and its value?" or "now turn it off" make sense,
 * even a while later or after the app restarts.
 */
class ChatHistory(context: Context) {
    data class Entry(val role: String, val text: String, val ts: Long, val kind: String)

    private val sp = context.applicationContext.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    private val list = ArrayList<Entry>()

    init {
        runCatching {
            val arr = JSONArray(sp.getString("v1", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Entry(o.getString("r"), o.getString("t"), o.optLong("ts"), o.optString("k", "chat")))
            }
        }
    }

    @Synchronized private fun save() {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("r", e.role).put("t", e.text).put("ts", e.ts).put("k", e.kind))
        sp.edit().putString("v1", arr.toString()).apply()
    }

    /** [kind] is "chat" for answers and conversation, "action" for things done on the phone. */
    @Synchronized fun add(role: String, text: String, kind: String = "chat") {
        val t = text.trim().take(700)
        if (t.isEmpty()) return
        list.add(Entry(role, t, System.currentTimeMillis(), kind))
        while (list.size > 240) list.removeAt(0)
        save()
    }

    @Synchronized fun addPair(user: String, reply: String, kind: String = "chat") {
        add("user", user, kind)
        add("assistant", reply, kind)
    }

    /** The latest turns, oldest first, from the last [maxAgeMin] minutes. */
    @Synchronized fun recent(n: Int = 12, maxAgeMin: Int = 45, kind: String? = null): List<Turn> {
        val cut = System.currentTimeMillis() - maxAgeMin * 60_000L
        return list.filter { it.ts >= cut && (kind == null || it.kind == kind) }.takeLast(n).map { Turn(it.role, it.text) }
    }

    /** What was last asked about, for resolving "it", "that", "and its value". */
    @Synchronized fun lastChatQuestion(maxAgeMin: Int = 45): String? {
        val cut = System.currentTimeMillis() - maxAgeMin * 60_000L
        return list.lastOrNull { it.role == "user" && it.kind == "chat" && it.ts >= cut }?.text
    }

    @Synchronized fun clear() { list.clear(); save() }

    @Synchronized fun size() = list.size
}

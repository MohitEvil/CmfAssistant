package com.demon.cmfassistant.agent

import android.content.Context
import com.demon.cmfassistant.debug.AppLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Needle 3 (Cactus): a tiny on-device model that turns a sentence into tool calls. It is optional: the GitHub build
 * adds it when the engine downloads; if it isn't there [available] stays false and nothing else changes.
 */
object NeedleEngine {
    @Volatile private var state = 0 // 0 untried, 1 ready, -1 unavailable
    @Volatile var reason = "Not set up."
        private set
    private val lock = Any()

    external fun nativeLoad(path: String): Boolean
    external fun nativeInit(system: String, tools: String): Boolean
    external fun nativeComplete(text: String, maxNew: Int): String?
    external fun nativeReset()

    /** True if the engine library and the weights file are part of this build. */
    fun bundled(c: Context): Boolean = runCatching { c.assets.list("")?.contains("needle3.cact") == true }.getOrDefault(false)

    fun available(c: Context): Boolean {
        if (state != 0) return state == 1
        synchronized(lock) {
            if (state != 0) return state == 1
            state = try {
                if (!bundled(c)) { reason = "Needle 3 isn't included in this build."; -1 } else {
                    System.loadLibrary("needle_jni")
                    val out = File(c.filesDir, "needle3.cact")
                    if (!out.exists() || out.length() < 1_000_000) c.assets.open("needle3.cact").use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    if (!nativeLoad(out.absolutePath)) { reason = "The Needle weights didn't load."; -1 }
                    else if (!nativeInit(facts(), Tools.schemaJson())) { reason = "Needle didn't accept the tool list."; -1 }
                    else { reason = "Needle 3 is on."; 1 }
                }
            } catch (t: Throwable) {
                reason = "Needle engine missing (${t.javaClass.simpleName})."
                -1
            }
            AppLog.d("needle", reason)
            return state == 1
        }
    }

    private fun facts() = "date: " + SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date()) + "; locale: en-IN; device: phone"

    /** Raw engine answer for one sentence, or null. */
    fun complete(c: Context, text: String): JSONObject? {
        if (!available(c)) return null
        return synchronized(lock) {
            runCatching {
                nativeReset()
                nativeComplete(text, 256)?.let { JSONObject(it) }
            }.getOrNull()
        }
    }
}

package com.demon.cmfassistant.priv

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Optional elevated shell: Shizuku (adb-level) or root. Everything here is a bonus. When neither is
 * granted, [available] is false and callers fall back to normal Android APIs and screen control.
 */
object Priv {
    class Out(val ok: Boolean, val text: String, val code: Int = 0)

    enum class Via { NONE, SHIZUKU, ROOT }

    @Volatile var preference: () -> String = { "auto" }
    @Volatile private var rootChecked = 0L
    @Volatile private var rootOk = false

    // ---- Shizuku ----

    fun shizukuInstalled(c: Context) = runCatching { c.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)

    fun shizukuRunning(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        shizukuRunning() && !rikka.shizuku.Shizuku.isPreV11() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku() {
        runCatching { if (shizukuRunning()) rikka.shizuku.Shizuku.requestPermission(4242) }
    }

    /** "ADB" or "root" when Shizuku runs as one of those. */
    fun shizukuLevel(): String = runCatching { if (rikka.shizuku.Shizuku.getUid() == 0) "root" else "adb" }.getOrDefault("adb")

    // ---- Root ----

    /** Whether `su` works. Cached for a minute, because the first call may show the root manager's prompt. */
    fun rootAvailable(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - rootChecked < 60_000) return rootOk
        rootChecked = now
        rootOk = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val done = p.waitFor(6, TimeUnit.SECONDS)
            val out = if (done) p.inputStream.bufferedReader().readText() else ""
            if (!done) p.destroy()
            done && out.contains("uid=0")
        }.getOrDefault(false)
        return rootOk
    }

    // ---- Choosing ----

    fun via(): Via {
        val pref = preference()
        return when (pref) {
            "off" -> Via.NONE
            "shizuku" -> if (shizukuGranted()) Via.SHIZUKU else Via.NONE
            "root" -> if (rootAvailable()) Via.ROOT else Via.NONE
            else -> when {
                shizukuGranted() -> Via.SHIZUKU
                rootOk && rootAvailable() -> Via.ROOT
                else -> Via.NONE
            }
        }
    }

    /** Cheap check for deciding whether to try an elevated route at all. */
    fun available(): Boolean = via() != Via.NONE

    /** Looks for root once (may show a prompt). Call from a background thread when the user turns "Auto" on or taps Check. */
    fun probe(): Via {
        if (preference() != "off" && !shizukuGranted()) rootAvailable(force = true)
        return via()
    }

    fun describe(c: Context): String {
        val pref = preference()
        if (pref == "off") return "Off. I only use normal Android access and screen control."
        val v = via()
        return when (v) {
            Via.SHIZUKU -> "Shizuku connected (${shizukuLevel()}). Deep controls are on."
            Via.ROOT -> "Root granted. Deep controls are on."
            Via.NONE -> when {
                shizukuRunning() && pref != "root" -> "Shizuku is running. Tap Grant to allow it."
                shizukuInstalled(c) && pref != "root" -> "Shizuku is installed but not started."
                else -> "No Shizuku or root found. Everything still works without them."
            }
        }
    }

    // ---- Running commands ----

    suspend fun sh(command: String, timeoutSec: Long = 20): Out = withContext(Dispatchers.IO) {
        try {
            val process: Process = when (via()) {
                Via.SHIZUKU -> {
                    val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    m.isAccessible = true
                    m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                }
                Via.ROOT -> ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                Via.NONE -> return@withContext Out(false, "No Shizuku or root access.", -1)
            }
            val outText = StringBuilder()
            val reader = Thread { runCatching { outText.append(process.inputStream.bufferedReader().readText()) } }.apply { start() }
            val errText = StringBuilder()
            val reader2 = Thread { runCatching { errText.append(process.errorStream.bufferedReader().readText()) } }.apply { start() }
            val done = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) { process.destroy(); return@withContext Out(false, "Timed out.", -2) }
            reader.join(500); reader2.join(500)
            val code = process.exitValue()
            val text = (outText.toString() + errText.toString()).trim()
            Out(code == 0, text, code)
        } catch (e: Throwable) {
            val root = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            Out(false, root.message ?: root.javaClass.simpleName, -3)
        }
    }
}

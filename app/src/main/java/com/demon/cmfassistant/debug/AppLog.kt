package com.demon.cmfassistant.debug

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary debug log. Every line is written straight to a file as it happens, so if the app is killed by a
 * native crash the last lines before it are still there the next time you open the log.
 */
object AppLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE = 300_000L

    private val lock = Any()
    private val buf = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private var out: FileOutputStream? = null
    private var file: File? = null

    /** Bumped on every new line so the log screen refreshes itself. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.filesDir, "debug.log")
            file = f
            runCatching {
                if (f.exists() && f.length() > MAX_FILE) {
                    val tail = f.readText().takeLast(100_000)
                    f.writeText(tail)
                }
                if (f.exists()) buf.addAll(f.readLines().takeLast(MAX_LINES))
                out = FileOutputStream(f, true)
            }
        }
        d("app", "======== app started ========")
        d("app", "device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}), abis ${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching {
            val dir = File(context.applicationInfo.nativeLibraryDir)
            val libs = dir.listFiles()?.joinToString { it.name + " " + it.length() / 1024 + "KB" } ?: "none"
            d("app", "native libs in ${dir.path}: $libs")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("crash", "UNCAUGHT on thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun d(tag: String, msg: String) = add("$tag  $msg")

    fun e(tag: String, msg: String, t: Throwable? = null) {
        add("$tag  ERROR $msg")
        var c = t
        var depth = 0
        while (c != null && depth < 4) {
            add("$tag    ${c.javaClass.name}: ${c.message}")
            c.stackTrace.take(8).forEach { add("$tag      at $it") }
            c = c.cause
            depth++
        }
    }

    private fun add(line: String) {
        val stamped = fmt.format(Date()) + " " + line
        synchronized(lock) {
            buf.add(stamped)
            while (buf.size > MAX_LINES) buf.removeAt(0)
            runCatching { out?.write((stamped + "\n").toByteArray()) }
        }
        main.post { version++ }
    }

    fun snapshot(): List<String> = synchronized(lock) { ArrayList(buf) }

    fun clear() {
        synchronized(lock) {
            buf.clear()
            runCatching { file?.writeText("") }
        }
        main.post { version++ }
    }

    /** Recent system log lines from this app (including native engine messages and crash reports). */
    fun systemLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.destroy()
        val keep = Regex("sherpa|onnx|ort|libc|DEBUG|AndroidRuntime|cmfassistant|FATAL|SIGSEGV|SIGABRT|Abort|tombstone|AudioTrack", RegexOption.IGNORE_CASE)
        val lines = text.lines().filter { keep.containsMatchIn(it) }.takeLast(250)
        if (lines.isEmpty()) "No matching system log lines (this phone may hide them)." else lines.joinToString("\n")
    }.getOrElse { "Couldn't read the system log: ${it.message}" }

    /** Sizes of everything downloaded for the voices, to spot missing or cut-off files. */
    fun voiceFiles(context: Context): String {
        val sb = StringBuilder()
        fun walk(f: File, depth: Int) {
            val kids = f.listFiles()?.sortedBy { it.name } ?: return
            for (k in kids) {
                val pad = "  ".repeat(depth)
                if (k.isDirectory) {
                    val count = k.walkTopDown().count { it.isFile }
                    sb.append(pad).append(k.name).append("/  ($count files)\n")
                    if (depth < 1) walk(k, depth + 1)
                } else {
                    sb.append(pad).append(k.name).append("  ").append(k.length() / 1024).append(" KB\n")
                }
            }
        }
        val root = File(context.filesDir, "voices")
        if (!root.exists()) return "No voices downloaded."
        walk(root, 0)
        val cache = File(context.cacheDir, "neural").listFiles()?.size ?: 0
        sb.append("\nphrase cache: $cache clips")
        return sb.toString()
    }
}

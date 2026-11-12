package com.demon.cmfassistant.audio

import android.content.Context
import com.demon.cmfassistant.debug.AppLog
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class InstallPhase { NOT_INSTALLED, DOWNLOADING, INSTALLING, INSTALLED, FAILED }

data class InstallState(val phase: InstallPhase, val progress: Float = 0f, val message: String = "")

/** Downloads voice models once (needs internet that one time), unpacks them, and keeps track of what's installed. */
class VoiceManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.filesDir, "voices").apply { mkdirs() }
    private val jobs = HashMap<String, Job>()

    /** Called on a background thread after a model finishes installing. */
    var onInstalled: ((VoiceModel) -> Unit)? = null

    val states = mutableStateMapOf<String, InstallState>()

    init {
        // Clear out the removed Kitten downloads to free space.
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("kitten") }?.forEach { it.deleteRecursively() }
        VoiceCatalog.all.forEach { states[it.id] = if (isInstalled(it)) InstallState(InstallPhase.INSTALLED) else InstallState(InstallPhase.NOT_INSTALLED) }
    }

    fun dirFor(model: VoiceModel) = File(root, model.folder)

    /** Installed only if the download finished completely (marker file) and every required file is present. */
    fun isInstalled(model: VoiceModel) =
        File(dirFor(model), ".complete").exists() && model.resolveModel(dirFor(model)) != null && model.requiredFiles().all { File(dirFor(model), it).exists() }

    fun download(model: VoiceModel) {
        if (jobs[model.id]?.isActive == true) return
        jobs[model.id] = scope.launch {
            val part = File(root, model.id + ".part")
            try {
                AppLog.d("download", "start ${model.id}: ${model.url}")
                states[model.id] = InstallState(InstallPhase.DOWNLOADING, 0f)
                val share = 1f / (1 + model.extras.size)
                fetch(model.url, part, { ensureActive() }) { p -> states[model.id] = InstallState(InstallPhase.DOWNLOADING, p * share) }
                AppLog.d("download", "archive downloaded: ${part.length() / 1024} KB, extracting")
                states[model.id] = InstallState(InstallPhase.INSTALLING)
                extract(part)
                AppLog.d("download", "extracted into ${dirFor(model).path}")
                part.delete()
                model.extras.forEachIndexed { i, (url, name) ->
                    val target = File(dirFor(model), name)
                    val tmp = File(root, model.id + ".extra")
                    fetch(url, tmp, { ensureActive() }) { p -> states[model.id] = InstallState(InstallPhase.DOWNLOADING, share * (i + 1 + p)) }
                    target.parentFile?.mkdirs()
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                val missing = (if (model.resolveModel(dirFor(model)) == null) listOf("model .onnx file") else emptyList<String>()) + model.requiredFiles().filter { !File(dirFor(model), it).exists() }
                if (missing.isNotEmpty()) {
                    dirFor(model).deleteRecursively()
                    throw IOException("The download was incomplete (missing ${missing.first()}). Try again.")
                }
                File(dirFor(model), ".complete").writeText("ok")
                AppLog.d("download", "${model.id} installed OK")
                states[model.id] = InstallState(InstallPhase.INSTALLED)
                onInstalled?.invoke(model)
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                File(root, model.id + ".extra").delete()
                states[model.id] = InstallState(InstallPhase.NOT_INSTALLED)
                throw e
            } catch (e: Exception) {
                part.delete()
                File(root, model.id + ".extra").delete()
                AppLog.e("download", "${model.id} failed", e)
                states[model.id] = InstallState(InstallPhase.FAILED, message = friendly(e))
            }
        }
    }

    private fun fetch(url: String, dest: File, alive: () -> Unit, report: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("The server answered ${conn.responseCode}.")
            val total = conn.contentLengthLong
            AppLog.d("download", "server answered ${conn.responseCode}, size ${total / 1024} KB")
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        alive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            report(if (total > 0) done.toFloat() / total else 0f)
                        }
                    }
                }
            }
            AppLog.d("download", "received ${done / 1024} KB of ${total / 1024} KB")
            if (total > 0 && done != total) throw IOException("The download was cut short. Check your connection and try again.")
        } finally {
            conn.disconnect()
        }
    }

    fun cancel(model: VoiceModel) {
        jobs[model.id]?.cancel()
    }

    fun delete(model: VoiceModel) {
        jobs[model.id]?.cancel()
        dirFor(model).deleteRecursively()
        states[model.id] = InstallState(InstallPhase.NOT_INSTALLED)
    }

    private fun extract(archive: File) {
        val base = root.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16), true)).use { tar ->
            while (true) {
                val entry: ArchiveEntry = tar.nextEntry ?: break
                val dest = File(root, entry.name).canonicalFile
                if (!dest.path.startsWith(base)) continue // ignore anything that tries to escape the folder
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                }
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet. Connect once to download the voice."
        is java.net.SocketTimeoutException -> "The download timed out. Try again."
        else -> e.message ?: e.javaClass.simpleName
    }
}

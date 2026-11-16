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
        // Clear out the removed Kitten, Matcha and Melo downloads to free space.
        root.listFiles()?.filter { it.isDirectory && (it.name.startsWith("kitten") || it.name.startsWith("matcha") || it.name.startsWith("vits-melo") || it.name.contains("pocket")) }?.forEach { it.deleteRecursively() }
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
                extract(part, if (model.kind == "piper") dirFor(model) else root)
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
                if (model.kind == "piper") preparePiper(model)
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
            if (conn.contentType?.startsWith("text/html") == true) throw IOException("The link gave a web page instead of the file. If it's a Google Drive link, set sharing to \"Anyone with the link\".")
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

    private fun extract(archive: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16), true)).use { tar ->
            while (true) {
                val entry: ArchiveEntry = tar.nextEntry ?: break
                val dest = File(into, entry.name).canonicalFile
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

    /** Turns a raw Piper export into what sherpa-onnx wants: a tokens.txt, and the metadata it reads from the model file. */
    private fun preparePiper(model: VoiceModel) {
        val dir = dirFor(model)
        val onnx = File(dir, model.modelFile)
        val cfg = org.json.JSONObject(File(dir, model.modelFile + ".json").readText())
        val tokens = StringBuilder()
        val ids = cfg.getJSONObject("phoneme_id_map")
        for (sym in ids.keys()) {
            if (sym == "\n") continue
            val v = ids.get(sym)
            val id = if (v is org.json.JSONArray) v.getInt(0) else (v as Number).toInt()
            tokens.append(sym).append(' ').append(id).append('\n')
        }
        File(dir, "tokens.txt").writeText(tokens.toString())
        var rate = cfg.getJSONObject("audio").getInt("sample_rate")
        if (rate == 22500) rate = 22050
        val voice = cfg.optJSONObject("espeak")?.optString("voice").orEmpty().ifBlank { cfg.optString("lang_code", "en-us") }
        val meta = linkedMapOf(
            "model_type" to "vits", "comment" to "piper", "language" to "English", "voice" to voice, "version" to "1",
            "has_espeak" to "1", "has_g2pw" to "0", "n_speakers" to cfg.optInt("num_speakers", 1).toString(), "sample_rate" to rate.toString()
        )
        if (!hasMeta(onnx)) appendMeta(onnx, meta)
        AppLog.d("download", "piper prepared: voice=$voice rate=$rate tokens=${ids.length()}")
    }

    /** True if the model already carries sherpa's piper metadata. */
    private fun hasMeta(f: File): Boolean {
        val key = "n_speakers".toByteArray()
        val buf = ByteArray(1 shl 16)
        var carry = 0
        f.inputStream().use { input ->
            while (true) {
                val n = input.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val len = carry + n
                var i = 0
                while (i <= len - key.size) {
                    var k = 0
                    while (k < key.size && buf[i + k] == key[k]) k++
                    if (k == key.size) return true
                    i++
                }
                carry = minOf(key.size - 1, len)
                System.arraycopy(buf, len - carry, buf, 0, carry)
            }
        }
        return false
    }

    /** Appends ONNX metadata_props entries. Protobuf merges repeated fields, so appending bytes to the end of the file is valid. */
    private fun appendMeta(f: File, meta: Map<String, String>) {
        val out = java.io.ByteArrayOutputStream()
        fun varint(o: java.io.ByteArrayOutputStream, v0: Int) { var v = v0; while (v >= 0x80) { o.write((v and 0x7f) or 0x80); v = v ushr 7 }; o.write(v) }
        for ((k, v) in meta) {
            val kb = k.toByteArray(); val vb = v.toByteArray()
            val e = java.io.ByteArrayOutputStream()
            e.write(0x0a); varint(e, kb.size); e.write(kb)
            e.write(0x12); varint(e, vb.size); e.write(vb)
            out.write(0x72); varint(out, e.size()); out.write(e.toByteArray())
        }
        java.io.FileOutputStream(f, true).use { it.write(out.toByteArray()) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet. Connect once to download the voice."
        is java.net.SocketTimeoutException -> "The download timed out. Try again."
        else -> e.message ?: e.javaClass.simpleName
    }
}

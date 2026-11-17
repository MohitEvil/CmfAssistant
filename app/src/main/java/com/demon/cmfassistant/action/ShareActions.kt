package com.demon.cmfassistant.action

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.access.CmfAccess
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** "Send it to Demon on WhatsApp" after taking a picture: shares the newest photo and taps the person and Send. */
class ShareActions(
    context: Context,
    private val findApp: (String) -> Pair<String, String>?,
    private val launch: (Intent) -> Boolean,
    private val ui: UiControl,
    private val smart: SmartUi
) {
    private val app = context.applicationContext

    fun hasPhotoAccess(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    }

    /** The newest picture, ignoring screenshots when there's a camera shot. */
    private fun latestPhoto(): Uri? = runCatching {
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val args = android.os.Bundle().apply {
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 12)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        app.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH), args, null)?.use { c ->
            var first: Uri? = null
            var camera: Uri? = null
            while (c.moveToNext() && camera == null) {
                val uri = ContentUris.withAppendedId(col, c.getLong(0))
                if (first == null) first = uri
                val path = c.getString(1).orEmpty()
                if (path.contains("DCIM", true) || path.contains("Camera", true)) camera = uri
            }
            camera ?: first
        }
    }.getOrNull()

    private fun defaultApp(): Pair<String, String>? =
        listOf("whatsapp", "telegram", "messages").firstNotNullOfOrNull { findApp(it) }

    suspend fun run(c: Command.SharePhoto): ActionResult = withContext(Dispatchers.Default) {
        if (!hasPhotoAccess()) return@withContext ActionResult.Failure("Allow Photos access so I can send your latest picture.")
        val uri = latestPhoto() ?: return@withContext ActionResult.Failure("I can't find a recent photo.")
        val target = (c.app?.let { findApp(it) } ?: if (c.app != null) null else defaultApp())
            ?: return@withContext ActionResult.Failure(if (c.app != null) "I couldn't find an app called ${c.app}." else "Tell me which app to send it through.")
        val svc = CmfAccess.instance
        val before = ui.frontPackage()
        val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
            .setPackage(target.first).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("photo", uri)
        if (!launch(send)) return@withContext ActionResult.Failure("${target.second} can't take a picture that way.")
        if (svc == null) return@withContext ActionResult.Success("${target.second} is open with the photo. Pick ${c.contact} and send. Turn on screen control and I'll do that too.")
        ui.settleAfterLaunch(before)
        delay(600)
        // Pick the person.
        var r = smart.run(Command.UiSmart(c.contact))
        if (r is ActionResult.Confirm && r.command is Command.UiX) r = smart.run(r.command as Command.UiX)
        if (r !is ActionResult.Success) {
            // Some share lists hide people behind a search icon.
            svc.waitFind(listOf("Search"), 800)?.let { svc.click(it); delay(350); svc.findEditable()?.let { f -> svc.click(f); ui.type(c.contact); delay(700) } }
            r = smart.run(Command.UiSmart(c.contact))
            if (r is ActionResult.Confirm && r.command is Command.UiX) r = smart.run(r.command as Command.UiX)
        }
        if (r !is ActionResult.Success) return@withContext ActionResult.Failure("I opened ${target.second} with the photo but couldn't find ${c.contact}. ${(r as? ActionResult.Failure)?.message.orEmpty()}")
        // Send: a confirm dialog and/or a preview screen, up to twice.
        var sent = false
        for (i in 0 until 2) {
            val b = svc.waitFind(listOf("Send", "Share", "Done", "OK"), 2200) ?: break
            if (svc.click(b)) { sent = true; delay(700) }
        }
        AppLog.d("share", "sent=$sent to ${c.contact} via ${target.first}")
        if (sent) ActionResult.Success("Sent the photo to ${c.contact} on ${target.second}.")
        else ActionResult.Failure("I picked ${c.contact} but couldn't find Send. Tap it, or tell me what to tap.")
    }
}

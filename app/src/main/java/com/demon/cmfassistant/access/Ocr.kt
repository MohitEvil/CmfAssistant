package com.demon.cmfassistant.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One line of text found in a screenshot, with where it is on screen. */
class OcrLine(val text: String, val box: Rect)

/**
 * Reads what is actually drawn on screen by taking a screenshot through the accessibility service and running Google's
 * on-device text recogniser over it. Nothing leaves the phone and the screenshot is dropped right after.
 */
object Ocr {
    suspend fun read(svc: AccessibilityService): List<OcrLine>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(6000) {
            val bmp = screenshot(svc) ?: return@withTimeoutOrNull null
            recognise(bmp)
        }
    }

    private suspend fun screenshot(svc: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val out = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        copy
                    }.getOrNull()
                    runCatching { result.hardwareBuffer.close() }
                    if (cont.isActive) cont.resume(out)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun recognise(bmp: Bitmap): List<OcrLine>? = suspendCancellableCoroutine { cont ->
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (b in text.textBlocks) for (l in b.lines) {
                    val box = l.boundingBox ?: continue
                    if (l.text.isNotBlank()) lines.add(OcrLine(l.text.trim(), box))
                }
                runCatching { bmp.recycle() }
                client.close()
                if (cont.isActive) cont.resume(lines.sortedBy { it.box.top })
            }
            .addOnFailureListener {
                runCatching { bmp.recycle() }
                client.close()
                if (cont.isActive) cont.resume(null)
            }
    }
}

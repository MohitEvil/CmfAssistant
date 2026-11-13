package com.demon.cmfassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.CmfApp
import com.demon.cmfassistant.MainActivity
import com.demon.cmfassistant.PanelActivity
import com.demon.cmfassistant.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draggable floating bubble. It is a static, non-animated view (zero idle cost); tapping it opens
 * the assistant and starts listening. Needs the "display over other apps" permission.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val CHANNEL_ID = "cmf_bubble"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating bubble", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cmf)
            .setContentTitle("CMF Assistant bubble is on")
            .setContentText("Tap the bubble to talk. Turn it off in Settings.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun addBubble() {
        val settings = (application as CmfApp).settings
        val manager = getSystemService(WindowManager::class.java)
        val size = (56 * resources.displayMetrics.density).toInt()
        val metrics = resources.displayMetrics

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleX >= 0) settings.bubbleX else metrics.widthPixels - size - 16
            y = if (settings.bubbleY >= 0) settings.bubbleY else metrics.heightPixels / 3
        }

        val view = BubbleView(this)
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false
                        v.isPressed = true
                        v.invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt().coerceIn(0, metrics.widthPixels - size)
                            params.y = (startY + dy).toInt().coerceIn(0, metrics.heightPixels - size)
                            manager.updateViewLayout(v, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        v.invalidate()
                        if (moved) {
                            // Snap to the nearest horizontal edge and remember the spot.
                            params.x = if (params.x + size / 2 < metrics.widthPixels / 2) 8 else metrics.widthPixels - size - 8
                            manager.updateViewLayout(v, params)
                            settings.bubbleX = params.x
                            settings.bubbleY = params.y
                        } else {
                            openAssistant()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        v.invalidate()
                    }
                }
                return true
            }
        })

        manager.addView(view, params)
        wm = manager
        bubble = view
    }

    private fun openAssistant() {
        // Tapping the bubble while the panel is showing closes it and stops listening; tapping again reopens it.
        if (PanelActivity.closeIfOpen()) return
        startActivity(
            Intent(this, PanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_AUTO_START, true)
        )
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    /** Black disc with a white dot ring and a red top dot, matching the glyph. */
    private class BubbleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = Paint.Style.STROKE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5232B.toInt() }
        private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f
            val d = resources.displayMetrics.density
            rim.strokeWidth = 1.5f * d
            canvas.drawCircle(cx, cy, r - rim.strokeWidth, fill)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth, rim)

            val ring = r * 0.56f
            val n = 12
            for (i in 0 until n) {
                val a = (2 * Math.PI * i / n - Math.PI / 2).toFloat()
                val p = if (i == 0) red else dot
                canvas.drawCircle(cx + cos(a) * ring, cy + sin(a) * ring, if (i == 0) 2.8f * d else 1.8f * d, p)
            }
            canvas.drawCircle(cx, cy, 3f * d, dot)
            if (isPressed) canvas.drawCircle(cx, cy, r - rim.strokeWidth, press)
        }
    }
}

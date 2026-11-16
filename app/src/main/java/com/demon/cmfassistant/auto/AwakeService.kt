package com.demon.cmfassistant.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demon.cmfassistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the screen on for a set time, like a "stay awake" macro. */
@Suppress("DEPRECATION")
class AwakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock? = null
    private var endAt = 0L

    private val poke = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= endAt) { stopSelf(); return }
            // A short wake-up now and then resets the screen timeout, even where the plain wake lock is ignored.
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "cmf:poke").apply { acquire(1500) }
            }
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val minutes = intent?.getIntExtra("minutes", 60) ?: 60
        endAt = System.currentTimeMillis() + minutes * 60_000L
        startAsForeground()
        lock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "cmf:awake").apply { acquire(minutes * 60_000L + 5000) }
        handler.removeCallbacks(poke)
        handler.post(poke)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Keep screen awake", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, AwakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val until = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(endAt))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cmf)
            .setContentTitle("Keeping the screen awake")
            .setContentText("Until $until")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF, n)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poke)
        lock?.let { if (it.isHeld) it.release() }
        active = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cmf_awake"
        private const val NOTIF = 4242
        const val ACTION_STOP = "com.demon.cmfassistant.STOP_AWAKE"
        @Volatile var active = false

        fun start(c: Context, minutes: Int) {
            active = true
            ContextCompat.startForegroundService(c, Intent(c, AwakeService::class.java).putExtra("minutes", minutes))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AwakeService::class.java))
            active = false
        }
    }
}

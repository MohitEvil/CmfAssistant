package com.demon.cmfassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.demon.cmfassistant.data.Settings

/** Restores the floating bubble after a reboot if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.bubbleEnabled(context) && android.provider.Settings.canDrawOverlays(context)) {
            runCatching { OverlayService.start(context) }
        }
    }
}

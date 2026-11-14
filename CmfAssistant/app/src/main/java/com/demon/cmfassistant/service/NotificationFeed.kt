package com.demon.cmfassistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Optional: once the user grants Notification access, the assistant can read or clear notifications. */
class NotificationFeed : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: NotificationFeed? = null

        /** "App: title. text" lines for the notifications that are showing, newest first. */
        fun readable(limit: Int = 6): List<String>? {
            val svc = instance ?: return null
            val list: Array<StatusBarNotification> = runCatching { svc.activeNotifications }.getOrNull() ?: return emptyList()
            return list.filter { it.isClearable }
                .sortedByDescending { it.postTime }
                .take(limit)
                .mapNotNull { sbn ->
                    val e = sbn.notification.extras
                    val title = e.getCharSequence("android.title")?.toString().orEmpty()
                    val text = e.getCharSequence("android.text")?.toString().orEmpty()
                    val app = runCatching {
                        val pm = svc.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                    }.getOrDefault(sbn.packageName)
                    val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")
                    if (body.isBlank()) null else "$app: $body"
                }
        }

        fun clearAll(): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.cancelAllNotifications() }.isSuccess
        }
    }
}

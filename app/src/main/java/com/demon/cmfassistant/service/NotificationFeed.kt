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

    /** One showing notification, flattened. [index] 0 = newest. */
    class Note(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val sbn: StatusBarNotification,
        val canReply: Boolean
    ) {
        /** "Demon says: hi" / "Telegram: title. text" */
        fun spoken(): String = when {
            title.isNotBlank() && text.isNotBlank() -> "$app, $title: $text"
            title.isNotBlank() -> "$app: $title"
            else -> "$app: $text"
        }
    }

    companion object {
        @Volatile
        var instance: NotificationFeed? = null

        private fun pretty(s: CharSequence?): String = s?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

        /** Everything showing that is a real alert (no ongoing, no group headers, nothing of ours), newest first. Null = no access. */
        fun notes(): List<Note>? {
            val svc = instance ?: return null
            val all: Array<StatusBarNotification> = runCatching { svc.activeNotifications }.getOrNull() ?: return emptyList()
            val pm = svc.packageManager
            return all.asSequence()
                .filter { it.packageName != svc.packageName && !it.isOngoing }
                .filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn ->
                    val e = sbn.notification.extras
                    var title = pretty(e.getCharSequence("android.title"))
                    var text = pretty(e.getCharSequence("android.bigText")).ifBlank { pretty(e.getCharSequence("android.text")) }
                    // Chat apps: the newest message sits in the messages list.
                    runCatching {
                        val msgs = e.getParcelableArray("android.messages")
                        val last = msgs?.lastOrNull() as? android.os.Bundle
                        val m = pretty(last?.getCharSequence("text"))
                        if (m.isNotBlank()) text = m
                        val who = pretty(last?.getCharSequence("sender"))
                        if (who.isNotBlank() && title.isBlank()) title = who
                    }
                    if (title.isBlank() && text.isBlank()) return@mapNotNull null
                    val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    val reply = sbn.notification.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
                    Note(sbn.key, sbn.packageName, app, title, text.take(300), sbn.postTime, sbn, reply)
                }
                .distinctBy { it.pkg + "|" + it.title + "|" + it.text }
                .toList()
        }

        /** Opens the notification the way tapping it would. */
        fun open(n: Note): Boolean {
            val pi = n.sbn.notification.contentIntent
            val ok = pi != null && runCatching { pi.send(); true }.getOrDefault(false)
            if (ok) return true
            val svc = instance ?: return false
            val launch = svc.packageManager.getLaunchIntentForPackage(n.pkg) ?: return false
            return runCatching { launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); svc.startActivity(launch); true }.getOrDefault(false)
        }

        /** Sends [text] through the notification's own inline reply box. */
        fun reply(n: Note, text: String): Boolean {
            val svc = instance ?: return false
            val action = n.sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = android.content.Intent().addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            val bundle = android.os.Bundle()
            for (ri in inputs) bundle.putCharSequence(ri.resultKey, text)
            android.app.RemoteInput.addResultsToIntent(inputs, intent, bundle)
            return runCatching { action.actionIntent.send(svc, 0, intent); true }.getOrDefault(false)
        }

        fun cancel(n: Note): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.cancelNotification(n.key) }.isSuccess
        }

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

package com.demon.cmfassistant.service

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Optional: once the user grants Notification access, the assistant can read, reply to, clear and look back at notifications. */
class NotificationFeed : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        runCatching { build(this, s)?.let { record(this, it) } }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** One showing notification, flattened. */
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
        /** A real person (or group) rather than the app's own headline. */
        val fromPerson: Boolean get() = canReply && title.isNotBlank() && !title.equals(app, true)

        fun spoken(): String = when {
            title.isNotBlank() && text.isNotBlank() -> "$app, $title: $text"
            title.isNotBlank() -> "$app: $title"
            else -> "$app: $text"
        }
    }

    /** One line of the assistant's own notification history (kept on the phone, newest last). */
    class Hist(val pkg: String, val app: String, val title: String, val text: String, val time: Long)

    companion object {
        @Volatile
        var instance: NotificationFeed? = null

        private const val PREFS = "cmf_notif_history"
        private const val CAP = 200
        private val lock = Any()

        private fun pretty(s: CharSequence?): String = s?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

        private fun build(svc: NotificationFeed, sbn: StatusBarNotification): Note? {
            if (sbn.packageName == svc.packageName || sbn.isOngoing) return null
            if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return null
            val e = sbn.notification.extras
            var title = pretty(e.getCharSequence("android.title"))
            var text = pretty(e.getCharSequence("android.bigText")).ifBlank { pretty(e.getCharSequence("android.text")) }
            runCatching {
                val msgs = e.getParcelableArray("android.messages")
                val last = msgs?.lastOrNull() as? android.os.Bundle
                val m = pretty(last?.getCharSequence("text"))
                if (m.isNotBlank()) text = m
                val who = pretty(last?.getCharSequence("sender"))
                if (who.isNotBlank() && title.isBlank()) title = who
            }
            if (title.isBlank() && text.isBlank()) return null
            val pm = svc.packageManager
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val reply = sbn.notification.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
            return Note(sbn.key, sbn.packageName, app, title, text.take(300), sbn.postTime, sbn, reply)
        }

        /** Everything showing that is a real alert, newest first. Null = no access. */
        fun notes(): List<Note>? {
            val svc = instance ?: return null
            val all: Array<StatusBarNotification> = runCatching { svc.activeNotifications }.getOrNull() ?: return emptyList()
            return all.sortedByDescending { it.postTime }
                .mapNotNull { runCatching { build(svc, it) }.getOrNull() }
                .distinctBy { it.pkg + "|" + it.title + "|" + it.text }
        }

        // ---- history ----

        private fun record(ctx: Context, n: Note) {
            synchronized(lock) {
                val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = runCatching { JSONArray(sp.getString("log", "[]")) }.getOrDefault(JSONArray())
                val last = arr.optJSONObject(arr.length() - 1)
                if (last != null && last.optString("pkg") == n.pkg && last.optString("title") == n.title && last.optString("text") == n.text) return
                arr.put(JSONObject().put("pkg", n.pkg).put("app", n.app).put("title", n.title).put("text", n.text).put("t", n.time))
                val out = JSONArray()
                for (i in maxOf(0, arr.length() - CAP) until arr.length()) out.put(arr.get(i))
                sp.edit().putString("log", out.toString()).apply()
            }
        }

        /** Notifications seen since access was granted, newest first (including ones already dismissed). */
        fun history(ctx: Context): List<Hist> = synchronized(lock) {
            val arr = runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]")) }.getOrDefault(JSONArray())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { Hist(it.optString("pkg"), it.optString("app"), it.optString("title"), it.optString("text"), it.optLong("t")) }
                .reversed()
        }

        // ---- acting ----

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
        fun readable(limit: Int = 6): List<String>? = notes()?.take(limit)?.map { it.spoken() }

        fun clearAll(): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.cancelAllNotifications() }.isSuccess
        }
    }
}

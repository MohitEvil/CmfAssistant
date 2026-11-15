package com.demon.cmfassistant.action

import android.content.Context
import android.content.Intent
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.NotifOp
import com.demon.cmfassistant.intent.NotifyState
import com.demon.cmfassistant.service.NotificationFeed
import com.demon.cmfassistant.service.NotificationFeed.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reading, opening, replying to, clearing and looking back at notifications (needs Notification access). */
class NotifyActions(context: Context) {
    private val app = context.applicationContext

    private fun needAccess() = ActionResult.Success(
        "I need Notification access for that. Opening it now. Allow CMF Assistant, then ask again."
    ) {
        runCatching { app.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun appMatch(q: String, name: String, pkg: String) =
        Targeting.score(q, name) >= 66 || name.contains(q, true) || pkg.contains(q.replace(" ", ""), true)

    /** Notes whose app or sender matches [who] ("telegram", "demon", "you tube"). */
    private fun matching(all: List<Note>, who: String): List<Note> {
        if (who.isBlank()) return all
        val q = who.trim()
        val byApp = all.filter { appMatch(q, it.app, it.pkg) }
        if (byApp.isNotEmpty()) return byApp
        return all.filter { maxOf(Targeting.score(q, it.title.substringBefore(":")), Targeting.score(q, it.title)) >= 66 || it.title.contains(q, true) }
    }

    private fun nth(all: List<Note>, n: Int?): Note? {
        if (n == null) return all.firstOrNull()
        val i = if (n < 0) all.size + n else n - 1
        return all.getOrNull(i)
    }

    private fun short(s: String, max: Int = 140) = if (s.length > max) s.take(max).trimEnd() + "…" else s

    private fun find(all: List<Note>, c: Command.Notif): Note? =
        if (c.who.startsWith("@")) all.firstOrNull { it.key == c.who.drop(1) }
        else (if (c.who.isNotBlank()) nth(matching(all, c.who), c.nth) else nth(all, c.nth))

    private fun line(n: Note): String =
        if (n.title.isNotBlank() && !n.title.equals(n.app, true) && n.text.isNotBlank()) "${n.title} on ${n.app} says: ${short(n.text, 220)}." else "${short(n.spoken(), 220)}."

    fun run(c: Command.Notif): ActionResult {
        if (c.op == NotifOp.DROP) { NotifyState.clear(); return ActionResult.Success("Okay.") }
        if (c.op == NotifOp.HISTORY) return history(c.who)
        val all = NotificationFeed.notes() ?: return needAccess()
        return when (c.op) {
            NotifOp.SUMMARY -> {
                if (all.isEmpty()) return ActionResult.Success("You have no notifications.")
                val groups = all.groupBy { it.app }.entries.sortedByDescending { it.value.size }
                val shown = groups.take(8)
                val parts = shown.joinToString(", ") { "${it.value.size} from ${it.key}" }
                val rest = if (groups.size > shown.size) " and others" else ""
                NotifyState.remember("")
                ActionResult.Success("You have ${all.size} ${if (all.size == 1) "notification" else "notifications"}: $parts$rest. Ask me about any app, like what's new from ${shown.first().key}.")
            }
            NotifOp.READ -> {
                val m = matching(all, c.who)
                if (m.isEmpty()) return ActionResult.Success("I don't see a notification from ${c.who}.")
                NotifyState.remember(c.who)
                val shown = m.take(5)
                val text = shown.joinToString(" ") { line(it) }
                // A real person we can answer: offer to reply.
                val person = shown.firstOrNull { it.fromPerson }
                if (person != null) {
                    NotifyState.ask(person.key, person.title)
                    ActionResult.Success("$text Do you want to reply to ${person.title}?")
                } else ActionResult.Success(text)
            }
            NotifOp.WHO -> {
                val m = matching(all, c.who)
                if (m.isEmpty()) return ActionResult.Success("I don't see a notification from ${c.who}.")
                NotifyState.remember(c.who)
                val people = m.filter { it.title.isNotBlank() && !it.title.equals(it.app, true) }.groupBy { it.title }
                if (people.isEmpty()) return ActionResult.Success("Those are ${m.size} alerts from the app itself, not from people.")
                val list = people.entries.sortedByDescending { it.value.size }.joinToString(", ") { "${it.key}${if (it.value.size > 1) ", ${it.value.size} messages" else ""}" }
                ActionResult.Success("On ${m.first().app}: $list. Say what did they say, or reply.")
            }
            NotifOp.FROM -> {
                val m = matching(all, c.who)
                NotifyState.remember(c.who)
                if (m.isEmpty()) ActionResult.Success("No, nothing from ${c.who}.")
                else ActionResult.Success("Yes, ${m.size} from ${c.who}. Say read them, or who sent them.")
            }
            NotifOp.READ_NTH -> {
                val n = nth(all, c.nth) ?: return ActionResult.Failure("I don't see that notification.")
                if (n.fromPerson) NotifyState.ask(n.key, n.title)
                ActionResult.Success(short(n.spoken(), 300) + "." + if (n.fromPerson) " Want to reply to ${n.title}?" else "")
            }
            NotifOp.OPEN -> {
                val n = find(all, c) ?: return ActionResult.Failure("I don't see that notification.")
                if (NotificationFeed.open(n)) ActionResult.Success("Opened ${n.title.ifBlank { n.app }}.") else ActionResult.Failure("I couldn't open it.")
            }
            NotifOp.REPLY_ASK -> {
                if (NotifyState.stage == 0) return ActionResult.Failure("There's nothing to reply to right now.")
                NotifyState.awaitText()
                ActionResult.Success("What should I say to ${NotifyState.name}?")
            }
            NotifOp.REPLY -> {
                val n = find(all, c) ?: run { NotifyState.clear(); return ActionResult.Failure("That notification is gone, so I can't reply to it.") }
                if (c.text.isBlank()) {
                    NotifyState.ask(n.key, n.title.ifBlank { n.app })
                    NotifyState.awaitText()
                    ActionResult.Success("What should I say to ${n.title.ifBlank { n.app }}?")
                } else if (n.canReply && NotificationFeed.reply(n, c.text)) {
                    NotifyState.clear()
                    ActionResult.Success("Sent to ${n.title.ifBlank { n.app }}: ${short(c.text, 60)}.")
                } else {
                    NotifyState.clear()
                    ActionResult.Failure("${n.app} doesn't let me reply from the notification. Say open it, then tell me what to type.")
                }
            }
            NotifOp.NEEDS_REPLY -> {
                val chat = all.filter { it.fromPerson }
                if (chat.isEmpty()) return ActionResult.Success("Nothing that looks like it needs a reply.")
                val asked = chat.filter { it.text.contains('?') }
                val list = (asked + chat.filter { it !in asked }).take(4)
                list.firstOrNull()?.let { NotifyState.ask(it.key, it.title) }
                ActionResult.Success(
                    (if (asked.isNotEmpty()) "These look like they want an answer: " else "You have messages waiting: ") +
                        list.joinToString(" ") { "${it.title} on ${it.app}: ${short(it.text, 100)}." } + " Reply to ${list.first().title}?"
                )
            }
            NotifOp.CLEAR -> {
                val m = if (c.who.isNotBlank()) matching(all, c.who) else listOfNotNull(nth(all, c.nth))
                if (m.isEmpty()) ActionResult.Success("There's nothing from ${c.who.ifBlank { "there" }} to clear.")
                else { m.forEach { NotificationFeed.cancel(it) }; ActionResult.Success("Cleared ${m.size} ${if (m.size == 1) "notification" else "notifications"}${if (c.who.isNotBlank()) " from ${c.who}" else ""}.") }
            }
            NotifOp.CLEAR_ALL -> if (NotificationFeed.clearAll()) ActionResult.Success("Cleared your notifications.") else ActionResult.Failure("I couldn't clear them.")
            NotifOp.HISTORY, NotifOp.DROP -> ActionResult.Failure("Not now.")
        }
    }

    private fun history(who: String): ActionResult {
        if (NotificationFeed.instance == null) return needAccess()
        var h = NotificationFeed.history(app)
        if (who.isNotBlank()) h = h.filter { appMatch(who, it.app, it.pkg) || it.title.contains(who, true) }
        if (h.isEmpty()) return ActionResult.Success(if (who.isBlank()) "I haven't logged any notifications yet. I start remembering from when Notification access was granted." else "I have nothing from $who in my notification history.")
        val f = SimpleDateFormat("h:mm a", Locale.getDefault())
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        val today = day.format(Date())
        val groups = h.groupBy { it.app }.entries.sortedByDescending { it.value.size }.take(6)
        val counts = groups.joinToString(", ") { "${it.value.size} from ${it.key}" }
        val recent = h.take(4).joinToString(" ") { n ->
            val whenText = if (day.format(Date(n.time)) == today) f.format(Date(n.time)) else day.format(Date(n.time))
            "At $whenText, ${n.app}${if (n.title.isNotBlank()) ", ${n.title}" else ""}: ${short(n.text, 90)}."
        }
        return ActionResult.Success("In your notification history I have ${h.size}: $counts. Latest: $recent")
    }
}

package com.demon.cmfassistant.action

import android.content.Context
import android.content.Intent
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.NotifOp
import com.demon.cmfassistant.service.NotificationFeed
import com.demon.cmfassistant.service.NotificationFeed.Note

/** Reading, opening, replying to and clearing notifications (needs Notification access). */
class NotifyActions(context: Context) {
    private val app = context.applicationContext

    private fun needAccess() = ActionResult.Success(
        "I need Notification access for that. Opening it now. Allow CMF Assistant, then ask again."
    ) {
        runCatching { app.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Notes whose app or sender matches [who] ("telegram", "demon", "you tube"). */
    private fun matching(all: List<Note>, who: String): List<Note> {
        if (who.isBlank()) return all
        val q = who.trim()
        return all.filter { n ->
            maxOf(Targeting.score(q, n.app), Targeting.score(q, n.title.substringBefore(":")), Targeting.score(q, n.title)) >= 66 ||
                n.title.contains(q, true) || n.app.contains(q, true) || n.pkg.contains(q.replace(" ", ""), true)
        }
    }

    private fun nth(all: List<Note>, n: Int?): Note? {
        if (n == null) return all.firstOrNull()
        val i = if (n < 0) all.size + n else n - 1
        return all.getOrNull(i)
    }

    private fun short(s: String, max: Int = 140) = if (s.length > max) s.take(max).trimEnd() + "…" else s

    fun run(c: Command.Notif): ActionResult {
        val all = NotificationFeed.notes() ?: return needAccess()
        return when (c.op) {
            NotifOp.SUMMARY -> {
                if (all.isEmpty()) return ActionResult.Success("You have no notifications.")
                val shown = all.take(8)
                val lead = "You have ${all.size} ${if (all.size == 1) "notification" else "notifications"}. "
                val rest = if (all.size > shown.size) " And ${all.size - shown.size} more." else ""
                ActionResult.Success(lead + shown.mapIndexed { i, n -> "${i + 1}. ${short(n.spoken())}." }.joinToString(" ") + rest)
            }
            NotifOp.READ -> {
                val m = matching(all, c.who)
                if (m.isEmpty()) ActionResult.Success("I don't see a notification from ${c.who}.")
                else ActionResult.Success(m.take(5).joinToString(" ") { n ->
                    if (n.title.isNotBlank() && !n.title.equals(n.app, true) && n.text.isNotBlank()) "${n.title} on ${n.app} says: ${short(n.text, 220)}." else "${short(n.spoken(), 220)}."
                })
            }
            NotifOp.FROM -> {
                val m = matching(all, c.who)
                if (m.isEmpty()) ActionResult.Success("No, nothing from ${c.who}.")
                else ActionResult.Success("Yes, ${m.size} from ${c.who}. " + m.take(3).joinToString(" ") { "${short(it.spoken(), 110)}." })
            }
            NotifOp.READ_NTH -> {
                val n = nth(all, c.nth) ?: return ActionResult.Failure("I don't see that notification.")
                ActionResult.Success(short(n.spoken(), 300) + ".")
            }
            NotifOp.OPEN -> {
                val n = (if (c.who.isNotBlank()) matching(all, c.who).let { nth(it, c.nth) } else nth(all, c.nth))
                    ?: return ActionResult.Failure("I don't see that notification.")
                if (NotificationFeed.open(n)) ActionResult.Success("Opened ${n.title.ifBlank { n.app }}.") else ActionResult.Failure("I couldn't open it.")
            }
            NotifOp.REPLY -> {
                val n = (if (c.who.isNotBlank()) matching(all, c.who).let { nth(it, c.nth) } else nth(all, c.nth))
                    ?: return ActionResult.Failure("I don't see that notification.")
                if (c.text.isBlank()) {
                    if (NotificationFeed.open(n)) ActionResult.Success("Opened ${n.title.ifBlank { n.app }}. Say what to type and I'll write it.")
                    else ActionResult.Failure("I couldn't open it.")
                } else if (n.canReply && NotificationFeed.reply(n, c.text)) {
                    ActionResult.Success("Replied to ${n.title.ifBlank { n.app }}: ${short(c.text, 60)}.")
                } else {
                    ActionResult.Failure("${n.app} doesn't let me reply from the notification. Say open it, then tell me what to type.")
                }
            }
            NotifOp.NEEDS_REPLY -> {
                val chat = all.filter { it.canReply }
                if (chat.isEmpty()) return ActionResult.Success("Nothing that looks like it needs a reply.")
                val asked = chat.filter { it.text.contains('?') }
                val list = (asked + chat.filter { it !in asked }).take(4)
                ActionResult.Success(
                    (if (asked.isNotEmpty()) "These look like they want an answer: " else "You have messages waiting: ") +
                        list.joinToString(" ") { "${it.title.ifBlank { it.app }} on ${it.app}: ${short(it.text, 100)}." }
                )
            }
            NotifOp.CLEAR -> {
                val m = if (c.who.isNotBlank()) matching(all, c.who) else listOfNotNull(nth(all, c.nth))
                if (m.isEmpty()) ActionResult.Success("There's nothing from ${c.who.ifBlank { "there" }} to clear.")
                else { m.forEach { NotificationFeed.cancel(it) }; ActionResult.Success("Cleared ${m.size} ${if (m.size == 1) "notification" else "notifications"}${if (c.who.isNotBlank()) " from ${c.who}" else ""}.") }
            }
            NotifOp.CLEAR_ALL -> if (NotificationFeed.clearAll()) ActionResult.Success("Cleared your notifications.") else ActionResult.Failure("I couldn't clear them.")
        }
    }
}

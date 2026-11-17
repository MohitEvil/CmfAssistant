package com.demon.cmfassistant.agent

import com.demon.cmfassistant.access.CmfAccess
import com.demon.cmfassistant.action.ActionResult
import com.demon.cmfassistant.ai.Brain
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.GlobalKey
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Multi-step jobs across apps: order food, book a ride, reserve a table, or anything else by reading the screen
 * and choosing the next tap. It always stops before paying or confirming a booking, and asks first.
 */
class ScreenAgent(
    private val exec: suspend (Command) -> ActionResult,
    private val brain: () -> Brain?
) {
    private val payWords = Regex("\\b(pay|payment|place (?:your )?order|confirm (?:order|booking|ride|payment|purchase)|book (?:now|ride|cab|table)|request (?:ride|cab)|buy(?: now)?|checkout|check out|proceed to pay|order now|purchase|subscribe|send money|transfer)\\b", RegexOption.IGNORE_CASE)

    private val foodApps = listOf("swiggy", "zomato", "blinkit", "zepto", "instamart", "bigbasket", "dominos", "mcdonald's", "kfc")
    private val rideApps = listOf("uber", "ola", "rapido", "namma yatri", "inDrive")
    private val tableApps = listOf("zomato", "district", "dineout", "eazydiner", "swiggy dineout")

    private suspend fun openFirst(apps: List<String>): String? {
        for (a in apps) if (exec(Command.OpenApp(a)) is ActionResult.Success) return a
        return null
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    suspend fun run(goal: String): ActionResult {
        val g = goal.trim().trimEnd('.', '!')
        // Ordering food
        Regex("^(?:order|get|buy|i want|i'd like|i would like)\\s+(.+?)(?:\\s+(?:from|on|using|via|in|with)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val item = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(foodApps) ?: return ActionResult.Failure("I couldn't find a food app like Swiggy or Zomato.")
            delay(1500)
            exec(Command.UiSearch(item, pick = true, first = true))
            return ActionResult.Success("I searched $item in ${cap(app)} and opened the first match. Pick what you like and add it. I'll never pay without asking you.")
        }
        // Booking a ride
        Regex("^(?:book|get|call|order|find)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:ride|cab|taxi|auto|auto rickshaw|bike taxi|uber|ola|rapido)\\s*(?:to|for)\\s+(.+?)(?:\\s+(?:with|on|using|via|in)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val dest = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(rideApps) ?: return ActionResult.Failure("I couldn't find Uber, Ola or Rapido on this phone.")
            delay(2000)
            exec(Command.UiSearch(dest, pick = true, first = true))
            return ActionResult.Success("I put $dest in ${cap(app)} as the destination. Choose the ride type and I'll stop before you confirm and pay.")
        }
        // Table reservation
        Regex("^(?:book|reserve|make a reservation|find)\\s+(?:me\\s+)?(?:a\\s+)?(?:table|reservation|seat)s?\\s*(?:for\\s+\\d+\\s*(?:people|persons|guests)?\\s*)?(?:at|in)\\s+(.+?)(?:\\s+(?:with|on|using|via)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val place = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(tableApps) ?: return ActionResult.Failure("I couldn't find a dining app like Zomato or District.")
            delay(1500)
            exec(Command.UiSearch(place, pick = true, first = true))
            return ActionResult.Success("Opened $place in ${cap(app)}. Choose the time and guests and I'll stop before you confirm.")
        }
        return openEnded(g)
    }

    /** Anything else: the user's AI looks at the screen, picks one action at a time, and I carry it out. */
    private suspend fun openEnded(goal: String): ActionResult {
        val b = brain()
        if (b == null || !b.configured()) return ActionResult.Failure("For open-ended jobs I need an AI provider set up in AI settings. I can already order food, book rides and tables, and chain simple steps.")
        val svc0 = CmfAccess.instance ?: return ActionResult.Failure("I need Accessibility access to work through other apps. Turn it on in Access settings.")
        val log = ArrayList<String>()
        for (step in 1..14) {
            val svc = CmfAccess.instance ?: svc0
            val seen = svc.candidates().map { it.label.trim() }.filter { it.isNotEmpty() }.distinct().take(45)
            val prompt = "GOAL: $goal\nDONE SO FAR: ${if (log.isEmpty()) "nothing" else log.joinToString("; ")}\nSCREEN: ${svc.topTitle()}\nVISIBLE: ${seen.joinToString(" | ")}\n" +
                "Pick ONE next action as JSON only: {\"do\":\"open|tap|type|scroll|back|done|ask\",\"target\":\"label or app name\",\"text\":\"text to type\",\"say\":\"short sentence for the user\"}. " +
                "Use done when the goal is finished or only a payment/confirmation remains. Use ask if you need the user's choice."
            val reply = b.ask(emptyList(), prompt, "You operate an Android phone by reading its screen. Answer with one JSON object only.").getOrNull()
                ?: return ActionResult.Failure("The AI didn't answer, so I stopped. " + progress(log))
            val o = runCatching { JSONObject(reply.substringAfter('{').let { "{" + it.substringBeforeLast('}') + "}" }) }.getOrNull()
                ?: return ActionResult.Failure("I couldn't understand the AI's plan, so I stopped. " + progress(log))
            val act = o.optString("do").lowercase()
            val target = o.optString("target").trim()
            val say = o.optString("say").trim()
            AppLog.d("agent", "step $step: $act '$target'")
            when (act) {
                "done" -> return ActionResult.Success(say.ifBlank { "That's as far as I should go. " + progress(log) })
                "ask" -> return ActionResult.Success(say.ifBlank { "I need you to choose here." })
                "open" -> { val r = exec(Command.OpenApp(target)); if (r !is ActionResult.Success) return r; log += "opened $target"; delay(1800) }
                "tap" -> {
                    if (payWords.containsMatchIn(target)) {
                        return ActionResult.Confirm("Tap \"$target\"?", "This step may spend money or confirm something.", "I'm at \"$target\". Shall I tap it?", Command.UiTap(target), "Okay, I'll leave it.", declineAsFailure = false)
                    }
                    val r = exec(Command.UiTap(target))
                    if (r is ActionResult.Failure) { log += "couldn't tap $target"; if (log.count { it.startsWith("couldn't") } >= 3) return ActionResult.Failure("I got stuck on \"$target\". " + progress(log)) }
                    else log += "tapped $target"
                    delay(1100)
                }
                "type" -> { exec(Command.UiType(o.optString("text").ifBlank { target })); log += "typed"; delay(700) }
                "scroll" -> { exec(Command.UiScroll(true)); log += "scrolled"; delay(600) }
                "back" -> { exec(Command.GlobalAction(GlobalKey.BACK)); log += "went back"; delay(700) }
                else -> return ActionResult.Failure("I wasn't sure what to do next. " + progress(log))
            }
        }
        return ActionResult.Success("I worked on it for a while and stopped. " + progress(log))
    }

    private fun progress(log: List<String>) = if (log.isEmpty()) "" else "So far: " + log.takeLast(4).joinToString(", ") + "."
}

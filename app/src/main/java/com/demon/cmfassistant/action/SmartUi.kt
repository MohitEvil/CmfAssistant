package com.demon.cmfassistant.action

import android.content.Context
import android.graphics.Rect
import com.demon.cmfassistant.access.CmfAccess
import com.demon.cmfassistant.access.ScreenMemory
import com.demon.cmfassistant.debug.AppLog
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.ScreenAct
import com.demon.cmfassistant.intent.TapKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Careful screen control: finds the thing that was asked for (never "close enough" without asking), asks which one when
 * several match, understands "the second toggle", "the button in front of Telegram", "it", and remembers what it just saw.
 */
class SmartUi(private val ui: UiControl, context: Context) {
    private val app = context.applicationContext
    private val dm get() = app.resources.displayMetrics

    private sealed class Loc {
        class One(val c: CmfAccess.Cand) : Loc()
        class Many(val list: List<CmfAccess.Cand>) : Loc()
        class Weak(val label: String) : Loc()
        object None : Loc()
    }

    private class Anchor(val cand: CmfAccess.Cand?, val fail: ActionResult?)

    private val pronouns = setOf("it", "that", "this", "this one", "that one", "same", "same one", "them", "there")
    private val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth")

    suspend fun run(c: Command.UiX): ActionResult = withContext(Dispatchers.Default) {
        val svc = CmfAccess.instance ?: return@withContext ui.needAccess("work with the screen")
        ui.clearWay(svc)
        try {
            when (c) {
                is Command.UiSmart -> smart(svc, c)
                is Command.UiScrollEx -> scrollEx(svc, c)
                is Command.UiSwitch -> switch(svc, c)
                is Command.UiMisc -> misc(svc, c.op)
                Command.UiRepeat -> ActionResult.Failure("I don't have anything to repeat yet.")
            }
        } catch (e: Exception) {
            AppLog.e("smartui", e.message ?: "failed")
            ActionResult.Failure("That didn't work on this screen.")
        }
    }

    /** "Tap X" from the plain grammar: unusual cases (search box, shutter, first result) keep their special handling. */
    suspend fun tap(label: String, kind: TapKind): ActionResult {
        val key = CmfAccess.norm(label)
        val special = label == "@first" || key in ui.shutterWords || key == "search" || ui.isSearchLabel(label)
        if (special) return ui.tap(label, kind)
        val act = when (kind) { TapKind.TAP -> ScreenAct.TAP; TapKind.LONG -> ScreenAct.LONG; TapKind.DOUBLE -> ScreenAct.DOUBLE }
        val r = run(Command.UiSmart(label, act))
        // Nothing even close in the accessibility tree: let the pixel reader try.
        return if (r is ActionResult.Failure && r.message.startsWith("I can't see")) ui.tap(label, kind) else r
    }

    /** "Can you see X": answers, and remembers X so "click on it" works next. */
    suspend fun find(label: String): ActionResult {
        val l = label.lowercase()
        if (ui.isSearchLabel(l) || l.contains("search") || l.contains("text box") || l.contains("text field")) return ui.find(label)
        val r = run(Command.UiSmart(label, ScreenAct.ASK))
        return if (r is ActionResult.Failure && r.message.startsWith("I can't see")) ui.find(label) else r
    }

    // ---------- tapping ----------

    private suspend fun smart(svc: CmfAccess, c: Command.UiSmart): ActionResult {
        val w = dm.widthPixels
        val h = dm.heightPixels
        val target = c.target.trim()
        val pronoun = target.lowercase() in pronouns
        val mem = ScreenMemory.last()

        if (c.rel == null && target.isBlank() && ScreenMemory.hasOptions() && (c.nth != null || c.region != null)) return fromOptions(svc, c)

        // Relative: "the stop button in front of Telegram", "what's right after that"
        if (c.rel != null) {
            val anchor = resolveAnchor(svc, c.anchor ?: "it")
            anchor.fail?.let { return it }
            val a = anchor.cand!!
            val (name, kind) = Targeting.split(c.target)
            val all = svc.candidates()
            var pool = when {
                name.isNotBlank() -> all.filter { Targeting.score(name, it.label) >= Targeting.WEAK }
                kind == Targeting.Kind.SWITCH -> all.filter { it.checkable }
                kind == Targeting.Kind.FIELD -> all.filter { it.editable }
                kind == Targeting.Kind.CLICK -> all.filter { it.clickable }
                else -> all.filter { it.label.isNotBlank() }
            }.filter { !it.label.equals(a.label, true) || it.hit != a.hit }.filter { it.node != a.node }
            if (kind == Targeting.Kind.CLICK && name.isNotBlank()) pool.filter { it.clickable }.takeIf { it.isNotEmpty() }?.let { pool = it }
            val hit = Targeting.relative(a.hit, c.rel, pool)
                ?: return ActionResult.Failure("I can't see ${if (target.isBlank()) "anything" else "a $target"} ${relWords(c.rel)} “${a.label}”.")
            return act(svc, hit, c.act, relNote = "${relWords(c.rel)} “${a.label}”")
        }

        if (pronoun && mem == null) return ActionResult.Failure("I'm not sure which one you mean. Tell me what to tap.")
        val (name, kind) = if (pronoun) mem!!.label to Targeting.Kind.ANY else Targeting.split(target)
        if (name.isBlank() && kind == Targeting.Kind.ANY && c.nth == null && c.region == null) return ActionResult.Failure("Which one should I ${verb(c.act)}?")
        AppLog.d("ui", "smart '$name' kind=$kind nth=${c.nth} region=${c.region} act=${c.act}")
        return when (val loc = locate(svc, name, kind, c.nth, c.region, if (pronoun) mem!!.rect else null)) {
            is Loc.One -> act(svc, loc.c, c.act)
            is Loc.Many -> ask(svc, loc.list, c, name.ifBlank { target }, -1)
            is Loc.Weak ->
                if (c.act == ScreenAct.ASK) ActionResult.Success("I can't see “$name” exactly, but I can see “${loc.label}”. Say tap it if that's what you mean.")
                else ActionResult.Confirm(
                    title = "${verb(c.act).replaceFirstChar { it.uppercase() }} “${loc.label}”?",
                    detail = "I couldn't find “$name” exactly. The closest is “${loc.label}”.",
                    spoken = "I can't see “$name” exactly. Do you mean “${loc.label}”?",
                    command = Command.UiSmart(loc.label, c.act),
                    declineMessage = "Okay, I won't touch it.",
                    declineAsFailure = false
                )
            Loc.None -> ActionResult.Failure("I can't see “${name.ifBlank { target }}” on this screen.")
        }
    }

    private fun verb(a: ScreenAct) = when (a) { ScreenAct.TAP -> "tap"; ScreenAct.LONG -> "press and hold"; ScreenAct.DOUBLE -> "double tap"; ScreenAct.DELETE -> "delete"; ScreenAct.ASK -> "look at" }

    private fun relWords(rel: String) = when (rel) {
        "front" -> "in front of"; "before" -> "before"; "after" -> "right after"; "below" -> "below"; "above" -> "above"
        "left" -> "to the left of"; "right" -> "to the right of"; else -> "near"
    }

    private suspend fun resolveAnchor(svc: CmfAccess, anchor: String): Anchor {
        val a = anchor.trim()
        if (a.lowercase() in pronouns || a.isBlank()) {
            val m = ScreenMemory.last() ?: return Anchor(null, ActionResult.Failure("I'm not sure what you mean by that. Name it and I'll look."))
            val near = svc.candidates().filter { it.label.equals(m.label, true) }
                .minByOrNull { abs(it.hit.centerY() - m.rect.centerY()) + abs(it.hit.centerX() - m.rect.centerX()) }
            if (near != null) return Anchor(near, null)
            return when (val loc = locate(svc, m.label, Targeting.Kind.ANY, null, null, m.rect)) {
                is Loc.One -> Anchor(loc.c, null)
                else -> Anchor(null, ActionResult.Failure("I can't see “${m.label}” any more."))
            }
        }
        val (name, kind) = Targeting.split(a)
        return when (val loc = locate(svc, name, kind, null, null, null)) {
            is Loc.One -> Anchor(loc.c, null)
            is Loc.Many -> Anchor(null, ask(svc, loc.list, Command.UiSmart(a), a, -1))
            is Loc.Weak -> Anchor(null, ActionResult.Failure("I can't see “$a” exactly. Did you mean “${loc.label}”? Say it with that name."))
            Loc.None -> Anchor(null, ActionResult.Failure("I can't see “$a” on this screen."))
        }
    }

    /**
     * Finds the node. Only an exact name is accepted straight away; anything weaker makes it look through the whole list first
     * (scrolling both ways) and, if there is still no exact match, hands back the closest label so the user can confirm it.
     */
    private suspend fun locate(svc: CmfAccess, name: String, kind: Targeting.Kind, nth: Int?, region: String?, hint: Rect?): Loc {
        val w = dm.widthPixels
        val h = dm.heightPixels
        var bestLabel: String? = null
        var bestScore = 0

        fun choose(pool: List<CmfAccess.Cand>): Loc {
            val ordered = Targeting.reading(pool.distinctBy { it.hit.flattenToString() })
            if (nth != null) {
                val i = if (nth < 0) ordered.size + nth else nth - 1
                return ordered.getOrNull(i)?.let { Loc.One(it) } ?: Loc.None
            }
            if (region != null) return Targeting.pickRegion(ordered, region, w, h)?.let { Loc.One(it) } ?: Loc.None
            if (ordered.size == 1) return Loc.One(ordered[0])
            if (hint != null) return Loc.One(ordered.minByOrNull { abs(it.hit.centerY() - hint.centerY()) + abs(it.hit.centerX() - hint.centerX()) }!!)
            return Loc.Many(ordered)
        }

        fun look(): Loc? {
            val all = svc.candidates()
            if (name.isBlank()) {
                var pool = when (kind) {
                    Targeting.Kind.SWITCH -> all.filter { it.checkable }
                    Targeting.Kind.FIELD -> all.filter { it.editable }
                    else -> all.filter { it.clickable && !it.editable }
                }
                if (kind == Targeting.Kind.ANY) {
                    // "the third video / photo / item": real content, not toolbar icons, and not a wrapper around another tappable thing.
                    val big = pool.filter { it.hit.width() >= w * 0.2f && it.hit.height() >= h * 0.06f }
                    if (big.isNotEmpty()) pool = big.filter { a -> big.none { b -> b !== a && a.hit != b.hit && a.hit.contains(b.hit) } }
                }
                return if (pool.isEmpty()) null else choose(pool)
            }
            val scored = all.filter { it.label.isNotEmpty() }.map { it to Targeting.score(name, it.label) }
            val exact = scored.filter { it.second >= 96 }.map { it.first }
            if (exact.isNotEmpty()) return choose(exact)
            for ((cand, s) in scored) if (s > bestScore) { bestScore = s; bestLabel = cand.label }
            return null
        }

        look()?.let { return it }
        if (svc.canScroll()) {
            for (dir in listOf("down", "up")) {
                var steps = if (dir == "down") 10 else 24
                while (steps-- > 0) {
                    val before = svc.signature()
                    svc.scrollDir(dir)
                    delay(150)
                    if (svc.signature() == before) break
                    look()?.let { return it }
                }
            }
        }
        val l = bestLabel
        return if (l != null && bestScore >= Targeting.WEAK) Loc.Weak(l) else Loc.None
    }

    private fun rowLabel(all: List<CmfAccess.Cand>, c: CmfAccess.Cand): String {
        if (c.label.isNotBlank()) return c.label
        val mates = all.filter { it.node != c.node && it.label.isNotBlank() && !it.checkable && abs(it.rect.centerY() - c.rect.centerY()) < max(c.rect.height(), 40) * 1.2 }
        return mates.minByOrNull { abs(it.rect.centerX() - c.rect.centerX()) + if (it.rect.centerX() > c.rect.centerX()) 200 else 0 }?.label.orEmpty()
    }

    private fun ask(svc: CmfAccess, list: List<CmfAccess.Cand>, c: Command.UiSmart, name: String, switch: Int): ActionResult {
        val w = dm.widthPixels
        val h = dm.heightPixels
        val all = svc.candidates()
        val shown = Targeting.reading(list).take(6)
        val opts = shown.map { ScreenMemory.Opt(rowLabel(all, it).ifBlank { name.ifBlank { "item" } }, it.hit, Targeting.whereWords(it.hit, w, h)) }
        ScreenMemory.offer(opts, switch)
        val parts = opts.mapIndexed { i, o -> "${ordinals[i]}, ${o.label.take(26)} ${o.where}" }
        val lead = if (c.act == ScreenAct.ASK) "Yes, I can see ${opts.size} of them: " else "There are ${opts.size} matches for “${name.ifBlank { "that" }}”: "
        return ActionResult.Failure(lead + parts.joinToString("; ") + ". Which one?")
    }

    private suspend fun fromOptions(svc: CmfAccess, c: Command.UiSmart): ActionResult {
        val opts = ScreenMemory.options
        val sw = ScreenMemory.pendingSwitch
        val idx = when {
            c.nth != null -> if (c.nth < 0) opts.size + c.nth else c.nth - 1
            else -> Targeting.pickRegionRect(opts.map { it.rect }, c.region ?: "middle", dm.widthPixels, dm.heightPixels)
        }
        val o = opts.getOrNull(idx) ?: return ActionResult.Failure("There's no option number ${c.nth ?: ""}. Pick one of the ${opts.size}.".replace("  ", " "))
        ScreenMemory.clearOptions()
        val near = svc.candidates().filter { it.hit.flattenToString() == o.rect.flattenToString() || (it.label.equals(o.label, true) && abs(it.hit.centerY() - o.rect.centerY()) < 40) }
            .let { l -> l.firstOrNull { it.checkable } ?: l.firstOrNull() }
        val cand = near ?: (locate(svc, o.label, Targeting.Kind.ANY, null, null, o.rect) as? Loc.One)?.c
            ?: return ActionResult.Failure("I can't see that one any more. Ask me again.")
        return if (sw >= 0) toggleCand(svc, cand, when (sw) { 0 -> false; 1 -> true; else -> null }, o.label) else act(svc, cand, c.act)
    }

    private suspend fun act(svc: CmfAccess, c: CmfAccess.Cand, how: ScreenAct, relNote: String = ""): ActionResult {
        val all = if (c.label.isBlank()) svc.candidates() else emptyList()
        val label = rowLabel(all, c).ifBlank { "that" }
        val where = Targeting.whereWords(c.hit, dm.widthPixels, dm.heightPixels)
        ScreenMemory.remember(label, c.hit, svc.frontPackage())
        val tail = if (relNote.isBlank()) "" else " $relNote"
        return when (how) {
            ScreenAct.ASK -> ActionResult.Success("Yes, “$label” is $where$tail. Say tap it, hold it, or ask what's next to it.")
            ScreenAct.TAP -> {
                val before = svc.signature()
                if (!svc.click(c.node)) return ActionResult.Failure("I couldn't tap “$label”.")
                delay(450)
                val title = svc.topTitle()
                val sameish = title.isBlank() || CmfAccess.norm(title) == CmfAccess.norm(label)
                val changed = svc.signature() != before
                ActionResult.Success(if (changed && !sameish) "Tapped “$label”. Now on $title." else "Tapped “$label”.")
            }
            ScreenAct.LONG -> if (svc.longClick(c.node)) ActionResult.Success("Held “$label”.") else ActionResult.Failure("I couldn't hold “$label”.")
            ScreenAct.DOUBLE -> if (svc.centre(c.node)?.let { svc.doubleTapAt(it.first, it.second) } == true) ActionResult.Success("Double tapped “$label”.") else ActionResult.Failure("I couldn't double tap “$label”.")
            ScreenAct.DELETE -> {
                svc.longClick(c.node)
                delay(650)
                val del = svc.find(listOf("Delete", "Remove", "Delete chat", "Delete for me", "Move to trash", "Trash", "Delete message", "Clear chat"))
                if (del != null && svc.click(del)) ActionResult.Success("Pressed delete on “$label”. If the app asks, confirm there.")
                else ActionResult.Failure("I held “$label” but couldn't see a delete option. Tell me what to tap.")
            }
        }
    }

    // ---------- switches ----------

    private suspend fun switch(svc: CmfAccess, c: Command.UiSwitch): ActionResult {
        val w = dm.widthPixels
        val h = dm.heightPixels
        val sw = when (c.on) { true -> 1; false -> 0; null -> 2 }
        var label = c.label.trim()
        if (label.lowercase() in pronouns) label = ScreenMemory.last()?.label.orEmpty()
        val all = svc.candidates()
        val switches = all.filter { it.checkable }
        if (label.isBlank()) {
            return when {
                switches.isEmpty() -> ActionResult.Failure("I can't see a switch on this screen.")
                switches.size == 1 -> toggleCand(svc, switches[0], c.on, rowLabel(all, switches[0]))
                else -> ask(svc, switches, Command.UiSmart("switch"), "switch", sw)
            }
        }
        val (name, _) = Targeting.split(label)
        val q = name.ifBlank { label }
        // A switch whose row text matches?
        val rated = switches.map { it to max(Targeting.score(q, rowLabel(all, it)), Targeting.score(q, it.label)) }
        val top = rated.maxOfOrNull { it.second } ?: 0
        if (top >= 96) {
            val hits = rated.filter { it.second >= 96 }.map { it.first }
            return if (hits.size == 1) toggleCand(svc, hits[0], c.on, rowLabel(all, hits[0]))
            else ask(svc, hits, Command.UiSmart(label), label, sw)
        }
        // Not visible (or only a weak match): look for the label itself, scrolling if needed, then use the switch on its row.
        return when (val loc = locate(svc, q, Targeting.Kind.ANY, null, null, null)) {
            is Loc.One -> {
                val now = svc.candidates()
                val row = now.filter { it.checkable }.filter { abs(it.rect.centerY() - loc.c.rect.centerY()) < max(loc.c.hit.height(), 60) * 0.6 }
                    .minByOrNull { abs(it.rect.centerX() - loc.c.rect.centerX()) }
                toggleCand(svc, row ?: loc.c, c.on, loc.c.label)
            }
            is Loc.Many -> ask(svc, loc.list, Command.UiSmart(label), label, sw)
            is Loc.Weak -> ActionResult.Confirm(
                title = "Switch “${loc.label}”?",
                detail = "I couldn't find “$q” exactly. The closest is “${loc.label}”.",
                spoken = "I can't see “$q” exactly. Do you mean “${loc.label}”?",
                command = Command.UiSwitch(loc.label, c.on),
                declineMessage = "Okay, I won't touch it.",
                declineAsFailure = false
            )
            Loc.None -> ActionResult.Failure("I can't see a switch for “$q” on this screen.")
        }
    }

    private suspend fun toggleCand(svc: CmfAccess, c: CmfAccess.Cand, on: Boolean?, label: String): ActionResult {
        val name = label.ifBlank { "the switch" }
        val state = runCatching { c.node.isChecked }.getOrDefault(false)
        val known = c.checkable
        if (on != null && known && state == on) return ActionResult.Success("“$name” is already ${if (on) "on" else "off"}.")
        ScreenMemory.remember(name, c.hit, svc.frontPackage())
        if (!svc.click(c.node)) return ActionResult.Failure("I couldn't switch “$name”.")
        delay(320)
        val fresh = runCatching { c.node.refresh() }.getOrDefault(false)
        val now = runCatching { c.node.isChecked }.getOrDefault(!state)
        return ActionResult.Success(if (fresh && known) "“$name” is now ${if (now) "on" else "off"}." else "Toggled “$name”.")
    }

    // ---------- scrolling and small actions ----------

    private suspend fun scrollEx(svc: CmfAccess, c: Command.UiScrollEx): ActionResult {
        val limit = if (c.keep) 40 else c.times.coerceIn(1, 30)
        var moved = 0
        var edge = false
        for (i in 0 until limit) {
            val before = svc.signature()
            svc.scrollDir(c.dir)
            delay(if (c.keep) 240 else 180)
            if (svc.signature() == before) { edge = true; break }
            moved++
        }
        return when {
            moved == 0 -> ActionResult.Failure("I can't scroll any further ${c.dir} here.")
            c.keep || edge -> ActionResult.Success("Scrolled ${c.dir} to the end.")
            c.times > 1 -> ActionResult.Success("Scrolled ${c.dir} ${c.times} times.")
            else -> ActionResult.Success("Scrolled ${c.dir}.")
        }
    }

    private suspend fun misc(svc: CmfAccess, op: String): ActionResult = when (op) {
        "hidekeyboard" -> if (svc.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) ActionResult.Success("Keyboard hidden.") else ActionResult.Failure("I couldn't hide the keyboard.")
        "refresh" -> if (svc.swipe("down")) ActionResult.Success("Refreshing.") else ActionResult.Failure("I couldn't refresh here.")
        "next" -> if (svc.swipe("left")) ActionResult.Success("Next.") else ActionResult.Failure("I couldn't go to the next page.")
        else -> if (svc.swipe("right")) ActionResult.Success("Previous.") else ActionResult.Failure("I couldn't go back a page.")
    }
}

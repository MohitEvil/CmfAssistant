package com.demon.cmfassistant.action

import android.graphics.Rect
import com.demon.cmfassistant.access.CmfAccess

/** Pure matching and geometry helpers for "tap X", "the second toggle", "the button in front of Y". */
object Targeting {
    /** At or above this a label is treated as "this is what they meant"; below it the user is asked first. */
    const val DIRECT = 88
    const val WEAK = 60

    enum class Kind { ANY, CLICK, SWITCH, FIELD }

    private val kindWords = mapOf(
        "button" to Kind.CLICK, "buttons" to Kind.CLICK, "icon" to Kind.CLICK, "icons" to Kind.CLICK,
        "switch" to Kind.SWITCH, "switches" to Kind.SWITCH, "toggle" to Kind.SWITCH, "toggles" to Kind.SWITCH,
        "checkbox" to Kind.SWITCH, "checkboxes" to Kind.SWITCH, "slider" to Kind.SWITCH,
        "field" to Kind.FIELD, "textbox" to Kind.FIELD, "box" to Kind.FIELD, "input" to Kind.FIELD,
        "option" to Kind.ANY, "options" to Kind.ANY, "item" to Kind.ANY, "items" to Kind.ANY, "one" to Kind.ANY, "ones" to Kind.ANY,
        "thing" to Kind.ANY, "things" to Kind.ANY, "element" to Kind.ANY, "link" to Kind.ANY, "tab" to Kind.ANY, "result" to Kind.ANY,
        "video" to Kind.ANY, "videos" to Kind.ANY, "photo" to Kind.ANY, "photos" to Kind.ANY, "picture" to Kind.ANY, "pictures" to Kind.ANY,
        "image" to Kind.ANY, "images" to Kind.ANY, "post" to Kind.ANY, "message" to Kind.ANY, "messages" to Kind.ANY, "song" to Kind.ANY, "songs" to Kind.ANY,
        "track" to Kind.ANY, "notification" to Kind.ANY, "thumbnail" to Kind.ANY, "card" to Kind.ANY, "file" to Kind.ANY, "folder" to Kind.ANY,
        "story" to Kind.ANY, "reel" to Kind.ANY, "short" to Kind.ANY, "channel" to Kind.ANY, "conversation" to Kind.ANY, "mail" to Kind.ANY,
        "email" to Kind.ANY, "document" to Kind.ANY, "setting" to Kind.ANY, "settings" to Kind.ANY, "menu" to Kind.ANY, "tile" to Kind.ANY,
        "chat" to Kind.ANY, "contact" to Kind.ANY, "entry" to Kind.ANY, "row" to Kind.ANY, "app" to Kind.ANY, "text" to Kind.ANY
    )

    val kindWordSet: Set<String> get() = kindWords.keys

    private val filler = setOf("the", "a", "an", "on", "at", "this", "that", "my", "of", "to", "please")

    fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Words that only say what kind of thing, never which one. */
    val genericWords = setOf(
        "one", "ones", "thing", "things", "item", "items", "option", "options", "element", "button", "buttons", "icon", "icons",
        "switch", "switches", "toggle", "toggles", "checkbox", "checkboxes", "slider", "field", "textbox", "box", "input",
        "link", "links", "result", "results", "tab", "tabs", "entry", "row"
    )

    /** "the stop button" -> name "stop", kind CLICK. "toggle" -> name "", kind SWITCH. "Demon chat" -> "demon". "settings" -> name "settings". */
    fun split(target: String): Pair<String, Kind> {
        var w = words(target).filter { it !in filler }
        var kind = Kind.ANY
        while (w.size > 1 && w.last() in kindWords) {
            val k = kindWords.getValue(w.last())
            if (k != Kind.ANY) kind = k
            w = w.dropLast(1)
        }
        if (w.size == 1 && w[0] in kindWords) {
            val k = kindWords.getValue(w[0])
            return if (w[0] in genericWords) "" to k else w[0] to kind
        }
        return w.joinToString(" ") to kind
    }

    private fun run(l: List<String>, q: List<String>): Boolean {
        if (q.isEmpty() || q.size > l.size) return false
        for (i in 0..l.size - q.size) if (l.subList(i, i + q.size) == q) return true
        return false
    }

    /** 0..100: how well [label] is what [query] asks for. 100 = the same words. */
    fun score(query: String, label: String): Int {
        val q = words(query)
        val l = words(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        if (q == l) return 100
        val qs = q.joinToString("")
        val ls = l.joinToString("")
        if (qs == ls) return 98
        if (q.toSet() == l.toSet()) return 96
        // Chat rows read like "Demon, hi there, 2:30 PM": the first part is the name.
        val seg = label.split(Regex("[,;:|\\n]|\\s[-–—]\\s"), 2)[0]
        if (seg.length < label.length) {
            val sw = words(seg)
            if (sw == q) return 97
            if (sw.isNotEmpty() && sw.take(q.size) == q && sw.size - q.size <= 1) return 91
        }
        val extra = l.size - q.size
        if (extra > 0) {
            if (l.take(q.size) == q) return if (extra <= 2) 92 else 80
            if (run(l, q)) return if (extra <= 1) 90 else 75
            if (l.toSet().containsAll(q)) return 70
        }
        if (qs.length >= 4 && ls.contains(qs) && ls.length <= qs.length + 6) return 78
        val sim = Fuzzy.similarity(qs, ls)
        return when {
            sim >= 0.88 -> 89
            sim >= 0.72 -> 66
            else -> 0
        }
    }

    /** Places in reading order: rows top to bottom, left to right inside a row. */
    fun reading(list: List<CmfAccess.Cand>): List<CmfAccess.Cand> =
        list.sortedWith(compareBy({ it.hit.centerY() / 60 }, { it.hit.centerX() }))

    fun pickRegion(list: List<CmfAccess.Cand>, region: String, w: Int, h: Int): CmfAccess.Cand? {
        if (list.isEmpty()) return null
        fun cx(c: CmfAccess.Cand) = c.hit.exactCenterX()
        fun cy(c: CmfAccess.Cand) = c.hit.exactCenterY()
        return when (region) {
            "top" -> list.minByOrNull { cy(it) }
            "bottom" -> list.maxByOrNull { cy(it) }
            "left" -> list.minByOrNull { cx(it) }
            "right" -> list.maxByOrNull { cx(it) }
            "topleft" -> list.minByOrNull { cx(it) + cy(it) }
            "bottomright" -> list.maxByOrNull { cx(it) + cy(it) }
            "topright" -> list.minByOrNull { cy(it) - cx(it) }
            "bottomleft" -> list.maxByOrNull { cy(it) - cx(it) }
            else -> list.minByOrNull { Math.hypot((cx(it) - w / 2f).toDouble(), (cy(it) - h / 2f).toDouble()) }
        }
    }

    fun pickRegionRect(rects: List<Rect>, region: String, w: Int, h: Int): Int {
        if (rects.isEmpty()) return -1
        val idx = rects.indices
        return when (region) {
            "top" -> idx.minByOrNull { rects[it].centerY() }!!
            "bottom" -> idx.maxByOrNull { rects[it].centerY() }!!
            "left" -> idx.minByOrNull { rects[it].centerX() }!!
            "right" -> idx.maxByOrNull { rects[it].centerX() }!!
            "topleft" -> idx.minByOrNull { rects[it].centerX() + rects[it].centerY() }!!
            "bottomright" -> idx.maxByOrNull { rects[it].centerX() + rects[it].centerY() }!!
            "topright" -> idx.minByOrNull { rects[it].centerY() - rects[it].centerX() }!!
            "bottomleft" -> idx.maxByOrNull { rects[it].centerY() - rects[it].centerX() }!!
            else -> idx.minByOrNull { Math.hypot((rects[it].centerX() - w / 2.0), (rects[it].centerY() - h / 2.0)) }!!
        }
    }

    /**
     * From [pool], the one that sits [rel] of [a]: "front" = on the same row (either side), "right"/"left" = same row on that side,
     * "below"/"above" = further down/up, "after"/"before" = next on the row, else the next below/above, "near" = closest.
     */
    fun relative(a: Rect, rel: String, pool: List<CmfAccess.Cand>): CmfAccess.Cand? {
        val ax = a.exactCenterX()
        val ay = a.exactCenterY()
        fun row(c: CmfAccess.Cand): Boolean = Math.abs(c.hit.exactCenterY() - ay) < maxOf(a.height(), c.hit.height()) * 0.6f
        fun dist(c: CmfAccess.Cand, wx: Double = 1.0, wy: Double = 1.0): Double =
            Math.hypot((c.hit.exactCenterX() - ax) * wx, (c.hit.exactCenterY() - ay) * wy)
        val others = pool.filter { !(it.hit == a || it.rect == a) }
        fun sameRow(side: Int): List<CmfAccess.Cand> = others.filter { row(it) && (side == 0 || (it.hit.exactCenterX() - ax) * side > 0) }
        fun vertical(dir: Int): List<CmfAccess.Cand> = others.filter { !row(it) && (it.hit.exactCenterY() - ay) * dir > 0 }
        return when (rel) {
            "front" -> sameRow(0).minByOrNull { dist(it) } ?: others.minByOrNull { dist(it) }
            "right" -> sameRow(1).minByOrNull { dist(it) }
            "left" -> sameRow(-1).minByOrNull { dist(it) }
            "below" -> vertical(1).minByOrNull { dist(it, 0.6, 1.0) }
            "above" -> vertical(-1).minByOrNull { dist(it, 0.6, 1.0) }
            "after" -> sameRow(1).minByOrNull { dist(it) } ?: vertical(1).minByOrNull { dist(it, 0.6, 1.0) }
            "before" -> sameRow(-1).minByOrNull { dist(it) } ?: vertical(-1).minByOrNull { dist(it, 0.6, 1.0) }
            else -> others.minByOrNull { dist(it) }
        }
    }

    fun whereWords(r: Rect, w: Int, h: Int): String {
        val y = r.centerY().toFloat() / h
        val x = r.centerX().toFloat() / w
        val v = when { y < 0.25f -> "at the top"; y > 0.75f -> "at the bottom"; else -> "in the middle" }
        val s = when { x < 0.33f -> " on the left"; x > 0.66f -> " on the right"; else -> "" }
        return v + s
    }
}

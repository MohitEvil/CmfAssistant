package com.demon.cmfassistant.access

import android.graphics.Rect
import com.demon.cmfassistant.intent.ScreenAct

/**
 * Short-term memory of the screen: the last few things the assistant saw, found or tapped, so "click on it",
 * "long press that" or "what's right after it" know what "it" is. Not saved anywhere; it forgets after a few minutes.
 */
object ScreenMemory {
    class Item(val label: String, val rect: Rect, val pkg: String, val at: Long)
    class Opt(val label: String, val rect: Rect, val where: String)

    private const val KEEP = 5
    private const val FRESH_MS = 5 * 60_000L
    private const val OPTION_MS = 2 * 60_000L

    private val items = ArrayList<Item>()

    /** Choices offered by a "which one?" question, in reading order. */
    @Volatile var options: List<Opt> = emptyList()
        private set
    @Volatile private var optionsAt = 0L

    @Synchronized
    fun remember(label: String, rect: Rect, pkg: String) {
        val l = label.trim()
        if (l.isEmpty()) return
        items.removeAll { it.label.equals(l, true) }
        items.add(Item(l, Rect(rect), pkg, System.currentTimeMillis()))
        while (items.size > KEEP) items.removeAt(0)
    }

    @Synchronized
    fun last(): Item? = items.lastOrNull()?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }

    @Synchronized
    fun recent(): List<Item> = items.filter { System.currentTimeMillis() - it.at < FRESH_MS }.reversed()

    /** -1 = a plain tap is wanted, 0 = switch off, 1 = switch on, 2 = flip. */
    @Volatile var pendingSwitch = -1

    fun offer(list: List<Opt>, switch: Int = -1) {
        pendingSwitch = switch
        options = list
        optionsAt = System.currentTimeMillis()
    }

    fun hasOptions(): Boolean = options.isNotEmpty() && System.currentTimeMillis() - optionsAt < OPTION_MS

    fun clearOptions() {
        options = emptyList()
    }

    @Synchronized
    fun forget() {
        items.clear()
        options = emptyList()
    }
}

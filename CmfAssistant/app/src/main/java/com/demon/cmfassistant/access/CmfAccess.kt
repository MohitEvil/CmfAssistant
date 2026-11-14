package com.demon.cmfassistant.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Optional screen control. Everything goes through accessibility node actions (click, scroll, set text),
 * which are applied straight to the app's views, so a stray touch from the user cannot cancel them.
 * Gestures are only a last resort. The service acts on voice commands only and keeps nothing it reads.
 */
class CmfAccess : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CmfAccess? = null
            private set

        /** True once the user has switched the service on in Android's Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, CmfAccess::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun norm(s: CharSequence?): String = s?.toString()?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Reading the screen ----

    /** Every window on screen except our own, so "tap Settings" never hits the assistant's own panel. */
    private fun roots(includeOwn: Boolean = false): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            windows?.forEach { w -> w.root?.let { r -> if (includeOwn || r.packageName != packageName) out.add(r) } }
        }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { if (includeOwn || it.packageName != packageName) out.add(it) }
        }
        return out
    }

    private inline fun walk(root: AccessibilityNodeInfo, limit: Int = 2500, visit: (AccessibilityNodeInfo) -> Boolean) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < limit) {
            val n = stack.removeFirst()
            seen++
            if (visit(n)) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        norm(n.text).ifEmpty { norm(n.contentDescription) }.ifEmpty { norm(n.hintText) }

    /** Best visible node whose text or description is (or contains) one of [labels]. Exact matches win. */
    fun find(labels: List<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { norm(it) }.filter { it.isNotEmpty() }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (r in roots()) {
            walk(r) { n ->
                if (n.isVisibleToUser) {
                    val l = labelOf(n)
                    if (l.isNotEmpty()) {
                        for (w in wanted) {
                            val score = when {
                                l == w -> 3
                                l.startsWith(w) -> 2
                                l.contains(w) && l.length < w.length + 24 -> 1
                                else -> 0
                            }
                            if (score > bestScore) { best = n; bestScore = score }
                        }
                    }
                }
                bestScore == 3
            }
            if (bestScore == 3) break
        }
        return best
    }

    /** Waits (polling every 60 ms) until one of [labels] shows up. */
    suspend fun waitFind(labels: List<String>, timeoutMs: Long = 2500): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            find(labels)?.let { return it }
            delay(60)
        }
        return null
    }

    /** Scrolls the first scrollable list until [labels] appears: forward first, then back, so a long page is searched directly. */
    suspend fun scrollToFind(labels: List<String>, maxScrolls: Int = 16): AccessibilityNodeInfo? {
        find(labels)?.let { return it }
        for (forward in listOf(true, false)) {
            var stuck = 0
            repeat(maxScrolls) {
                val sc = findScrollable() ?: return null
                val moved = sc.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
                delay(if (moved) 110 else 40)
                find(labels)?.let { return it }
                if (!moved && ++stuck >= 1) return@repeat
            }
        }
        return find(labels)
    }

    private fun findScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()
        for (root in roots()) {
            walk(root) { n ->
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(r)
                    val a = r.width() * r.height()
                    if (a > bestArea) { best = n; bestArea = a }
                }
                false
            }
        }
        return best
    }

    fun scroll(forward: Boolean): Boolean = findScrollable()?.performAction(
        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    ) ?: false

    /** Text currently visible on screen, top to bottom, without duplicates. */
    fun visibleText(limit: Int = 40): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in roots()) {
            walk(r) { n ->
                if (n.isVisibleToUser) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrEmpty() && t.length < 160) seen.add(t)
                }
                seen.size >= limit
            }
        }
        return seen.toList()
    }

    // ---- Acting ----

    /** Clicks the node, or the nearest clickable parent. Falls back to a tap gesture if nothing is clickable. */
    fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var i = 0
        while (c != null && i < 7) {
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            i++
        }
        return tapCentre(n)
    }

    private fun tapCentre(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** On/off state of the switch that belongs to this label (itself, a child, or a sibling in the same row). */
    fun switchState(n: AccessibilityNodeInfo): Boolean? {
        var c: AccessibilityNodeInfo? = n
        repeat(4) {
            val s = c ?: return null
            checkable(s, 0)?.let { return it.isChecked }
            c = s.parent
        }
        return null
    }

    private fun checkable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (n.isCheckable) return n
        val cn = n.className?.toString().orEmpty()
        if (cn.contains("Switch") || cn.contains("CheckBox") || cn.contains("ToggleButton")) return n
        if (depth >= 4) return null
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { ch -> checkable(ch, depth + 1)?.let { return it } }
        }
        return null
    }

    fun setText(value: String): Boolean {
        var field: AccessibilityNodeInfo? = null
        for (r in roots()) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) field = it }
            if (field != null) break
            walk(r) { n ->
                if (n.isEditable && n.isVisibleToUser) { field = n; true } else false
            }
            if (field != null) break
        }
        val f = field ?: return false
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)
}

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

    // ---- Understanding the screen ----

    class Screen(val app: String, val texts: List<String>, val buttons: List<String>, val fields: List<String>)

    /** Label of the app in front, e.g. "Settings" or "Chrome". */
    fun frontApp(): String {
        val pkg = roots().firstOrNull()?.packageName?.toString() ?: return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** A summary of what is on screen: text, tappable things and text boxes. */
    fun describeScreen(): Screen {
        val texts = LinkedHashSet<String>()
        val buttons = LinkedHashSet<String>()
        val fields = LinkedHashSet<String>()
        for (r in roots()) {
            walk(r) { n ->
                if (n.isVisibleToUser) {
                    val t = n.text?.toString()?.trim().orEmpty()
                    val d = n.contentDescription?.toString()?.trim().orEmpty()
                    val label = t.ifEmpty { d }
                    if (n.isEditable) {
                        fields.add(label.ifEmpty { n.hintText?.toString().orEmpty() }.ifEmpty { "text box" })
                    } else if (label.isNotEmpty() && label.length < 60 && (n.isClickable || n.isCheckable)) {
                        buttons.add(label)
                    } else if (t.isNotEmpty() && t.length < 160) {
                        texts.add(t)
                    }
                }
                false
            }
        }
        return Screen(frontApp(), texts.toList(), buttons.toList(), fields.toList())
    }

    /** First visible text box, preferring one that looks like a search field. */
    fun findEditable(): AccessibilityNodeInfo? {
        var first: AccessibilityNodeInfo? = null
        var search: AccessibilityNodeInfo? = null
        for (r in roots()) {
            walk(r) { n ->
                if (n.isEditable && n.isVisibleToUser) {
                    if (first == null) first = n
                    val l = (n.text?.toString().orEmpty() + n.hintText + n.contentDescription).lowercase()
                    if (search == null && l.contains("search")) search = n
                }
                false
            }
        }
        return search ?: first
    }

    /** "at the top", "in the middle on the right", ... */
    fun where(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        val y = r.centerY().toFloat() / dm.heightPixels
        val x = r.centerX().toFloat() / dm.widthPixels
        val v = when { y < 0.25f -> "at the top"; y > 0.75f -> "at the bottom"; else -> "in the middle" }
        val h = when { x < 0.33f -> " on the left"; x > 0.66f -> " on the right"; else -> "" }
        return v + h
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    // ---- More ways to act ----

    fun frontPackage(): String = roots().firstOrNull()?.packageName?.toString().orEmpty()

    /** Long press: the node's own long-click action (cannot be cancelled by a touch), else a held gesture. */
    fun longClick(n: AccessibilityNodeInfo, holdMs: Long = 800): Boolean {
        var c: AccessibilityNodeInfo? = n
        var i = 0
        while (c != null && i < 7) {
            if (c.isLongClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            c = c.parent
            i++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return holdAt(r.exactCenterX(), r.exactCenterY(), holdMs)
    }

    fun holdAt(x: Float, y: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
    }

    /** Two quick taps in the same place. */
    fun doubleTapAt(x: Float, y: Float): Boolean {
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 40))
        b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 140, 40))
        return dispatchGesture(b.build(), null, null)
    }

    fun centre(n: AccessibilityNodeInfo): Pair<Float, Float>? {
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (r.isEmpty) null else r.exactCenterX() to r.exactCenterY()
    }

    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (dir) {
            "left" -> listOf(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
            "right" -> listOf(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            "up" -> listOf(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f)
            else -> listOf(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.7f)
        }
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 260)).build(), null, null)
    }

    /** Pinch: two fingers moving apart (zoom in) or together (zoom out). */
    fun pinch(zoomIn: Boolean): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val near = dm.widthPixels * 0.06f
        val far = dm.widthPixels * 0.30f
        val (a, b) = if (zoomIn) near to far else far to near
        val p1 = Path().apply { moveTo(cx - a, cy); lineTo(cx - b, cy) }
        val p2 = Path().apply { moveTo(cx + a, cy); lineTo(cx + b, cy) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 350))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, 350))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Select all / copy / paste / cut in the focused text box. */
    fun clip(op: String): Boolean {
        var f: AccessibilityNodeInfo? = null
        for (r in roots()) {
            f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (f != null) break
        }
        val node = f ?: findEditable() ?: return false
        return when (op) {
            "select_all" -> {
                val len = node.text?.length ?: 0
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
            "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "paste" -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            else -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }
    }

    /** The keyboard's Enter / Search / Send key for the focused text box. */
    fun imeEnter(): Boolean {
        val f = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } ?: findEditable() ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scrollEdge(top: Boolean): Boolean {
        var any = false
        repeat(25) {
            val moved = scroll(!top)
            if (!moved) return any
            any = true
        }
        return any
    }

    /** The search field, or the icon/button that opens one. Second value is true when it is already a text box. */
    fun findSearchTrigger(): Pair<AccessibilityNodeInfo, Boolean>? {
        findEditable()?.let { e ->
            val l = (e.text?.toString().orEmpty() + e.hintText + e.contentDescription).lowercase()
            if (l.contains("search") || l.contains("find")) return e to true
        }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (r in roots()) {
            walk(r) { n ->
                if (n.isVisibleToUser && !n.isEditable) {
                    val l = labelOf(n)
                    if (l.isNotEmpty() && l.length < 40 && (l.contains("search") || l == "find")) {
                        var clickable = false
                        var c: AccessibilityNodeInfo? = n
                        repeat(4) { if (c != null && c!!.isClickable) clickable = true; c = c?.parent }
                        if (clickable) {
                            val score = if (l == "search") 3 else if (l.startsWith("search")) 2 else 1
                            if (score > bestScore) { best = n; bestScore = score }
                        }
                    }
                }
                false
            }
        }
        best?.let { return it to false }
        findEditable()?.let { return it to true }
        return null
    }

    /** A visible editable field (the one to type into), after a short wait. */
    suspend fun waitEditable(ms: Long = 1500): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findEditable()?.let { return it }
            delay(60)
        }
        return null
    }

    /** Best on-screen text matching [query]: exact beats starts-with beats contains; the text box itself is ignored. */
    fun bestMatch(query: String): AccessibilityNodeInfo? {
        val q = norm(query)
        if (q.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        val skip = setOf("search", "cancel", "clear", "back", "close", "voicesearch", "searchforq", "all", "chats", "people")
        for (r in roots()) {
            walk(r) { n ->
                if (n.isVisibleToUser && !n.isEditable) {
                    val l = norm(n.text).ifEmpty { norm(n.contentDescription) }
                    if (l.isNotEmpty() && l !in skip) {
                        var sc = when {
                            l == q -> 1.0
                            l.startsWith(q) -> 0.9
                            l.contains(q) && !l.startsWith("searchfor") -> 0.7
                            else -> 0.0
                        }
                        if (sc == 0.0 && l.length <= 40) {
                            val d = com.demon.cmfassistant.action.Fuzzy.similarity(q, l)
                            if (d >= 0.6) sc = d * 0.8
                        }
                        if (sc > bestScore) { best = n; bestScore = sc }
                    }
                }
                false
            }
        }
        return if (bestScore >= 0.5) best else null
    }

    /** The send / submit button next to a message box. */
    fun findSend(): AccessibilityNodeInfo? {
        find(listOf("Send", "Send message", "Send button", "Post", "Submit"))?.let { return it }
        return null
    }

    /** The camera shutter: by label first, otherwise the round clickable thing nearest the bottom centre. */
    fun findShutter(): AccessibilityNodeInfo? {
        find(listOf("Shutter", "Shutter button", "Take photo", "Take picture", "Capture", "Take a photo", "Camera shutter", "Photo"))?.let { return it }
        val dm = resources.displayMetrics
        var best: AccessibilityNodeInfo? = null
        var bestD = Float.MAX_VALUE
        val r = Rect()
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser && n.isClickable && n.isEnabled) {
                    n.getBoundsInScreen(r)
                    val w = r.width()
                    val wantMin = dm.density * 48
                    val wantMax = dm.density * 160
                    if (r.centerY() > dm.heightPixels * 0.72f && w in wantMin.toInt()..wantMax.toInt() && r.height() in wantMin.toInt()..wantMax.toInt()) {
                        val d = Math.abs(r.centerX() - dm.widthPixels / 2f)
                        if (d < dm.widthPixels * 0.18f && d < bestD) { best = n; bestD = d }
                    }
                }
                false
            }
        }
        return best
    }

    suspend fun waitSearch(ms: Long): Pair<AccessibilityNodeInfo, Boolean>? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findSearchTrigger()?.let { return it }
            delay(70)
        }
        return null
    }

    suspend fun waitShutter(ms: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findShutter()?.let { return it }
            delay(100)
        }
        return null
    }

    /** The first tappable result under the search box. */
    fun firstResult(): AccessibilityNodeInfo? {
        val field = findEditable()
        val fr = Rect()
        field?.getBoundsInScreen(fr)
        val top = if (field != null) fr.bottom else 0
        var best: AccessibilityNodeInfo? = null
        var bestY = Int.MAX_VALUE
        val r = Rect()
        for (root in roots()) {
            walk(root) { n ->
                if (n.isVisibleToUser && !n.isEditable && n.isClickable && n.isEnabled) {
                    n.getBoundsInScreen(r)
                    if (r.top >= top - 4 && r.top < bestY && (n.text?.isNotEmpty() == true || n.contentDescription?.isNotEmpty() == true || n.childCount > 0)) {
                        best = n; bestY = r.top
                    }
                }
                false
            }
        }
        return best
    }
}

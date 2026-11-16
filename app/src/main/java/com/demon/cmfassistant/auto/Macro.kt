package com.demon.cmfassistant.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** A scheduled task: a voice command that runs by itself. [days] uses Calendar.SUNDAY..SATURDAY; empty = every day. [onceAt] > 0 means one time only. */
data class Macro(
    val id: Long,
    val text: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val onceAt: Long = 0L,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("h", hour).put("m", minute)
        .put("days", JSONArray(days.toList())).put("once", onceAt).put("on", enabled)

    fun whenText(): String {
        val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }.time)
        if (onceAt > 0) {
            val c = Calendar.getInstance().apply { timeInMillis = onceAt }
            val now = Calendar.getInstance()
            val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            val tom = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val isTom = c.get(Calendar.YEAR) == tom.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == tom.get(Calendar.DAY_OF_YEAR)
            val day = when { sameDay -> "today"; isTom -> "tomorrow"; else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(onceAt)) }
            return "$day at $t"
        }
        val names = mapOf(Calendar.SUNDAY to "Sunday", Calendar.MONDAY to "Monday", Calendar.TUESDAY to "Tuesday", Calendar.WEDNESDAY to "Wednesday",
            Calendar.THURSDAY to "Thursday", Calendar.FRIDAY to "Friday", Calendar.SATURDAY to "Saturday")
        val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val who = when {
            days.isEmpty() || days.size == 7 -> "every day"
            days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "on weekdays"
            days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "on weekends"
            days.size == 6 -> "every day except " + names[order.first { it !in days }]
            else -> "every " + order.filter { it in days }.joinToString(", ") { names[it]!! }
        }
        return "$who at $t"
    }

    companion object {
        fun fromJson(j: JSONObject): Macro {
            val d = HashSet<Int>()
            j.optJSONArray("days")?.let { for (i in 0 until it.length()) d.add(it.getInt(i)) }
            return Macro(j.getLong("id"), j.getString("text"), j.getInt("h"), j.getInt("m"), d, j.optLong("once", 0L), j.optBoolean("on", true))
        }
    }
}

object MacroStore {
    private fun prefs(c: Context) = c.getSharedPreferences("cmf_macros", Context.MODE_PRIVATE)

    fun all(c: Context): List<Macro> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { Macro.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Macro>) {
        prefs(c).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun add(c: Context, m: Macro) = save(c, all(c) + m)
    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })
    fun update(c: Context, m: Macro) = save(c, all(c).map { if (it.id == m.id) m else it })
    fun clear(c: Context) = save(c, emptyList())
}

object MacroScheduler {
    fun next(m: Macro, now: Long = System.currentTimeMillis()): Long {
        if (m.onceAt > 0) return if (m.onceAt > now) m.onceAt else 0L
        for (d in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, m.hour); set(Calendar.MINUTE, m.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now && (m.days.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in m.days)) return c.timeInMillis
        }
        return 0L
    }

    private fun pending(c: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(c, (id % Int.MAX_VALUE).toInt(), Intent(c, MacroReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, m: Macro) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = next(m)
        if (!m.enabled || at == 0L) { am.cancel(pending(c, m.id)); return }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, m.id))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, m.id))
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, m.id)) } }
    }

    fun cancel(c: Context, id: Long) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id))
    }

    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        for (m in MacroStore.all(c)) {
            if (m.onceAt in 1..now) MacroStore.remove(c, m.id) else schedule(c, m)
        }
    }
}

class MacroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        val m = MacroStore.all(context).firstOrNull { it.id == id } ?: return
        if (m.onceAt > 0) { MacroStore.remove(context, id) } else MacroScheduler.schedule(context, m)
        if (!m.enabled) return
        val app = context.applicationContext as com.demon.cmfassistant.CmfApp
        app.controller.runScheduled(m.text)
    }
}

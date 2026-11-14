package com.demon.cmfassistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.demon.cmfassistant.core.AssistantState
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.delay

/** NORMAL: full motion while active. SAVER: lower frame rate. CRITICAL: static glyphs, no animation. */
enum class PerfMode { NORMAL, SAVER, CRITICAL }

private fun readPower(context: Context): PerfMode {
    val pm = context.getSystemService(PowerManager::class.java)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
    return when {
        !charging && pct <= 10 -> PerfMode.CRITICAL
        pm?.isPowerSaveMode == true || (!charging && pct <= 20) -> PerfMode.SAVER
        else -> PerfMode.NORMAL
    }
}

/** Combines the user's settings with live battery state. */
@Composable
fun rememberPerfMode(settings: Settings): PerfMode {
    val context = LocalContext.current
    var power by remember { mutableStateOf(readPower(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                power = readPower(c)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return when {
        !settings.animations || power == PerfMode.CRITICAL -> PerfMode.CRITICAL
        settings.batterySaver || power == PerfMode.SAVER -> PerfMode.SAVER
        else -> PerfMode.NORMAL
    }
}

/** Frame rate budget per state. Idle is nearly static, so it gets the lowest rate. */
fun targetFps(state: AssistantState, mode: PerfMode): Int = when (mode) {
    PerfMode.CRITICAL -> 0
    PerfMode.SAVER -> when (state) {
        AssistantState.LISTENING, AssistantState.SUCCESS, AssistantState.FAILURE -> 24
        AssistantState.SPEAKING -> 20
        else -> 15
    }
    PerfMode.NORMAL -> when (state) {
        AssistantState.LISTENING, AssistantState.SUCCESS, AssistantState.FAILURE -> 60
        AssistantState.RECOGNIZING, AssistantState.THINKING, AssistantState.EXECUTING, AssistantState.SPEAKING -> 30
        AssistantState.CONFIRMING, AssistantState.IDLE -> 15
    }
}

/**
 * A clock that ticks at [fps] only while the screen is STARTED. The glyph reads it while drawing,
 * so each tick redraws the canvas without recomposing anything. fps == 0 stops it entirely.
 */
@Composable
fun rememberGlyphClock(fps: Int): LongState {
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(fps, owner) {
        if (fps <= 0) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (fps >= 50) {
                while (true) {
                    withFrameNanos { clock.longValue = SystemClock.uptimeMillis() }
                }
            } else {
                val step = 1000L / fps
                while (true) {
                    clock.longValue = SystemClock.uptimeMillis()
                    delay(step)
                }
            }
        }
    }
    return clock
}

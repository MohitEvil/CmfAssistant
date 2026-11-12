package com.demon.cmfassistant.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.demon.cmfassistant.data.Settings

/**
 * Event-based haptics from the design plan. Nothing vibrates continuously:
 * listening, recognizing and thinking only get (at most) a single short tick at their edges.
 */
class HapticController(context: Context, private val settings: Settings) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun play(effect: VibrationEffect) {
        if (!settings.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(effect)
    }

    /** Microphone activated / action started: one light tap. */
    fun lightTap() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    /** Recognition completed: a very light tap. */
    fun veryLightTap() = play(VibrationEffect.createOneShot(8, 40))

    /** Action successful: two short confirmation taps. */
    fun success() = play(
        VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 18), intArrayOf(0, 160, 0, 160), -1)
    )

    /** Action failed: one stronger warning pulse. */
    fun failure() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))

    /** User cancels: one short tap. */
    fun cancel() = play(VibrationEffect.createOneShot(20, 120))
}

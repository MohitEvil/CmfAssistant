package com.demon.cmfassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.demon.cmfassistant.core.AssistantState
import kotlin.math.abs
import kotlin.math.sin

private const val TAU = 6.2831855f

/**
 * The compact, one-line version of the glyph used in the small bubble panel:
 * a dot-matrix strip whose bars, sweep and colour follow the same states as the round glyph.
 * Clock and amplitude are read inside the draw block only, so frames never recompose anything.
 */
@Composable
fun LineGlyph(
    state: AssistantState,
    since: Long,
    clock: LongState,
    amplitude: FloatState,
    animate: Boolean,
    palette: GlyphPalette,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val cols = 41
        val rows = 7
        val mid = 3
        val center = cols / 2
        val pitch = minOf(size.width / cols, size.height / rows)
        val ox = (size.width - pitch * cols) / 2f
        val oy = (size.height - pitch * rows) / 2f
        val r = pitch * 0.28f

        val t = if (animate) clock.longValue else 0L
        val ph = (t % 100_000L).toFloat()
        val elapsed = if (animate) (t - since).coerceAtLeast(0L).toFloat() else 10_000f
        val amp = if (animate) amplitude.floatValue else 0.25f

        val decay = (1f - elapsed / 280f).coerceIn(0f, 1f)
        val shake = if (state == AssistantState.FAILURE) sin(elapsed * 0.09f) * pitch * 1.2f * decay else 0f

        for (c in 0 until cols) {
            var half = 0
            var bar = palette.fg
            var base = palette.dim
            var baseR = r
            val env = 1f - 0.65f * abs(c - center) / center.toFloat()

            when (state) {
                AssistantState.IDLE -> {
                    if (abs(c - center) <= 1) {
                        val breathe = if (animate) 0.5f + 0.5f * sin(TAU * (t % 2800L).toFloat() / 2800f) else 1f
                        base = palette.fg.copy(alpha = 0.45f + 0.55f * breathe)
                        baseR = r * 1.3f
                    }
                }
                AssistantState.LISTENING -> {
                    val n = if (animate) 0.5f + 0.5f * sin(c * 1.3f + ph * 0.012f + sin(c * 0.7f) * 2f) else 0.5f
                    val lv = (amp * n * env * 1.6f).coerceIn(0f, 1f)
                    half = (lv * 3f + 0.5f).toInt().coerceIn(0, 3)
                    if (half >= 3) bar = palette.red
                }
                AssistantState.RECOGNIZING -> {
                    val w = if (animate) 0.5f + 0.5f * sin(ph * 0.006f + c * 0.38f) else 0.5f
                    half = (w * env * 3f + 0.3f).toInt().coerceIn(0, 3)
                }
                AssistantState.SPEAKING -> {
                    val w = if (animate) {
                        (0.5f + 0.5f * sin(ph * 0.009f + c * 0.55f)) * (0.55f + 0.45f * sin(ph * 0.0037f + c * 0.2f))
                    } else {
                        0.4f
                    }
                    half = (w.coerceIn(0f, 1f) * env * 3.4f).toInt().coerceIn(0, 3)
                    if (half >= 3) bar = palette.red
                }
                AssistantState.THINKING -> {
                    val x = (if (animate) ph / 1500f else 0.5f) % 2f
                    val pos = (if (x < 1f) x else 2f - x) * (cols - 1)
                    val d = abs(c - pos)
                    if (d < 1.2f) {
                        base = palette.red
                        baseR = r * 1.7f
                    } else if (d < 8f) {
                        val k = 1f - d / 8f
                        base = palette.fg.copy(alpha = 0.12f + 0.8f * k)
                        baseR = r * (1f + 0.5f * k)
                    }
                }
                AssistantState.CONFIRMING -> {
                    base = palette.muted.copy(alpha = 0.6f)
                    if (abs(c - center) <= 1 || c == 0 || c == cols - 1) {
                        base = palette.red
                        baseR = r * 1.5f
                    }
                }
                AssistantState.EXECUTING -> {
                    val head = if (animate) (ph % 1000f) / 1000f * (cols + 12) else cols * 0.7f
                    val d = head - c
                    if (d in 0f..12f) {
                        base = palette.red.copy(alpha = 1f - d / 12f * 0.85f)
                        baseR = r * 1.4f
                    }
                }
                AssistantState.SUCCESS -> {
                    val reach = (elapsed / 450f).coerceIn(0f, 1f) * center
                    if (abs(c - center) <= reach) {
                        base = palette.red
                        baseR = r * 1.25f
                    }
                }
                AssistantState.FAILURE -> {
                    base = palette.red
                    baseR = r * 1.25f
                }
            }

            val x = ox + (c + 0.5f) * pitch + shake
            for (k in 1..3) {
                val y1 = oy + (mid - k + 0.5f) * pitch
                val y2 = oy + (mid + k + 0.5f) * pitch
                if (k <= half) {
                    drawCircle(bar, r, Offset(x, y1))
                    drawCircle(bar, r, Offset(x, y2))
                } else {
                    drawCircle(palette.dim.copy(alpha = 0.3f), r * 0.75f, Offset(x, y1))
                    drawCircle(palette.dim.copy(alpha = 0.3f), r * 0.75f, Offset(x, y2))
                }
            }
            drawCircle(base, baseR, Offset(x, oy + (mid + 0.5f) * pitch))
        }
    }
}

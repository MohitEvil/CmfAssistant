package com.demon.cmfassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.demon.cmfassistant.core.AssistantState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val PI_F = 3.1415927f
private const val TWO_PI_F = 6.2831855f

/**
 * The circular dot-matrix "glyph". One identity, one shape per state.
 *
 * [clock] and [amplitude] are read inside the draw block only, so a new frame or a new microphone
 * level invalidates the drawing, not the composition. With [animate] false (critical battery or
 * animations off) every state draws a single static frame and nothing ticks.
 */
@Composable
fun GlyphVisualizer(
    state: AssistantState,
    since: Long,
    clock: LongState,
    amplitude: FloatState,
    animate: Boolean,
    palette: GlyphPalette,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = s * 0.42f
        val now = if (animate) clock.longValue else 0L
        val elapsed = if (animate) (now - since).coerceAtLeast(0L).toFloat() else 10_000f
        when (state) {
            AssistantState.IDLE -> drawIdle(c, s, r, now, animate, palette)
            AssistantState.LISTENING -> drawListening(c, s, r, now, if (animate) amplitude.floatValue else 0.25f, animate, palette)
            AssistantState.SPEAKING -> {
                val ph = (now % 100_000L).toFloat()
                val amp = if (animate) 0.3f + 0.3f * (0.5f + 0.5f * sin(ph * 0.007f)) else 0.3f
                drawListening(c, s, r, now, amp, animate, palette, mic = false)
            }
            AssistantState.RECOGNIZING -> drawRecognizing(c, s, r, now, animate, palette)
            AssistantState.THINKING -> drawThinking(c, s, r, now, animate, palette)
            AssistantState.CONFIRMING -> drawConfirming(c, s, r, palette)
            AssistantState.EXECUTING -> drawExecuting(c, s, r, now, animate, palette)
            AssistantState.SUCCESS -> drawSuccess(c, s, r, elapsed, palette)
            AssistantState.FAILURE -> drawFailure(c, s, r, elapsed, palette)
        }
    }
}

private fun DrawScope.drawIdle(c: Offset, s: Float, r: Float, t: Long, animate: Boolean, p: GlyphPalette) {
    val dotR = s * 0.0075f
    for (k in 1..6) {
        val rad = r * k / 6f
        val n = 6 * k
        for (i in 0 until n) {
            val a = TWO_PI_F * i / n
            drawCircle(p.dim, dotR, c + Offset(cos(a) * rad, sin(a) * rad))
        }
    }
    // The only motion in idle: the centre dot breathing, 2.8s per cycle.
    val breathe = if (animate) 0.5f + 0.5f * sin(TWO_PI_F * (t % 2800L).toFloat() / 2800f) else 1f
    drawCircle(p.fg.copy(alpha = 0.45f + 0.55f * breathe), s * 0.022f, c)
}

private fun DrawScope.drawListening(
    c: Offset, s: Float, r: Float, t: Long, amp: Float, animate: Boolean, p: GlyphPalette, mic: Boolean = true
) {
    val n = 72
    val r0 = r * 0.62f
    val maxLen = r * 0.38f
    val stroke = s * 0.011f
    val phase = (t % 100_000L).toFloat()
    for (i in 0 until n) {
        val a = TWO_PI_F * i / n - PI_F / 2f
        val noise = if (animate) 0.5f + 0.5f * sin(i * 1.7f + phase * 0.012f + sin(i * 0.9f) * 2f) else 0.5f
        val level = amp * noise
        val len = r * 0.05f + maxLen * level
        val color = if (level > 0.55f) p.red else p.fg
        val dx = cos(a)
        val dy = sin(a)
        drawLine(
            color,
            c + Offset(dx * r0, dy * r0),
            c + Offset(dx * (r0 + len), dy * (r0 + len)),
            stroke,
            StrokeCap.Round
        )
    }
    if (mic) drawMic(c, s * 0.07f, p) else drawCircle(p.fg, s * 0.02f, c)
}

private fun DrawScope.drawMic(c: Offset, h: Float, p: GlyphPalette) {
    val w = h * 0.55f
    val sw = h * 0.12f
    drawRoundRect(
        color = p.fg,
        topLeft = Offset(c.x - w / 2f, c.y - h * 0.6f),
        size = Size(w, h * 0.9f),
        cornerRadius = CornerRadius(w / 2f),
        style = Stroke(width = sw)
    )
    drawArc(
        color = p.fg,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(c.x - w * 0.85f, c.y - h * 0.45f),
        size = Size(w * 1.7f, h * 1.2f),
        style = Stroke(width = sw, cap = StrokeCap.Round)
    )
    drawLine(p.fg, Offset(c.x, c.y + h * 0.75f), Offset(c.x, c.y + h * 0.95f), sw, StrokeCap.Round)
}

private fun DrawScope.drawRecognizing(c: Offset, s: Float, r: Float, t: Long, animate: Boolean, p: GlyphPalette) {
    val n = 36
    val rr = r * 0.8f
    val dotR = s * 0.0085f
    for (i in 0 until n) {
        val a = TWO_PI_F * i / n
        val accent = i % 12 == 3
        drawCircle(
            if (accent) p.red else p.muted.copy(alpha = 0.5f),
            if (accent) dotR * 1.5f else dotR,
            c + Offset(cos(a) * rr, sin(a) * rr)
        )
    }
    val phase = (t % 100_000L).toFloat()
    val gap = s * 0.035f
    val bw = s * 0.014f
    for (b in 0 until 7) {
        val wave = if (animate) sin(phase * 0.006f + b * 0.9f) else 0f
        val taper = 1f - abs(b - 3) * 0.12f
        val h = s * (0.05f + 0.045f * (0.5f + 0.5f * wave)) * taper
        val x = c.x + (b - 3) * gap
        drawLine(p.fg, Offset(x, c.y - h / 2f), Offset(x, c.y + h / 2f), bw, StrokeCap.Round)
    }
}

private fun DrawScope.drawThinking(c: Offset, s: Float, r: Float, t: Long, animate: Boolean, p: GlyphPalette) {
    val rot = if (animate) TWO_PI_F * ((t % 4000L).toFloat() / 4000f) else 0f
    val dotR = s * 0.0085f

    // Outer orbit: a red leader dragging a fading tail of white dots.
    val n = 60
    val rr = r * 0.8f
    for (i in 0 until n) {
        val ang = TWO_PI_F * i / n
        val d = (((rot - ang) % TWO_PI_F) + TWO_PI_F) % TWO_PI_F
        val trail = d / TWO_PI_F
        val strength = (1f - trail).pow(2.2f)
        val a = ang - PI_F / 2f
        drawCircle(
            p.fg.copy(alpha = 0.12f + 0.8f * strength),
            dotR * (1f + 0.6f * strength),
            c + Offset(cos(a) * rr, sin(a) * rr)
        )
    }
    val lead = rot - PI_F / 2f
    drawCircle(p.red, dotR * 2.3f, c + Offset(cos(lead) * rr, sin(lead) * rr))

    // Inner ring turns the other way, slower.
    val n2 = 36
    val rr2 = r * 0.52f
    val rot2 = -rot * 0.6f
    for (i in 0 until n2) {
        val a = TWO_PI_F * i / n2 + rot2 - PI_F / 2f
        drawCircle(p.muted.copy(alpha = 0.5f), dotR * 0.9f, c + Offset(cos(a) * rr2, sin(a) * rr2))
    }

    // Core glows gently while the assistant works.
    val pulse = if (animate) 0.5f + 0.5f * sin(TWO_PI_F * (t % 2000L).toFloat() / 2000f) else 1f
    drawCircle(p.fg.copy(alpha = 0.16f + 0.12f * pulse), s * 0.05f + s * 0.012f * pulse, c)
    drawCircle(p.fg, s * 0.022f, c)
}

private fun DrawScope.drawConfirming(c: Offset, s: Float, r: Float, p: GlyphPalette) {
    val n = 60
    val rr = r * 0.8f
    val dotR = s * 0.0085f
    for (i in 0 until n) {
        val a = TWO_PI_F * i / n - PI_F / 2f
        val top = i == 0
        drawCircle(
            if (top) p.red else p.muted.copy(alpha = 0.6f),
            if (top) dotR * 2.3f else dotR,
            c + Offset(cos(a) * rr, sin(a) * rr)
        )
    }
    // Paper-plane style arrow.
    val u = s * 0.07f
    val path = Path().apply {
        moveTo(c.x - u, c.y + u * 0.7f)
        lineTo(c.x + u * 1.2f, c.y)
        lineTo(c.x - u, c.y - u * 0.7f)
        lineTo(c.x - u * 0.4f, c.y)
        close()
    }
    drawPath(path, p.fg, style = Stroke(width = s * 0.01f, join = StrokeJoin.Round))
}

private fun DrawScope.drawExecuting(c: Offset, s: Float, r: Float, t: Long, animate: Boolean, p: GlyphPalette) {
    val n = 72
    val rr = r * 0.78f
    val dotR = s * 0.0085f
    val sweep = 20
    val head = if (animate) (t % 1400L).toFloat() / 1400f * n else n * 0.7f
    for (i in 0 until n) {
        val dist = ((head - i) % n + n) % n
        val on = dist < sweep
        val a = TWO_PI_F * i / n - PI_F / 2f
        drawCircle(
            if (on) p.red.copy(alpha = 1f - dist / sweep * 0.85f) else p.dim,
            if (on) dotR * 1.4f else dotR,
            c + Offset(cos(a) * rr, sin(a) * rr)
        )
    }
    val pulse = if (animate) 0.5f + 0.5f * sin(TWO_PI_F * (t % 1200L).toFloat() / 1200f) else 1f
    drawCircle(p.fg.copy(alpha = 0.5f + 0.5f * pulse), r * 0.5f, c, style = Stroke(width = s * 0.008f))
    drawCircle(p.fg, s * 0.02f, c)
}

private fun DrawScope.drawSuccess(c: Offset, s: Float, r: Float, elapsed: Float, p: GlyphPalette) {
    val n = 72
    val rr = r * 0.78f
    val dotR = s * 0.0085f
    for (i in 0 until n) {
        val a = TWO_PI_F * i / n - PI_F / 2f
        drawCircle(p.red, dotR, c + Offset(cos(a) * rr, sin(a) * rr))
    }
    // One short red pulse leaving the ring.
    val pp = (elapsed / 450f).coerceIn(0f, 1f)
    if (pp < 1f) {
        drawCircle(
            p.red.copy(alpha = 0.7f * (1f - pp)),
            rr + r * 0.2f * pp,
            c,
            style = Stroke(width = s * 0.006f)
        )
    }
    drawCircle(p.fg, r * 0.5f, c, style = Stroke(width = s * 0.009f))

    // Check mark drawn over ~300ms.
    val u = s * 0.1f
    val a = Offset(c.x - 0.5f * u, c.y)
    val b = Offset(c.x - 0.12f * u, c.y + 0.38f * u)
    val d = Offset(c.x + 0.55f * u, c.y - 0.4f * u)
    val prog = (elapsed / 300f).coerceIn(0f, 1f)
    val p1 = (prog / 0.35f).coerceIn(0f, 1f)
    val p2 = ((prog - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val sw = s * 0.018f
    drawLine(p.fg, a, lerp(a, b, p1), sw, StrokeCap.Round)
    if (p2 > 0f) drawLine(p.fg, b, lerp(b, d, p2), sw, StrokeCap.Round)
}

private fun DrawScope.drawFailure(c: Offset, s: Float, r: Float, elapsed: Float, p: GlyphPalette) {
    val decay = (1f - elapsed / 280f).coerceIn(0f, 1f)
    val shake = sin(elapsed * 0.09f) * s * 0.025f * decay
    val cc = Offset(c.x + shake, c.y)

    val flash = (1f - elapsed / 200f).coerceIn(0f, 1f)
    if (flash > 0f) drawCircle(p.red.copy(alpha = 0.15f * flash), r, cc)

    val n = 60
    val rr = r * 0.78f
    val dotR = s * 0.0085f
    for (i in 0 until n) {
        val a = TWO_PI_F * i / n - PI_F / 2f
        drawCircle(p.red.copy(alpha = 0.55f), dotR, cc + Offset(cos(a) * rr, sin(a) * rr))
    }
    drawCircle(p.red, r * 0.5f, cc, style = Stroke(width = s * 0.009f))
    val u = s * 0.055f
    val sw = s * 0.016f
    drawLine(p.red, Offset(cc.x - u, cc.y - u), Offset(cc.x + u, cc.y + u), sw, StrokeCap.Round)
    drawLine(p.red, Offset(cc.x - u, cc.y + u), Offset(cc.x + u, cc.y - u), sw, StrokeCap.Round)
}

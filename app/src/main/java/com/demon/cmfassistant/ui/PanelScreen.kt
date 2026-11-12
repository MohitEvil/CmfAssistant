package com.demon.cmfassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.core.AssistantState
import com.demon.cmfassistant.core.UiState

/**
 * The small Gemini-style panel: a bottom card over whatever app you were in,
 * with the dot-matrix line, what you said, and the answer. Tap outside to dismiss.
 */
@Composable
fun PanelScreen(
    ui: UiState,
    amplitude: FloatState,
    palette: GlyphPalette,
    perf: PerfMode,
    onTapLine: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    val fps = targetFps(ui.state, perf)
    val clock = rememberGlyphClock(fps)
    val animate = perf != PerfMode.CRITICAL
    val mono = FontFamily.Monospace
    val shape = RoundedCornerShape(28.dp)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .tap(onClose)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(palette.bg)
                .border(1.dp, palette.dim, shape)
                .tap {} // taps inside the card must not dismiss it
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            LineGlyph(
                state = ui.state,
                since = ui.since,
                clock = clock,
                amplitude = amplitude,
                animate = animate,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .tap(onTapLine)
            )
            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val pending = ui.pending
                when {
                    pending != null -> ConfirmContent(
                        pending = pending,
                        micOpen = ui.micOpen,
                        palette = palette,
                        onConfirm = onConfirm,
                        onDismiss = onDismissConfirm
                    )
                    ui.state == AssistantState.SPEAKING && ui.reply.isNotBlank() -> Column(
                        Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            ui.reply,
                            color = palette.fg,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ui.state == AssistantState.RECOGNIZING -> Text(
                        "“${ui.transcript}”",
                        color = palette.fg,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    else -> {
                        val line = when (ui.state) {
                            AssistantState.IDLE -> "Tap the line, then speak"
                            else -> ui.status
                        }
                        Text(
                            line,
                            color = palette.muted,
                            fontFamily = mono,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        if (ui.transcript.isNotBlank() &&
                            (ui.state == AssistantState.THINKING || ui.state == AssistantState.EXECUTING ||
                                ui.state == AssistantState.SUCCESS || ui.state == AssistantState.FAILURE)
                        ) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "“${ui.transcript}”",
                                color = palette.muted.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CLOSE",
                    color = palette.muted,
                    fontFamily = mono,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(vertical = 6.dp, horizontal = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                if (ui.micOpen) {
                    Text("● MIC", color = palette.red, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    "OPEN APP",
                    color = palette.muted,
                    fontFamily = mono,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable(onClick = onExpand).padding(vertical = 6.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

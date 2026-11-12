package com.demon.cmfassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.core.AssistantState
import com.demon.cmfassistant.core.UiState

fun labelFor(state: AssistantState) = when (state) {
    AssistantState.IDLE -> "READY"
    AssistantState.LISTENING -> "LISTENING"
    AssistantState.RECOGNIZING -> "RECOGNIZING"
    AssistantState.THINKING -> "THINKING"
    AssistantState.SPEAKING -> "SPEAKING"
    AssistantState.CONFIRMING -> "CONFIRM"
    AssistantState.EXECUTING -> "EXECUTING"
    AssistantState.SUCCESS -> "DONE"
    AssistantState.FAILURE -> "FAILED"
}

@Composable
fun AssistantScreen(
    ui: UiState,
    amplitude: FloatState,
    palette: GlyphPalette,
    perf: PerfMode,
    onGlyphTap: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val fps = targetFps(ui.state, perf)
    val clock = rememberGlyphClock(fps)
    val animate = perf != PerfMode.CRITICAL
    val label = labelFor(ui.state)
    val mono = FontFamily.Monospace

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CMF ASSISTANT", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "SETTINGS",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }

        // Glyph
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val glyphSize: Dp = minOf(maxWidth, maxHeight, 340.dp)
            GlyphVisualizer(
                state = ui.state,
                since = ui.since,
                clock = clock,
                amplitude = amplitude,
                animate = animate,
                palette = palette,
                modifier = Modifier
                    .size(glyphSize)
                    .semantics {
                        contentDescription = "Assistant is $label. Double tap to speak."
                        role = Role.Button
                    }
                    .tap(onGlyphTap)
            )
        }

        // Label, status, transcript, answer
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = dotTextCols(label)
            val pitch = minOf(5.dp, maxWidth / cols)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 170.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DotText(
                    text = label,
                    color = if (ui.state == AssistantState.FAILURE) palette.red else palette.fg,
                    offColor = palette.dim.copy(alpha = 0.45f),
                    pitch = pitch
                )
                Spacer(Modifier.height(14.dp))

                if (ui.state == AssistantState.SPEAKING && ui.reply.isNotBlank()) {
                    Column(
                        Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            ui.reply,
                            color = palette.fg,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (ui.transcript.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "“${ui.transcript}”",
                            color = palette.muted,
                            fontFamily = mono,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val subtitle = if (ui.state == AssistantState.IDLE) "Tap the glyph to speak" else ui.status
                    Text(
                        subtitle,
                        color = palette.muted,
                        fontFamily = mono,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    when (ui.state) {
                        AssistantState.LISTENING -> {
                            Spacer(Modifier.height(10.dp))
                            Text("● MIC ACTIVE", color = palette.red, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
                        }
                        AssistantState.RECOGNIZING -> {
                            Spacer(Modifier.height(12.dp))
                            TranscriptPill("“${ui.transcript}”", palette, bright = true)
                        }
                        AssistantState.THINKING, AssistantState.EXECUTING, AssistantState.SUCCESS, AssistantState.FAILURE -> {
                            if (ui.transcript.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                TranscriptPill("“${ui.transcript}”", palette, bright = false)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        // Yes / no question
        ui.pending?.let { pending ->
            ConfirmContent(
                pending = pending,
                micOpen = ui.micOpen,
                palette = palette,
                onConfirm = onConfirm,
                onDismiss = onDismissConfirm,
                modifier = Modifier
                    .background(palette.card, RoundedCornerShape(24.dp))
                    .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        // Bottom area
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (ui.state) {
                AssistantState.LISTENING, AssistantState.RECOGNIZING, AssistantState.SPEAKING ->
                    PillButton(
                        if (ui.state == AssistantState.SPEAKING) "STOP" else "CANCEL",
                        filled = false,
                        palette = palette,
                        onClick = onCancel,
                        modifier = Modifier.padding(horizontal = 40.dp).fillMaxWidth()
                    )
                AssistantState.IDLE -> {
                    Text("TRY", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "“turn on the flashlight”",
                        "“set a timer for 5 minutes”",
                        "“text Mom saying on my way”",
                        "“how are you?”"
                    ).forEach {
                        Text(it, color = palette.muted.copy(alpha = 0.8f), fontFamily = mono, fontSize = 12.sp)
                    }
                }
                else -> Unit
            }
            if (perf != PerfMode.NORMAL) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (perf == PerfMode.SAVER) "LOW-POWER VISUALS" else "STATIC VISUALS",
                    color = palette.muted,
                    fontFamily = mono,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

@Composable
private fun TranscriptPill(text: String, palette: GlyphPalette, bright: Boolean) {
    Box(
        Modifier
            .background(palette.card, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = if (bright) palette.fg else palette.muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PillButton(
    text: String,
    filled: Boolean,
    palette: GlyphPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .then(
                if (filled) Modifier.background(palette.fg, shape)
                else Modifier.border(BorderStroke(1.dp, palette.muted), shape)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) palette.bg else palette.fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

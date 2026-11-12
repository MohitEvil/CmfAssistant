package com.demon.cmfassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.core.PendingConfirm

/** The yes / no question. Works by tapping, and by voice while the microphone is open. */
@Composable
fun ConfirmContent(
    pending: PendingConfirm,
    micOpen: Boolean,
    palette: GlyphPalette,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mono = FontFamily.Monospace
    Column(modifier.fillMaxWidth()) {
        Text(pending.title, color = palette.fg, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(pending.detail, color = palette.fg, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("NO", filled = false, palette, onDismiss, Modifier.weight(1f))
            PillButton("YES", filled = true, palette, onConfirm, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (micOpen) "● LISTENING. SAY YES OR NO (HAAN / NAHI)" else "TAP A BUTTON, OR TAP THE GLYPH TO ANSWER BY VOICE",
            color = if (micOpen) palette.red else palette.muted,
            fontFamily = mono,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
    }
}

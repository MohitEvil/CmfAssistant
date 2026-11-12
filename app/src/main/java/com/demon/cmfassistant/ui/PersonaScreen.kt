package com.demon.cmfassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.data.PersonaPresets
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.launch

/** Lets the user shape how the AI behaves: pick a preset, or write their own. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PersonaScreen(
    settings: Settings,
    palette: GlyphPalette,
    onTestAi: suspend (String) -> String,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CMF ASSISTANT", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "BACK",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        DotText("PERSONA", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Tell the AI how to behave and sound. This is sent with every chat message. " +
                "The default is a quiet assistant that keeps answers short.",
            color = palette.muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("PRESETS", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PersonaPresets.all.forEach { preset ->
                Chip(preset.name, settings.aiPersona.trim() == preset.text, palette) {
                    settings.setAiPersona(preset.text)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("YOUR PERSONA", palette)
        BasicTextField(
            value = settings.aiPersona,
            onValueChange = { settings.setAiPersona(it) },
            minLines = 6,
            maxLines = 14,
            textStyle = TextStyle(color = palette.fg, fontSize = 14.sp),
            cursorBrush = SolidColor(palette.red),
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.card, RoundedCornerShape(18.dp))
                .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(18.dp))
                .padding(16.dp)
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("RESET", filled = false, palette = palette, onClick = {
                settings.setAiPersona(PersonaPresets.DEFAULT)
                testResult = ""
            }, modifier = Modifier.weight(1f))
            PillButton(if (testing) "…" else "TRY IT", filled = true, palette = palette, onClick = {
                if (!testing) {
                    testing = true
                    testResult = ""
                    scope.launch {
                        testResult = onTestAi("Hey, how's your day going?")
                        testing = false
                    }
                }
            }, modifier = Modifier.weight(1f))
        }

        if (testResult.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("REPLY", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(testResult, color = palette.fg, fontSize = 15.sp)
        }
        Spacer(Modifier.height(32.dp))
    }
}

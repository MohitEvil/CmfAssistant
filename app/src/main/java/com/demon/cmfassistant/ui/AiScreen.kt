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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.data.AiPresets
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.launch

/** All the AI chat settings live here, away from the main settings page. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiScreen(
    settings: Settings,
    palette: GlyphPalette,
    onTestAi: suspend (String) -> String,
    onFetchModels: suspend () -> Result<List<String>>,
    alwaysThinks: Boolean,
    onOpenPersona: () -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }

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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DotText("AI CHAT", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = settings.aiEnabled,
                onCheckedChange = { settings.setAiEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.red,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = palette.muted,
                    uncheckedTrackColor = palette.dim,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
        Text(
            if (settings.aiEnabled) "ON" else "OFF. THE ASSISTANT WON'T USE AI.",
            color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Add your own API key so the assistant can answer questions and chat in English, Hindi or Hinglish. " +
                "Without a key it still runs commands and makes small talk. Your key stays on this phone and is only sent to the provider you pick.",
            color = palette.muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("PROVIDER", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiPresets.all.forEach { p ->
                Chip(p.name, settings.aiProvider == p.id, palette) {
                    settings.setAiProvider(p.id)
                    models = emptyList()
                    fetchNote = ""
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(palette) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Field("API KEY", settings.aiKey, palette, secret = true) { settings.setAiKey(it) }
                Spacer(Modifier.height(14.dp))
                Field("ADDRESS", settings.aiBaseUrl, palette, keyboard = KeyboardType.Uri) { settings.setAiBaseUrl(it) }
                Spacer(Modifier.height(14.dp))
                Text("MODEL", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = settings.aiModel,
                        onValueChange = { settings.setAiModel(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.fg, fontSize = 14.sp, fontFamily = mono),
                        cursorBrush = SolidColor(palette.red),
                        modifier = Modifier
                            .weight(1f)
                            .background(palette.bg, RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (fetching) "…" else "FETCH",
                        color = palette.fg,
                        fontFamily = mono,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .border(BorderStroke(1.dp, palette.muted), RoundedCornerShape(50))
                            .clickable {
                                if (!fetching) {
                                    fetching = true
                                    fetchNote = ""
                                    scope.launch {
                                        val r = onFetchModels()
                                        r.onSuccess {
                                            models = it
                                            filter = ""
                                            fetchNote = "${it.size} MODELS FOUND. TAP ONE."
                                        }.onFailure {
                                            models = emptyList()
                                            fetchNote = it.message ?: "Couldn't fetch models."
                                        }
                                        fetching = false
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
                if (fetchNote.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(fetchNote, color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
        }

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val shown = if (filter.isBlank()) models else models.filter { it.contains(filter.trim(), ignoreCase = true) }
            Card(palette) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.fg, fontSize = 12.sp, fontFamily = mono),
                            cursorBrush = SolidColor(palette.red),
                            decorationBox = { inner ->
                                if (filter.isEmpty()) Text("filter…", color = palette.muted, fontFamily = mono, fontSize = 12.sp)
                                inner()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .background(palette.bg, RoundedCornerShape(10.dp))
                                .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("${shown.size}/${models.size}", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                        items(shown, key = { it }) { id ->
                            val picked = id == settings.aiModel.trim()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { settings.setAiModel(id) }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    id,
                                    color = if (picked) palette.fg else palette.muted,
                                    fontFamily = mono,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                if (picked) Text("●", color = palette.red, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(palette) {
            ToggleRow(
                "Thinking mode",
                "Lets the AI reason before answering. Smarter on hard questions, but slower. Off is faster." +
                    if (!settings.aiThinking && alwaysThinks) " This model reasons anyway. Pick a faster one for quicker answers." else "",
                settings.aiThinking,
                palette
            ) { settings.setAiThinking(it) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (settings.aiKey.isBlank()) "NO KEY SET. CHAT USES BASIC REPLIES." else "AI CHAT IS ON.",
            color = palette.muted,
            fontFamily = mono,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )

        if (settings.aiKey.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            PillButton(
                text = if (testing) "TESTING…" else "TEST CONNECTION",
                filled = false,
                palette = palette,
                onClick = {
                    if (!testing) {
                        testing = true
                        testResult = ""
                        scope.launch {
                            testResult = onTestAi("Say hello in five words.")
                            testing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (testResult.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(testResult, color = palette.fg, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            Card(palette) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPersona)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Assistant persona", color = palette.fg, fontSize = 15.sp)
                        Text("How the AI behaves and sounds", color = palette.muted, fontSize = 12.sp)
                    }
                    Text("›", color = palette.muted, fontSize = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

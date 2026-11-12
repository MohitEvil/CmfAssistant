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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.data.AiPresets
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    palette: GlyphPalette,
    perf: PerfMode,
    onBubbleToggle: (Boolean) -> Unit,
    onOpenAppPermissions: () -> Unit,
    onTestAi: suspend (String) -> String,
    onOpenPersona: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenLog: () -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
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
                "CLOSE",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        DotText("SETTINGS", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
        Spacer(Modifier.height(24.dp))

        Card(palette) {
            ToggleRow("Use haptics", "Short taps for key moments only", settings.haptics, palette) { settings.setHaptics(it) }
            Divider(palette)
            ToggleRow("Show animations", "Off draws static glyphs", settings.animations, palette) { settings.setAnimations(it) }
            Divider(palette)
            ToggleRow("Battery saver mode", "Simpler visuals, lower power", settings.batterySaver, palette) { settings.setBatterySaver(it) }
            Divider(palette)
            ToggleRow("Prefer offline speech", "Uses the on-device model when installed", settings.preferOffline, palette) {
                settings.setPreferOffline(it)
            }
            Divider(palette)
            ToggleRow("Spoken replies", "The assistant talks back and asks you questions", settings.speakReplies, palette) {
                settings.setSpeakReplies(it)
            }
            Divider(palette)
            ToggleRow("Keep listening for follow-ups", "Mic reopens after an answer, so you can just reply", settings.followUp, palette) {
                settings.setFollowUp(it)
            }
            Divider(palette)
            ToggleRow("Floating bubble", "Draws over other apps. Tap to talk.", settings.bubble, palette, onBubbleToggle)
        }

        Spacer(Modifier.height(16.dp))
        Card(palette) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenVoice)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voice", color = palette.fg, fontSize = 15.sp)
                    Text("Offline neural voices, speaker and speed", color = palette.muted, fontSize = 12.sp)
                }
                Text("›", color = palette.muted, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(palette) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLog)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Debug log", color = palette.fg, fontSize = 15.sp)
                    Text("Temporary: see what the app and voices are doing", color = palette.muted, fontSize = 12.sp)
                }
                Text("›", color = palette.muted, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("LANGUAGE", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("EN-IN" to "en-IN", "EN-US" to "en-US", "HI-IN" to "hi-IN").forEach { (name, tag) ->
                Chip(name, settings.language == tag, palette) { settings.setLanguage(tag) }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("THEME", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("SYSTEM" to ThemeMode.SYSTEM, "DARK" to ThemeMode.DARK, "LIGHT" to ThemeMode.LIGHT).forEach { (name, mode) ->
                Chip(name, settings.theme == mode, palette) { settings.setTheme(mode) }
            }
        }

        Spacer(Modifier.height(24.dp))
        AiSection(settings, palette, onTestAi, onOpenPersona)

        Spacer(Modifier.height(24.dp))
        Card(palette) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAppPermissions)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App permissions", color = palette.fg, fontSize = 15.sp)
                    Text("Microphone, contacts, notifications", color = palette.muted, fontSize = 12.sp)
                }
                Text("›", color = palette.muted, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "VISUALS: ${perf.name}",
            color = palette.muted,
            fontFamily = mono,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "CMF Assistant 1.0. A Nothing-inspired design made for the CMF Phone 1. Not affiliated with Nothing Technology.",
            color = palette.muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
internal fun Card(palette: GlyphPalette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.card, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(24.dp))
    ) { content() }
}

@Composable
internal fun Divider(palette: GlyphPalette) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(1.dp)
            .background(palette.dim.copy(alpha = 0.6f))
    )
}

@Composable
internal fun SectionLabel(text: String, palette: GlyphPalette) {
    Text(
        text,
        color = palette.muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    palette: GlyphPalette,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.fg, fontSize = 15.sp)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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
}

@Composable
internal fun Chip(text: String, selected: Boolean, palette: GlyphPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) palette.bg else palette.fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .then(
                if (selected) Modifier.background(palette.fg, shape)
                else Modifier.border(BorderStroke(1.dp, palette.muted), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AiSection(
    settings: Settings,
    palette: GlyphPalette,
    onTestAi: suspend (String) -> String,
    onOpenPersona: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    SectionLabel("AI CHAT (OPTIONAL)", palette)
    Text(
        "Add your own API key so the assistant can answer questions and chat in English, Hindi or Hinglish. " +
            "Without a key it still runs commands and makes small talk. Your key stays on this phone and is only sent to the provider you pick.",
        color = palette.muted,
        fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AiPresets.all.forEach { p ->
            Chip(p.name, settings.aiProvider == p.id, palette) { settings.setAiProvider(p.id) }
        }
    }
    Spacer(Modifier.height(14.dp))
    Card(palette) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Field("API KEY", settings.aiKey, palette, secret = true) { settings.setAiKey(it) }
            Spacer(Modifier.height(14.dp))
            Field("MODEL", settings.aiModel, palette) { settings.setAiModel(it) }
            Spacer(Modifier.height(14.dp))
            Field("ADDRESS", settings.aiBaseUrl, palette, keyboard = KeyboardType.Uri) { settings.setAiBaseUrl(it) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(palette) {
        ToggleRow(
            "Thinking mode",
            "Lets the AI reason before answering. Smarter on hard questions, but slower. Off is faster.",
            settings.aiThinking,
            palette
        ) { settings.setAiThinking(it) }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (settings.aiKey.isBlank()) "NO KEY SET. CHAT USES BASIC REPLIES." else "AI CHAT IS ON.",
        color = palette.muted,
        fontFamily = FontFamily.Monospace,
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
}

@Composable
private fun Field(
    label: String,
    value: String,
    palette: GlyphPalette,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    Text(label, color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = palette.fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(palette.red),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

package com.demon.cmfassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.data.Provider
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.launch

/** AI voice: optional cloud text-to-speech. Same layout as AI chat. Off by default; the offline voice is used when it's off or fails. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AiVoiceScreen(
    settings: Settings,
    palette: GlyphPalette,
    onTestVoice: suspend (Provider) -> String,
    onOpenChatTab: () -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var reorderId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    val on = settings.aiVoiceEnabled
    val list = settings.voiceProviders
    val selected = list.firstOrNull { it.id == selectedId }
    val readyCount = list.count { it.ready }

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
                "BACK", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        AiTabs(1, palette, onOpenChat = onOpenChatTab, onOpenVoice = {})
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DotText("AI VOICE", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = { settings.setAiVoiceEnabled(it) },
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
            if (on) "ON" else "OFF. THE ASSISTANT USES ITS OFFLINE VOICE.",
            color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(20.dp))

        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().alpha(if (on) 1f else 0.3f)) {
                Text(
                    "Add your own voice providers. Replies are spoken by the first one that works, and by the offline voice if none do. " +
                        "Online voices need internet and can be slower than the offline voice. Keys stay on this phone.",
                    color = palette.muted, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("PROVIDERS · LONG PRESS TO REORDER", palette)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEachIndexed { i, p ->
                        ProviderChip(
                            text = "${i + 1} ${p.name}" + if (p.locked) " ·LOCK" else "",
                            selected = p.id == selectedId,
                            ready = p.ready,
                            palette = palette,
                            onClick = { adding = false; reorderId = null; testResult = ""; selectedId = if (p.id == selectedId) null else p.id },
                            onLongClick = { reorderId = if (reorderId == p.id) null else p.id }
                        )
                    }
                    ProviderChip("+ CUSTOM", adding, true, palette, onClick = { adding = !adding; reorderId = null }, onLongClick = {})
                }

                val moving = list.firstOrNull { it.id == reorderId }
                if (moving != null) {
                    val pos = list.indexOf(moving)
                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${moving.name} · ${pos + 1}/${list.size}", color = palette.fg, fontFamily = mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            SmallAction("‹ EARLIER", pos > 0, palette) { settings.moveVoiceProvider(moving.id, -1) }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("LATER ›", pos < list.size - 1, palette) { settings.moveVoiceProvider(moving.id, 1) }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("DONE", true, palette) { reorderId = null }
                        }
                    }
                }

                if (adding) {
                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Field("PROVIDER NAME", newName, palette) { newName = it }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                SmallAction("SAVE", newName.isNotBlank(), palette) {
                                    val id = settings.addCustomVoiceProvider(newName)
                                    newName = ""
                                    adding = false
                                    selectedId = id
                                }
                                Spacer(Modifier.width(8.dp))
                                SmallAction("CANCEL", true, palette) { adding = false; newName = "" }
                            }
                        }
                    }
                }

                if (selected != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Card(palette) {
                            Column(Modifier.alpha(if (selected.locked) 0.45f else 1f).padding(horizontal = 18.dp, vertical = 14.dp)) {
                                if (selected.custom) {
                                    Field("NAME", selected.name, palette) { v -> settings.updateVoiceProvider(selected.id) { it.copy(name = v.uppercase()) } }
                                    Spacer(Modifier.height(14.dp))
                                }
                                Field("API KEY", selected.key, palette, secret = true) { v -> settings.updateVoiceProvider(selected.id) { it.copy(key = v) } }
                                Spacer(Modifier.height(14.dp))
                                Field("ADDRESS", selected.baseUrl, palette, keyboard = KeyboardType.Uri) { v -> settings.updateVoiceProvider(selected.id) { it.copy(baseUrl = v) } }
                                Spacer(Modifier.height(14.dp))
                                Field("MODEL", selected.model, palette) { v -> settings.updateVoiceProvider(selected.id) { it.copy(model = v) } }
                                Spacer(Modifier.height(14.dp))
                                Field("VOICE", selected.extra, palette) { v -> settings.updateVoiceProvider(selected.id) { it.copy(extra = v) } }
                            }
                        }
                        if (selected.locked) Blocker(Modifier.matchParentSizeCompat(this))
                    }

                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        ToggleRow(
                            "Lock this provider",
                            "Keeps its key, address, model and voice from being edited by accident",
                            selected.locked,
                            palette
                        ) { v -> settings.updateVoiceProvider(selected.id) { it.copy(locked = v) } }
                        if (selected.custom && !selected.locked) {
                            Divider(palette)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val id = selected.id
                                    selectedId = null
                                    settings.removeVoiceProvider(id)
                                }.padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text("Delete this provider", color = palette.red, fontSize = 15.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (readyCount == 0) "NO PROVIDER READY. THE OFFLINE VOICE IS USED." else "$readyCount READY. TRIED IN ORDER, FIRST TO LAST.",
                    color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
                )

                if (selected != null && selected.ready) {
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        text = if (testing) "PLAYING…" else "TEST ${selected.name}",
                        filled = false,
                        palette = palette,
                        onClick = {
                            if (!testing) {
                                testing = true
                                testResult = ""
                                val target = selected
                                scope.launch {
                                    testResult = onTestVoice(target)
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
                }
            }
            if (!on) Blocker(Modifier.matchParentSizeCompat(this))
        }
        Spacer(Modifier.height(32.dp))
    }
}

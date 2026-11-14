package com.demon.cmfassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.data.Provider
import com.demon.cmfassistant.data.Settings
import kotlinx.coroutines.launch

/** All the AI chat settings live here, away from the main settings page. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AiScreen(
    settings: Settings,
    palette: GlyphPalette,
    onTestProvider: suspend (Provider) -> String,
    onFetchModels: suspend (Provider) -> Result<List<String>>,
    alwaysThinks: (Provider) -> Boolean,
    onOpenPersona: () -> Unit,
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
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }

    val on = settings.aiEnabled
    val selected = settings.providers.firstOrNull { it.id == selectedId }
    val readyCount = settings.providers.count { it.ready }

    fun select(id: String?) {
        selectedId = id
        models = emptyList()
        fetchNote = ""
        filter = ""
        testResult = ""
    }

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
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DotText("AI CHAT", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = on,
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
            if (on) "ON" else "OFF. THE ASSISTANT WON'T USE AI.",
            color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(20.dp))

        // Everything below goes dim and untouchable while AI chat is off.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().alpha(if (on) 1f else 0.3f)) {
                Text(
                    "Add your own providers. The assistant tries them from first to last and uses the first that answers. " +
                        "Without a key it still runs commands and makes small talk. Keys stay on this phone.",
                    color = palette.muted, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("PROVIDERS · LONG PRESS TO REORDER", palette)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    settings.providers.forEachIndexed { i, p ->
                        ProviderChip(
                            text = "${i + 1} ${p.name}" + if (p.locked) " ·LOCK" else "",
                            selected = p.id == selectedId,
                            ready = p.ready,
                            palette = palette,
                            onClick = { adding = false; reorderId = null; select(if (p.id == selectedId) null else p.id) },
                            onLongClick = { reorderId = if (reorderId == p.id) null else p.id }
                        )
                    }
                    ProviderChip("+ CUSTOM", adding, true, palette, onClick = { adding = !adding; reorderId = null }, onLongClick = {})
                }

                // Reorder bar
                val moving = settings.providers.firstOrNull { it.id == reorderId }
                if (moving != null) {
                    val pos = settings.providers.indexOf(moving)
                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${moving.name} · ${pos + 1}/${settings.providers.size}", color = palette.fg, fontFamily = mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            SmallAction("‹ EARLIER", pos > 0, palette) { settings.moveProvider(moving.id, -1) }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("LATER ›", pos < settings.providers.size - 1, palette) { settings.moveProvider(moving.id, 1) }
                            Spacer(Modifier.width(8.dp))
                            SmallAction("DONE", true, palette) { reorderId = null }
                        }
                    }
                }

                // New custom provider
                if (adding) {
                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Field("PROVIDER NAME", newName, palette) { newName = it }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                SmallAction("SAVE", newName.isNotBlank(), palette) {
                                    val id = settings.addCustomProvider(newName)
                                    newName = ""
                                    adding = false
                                    select(id)
                                }
                                Spacer(Modifier.width(8.dp))
                                SmallAction("CANCEL", true, palette) { adding = false; newName = "" }
                            }
                        }
                    }
                }

                // Selected provider details (hidden until a provider is tapped)
                if (selected != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Card(palette) {
                            Column(Modifier.alpha(if (selected.locked) 0.45f else 1f).padding(horizontal = 18.dp, vertical = 14.dp)) {
                                if (selected.custom) {
                                    Field("NAME", selected.name, palette) { v -> settings.updateProvider(selected.id) { it.copy(name = v.uppercase()) } }
                                    Spacer(Modifier.height(14.dp))
                                }
                                Field("API KEY", selected.key, palette, secret = true) { v -> settings.updateProvider(selected.id) { it.copy(key = v) } }
                                Spacer(Modifier.height(14.dp))
                                Field("ADDRESS", selected.baseUrl, palette, keyboard = KeyboardType.Uri) { v -> settings.updateProvider(selected.id) { it.copy(baseUrl = v) } }
                                Spacer(Modifier.height(14.dp))
                                Text("MODEL", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = selected.model,
                                        onValueChange = { v -> settings.updateProvider(selected.id) { it.copy(model = v) } },
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
                                        color = palette.fg, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp,
                                        modifier = Modifier
                                            .border(BorderStroke(1.dp, palette.muted), RoundedCornerShape(50))
                                            .clickable {
                                                if (!fetching) {
                                                    fetching = true
                                                    fetchNote = ""
                                                    val target = selected
                                                    scope.launch {
                                                        onFetchModels(target).onSuccess {
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
                        if (selected.locked) Blocker(Modifier.matchParentSizeCompat(this))
                    }

                    if (models.isNotEmpty() && !selected.locked) {
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
                                        val picked = id == selected.model.trim()
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { settings.updateProvider(selected.id) { it.copy(model = id) } }
                                                .padding(horizontal = 6.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // The whole name wraps onto more lines instead of being cut off.
                                            Text(
                                                id,
                                                color = if (picked) palette.fg else palette.muted,
                                                fontFamily = mono,
                                                fontSize = 11.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (picked) Text("●", color = palette.red, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Lock / delete
                    Spacer(Modifier.height(12.dp))
                    Card(palette) {
                        ToggleRow(
                            "Lock this provider",
                            "Keeps its key, address and model from being edited by accident",
                            selected.locked,
                            palette
                        ) { v -> settings.updateProvider(selected.id) { it.copy(locked = v) } }
                        if (selected.custom && !selected.locked) {
                            Divider(palette)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val id = selected.id
                                    select(null)
                                    settings.removeProvider(id)
                                }.padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text("Delete this provider", color = palette.red, fontSize = 15.sp)
                            }
                        }
                    }
                    if (!selected.locked && alwaysThinks(selected) && !settings.aiThinking) {
                        Spacer(Modifier.height(8.dp))
                        Text("THIS MODEL REASONS EVEN WITH THINKING OFF. PICK A FASTER ONE FOR QUICKER ANSWERS.", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))
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
                    if (readyCount == 0) "NO PROVIDER READY. CHAT USES BASIC REPLIES." else "$readyCount READY. TRIED IN ORDER, FIRST TO LAST.",
                    color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
                )

                if (selected != null && selected.ready) {
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        text = if (testing) "TESTING…" else "TEST ${selected.name}",
                        filled = false,
                        palette = palette,
                        onClick = {
                            if (!testing) {
                                testing = true
                                testResult = ""
                                val target = selected
                                scope.launch {
                                    testResult = onTestProvider(target)
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
                if (readyCount > 0) {
                    Spacer(Modifier.height(14.dp))
                    Card(palette) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onOpenPersona).padding(horizontal = 18.dp, vertical = 16.dp),
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
            if (!on) Blocker(Modifier.matchParentSizeCompat(this))
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Swallows taps so nothing underneath can be touched. Scrolling still passes through. */
@Composable
private fun Blocker(modifier: Modifier) {
    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    )
}

private fun Modifier.matchParentSizeCompat(scope: androidx.compose.foundation.layout.BoxScope): Modifier =
    with(scope) { this@matchParentSizeCompat.matchParentSize() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderChip(
    text: String,
    selected: Boolean,
    ready: Boolean,
    palette: GlyphPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) palette.bg else palette.fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .alpha(if (ready || selected) 1f else 0.38f) // greyed out until key, address and model are filled in
            .then(
                if (selected) Modifier.background(palette.fg, shape)
                else Modifier.border(BorderStroke(1.dp, palette.muted), shape)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun SmallAction(text: String, enabled: Boolean, palette: GlyphPalette, onClick: () -> Unit) {
    Text(
        text,
        color = palette.fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .border(BorderStroke(1.dp, palette.muted), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

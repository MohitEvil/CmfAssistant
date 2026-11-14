package com.demon.cmfassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Temporary debug window: the app's own log, the system log, and the downloaded voice files. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LogScreen(palette: GlyphPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("APP") }
    var other by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun load(which: String) {
        tab = which
        if (which == "APP") return
        other = "Loading..."
        scope.launch {
            other = withContext(Dispatchers.IO) {
                if (which == "SYSTEM") AppLog.systemLog() else AppLog.voiceFiles(context)
            }
        }
    }

    val version = AppLog.version // read so the app log refreshes as lines arrive
    val text = if (tab == "APP") AppLog.snapshot().joinToString("\n") else other
    LaunchedEffect(version, tab) {
        if (tab == "APP") scroll.scrollTo(scroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = palette.fg, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onClose).padding(end = 12.dp))
            Text("DEBUG LOG", color = palette.fg, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("APP", tab == "APP", palette) { load("APP") }
            Chip("SYSTEM", tab == "SYSTEM", palette) { load("SYSTEM") }
            Chip("FILES", tab == "FILES", palette) { load("FILES") }
            Chip("COPY", false, palette) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CMF log", text))
            }
            Chip("CLEAR", false, palette) { AppLog.clear(); if (tab != "APP") other = "" }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "APP shows what the app did (kept even after a crash). SYSTEM shows engine and crash lines. FILES shows downloaded voice files and sizes.",
            color = palette.muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Text(
                text.ifBlank { "(empty)" },
                color = palette.fg,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(scroll)
            )
        }
    }
}

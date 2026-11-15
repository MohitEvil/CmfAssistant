package com.demon.cmfassistant.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private class PickApp(val label: String, val pkg: String)

/** A settings row that shows the chosen app and opens a list of installed apps to pick from. */
@Composable
internal fun AppPickRow(title: String, subtitle: String, current: String, palette: GlyphPalette, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val apps = remember(open) {
        if (!open) emptyList() else runCatching {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { PickApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
    val label = remember(current) {
        if (current.isBlank()) "Not set" else runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(current, 0)).toString() }.getOrDefault(current)
    }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.fg, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Text(label.uppercase(), color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(24.dp)).padding(vertical = 12.dp)
            ) {
                Text(title.uppercase(), color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyColumn(Modifier.height(420.dp)) {
                    item {
                        Text("None (use the usual way)", color = palette.fg, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(""); open = false }.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                    items(apps, key = { it.pkg }) { a ->
                        Text(
                            a.label,
                            color = if (a.pkg == current) palette.fg else palette.muted,
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(a.pkg); open = false }.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

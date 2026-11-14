package com.demon.cmfassistant.ui

import android.Manifest
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.demon.cmfassistant.action.SystemActions

private class AccessItem(
    val title: String,
    val why: String,
    val granted: () -> Boolean,
    val grant: () -> Unit
)

/** One place to grant everything the phone controls need. Each row shows its state and opens the right Android page. */
@Composable
fun AccessScreen(
    system: SystemActions,
    palette: GlyphPalette,
    onRequestPermission: (String) -> Unit,
    onOpenIntent: (android.content.Intent) -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val ctx: Context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val pkg = ctx.packageName
    val items = listOf(
        AccessItem("Modify system settings", "Brightness, rotation, screen timeout",
            { system.canWriteSettings() },
            { onOpenIntent(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$pkg"))) }),
        AccessItem("Do Not Disturb access", "Do Not Disturb, silent mode, ring volume",
            { system.hasDndAccess() },
            { onOpenIntent(android.content.Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")) }),
        AccessItem("Device admin (lock only)", "“Lock the phone”. Cannot wipe or change passwords.",
            { system.isAdmin() },
            {
                onOpenIntent(
                    android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, android.content.ComponentName(ctx, com.demon.cmfassistant.service.AdminReceiver::class.java))
                )
            }),
        AccessItem("Accessibility (screen control)", "Tap, scroll and toggle Wi-Fi, Bluetooth etc. in the background",
            { com.demon.cmfassistant.access.CmfAccess.isEnabled(ctx) },
            { onOpenIntent(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
        AccessItem("Notification access", "Read and clear your notifications by voice",
            { system.hasNotificationAccess() },
            { onOpenIntent(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }),
        AccessItem("Contacts", "Call and message by name",
            { system.has(Manifest.permission.READ_CONTACTS) }, { onRequestPermission(Manifest.permission.READ_CONTACTS) }),
        AccessItem("Calendar", "“What's on my calendar today?”",
            { system.has(Manifest.permission.READ_CALENDAR) }, { onRequestPermission(Manifest.permission.READ_CALENDAR) }),
        AccessItem("Microphone", "Listening to you",
            { system.has(Manifest.permission.RECORD_AUDIO) }, { onRequestPermission(Manifest.permission.RECORD_AUDIO) }),
        AccessItem("Display over other apps", "The floating bubble",
            { android.provider.Settings.canDrawOverlays(ctx) },
            { onOpenIntent(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$pkg"))) })
    )

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
        DotText("ACCESS", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Android keeps these behind special switches. Grant only what you want the assistant to control. " +
                "If you ask for something that needs one, it opens the right page for you.",
            color = palette.muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        Card(palette) {
            // Reading `tick` here re-runs every check when the screen comes back from Android's pages.
            val refresh = tick
            items.forEachIndexed { i, it ->
                if (i > 0) Divider(palette)
                val ok = refresh >= 0 && it.granted()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !ok) { it.grant() }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(it.title, color = palette.fg, fontSize = 15.sp)
                        Text(it.why, color = palette.muted, fontSize = 12.sp)
                    }
                    Text(
                        if (ok) "ON" else "GRANT",
                        color = if (ok) palette.muted else palette.red,
                        fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "NOT POSSIBLE WITHOUT ROOT: toggling Wi-Fi, Bluetooth, mobile data or airplane mode directly, " +
                "silent screenshots, factory reset. The assistant opens the right panel for those instead.",
            color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(32.dp))
    }
}

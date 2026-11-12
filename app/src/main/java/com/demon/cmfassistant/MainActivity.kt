package com.demon.cmfassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demon.cmfassistant.core.AssistantState
import com.demon.cmfassistant.core.AssistantController
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.data.ThemeMode
import com.demon.cmfassistant.service.OverlayService
import com.demon.cmfassistant.ui.AssistantScreen
import com.demon.cmfassistant.ui.DarkPalette
import com.demon.cmfassistant.ui.LightPalette
import com.demon.cmfassistant.ui.PersonaScreen
import com.demon.cmfassistant.ui.VoiceScreen
import com.demon.cmfassistant.ui.SettingsScreen
import com.demon.cmfassistant.ui.rememberPerfMode

class MainActivity : ComponentActivity() {

    private val controller: AssistantController get() = (application as CmfApp).controller
    private val settings: Settings get() = (application as CmfApp).settings

    /** Set when the bubble or the assist gesture launched us: start listening as soon as the mic is allowed. */
    private var autoStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        autoStart = shouldAutoStart(intent)

        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldAutoStart(intent)) autoStart = true
    }

    override fun onStart() {
        super.onStart()
        // The bubble is a foreground service; bring it back if the user wants it and the app was restarted.
        if (settings.bubble && android.provider.Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.onUiHidden()
    }

    private fun shouldAutoStart(i: Intent?): Boolean =
        i?.getBooleanExtra(OverlayService.EXTRA_AUTO_START, false) == true || i?.action == Intent.ACTION_ASSIST

    @Composable
    private fun Root() {
        val ui by controller.ui.collectAsStateWithLifecycle()
        var showSettings by remember { mutableStateOf(false) }
        var showPersona by remember { mutableStateOf(false) }
        var showVoice by remember { mutableStateOf(false) }

        val dark = when (settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        val palette = if (dark) DarkPalette else LightPalette
        val perf = rememberPerfMode(settings)

        // Keep the screen awake only while the assistant is actively working.
        LaunchedEffect(ui.state) {
            val busy = ui.state != AssistantState.IDLE
            if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller.startListening() // starts if granted, otherwise shows the permission message
        }
        val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

        // First launch: ask for notifications (needed to show the bubble service) once.
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        fun startOrAsk() {
            if (controller.hasMicPermission()) controller.startListening()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        LaunchedEffect(autoStart) {
            if (autoStart) {
                autoStart = false
                showSettings = false
                if (ui.state == AssistantState.IDLE || ui.state == AssistantState.SUCCESS || ui.state == AssistantState.FAILURE) {
                    startOrAsk()
                }
            }
        }

        // Contacts permission is requested lazily, the first time a command fails because of it.
        LaunchedEffect(ui.status) {
            if (ui.state == AssistantState.FAILURE && ui.status.startsWith("Allow Contacts")) {
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        BackHandler(enabled = showSettings || showPersona || showVoice || ui.state != AssistantState.IDLE) {
            when {
                showVoice -> showVoice = false
                showPersona -> showPersona = false
                showSettings -> showSettings = false
                else -> controller.cancel()
            }
        }

        if (showVoice) {
            VoiceScreen(
                settings = settings,
                voice = controller.voice,
                palette = palette,
                onClose = { showVoice = false }
            )
        } else if (showPersona) {
            PersonaScreen(
                settings = settings,
                palette = palette,
                onTestAi = controller::testAi,
                onClose = { showPersona = false }
            )
        } else if (showSettings) {
            SettingsScreen(
                settings = settings,
                palette = palette,
                perf = perf,
                onBubbleToggle = { on ->
                    if (on) {
                        if (android.provider.Settings.canDrawOverlays(this)) {
                            settings.setBubble(true)
                            OverlayService.start(this)
                        } else {
                            startActivity(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    } else {
                        settings.setBubble(false)
                        OverlayService.stop(this)
                    }
                },
                onOpenAppPermissions = {
                    startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                },
                onTestAi = controller::testAi,
                onOpenPersona = { showPersona = true },
                onOpenVoice = { showVoice = true },
                onClose = { showSettings = false }
            )
        } else {
            AssistantScreen(
                ui = ui,
                amplitude = controller.amplitude,
                palette = palette,
                perf = perf,
                onGlyphTap = {
                    when (ui.state) {
                        AssistantState.IDLE, AssistantState.SUCCESS, AssistantState.FAILURE -> startOrAsk()
                        else -> controller.onGlyphTap()
                    }
                },
                onCancel = controller::cancel,
                onConfirm = controller::confirmPending,
                onDismissConfirm = controller::dismissPending,
                onOpenSettings = { showSettings = true }
            )
        }
    }
}

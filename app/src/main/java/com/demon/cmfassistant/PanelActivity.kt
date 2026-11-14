package com.demon.cmfassistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demon.cmfassistant.core.AssistantController
import com.demon.cmfassistant.core.AssistantState
import com.demon.cmfassistant.data.Settings
import com.demon.cmfassistant.data.ThemeMode
import com.demon.cmfassistant.ui.DarkPalette
import com.demon.cmfassistant.ui.LightPalette
import com.demon.cmfassistant.ui.PanelScreen
import com.demon.cmfassistant.ui.rememberPerfMode

/**
 * The small bubble panel. It is a transparent activity, so the app you were using stays visible behind it.
 * It starts listening right away and closes itself when the conversation ends.
 */
class PanelActivity : ComponentActivity() {

    private val controller: AssistantController get() = (application as CmfApp).controller
    private val settings: Settings get() = (application as CmfApp).settings

    private var autoStart by mutableStateOf(true)

    /** True while we are handing the conversation to the full app, so leaving doesn't cancel it. */
    private var handOff = false

    companion object {
        @Volatile
        var current: PanelActivity? = null
            private set

        /** The floating bubble was tapped while the panel is open: stop listening and close it. Returns true if it closed one. */
        fun closeIfOpen(): Boolean {
            val p = current ?: return false
            if (p.isFinishing || p.isDestroyed) return false
            p.controller.cancel()
            p.finish()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeToMain()) return
        current = this
        enableEdgeToEdge()
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (routeToMain()) return
        autoStart = true
    }

    /** If the full app is already on screen, listen there instead of stacking the panel on top of it. */
    private fun routeToMain(): Boolean {
        if (!MainActivity.resumed) return false
        handOff = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
        return true
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!handOff) controller.onUiHidden()
    }

    @Composable
    private fun Root() {
        val ui by controller.ui.collectAsStateWithLifecycle()
        val dark = when (settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        val palette = if (dark) DarkPalette else LightPalette
        val perf = rememberPerfMode(settings)
        var seenActive by remember { mutableStateOf(false) }

        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller.startListening() // starts if granted, otherwise shows the permission message
        }

        fun startOrAsk() {
            if (controller.hasMicPermission()) controller.startListening()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        LaunchedEffect(autoStart) {
            if (autoStart) {
                autoStart = false
                if (ui.state == AssistantState.IDLE || ui.state == AssistantState.SUCCESS || ui.state == AssistantState.FAILURE) {
                    startOrAsk()
                }
            }
        }

        // Close once the conversation is over (back to idle after having been active).
        LaunchedEffect(ui.state) {
            if (ui.state != AssistantState.IDLE) seenActive = true
            else if (seenActive) finish()
        }

        BackHandler {
            controller.cancel()
            finish()
        }

        PanelScreen(
            ui = ui,
            amplitude = controller.amplitude,
            palette = palette,
            perf = perf,
            onTapLine = {
                when (ui.state) {
                    AssistantState.IDLE, AssistantState.SUCCESS, AssistantState.FAILURE -> startOrAsk()
                    else -> controller.onGlyphTap()
                }
            },
            onConfirm = controller::confirmPending,
            onDismissConfirm = controller::dismissPending,
            onExpand = {
                handOff = true
                startActivity(
                    Intent(this@PanelActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            },
            onClose = {
                controller.cancel()
                finish()
            }
        )
    }
}

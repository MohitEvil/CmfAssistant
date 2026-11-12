package com.demon.cmfassistant.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.cmfassistant.audio.InstallPhase
import com.demon.cmfassistant.audio.InstallState
import com.demon.cmfassistant.audio.NeuralVoice
import com.demon.cmfassistant.audio.VoiceCatalog
import com.demon.cmfassistant.audio.VoiceHub
import com.demon.cmfassistant.data.Settings

/** Offline voice: pick an engine, download a voice once, choose the speaker and speed. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceScreen(
    settings: Settings,
    voice: VoiceHub,
    palette: GlyphPalette,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val neural = voice.neural
    val selected = VoiceCatalog.byId(settings.voiceModelId)

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
        DotText("VOICE", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Download a voice once and it works fully offline, with no internet needed after that. " +
                "Hindi in Devanagari still uses your phone's own voice.",
            color = palette.muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("ENGINE", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("NEURAL (OFFLINE)", settings.voiceEngine == "neural", palette) {
                settings.setVoiceEngine("neural")
                selected?.let { if (voice.manager.isInstalled(it)) voice.useModel(it) }
            }
            Chip("PHONE VOICE", settings.voiceEngine == "system", palette) { voice.useDeviceVoice() }
        }

        if (!neural.libraryPresent) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The neural engine isn't part of this build, so the phone voice is used. " +
                    "Build with the included GitHub workflow (or run scripts/fetch-sherpa.sh first) to add it.",
                color = palette.red,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("VOICES", palette)
        VoiceCatalog.all.forEach { model ->
            val state = voice.manager.states[model.id] ?: InstallState(InstallPhase.NOT_INSTALLED)
            val inUse = settings.voiceEngine == "neural" && settings.voiceModelId == model.id
            Card(palette) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, color = palette.fg, fontFamily = mono, fontSize = 13.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                inUse && neural.phase == NeuralVoice.Phase.READY -> "● IN USE"
                                inUse && neural.phase == NeuralVoice.Phase.LOADING -> "LOADING…"
                                inUse && neural.phase == NeuralVoice.Phase.ERROR -> "ERROR"
                                state.phase == InstallPhase.INSTALLED -> "INSTALLED"
                                else -> model.sizeLabel.uppercase()
                            },
                            color = if (inUse && neural.phase == NeuralVoice.Phase.READY) palette.red else palette.muted,
                            fontFamily = mono,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(model.blurb, color = palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))

                    when (state.phase) {
                        InstallPhase.NOT_INSTALLED, InstallPhase.FAILED -> {
                            if (state.phase == InstallPhase.FAILED) {
                                Text(state.message, color = palette.red, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                            }
                            PillButton(
                                if (state.phase == InstallPhase.FAILED) "TRY AGAIN" else "DOWNLOAD (${model.sizeLabel.uppercase()})",
                                filled = true,
                                palette = palette,
                                onClick = { voice.manager.download(model) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        InstallPhase.DOWNLOADING -> {
                            val pct = (state.progress * 100).toInt()
                            Text(
                                if (state.progress > 0f) "DOWNLOADING $pct%" else "DOWNLOADING…",
                                color = palette.fg,
                                fontFamily = mono,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            ProgressBar(state.progress, palette)
                            Spacer(Modifier.height(10.dp))
                            PillButton("CANCEL", filled = false, palette = palette, onClick = { voice.manager.cancel(model) }, modifier = Modifier.fillMaxWidth())
                        }
                        InstallPhase.INSTALLING -> Text(
                            "UNPACKING… ONE MOMENT",
                            color = palette.fg,
                            fontFamily = mono,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        InstallPhase.INSTALLED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                if (inUse) "IN USE" else "USE THIS VOICE",
                                filled = !inUse,
                                palette = palette,
                                onClick = { if (!inUse) voice.useModel(model) },
                                modifier = Modifier.weight(1f)
                            )
                            PillButton("DELETE", filled = false, palette = palette, onClick = { voice.deleteModel(model) })
                        }
                    }

                    if (inUse && neural.phase == NeuralVoice.Phase.ERROR) {
                        Spacer(Modifier.height(8.dp))
                        Text("Couldn't start this voice: ${neural.error}. The phone voice is used instead.", color = palette.red, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (selected != null && settings.voiceEngine == "neural" && voice.manager.isInstalled(selected)) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("SPEAKER", palette)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                selected.voices.forEach { (name, sid) ->
                    Chip(name.uppercase(), settings.voiceSid == sid, palette) {
                        settings.setVoiceSid(sid)
                        voice.speak("Hi, this is how I sound.") {}
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("SPEED", palette)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("RELAXED" to 0.9f, "NORMAL" to 1.0f, "QUICK" to 1.15f).forEach { (name, v) ->
                    Chip(name, settings.voiceSpeed == v, palette) {
                        settings.setVoiceSpeed(v)
                        voice.speak("Hey, this is my speed.") {}
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        PillButton(
            "TEST VOICE",
            filled = true,
            palette = palette,
            onClick = { voice.speak("Hey! This is how I sound. Pretty natural, right?") {} },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ProgressBar(progress: Float, palette: GlyphPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(palette.dim, androidx.compose.foundation.shape.RoundedCornerShape(50))
    ) {
        if (progress > 0f) {
            Spacer(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(palette.red, androidx.compose.foundation.shape.RoundedCornerShape(50))
            )
        }
    }
}

package com.armsx2.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.ui.InGameOverlay

/**
 * SPU2 audio output settings. Volume + mute apply live to the open audio
 * stream (NativeApp.setAudioVolume / setAudioMuted) and persist via ConfigStore.
 */
@Composable
fun AudioTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)

    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            str("audio.header.description"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        IntSliderRow(
            label = str("audio.volume.label"),
            value = s.audioVolume.coerceIn(0, 150),
            min = 0,
            max = 150,
            description = str("audio.volume.description"),
            valueFormatter = { "$it%" },
            onChange = { apply(s.copy(audioVolume = it)) },
        )
        SettingsDivider()
        ToggleRow(str("audio.mute.label"), s.audioMuted) { apply(s.copy(audioMuted = it)) }
        SettingsDivider()
        ToggleRow(
            str("audio.synchronization.label"),
            s.ps3.audioTimeStretch,
            description = str("audio.synchronization.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(audioTimeStretch = it))) }
        SettingsDivider()
        IntSliderRow(
            label = str("audio.buffer.label"),
            value = s.ps3.audioBufferMs.coerceIn(4, 250),
            min = 4,
            max = 250,
            description = str("audio.buffer.description"),
            valueFormatter = { "$it ms" },
            onChange = { apply(s.copy(ps3 = s.ps3.copy(audioBufferMs = it))) },
        )
        SettingsDivider()
        // Audio backend. Only Cubeb and Null are real on Android -- XAudio2 is
        // Windows-only and FAudio is not built here -- so offering the other two
        // would just be a way to silently kill audio.
        // Indices here are positions in Rpcs3Settings.AUDIO_RENDERERS: Cubeb=2, Oboe=4, Null=0.
        val rendererIndices = listOf(2, 4, 0)
        SegmentedRow(
            label = str("audio.renderer.label"),
            options = listOf("Cubeb", "Oboe", str("common.off")),
            selectedIndex = rendererIndices.indexOf(s.ps3.audioRenderer).coerceAtLeast(0),
            description = str("audio.renderer.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(audioRenderer = rendererIndices[it]))) },
        )
        SettingsDivider()
        SegmentedGridRow(
            label = str("audio.format.label"),
            options = listOf("Stereo", "5.1", "7.1", str("common.auto")),
            selectedIndex = s.ps3.audioFormat.coerceIn(0, 3),
            columns = 4,
            description = str("audio.format.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(audioFormat = it))) },
        )
        SettingsDivider()
        SegmentedGridRow(
            label = str("audio.channels.label"),
            options = listOf(str("common.auto"), "Mono", "Stereo", "5.1", "7.1"),
            // Config ordinals are Automatic, Mono, Stereo, Stereo LFE,
            // Quadraphonic, Quadraphonic LFE, Surround 5.1, Surround 7.1. Only
            // the useful ones are shown, so map visible index -> real ordinal.
            selectedIndex = when (s.ps3.audioChannels) { 1 -> 1; 2 -> 2; 6 -> 3; 7 -> 4; else -> 0 },
            columns = 5,
            description = str("audio.channels.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(audioChannels = intArrayOf(0, 1, 2, 6, 7)[it]))) },
        )
        SettingsDivider()
        // The replacement for PCSX2's OpenSL ES toggle, which was removed from here on the
        // reasoning that "RPCS3 picks its backend via Audio Renderer". That is not the same knob:
        // Audio Renderer chooses Cubeb or Null, while THIS chooses which backend cubeb then talks
        // to. Android builds all three, and cubeb's auto order takes AAudio on anything modern, so
        // OpenSL had quietly become unreachable rather than superseded.
        //
        // It matters because AAudio's low-latency path takes the smallest buffers the device will
        // grant, and those are the first thing to underrun once the emulator drops below full
        // speed -- the audio stutter reported on low-end Mali devices, which faster hardware never
        // shows. OpenSL trades latency for buffers that are much harder to starve.
        SegmentedGridRow(
            label = str("audio.cubebBackend.label"),
            options = listOf(str("common.auto"), "AAudio", "OpenSL", "AudioTrack"),
            selectedIndex = s.ps3.audioCubebBackend.coerceIn(0, 3),
            columns = 4,
            description = str("audio.cubebBackend.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(audioCubebBackend = it))) },
        )
        // Removed: SPU2 is the PS2's sound chip; its NEON reverb path does not exist here.
        // Removed: PCSX2 SPU2 lightweight mixing mode. No RPCS3 counterpart.
    }
}

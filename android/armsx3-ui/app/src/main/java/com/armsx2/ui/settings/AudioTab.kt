package com.armsx2.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.input.MicDevices
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.armsx3.core.Permission

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
        ToggleRow(
            str("audio.recordingcompat.label"),
            s.ps3.audioRecordingCompat,
            description = str("audio.recordingcompat.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(audioRecordingCompat = it))) }
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
        // Cubeb only. g_cfg.audio.cubeb_backend is read once, in CubebBackend's constructor, so
        // under Oboe this row is inert -- yet it stayed interactive and kept showing a selection,
        // which is how issue #73 produced "I tried OpenSL too" as a meaningful-looking result.
        if (s.ps3.audioRenderer == 2) {
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
        }
        // Removed: SPU2 is the PS2's sound chip; its NEON reverb path does not exist here.
        // Removed: PCSX2 SPU2 lightweight mixing mode. No RPCS3 counterpart.

        // Only where the build declares RECORD_AUDIO, which both flavors do
        val context = LocalContext.current
        if (remember { MicDevices.supported(context) }) {
            MicrophoneSection(s) { apply(it) }
        }
    }
}

/**
 * The microphone a game is given (cellMic, captured through Oboe): the kind, then which device.
 *
 * Android lists the devices to Java only (MicDevices). Each is stored by its kind and product name,
 * so the choice survives unplugging it; one that is not plugged in stays listed as not connected.
 */
@Composable
private fun MicrophoneSection(s: Settings, apply: (Settings) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(MicDevices.granted(context)) }
    var devices by remember { mutableStateOf(MicDevices.list(context)) }

    // Back from the permission prompt or Android settings, or after plugging a microphone in
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = MicDevices.granted(context)
        devices = MicDevices.list(context)
    }

    val ask = {
        MainActivityRuntime.instance?.let { Permission.RecordAudio.requestPermission(it) }
        Unit
    }

    SettingsDivider()
    SegmentedGridRow(
        label = str("audio.mic.label"),
        options = listOf(str("common.off"), str("audio.mic.standard"), "SingStar", "Real SingStar", "Rocksmith"),
        selectedIndex = s.ps3.micType.coerceIn(0, 4),
        columns = 3,
        description = str("audio.mic.description"),
        onChange = {
            apply(s.copy(ps3 = s.ps3.copy(micType = it)))
            if (it != 0 && !allowed) ask()
        },
    )

    if (s.ps3.micType == 0) return

    if (!allowed) {
        SettingsDivider()
        MicActionRow("audio.mic.allow", str("audio.mic.allow"), str("audio.mic.allow.description"), ask)
        // Android stops showing the prompt once it has been dismissed twice
        MicActionRow("audio.mic.settings", str("audio.mic.settings"), str("audio.mic.settings.description")) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val noneLabel = str("audio.mic.none")
    val automaticLabel = str("audio.mic.automatic")
    val builtinLabel = str("audio.mic.builtin")
    val notConnectedLabel = str("audio.mic.notConnected")

    // Automatic first, then what is plugged in, then a saved one that is not
    fun options(saved: String, withNone: Boolean): Pair<List<String>, List<String>> {
        val names = mutableListOf<String>()
        val labels = mutableListOf<String>()

        if (withNone) {
            names += ""
            labels += noneLabel
        }

        names += MicDevices.DEFAULT
        labels += automaticLabel

        for (device in devices) {
            names += device.name
            labels += if (device.name == MicDevices.BUILTIN) builtinLabel else device.product
        }

        if (saved !in names) {
            names += saved
            labels += notConnectedLabel.format(saved.substringAfter(':'))
        }

        return names to labels
    }

    val (names1, labels1) = options(s.ps3.micDevice1, withNone = false)
    SettingsDivider()
    SegmentedGridRow(
        label = str(if (s.ps3.micType == 2) "audio.mic.singer1" else "audio.mic.device"),
        options = labels1,
        selectedIndex = names1.indexOf(s.ps3.micDevice1).coerceAtLeast(0),
        columns = 1,
        description = str("audio.mic.device.description"),
        onChange = { apply(s.copy(ps3 = s.ps3.copy(micDevice1 = names1[it]))) },
    )

    if (allowed) {
        MicTestRow(s.ps3.micDevice1)
    }

    // SingStar: one microphone per singer
    if (s.ps3.micType == 2) {
        val (names2, labels2) = options(s.ps3.micDevice2, withNone = true)
        SettingsDivider()
        SegmentedGridRow(
            label = str("audio.mic.singer2"),
            options = labels2,
            selectedIndex = names2.indexOf(s.ps3.micDevice2).coerceAtLeast(0),
            columns = 1,
            onChange = { apply(s.copy(ps3 = s.ps3.copy(micDevice2 = names2[it]))) },
        )
    }
}

/**
 * Hears the chosen microphone through the same capture a game gets (oboe_capture in the core), with a
 * bar for how loud it is: if the bar moves, a game is sent the same sound.
 */
@Composable
private fun MicTestRow(device: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }

    // Never left recording: leaving the screen or the app ends the test
    DisposableEffect(Unit) { onDispose { Rpcs3Bridge.micTestStop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        Rpcs3Bridge.micTestStop()
        testing = false
    }

    LaunchedEffect(testing) {
        while (testing) {
            val now = withContext(Dispatchers.IO) { Rpcs3Bridge.micTestLevel() }
            if (now < 0f) break
            level = now
            delay(60)
        }
        level = 0f
    }

    val toggle: () -> Unit = {
        if (testing) {
            Rpcs3Bridge.micTestStop()
            testing = false
        } else {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    MicDevices.push(context)
                    Rpcs3Bridge.micTestStart(device)
                }
                failed = !ok
                testing = ok
            }
        }
    }

    SettingsDivider()
    MicActionRow(
        "audio.mic.test",
        str(if (testing) "audio.mic.test.stop" else "audio.mic.test"),
        str(if (failed) "audio.mic.test.failed" else "audio.mic.test.description"),
        toggle,
    )

    if (testing) {
        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp).height(10.dp),
        )
    }
}

/** A tappable label and subtitle, reachable with the pad like the shared rows. */
@Composable
private fun MicActionRow(controllerId: String, label: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = onClick)
            .controllerFocusable(controllerId, onConfirm = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

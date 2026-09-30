package com.armsx2.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import net.rpcsx.RPCSX

/**
 * Frame generation. Its own section rather than folded into the GPU one because it is not a
 * rendering option -- it inserts frames that the game never drew, and the choice to do that is a
 * different kind of decision from how the real ones are drawn.
 *
 * Github build only, like its strings and the library it drives: none of it ships through the
 * store. The play build has a note in its place (src/play).
 */
@Composable
internal fun FrameGenSection(s: Settings, apply: (Settings) -> Unit) {
    CollapsibleSection(str("perf.framegen.title")) {
        SegmentedGridRow(
            label = str("perf.framegen.label"),
            options = listOf(
                str("perf.framegen.off"),
                str("perf.framegen.x2"),
                str("perf.framegen.x3"),
                str("perf.framegen.x4"),
            ),
            selectedIndex = s.ps3.frameGeneration.coerceIn(0, 3),
            columns = 4,
            description = str("perf.framegen.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(frameGeneration = it))) },
            notDefault = s.notDefault { it.ps3.frameGeneration },
        )
        SettingsDivider()
        ToggleRow(
            str("perf.framegen.performance.label"),
            s.ps3.frameGenPerformance,
            description = str("perf.framegen.performance.description"),
            notDefault = s.notDefault { it.ps3.frameGenPerformance },
        ) { apply(s.copy(ps3 = s.ps3.copy(frameGenPerformance = it))) }
        SettingsDivider()
        IntSliderRow(
            label = str("perf.framegen.flowScale.label"),
            value = s.ps3.frameGenFlowScale.coerceIn(25, 100),
            min = 25,
            max = 100,
            description = str("perf.framegen.flowScale.description"),
            valueFormatter = { "$it%" },
            onChange = { apply(s.copy(ps3 = s.ps3.copy(frameGenFlowScale = it))) },
            notDefault = s.notDefault { it.ps3.frameGenFlowScale },
        )
        SettingsDivider()
        SettingsDivider()
        SegmentedGridRow(
            label = str("perf.framegen.targetRate.label"),
            options = listOf(str("perf.framegen.off"), "60 Hz", "90 Hz", "120 Hz"),
            selectedIndex = when (s.ps3.frameGenTargetRate) { 60 -> 1; 90 -> 2; 120 -> 3; else -> 0 },
            columns = 4,
            description = str("perf.framegen.targetRate.description"),
            onChange = { idx ->
                val hz = when (idx) { 1 -> 60; 2 -> 90; 3 -> 120; else -> 0 }
                apply(s.copy(ps3 = s.ps3.copy(frameGenTargetRate = hz)))
            },
            notDefault = s.notDefault { it.ps3.frameGenTargetRate },
        )
        SettingsDivider()
        FrameGenShaderRow()
    }
}

/**
 * Import the shaders frame generation needs, and say plainly when they are missing.
 *
 * The picker on its own is a toggle that appears to do nothing: framegen refuses to start without
 * shaders, and nothing in the app ships them. They are THS's property and have to come from a
 * legitimately purchased copy of Lossless Scaling, so the state has to be visible at the point of
 * use rather than explained in a description nobody reads.
 *
 * The file is copied into app storage before extraction rather than read through the content URI:
 * the PE walk is ordinary file IO on the native side and cannot open a content:// path. The copy
 * is deleted afterwards -- only the translated SPIR-V is kept.
 */
@Composable
private fun FrameGenShaderRow() {
    val ctx = LocalContext.current
    var count by remember { mutableStateOf(runCatching { RPCSX.instance.frameGenShaderCount() }.getOrDefault(0)) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Resolved here, not in the callback: str() is @Composable and the picker result arrives
    // outside composition.
    val failedMsg = str("perf.framegen.import.failed")

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            error = ""

            runCatching {
                // Named .dll but the picker filters on */* -- Android has no MIME type for a PE
                // binary and several file providers report octet-stream or nothing at all.
                val tmp = java.io.File(ctx.cacheDir, "Lossless.dll")

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }

                val n = RPCSX.instance.frameGenImportShaders(tmp.absolutePath)
                tmp.delete()

                if (n > 0) {
                    count = n
                } else {
                    error = RPCSX.instance.frameGenShaderError().ifEmpty { failedMsg }
                }
            }.onFailure { error = it.message ?: failedMsg }

            busy = false
        }
    }

    Text(
        when {
            busy -> str("perf.framegen.import.working")
            count > 0 -> str("perf.framegen.import.ok").format(count)
            else -> str("perf.framegen.import.missing")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (count > 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )

    if (error.isNotEmpty()) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val pick = { picker.launch(arrayOf("*/*")) }
        OutlinedButton(
            onClick = pick,
            modifier = Modifier.controllerFocusable("perf.framegen.import", onConfirm = pick),
        ) { Text(str("perf.framegen.import")) }
    }
}

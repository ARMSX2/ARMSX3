package com.armsx2.ui.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str

/** Frame generation in the pause menu. The play build has an empty stand-in (see FrameGenSection). */
@Composable
internal fun FrameGenCard(settings: Settings, viewModel: EmulationMenuViewModel) {
    SectionCard(str("perf.framegen.title")) {
        HorizontalOptions(
            title = str("perf.framegen.label"),
            options = listOf(
                str("perf.framegen.off"), str("perf.framegen.x2"),
                str("perf.framegen.x3"), str("perf.framegen.x4"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.ps3.frameGeneration,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGeneration = v)) } },
        )

        // The rest of frame generation, which until now only existed in the main settings
        // screen. Someone who opens this menu mid-game is here to change exactly these:
        // the multiplier alone cannot answer "it is generating, but the picture is unsteady"
        // (target rate) or "it is generating, but too expensive" (flow scale, performance).
        HorizontalOptions(
            title = str("perf.framegen.targetRate.label"),
            options = listOf(0 to str("perf.framegen.off"), 60 to "60 Hz", 90 to "90 Hz", 120 to "120 Hz"),
            selected = settings.ps3.frameGenTargetRate,
            onSelect = { v ->
                android.util.Log.i("FRAMEGEN", "pause menu: target rate chip -> $v")
                viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGenTargetRate = v)) }
            },
        )
        // Motion detail is continuous, so it gets a slider rather than three stops -- the
        // useful values are wherever the picture stops improving on a given game, not a set
        // someone picked in advance. 25 is the floor the core clamps to.
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    str("perf.framegen.flowScale.label"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${settings.ps3.frameGenFlowScale}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = settings.ps3.frameGenFlowScale.coerceIn(25, 100).toFloat(),
                onValueChange = { v ->
                    viewModel.updateSettings {
                        it.copy(ps3 = it.ps3.copy(frameGenFlowScale = Math.round(v).coerceIn(25, 100)))
                    }
                },
                valueRange = 25f..100f,
            )
        }
        MenuSwitchRow(
            str("perf.framegen.performance.label"),
            settings.ps3.frameGenPerformance,
        ) { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGenPerformance = v)) } }
    }
    Spacer(Modifier.height(10.dp))
}

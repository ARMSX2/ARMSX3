package com.armsx2.ui.emulation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str

/** ZeroFG frame generation in the pause menu, above the experimental one (see ZeroFgSection). */
@Composable
internal fun ZeroFgCard(settings: Settings, viewModel: EmulationMenuViewModel) {
    SectionCard(str("perf.zerofg.title")) {
        // First, above the options: ZeroFG makes its device when the game starts, and the app cannot
        // tell from here whether it did.
        Text(
            str("perf.zerofg.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        HorizontalOptions(
            title = str("perf.zerofg.label"),
            options = listOf(str("perf.zerofg.off"), "x2", "x3", "x4").mapIndexed { index, label -> index to label },
            selected = settings.ps3.zeroFg,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(zeroFg = v)) } },
        )
    }
}

package com.armsx2.ui.emulation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.ui.settings.ZeroFgRestartAsk
import com.armsx2.ui.settings.restartForZeroFg
import com.armsx2.ui.settings.zeroFgNeedsRestart

/**
 * ZeroFG frame generation in the pause menu and the in-game XMB, above the experimental one (see
 * ZeroFgSection). The rule that it starts with the game goes first, and in a game that started
 * without it, switching it on offers Apply & Restart.
 */
@Composable
internal fun ZeroFgCard(settings: Settings, viewModel: EmulationMenuViewModel) {
    val zeroFg = settings.ps3.zeroFg
    val options = listOf(str("perf.zerofg.off"), "x2", "x3", "x4")
    val select: (Int) -> Unit = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(zeroFg = v)) } }
    ZeroFgRestartAsk(zeroFg)
    SectionCard(str("perf.zerofg.title")) {
        // In the in-game XMB the card is a list, and plain text in it is not collected: the rule goes
        // in the setting's description instead.
        com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
            com.armsx2.ui.xmb.XmbCollected(
                c,
                com.armsx2.ui.xmb.XmbNode.Setting(
                    key = "ingame.zerofg",
                    title = str("perf.zerofg.label"),
                    icon = c.icon,
                    editor = com.armsx2.ui.xmb.XmbEditor.Choice(options, { zeroFg.coerceIn(0, 3) }) { i -> select(i) },
                    value = { options.getOrElse(zeroFg) { zeroFg.toString() } },
                    description = str("perf.zerofg.note"),
                ),
            )
            if (zeroFgNeedsRestart(zeroFg)) {
                com.armsx2.ui.xmb.XmbCollected(
                    c,
                    com.armsx2.ui.xmb.XmbNode.Action(
                        "ingame.zerofg.restart", str("backend.applyRestart"), c.icon, str("perf.zerofg.restart"),
                    ) { restartForZeroFg() },
                )
            }
            return@SectionCard
        }
        // First, above the options: ZeroFG makes its device when the game starts.
        Text(
            str("perf.zerofg.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        HorizontalOptions(
            title = str("perf.zerofg.label"),
            options = options.mapIndexed { index, label -> index to label },
            selected = zeroFg,
            onSelect = select,
        )
        if (zeroFgNeedsRestart(zeroFg)) {
            Text(
                str("perf.zerofg.restart"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), ::restartForZeroFg)
        }
    }
}

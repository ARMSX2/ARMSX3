package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.EmuState
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime

/**
 * ZeroFG frame generation, above the experimental (Lossless Scaling) one: Off, or the frames on
 * screen per game frame. It is its own frame generator on its own Vulkan device and needs nothing
 * imported, so it is in every build. While it is on, Lossless Scaling's frame generation stays off.
 * Feature contributed by hy300leosquizz-ctrl (PR #331).
 *
 * ZeroFG makes its device as the renderer starts, so it is switched on before a game starts, or the
 * game is restarted after: the description says so, and during a game that started without it the
 * section offers Apply & Restart (in XMB mode it also asks as it is switched on).
 */
@Composable
internal fun ZeroFgSection(s: Settings, apply: (Settings) -> Unit) {
    ZeroFgRestartAsk(s.ps3.zeroFg)
    CollapsibleSection(str("perf.zerofg.title")) {
        SegmentedGridRow(
            label = str("perf.zerofg.label"),
            options = listOf(str("perf.zerofg.off"), "x2", "x3", "x4"),
            selectedIndex = s.ps3.zeroFg.coerceIn(0, 3),
            columns = 4,
            description = str("perf.zerofg.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(zeroFg = it))) },
            notDefault = s.notDefault { it.ps3.zeroFg },
        )
        if (zeroFgNeedsRestart(s.ps3.zeroFg)) {
            com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
                com.armsx2.ui.xmb.XmbCollected(
                    c,
                    com.armsx2.ui.xmb.XmbNode.Action(
                        "settings.zerofg.restart", str("backend.applyRestart"), c.icon, str("perf.zerofg.restart"),
                    ) { restartForZeroFg() },
                )
                return@CollapsibleSection
            }
            HelpText(str("perf.zerofg.restart"))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = ::restartForZeroFg, shape = RoundedCornerShape(13.dp)) {
                    Text(str("backend.applyRestart"))
                }
            }
        }
    }
}

/**
 * True while a game runs that started with ZeroFG off and [zeroFg] now switches it on: it only starts
 * once the game restarts. Switching it off, or between x2, x3 and x4, applies at once.
 */
internal fun zeroFgNeedsRestart(zeroFg: Int): Boolean =
    zeroFg != 0 && MainActivityRuntime.currentGame.value != null &&
        MainActivityRuntime.eState.value != EmuState.STOPPED && !MainActivityRuntime.zeroFgAtBoot.value

/** Restarts the running game with ZeroFG, from the in-game menus (closed first, as the renderer's is). */
internal fun restartForZeroFg() {
    if (com.armsx2.ui.WindowImpl.overlayVisible.value) com.armsx2.ui.InGameOverlay.toggle()
    MainActivityRuntime.restart()
}

private var zeroFgAskedAtMs = 0L
private const val ASK_ONCE_MS = 1000L

/**
 * In the XMB over a game: switching ZeroFG on in a game that started without it asks straight away
 * whether to restart the game with it, as a change of graphics API does (RendererBackendSection).
 */
@Composable
internal fun ZeroFgRestartAsk(zeroFg: Int) {
    if (com.armsx2.ui.xmb.LocalXmbCollector.current == null || MainActivityRuntime.currentGame.value == null) return
    val asked = remember { mutableStateOf(zeroFg) }
    val title = str("backend.applyRestart")
    val message = str("perf.zerofg.restartAsk")
    val later = str("xmb.later")
    LaunchedEffect(zeroFg) {
        if (zeroFg == asked.value) return@LaunchedEffect
        val before = asked.value
        asked.value = zeroFg
        if (before != 0 || !zeroFgNeedsRestart(zeroFg)) return@LaunchedEffect
        // The in-game card and this Settings section can both be composed in the XMB: ask once.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - zeroFgAskedAtMs < ASK_ONCE_MS) return@LaunchedEffect
        zeroFgAskedAtMs = now
        com.armsx2.ui.xmb.XmbDialogs.target()?.let { x ->
            com.armsx2.ui.xmb.XmbDialogs.ask(x, title, message, title, later, ::restartForZeroFg, {})
        }
    }
}

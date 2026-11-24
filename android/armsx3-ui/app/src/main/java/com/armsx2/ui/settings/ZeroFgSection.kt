package com.armsx2.ui.settings

import androidx.compose.runtime.Composable
import com.armsx2.config.Settings
import com.armsx2.i18n.str

/**
 * ZeroFG frame generation, above the experimental (Lossless Scaling) one: Off, or the frames on
 * screen per game frame. It is its own frame generator on its own Vulkan device and needs nothing
 * imported, so it is in every build. While it is on, Lossless Scaling's frame generation stays off.
 * Feature contributed by hy300leosquizz-ctrl (PR #331).
 */
@Composable
internal fun ZeroFgSection(s: Settings, apply: (Settings) -> Unit) {
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
    }
}

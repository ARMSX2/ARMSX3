package com.armsx2.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.ui.InGameOverlay

/**
 * Hardware / upscaling compatibility fixes — the PCSX2 "Hardware Fixes" and
 * "Upscaling Fixes" panels. Split out of [RendererTab] so Render keeps only
 * core quality/display settings.
 *
 * Every row writes into [Settings] via [InGameOverlay.saveSettings]; on a
 * running VM that reconfigures the GS live (Settings.applyGsLive → native
 * applyGSSettingsLive) so changes show without a restart. Note PCSX2 masks
 * upscaling hacks at native (1x) resolution and masks every UserHacks_* key
 * unless at least one fix is enabled — both are intentional parity behaviours.
 */
@Composable
fun FixesTab(state: MutableState<Settings>) {
    val s = state.value
    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    // Rebuilt from scratch against RPCS3's Core section. Everything that was here
    // -- upscaling fixes, half-pixel offset, sprite rounding, CLUT/palette
    // handling, texture-inside-RT, skipdraw ranges, software renderer threads --
    // describes the PS2's GS and has no counterpart on the PS3. None of it was
    // reachable by the RPCS3 config tree, so all of it did nothing.
    Column(modifier = Modifier.fillMaxWidth()) {
        CollapsibleSection(str("adv.section.cpuAccuracy"), initiallyExpanded = true) {
            HelpText(
                str("adv.section.cpuAccuracy.help"),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            SegmentedGridRow(
                label = str("adv.xfloat.label"),
                options = listOf(
                    str("adv.xfloat.accurate"),
                    str("adv.xfloat.approximate"),
                    str("adv.xfloat.relaxed"),
                    str("adv.xfloat.inaccurate"),
                ),
                selectedIndex = s.ps3.spuXFloat.coerceIn(0, 3),
                columns = 2,
                description = str("adv.xfloat.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(spuXFloat = it))) },
            )
            SettingsDivider()
            ToggleRow(
                str("adv.accurateSpuRsv.label"),
                s.ps3.accurateSpuRsv,
                description = str("adv.accurateSpuRsv.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(accurateSpuRsv = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.accurateCacheLine.label"),
                s.ps3.accurateCacheLine,
                description = str("adv.accurateCacheLine.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(accurateCacheLine = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.accurateRsxRsv.label"),
                s.ps3.accurateRsxRsv,
                description = str("adv.accurateRsxRsv.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(accurateRsxRsv = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.ppuRsvPriority.label"),
                s.ps3.ppuRsvPriority,
                description = str("adv.ppuRsvPriority.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(ppuRsvPriority = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.spuVerification.label"),
                s.ps3.spuVerification,
                description = str("adv.spuVerification.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(spuVerification = it))) }
            SettingsDivider()
            // Distinct from the toggle above: that one chooses whether to verify at all, this one
            // chooses how. The field was already serialised and written to the config tree but had
            // no control, so the only way to reach it was a raw core override.
            ToggleRow(
                str("adv.preciseSpuVerification.label"),
                s.ps3.preciseSpuVerification,
                description = str("adv.preciseSpuVerification.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(preciseSpuVerification = it))) }
        }

        CollapsibleSection(str("adv.section.fpu")) {
            HelpText(
                str("adv.section.fpu.help"),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            ToggleRow(
                str("adv.ppuNan.label"),
                s.ps3.ppuNanHandling,
                description = str("adv.ppuNan.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(ppuNanHandling = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.accurateDfma.label"),
                s.ps3.accurateDfma,
                description = str("adv.accurateDfma.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(accurateDfma = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.dazFtz.label"),
                s.ps3.setDazFtz,
                description = str("adv.dazFtz.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(setDazFtz = it))) }
        }

        CollapsibleSection(str("adv.section.system")) {
            SegmentedGridRow(
                label = str("adv.sleepTimers.label"),
                options = listOf(
                    str("adv.sleepTimers.asHost"),
                    str("adv.sleepTimers.usleep"),
                    str("adv.sleepTimers.all"),
                ),
                selectedIndex = s.ps3.sleepTimers.coerceIn(0, 2),
                columns = 3,
                description = str("adv.sleepTimers.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(sleepTimers = it))) },
            )
            SettingsDivider()
            ToggleRow(
                str("adv.hleLwmutex.label"),
                s.ps3.hleLwmutex,
                description = str("adv.hleLwmutex.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(hleLwmutex = it))) }
            SettingsDivider()
            ToggleRow(
                str("adv.debugConsole.label"),
                s.ps3.debugConsoleMode,
                description = str("adv.debugConsole.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(debugConsoleMode = it))) }
        }

        // What the emulated console tells games about itself.
        //
        // These are not app preferences: a multi-language disc picks its text from the console
        // language, and region is what makes a title behave as its NTSC or PAL self. Option
        // labels come from the core's own enum names (the same strings the All Core Settings
        // screen shows) rather than 60 new translation keys -- they are proper nouns like
        // "SCEA" and keyboard layout names, which are not usefully translated.
        CollapsibleSection(str("adv.section.console")) {
            SegmentedGridRow(
                label = str("adv.consoleLanguage.label"),
                options = com.armsx3.Rpcs3Settings.consoleLanguageNames(),
                selectedIndex = s.ps3.consoleLanguage.coerceIn(0, com.armsx3.Rpcs3Settings.consoleLanguageNames().lastIndex),
                columns = 2,
                description = str("adv.consoleLanguage.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(consoleLanguage = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("adv.consoleRegion.label"),
                options = com.armsx3.Rpcs3Settings.consoleRegionNames(),
                selectedIndex = s.ps3.consoleRegion.coerceIn(0, com.armsx3.Rpcs3Settings.consoleRegionNames().lastIndex),
                columns = 3,
                description = str("adv.consoleRegion.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(consoleRegion = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("adv.keyboardType.label"),
                options = com.armsx3.Rpcs3Settings.keyboardTypeNames(),
                selectedIndex = s.ps3.keyboardType.coerceIn(0, com.armsx3.Rpcs3Settings.keyboardTypeNames().lastIndex),
                columns = 1,
                description = str("adv.keyboardType.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(keyboardType = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("adv.dateFormat.label"),
                options = listOf(
                    str("adv.dateFormat.ymd"),
                    str("adv.dateFormat.dmy"),
                    str("adv.dateFormat.mdy"),
                ),
                selectedIndex = s.ps3.dateFormat.coerceIn(0, 2),
                columns = 3,
                onChange = { apply(s.copy(ps3 = s.ps3.copy(dateFormat = it))) },
            )
            SettingsDivider()
            SegmentedRow(
                label = str("adv.timeFormat.label"),
                options = listOf(str("adv.timeFormat.clock12"), str("adv.timeFormat.clock24")),
                selectedIndex = s.ps3.timeFormat.coerceIn(0, 1),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(timeFormat = it))) },
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

// CollapsibleSection now lives in SettingsWidgets.kt (shared by the Fixes / Pad /
// Performance / Renderer tabs).

/** The former standalone Recompiler tab, folded in as a section. Turning a recompiler off drops
 *  that processor to an interpreter — correct but far slower — so it is a debugging control, not
 *  something to browse past on the way to a speed setting. */
@Composable
private fun RecompilerSection(state: MutableState<Settings>) {
    val settings = state.value
    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    CollapsibleSection(str("tab.recompiler")) {
        Text(
            str("jit.recompiler.warning"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        ToggleRow("EE (R5900)", settings.recEE) { apply(settings.copy(recEE = it)) }
        ToggleRow("IOP (R3000)", settings.recIOP) { apply(settings.copy(recIOP = it)) }
        ToggleRow("VU0", settings.recVU0) { apply(settings.copy(recVU0 = it)) }
        ToggleRow("VU1", settings.recVU1) { apply(settings.copy(recVU1 = it)) }
        ToggleRow("Fastmem", settings.enableFastmem) { apply(settings.copy(enableFastmem = it)) }
    }
}

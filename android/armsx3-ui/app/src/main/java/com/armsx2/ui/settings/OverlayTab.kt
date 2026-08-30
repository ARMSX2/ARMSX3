package com.armsx2.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.config.Settings
import com.armsx2.i18n.str
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.UiScale
import androidx.core.content.edit

/** OSD text colours as 0xRRGGBB, index-aligned with [OSD_COLOR_LABEL_KEYS]. 0 = default white.
 *  Deliberately light/desaturated: the OSD draws over gameplay with only a soft shadow behind
 *  it, so fully-saturated colours read badly on bright scenes.
 *  Internal, not private: the in-game quick menu cycles the same palette, and two copies would
 *  drift the moment one gains a colour. */
internal val OSD_COLORS = listOf(
    0xFFFFFFFF.toInt(), // white
    0xFF66FF66.toInt(), // green
    0xFF66E0FF.toInt(), // cyan
    0xFFFFE066.toInt(), // yellow
    0xFFFFA64D.toInt(), // orange
    0xFFFF6666.toInt(), // red
    0xFFFF7AC8.toInt(), // pink
    0xFFC08CFF.toInt(), // purple
)

/** Which preset [argb] is, or -1 for a colour picked with the RGBA sliders instead. */
internal fun osdPresetIndex(argb: Int): Int = OSD_COLORS.indexOf(argb)

/** i18n keys for [OSD_COLORS], same order. */
internal val OSD_COLOR_LABEL_KEYS = listOf(
    "overlay.osdColor.default", "overlay.osdColor.green", "overlay.osdColor.cyan",
    "overlay.osdColor.yellow", "overlay.osdColor.orange", "overlay.osdColor.red",
    "overlay.osdColor.pink", "overlay.osdColor.purple",
)

/**
 * Performance Overlay element toggles. Lets the user show/hide individual
 * parts of the on-screen stats overlay (the master OSD pill on the Play tab
 * is still the quick all-on/all-off switch).
 *
 * The GPU toggle is special: turning it off also stops the GPU timing
 * queries (timestamp queries + per-frame readback have real overhead), so
 * it's a genuine performance lever, not just a display option — see GS.cpp
 * SetGPUTimingEnabled. Each toggle persists to base AND applies live via the
 * native osdShow* setters (see [InGameOverlay.applySafeLiveDelta]).
 */
@Composable
fun OverlayTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)

    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            str("overlay.intro.description"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // All three size sliders sit together at the top. The first scales the emulator's OSD
        // (the perf/stat readout drawn over the game); the two below scale the app's own menus.
        // They used to be split across the tab AND shared one label key, so this slider read as
        // "UI Size (borders)" while actually driving osdScale — two settings, one name.
        IntSliderRow(
            label = str("overlay.osdSize.label"),
            value = s.osdScale,
            min = 50,
            max = 250,
            description = str("overlay.osdSize.description"),
            valueFormatter = { "$it%" },
            onChange = { apply(s.copy(osdScale = it)) },
        )
        SettingsDivider()

        // OSD text colour. A preset row rather than an RGB picker: SegmentedRow is already
        // controller-navigable (Left/Right/Confirm), whereas a colour wheel would demand
        // pointer input and strand pad-only devices. The RGBA sliders further down set the
        // same value for anyone who wants a colour that is not on this list.
        //
        // This used to write `osdColor`, which is PCSX2's EmuCore/GS/OsdColor plus a
        // NativeApp.osdSetColor() that is an Unsupported.note() stub in this app -- so it did
        // nothing at all here, on either the settings tab or the in-game menu, and the OSD
        // stayed whatever RPCS3's default was. It writes RPCS3's own overlay body colour now.
        SegmentedRow(
            label = str("overlay.osdColor.label"),
            options = OSD_COLOR_LABEL_KEYS.map { str(it) },
            // No match means the RGBA sliders were used; -1 leaves every segment unselected
            // rather than lying about which preset is active.
            selectedIndex = osdPresetIndex(s.ps3.overlayBodyColor),
            description = str("overlay.osdColor.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(overlayBodyColor = OSD_COLORS[it]))) },
        )
        SettingsDivider()

        // Interface scaling (global, not per-game): resize the library / menu chrome
        // and text for different screen aspect ratios / handheld sizes. Does NOT
        // touch the game image or the on-screen touch controls.
        Text(
            str("overlay.interfaceScaling.description"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        IntSliderRow(
            label = str("overlay.uiSize.label"),
            value = (UiScale.borderScale.value * 100f).toInt(),
            min = (UiScale.MIN * 100f).toInt(),
            max = (UiScale.BORDER_MAX * 100f).toInt(),
            description = str("overlay.uiSize.description"),
            valueFormatter = { "$it%" },
            onChange = { UiScale.setBorderScale(it / 100f) },
        )
        SettingsDivider()
        IntSliderRow(
            label = str("overlay.uiFontSize.label"),
            value = (UiScale.fontScale.value * 100f).toInt(),
            min = (UiScale.MIN * 100f).toInt(),
            max = (UiScale.MAX * 100f).toInt(),
            description = str("overlay.uiFontSize.description"),
            valueFormatter = { "$it%" },
            onChange = { UiScale.setFontScale(it / 100f) },
        )
        SettingsDivider()

        // RPCS3's overlay is NOT a per-element checklist like PCSX2's. It has one
        // Enabled flag plus a detail_level that decides which elements appear, so
        // the thirteen individual toggles that used to be here had nothing to bind
        // to -- every one of them was inert.
        ToggleRow(
            str("overlay.enabled.label"),
            s.ps3.overlayEnabled,
            description = str("overlay.enabled.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(overlayEnabled = it))) }
        SettingsDivider()
        SegmentedRow(
            label = str("overlay.detail.label"),
            options = listOf(
                str("overlay.detail.none"), str("overlay.detail.minimal"),
                str("overlay.detail.low"), str("overlay.detail.medium"),
                str("overlay.detail.high"),
            ),
            selectedIndex = s.ps3.overlayDetail.coerceIn(0, 4),
            description = str("overlay.detail.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(overlayDetail = it))) },
        )
        SettingsDivider()
        SegmentedRow(
            label = str("overlay.position.label"),
            options = listOf(
                str("overlay.position.topLeft"), str("overlay.position.topRight"),
                str("overlay.position.bottomLeft"), str("overlay.position.bottomRight"),
            ),
            selectedIndex = s.ps3.overlayPosition.coerceIn(0, 3),
            description = str("overlay.position.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(overlayPosition = it))) },
        )
        SettingsDivider()
        IntSliderRow(
            label = str("overlay.fontSize.label"),
            value = s.ps3.overlayFontSize.coerceIn(4, 36),
            min = 4,
            max = 36,
            description = str("overlay.fontSize.description"),
            valueFormatter = { "$it px" },
            onChange = { apply(s.copy(ps3 = s.ps3.copy(overlayFontSize = it))) },
        )
        SettingsDivider()
        IntSliderRow(
            label = str("overlay.opacity.label"),
            value = s.ps3.overlayOpacity.coerceIn(0, 100),
            min = 0,
            max = 100,
            description = str("overlay.opacity.description"),
            valueFormatter = { "$it%" },
            onChange = { apply(s.copy(ps3 = s.ps3.copy(overlayOpacity = it))) },
        )
        SettingsDivider()
        ToggleRow(
            str("overlay.framerateGraph.label"),
            s.ps3.overlayFramerateGraph,
            description = str("overlay.framerateGraph.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(overlayFramerateGraph = it))) }
        SettingsDivider()
        ToggleRow(
            str("overlay.frametimeGraph.label"),
            s.ps3.overlayFrametimeGraph,
            description = str("overlay.frametimeGraph.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(overlayFrametimeGraph = it))) }
        SettingsDivider()

        // Custom colours. RPCS3 stores four "#RRGGBBAA" strings, so unlike PCSX2's
        // fixed preset list these really are free colours.
        OsdColorRow(str("overlay.color.body"), s.ps3.overlayBodyColor) { argb ->
            apply(s.copy(ps3 = s.ps3.copy(overlayBodyColor = argb)))
        }
        SettingsDivider()
        OsdColorRow(str("overlay.color.bodyBg"), s.ps3.overlayBodyBg) { argb ->
            apply(s.copy(ps3 = s.ps3.copy(overlayBodyBg = argb)))
        }
        SettingsDivider()
        OsdColorRow(str("overlay.color.title"), s.ps3.overlayTitleColor) { argb ->
            apply(s.copy(ps3 = s.ps3.copy(overlayTitleColor = argb)))
        }
        SettingsDivider()
        OsdColorRow(str("overlay.color.titleBg"), s.ps3.overlayTitleBg) { argb ->
            apply(s.copy(ps3 = s.ps3.copy(overlayTitleBg = argb)))
        }
        SettingsDivider()
        ToggleRow(str("overlay.toggle.onScreenNotifications"), s.osdShowMessages) { apply(s.copy(osdShowMessages = it)) }
        SettingsDivider()
        // Android hotkey pop-ups (Fast-Forward on/off, etc.) — separate from the emulator
        // OSD, pref-backed. Cancel-previous already stops them stacking; this switches
        // them off entirely for heavy fast-forward users.
        val ffToasts = remember { mutableStateOf(com.armsx2.runtime.MainActivityRuntime.prefs.getBoolean("ui.hotkeyToasts", true)) }
        ToggleRow(str("overlay.toggle.fastForwardPopups"), ffToasts.value) {
            ffToasts.value = it
            com.armsx2.runtime.MainActivityRuntime.prefs.edit { putBoolean("ui.hotkeyToasts", it) }
        }
        SettingsDivider()
        // Device temperatures on the perf overlay. Android exposes no supported API for SoC
        // temperatures, so these come from the thermal sysfs, whose zone naming and units are
        // vendor-specific -- a device with no readable zone simply shows nothing here.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        ToggleRow(
            str("overlay.toggle.temps"),
            com.armsx2.Thermals.osdEnabled.value,
            description = str("overlay.toggle.temps.description"),
        ) {
            com.armsx2.Thermals.setOsdEnabled(ctx, it)
        }
        // Poll interval. Asked for explicitly as the mitigation for sensor overhead. No
        // "realtime" option: a temperature that moves slower than a second is not worth the
        // syscalls.
        if (com.armsx2.Thermals.osdEnabled.value) {
            IntSliderRow(
                label = str("overlay.tempInterval.label"),
                value = com.armsx2.Thermals.intervalSec.value,
                min = 1,
                max = 5,
                valueFormatter = { "${it}s" },
                onChange = { com.armsx2.Thermals.setIntervalSec(it) },
            )
        }
    }
}

/**
 * One overlay colour: swatch, hex readout, and R/G/B/A sliders.
 *
 * Alpha is editable because RPCS3's overlay backgrounds genuinely use it -- the
 * default title background is fully transparent (#00000000), so an RGB-only
 * picker could not reproduce the defaults.
 */
@Composable
private fun OsdColorRow(label: String, argb: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(argb),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "#%08X".format(argb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    listOf(
        Triple("R", 16, (argb shr 16) and 0xFF),
        Triple("G", 8, (argb shr 8) and 0xFF),
        Triple("B", 0, argb and 0xFF),
        Triple("A", 24, (argb ushr 24) and 0xFF),
    ).forEach { (name, shift, value) ->
        IntSliderRow(
            label = name,
            value = value,
            min = 0,
            max = 255,
            onChange = { channel ->
                onChange((argb and (0xFF shl shift).inv()) or (channel shl shift))
            },
        )
    }
}

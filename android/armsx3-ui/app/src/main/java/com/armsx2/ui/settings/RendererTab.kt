package com.armsx2.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.armsx2.config.Settings
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.Colors
import com.armsx2.ui.InGameOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.armsx3.NativeApp
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renderer section of the in-game settings overlay.
 *
 * Most fields write into [Settings] via [InGameOverlay.saveSettings],
 * which honors the overlay's scope toggle (Global / Game). Upscale is
 * the one outlier — it has its own dedicated `MainActivityRuntime.upscale` state that's
 * also consumed by `MainActivityRuntime.applyRendererPrefs` and the setup wizard. Upscale
 * uses a narrow native GS helper so it can visibly apply while a game is live
 * without running the full settings commit path.
 */
internal data class UpscaleOption(val value: Float, val label: String)

// Shared so the in-game quick Graphics pane (EmulationMenuScreen) shows the exact same
// scale list — including the sub-native 0.25/0.5/0.75/Native options that its old
// hardcoded list omitted.
internal val UPSCALE_OPTIONS = listOf(
    // Sub-native (issue #207) — fewer pixels = big perf win on low/mid devices,
    // at the cost of sharpness. The GS only clamps the upper bound, so these are
    // applied as-is.
    UpscaleOption(0.25f, "0.25x"),
    UpscaleOption(0.5f, "0.5x"),
    UpscaleOption(0.75f, "0.75x"),
    UpscaleOption(1.0f, "Native"),
    UpscaleOption(1.25f, "1.25x"),
    UpscaleOption(1.5f, "1.5x"),
    UpscaleOption(1.75f, "1.75x"),
    UpscaleOption(2.0f, "2x"),
    UpscaleOption(2.25f, "2.25x"),
    UpscaleOption(2.5f, "2.5x"),
    UpscaleOption(2.75f, "2.75x"),
    UpscaleOption(3.0f, "3x"),
    UpscaleOption(3.5f, "3.5x"),
    UpscaleOption(4.0f, "4x"),
    UpscaleOption(5.0f, "5x"),
    UpscaleOption(6.0f, "6x"),
    UpscaleOption(7.0f, "7x"),
    UpscaleOption(8.0f, "8x"),
)

/** Screen-aspect presets in permille, index-aligned with the picker's options. Anything not
 *  in this list is a custom value, which is what selects the Custom entry and its slider. */
private val SCREEN_ASPECTS = listOf(
    0,    // Auto - follow whatever the game signalled (4:3 or 16:9)
    1333, // 4:3
    1600, // 16:10
    1778, // 16:9
    2000, // 18:9
    2167, // 19.5:9
    2222, // 20:9
    2333, // 21:9
)

/** Seed for Custom. Deliberately NOT a preset value: landing on one would re-select that
 *  preset and hide the slider the user just asked for. */
private const val CUSTOM_ASPECT_SEED = 2100

@Composable
fun RendererTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)

    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        CollapsibleSection(str("renderer.section.displayResolution"), initiallyExpanded = true) {
            // Graphics API (OpenGL / Vulkan) + Vulkan custom-driver picker.
            // from the removed first-run setup renderer page into settings.
            RendererBackendSection(state)
            SettingsDivider()
            // Clear Shader Cache — directly under the GPU driver picker. Switching the Vulkan driver
            // is exactly when you want to wipe the on-disk shader cache so it recompiles on the new
            // one, so the control lives with the driver rather than buried lower in the tab.
            ClearShaderCacheRow()
            SettingsDivider()
            // GS Multi-threading (GV7 front/back split), placed right under the
            // renderer/driver picker. Off = today's single-threaded path (the
            // default — opt-in); On = the GS runs on a dedicated back thread
            // (Pipelined, enum value 3). The enum's Inline/Lockstep rungs (1/2)
            // are dev-only and deliberately not exposed. Restart-required (in
            // RestartOptionsAreEqual); native-lib snapshots the field across a
            // live apply, so it only takes effect on the next game boot.
        // Removed: GS is the PS2's rasteriser. RPCS3's analogue is Multithreaded RSX, in RSX Accuracy.
            // A value that matches no preset is a CUSTOM one (set below, per-game, or from an INI).
            // It used to fall back to index 0, which displayed 0.25x while the GS ran something
            // else entirely — so the row lied about the active resolution.
            val presetIndex = UPSCALE_OPTIONS.indexOfFirst { abs(it.value - s.upscaleFloat) < 0.01f }
            val customIndex = UPSCALE_OPTIONS.size
            // Whether the Custom row is OPEN has to be its own state, not "the value matches no
            // preset". Picking Custom leaves the value untouched by design, so deriving it from the
            // value alone made the tap a no-op: the selection snapped straight back to the preset
            // and the slider never appeared. A value that matches no preset (per-game, or an INI)
            // still forces it open, so the row can never misreport what the GS is running.
            val customOpen = rememberSaveable { mutableStateOf(false) }
            val showCustom = customOpen.value || presetIndex < 0
            val upscaleIndex = if (showCustom) customIndex else presetIndex
            SegmentedGridRow(
                label = str("renderer.upscale.label"),
                options = UPSCALE_OPTIONS.map { it.label } + str("renderer.upscale.custom"),
                selectedIndex = upscaleIndex,
                columns = 4,
                description = str("renderer.upscale.description"),
                onChange = { index ->
                    if (index == customIndex) {
                        // Reveal the slider and keep the current value as its starting point —
                        // jumping to some arbitrary default would throw away what they had.
                        customOpen.value = true
                    } else {
                        customOpen.value = false
                        val mult = UPSCALE_OPTIONS[index].value
                        // Persist scope-aware (per-game when the overlay scope is Game);
                        // the live GS apply happens in InGameOverlay's settings delta.
                        if (abs(s.upscaleFloat - mult) >= 0.01f) apply(s.copy(upscaleFloat = mult))
                    }
                },
            )
            // Custom resolution scale, as a PERCENTAGE of native — the Dolphin-style numeric
            // control people ask for when a preset step is too coarse. The GS multiplier is a
            // float, so e.g. 107% turns 512x448 into a true 480p-height render without paying for
            // a full 2x. Only shown on Custom, so the preset grid stays uncluttered.
            if (upscaleIndex == customIndex) {
                IntSliderRow(
                    label = str("renderer.upscale.customScale"),
                    value = (s.upscaleFloat * 100f).roundToInt().coerceIn(25, 800),
                    min = 25,
                    max = 800,
                    description = str("renderer.upscale.customScale.description"),
                    valueFormatter = { "$it%" },
                    onReset = { apply(s.copy(upscaleFloat = 1.0f)) },
                    onChange = { pct -> apply(s.copy(upscaleFloat = pct / 100f)) },
                )
            }
            SettingsDivider()
            // Two different things, deliberately kept apart:
            //
            //   CONSOLE ASPECT  - RPCS3's video_aspect, which is only 4:3 or 16:9
            //                     because that is all the PS3 ever signalled. A
            //                     value outside those two is rejected by
            //                     from_string and silently does nothing.
            //   SCREEN FIT      - how that image is placed on YOUR panel. This is
            //                     ours (surface layout + Stretch To Display Area),
            //                     so the ultrawide ratios ARMSX2 offered still make
            //                     sense here; they just are not console aspects.
            SegmentedRow(
                label = str("renderer.consoleAspect.label"),
                options = listOf(str("common.auto"), "4:3", "16:9"),
                selectedIndex = when (s.aspectRatio) { 2 -> 1; 3 -> 2; else -> 0 },
                description = str("renderer.consoleAspect.description"),
                onChange = { apply(s.copy(aspectRatio = intArrayOf(1, 2, 3)[it])) },
            )
            SettingsDivider()
            SegmentedRow(
                label = str("renderer.displayMode.label"),
                // TWO options, because RPCS3 has exactly one knob here:
                // Video@@"Stretch To Display Area". Integer and Fill were carried
                // over from the PS2 UI with nothing behind them -- all three of
                // Fit/Integer/Fill wrote stretch=false, so picking between them
                // changed nothing on screen. Console aspect (4:3 / 16:9) and
                // Output Scaling (Nearest/Bilinear/FSR) are their own rows.
                options = listOf(
                    str("renderer.fit.auto"),
                    str("renderer.fit.stretch"),
                ),
                selectedIndex = if (s.displayFitMode == 1) 1 else 0,
                description = str("renderer.displayMode.description"),
                onChange = { apply(s.copy(displayFitMode = it)) },
            )
            SettingsDivider()
            // SCREEN aspect, distinct from Console Aspect above. Console aspect is what the
            // GAME thinks it is drawing (4:3 or 16:9, all the PS3 could signal); this is the
            // shape of the letterbox its image is fitted into on YOUR panel. Without it a
            // 20:9 handheld had only Fit (pillarboxed) or Stretch (distorted), which is what
            // "no way to adjust between custom aspect ratios like armsx2" was about.
            // Stored in permille to match the core's cfg::_int.
            SegmentedGridRow(
                label = str("renderer.screenAspect.label"),
                options = listOf(
                    str("common.auto"), "4:3", "16:10", "16:9", "18:9", "19.5:9", "20:9", "21:9",
                    str("renderer.screenAspect.custom"),
                ),
                selectedIndex = SCREEN_ASPECTS.indexOf(s.ps3.displayAspect)
                    .let { if (it >= 0) it else SCREEN_ASPECTS.size },
                columns = 3,
                description = str("renderer.screenAspect.description"),
                onChange = { index ->
                    // Last option is Custom: seed the slider from the panel's own ratio so it
                    // starts somewhere useful instead of snapping the image on selection.
                    val permille = SCREEN_ASPECTS.getOrNull(index) ?: CUSTOM_ASPECT_SEED
                    apply(s.copy(ps3 = s.ps3.copy(displayAspect = permille)))
                },
            )
            if (s.ps3.displayAspect !in SCREEN_ASPECTS) {
                SettingsDivider()
                IntSliderRow(
                    label = str("renderer.screenAspect.customValue"),
                    value = s.ps3.displayAspect.coerceIn(1000, 3000),
                    min = 1000,
                    max = 3000,
                    description = str("renderer.screenAspect.customValue.description"),
                    valueFormatter = { String.format(java.util.Locale.US, "%.2f:1", it / 1000f) },
                    onChange = { apply(s.copy(ps3 = s.ps3.copy(displayAspect = it))) },
                )
            }
            SettingsDivider()
            // PS3 output resolution -- what the console reports to the game.
            // Separate from Resolution Scale above, which is internal upscaling.
            SegmentedGridRow(
                label = str("renderer.ps3Resolution.label"),
                options = com.armsx3.Rpcs3Settings.RESOLUTIONS.toList(),
                selectedIndex = s.ps3.resolution.coerceIn(0, com.armsx3.Rpcs3Settings.RESOLUTIONS.size - 1),
                columns = 3,
                description = str("renderer.ps3Resolution.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(resolution = it))) },
            )
            SettingsDivider()
            // FMV Aspect Ratio override — applies only during FMVs/cutscenes; "Off" keeps
            // the aspect above. Handy for games that render FMVs at a different ratio.
        // Removed: PCSX2 switches aspect for FMVs because PS2 FMVs are a different ratio. PS3 video is native.
            // Emulation Screen Orientation — Android activity orientation, now scope-aware
            // (global ∘ per-game) like the rest of this tab. applyEmulationOrientation resolves
            // the running game's value at boot and reverts to global on exit-to-library.
            SegmentedRow(
                label = str("renderer.orientation.label"),
                options = listOf(
                    str("renderer.orientation.device"),
                    str("renderer.orientation.landscape"),
                    str("renderer.orientation.portrait"),
                    str("renderer.orientation.autoRotate"),
                ),
                selectedIndex = s.orientation.coerceIn(0, 3),
                description = str("renderer.orientation.description"),
                onChange = {
                    apply(s.copy(orientation = it))
                    MainActivityRuntime.instance?.applyEmulationOrientation()
                },
            )
            SettingsDivider()
            // GitHub #375: where the render sits in a PORTRAIT window. Top (default) frees the
            // bottom half for touch controls; Center keeps the old vertical-centered behavior.
            // Live via NativeApp.setPortraitRenderTop (through applyTo); only affects portrait.
            SegmentedRow(
                label = str("renderer.portraitPosition.label"),
                options = listOf(str("renderer.portraitPosition.top"), str("renderer.portraitPosition.center")),
                selectedIndex = if (s.portraitRenderTop) 0 else 1,
                description = str("renderer.portraitPosition.description"),
                onChange = { apply(s.copy(portraitRenderTop = it == 0)) },
            )
            SettingsDivider()
            // Where the render sits in a LANDSCAPE window. Center is the default; Top suits
            // foldables and clamshell controllers, whose screens open downward so a centred
            // image reads as sitting too low. Live via NativeApp.setLandscapeRenderTop.
            SegmentedRow(
                label = str("renderer.landscapePosition.label"),
                options = listOf(str("renderer.landscapePosition.center"), str("renderer.landscapePosition.top")),
                selectedIndex = if (s.landscapeRenderTop) 1 else 0,
                description = str("renderer.landscapePosition.description"),
                onChange = { apply(s.copy(landscapeRenderTop = it == 1)) },
            )
            SettingsDivider()
            // Auto Progressive Scan — holds Triangle+Cross through boot, the combo some titles
            // probe to offer 480p progressive. Takes effect on the next boot (it is a boot-time
            // pad hold, not a live setting), and only does anything on games that implement it.
        // Removed: the PS3 outputs progressive; there is no interlaced field to deinterlace.
        // Removed: PS2 deinterlacing mode. RPCS3 has no deinterlacer.
        }
        SettingsDivider()
        // Removed: PS2 texture filtering (bilinear/trilinear per-primitive),
        // texture preloading and hardware download mode are GS features. RPCS3's
        // equivalent knob is the anisotropic override, which is in Output Scaling.
        SettingsDivider()
        // PCSX2's post-processing chain (display filter, TV shader, shade boost,
        // brightness/contrast/saturation/gamma, FXAA) has no RPCS3 counterpart --
        // none of those nodes exist in the config tree, so every one of them was
        // inert. RPCS3's only real post-process is FidelityFX CAS, which is an
        // OUTPUT SCALING MODE rather than an effect, so it lives here now.
        CollapsibleSection(str("renderer.section.outputScaling")) {
            SegmentedGridRow(
                label = str("renderer.outputScaling.label"),
                options = listOf(
                    str("renderer.outputScaling.nearest"),
                    str("renderer.outputScaling.bilinear"),
                    str("renderer.outputScaling.fsr"),
                    str("renderer.outputScaling.sgsr"),
                ),
                // Bound raised with the option. A clamp left at the old maximum silently rewrites
                // the new choice back to the previous one, which reads as the setting refusing to
                // take.
                selectedIndex = s.casMode.coerceIn(0, 3),
                columns = 2,
                description = str("renderer.outputScaling.description"),
                onChange = { apply(s.copy(casMode = it)) },
            )
            SettingsDivider()
            IntSliderRow(
                // Named for whichever upscaler is actually selected: the value is an RCAS stop to
                // FSR and an edge factor to SGSR.
                label = str(
                    when (s.casMode) {
                        2 -> "renderer.cas.sharpness.fsr"
                        3 -> "renderer.cas.sharpness.sgsr"
                        else -> "renderer.cas.sharpness.label"
                    }
                ),
                value = s.casSharpness.coerceIn(0, 100),
                min = 0,
                max = 100,
                description = str("renderer.casSharpness.description"),
                // SGSR reads as 0-200%: 100% is Qualcomm's default and 200% the widened top end.
                // Stored value is unchanged and still shared with FSR.
                valueFormatter = { if (s.casMode == 3) "${it * 2}%" else "$it%" },
                onChange = { apply(s.copy(casSharpness = it)) },
            )
            SettingsDivider()
            IntSliderRow(
                label = str("renderer.aniso.label"),
                value = s.ps3.anisoFilter.coerceIn(0, 16),
                min = 0,
                max = 16,
                description = str("renderer.aniso.description"),
                valueFormatter = { if (it == 0) "Auto" else "${it}x" },
                onChange = { apply(s.copy(ps3 = s.ps3.copy(anisoFilter = it))) },
            )
        }
        SettingsDivider()
        // Its OWN section, not a row at the bottom of Display Effects: buried under the whole
        // shader manager inside a collapsed section, nobody could find it.
        CollapsibleSection(str("renderer.section.overlayArt")) {
            OverlayArtSection()
        }
        SettingsDivider()
        // Removed: texture replacement packs are a PCSX2 feature (dumping and
        // reloading GS textures by hash). RPCS3 has no texture-replacement system,
        // so the whole section -- load, async load, precache, dump, OSD -- did
        // nothing.
        //
        // RetroArch shader chains DO work (librashader runs as RPCS3's output
        // scaling pass), so they get their own section rather than living inside
        // the texture-pack block they used to share.
        CollapsibleSection(str("renderer.section.shaderChain")) {
            com.armsx2.ui.common.ShaderChainSection(
                enabled = s.shaderChainEnabled,
                preset = s.shaderChainPreset,
                params = s.shaderChainParams,
                onEnabledChange = { on -> apply(s.copy(shaderChainEnabled = on)) },
                onPresetChange = { path -> apply(s.copy(shaderChainPreset = path)) },
                onParamsChange = { next -> apply(s.copy(shaderChainParams = next)) },
            )
            com.armsx2.ui.common.ShaderManagerSection()
        }
        SettingsDivider()
        // RSX accuracy. The PS2 GS controls that were here (blending accuracy,
        // ROV, framebuffer fetch, render-pass coalescing) describe the PS2's
        // rasteriser -- the PS3's RSX has none of them. These are the knobs
        // RPCS3 actually exposes for correctness-vs-speed.
        CollapsibleSection(str("renderer.section.rsxAccuracy")) {
            ToggleRow(
                str("renderer.writeColorBuffers.label"),
                s.ps3.writeColorBuffers,
                description = str("renderer.writeColorBuffers.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(writeColorBuffers = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.writeDepthBuffer.label"),
                s.ps3.writeDepthBuffer,
                description = str("renderer.writeDepthBuffer.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(writeDepthBuffer = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.readColorBuffers.label"),
                s.ps3.readColorBuffers,
                description = str("renderer.readColorBuffers.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(readColorBuffers = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.readDepthBuffer.label"),
                s.ps3.readDepthBuffer,
                description = str("renderer.readDepthBuffer.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(readDepthBuffer = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.strictRendering.label"),
                s.ps3.strictRendering,
                description = str("renderer.strictRendering.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(strictRendering = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.multithreadedRsx.label"),
                s.ps3.multithreadedRsx,
                description = str("renderer.multithreadedRsx.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(multithreadedRsx = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.disableZcull.label"),
                s.ps3.disableZcull,
                description = str("renderer.disableZcull.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(disableZcull = it))) }
            SettingsDivider()
            ToggleRow(
                str("renderer.relaxedZcull.label"),
                s.ps3.relaxedZcull,
                description = str("renderer.relaxedZcull.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(relaxedZcull = it))) }
            SettingsDivider()
            SegmentedGridRow(
                label = str("renderer.shaderMode.label"),
                options = listOf(
                    str("renderer.shaderMode.legacy"),
                    str("renderer.shaderMode.async"),
                    str("renderer.shaderMode.asyncInterp"),
                    str("renderer.shaderMode.interpOnly"),
                ),
                selectedIndex = s.ps3.shaderMode.coerceIn(0, 3),
                columns = 2,
                description = str("renderer.shaderMode.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(shaderMode = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("renderer.msaa.label"),
                options = listOf(str("common.off"), str("common.auto")),
                selectedIndex = s.ps3.msaaMode.coerceIn(0, 1),
                columns = 2,
                description = str("renderer.msaa.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(msaaMode = it))) },
            )
            SettingsDivider()
            IntSliderRow(
                label = str("renderer.vramLimit.label"),
                // IntSliderRow has no step; express the range in 256 MB units
                // and scale on the way in and out.
                value = (s.ps3.vramLimitMb.coerceIn(256, 8192)) / 256,
                min = 1,
                max = 32,
                description = str("renderer.vramLimit.description"),
                valueFormatter = { "${it * 256} MB" },
                onChange = { apply(s.copy(ps3 = s.ps3.copy(vramLimitMb = it * 256))) },
            )
            SettingsDivider()
            ToggleRow(
                str("renderer.asyncTexStream.label"),
                s.ps3.asyncTexStream,
                description = str("renderer.asyncTexStream.description"),
            ) { apply(s.copy(ps3 = s.ps3.copy(asyncTexStream = it))) }
        }
    }
}


// Removed TexturePackImportRow: PCSX2 replaces GS textures by hash. RPCS3 has
// no texture-replacement system, so import/dump/precache had nothing to feed.

/**
 * RetroArch overlay artwork (bezel / border): import a pack, pick one, set its opacity.
 *
 * Separate from the shader chain on purpose — an RA overlay is just an image, so it composites
 * for free and can be layered WITH a shader preset, which is what was asked for. Only the image
 * half of the .cfg is used; ARMSX2 has its own touch layout, so the format's input hitboxes are
 * deliberately ignored rather than fighting it.
 */
@Composable
private fun OverlayArtSection() {
    val context = LocalContext.current
    val refresh = remember { mutableStateOf(0) }
    val entries = remember(refresh.value) { com.armsx2.OverlayRepo.list(context) }
    // Result of the last import, so it can never be silent — a .cfg whose image didn't come with
    // it used to look identical to a successful import (nothing appeared, nothing was said).
    val status = remember { mutableStateOf("") }
    // MULTI-select, so a .cfg CAN be imported as a file: pick the cfg and its image(s) together in
    // one go. A single-document pick can't reach the cfg's siblings, which is why importing a lone
    // cfg silently produced nothing — but the fix for that is letting you select them both, not
    // forcing everyone to use a folder picker.
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            var total = 0
            var sawCfg = false
            var sawImage = false
            uris.forEach { uri ->
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                }.getOrNull()
                if (name?.endsWith(".cfg", true) == true) sawCfg = true
                if (name?.substringAfterLast('.', "")?.lowercase() in setOf("png", "jpg", "jpeg", "webp")) sawImage = true
                total += com.armsx2.OverlayRepo.importFrom(context, uri, name)
            }
            refresh.value++
            status.value = when {
                total <= 0 -> I18n.get("renderer.overlayArt.importFailed")
                // A cfg with no artwork alongside it still can't resolve — say so rather than
                // leaving the list looking unchanged.
                sawCfg && !sawImage -> I18n.get("renderer.overlayArt.importCfgAlone")
                else -> I18n.get("renderer.overlayArt.imported").format(total)
            }
        }
    }
    val folderImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val n = com.armsx2.OverlayRepo.importTree(context, uri)
            refresh.value++
            status.value = if (n > 0) I18n.get("renderer.overlayArt.imported").format(n)
            else I18n.get("renderer.overlayArt.importFailed")
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .controllerFocusable("renderer.overlayArt.import", RoundedCornerShape(16.dp),
                onConfirm = { importer.launch(arrayOf("application/zip", "image/*", "*/*")) })
            .clickable { importer.launch(arrayOf("application/zip", "image/*", "*/*")) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            str("renderer.overlayArt.import"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    // ---- Download instead of import ----------------------------------------------------------
    // Importing by hand is genuinely fiddly (a .cfg is useless without the image it references),
    // so the primary path is now a browse-and-tap list from libretro's own overlay collection.
    // The file/folder importers stay for people bringing their own packs.
    val scope = rememberCoroutineScope()
    val catalog = remember { mutableStateOf<List<com.armsx2.OverlayRepo.CatalogEntry>>(emptyList()) }
    val catalogBusy = remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .controllerFocusable("renderer.overlayArt.browse", RoundedCornerShape(16.dp), onConfirm = {
                if (!catalogBusy.value) {
                    catalogBusy.value = true
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { com.armsx2.OverlayRepo.fetchCatalog() }
                        catalog.value = list
                        catalogBusy.value = false
                        if (list.isEmpty()) status.value = I18n.get("renderer.overlayArt.browseFailed")
                    }
                }
            })
            .clickable {
                if (!catalogBusy.value) {
                    catalogBusy.value = true
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { com.armsx2.OverlayRepo.fetchCatalog() }
                        catalog.value = list
                        catalogBusy.value = false
                        if (list.isEmpty()) status.value = I18n.get("renderer.overlayArt.browseFailed")
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (catalogBusy.value) str("renderer.overlayArt.browsing") else str("renderer.overlayArt.browse"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    catalog.value.forEach { entry ->
        val download = {
            scope.launch {
                status.value = I18n.get("renderer.overlayArt.downloading").format(entry.name)
                val n = withContext(Dispatchers.IO) {
                    com.armsx2.OverlayRepo.downloadFromCatalog(context, entry)
                }
                refresh.value++
                status.value = if (n > 0) I18n.get("renderer.overlayArt.downloaded").format(entry.name)
                else I18n.get("renderer.overlayArt.importFailed")
            }
            Unit
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .controllerFocusable("renderer.overlayArt.dl.${entry.path}", RoundedCornerShape(12.dp), onConfirm = download)
                .clickable { download() }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("⤓  ${entry.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
    // Folder import — the one that works for a RetroArch .cfg, because only a tree URI can bring
    // the artwork the cfg points at along with it.
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .controllerFocusable("renderer.overlayArt.importFolder", RoundedCornerShape(16.dp),
                onConfirm = { folderImporter.launch(null) })
            .clickable { folderImporter.launch(null) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            str("renderer.overlayArt.importFolder"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (status.value.isNotBlank()) {
        Text(
            status.value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    SettingsDivider()
    // "None" first so turning it off is always one tap away, even with a long pack list.
    val options = listOf(str("renderer.overlayArt.none")) + entries.map { it.name }
    val selected = entries.indexOfFirst { it.imagePath == com.armsx2.OverlayRepo.activePath.value }
        .let { if (it >= 0) it + 1 else 0 }
    SegmentedGridRow(
        label = str("renderer.overlayArt.label"),
        options = options,
        selectedIndex = selected,
        columns = 2,
        description = str("renderer.overlayArt.description"),
        onChange = { idx ->
            com.armsx2.OverlayRepo.setActive(
                if (idx == 0) "" else entries.getOrNull(idx - 1)?.imagePath.orEmpty(),
            )
        },
    )
    if (com.armsx2.OverlayRepo.activePath.value.isNotBlank()) {
        SettingsDivider()
        IntSliderRow(
            label = str("renderer.overlayArt.opacity"),
            value = (com.armsx2.OverlayRepo.opacity.floatValue * 100f).roundToInt(),
            min = 5,
            max = 100,
            description = str("renderer.overlayArt.opacity.description"),
            valueFormatter = { "$it%" },
            onReset = { com.armsx2.OverlayRepo.setOpacity(1f) },
            onChange = { com.armsx2.OverlayRepo.setOpacity(it / 100f) },
        )
    }
}

@Composable
private fun ClearShaderCacheRow() {
    val context = LocalContext.current
    val status = remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable {
                val n = clearShaderCache(File(MainActivityRuntime.assetCopyRoot(context), "cache"))
                status.value = if (n > 0)
                    "Cleared $n shader-cache file${if (n == 1) "" else "s"} — restart the game to rebuild."
                else
                    I18n.get("renderer.clearShaderCache.alreadyEmpty")
                Toast.makeText(context, status.value, Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                str("renderer.clearShaderCache.label"),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                status.value.ifEmpty {
                    I18n.get("renderer.clearShaderCache.description")
                },
                color = Colors.pasx2_blue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Delete the on-disk compiled shader/pipeline caches (Vulkan + GL). They rebuild
 *  on the next renderer init; a stale/mismatched cache (e.g. after a driver change)
 *  can otherwise leave a game rendering corrupt. Returns how many files were removed. */
private fun clearShaderCache(cacheDir: File): Int {
    val names = listOf(
        "vulkan_pipelines.bin", "vulkan_shaders.bin", "vulkan_shaders.idx",
        "gl_programs.bin", "gl_programs.idx",
    )
    var removed = 0
    for (name in names) {
        val f = File(cacheDir, name)
        if (f.isFile && runCatching { f.delete() }.getOrDefault(false)) removed++
    }
    return removed
}


// Removed GsDumpCaptureRow: a GS dump is a PS2 GS command stream. RPCS3's
// analogue is an RSX capture -- a different format, and not wired up here.

private fun activeTextureSerial(): String? {
    return MainActivityRuntime.currentGame.value?.serial?.takeIf { it.isNotBlank() }
        ?: runCatching { NativeApp.getGameSerial() }.getOrNull()?.takeIf { it.isNotBlank() }
        // Last resort: the game the user most recently had open. Both sources above go blank the
        // moment you quit to the library (currentGame is nulled so per-game settings scope can't
        // leak, and the VM's serial dies with the VM), which stranded texture-pack import behind
        // "Boot a game first" even though the user had just played — and quit — that game.
        ?: MainActivityRuntime.contextGame.value?.serial?.takeIf { it.isNotBlank() }
}

private fun importTexturePack(context: Context, uri: Uri, serial: String): Int {
    val root = DocumentFile.fromTreeUri(context, uri) ?: return -1
    val source = root.findFile("replacements")?.takeIf { it.isDirectory } ?: root
    val dest = File(MainActivityRuntime.assetCopyRoot(context), "textures/$serial/replacements")
    if (!dest.exists() && !dest.mkdirs())
        return -1
    return copyDocumentTree(context, source, dest)
}

/** Extract a picked .zip of replacement textures into textures/<serial>/replacements,
 *  preserving the archive's internal folder structure. The native loader scans that
 *  directory RECURSIVELY and matches by hash filename, so a pack that nests its files
 *  (e.g. under its own "replacements/" or a pack-name folder) still resolves. Guards
 *  against Zip-Slip path traversal by rejecting any entry that escapes the dest dir. */
private fun importTexturePackZip(context: Context, zipUri: Uri, serial: String): Int {
    val dest = File(MainActivityRuntime.assetCopyRoot(context), "textures/$serial/replacements")
    if (!dest.exists() && !dest.mkdirs())
        return -1
    val destCanon = dest.canonicalPath
    var copied = 0
    context.contentResolver.openInputStream(zipUri)?.use { raw ->
        ZipInputStream(BufferedInputStream(raw)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) {
                    val rel = e.name.replace('\\', '/').trimStart('/')
                    val out = File(dest, rel)
                    // Zip-Slip guard: the resolved path must stay inside dest.
                    val outCanon = out.canonicalPath
                    if (outCanon == destCanon || outCanon.startsWith(destCanon + File.separator)) {
                        out.parentFile?.mkdirs()
                        // Per-entry guard: a mid-copy failure deletes that partial file
                        // and lets the remaining entries still import.
                        val ok = runCatching { out.outputStream().use { zin.copyTo(it) } }.isSuccess
                        if (ok) copied++ else out.delete()
                    }
                }
                zin.closeEntry()
            }
        }
    } ?: return -1
    return copied
}

private fun copyDocumentTree(context: Context, source: DocumentFile, dest: File): Int {
    var copied = 0
    for (child in source.listFiles()) {
        val name = child.name ?: continue
        if (child.isDirectory) {
            val childDest = File(dest, name)
            if (!childDest.exists())
                childDest.mkdirs()
            copied += copyDocumentTree(context, child, childDest)
        } else if (child.isFile) {
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                File(dest, name).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: continue
            copied++
        }
    }
    return copied
}

package com.armsx2.ui.settings

import net.rpcsx.RPCSX
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.armsx2.EmuState
import com.armsx2.config.Settings
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.Colors
import com.armsx2.ui.InGameOverlay
import androidx.core.content.edit
import java.io.File
import kotlin.math.roundToInt

/**
 * Performance section of the in-game settings overlay.
 *
 * Mutates the live [Settings] state and routes the write through
 * [InGameOverlay.saveSettings], which picks the global or per-game
 * storage tier based on the overlay's current scope. Applies via
 * [Settings.applyTo] so toggles take effect immediately on a running
 * VM. Every visible setting maps 1-1 onto an EmuCore key (see Settings
 * field comments for the exact `<section>/<key>`).
 *
 * Column + verticalScroll instead of LazyColumn so the tab can sit
 * inside the wrap-content RootTabs container without needing a hard
 * height bound. List is short (~9 rows) so non-lazy is fine.
 */
@Composable
fun PerformanceTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)

    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        // Prominent latency preset: zero queued GS frames keeps the emulated CPU
        // from running ahead of presentation, and the Surface requests a matching
        // high-refresh display mode. Settings scope is supplied by InGameOverlay,
        // so the same switch naturally supports Global and Game overrides. Off
        // restores the small, smoother queue and Android's automatic refresh policy.
        // Removed: PCSX2's GS vsync queue depth. RPCS3 has no equivalent knob.
        // Speedhack profile presets. Equality against s.copy(...) means the
        // segment auto-reflects "Custom" once the user tweaks any speedhack below.
        run {
            val safe = s.copy(eeCycleRate = 0, eeCycleSkip = 0, mtvu = true, vu1Instant = true,
                vuFlagHack = true, intcStat = true, waitLoop = true, fastCDVD = false,
                // Restore the GPU-quality levers the Fast/Low-End presets lower, so
                // Optimal is a COMPLETE reset to recommended defaults — not just the
                // speedhacks (e.g. Texture Preloading back to Full, blending to Basic).
                // Resolution is left as-is so upscalers aren't dropped to native.
                accurateBlendingUnit = 1, hwMipmap = true, texturePreloading = 2, hwRov = false)
            // Fast = speed-first: EE cycle skip + fast CDVD, plus render-side wins
            // that are safe for most games (native resolution + Basic blending).
            val fast = s.copy(eeCycleRate = 0, eeCycleSkip = 2, mtvu = true, vu1Instant = true,
                vuFlagHack = true, intcStat = true, waitLoop = true, fastCDVD = true,
                upscaleFloat = 1.0f, accurateBlendingUnit = 1)
            // Low-End = every cheap GPU/CPU lever, MTVU gated on core count. Built
            // from the shared Settings.lowEndPreset so it matches the setup wizard.
            val lowEnd = Settings.lowEndPreset(
                s.copy(eeCycleRate = 0, mtvu = true, vu1Instant = true,
                    vuFlagHack = true, intcStat = true, waitLoop = true, fastCDVD = true),
                mtvu = com.armsx2.DeviceTier.mtvuDefault(),
            )
            // -1 = no preset matches (custom): no segment highlighted.
            val idx = when (s) { safe -> 0; fast -> 1; lowEnd -> 2; else -> -1 }
        // Removed: PS2 speedhack presets (EE cycle rate/skip bundles). No PS3 analogue.
        }
        HelpText(str("perf.speedhackProfile.help"))
        SettingsDivider()
        // ---- Display Resolution (HW scaler), NetherSX2-style ----------------
        // Shrinks the game's OUTPUT surface (hardware-composer upscales to the
        // screen) to cut GPU present cost, heat and battery. Global pref (not a
        // Settings field) applied live to the active output surface.
        run {
            // Per-game scoped (Duda, 2026-07-28): this used to write MainActivityRuntime.prefs
            // directly, so changing it in Game scope silently moved the Global value too —
            // there was no per-game copy to write. Now it routes through the same
            // saveSettings() path as every other row in this tab.
            SegmentedRow(
                label = str("perf.displayResolution.label"),
                // Short-side render heights, not PS2 multiples. This scales the Android
                // surface, so it works regardless of core -- but 448*n was the PS2's
                // native height and meant nothing for a 720p console.
                options = listOf(str("perf.displayResolution.screen"), "1080p", "720p", "540p"),
                selectedIndex = when (s.hwScaler) { 3 -> 1; 2 -> 2; 1 -> 3; else -> 0 },
                description = str("perf.displayResolution.description"),
                onChange = {
                    apply(s.copy(hwScaler = when (it) { 1 -> 1080; 2 -> 720; 3 -> 540; else -> 0 }))
                    com.armsx2.runtime.MainActivityRuntime.surface.value?.applyOutputScale()
                },
            )
        }
        SettingsDivider()
        // ---- Screen resolution override (#398) ------------------------------
        // Forces the game's OUTPUT surface to a fixed 16:9 resolution instead of the
        // detected panel size — fixes 16:10 / mis-detected panels (e.g. reported 1920x1200
        // on a 1080p screen) that squish 16:9 games and widescreen patches. Global pref,
        // live-applied to the output surface; composes with the HW scaler above.
        run {
            // Per-game scoped for the same reason as the HW scaler above.
            val presets = listOf("auto", "2560x1440", "1920x1080", "1280x720")
            SegmentedRow(
                label = str("perf.screenRes.label"),
                options = listOf(str("perf.screenRes.auto"), "1440p", "1080p", "720p"),
                selectedIndex = presets.indexOf(s.screenResOverride).let { if (it >= 0) it else 0 },
                description = str("perf.screenRes.description"),
                onChange = { idx ->
                    apply(s.copy(screenResOverride = presets[idx]))
                    com.armsx2.runtime.MainActivityRuntime.surface.value?.applyOutputScale()
                },
            )
        }
        // ---- Sustained Performance (#128) ---------------------------------------
        // Asks Android to hold a steady, thermally-sustainable clock instead of
        // boost-then-throttle. Better for long sessions on handhelds, but it CAPS the
        // peak clock so peak-hungry games can lose fps — a user choice, default Off.
        // Global pref, applied at launch (MainActivityRuntime.onCreate) and live here via the window.
        run {
            val sustained = remember { androidx.compose.runtime.mutableStateOf(com.armsx2.runtime.MainActivityRuntime.prefs.getBoolean("ui.sustainedPerf", false)) }
            SegmentedRow(
                label = str("perf.sustainedPerformance.label"),
                options = listOf(str("common.off"), str("common.on")),
                selectedIndex = if (sustained.value) 1 else 0,
                description = str("perf.sustainedPerformance.description"),
                onChange = {
                    val on = it == 1
                    sustained.value = on
                    com.armsx2.runtime.MainActivityRuntime.prefs.edit {
                        putBoolean(
                            "ui.sustainedPerf",
                            on
                        )
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        runCatching {
                            (com.armsx2.runtime.MainActivityRuntime.surface.value?.context as? android.app.Activity)
                                ?.window?.setSustainedPerformanceMode(on)
                        }
                    }
                },
            )
        }
        // ---- CPU clock hint (ADPF) ---------------------------------------------
        // PerformanceHintManager: reports the per-frame CPU work to the OS scheduler so it can
        // raise the EE/GS/MTVU threads' CPU frequency toward the frame deadline, countering the
        // DVFS governor under-clocking emulation's bursty load. EXPERIMENTAL, default OFF.
        // No-op below API 33. Applied at launch (MainActivityRuntime) + live here.
        run {
            val adpf = remember { androidx.compose.runtime.mutableStateOf(com.armsx2.runtime.MainActivityRuntime.prefs.getBoolean("ui.adpf", false)) }
            SegmentedRow(
                label = str("perf.adpf.label"),
                options = listOf(str("common.off"), str("common.on")),
                selectedIndex = if (adpf.value) 1 else 0,
                description = str("perf.adpf.description"),
                onChange = {
                    val on = it == 1
                    adpf.value = on
                    com.armsx2.runtime.MainActivityRuntime.prefs.edit { putBoolean("ui.adpf", on) }
                    runCatching { com.armsx3.NativeApp.setAdpfEnabled(on) }
                },
            )
        }
        SettingsDivider()
        // ---- GPU Turbo (Adreno) -------------------------------------------------
        // A KGSL ioctl that pins the GPU to maximum clocks instead of letting DVFS ramp. Lives
        // here rather than with the driver picker because what it actually trades is heat and
        // battery for frametime stability -- that is a performance decision, not a display one.
        SegmentedRow(
            label = str("renderer.gpuTurbo.label"),
            options = listOf(str("common.off"), str("common.on")),
            selectedIndex = if (s.ps3.gpuTurbo) 1 else 0,
            description = str("renderer.gpuTurbo.description"),
            onChange = {
                val on = it == 1
                apply(s.copy(ps3 = s.ps3.copy(gpuTurbo = on)))
                // Apply live as well as at the next renderer start, so it is testable without
                // rebooting the game.
                runCatching { net.rpcsx.RPCSX.instance.setGpuTurbo(on) }
            },
        )
        SettingsDivider()
        // ---- Silence all logs ---------------------------------------------------
        // A genuine performance lever, not a debug knob: a few games log thousands of lines a
        // second and every one costs a write. Measured at 5-7 FPS on LittleBigPlanet 2, which
        // can be the difference between unplayable and playable.
        //
        // Default OFF, deliberately. A bug report without a log is usually unfixable, and the
        // description says so in as many words -- the setting is here for people who already
        // know the trade and want the frames.
        SegmentedRow(
            label = str("perf.silenceLogs.label"),
            options = listOf(str("common.off"), str("common.on")),
            selectedIndex = if (s.ps3.silenceAllLogs) 1 else 0,
            description = str("perf.silenceLogs.description"),
            onChange = { apply(s.copy(ps3 = s.ps3.copy(silenceAllLogs = it == 1))) },
        )
        SettingsDivider()
        // Affinity Control Mode — opt-in CPU pinning for the EE/VU/GS threads. Android normally
        // leaves them unpinned on purpose (EAS puts the busiest thread on the prime core, and
        // pinning VU to a mid-tier big core measured ~1.4x slower), so this is EXPERIMENTAL and
        // default Disabled. It exists because the tradeoff is workload-dependent: GS-bound titles
        // benefited from an explicitly placed GS thread. Applies on the next boot.
        // The PS3 has no EE/VU/GS -- those are PS2 silicon. RPCS3's analogue is
        // Thread Scheduler Mode, which is three modes, not six orderings. The old
        // picker offered eight options that all collapsed onto these three, so
        // five of them silently did the same thing.
        SegmentedGridRow(
            label = str("perf.scheduler.label"),
            options = listOf(
                str("perf.scheduler.os"),
                str("perf.scheduler.rpcs3"),
                str("perf.scheduler.rpcs3Alt"),
            ),
            selectedIndex = s.affinityMode.coerceIn(0, 2),
            columns = 1,
            description = str("perf.scheduler.description"),
            onChange = { apply(s.copy(affinityMode = it)) },
        )
        SettingsDivider()
        // PS3 CPU. The PS2 speedhacks that were here (EE cycle rate/skip, VU
        // clamping, FPU round modes) describe silicon the PS3 does not have --
        // none of them had anywhere to go in the RPCS3 config.
        CollapsibleSection(str("perf.ps3cpu.title"), initiallyExpanded = true) {
            SegmentedGridRow(
                label = str("perf.ppuDecoder.label"),
                options = listOf(str("perf.decoder.interpreter"), str("perf.decoder.llvm")),
                selectedIndex = s.ps3.ppuDecoder.coerceIn(0, 1),
                columns = 2,
                description = str("perf.ppuDecoder.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(ppuDecoder = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("perf.spuDecoder.label"),
                options = listOf(
                    str("perf.decoder.interpreter"),
                    str("perf.decoder.interpreterDyn"),
                    str("perf.decoder.asmjit"),
                    str("perf.decoder.llvm"),
                ),
                selectedIndex = s.ps3.spuDecoder.coerceIn(0, 3),
                columns = 2,
                description = str("perf.spuDecoder.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(spuDecoder = it))) },
            )
            SettingsDivider()
            SegmentedGridRow(
                label = str("perf.spuBlockSize.label"),
                options = listOf("Safe", "Mega", "Giga"),
                selectedIndex = s.ps3.spuBlockSize.coerceIn(0, 2),
                columns = 3,
                description = str("perf.spuBlockSize.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(spuBlockSize = it))) },
            )
            SettingsDivider()
            // str() is @Composable, so it cannot be called from inside the
            // (non-composable) valueFormatter lambda -- resolve it up front.
            val autoLabel = str("common.auto")
            IntSliderRow(
                label = str("perf.preferredSpuThreads.label"),
                value = s.ps3.preferredSpuThreads.coerceIn(0, 6),
                min = 0,
                max = 6,
                description = str("perf.preferredSpuThreads.description"),
                valueFormatter = { if (it == 0) autoLabel else "$it" },
                onChange = { apply(s.copy(ps3 = s.ps3.copy(preferredSpuThreads = it))) },
            )
            SettingsDivider()
            IntSliderRow(
                label = str("perf.llvmThreads.label"),
                value = s.ps3.llvmThreads.coerceIn(0, 8),
                min = 0,
                max = 8,
                description = str("perf.llvmThreads.description"),
                valueFormatter = { if (it == 0) autoLabel else "$it" },
                onChange = { apply(s.copy(ps3 = s.ps3.copy(llvmThreads = it))) },
            )
            SettingsDivider()
            IntSliderRow(
                label = str("perf.maxSpursThreads.label"),
                value = s.ps3.maxSpursThreads.coerceIn(1, 6),
                min = 1,
                max = 6,
                description = str("perf.maxSpursThreads.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(maxSpursThreads = it))) },
            )
            SettingsDivider()
            IntSliderRow(
                label = str("perf.clocksScale.label"),
                value = s.ps3.clocksScale.coerceIn(10, 300),
                min = 10,
                max = 300,
                description = str("perf.clocksScale.description"),
                valueFormatter = { "$it%" },
                onChange = { apply(s.copy(ps3 = s.ps3.copy(clocksScale = it))) },
            )
            SettingsDivider()
            ToggleRow(
                label = str("perf.spuCache.label"),
                value = s.ps3.spuCache,
                description = str("perf.spuCache.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(spuCache = it))) },
            )
            SettingsDivider()
            ToggleRow(
                label = str("perf.llvmPrecompile.label"),
                value = s.ps3.llvmPrecompile,
                description = str("perf.llvmPrecompile.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(llvmPrecompile = it))) },
            )
            SettingsDivider()
            ToggleRow(
                label = str("perf.spuLoopDetection.label"),
                value = s.ps3.spuLoopDetection,
                description = str("perf.spuLoopDetection.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(spuLoopDetection = it))) },
            )
            SettingsDivider()
            ToggleRow(
                label = str("perf.accurateSpuDma.label"),
                value = s.ps3.accurateSpuDma,
                description = str("perf.accurateSpuDma.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(accurateSpuDma = it))) },
            )
            SettingsDivider()
            // Sits with the SPU rows because that is what it costs, not under a savestate
            // heading where it would read as free.
            ToggleRow(
                label = str("perf.savestateCompatible.label"),
                value = s.ps3.savestateCompatibleMode,
                description = str("perf.savestateCompatible.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(savestateCompatibleMode = it))) },
            )
        }
        SettingsDivider()
        // The PS2 speedhacks here (INTC/wait-loop detection, fast CDVD, instant
        // VU1, VU flag hacks, deferred VU writes, NEON fusions, stall-sim skip)
        // are all EE/VU tricks. The PS3's equivalent tuning is SPU-side and
        // lives in the PS3 CPU section above; the accuracy trade-offs are in
        // Advanced.
        CollapsibleSection(str("perf.spuTuning.title")) {
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
        }
        SettingsDivider()
        // Frame generation. Its own section rather than folded into the GPU one because it is
        // not a rendering option -- it inserts frames that the game never drew, and the choice
        // to do that is a different kind of decision from how the real ones are drawn.
        // Absent from the play build, where libarmsx3_lsfg.so is not in the bundle. The core
        // reports frame generation unavailable without it and every control here would be inert,
        // so showing them would only offer something that cannot work.
        if (com.armsx2.BuildConfig.FRAME_GENERATION)
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
            )
            SettingsDivider()
            ToggleRow(
                str("perf.framegen.performance.label"),
                s.ps3.frameGenPerformance,
                description = str("perf.framegen.performance.description"),
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
            )
            SettingsDivider()
            FrameGenShaderRow()
        }
        SettingsDivider()
        // Compiled-code caches. Separate from the shader cache on the Renderer tab:
        // these hold recompiled PPU/SPU code, not GPU pipelines.
        CollapsibleSection(str("perf.caches.title")) {
            ClearCacheRow(spuOnly = true)
            SettingsDivider()
            ClearCacheRow(spuOnly = false)
        }
    }
}

/**
 * Root of RPCS3's compiled-code cache: `<files>/cache/cache/`.
 *
 * Holds `ppu-<hash>-<name>` directories for firmware modules at the top level, plus
 * `<TITLEID>/ppu-<hash>-EBOOT.BIN` per game. The SPU caches live INSIDE those directories -- a
 * `spu-*.dat` file and a `spuobj-v<n>-<key>/` directory of compiled objects -- which is why
 * clearing PPU necessarily clears SPU with it.
 */
private fun recompilerCacheRoot(context: Context): File =
    File(MainActivityRuntime.assetCopyRoot(context), "cache/cache")

/** Every `ppu-*` directory, both the top-level firmware ones and the per-title ones. */
private fun ppuCacheDirs(root: File): List<File> = buildList {
    root.listFiles()?.forEach { entry ->
        if (!entry.isDirectory) return@forEach
        if (entry.name.startsWith("ppu-")) add(entry)
        // A title id directory; its ppu-* dirs live one level down.
        else entry.listFiles()?.forEach { if (it.isDirectory && it.name.startsWith("ppu-")) add(it) }
    }
}

private fun File.sizeRecursive(): Long =
    runCatching { walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "0 KB"
}

/**
 * Delete the SPU cache only, or the whole PPU cache.
 *
 * SPU-only leaves the compiled PPU modules in place, so booting stays as fast as it was and
 * only the SPU programs rebuild. Clearing PPU removes the directories outright, which takes
 * the SPU caches nested inside them too.
 */
private fun clearRecompilerCache(root: File, spuOnly: Boolean): Pair<Int, Long> {
    var count = 0
    var bytes = 0L

    if (spuOnly) {
        // Two things live here, not one. `spu-*.dat` is the original SPU cache; the persistent SPU
        // LLVM object cache sits beside it in `spuobj-v<n>-<key>/` directories and is by far the
        // larger of the two. Clearing only the .dat files handed the recompiler straight back the
        // objects the user pressed this button to be rid of -- which is exactly what someone
        // clearing an SPU cache to escape stale compiled code needs not to happen.
        val objDirs = root.walkTopDown()
            .filter { it.isDirectory && it.name.startsWith("spuobj-") }
            .toList() // materialise before deleting, so the walk is not mutated under itself
        val datFiles = root.walkTopDown()
            .filter { it.isFile && it.name.startsWith("spu-") && it.extension == "dat" }
            .toList()

        objDirs.forEach { dir ->
            val size = dir.sizeRecursive()
            if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }

        datFiles.forEach { file ->
            // exists() because a .dat inside one of the directories above is already gone.
            if (!file.exists()) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }

        return count to bytes
    }

    ppuCacheDirs(root).forEach { dir ->
        val size = dir.sizeRecursive()
        if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
            count++
            bytes += size
        }
    }
    return count to bytes
}

@Composable
private fun ClearCacheRow(spuOnly: Boolean) {
    val context = LocalContext.current
    val status = remember { mutableStateOf("") }
    val labelKey = if (spuOnly) "perf.clearSpuCache.label" else "perf.clearPpuCache.label"
    val descKey = if (spuOnly) "perf.clearSpuCache.description" else "perf.clearPpuCache.description"

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable {
                // Refuse while a game is loaded: the running VM holds these files open and
                // is still writing to them, so deleting underneath it achieves nothing useful
                // and risks a half-written cache on the next boot.
                if (MainActivityRuntime.eState.value != EmuState.STOPPED) {
                    status.value = I18n.get("perf.clearCache.stopFirst")
                } else {
                    val (count, bytes) = clearRecompilerCache(recompilerCacheRoot(context), spuOnly)
                    status.value = if (count > 0) {
                        I18n.get("perf.clearCache.done").format(count, formatBytes(bytes))
                    } else {
                        I18n.get("perf.clearCache.alreadyEmpty")
                    }
                }
                Toast.makeText(context, status.value, Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                str(labelKey),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                status.value.ifEmpty { I18n.get(descKey) },
                color = Colors.pasx2_blue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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

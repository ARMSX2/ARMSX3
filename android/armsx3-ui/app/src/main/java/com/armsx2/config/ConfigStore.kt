package com.armsx2.config

import com.armsx2.runtime.MainActivityRuntime
import org.json.JSONObject
import androidx.core.content.edit
import java.io.File

/**
 * Persistence + resolution for emu [Settings].
 *
 * Two storage tiers, both in `MainActivityRuntime.prefs`:
 *   - **Global** under `config.global` — the user's baseline. Stored as a
 *     full Settings JSON.
 *   - **Per-game** under `config.game.<serial>` — sparse JSON containing
 *     ONLY the fields the user explicitly overrode for that title. Sparse
 *     storage means future global tweaks still flow through fields the
 *     user hasn't touched per-game.
 *
 * [resolveForGame] is the only method anyone outside the settings UI
 * should call. It returns the merged Settings to push at VM launch.
 *
 * No caching — load on demand. Writes are rare (user clicked Save) and
 * reads happen at game launch (once per launch). Both well within
 * SharedPreferences' overhead.
 */
/**
 * Where overlay setting changes land. The overlay header picks one at
 * runtime via a switch; default is [Game] when a game is loaded,
 * [Global] otherwise.
 *
 *   - [Global] writes the full Settings to `config.global`. Affects every
 *     future game launch that doesn't have its own per-game override.
 *   - [Game] writes the SPARSE diff (only fields differing from current
 *     global) to `config.game.<serial>`. Global stays untouched; only
 *     this title sees the change.
 */
enum class SettingsScope { Global, Game }

object ConfigStore {
    private const val KEY_GLOBAL = "config.global"
    private const val KEY_BLEND_BASIC_MIGRATED = "config.migrated.blendBasic"
    // One-time seed of the (now per-game) renderer/upscale fields from the legacy
    // global prefs, so updating doesn't reset everyone's backend/resolution.
    private const val KEY_RENDERER_MIGRATED = "config.migrated.rendererUpscale"
    // One-time seed of the (now per-game) screen orientation + custom Vulkan driver from
    // their legacy global prefs, so updating doesn't reset a user's rotation lock or GPU driver.
    private const val KEY_ORIENTATION_DRIVER_MIGRATED = "config.migrated.orientationDriver"
    // One-time flip of existing saves to the new Adreno framebuffer-fetch default-on.
    // One-time seed of the (now per-game) output-scaler fields from their legacy
    // global-only prefs, so updating doesn't reset a user's display resolution.
    private const val KEY_OUTPUT_SCALE_MIGRATED = "config.migrated.outputScale"
    private const val KEY_ADRENO_FBFETCH_MIGRATED = "config.migrated.adrenoFbFetchOn"
    // One-time flip of existing all-on OSD saves to the new default-off.
    private const val KEY_OSD_OFF_MIGRATED = "config.migrated.osdDefaultOff"
    private const val KEY_OSD_SCALE_MIGRATED = "config.migrated.osdScale65"
    /** One-time removal of the Frame limit core override that made the FPS cap inert. */
    private const val KEY_FRAME_LIMIT_UNPINNED = "config.migrated.frameLimitUnpinned"
    // One-time reconcile for the fresh-install + reused-data-folder case (people who
    // can't update in place and re-point setup at their old folder). See reconcileReusedFolder.
    private const val KEY_FOLDER_RECONCILE = "config.migrated.folderReconcile"
    private const val KEY_SHADER_INTERP_MIGRATED = "config.migrated.shaderInterpOff"
    private const val KEY_XFLOAT_ACCURATE_MIGRATED = "config.migrated.xfloatAccurate"
    private const val KEY_SPU_DECODER_RESTORE = "config.migrated.spuDecoderRestoreLlvm"
    private const val KEY_XFLOAT_BACK_TO_APPROX = "config.migrated.xfloatBackToApprox"
    private const val KEY_PRECISE_SPU_OFF = "config.migrated.preciseSpuVerifyOff"
    // Oboe became the Android default in 0.7.2; move anyone still on the old Cubeb default.
    private const val KEY_AUDIO_OBOE = "config.migrated.audioOboeDefault"
    private const val KEY_ATOMIC_DMA_OFF = "config.migrated.atomicDmaStoresOff"
    // Bumped: the first pass recorded only Vblank Rate, which did not hold on its own.
    private const val KEY_VBLANK_60 = "config.migrated.frameCap60"
    // Clears core overrides left behind by the profiling work.
    private const val KEY_DIAG_OVERRIDES_PURGED = "config.migrated.diagOverridesPurged"
    // Bumped: the profiler was recorded again during the 0.5 debugging work, after the first
    // purge had already marked itself done.
    private const val KEY_DIAG_OVERRIDES_PURGED_2 = "config.migrated.diagOverridesPurged2"
    // Core settings left pinned as raw overrides by the 0.5 debugging sessions.
    private const val KEY_TUNING_OVERRIDES_PURGED = "config.migrated.tuningOverridesPurged"
    // Per-title Accurate SPU Reservations values left behind by the same debugging.
    private const val KEY_PERGAME_RSV_CLEARED = "config.migrated.perGameRsvCleared"
    private const val KEY_RELAXED_ZCULL_ON = "config.migrated.relaxedZcullOn"
    private const val KEY_RELAXED_ZCULL_OFF = "config.migrated.relaxedZcullOff"
    // The relaxed-ZCULL default was recorded as a raw core override as well, and the OFF
    // migration above only ever corrected the curated field.
    private const val KEY_RELAXED_ZCULL_OVERRIDE_PURGED = "config.migrated.relaxedZcullOverridePurged"
    // Per-title core settings that differ from the safe global default, seeded once into the
    // title's own override so the global stays conservative. Keyed by serial; other regions of
    // the same game need their own entry.
    private const val KEY_PER_GAME_SEED = "config.migrated.perGameSeedV1"
    private val PER_GAME_SEED: Map<String, Map<String, Any>> = mapOf(
        // Spider-Man: Web of Shadows. Its SPURS reservation traffic serialises behind the global
        // exclusive vm::writer_lock taken by every reservation_op, which no amount of CPU can
        // help: measured all six SPU threads and several PPUs yielding at the same rate, 18.8% of
        // total CPU in sched_yield. Turning accurate reservations off routes SPURS through the
        // lock-free path in SPUThread.cpp and dropped vm::writer_lock from 8.06% to 0.96%.
        //
        // Deliberately per-game and not a global default. It is off-spec, upstream defaults it
        // on, and Sonic Unleashed fails EARLIER with it off, so it is not safe to apply blindly.
        "BLUS30218" to mapOf("ps3AccurateSpuRsv" to false),    )
    // The VRAM limit is a hard heap cap, not an eviction threshold; too low fails allocations.
    private const val KEY_VRAM_LIMIT_1024 = "config.migrated.vramCap2048b"
    private const val KEY_AFFINITY_ON = "config.migrated.affinityScheduler"
    // ...and back off it: the mask it enables confines six SPU threads to four cores.
    private const val KEY_AFFINITY_OS = "config.migrated.affinitySchedulerOff"
    private const val KEY_TIMESTRETCH_OFF = "config.migrated.audioTimeStretchOff"
    // Scaling Mode row changed meaning; a stored 0 used to resolve to Bilinear, now Nearest.
    private const val KEY_CAS_MODE_BILINEAR = "config.migrated.casModeBilinear"
    // Mirror of the settings, written INTO the data folder so a later fresh install that
    // reuses the same folder can restore them (SharedPreferences don't survive uninstall).
    private const val BACKUP_FILENAME = "armsx2-settings.json"
    private fun keyForGame(serial: String) = "config.game.$serial"

    // Memoized result of loadGlobal(). The function below is a JSON parse plus every
    // migration block in this file -- 24,555 dex instructions by ART's count, over its
    // JIT ceiling, so it runs interpreted every single call. That would be fine if it
    // were called rarely, but EmulationSurface's frame-rate monitor re-resolves the
    // config every 5 seconds of gameplay (measured: one ART bailout log line per 5.00s
    // for entire sessions), all to read one boolean. The migrations are one-shot by
    // their own prefs flags, so caching the parsed result is behavior-identical; the
    // cache is refreshed by saveGlobal (the only writer of KEY_GLOBAL after boot) and
    // dropped by reconcileReusedFolder, whose restore writes the pref directly.
    @Volatile
    private var cachedGlobal: Settings? = null

    fun loadGlobal(): Settings {
        cachedGlobal?.let { return it }
        val raw = MainActivityRuntime.prefs.getString(KEY_GLOBAL, null)
        var parsed = if (raw != null) {
            try { Settings.fromJson(JSONObject(raw)) } catch (_: Exception) { Settings() }
        } else {
            Settings()
        }
        var dirty = false

        // Legacy: the HW scaler + screen-resolution override were global-only prefs
        // before they became per-game scoped. Adopt whatever the user had set.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_OUTPUT_SCALE_MIGRATED, false))
        {
            val legacyScaler = MainActivityRuntime.prefs.getInt("ui.hwScaler", 0)
            val legacyRes = MainActivityRuntime.prefs.getString("ui.screenResOverride", "auto") ?: "auto"
            if (legacyScaler != 0 || legacyRes != "auto")
            {
                parsed = parsed.copy(hwScaler = legacyScaler, screenResOverride = legacyRes)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_OUTPUT_SCALE_MIGRATED, true) }
        }

        // A diagnostic build pinned the SPU decoder to the interpreter to prove the
        // ARM64 SPU LLVM recompiler was miscompiling. That value persists in the
        // settings store, so put anyone carrying it back on the recompiler once --
        // the interpreter is a debugging tool, not something to ship someone.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_SPU_DECODER_RESTORE, false)) {
            if (raw != null && parsed.ps3.spuDecoder == 0) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(spuDecoder = 3))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_SPU_DECODER_RESTORE, true) }
        }

        // Audio time-stretching costs real CPU for no benefit on a handheld: a
        // profile of Skate 3 put soundtouch's FIRFilter::evaluateFilterMulti and
        // TDStretch::calcCrossCorrAccumulate together at ~8% of all emulator C++
        // time. Upstream defaults it off; a stale stored value had it on.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_TIMESTRETCH_OFF, false)) {
            if (raw != null && parsed.ps3.audioTimeStretch) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(audioTimeStretch = false))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_TIMESTRETCH_OFF, true) }
        }

        // The Scaling Mode row changed meaning, so a stored 0 has to move with it.
        //
        // casMode used to be read as PCSX2's CAS mode, where 0 meant "CAS off" and the mode
        // then fell through to what IntegerScaling wrote, which was Bilinear. It is now the
        // row's own index, where 0 means Nearest. Every existing save holds 0, so leaving it
        // alone would silently move everyone from Bilinear to Nearest -- a visibly harder,
        // more aliased image that nobody asked for.
        //
        // Safe to move all of them: until this change the row could not express Nearest at
        // all (picking it asked for nothing), so a stored 0 never meant a deliberate choice.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_CAS_MODE_BILINEAR, false)) {
            if (raw != null && parsed.casMode == 0) {
                parsed = parsed.copy(casMode = 1)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_CAS_MODE_BILINEAR, true) }
        }

        // Turn the scheduler on so the new big.LITTLE affinity mask applies.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_AFFINITY_ON, false)) {
            if (raw != null && parsed.affinityMode == 0) {
                parsed = parsed.copy(affinityMode = 2)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_AFFINITY_ON, true) }
        }

        // ...and undo it. The migration above turned the scheduler on so the big.LITTLE mask
        // would apply, keeping SPU and RSX off the A510s. That is right for one thread per core
        // and wrong at six: the mask hands the six SPU threads four cores between them, so each
        // gets about two thirds of one, which is worse than a thread owning an A510 outright.
        //
        // Measured in game on a Snapdragon 8 Gen 2, from the masks the threads actually carry:
        // Android grants the app cores 0-7, we confine SPU to 3-6, and the device sits at 60%
        // with cores 0-2 idle while frames are slow. Web of Shadows is visibly better at OS.
        //
        // Same reason these devices are reported to do better under native Linux, which applies
        // no such policy. The other modes stay selectable.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_AFFINITY_OS, false)) {
            if (raw != null && parsed.affinityMode == 2) {
                parsed = parsed.copy(affinityMode = 0)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_AFFINITY_OS, true) }
        }

        // Undo the relaxed-ZCULL default: it stopped Skate 3 rendering.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_RELAXED_ZCULL_OFF, false)) {
            if (raw != null && parsed.ps3.relaxedZcull) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(relaxedZcull = false))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_RELAXED_ZCULL_OFF, true) }
        }

        // Seed the per-title core settings once. Only fields the title does not already carry
        // are written, so a deliberate change is never overwritten.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_PER_GAME_SEED, false)) {
            runCatching {
                for ((serial, fields) in PER_GAME_SEED) {
                    val existing = loadOverrides(serial) ?: JSONObject()
                    var changed = false
                    for ((key, value) in fields) {
                        if (!existing.has(key)) {
                            existing.put(key, value)
                            changed = true
                        }
                    }
                    if (changed) saveOverrides(serial, existing)
                }
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_PER_GAME_SEED, true) }
        }

        // Bring stored VRAM caps up to 3072.
        //
        // This value is VMA's pHeapSizeLimit, a hard ceiling rather than an eviction threshold, so
        // a low value does not make the cache release earlier -- it makes allocation fail earlier.
        // The God of War 3 demo was measured failing a routine 24MB request at 1024 while holding
        // 1.6GB resident and 280MB in that pool, and failing at 2048 one screen later. A
        // deliberate choice of some other value is left alone.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_VRAM_LIMIT_1024, false)) {
            if (raw != null && (parsed.ps3.vramLimitMb == 1024 || parsed.ps3.vramLimitMb == 3072)) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(vramLimitMb = 2048))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_VRAM_LIMIT_1024, true) }
        }

        // Clear the core settings left pinned while debugging 0.5.
        //
        // These were set to test things and never unset, and a raw override beats the UI silently:
        // the settings screen read SPU Block Size = Safe while config.yml read Mega for hours.
        // Mega is the one that mattered -- it produces very large compilation units, and those are
        // what fail AArch64 register allocation with "Cannot scavenge register without an
        // emergency spill slot", which is what put threads on the interpreter fallback in the
        // first place. Every "cannot be compiled" in those sessions traces back to it.
        //
        // Cleared in every scope, since a title can pin a key the global also pins: Arkham City
        // carried Accurate SPU Reservations true per-title against false globally, so clearing one
        // did nothing. Whatever the settings screens show becomes what actually runs.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_TUNING_OVERRIDES_PURGED, false)) {
            runCatching {
                CoreSettingOverrides.forgetEverywhere(
                    "Core@@SPU Block Size",
                    "Core@@SPU Decoder",
                    "Core@@Accurate SPU Reservations",
                    "Core@@Accurate SPU DMA",
                )
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_TUNING_OVERRIDES_PURGED, true) }
        }

        // Drop per-title Accurate SPU Reservations values, except the one title that needs it.
        //
        // Turning it off was tried per-title as well as globally while debugging 0.5, and off is
        // off-spec: it forces the SPURS scheduler to HLE and bypasses the reservation lock, so a
        // title left that way desyncs and its SPU threads end up executing whatever they land on.
        // Batman: Arkham City carried it off this way and died with "Unknown STOP code: 0x0".
        //
        // Web of Shadows keeps it, since it is the title the setting was measured on and it is
        // the one that gains from it.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_PERGAME_RSV_CLEARED, false)) {
            runCatching {
                for (key in MainActivityRuntime.prefs.all.keys.toList()) {
                    if (!key.startsWith("config.game.")) continue
                    if (key == keyForGame("BLUS30218")) continue

                    val serial = key.removePrefix("config.game.")
                    val stored = loadOverrides(serial) ?: continue
                    if (!stored.has("ps3AccurateSpuRsv")) continue

                    stored.remove("ps3AccurateSpuRsv")
                    saveOverrides(serial, stored)
                }
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_PERGAME_RSV_CLEARED, true) }
        }

        // ...and take the raw override with it. The ON migration recorded the same setting a
        // second time as a core override, and the OFF migration above only corrected the
        // curated field, so the two stores disagreed: relaxedZcull was false everywhere the UI
        // could show it while the override still held "true".
        //
        // The override wins, because overrides re-push at the tail of applyTo, after the curated
        // store has written the setting. So the toggle read OFF, config.yml read
        // "Relaxed ZCULL Sync: true" every boot, and nothing in the UI could change that.
        //
        // Not cosmetic: relaxed sync is what lets queries be read while still pending, which is
        // the "Dubious query data pushed to cond render" path, and it also selects emulated
        // predication in the VK backend.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_RELAXED_ZCULL_OVERRIDE_PURGED, false)) {
            runCatching {
                CoreSettingOverrides.forget(
                    SettingsScope.Global, null,
                    "Video@@Relaxed ZCULL Sync",
                )
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_RELAXED_ZCULL_OVERRIDE_PURGED, true) }
        }


        // Oboe is the Android default now. Only move people sitting on the previous default
        // (Cubeb, index 2) -- anyone who deliberately picked Null or another backend keeps it.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_AUDIO_OBOE, false)) {
            if (raw != null && parsed.ps3.audioRenderer == 2) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(audioRenderer = 4))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_AUDIO_OBOE, true) }
        }

        // The ARM64 block checksum is fixed, so the full-compare workaround can go.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_PRECISE_SPU_OFF, false)) {
            if (raw != null && parsed.ps3.preciseSpuVerification) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(preciseSpuVerification = false))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_PRECISE_SPU_OFF, true) }
        }

        // ...and back off it again: Accurate was a wrong guess at the SPU freeze,
        // which was really the block-verification checksum. Return anyone carrying
        // the forced Accurate value to upstream's Approximate.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_XFLOAT_BACK_TO_APPROX, false)) {
            if (raw != null && parsed.ps3.spuXFloat == 0) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(spuXFloat = 1))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_XFLOAT_BACK_TO_APPROX, true) }
        }

        // Return the two reservation settings to upstream's defaults, both off.
        //
        // Accurate SPU DMA + Accurate Cache Line Stores together route every 128-byte
        // SPU DMA store through do_cell_atomic_128_store, so bulk DMA turns into one
        // atomic reservation store per cache line. Contention then outruns the rate the
        // reservations can drain and an SPU spins in do_putllc indefinitely, taking the
        // PPU down with it. Minecraft froze on world chunk load, which is bulk SPU DMA
        // and nothing else. Load-dependent, hence the intermittency.
        //
        // Both are stored true on existing installs, so the default change alone would
        // not reach anyone who has already run the app.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_ATOMIC_DMA_OFF, false)) {
            if (raw != null && (parsed.ps3.accurateCacheLine || parsed.ps3.accurateSpuDma)) {
                parsed = parsed.copy(
                    ps3 = parsed.ps3.copy(accurateCacheLine = false, accurateSpuDma = false),
                )
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_ATOMIC_DMA_OFF, true) }
        }

        // Put Vblank Rate back to 60, which is both upstream's default and what a PS3
        // actually runs at.
        //
        // It was sitting at 120. Nothing in this app sets that, so it came from the core
        // settings screen, and it makes the emulator aim for 120fps: twice the frames, twice
        // the RSX command volume, twice the GPU work, on a handheld. Chasing a 120Hz panel
        // in PS3 emulation costs battery and heat for frames the games were never designed
        // to produce.
        //
        // Recorded as a core override rather than written to config.yml directly, so it
        // survives the settings push on every boot and stays changeable afterwards.
        //
        // Global tier, like every migration in this method: it is correcting the baseline
        // everyone inherited, not a choice made for one title.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_VBLANK_60, false)) {
            runCatching {
                CoreSettingOverrides.record(SettingsScope.Global, null, "Video@@Vblank Rate", "60")
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_VBLANK_60, true) }
        }

        // The migration above used to pin Video@@Frame limit to 60 as well, as a second way to
        // reach a 60Hz cap in case the Vblank Rate override did not hold.
        //
        // Frame limit is not a spare knob: it is the node the Display FPS Cap row writes. Pinned as
        // a core override it replayed AFTER every settings push, so the cap silently did nothing for
        // every value that uses the enum -- 30, 50, 60, 120 -- while 20 and 45 appeared to work
        // because those are not presets and go to the free-form Second Frame Limit instead.
        // Measured: the UI wrote '30', settingsSet accepted it, and the core still reported
        // frame_limit=_60 on every flip.
        //
        // Nothing is lost by dropping it. Frame limit Auto resolves to the vblank rate
        // (RSXThread.cpp, the _auto case), and the Vblank Rate override above is already 60, so the
        // 60Hz default this was protecting still holds.
        //
        // forgetEverywhere, not a scoped forget: overrides live in two stores across two scopes, and
        // an install that ran the old migration has the Global one recorded already. Anyone who
        // deliberately set a Frame limit in All Core Settings loses that override here, which is the
        // right trade against a cap control that cannot work.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_FRAME_LIMIT_UNPINNED, false)) {
            runCatching { CoreSettingOverrides.forgetEverywhere("Video@@Frame limit") }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_FRAME_LIMIT_UNPINNED, true) }
        }

        // Diagnostic settings that got recorded as core overrides during the profiling work
        // and would otherwise follow people into a release build.
        //
        // RSX Profiler defaults to false and belongs that way: it is instrumentation, kept
        // for the next investigation rather than for playing. Eager Surface Readback names a
        // node this build no longer has at all, so replaying it only produces a failed set
        // and a log line.
        //
        // An override is a record of a deliberate choice, so these are removed by path
        // rather than by clearing the store, which would take the user's real edits with it.
        //
        // Global tier only: the per-game sets did not exist while the profiling build was
        // out, so there is nowhere else these can have been recorded.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_DIAG_OVERRIDES_PURGED, false)) {
            runCatching {
                CoreSettingOverrides.forget(
                    SettingsScope.Global, null,
                    "Video@@RSX Profiler", "Video@@Eager Surface Readback",
                )
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_DIAG_OVERRIDES_PURGED, true) }
        }

        // Again, for the profiling done to fix Web of Shadows, Sonic Unleashed and God of War 3.
        //
        // The RSX profiler writes a bucket report every 300 frames and keeps per-scope timers on
        // the RSX thread, so it is not something to leave switched on for a release. It was found
        // still recorded as a raw core override -- config.yml read "RSX Profiler: true" while
        // nothing in the UI said so, the same divergence as the relaxed-ZCULL one, because
        // overrides re-push at the tail of applyTo.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_DIAG_OVERRIDES_PURGED_2, false)) {
            runCatching {
                CoreSettingOverrides.forget(
                    SettingsScope.Global, null,
                    "Video@@RSX Profiler", "Video@@Eager Surface Readback", "Video@@GPU Profiler",
                )
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_DIAG_OVERRIDES_PURGED_2, true) }
        }

        // Move anyone still on the old Approximate xfloat default onto Accurate.
        // Approximate corrupted SPU float registers badly enough that a job
        // manager built a DMA command out of one; see Settings.spuXFloat. A
        // deliberate choice of Relaxed/Inaccurate is left alone.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_XFLOAT_ACCURATE_MIGRATED, false)) {
            if (raw != null && parsed.ps3.spuXFloat == 1) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(spuXFloat = 0))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_XFLOAT_ACCURATE_MIGRATED, true) }
        }

        // The shader interpreter cannot compile on Adreno -- vkCreateGraphicsPipelines
        // returns VK_ERROR_UNKNOWN and the link ensure() kills the process during
        // boot, before any game renders. Anyone carrying the old default (2, the
        // upstream one) has a config that cannot boot, so move it to plain Async
        // Recompiler once. Interpreter-only (3) is left alone: that is a deliberate
        // choice, not an inherited default.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_SHADER_INTERP_MIGRATED, false)) {
            if (raw != null && parsed.ps3.shaderMode == 2) {
                parsed = parsed.copy(ps3 = parsed.ps3.copy(shaderMode = 1))
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_SHADER_INTERP_MIGRATED, true) }
        }

        // Legacy: "Basic" blending migration.
        if (raw != null && !MainActivityRuntime.prefs.getBoolean(KEY_BLEND_BASIC_MIGRATED, false) &&
            parsed.accurateBlendingUnit == 4) {
            parsed = parsed.copy(accurateBlendingUnit = 1)
            dirty = true
        }
        if (!MainActivityRuntime.prefs.getBoolean(KEY_BLEND_BASIC_MIGRATED, false)) {
            MainActivityRuntime.prefs.edit { putBoolean(KEY_BLEND_BASIC_MIGRATED, true) }
        }

        // Seed renderer/upscale from the legacy global prefs (where they used to
        // live) into the Settings tier, once. After this they're scope-aware like
        // every other setting; the old prefs become vestigial.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_RENDERER_MIGRATED, false)) {
            MainActivityRuntime.prefs.getString("renderer", null)?.takeIf { it.isNotBlank() }?.let {
                parsed = parsed.copy(renderer = it)
                dirty = true
            }
            legacyUpscalePref()?.let {
                parsed = parsed.copy(upscaleFloat = it)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_RENDERER_MIGRATED, true) }
        }

        // Same one-time seed for screen orientation + the custom Vulkan driver, which used
        // to be global-only prefs ("ui.orientation" / "customDriverId"). After this they're
        // scope-aware (global ∘ per-game) like renderer; the old prefs become vestigial.
        if (!MainActivityRuntime.prefs.getBoolean(KEY_ORIENTATION_DRIVER_MIGRATED, false)) {
            val legacyOrient = MainActivityRuntime.prefs.getInt("ui.orientation", 0)
            if (legacyOrient != parsed.orientation) {
                parsed = parsed.copy(orientation = legacyOrient)
                dirty = true
            }
            MainActivityRuntime.prefs.getString("customDriverId", null)?.takeIf { it.isNotBlank() }?.let {
                parsed = parsed.copy(customDriverId = it)
                dirty = true
            }
            MainActivityRuntime.prefs.edit { putBoolean(KEY_ORIENTATION_DRIVER_MIGRATED, true) }
        }

        // Adreno framebuffer-fetch is now default-on. Flip existing global saves that
        // still carry the old default-off ONCE, so updating users get the fast
        // accurate-blending path too (they can turn it back off in the Renderer tab).
        if (raw != null && !MainActivityRuntime.prefs.getBoolean(KEY_ADRENO_FBFETCH_MIGRATED, false) &&
            !parsed.adrenoFbFetch) {
            parsed = parsed.copy(adrenoFbFetch = true)
            dirty = true
        }
        if (!MainActivityRuntime.prefs.getBoolean(KEY_ADRENO_FBFETCH_MIGRATED, false)) {
            MainActivityRuntime.prefs.edit { putBoolean(KEY_ADRENO_FBFETCH_MIGRATED, true) }
        }

        // The perf OSD (FPS/stats counters) now defaults OFF — it read as clutter.
        // Flip existing saves that still sit at the old all-on default ONCE; a user
        // who'd already turned any element off is left untouched, and the in-game
        // "OSD" toggle re-enables everything. (The bottom-left/right summaries are
        // handled by their own absent-key default for pre-2.6 saves.)
        if (raw != null && !MainActivityRuntime.prefs.getBoolean(KEY_OSD_OFF_MIGRATED, false) &&
            parsed.osdShowFps && parsed.osdShowVps && parsed.osdShowSpeed &&
            parsed.osdShowCpu && parsed.osdShowGpu && parsed.osdShowResolution &&
            parsed.osdShowGsStats && parsed.osdShowFrameTimes &&
            parsed.osdShowHardwareInfo && parsed.osdShowVersion) {
            parsed = parsed.copy(
                osdShowFps = false, osdShowVps = false, osdShowSpeed = false,
                osdShowCpu = false, osdShowGpu = false, osdShowResolution = false,
                osdShowGsStats = false, osdShowFrameTimes = false,
                osdShowHardwareInfo = false, osdShowVersion = false,
            )
            dirty = true
        }
        if (!MainActivityRuntime.prefs.getBoolean(KEY_OSD_OFF_MIGRATED, false)) {
            MainActivityRuntime.prefs.edit { putBoolean(KEY_OSD_OFF_MIGRATED, true) }
        }

        // OSD text now defaults to 65% (was 100%) to match NetherSX2 — at 100 the stats block eats
        // a handheld screen. Only saves sitting on the exact old default are moved; anyone who
        // picked their own size keeps it.
        if (raw != null && !MainActivityRuntime.prefs.getBoolean(KEY_OSD_SCALE_MIGRATED, false) &&
            parsed.osdScale == 100) {
            parsed = parsed.copy(osdScale = 65)
            dirty = true
        }
        if (!MainActivityRuntime.prefs.getBoolean(KEY_OSD_SCALE_MIGRATED, false)) {
            MainActivityRuntime.prefs.edit { putBoolean(KEY_OSD_SCALE_MIGRATED, true) }
        }

        if (dirty) saveGlobal(parsed)
        cachedGlobal = parsed
        return parsed
    }

    /** Read the legacy global upscale pref (float, or older int/string), or null. */
    private fun legacyUpscalePref(): Float? {
        val all = MainActivityRuntime.prefs.all
        fun coerce(raw: Any?): Float? = when (raw) {
            is Float -> raw
            is Double -> raw.toFloat()
            is Int -> raw.toFloat()
            is Long -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            else -> null
        }?.coerceIn(0.25f, 8.0f)
        return coerce(all["upscaleFloat"]) ?: coerce(all["upscale"])
    }

    fun saveGlobal(s: Settings) {
        MainActivityRuntime.prefs.edit { putString(KEY_GLOBAL, s.toJson().toString()) }
        cachedGlobal = s
        writeBackupMirror()
    }

    /**
     * Persist capability-aware defaults only when this is genuinely a fresh install.
     *
     * Call after [reconcileReusedFolder]: a reused data directory gets first chance to
     * restore its prior global settings, while an empty install starts with the
     * zero-frame GS queue on capable devices. Low-end devices retain the smoother
     * two-frame queue. Once persisted, this never changes an existing user's choice.
     */
    fun seedFreshInstallDefaults(context: android.content.Context) {
        if (MainActivityRuntime.prefs.getString(KEY_GLOBAL, null) != null) return
        // Low Latency (zero-frame GS queue) is NOT the default any more — it was briefly seeded on
        // capable devices, but a zero-frame queue gives the GS thread no slack and cost smoothness
        // on too many setups. Everyone starts on PCSX2's two-frame queue and can opt in from the
        // Performance tab. Settings.vsyncQueueSize already defaults to 2, so this just materialises
        // the global save that the rest of the config layer keys "is this a fresh install?" off.
        saveGlobal(Settings())
    }

    // Migration 1 (config.migrated.lowLatencyDefault) flipped existing capable devices ON. It is
    // retired rather than deleted: the key must never be reused, or an install that already ran it
    // would skip the correction below.
    private const val KEY_LOWLATENCY_OFF_MIGRATED = "config.migrated.lowLatencyOff"
    /**
     * One-time correction that undoes migration 1: puts existing installs back on the two-frame GS
     * queue. Keyed separately so it runs exactly once even on devices that already took the earlier
     * flip, and after it runs the user's own choice sticks.
     *
     * Caveat, deliberately accepted: this cannot distinguish "queue 0 because migration 1 set it"
     * from "queue 0 because the user chose it", so anyone who opted in during the short window that
     * shipped the ON default gets reset once and has to re-enable it.
     */
    fun migrateLowLatencyOff(context: android.content.Context) {
        if (MainActivityRuntime.prefs.getBoolean(KEY_LOWLATENCY_OFF_MIGRATED, false)) return
        MainActivityRuntime.prefs.edit().putBoolean(KEY_LOWLATENCY_OFF_MIGRATED, true).apply()
        // Fresh installs are handled by seedFreshInstallDefaults; only touch an existing global save.
        if (MainActivityRuntime.prefs.getString(KEY_GLOBAL, null) == null) return
        val g = loadGlobal()
        if (g.vsyncQueueSize == 0) saveGlobal(g.copy(vsyncQueueSize = 2))
    }

    /** Load the sparse per-game override blob, or null if there are none. */
    fun loadOverrides(serial: String): JSONObject? {
        val raw = MainActivityRuntime.prefs.getString(keyForGame(serial), null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun saveOverrides(serial: String, overrides: JSONObject) {
        MainActivityRuntime.prefs.edit { putString(keyForGame(serial), overrides.toString()) }
        writeBackupMirror()
    }

    fun clearOverrides(serial: String) {
        MainActivityRuntime.prefs.edit { remove(keyForGame(serial)) }
        writeBackupMirror()
    }

    /**
     * Resolve effective Settings for a VM launch:
     *   per-game override (if present) ∘ global ∘ defaults.
     *
     * Pass null serial to skip the per-game tier (BIOS boots, anonymous
     * launches via Swap/Boot Disc when no GameInfo was carried through).
     */
    fun resolveForGame(serial: String?): Settings {
        val global = loadGlobal()
        if (serial == null) return global
        val overrides = loadOverrides(serial) ?: return global
        return Settings.merge(global, overrides)
    }

    /**
     * Single entry point for overlay tabs to persist a settings change.
     * Scope picks the storage tier; serial may be null (Global is the
     * only valid scope in that case).
     *
     * Game scope stores a SPARSE override: a field the user never touched for this title
     * is absent, so a later global tweak still reaches it. That inheritance is the point
     * and is unchanged.
     *
     * What IS new: an override is STICKY once it exists. The old rule — store exactly the
     * fields that differ from global right now — could not tell "the user set this for
     * this game, and it happens to match global" from "the user never touched it", so it
     * stored nothing in both cases. Set Cheats on for a game while global also had them
     * on, later turn global off, and the game silently lost its setting; that's the
     * reported "global overwrites per-game and vice versa". Now a field is pinned to the
     * game once it's overridden — by differing from global, by the user changing it here
     * ([previous]), or by already being pinned — and only [clearOverrides] unpins it.
     */
    fun save(scope: SettingsScope, serial: String?, updated: Settings, previous: Settings? = null) {
        if (scope == SettingsScope.Game && serial != null) {
            val global = loadGlobal()
            val overrides = Settings.diff(global, updated)
            // Every field, so a pinned key can be given its CURRENT value even when that
            // value equals global's (the diff above necessarily omits it).
            val full = updated.toJson()
            val existing = loadOverrides(serial)
            val pinned = LinkedHashSet<String>()
            existing?.keys()?.forEach { pinned.add(it) }
            // What the user just changed, pinned even if it landed on global's value —
            // otherwise editing a field in Game scope could silently un-pin it.
            val changedNow = LinkedHashSet<String>()
            previous?.let { Settings.diff(it, updated).keys().forEach { k -> changedNow.add(k); pinned.add(k) } }
            pinned.forEach { key ->
                if (overrides.has(key))
                    return@forEach
                // ★ For a pinned key the caller did NOT touch in this save, keep the value ALREADY
                // STORED rather than re-pinning whatever `updated` happens to hold. Every screen
                // writes the whole Settings object, so `updated` can be a stale snapshot; the old
                // unconditional `full.get(key)` then wrote that stale value straight back over a
                // good override. That is how a per-game FPS cap of 30 came back as 0 and STAYED 0 —
                // the pin made the wrong value sticky, so it survived even after the writers were
                // fixed. Only trust `updated` for keys `previous` proves the caller just changed.
                //
                // When `previous` is absent the caller cannot tell us what it changed, so fall back
                // to the original behaviour rather than silently altering semantics for those paths.
                val trustUpdated = changedNow.contains(key) || previous == null
                when {
                    trustUpdated && full.has(key) -> overrides.put(key, full.get(key))
                    existing != null && existing.has(key) -> overrides.put(key, existing.get(key))
                    full.has(key) -> overrides.put(key, full.get(key))
                }
            }
            saveOverrides(serial, overrides)
        } else {
            saveGlobal(updated)
        }
    }

    // ---- Fresh-install + reused-data-folder recovery (#9) ----
    //
    // SharedPreferences (where config.global lives) are WIPED when the app is
    // uninstalled. People who can't update in place — different signing key between the
    // old-UI and new-UI builds — must uninstall+reinstall, then re-point setup at their
    // existing data folder to keep their games/BIOS/saves. That folder still holds their
    // old native config (PCSX2-Android.ini) + per-game gamesettings/*.ini, so the core
    // applies old settings while the new UI shows defaults and then clobbers them.
    //
    // Fix: mirror settings INTO the folder on every save, and on first run — when there
    // is NO config.global in prefs — restore from that mirror (lossless) or, failing that,
    // seed from the old PCSX2-Android.ini (best-effort). The `config.global == null` guard
    // means anyone ALREADY on the new UI is never touched: with settings present there is
    // nothing to recover, so this can only ADD when there are none, never overwrite.

    private fun backupFile(): File? {
        val root = MainActivityRuntime.currentInitDataRoot()?.takeIf { it.isNotBlank() } ?: return null
        return File(root, BACKUP_FILENAME)
    }

    /** Write the in-folder settings mirror (global + every per-game blob). Cheap; called
     *  on each save. Silently no-ops until the data root is known. */
    private fun writeBackupMirror() {
        val file = backupFile() ?: return
        runCatching {
            val root = JSONObject()
            MainActivityRuntime.prefs.getString(KEY_GLOBAL, null)?.let { root.put("global", JSONObject(it)) }
            val games = JSONObject()
            for ((k, v) in MainActivityRuntime.prefs.all) {
                if (k.startsWith("config.game.") && v is String) {
                    runCatching { games.put(k.removePrefix("config.game."), JSONObject(v)) }
                }
            }
            if (games.length() > 0) root.put("games", games)
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    /** One-time, guarded recovery for the fresh-install + reused-folder case. Call once at
     *  app init (after the data root is resolved). Ordered: (1) restore losslessly from the
     *  in-folder mirror a prior new-UI install left; (2) else seed config.global from the
     *  folder's PCSX2-Android.ini. NEVER runs when config.global already exists. */
    fun reconcileReusedFolder() {
        if (MainActivityRuntime.prefs.getBoolean(KEY_FOLDER_RECONCILE, false)) return
        MainActivityRuntime.prefs.edit { putBoolean(KEY_FOLDER_RECONCILE, true) }
        // Hard guard: an existing new-UI user (has config.global) is off-limits.
        if (MainActivityRuntime.prefs.getString(KEY_GLOBAL, null) != null) return

        // The restore below writes KEY_GLOBAL behind loadGlobal's back; drop any
        // default Settings() a pre-restore call may have pinned in the cache.
        cachedGlobal = null

        // (1) Lossless restore from the in-folder mirror (written by a prior new-UI install).
        val mirror = backupFile()
        if (mirror != null && mirror.exists() && mirror.length() > 0L) {
            val restored = runCatching {
                val root = JSONObject(mirror.readText())
                root.optJSONObject("global")?.let { g ->
                    MainActivityRuntime.prefs.edit { putString(KEY_GLOBAL, g.toString()) }
                }
                root.optJSONObject("games")?.let { games ->
                    val it = games.keys()
                    while (it.hasNext()) {
                        val serial = it.next()
                        games.optJSONObject(serial)?.let { g ->
                            MainActivityRuntime.prefs.edit { putString(keyForGame(serial), g.toString()) }
                        }
                    }
                }
                MainActivityRuntime.prefs.getString(KEY_GLOBAL, null) != null
            }.getOrDefault(false)
            if (restored) return
        }

        // The old native PCSX2-Android.ini seed is gone with the PS2 app: it is written by
        // that emulator under its own package, so nothing under com.armsx3 can ever have one.
    }

    /**
     * Delete every settings layer that lives OUTSIDE SharedPreferences. Part of the full app
     * reset, and it is not optional: prefs are only one of four stores, and clearing them alone
     * leaves the reset silently undone.
     *
     *  - the in-folder mirror ([BACKUP_FILENAME]): [reconcileReusedFolder] re-seeds prefs from
     *    it precisely BECAUSE config.global is missing, which is exactly the state a reset
     *    creates — so the next launch would restore everything just wiped.
     *  - the `gamesettings` directory of per-game INIs. The core reads those directly and they
     *    SHADOW the global tier, so leaving them behind means per-game tweaks survive a reset
     *    and then look like settings that "do nothing".
     *
     * Games, firmware, save data, save states and covers are untouched.
     */
    fun purgeAllSettingsFiles() {
        runCatching { backupFile()?.delete() }
        val root = MainActivityRuntime.currentInitDataRoot()?.takeIf { it.isNotBlank() } ?: return
        runCatching { File(root, "gamesettings").deleteRecursively() }
    }

    /** Minimal INI reader: "[Section]" + "Key = Value" -> map keyed "Section/Key". Comments
     *  (# / ;) and blank lines ignored. Section text is taken verbatim (it can itself contain
     *  slashes, e.g. "EmuCore/GS"), matching the (section,key) applyTo/readFromIni use. */
    private fun parseIni(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isNotEmpty()) map["$section/$key"] = value
        }
        return map
    }
}

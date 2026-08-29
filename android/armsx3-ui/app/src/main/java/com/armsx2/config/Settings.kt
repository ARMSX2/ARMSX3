package com.armsx2.config

import com.armsx2.ShaderParams
import com.armsx2.config.Settings.Companion.emitSink
import com.armsx2.config.Settings.Companion.merge
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.NativeApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolved emulator config used to drive a VM launch / live-apply.
 *
 * Field naming convention: each field comments the upstream
 * `<section>/<key>` it maps to (grep against pcsx2 docs / Pcsx2Config.cpp).
 *
 * Settings are pushed via [applyTo] which calls NativeApp.setSetting per
 * field, then a single NativeApp.commitSettings to push the queued writes
 * into the running VM (or persist them for the next launch).
 *
 * Adding a new setting:
 *   1. Add a field with an upstream-matching default,
 *   2. Add a setSetting line in applyTo,
 *   3. Add the JSON mapping in toJson + fromJson + merge,
 *   4. Surface a widget in the appropriate Settings tab.
 */
/** One DEV9 internal-DNS host override: [url] resolves to [ip] when DNS mode = Internal.
 *  Used for private/fan servers (e.g. obsrv for RE Outbreak) that redirect specific hostnames. */
data class Dev9HostMapping(
    val url: String = "",
    val ip: String = "0.0.0.0",
    val enabled: Boolean = true,
)

/** `{"<preset path>": {"<parameter name>": value}}` — the wire form of
 *  [Settings.shaderChainParams]. Spelled once and shared by all four places the map has to
 *  cross a boundary (the JSON store, the per-game override diff, the override merge and the
 *  INI seed), because four hand-rolled copies of the same nesting is four chances for one
 *  of them to drift. */
private fun shaderChainParamsToJson(value: Map<String, Map<String, Float>>): JSONObject =
    JSONObject().apply {
        value.forEach { (preset, params) ->
            if (preset.isNotEmpty() && params.isNotEmpty()) {
                put(preset, JSONObject().apply {
                    params.forEach { (name, v) -> put(name, v.toDouble()) }
                })
            }
        }
    }

private fun shaderChainParamsFromJson(json: JSONObject?): Map<String, Map<String, Float>> {
    if (json == null) return emptyMap()
    return buildMap {
        json.keys().forEach { preset ->
            val params = json.optJSONObject(preset) ?: return@forEach
            val values = buildMap<String, Float> {
                params.keys().forEach { name -> put(name, params.optDouble(name, 0.0).toFloat()) }
            }
            // Drop presets whose overrides all went away rather than persisting an empty
            // object that would read back as "this preset is tweaked" forever.
            if (values.isNotEmpty()) put(preset, values)
        }
    }
}

/**
 * PS3 core settings, grouped.
 *
 * NOT flattened into [Settings] deliberately. Settings already had 219
 * constructor parameters; adding 51 more took it to 270, and Kotlin's generated
 * copy$default carries one extra bitmask int per 32 parameters. Crossing from 8
 * masks to 9 changed every synthetic signature, and R8 then emitted invocations
 * that ART's verifier rejects outright:
 *
 *   VerifyError: Rejecting invocation, expected N argument registers,
 *   method signature has N+1 or more
 *
 * The release build died in androidx.startup before any of our code ran. Nesting
 * costs Settings one parameter instead of fifty-one.
 */
data class Ps3Settings(
    val ppuDecoder: Int = 1,
    val spuDecoder: Int = 3,
    /** 0 = Safe, 1 = Mega, 2 = Giga. Safe, matching upstream. */
    val spuBlockSize: Int = 0,
    val ppuThreads: Int = 2,
    /**
     * Concurrent PPU LLVM module compiles. 0 = one per core (upstream's default).
     *
     * Defaults to 2 here, not 0: each in-flight compile holds a whole LLVM module,
     * and eight at once ran an 8 GB device out of memory mid-precompilation
     * (scudo::dieOnMapUnmapError -> abort). Precompiling takes longer, but it
     * finishes.
     */
    val llvmThreads: Int = 2,
    val preferredSpuThreads: Int = 0,
    val maxSpursThreads: Int = 6,
    val spuLoopDetection: Boolean = false,
    val spuCache: Boolean = true,
    val llvmPrecompile: Boolean = true,
    val accurateSpuDma: Boolean = false,
    /** Locks every SPU thread into a state a savestate can be serialised from.
     *
     *  Savestates cannot be taken without it: the save has to stop each SPU somewhere it can
     *  be written out, and with this off that fails on any title with SPU work running.
     *
     *  On by default, unlike upstream, so the feature works for someone who never opens
     *  settings -- a save that fails with "missing SPU setting" reads as broken, not as a
     *  setting waiting to be found. The costs are real and are stated on the switch: it slows
     *  the SPUs while it is on, and a PS3 state runs 500MB to 3GB. Turning it off restores
     *  upstream behaviour and gives the SPU performance back. */
    val savestateCompatibleMode: Boolean = true,
    val clocksScale: Int = 100,
    val resolutionScale: Int = 100,
    /** 0 = Disabled. Off by default: mobile drivers routinely lack the MSAA
     *  features RPCS3 needs, and it is an expensive win on a handheld anyway. */
    val msaaMode: Int = 0,
    /**
     * 0 = Recompiler only, 1 = Async Recompiler, 2 = Async + Interpreter,
     * 3 = Interpreter only.
     *
     * Upstream defaults to 2, but the shader interpreter's uber-shader does not
     * compile on Adreno: vkCreateGraphicsPipelines returns VK_ERROR_UNKNOWN and
     * the pipeline-link ensure() takes the whole process down before a game can
     * draw a frame. 1 is the same thing minus the interpreter fallback -- draws
     * wait for their real shader instead of running through the interpreter.
     */
    val shaderMode: Int = 1,
    /** Lossless Scaling frame generation: 0 Off, 1 x2, 2 x3, 3 x4. Off unless the user has
     *  supplied shaders from their own copy -- nothing is bundled. */
    val frameGeneration: Int = 0,
    // Default ON: 3.1p is the cheaper of the two shader families framegen ships, and on a mobile
    // GPU the full-quality path costs more than the frames it buys.
    val frameGenPerformance: Boolean = true,
    // Optical-flow resolution as a percentage of full; lower is cheaper and blurrier in motion.
    val frameGenFlowScale: Int = 100,
    /** Hz to hold, or 0 for the fixed multiplier. Non-zero selects adaptive pacing. */
    val frameGenTargetRate: Int = 0,
    val writeColorBuffers: Boolean = false,
    val writeDepthBuffer: Boolean = false,
    val readColorBuffers: Boolean = false,
    val readDepthBuffer: Boolean = false,
    val strictRendering: Boolean = false,
    val multithreadedRsx: Boolean = false,
    val disableZcull: Boolean = false,
    /**
     * Relaxed ZCULL sync. OFF.
     *
     * Tried as a fix for 28k "Dubious query data pushed to cond render" stalls.
     * It does remove the stalls -- and it also stops Skate 3 rendering entirely,
     * which is a far worse trade. The occlusion-query cost is real and still
     * needs addressing, but not this way.
     */
    val relaxedZcull: Boolean = false,
    val gpuTextureScaling: Boolean = false,
    val forceCpuBlit: Boolean = false,
    val shaderCompThreads: Int = 0,
    val textureLodBias: Int = 0,
    /**
     * VRAM allocation limit in MB. 1024.
     *
     * NOT a soft budget. It is applied as VMA's pHeapSizeLimit, so it is a hard ceiling: once
     * total allocations reach it VMA returns OUT_OF_DEVICE_MEMORY however much memory the device
     * actually has free. Lowering it does not make the cache evict earlier, it makes allocation
     * fail earlier.
     *
     * Measured on the God of War 3 demo at 1024: a routine 24MB request failed while the process
     * held only 1.6GB resident and 280MB in that pool -- an artificial ceiling, not the hardware.
     * At 2048 it failed too, one screen later.
     *
     * Back to 2048, the value shipped before 0.5. 3072 was set to get the God of War 3 demo past
     * an allocation failure, but that failure was measured BEFORE the uninterruptible reclaim
     * fix landed, and raising the ceiling has its own cost: the texture cache budgets itself up
     * to 2560MB on Android, so a higher cap lets the total grow with it. Batman: Arkham City was
     * measured at 5596MB resident with a 6246MB peak on a 7.2GB device and stalled after a while
     * -- no allocation failure, but far enough into memory pressure that the present pipeline
     * collapses to a single frame in flight.
     *
     * The cap and the texture cache budget are not coordinated, which is the underlying problem;
     * 2048 keeps their sum where it was when this game worked.
     */
    val vramLimitMb: Int = 2048,
    val asyncTexStream: Boolean = false,
    val audioFormat: Int = 0,
    val audioChannels: Int = 0,
    val audioTimeStretch: Boolean = false,
    val audioBuffering: Boolean = true,
    val audioBufferMs: Int = 34,
    /** cubeb backend: 0 auto, 1 aaudio, 2 opensl, 3 audiotrack. Auto reaches AAudio on
     *  anything modern; opensl trades latency for far fewer underruns on weak devices. */
    val audioCubebBackend: Int = 0,
    /** Pin Adreno to max clocks: removes DVFS ramp-up stutter in GPU-bound scenes, costs heat
     *  and battery. Off by default, matching the RPCSX fork this came from. */
    val gpuTurbo: Boolean = false,
    /** Stops the core writing any log after startup. A real performance lever on games that
     *  log heavily, but it also destroys the only artifact a bug report can carry, so it is
     *  off by default and the UI says so plainly. */
    val silenceAllLogs: Boolean = false,
    val netEnabled: Boolean = false,
    /** Net/PSN status: 0 = Disconnected, 1 = Simulated, 2 = RPCN.
     *
     *  Was a Boolean, which could only ever pick Disconnected or Simulated -- so
     *  np_psn_status::psn_rpcn had no writer anywhere in the app and RPCN, which is fully
     *  compiled into the core, was unreachable. */
    val psnStatus: Int = 0,
    val upnpEnabled: Boolean = false,
    /** The IPv4 address games are told the console has. "0.0.0.0" means "work it out". */
    val ipAddress: String = "0.0.0.0",
    /** Which local interface the emulated network stack binds to. "0.0.0.0" = any. */
    val bindAddress: String = "0.0.0.0",
    /** DNS server for the emulated stack. This is the one that matters for private/fan
     *  game servers: RPCN replaces Sony's PSN, but a publisher's own backend was never PSN,
     *  so reaching a revival of one means resolving its hostnames somewhere else. */
    val dnsAddress: String = "8.8.8.8",
    /** Per-hostname redirects, "host=1.2.3.4" joined by "&&" -- finer than dnsAddress
     *  because it moves one hostname instead of every lookup. Parsed by np::dnshook. */
    val ipSwapList: String = "",
    /** Derive the console's MAC from its PSID rather than using a fixed one. */
    val deriveMacFromPsid: Boolean = false,
    /** Two-letter country code reported to PSN/RPCN. */
    val psnCountry: String = "us",
    val clansEnabled: Boolean = false,
    /**
     * 0 = Accurate, 1 = Approximate, 2 = Relaxed, 3 = Inaccurate.
     *
     * Approximate, matching upstream. This was briefly set to Accurate while
     * chasing an SPU freeze; Accurate only shifted the timing and the real cause
     * turned out to be the SPU block-verification checksum (see
     * preciseSpuVerification). Accurate is a rarely-exercised path and costs
     * speed, so there is no reason to sit on it.
     */
    /**
     * Which face button confirms in PS3 system dialogs. 0 = circle, 1 = cross, matching
     * enter_button_assign. Japanese-region games and hardware confirm with circle; the rest of
     * the world uses cross, which is why RPCS3 exposes it rather than deriving it from region.
     */
    val enterButtonAssign: Int = 1,
    /**
     * The rest of the console's identity, as cellSysutil reports it to games: language, region,
     * keyboard layout and clock formats.
     *
     * All five are an INDEX into the tables in Rpcs3Settings, not the core's enum value, and the
     * bridge turns them into the enum NAME the config expects. Defaults match upstream --
     * English (US), SCEA, US keyboard, ddmmyyyy, clock24 -- so an existing install is unchanged.
     *
     * A game reads these: the language decides which text a multi-language disc shows, and the
     * region is what makes a title behave as its NTSC or PAL self.
     */
    val consoleLanguage: Int = 1,
    val consoleRegion: Int = 1,
    val keyboardType: Int = 0,
    val dateFormat: Int = 1,
    val timeFormat: Int = 1,
    val spuXFloat: Int = 1,
    val accurateSpuRsv: Boolean = true,
    /**
     * Off, matching upstream, which is what this always should have been.
     *
     * With this on AND Accurate SPU DMA on, every 128-byte SPU DMA store is routed through
     * do_cell_atomic_128_store, i.e. an atomic reservation store per cache line of every
     * transfer. A game doing bulk DMA then generates reservation contention faster than it
     * can drain, and an SPU sits in do_putllc retrying forever while the PPU stalls behind
     * it and the RSX spins idle. Minecraft froze loading world chunks, which is almost
     * nothing but bulk SPU DMA.
     *
     * Load-dependent, so it presented as an intermittent freeze rather than a clean failure.
     */
    val accurateCacheLine: Boolean = false,
    val accurateRsxRsv: Boolean = false,
    val ppuRsvPriority: Boolean = false,
    val spuVerification: Boolean = true,
    /**
     * Full compare instead of the xorsum checksum when validating cached SPU
     * blocks. Off, matching upstream.
     *
     * This was forced on to work around the ARM64 checksum folding two thirds of
     * every block through an absolute difference, which collided on the
     * near-identical job binaries an SPU job manager streams through one address.
     * The checksum sums those lanes now, so the cheap path is trustworthy again --
     * and the full compare ran on every cached-block entry, which is not something
     * to pay for permanently on a handheld.
     */
    val preciseSpuVerification: Boolean = false,
    /**
     * true, matching RPCS3's own default for "PPU Vector NaN Handling" (system_config.h:68).
     *
     * This was false, so the curated push wrote false over a node upstream ships as true and
     * every install ran with an accuracy fixup disabled that RPCS3 wants on. Games that rely
     * on it get NaNs through vector maths, which surfaces as geometry behaving impossibly
     * rather than as an error.
     *
     * It is also in the PPU cache key (PPUThread.cpp:5343 sets ppu_settings::fixup_vnan from
     * it), so the disagreement was not free: flipping it renames every compiled object and
     * the next boot recompiles the lot. A device here has both variants on disk, 76 modules
     * under one key and 58 under the other, from a single flip.
     */
    val ppuNanHandling: Boolean = true,
    val accurateDfma: Boolean = true,
    val setDazFtz: Boolean = false,
    val hleLwmutex: Boolean = false,
    // 1 = Usleep Only, not 0 = As Host. As Host takes lv2.cpp's plain wait_for(), written for a
    // machine with precise timers; measured on a Snapdragon 8 Gen 2 a 30us sleep takes 50-161us.
    // Guest poll loops then run several times slower than intended -- which is load-bearing for
    // Portal 2, where _gcm_intr_thread polls a mutex on a 30us timer against a main_thread that
    // is waiting on a semaphore only that thread can post.
    val sleepTimers: Int = 1,
    val debugConsoleMode: Boolean = false,
    val resolution: Int = 2,
    val anisoFilter: Int = 0,
    /** Index into Rpcs3Settings.AUDIO_RENDERERS. 4 = Oboe, the Android default (see node_audio). */
    val audioRenderer: Int = 4,
    /**
     * Output aspect override in permille (1778 = 16:9, 1333 = 4:3), 0 = follow the game.
     *
     * The PS3 only ever signalled 4:3 or 16:9, so RPCS3's video_aspect cannot express a
     * handheld panel (20:9, 19.5:9) and Stretch was the only way to fill one -- at the cost
     * of distorting the image. Permille because RPCS3's cfg has no float type.
     */
    val displayAspect: Int = 0,
    // ---- Performance overlay (RPCS3 perf_overlay) ----
    /** PS3: Video/Performance Overlay / Enabled. */
    val overlayEnabled: Boolean = false,
    /** detail_level: None | Minimal | Low | Medium | High. */
    val overlayDetail: Int = 3,
    val overlayFramerateGraph: Boolean = false,
    val overlayFrametimeGraph: Boolean = false,
    val overlayFontSize: Int = 10,
    val overlayOpacity: Int = 70,
    /** screen_quadrant: Top Left | Top Right | Bottom Left | Bottom Right. */
    val overlayPosition: Int = 0,
    // RPCS3 stores these as "#RRGGBBAA" strings. Kept as packed ARGB ints here so
    // the existing colour picker can drive them, and converted on the way out.
    // ARGB, because that is what Android colour ints are and what argbToRgba() converts FROM.
    //
    // These used to hold RPCS3's RGBA hex values verbatim (0xFFE138FF and friends), which are the
    // right colours in the wrong order: argbToRgba then read the leading FF as alpha and rotated
    // every channel one byte left, turning the default orange #FFE138FF into #E138FFFF. That is
    // the pink the overlay has always drawn in, and it made the colour pickers look broken --
    // every value the user chose was rotated the same way, so nothing ever matched.
    val overlayBodyColor: Int = 0xFFFFE138.toInt(),   // core #FFE138FF
    val overlayBodyBg: Int = 0xFF002339.toInt(),      // core #002339FF
    val overlayTitleColor: Int = 0xFFF26C24.toInt(),  // core #F26C24FF
    val overlayTitleBg: Int = 0x00000000,             // core #00000000, fully transparent
)

data class Settings(
    // ---- EmuCore/Speedhacks ----
    /** EmuCore/Speedhacks/EECycleRate — −3..+3 (50%..300%). 0 = nominal. */
    val eeCycleRate: Int = 0,
    /** EmuCore/Speedhacks/EECycleSkip — 0..3. 0 = no skip. */
    val eeCycleSkip: Int = 0,
    /** EE/FPU clamp mode — 0 None / 1 Normal / 2 Extra / 3 Full (PCSX2 default Normal).
     *  Unpacks to EmuCore/CPU/Recompiler fpuOverflow/fpuExtraOverflow/fpuFullMode. */
    val eeClampMode: Int = 1,
    /** VU clamp mode — 0 None / 1 Normal / 2 Extra / 3 Extra+Sign (PCSX2 default Normal).
     *  Unpacks to vu0/vu1 Overflow/ExtraOverflow/SignOverflow. */
    val vuClampMode: Int = 1,
    /** EmuCore/Speedhacks/vuThread — Multi-Threaded VU1 (MTVU).
     *  Kept on by default for the mac ARM64 backend, but persisted normally
     *  so testers can A/B games which dislike MTVU. */
    val mtvu: Boolean = true,
    /** EmuCore/Speedhacks/vu1Instant — completes VU1 in one cycle. */
    val vu1Instant: Boolean = true,
    /** EmuCore/Speedhacks/vuFlagHack — skip VU flag computation when unread. */
    val vuFlagHack: Boolean = true,
    /** EmuCore/Speedhacks/fastCDVD — skip CDVD reads. */
    val fastCDVD: Boolean = false,
    /** EmuCore/Speedhacks/IntcStat — INTC_STAT register read hack. */
    val intcStat: Boolean = true,
    /** EmuCore/Speedhacks/WaitLoop — detect EE wait loops. */
    val waitLoop: Boolean = true,
    /** EmuCore/Speedhacks/vuNeonFusions — ARMSX2-only. Gates the arm64
     *  VU1 JIT NEON peephole fusions (MAC cluster
     *  MULAx+MADDAy+MADDAz+MADDw, OPMULA+OPMSUB cross-product). Default
     *  on — toggle off to A/B whether one of those JIT fusions is
     *  responsible for a per-game regression. */
    val vuNeonFusions: Boolean = true,
    /** EmuCore/Speedhacks/vuDeferredWrites — EXPERIMENTAL. Defers
     *  per-pair VF stores via the NEON cache; flush sites commit later.
     *  Big perf win on transform-heavy code. Known to break SH2 graphics
     *  and other games with cross-pair memory coherence assumptions. */
    val vuDeferredWrites: Boolean = false,
    /** EmuCore/Speedhacks/vuSkipStallSim — AGGRESSIVE. Skips the
     *  vu1_TestPipes_VU1 BL in the JIT — was 19-32% of total CPU on
     *  Futurama/GoW2/Ape Escape 3 per profiling. Breaks any game that
     *  relies on accurate FMAC/FDIV/EFU/IALU pipeline-stall timing. */
    val vuSkipStallSim: Boolean = false,

    // ---- EmuCore/GS — frame limiter ----
    /** EmuCore/GS/FrameLimitEnable. */
    val frameLimitEnable: Boolean = true,
    /** Framerate/NominalScalar expressed as a percent of native speed
     *  (100 = full speed ≈ 60fps NTSC / 50fps PAL). Applies when the Frame
     *  Limiter is on: lower values cap the FPS (50 ≈ 30fps), higher values
     *  fast-forward. Stored as percent; written to emucore as the 0.05..10.0
     *  float scalar. */
    val nominalSpeedPercent: Int = 100,
    /** Max presented-FPS cap, independent of [nominalSpeedPercent] and the Speed
     *  Limit %. 0 = off. When > 0 the native side caps the DISPLAY frame rate by
     *  dropping presents on the GS thread while emulation keeps running full
     *  speed — it does NOT slow the game. Adaptive: a game already at/below the
     *  target is unaffected (no over-skip). */
    val fpsLimit: Int = 0,
    /** Deprecated Android-only frame skip. Kept for JSON compatibility only. */
    val frameSkip: Int = 0,

    // ---- Audio (SPU2/Output) ----
    /** SPU2/Output/StandardVolume — output volume %, 0..200 (100 = full). */
    val audioVolume: Int = 100,
    /** SPU2/Output/OutputMuted — mute audio output. */
    val audioMuted: Boolean = false,
    /** SPU2/Output/SwapChannels — swap final stereo output L<->R (flipped-speaker
     *  devices forced into reverse-landscape, e.g. the Clamp gamepad). */
    val audioSwapChannels: Boolean = false,
    /** SPU2/Output/SyncMode — TimeStretch keeps pitch stable under load; off
     *  (Disabled) is lower CPU but drifts pitch when frame-time varies. */
    val audioTimeStretch: Boolean = true,
    /** SPU2/Output/BufferMS — audio buffer size (ms). Higher = fewer dropouts,
     *  more latency. 50 = default; raise if audio stutters on low-end devices. */
    val audioBufferMs: Int = 50,
    /** SPU2/Output/OutputLatencyMS — target output latency (ms). 20 = default. */
    val audioOutputLatencyMs: Int = 20,
    /** SPU2/Output/FastForwardVolume — output volume % while fast-forwarding. */
    val audioFastForwardVolume: Int = 100,
    /** SPU2/NeonReverbSIMD — opt-in NEON reverb FIR on ARM64. Frees CPU on
     *  CPU-bound devices; default off uses the scalar reference (unchanged
     *  audio). Applied on the next game boot/reset. */
    val spu2NeonReverb: Boolean = false,
    /** SPU2/Output/AndroidOpenSLES — opt-in legacy OpenSL ES audio path (Oboe)
     *  instead of AAudio. Slightly higher latency, but Android doesn't reclaim
     *  the idle stream, so pause/resume (and fast-forward toggling through the
     *  menu) never triggers the ~1s stream rebuild. Applies live (stream
     *  reconfigures). Default off = AAudio low-latency. */
    val audioOpenSLES: Boolean = false,
    /** SPU2/Output/LightweightMode — low-end audio lever: skip the SPU2 reverb
     *  pipeline (all echo/spatial reverb) in the mixer. Frees CPU on devices that
     *  can't keep up even with NEON reverb; default off = full reverb. Applies
     *  live (read per-sample in MixCore). */
    val spu2LightweightMix: Boolean = false,

    // ---- EmuCore — patches / cheats ----
    /** EmuCore/EnablePatches — game-compatibility patches (default on). */
    val enablePatches: Boolean = true,
    /** EmuCore/EnableCheats — PNACH cheats. */
    val enableCheats: Boolean = false,
    /** EmuCore/EnableWideScreenPatches — 16:9 widescreen patches. */
    val enableWideScreenPatches: Boolean = false,
    /** EmuCore/EnableNoInterlacingPatches — no-interlacing patches. */
    val enableNoInterlacingPatches: Boolean = false,
    /** EmuCore/EnableFastBoot — skip BIOS splash and boot straight to the game.
     *
     *  Default ON: "how do I skip the boot animation" is one of the most-asked questions in the
     *  Discord, and desktop PCSX2 fast-boots by default too. Only fresh installs are affected —
     *  the saved JSON always carries this key, so anyone who already has a value keeps it rather
     *  than having their boot behaviour changed under them by an update. */
    val enableFastBoot: Boolean = true,
    /** EmuCore/HostFs — host: filesystem access in the VM, for ELF/homebrew and mods
     *  (e.g. modded Persona 3 FES). Per-game capable; applies on the next game boot. */
    val hostFs: Boolean = false,
    /** EmuCore/EnableGameFixes — master switch that lets the GameDB apply each game's
     *  curated compatibility gamefixes (e.g. VuAddSubHack, SkipMPEGHack). Defaults TRUE
     *  to match upstream PCSX2 (Pcsx2Config.cpp EnableGameFixes = true) and trak's Mac:
     *  Android was the outlier defaulting it false, which silently skipped every GameDB
     *  CPU gamefix — that's what broke Valkyrie Profile 2 (needs VuAddSubHack; without it
     *  the first VU0 program diverges and the EE derails to PC=0) and made Skip MPEG inert.
     *  GameDB gamefixes are per-game curated, so on-by-default only helps compatibility. */
    val enableGameFixes: Boolean = true,
    /** EmuCore/Gamefixes/SoftwareRendererFMVHack. */
    val gamefixSoftwareRendererFmv: Boolean = false,
    /** EmuCore/Gamefixes/SkipMPEGHack. */
    val gamefixSkipMpeg: Boolean = false,
    /** EmuCore/Gamefixes/EETimingHack. */
    val gamefixEETiming: Boolean = false,
    /** EmuCore/Gamefixes/InstantDMAHack. */
    val gamefixInstantDma: Boolean = false,
    /** EmuCore/Gamefixes/BlitInternalFPSHack. */
    val gamefixBlitInternalFps: Boolean = false,
    /** EmuCore/Gamefixes/FpuMulHack — Tales of Destiny. */
    val gamefixFpuMul: Boolean = false,
    /** EmuCore/Gamefixes/OPHFlagHack — Bleach Blade Battlers. */
    val gamefixOphFlag: Boolean = false,
    /** EmuCore/Gamefixes/GIFFIFOHack — emulate the GIF FIFO (Test Drive Unlimited). */
    val gamefixGifFifo: Boolean = false,
    /** EmuCore/Gamefixes/DMABusyHack — Mana Khemia 1. */
    val gamefixDmaBusy: Boolean = false,
    /** EmuCore/Gamefixes/VIF1StallHack — delay VIF1 stalls (SOCOM 2 HUD). */
    val gamefixVif1Stall: Boolean = false,
    /** EmuCore/Gamefixes/IbitHack — Scarface, Crash Twinsanity. */
    val gamefixIbit: Boolean = false,
    /** EmuCore/Gamefixes/FullVU0SyncHack — tight VU0 sync on every COP2 op. */
    val gamefixFullVu0Sync: Boolean = false,
    /** EmuCore/Gamefixes/VuAddSubHack — Tri-Ace games. */
    val gamefixVuAddSub: Boolean = false,
    /** EmuCore/Gamefixes/VUOverflowHack — Superman Returns. */
    val gamefixVuOverflow: Boolean = false,
    /** EmuCore/Gamefixes/XgKickHack — extra XGKICK delay (Erementar Gerad). */
    val gamefixXgkick: Boolean = false,
    /** EmuCore/Gamefixes/GoemonTlbHack — preload TLB for Goemon games. Restart to apply. */
    val gamefixGoemonTlb: Boolean = false,
    /** EmuCore/Gamefixes/VUSyncHack — run microVU behind the EE (M-bit games). Restart to apply. */
    val gamefixVuSync: Boolean = false,
    /** EmuCore/GS/SkipDuplicateFrames — skip presenting unchanged frames. PCSX2 default on. */
    val skipDuplicateFrames: Boolean = true,
    /** EmuCore/CPU/FPU.Roundmode — EE FPU rounding: 0 Nearest / 1 Negative / 2 Positive
     *  / 3 Chop. PS2 EE FPU default is Chop (toward zero). */
    val eeFpuRoundMode: Int = 3,
    /** EmuCore/CPU/VU0.Roundmode — VU0 rounding: 0 Nearest / 1 Neg / 2 Pos / 3 Chop. Default Chop. */
    val vu0RoundMode: Int = 3,
    /** EmuCore/CPU/VU1.Roundmode — VU1 rounding: 0 Nearest / 1 Neg / 2 Pos / 3 Chop. Default Chop. */
    val vu1RoundMode: Int = 3,

    // ---- EmuCore/GS — display / PCRTC fixes ----
    /** EmuCore/GS/pcrtc_offsets — apply PCRTC screen offsets. PCSX2 default off. */
    val screenOffsets: Boolean = false,
    /** EmuCore/GS/pcrtc_overscan — show overscan area. PCSX2 default off. */
    val showOverscan: Boolean = false,
    /** EmuCore/GS/pcrtc_antiblur — anti-blur. PCSX2 default ON. */
    val antiBlur: Boolean = true,
    /** EmuCore/GS/disable_interlace_offset — disable interlace offset. Default off. */
    val disableInterlaceOffset: Boolean = false,
    /** EmuCore/GS/SyncToHostRefreshRate — pace emulation to the host refresh. Default off. */
    val syncToHostRefresh: Boolean = false,
    /** EmuCore/GS/DisableFramebufferFetch — disable the framebuffer-fetch path. Default off. */
    val disableFramebufferFetch: Boolean = false,
    /** EmuCore/GS/HWROV — Rasterizer Order Views (accurate blending via fragment-shader
     *  interlock; Vulkan only). Default OFF on mobile: it's a perf loss on tilers and is
     *  inert on Turnip/Adreno (no VK_EXT_fragment_shader_interlock), so on-by-default just
     *  costs frames for no gain. Upstream PCSX2 defaults it true (desktop); we override to
     *  false for Android. Users can still enable it in Renderer for benchmarking. */
    val hwRov: Boolean = false,
    /** EmuCore/GS/HWAA1 — hardware PS2 AA1 edge anti-aliasing. Default off. Applies on game restart. */
    val hwAa1: Boolean = false,
    /** EmuCore/GS/HWAccurateAlphaTest — accurate alpha test for the HW renderer (pairs with ROV). Default off. */
    val hwAat: Boolean = false,
    /** EmuCore/GS/EnableAdrenoFramebufferFetch — enable the Vulkan framebuffer-fetch
     * (ROAA) accurate-blending fast path on non-Mali (Adreno) GPUs that expose the
     * extension. Default ON so accurate blending runs in-tile (fast) instead of the
     * per-primitive barrier fallback. A few proprietary Adreno drivers show stale-ROAA
     * read artifacts — turn this off in the Renderer tab if so. Applies on game restart. */
    val adrenoFbFetch: Boolean = true,
    /** EmuCore/GS/CoalesceRenderPasses — group consecutive draws to the same target into a
     * single render pass. Aimed squarely at tiling GPUs (every Android GPU), where each pass
     * boundary costs a full tile load and store; rendering output is unchanged. Default off,
     * matching upstream, because it is new. bmd only wired this into the desktop UI, so
     * without this it would be unreachable on the platform it was written for. */
    val coalesceRenderPasses: Boolean = false,
    /** EmuCore/GS/ForceMaliFramebufferFetch — re-enable the Vulkan framebuffer-fetch
     * (ROAA) path on MediaTek Mali / Mali-G57, where it is force-disabled because those
     * drivers return zero/stale destination colour through ROAA (black or missing
     * textures). Mali exposes no hardware dual-source blend, so with fetch off the HW
     * renderer SW-blends via a per-primitive texture barrier — very slow in blend-heavy
     * games (issue #339: Shadow of the Colossus on Dimensity 8350 + Mali-G615). This lets
     * such a user test whether their driver is actually affected. Default OFF, and kept
     * deliberately separate from adrenoFbFetch (which is default-ON plus a ConfigStore
     * migration — keying off it would force fetch on for EVERY MediaTek Mali user, the
     * exact breakage this works around). Inert on other GPUs/renderers. Applies on game
     * restart. */
    val forceMaliFbFetch: Boolean = false,
    /** EmuCore/GS/AndroidUseAngleOpenGL — run the OpenGL renderer through ANGLE's
     *  GLES-on-Vulkan translation (bundled libEGL_angle.so / libGLESv2_angle.so).
     *  Useful on devices with a broken native GLES driver (e.g. some MediaTek Mali).
     *  Only takes effect when the renderer is OpenGL; MainActivityRuntime.applyAngleEnv
     *  turns it into the ARMSX2_ANGLE_EGL_LIBRARY env var that GLContextEGL reads.
     *  Applies on game restart. Default off. */
    val useAngleOpenGL: Boolean = false,
    /** EmuCore/GS/OverrideTextureBarriers — -1 Auto / 0 Off / 1 On. */
    val overrideTextureBarriers: Int = -1,
    /** EmuCore/GS/GSBackThreadMode — GV7 GS front/back thread split.
     * 0 Off (single-threaded), 1 Inline, 2 Lockstep, 3 Pipelined (fastest).
     * Defaults to Off (opt-in); a per-game override can raise it. Restart-required. */
    val gsBackThreadMode: Int = 0,
    /** EmuCore/GS/DisableVertexShaderExpand — force CPU vertex expansion. Renderer-init; restart to apply. */
    val disableVertexShaderExpand: Boolean = false,
    /** EmuCore/GS/UseBlitSwapChain — blit present model instead of flip. Renderer-init; restart to apply. */
    val useBlitSwapChain: Boolean = false,
    /** EmuCore/GS/DisableShaderCache — don't cache compiled shaders to disk. Renderer-init; restart to apply. */
    val disableShaderCache: Boolean = false,
    /** EmuCore/GS/HWAccurateAlphaTest — accurate hardware alpha test. PCSX2 default off. */
    val hwAccurateAlphaTest: Boolean = false,

    // ---- EmuCore/GS — hardware / software renderer fixes ----
    /** EmuCore/GS/UserHacks_SkipDraw_Start — first draw to skip. 0 = off. */
    val skipDrawStart: Int = 0,
    /** EmuCore/GS/UserHacks_SkipDraw_End — last draw to skip. 0 = off. */
    val skipDrawEnd: Int = 0,
    /** EmuCore/GS/HWSpinGPUForReadbacks — busy-wait the GPU on readbacks. Default off. */
    val spinGpuReadbacks: Boolean = false,
    /** EmuCore/GS/HWSpinCPUForReadbacks — busy-wait the CPU on readbacks. Default off. */
    val spinCpuReadbacks: Boolean = false,
    /** EmuCore/GS/IntegerScaling — integer pixel scaling for the presented image. Default off. */
    val integerScaling: Boolean = false,
    /** EmuCore/GS/CropLeft|Top|Right|Bottom — overscan crop in native PS2 pixels, trimmed
     *  from the presented image before aspect/integer scaling. Many PS2 titles render
     *  garbage or a black band in the overscan area that a TV would have hidden; the core
     *  has always supported this (GSRenderer.cpp) but Android never exposed it (issue #293). */
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = 0,
    val cropBottom: Int = 0,
    /** Display zoom, 100-150% (#383). An AetherSX2-style single "zoom" slider: rather than the
     *  four fiddly per-edge crops (which distort when set unevenly), this trims all four edges by
     *  the SAME fraction, so the image scales up into the frame without changing aspect. App-side
     *  only (no native key) — it's converted to symmetric CropLeft/Top/Right/Bottom in writeIni,
     *  overriding the manual crops while > 100. */
    val displayZoom: Int = 100,
    /** EmuCore/GS/dithering_ps2 — 0 Off / 1 Scaled / 2 Unscaled / 3 Force 32bit. PCSX2 default Unscaled. */
    val dithering: Int = 2,
    /** EmuCore/GS/VsyncQueueSize — frames the GS thread may queue (0-3). PCSX2 default 2. */
    val vsyncQueueSize: Int = 2,
    // Output-surface scaling. App-side (no EmuCore key) but PER-GAME scoped: a heavy
    // game can render its output smaller while the library and lighter games stay
    // sharp. Were global-only prefs until #-Duda reported that changing them in Game
    // scope also moved Global — there was no per-game copy to write.
    val hwScaler: Int = 0,                       // 0 = screen, else 448*n short side
    val screenResOverride: String = "auto",      // "auto" | "2560x1440" | "1920x1080" | "1280x720"
    /** EmuCore/GS/autoflush_sw — software-renderer auto-flush. PCSX2 default on. */
    val autoFlushSw: Boolean = true,
    /** EmuCore/GS/mipmap — software-renderer mipmapping. PCSX2 default on. */
    val mipmapSw: Boolean = true,
    /** EmuCore/GS/extrathreads — extra software-renderer threads (0-10). PCSX2 default 4. */
    val swThreads: Int = 4,
    /** EmuCore/GS/extrathreads_height — SW-renderer tile height per thread (0-8). PCSX2 default 4. Restart to apply. */
    val swThreadsHeight: Int = 4,

    /** EmuCore/GS/AspectRatio:
     *  0 Stretch · 1 Auto 4:3/3:2 · 2 4:3 · 3 16:9 · 4 10:7 · 5 21:9 · 6 20:9 · 7 19.5:9 · 8 Custom.
     *  Indices are persisted, so append new ratios — never insert. */
    val aspectRatio: Int = 1,
    /** EmuCore/GS/FMVAspectRatioSwitch — aspect ratio used ONLY while an FMV/MPEG is
     *  playing (restores [aspectRatio] when it ends). 0 Off (no override) · 1 Auto
     *  4:3/3:2 · 2 4:3 · 3 16:9 · 4 10:7 · 5 21:9 · 6 20:9 · 7 19.5:9 · 8 Custom. Default Off. */
    val fmvAspectRatio: Int = 0,
    /** EmuCore/GS/CustomAspectRatio — width/height used when [aspectRatio] is 8 (Custom).
     *  A ratio rather than separate W/H so any value is expressible; clamped 0.5..5.0 natively. */
    val customAspectRatio: Float = 16f / 9f,
    /** Host graphics API: "auto" / "opengl" / "vulkan" / "software". Applied via
     *  the renderer JNI helpers on (re)launch; per-game so each title can pick its
     *  own backend. Seeded from the legacy global "renderer" pref on first load. */
    val renderer: String = "auto",
    /** Internal resolution multiplier (0.25..5.0; 1.0 = native). Applied live via
     *  the GS upscale helper; per-game so each title keeps its own. Seeded from the
     *  legacy global "upscaleFloat" pref on first load. */
    val upscaleFloat: Float = 1.0f,
    /** Installed custom Vulkan GPU driver id to pin (e.g. a Turnip build). "" = system
     *  driver. Applied at (re)launch via CustomDriver.applyToNative in
     *  MainActivityRuntime.applyRendererPrefs; per-game so a title can pin the driver it
     *  needs. Seeded from the legacy global "customDriverId" pref on first load. */
    val customDriverId: String = "",
    /** Android activity screen orientation: 0 Use Device Setting · 1 Landscape · 2 Portrait
     *  · 3 Auto-Rotate. Applied via MainActivityRuntime.applyEmulationOrientation, resolved
     *  per-game at game boot (global in the library/menus). Seeded from the legacy global
     *  "ui.orientation" pref on first load. */
    val orientation: Int = 0,
    /** GitHub #375: in PORTRAIT, top-align the render (true, default) instead of vertical-
     *  centering (false), so the bottom is free for touch controls. Applied live via
     *  NativeApp.setPortraitRenderTop; only affects a portrait window. */
    val portraitRenderTop: Boolean = true,
    /** In LANDSCAPE, top-align the render instead of vertical-centering (default). Foldables and
     *  clamshell controllers (Backbone-style) open the screen downward, so a centred image sits
     *  too low. Applied live via NativeApp.setLandscapeRenderTop; only affects a landscape window. */
    val landscapeRenderTop: Boolean = false,
    /** Auto Progressive Scan: hold Triangle+Cross on port 1 through the boot sequence, which is
     *  the real-console combo a number of PS2 titles probe to offer 480p progressive output
     *  (Tekken 4, several Criterion games). Purely a synthetic pad hold — no core setting — so it
     *  only does anything on games that implement the prompt. Per-game because the same combo is
     *  a normal input elsewhere, and titles that ignore the prompt gain nothing from holding it. */
    val autoProgressiveScan: Boolean = false,
    /** Affinity Control Mode (EXPERIMENTAL, default 0 = off). 0 Disabled · 1 EE>VU>GS ·
     *  2 EE>GS>VU · 3 VU>EE>GS · 4 VU>GS>EE · 5 GS>EE>VU · 6 GS>VU>EE · 7 Performance Cores.
     *  Pushed to native via NativeApp.setAffinityMode before runVMThread and consumed by
     *  VMManager::SetEmuThreadAffinities, so it applies on the next boot. Per-game because the
     *  best placement is workload-dependent: GS-bound titles want the GS thread on the prime
     *  core, VU-bound ones want VU left free to float there. Off is still the recommended
     *  default — Android's EAS scheduler usually beats hand-pinning. */
    /**
     * Thread Scheduler Mode: 0 = OS, 1 = RPCS3, 2 = RPCS3 Alternative.
     *
     * 0 by default. This was 2, to keep SPU and RSX off the A510s, which run at
     * roughly 27% of prime-core capacity. The reasoning holds for one thread per
     * core and breaks down at six.
     *
     * Measured on a Snapdragon 8 Gen 2, in game, reading the masks the threads
     * actually carry:
     *
     *     app cpuset (top-app)  0-7      Android grants every core
     *     SPU[0..5]             3-6      six threads, four cores
     *     rsx::thread           3-7
     *
     * Six SPU threads sharing four cores get about two thirds of a core each,
     * which is worse than one thread owning an A510 outright, and it caps the
     * whole emulator: the device sat at 60% with cores 0-2 idle while frames
     * were slow. Spider-Man: Web of Shadows is visibly better at OS.
     *
     * The mask is ours, not Android's, which is also why these devices are
     * reported to run better under native Linux, where no such policy applies.
     *
     * The other modes remain selectable for anyone whose device disagrees.
     */
    val affinityMode: Int = 0,
    /** EmuCore/GS FramerateNTSC — the emulated PS2 vsync rate for NTSC games
     *  (PCSX2 default 59.94). Lowering it slows the game's target rate; raising it
     *  speeds it up. Mirrors NetherSX2's "Framerate For NTSC". */
    val framerateNtsc: Float = 59.94f,
    /** EmuCore/GS FrameratePAL — emulated PS2 vsync rate for PAL games (default 50.00). */
    val frameratePal: Float = 50.00f,
    /** EmuCore/GS/deinterlace_mode — GSInterlaceMode:
     *  0 Auto · 1 Off · 2/3 Weave · 4/5 Bob · 6/7 Blend · 8/9 Adaptive. */
    val deinterlaceMode: Int = 0,

    // ---- DEV9 — PS2 HDD / Ethernet ----
    /** DEV9/Eth/EthEnable — PS2 network adapter. */
    val dev9EthEnable: Boolean = false,
    /** DEV9/Eth/EthApi — "Sockets" for internet play, "Local Link" for device-to-device LAN. */
    val dev9EthApi: String = "Sockets",
    // ---- Local Link (EthApi = "Local Link") -------------------------------------------------
    // Bridges the emulated PS2 Ethernet frames between devices over UDP on the local network, so
    // games with native System Link / LAN support see each other as if on one switch. Each device
    // runs its own VM — this is NOT netplay, and it does nothing for online-only or i.Link titles.
    /** DEV9/Eth/LocalLinkHost — true = this device relays for the session; false = it joins one. */
    val localLinkHost: Boolean = false,
    /** DEV9/Eth/LocalLinkAddress — the host's LAN IPv4, entered on joining devices only. */
    val localLinkAddress: String = "",
    /** DEV9/Eth/LocalLinkPort — UDP port; must match on every device (no negotiation). */
    val localLinkPort: Int = 19072,
    /** DEV9/Eth/LocalLinkPeerId — 1 for the host, 2+ for each guest. Must be unique per device;
     *  duplicate ids collide because the peer id is what derives the emulated MAC and IP. */
    val localLinkPeerId: Int = 1,
    /** DEV9/Eth/LocalLinkRoomCode — shared 4-12 char code that keys the packet authentication.
     *  Prevents crosstalk between sessions on the same Wi-Fi; it is NOT strong security. */
    val localLinkRoomCode: String = "",
    /** DEV9/Eth/EthDevice — "Auto" lets the sockets backend choose. */
    val dev9EthDevice: String = "Auto",
    /** DEV9/Eth/EthLogDHCP — logs DHCP packets for network debugging. */
    val dev9EthLogDhcp: Boolean = false,
    /** DEV9/Eth/EthLogDNS — logs DNS packets for network debugging. */
    val dev9EthLogDns: Boolean = false,
    /** DEV9/Eth/InterceptDHCP — use PCSX2's internal DHCP replies. */
    val dev9InterceptDhcp: Boolean = false,
    val dev9Ps2Ip: String = "0.0.0.0",
    val dev9Mask: String = "0.0.0.0",
    val dev9Gateway: String = "0.0.0.0",
    val dev9Dns1: String = "0.0.0.0",
    val dev9Dns2: String = "0.0.0.0",
    val dev9AutoMask: Boolean = true,
    val dev9AutoGateway: Boolean = true,
    val dev9ModeDns1: String = "Auto",
    val dev9ModeDns2: String = "Auto",
    /** DEV9/Eth/Hosts — hostname->IP overrides consulted by the INTERNAL DNS server
     *  (DNS mode = Internal). For private/fan servers that redirect specific hostnames. */
    val dev9EthHosts: List<Dev9HostMapping> = emptyList(),
    /** DEV9/Hdd/HddEnable — virtual PS2 HDD. */
    val dev9HddEnable: Boolean = false,
    /** DEV9/Hdd/HddFile — path/name of the virtual HDD image. */
    val dev9HddFile: String = "DEV9hdd.raw",

    // ---- MemoryCards ----
    val memoryCardSlot1Enabled: Boolean = true,
    val memoryCardSlot1Filename: String = "mcd001.ps2",
    // Per-game BIOS override (e.g. an EU disc vs a US disc wanting its region's BIOS).
    // Empty = use the global BIOS picked in the BIOS manager. Just the filename; the file
    // lives in the app-private BIOS dir like every installed BIOS. Applied at boot in
    // MainActivityRuntime.applyRendererPrefs (resolved per-game, with global fallback).
    val biosFilename: String = "",
    val memoryCardSlot2Enabled: Boolean = true,
    val memoryCardSlot2Filename: String = "mcd002.ps2",

    // ---- Keyboard ----
    /** Input/Output/Keyboard = Basic — serve cellKb from the Android keyboard handler.
     *  Needed by games that want a keyboard (EverQuest Online Adventures, in-game
     *  text chat, the debug menus some titles put behind one). Keys come from a
     *  physical/Bluetooth keyboard (MainActivityRuntime.forwardKeyToUsbKeyboard) or
     *  from the Android IME the On-Screen Keyboard hotkey raises (SoftKeyboard), and
     *  reach the core through NativeApp.usbKeyboardKey.
     *
     *  The name is ARMSX2's. RPCS3 has no emulated USB HID keyboard device; it has a
     *  keyboard handler, which is what this drives.
     *
     *  Read once, in Emulator::Load, so it takes effect on the next boot. Default off. */
    val usbKeyboard: Boolean = false,

    // ---- EmuCore/CPU/Recompiler — recompiler enables ----
    /** EmuCore/CPU/Recompiler/EnableEE — EE (R5900) recompiler. */
    val recEE: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableIOP — IOP (R3000) recompiler. */
    val recIOP: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableVU0 — VU0 recompiler. */
    val recVU0: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableVU1 — VU1 recompiler. */
    val recVU1: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableFastmem — fastmem (page-fault backpatch
     *  signal handler). Disabling falls back to the slow VTLB read/write
     *  path on every memory op. */
    val enableFastmem: Boolean = true,

    // ---- macOS/PCSX2 ARM64 backend compatibility flags ----
    // Hidden from UI and forced on. Kept only so older JSON/INI/per-game blobs
    // with UseMac* keys still parse without losing the rest of their settings.
    /** EmuCore/CPU/Recompiler/UseMacEE — legacy, forced on. */
    val useMacEE: Boolean = true,
    /** EmuCore/CPU/Recompiler/UseMacIOP — legacy, forced on. */
    val useMacIOP: Boolean = true,
    /** EmuCore/CPU/Recompiler/UseMacVU0 — legacy, forced on. */
    val useMacVU0: Boolean = true,
    /** EmuCore/CPU/Recompiler/UseMacVU1 — legacy, forced on. */
    val useMacVU1: Boolean = true,

    // ---- microVU-style compile-time pipeline-stall folding ----
    /** EmuCore/CPU/Recompiler/Vu1InlineFmacStall — replace the per-pair
     *  `vu1_TestFMACStallReg / _Reg2` BLs (formerly 17-32% of total CPU per
     *  simpleperf) with an inline `Add VU1_CYCLE_REG, #fmac_stall`. Mirrors
     *  mac's compile-time mVUincCycles + mVUstall fold. Gated by the same
     *  `fmac_carry_safe` (ct_cycle > 3) guarantee that cross-block carry-in
     *  FMAC slots have retired at runtime. */
    val vu1InlineFmacStall: Boolean = false,
    /** EmuCore/CPU/Recompiler/Vu1CrossBlockPState — propagate predecessor's
     *  exit pipeline-state to successor block compile, so CARRY_IN_GATE_*
     *  bounds can shrink (FMAC/IALU=3, FDIV=12, EFU=54). When a predecessor
     *  links to a successor, the successor variant is specialised for that
     *  predecessor's exitState. Mirrors mac's microBlockManager pState match. */
    val vu1CrossBlockPState: Boolean = false,
    /** EmuCore/CPU/Recompiler/Vu1InlineDrainTestPipes — inline-emit the
     *  vu1_TestPipes_VU1 FMAC drain at JIT sites where the pre-walk proves
     *  FDIV/EFU/IALU are empty (skip_info[i].fmacOnlyTestPipes). Saves the BL
     *  + viCacheInvalidateAll + return overhead per call. Mac doesn't need
     *  this because it has no runtime FMAC ring — flag instances are routed
     *  at compile time. */
    val vu1InlineDrainTestPipes: Boolean = false,
    /** EmuCore/CPU/Recompiler/Vu1FmacInstanceRouting — mac-style 4-slot flag-
     *  instance routing. Repurposes VU->fmac[0..3].{mac,status,clip}flag as
     *  instance slots; skips the ring metadata Strs and the FMAC stall BLs.
     *  fmaccount stays 0 so vu1_TestPipes_VU1's FMAC drain early-exits. */
    val vu1FmacInstanceRouting: Boolean = false,

    // ---- EmuCore/GS — renderer accuracy / quality ----
    /** EmuCore/GS/hw_mipmap. */
    val hwMipmap: Boolean = true,
    /** EmuCore/GS/accurate_blending_unit — AccBlendLevel:
     *  0 Min · 1 Basic · 2 Medium · 3 High · 4 Full · 5 Maximum. */
    val accurateBlendingUnit: Int = 1,
    /** EmuCore/GS/filter — BiFiltering:
     *  0 Nearest · 1 Forced (Bilinear) · 2 PS2 · 3 Forced_But_Sprite. */
    val textureFiltering: Int = 2,
    /** EmuCore/GS/linear_present_mode — GSPostBilinearMode:
     *  0 Off (nearest) · 1 Smooth · 2 Sharp. Display output (scan-out) bilinear filter. */
    val displayBilinear: Int = 1,
    /** EmuCore/GS/texture_preloading — TexturePreloadingLevel:
     *  0 Off · 1 Partial · 2 Full. */
    val texturePreloading: Int = 2,
    /** EmuCore/GS/HWDownloadMode — GSHardwareDownloadMode:
     *  0 Accurate · 1 Force Full · 2 No Readbacks · 3 Unsync · 4 Disabled · 5 Asynchronous.
     *  ★ 5 (Asynchronous) is EXPERIMENTAL: a non-blocking GPU→CPU readback pipeline, so the EE
     *  thread never stalls on the GS thread. Note the enum stops being ordered at 5 — never write
     *  `mode > n` comparisons against it. Keep the clamp in applyTo in sync with this list. */
    val hardwareDownloadMode: Int = 0,
    /** EmuCore/GS/TVShader — CRT / TV shader preset. */
    val tvShader: Int = 0,
    /** EmuCore/GS/ShadeBoost. */
    val shadeBoost: Boolean = false,
    val shadeBoostBrightness: Int = 50,
    val shadeBoostContrast: Int = 50,
    val shadeBoostSaturation: Int = 50,
    val shadeBoostGamma: Int = 50,
    /** EmuCore/GS/fxaa — FXAA post-process anti-aliasing. */
    val fxaa: Boolean = false,
    /** EmuCore/GS/ShaderChainEnabled + /ShaderChainPreset — RetroArch (.slangp) shader
     *  chain run at present via librashader, after ShadeBoost/FXAA. An empty preset means
     *  off regardless of the flag, and it's a no-op if this build has no librashader. */
    val shaderChainEnabled: Boolean = false,
    val shaderChainPreset: String = "",
    /** Tweaked shader parameters, as `preset path -> (parameter name -> value)`.
     *
     *  Sparse: a parameter the user hasn't touched is simply absent, and the author's own
     *  initial applies. That is what keeps this from bloating — a preset can declare ~900
     *  parameters, and storing all of them per game would dwarf the rest of the config.
     *
     *  Keyed by preset rather than holding one flat map because parameter names collide
     *  freely across packs ("gamma" means something different in every one of them), and
     *  because it lets a user flip between two tweaked presets without losing either set.
     *  No EmuCore key mirrors this — see applyTo. */
    val shaderChainParams: Map<String, Map<String, Float>> = emptyMap(),
    /** EmuCore/GS/CASMode — GSCASMode: 0 Off / 1 Sharpen Only / 2 Sharpen + Resize. */
    /** Scaling Mode row: 0 Nearest, 1 Bilinear, 2 FSR. Bilinear because that is what the
     *  core has always actually used, and what RPCS3 itself defaults to. */
    val casMode: Int = 1,
    /** SGSR edge sharpness, 0..200. 100 is Qualcomm's default. Separate from casSharpness
     *  because that one is natively clamped to 100 and cannot express this range. */
    val sgsrSharpness: Int = 100,
    /** EmuCore/GS/CASSharpness — sharpening strength 0..100 (%). */
    val casSharpness: Int = 50,
    /** EmuCore/GS/LoadTextureReplacements. */
    val loadTextureReplacements: Boolean = false,
    /** EmuCore/GS/LoadTextureReplacementsAsync. */
    val loadTextureReplacementsAsync: Boolean = true,
    /** EmuCore/GS/PrecacheTextureReplacements. */
    val precacheTextureReplacements: Boolean = false,
    /** EmuCore/GS/DumpReplaceableTextures. */
    val dumpReplaceableTextures: Boolean = false,
    /** EmuCore/GS/OsdShowTextureReplacements. */
    val osdShowTextureReplacements: Boolean = false,
    // Performance Overlay element toggles. Default true to mirror native
    // initialize(), which turns every OsdShow* bit on at first boot.
    // Disabling GPU also stops the GPU timing queries (real perf win).
    /** EmuCore/GS/OsdShowFPS. */
    val osdShowFps: Boolean = false,
    /** EmuCore/GS/OsdScale — size of on-screen messages/stats, percent (25–500, 100 = PCSX2's
     *  normal). Defaults to 65: at 100 the stats block dominates a handheld screen, and 65 matches
     *  the size NetherSX2 ships. Saves still on the old 100 default are migrated once (ConfigStore). */
    val osdScale: Int = 65,
    /** EmuCore/GS/OsdColor — OSD text colour as 0xRRGGBB. 0 = default white. */
    val osdColor: Int = 0,
    /** EmuCore/GS/VsyncEnable — sync presentation to the display refresh (less
     *  tearing/smoother, slightly higher latency). Applies on game restart. */
    val vsyncEnable: Boolean = false,
    /** EmuCore/GS/OsdShowVPS. */
    val osdShowVps: Boolean = false,
    /** EmuCore/GS/OsdShowSpeed. */
    val osdShowSpeed: Boolean = false,
    /** EmuCore/GS/OsdShowCPU. */
    val osdShowCpu: Boolean = false,
    /** EmuCore/GS/OsdShowGPU. */
    val osdShowGpu: Boolean = false,
    /** EmuCore/GS/OsdShowResolution. */
    val osdShowResolution: Boolean = false,
    /** EmuCore/GS/OsdShowGSStats. */
    val osdShowGsStats: Boolean = false,
    /** EmuCore/GS/OsdShowFrameTimes. */
    val osdShowFrameTimes: Boolean = false,
    /** EmuCore/GS/OsdShowHardwareInfo — the CPU/GPU model info line. */
    val osdShowHardwareInfo: Boolean = false,
    /** EmuCore/GS/OsdMessagesPos — transient OSD notifications (shader-compile
     *  popups, "settings applied", save-state, etc.). true = shown (TopLeft),
     *  false = hidden (None). Achievement popups are separate & unaffected. */
    val osdShowMessages: Boolean = true,
    /** EmuCore/GS/OsdShowGPUStats — GPU pipeline stats (VSI/PSI). Vulkan-only
     *  (GLES has no pipeline_statistics_query); default off since it's a niche
     *  diagnostic that adds per-frame query overhead. */
    val osdShowGpuStats: Boolean = false,
    /** EmuCore/GS/OsdShowVersion — the emulator version line. */
    val osdShowVersion: Boolean = false,
    /** EmuCore/GS/OsdShowSettings — the settings summary (bottom-left). */
    val osdShowSettings: Boolean = false,
    /** EmuCore/GS/OsdShowInputs — the control inputs (bottom-right). */
    val osdShowInputs: Boolean = false,
    /** EmuCore/GS/UserHacks_AutoFlushLevel — GSHWAutoFlushLevel:
     *  0 Disabled · 1 SpritesOnly · 2 Enabled. */
    val autoFlush: Int = 0,
    /** EmuCore/GS/UserHacks_HalfPixelOffset — GSHalfPixelOffset:
     *  0 Off · 1 Normal · 2 Special · 3 SpecialAggressive · 4 Native · 5 NativeWTexOffset. */
    val halfPixelOffset: Int = 0,
    /** EmuCore/GS/UserHacks_Limit24BitDepth — 0 Off · 1 Upper · 2 Lower. */
    val limit24BitDepth: Int = 0,
    /** EmuCore/GS/UserHacks — master hardware-fixes toggle. */
    val manualUserHacks: Boolean = false,
    /** EmuCore/GS/UserHacks_TextureInsideRt — texture inside render target. */
    val textureInsideRt: Int = 0,
    /** EmuCore/GS/UserHacks_native_scaling — upscaling fixes/native scaling. */
    val nativeScaling: Int = 0,
    /** EmuCore/GS/UserHacks_round_sprite_offset. */
    val roundSprite: Int = 0,
    /** EmuCore/GS/UserHacks_BilinearHack. */
    val bilinearUpscale: Int = 0,
    /** EmuCore/GS/UserHacks_GPUTargetCLUTMode. */
    val gpuTargetClut: Int = 0,
    /** EmuCore/GS/UserHacks_CPUSpriteRenderBW. */
    val cpuSpriteRenderBw: Int = 0,
    /** EmuCore/GS/UserHacks_CPUSpriteRenderLevel. */
    val cpuSpriteRenderLevel: Int = 0,
    // ---- Additional PCSX2 hardware / upscaling fixes (full parity) ----
    // Upscaling fixes
    /** EmuCore/GS/UserHacks_align_sprite_X — Align Sprite (fixes vertical lines on some 2D upscales). */
    val alignSprite: Boolean = false,
    /** EmuCore/GS/UserHacks_merge_pp_sprite — Merge Sprite (fixes lines between post-process sprites). */
    val mergeSprite: Boolean = false,
    /** EmuCore/GS/UserHacks_ForceEvenSpritePosition — "Wild Arms" hack; forces even sprite/texture positions. */
    val forceEvenSpritePosition: Boolean = false,
    /** EmuCore/GS/UserHacks_NativePaletteDraw — Unscaled Palette Texture Draws. */
    val unscaledPaletteDraw: Boolean = false,
    /** EmuCore/GS/UserHacks_TCOffsetX — texture-coordinate X offset, 0..10000 (= 0..10 px ×1000). */
    val textureOffsetX: Int = 0,
    /** EmuCore/GS/UserHacks_TCOffsetY — texture-coordinate Y offset, 0..10000 (= 0..10 px ×1000). */
    val textureOffsetY: Int = 0,
    // Hardware fixes
    /** EmuCore/GS/paltex — GPU Palette Conversion. */
    val gpuPaletteConversion: Boolean = false,
    /** EmuCore/GS/UserHacks_CPU_FB_Conversion — CPU Framebuffer Conversion. */
    val cpuFramebufferConversion: Boolean = false,
    /** EmuCore/GS/UserHacks_ReadTCOnClose — Read Targets When Closing. */
    val readTargetsWhenClosing: Boolean = false,
    /** EmuCore/GS/UserHacks_DisableDepthSupport — Disable Depth Emulation. */
    val disableDepthEmulation: Boolean = false,
    /** EmuCore/GS/UserHacks_DisablePartialInvalidation — Disable Partial Source Invalidation. */
    val disablePartialInvalidation: Boolean = false,
    /** EmuCore/GS/UserHacks_Disable_Safe_Features — Disable Safe Features. */
    val disableSafeFeatures: Boolean = false,
    /** EmuCore/GS/UserHacks_DisableRenderFixes — Disable Render Fixes. */
    val disableRenderFixes: Boolean = false,
    /** EmuCore/GS/preload_frame_with_gs_data — Preload Frame Data. */
    val preloadFrameData: Boolean = false,
    /** EmuCore/GS/UserHacks_EstimateTextureRegion — Estimate Texture Region. */
    val estimateTextureRegion: Boolean = false,
    /** EmuCore/GS/UserHacks_DrawBuffering — buffer draws (UserHack). */
    val drawBuffering: Boolean = false,
    /** EmuCore/GS/UserHacks_CPUCLUTRender — CPU CLUT Render: 0 Off · 1 Normal · 2 Aggressive. */
    val cpuClutRender: Int = 0,
    /** EmuCore/GS/TriFilter — TriFiltering: -1 Auto · 0 Off · 1 PS2 · 2 Forced. */
    val triFilter: Int = -1,
    /** EmuCore/GS/MaxAnisotropy — 0 Off, else 2/4/8/16. */
    val maxAnisotropy: Int = 0,
    /** EmuCore/GS/AndroidGpuProfileOverride — 0 Auto · 1 Mali · 2 Adreno · 3 PowerVR · 4 Xclipse.
     *  Stringified to "auto"/"mali"/"adreno"/"powervr"/"xclipse" when written to emucore.
     *  Picked up in GSDeviceOGL::CheckFeatures at device init; requires
     *  a renderer restart to take effect. */
    val gpuProfile: Int = 0,

    // ---- PS3 core (RPCS3) ----
    /** PS3 core settings. See [Ps3Settings] for why these are nested. */
    /** How the emulated image is fitted to the panel. App-side (surface layout
     *  + Stretch To Display Area), NOT a core setting: RPCS3's video_aspect only
     *  describes what the console signalled, not how you want it on screen.
     *  0 = Auto (preserve aspect), 1 = Stretch, 2 = Integer, 3 = Fill/crop. */
    /** 0 = Fit (preserve aspect), 1 = Stretch. Maps to RPCS3's one boolean,
     *  Video@@"Stretch To Display Area"; there is no integer-scale or fill mode. */
    val displayFitMode: Int = 0,
    val ps3: Ps3Settings = Ps3Settings(),
) {
    /** Routes a persisted-key write to the native base layer, or to
     *  [emitSink] when a per-game INI export is capturing the key set (see
     *  [writeGameSettingsIni]). Replaces the direct NativeApp.setSetting calls
     *  so applyTo/writeGsToNative can be reused as the single source of the
     *  field→EmuCore-key mapping for the export — no duplicated key list. */
    private fun put(section: String, key: String, type: String, value: String) {
        val sink = emitSink
        if (sink != null) sink(section, key, type, value)
        else NativeApp.setSetting(section, key, type, value)
    }

    /**
     * Packed ARGB -> RPCS3's "#RRGGBBAA".
     *
     * Android colour ints are ARGB; RPCS3's overlay strings are RGBA. Passing one
     * for the other silently swaps alpha into red, which reads as "the colour
     * picker sets the wrong colour" rather than as a format bug.
     */
    private fun argbToRgba(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return "#%06X%02X".format(rgb, a)
    }

    /**
     * Push every field into emucore via NativeApp.setSetting + commit.
     *
     * Wrapped in a settings batch. Each PS3 key reaching the core ran
     * Emulator::SaveSettings(g_cfg.to_string(), ""), which serialises the ENTIRE config
     * to YAML and writes it out -- and [applyToInner] pushes ~165 keys, so one toggle in
     * the in-game menu cost 165 full serialisations plus 165 file writes on the UI thread.
     * That is the reported menu lag. Batching collapses them into one write.
     *
     * try/finally because applyToInner has an early return on the INI-export path; leaving
     * the batch open there would defer the NEXT change's save indefinitely.
     */
    fun applyTo() {
        val batched = emitSink == null && MainActivityRuntime.nativeReady.value
        if (batched) runCatching { net.rpcsx.RPCSX.instance.settingsBeginBatch() }
        try {
            applyToInner()
        } finally {
            if (batched) runCatching { net.rpcsx.RPCSX.instance.settingsEndBatch() }
        }
    }

    private fun applyToInner() {
        // Speedhacks
        // PS3 core settings, routed by Rpcs3Bridge to the RPCS3 config tree.
        put("PS3/Core", "PPU Decoder", "enum", ps3.ppuDecoder.toString())
        put("PS3/Core", "SPU Decoder", "enum", ps3.spuDecoder.toString())
        put("PS3/Core", "SPU Block Size", "enum", ps3.spuBlockSize.toString())
        put("PS3/Core", "PPU Threads", "int", ps3.ppuThreads.toString())
        put("PS3/Core", "Max LLVM Compile Threads", "int", ps3.llvmThreads.toString())
        put("PS3/Core", "Preferred SPU Threads", "int", ps3.preferredSpuThreads.toString())
        put("PS3/Core", "Max SPURS Threads", "int", ps3.maxSpursThreads.toString())
        put("PS3/Core", "SPU loop detection", "bool", ps3.spuLoopDetection.toString())
        put("PS3/Core", "SPU Cache", "bool", ps3.spuCache.toString())
        put("PS3/Core", "LLVM Precompilation", "bool", ps3.llvmPrecompile.toString())
        put("PS3/Core", "Accurate SPU DMA", "bool", ps3.accurateSpuDma.toString())
        put("Savestate", "Compatible Savestate Mode", "bool", ps3.savestateCompatibleMode.toString())
        put("PS3/Core", "Clocks scale", "int", ps3.clocksScale.toString())
        // From upscaleFloat, which is the control that exists.
        //
        // ps3.resolutionScale has no writer anywhere in the UI, so it sits at its default of
        // 100 forever and this line used to push that default onto the same native node the
        // upscale multiplier writes, Video@@Resolution Scale. applyTo runs after the launch
        // path, so picking a scale and then booting a game silently rendered at native while
        // the UI kept showing the chosen value. Changing it in game worked only because
        // nothing calls applyTo again afterwards.
        //
        // Same conversion and clamp as Rpcs3Settings.setUpscaleMultiplier, so the two writers
        // cannot disagree about what a given multiplier means.
        put("PS3/Video", "Resolution Scale", "int",
            (upscaleFloat * 100f).toInt().coerceIn(25, 800).toString())
        // Stretch is the only fit mode the CORE participates in; the rest are
        // surface layout. Keeping them in sync stops "Stretch" looking inert.
        put("PS3/Video", "Stretch To Display Area", "bool", (displayFitMode == 1).toString())
        put("PS3/Video", "Display Aspect Override", "int", ps3.displayAspect.coerceIn(0, 4000).toString())
        put("PS3/Misc", "Silence All Logs", "bool", ps3.silenceAllLogs.toString())
        put("PS3/Overlay", "Enabled", "bool", ps3.overlayEnabled.toString())
        put("PS3/Overlay", "Detail level", "enum", ps3.overlayDetail.toString())
        put("PS3/Overlay", "Enable Framerate Graph", "bool", ps3.overlayFramerateGraph.toString())
        put("PS3/Overlay", "Enable Frametime Graph", "bool", ps3.overlayFrametimeGraph.toString())
        put("PS3/Overlay", "Font size (px)", "int", ps3.overlayFontSize.toString())
        put("PS3/Overlay", "Opacity (%)", "int", ps3.overlayOpacity.toString())
        put("PS3/Overlay", "Position", "enum", ps3.overlayPosition.toString())
        put("PS3/Overlay", "Body Color (hex)", "string", argbToRgba(ps3.overlayBodyColor))
        put("PS3/Overlay", "Body Background (hex)", "string", argbToRgba(ps3.overlayBodyBg))
        put("PS3/Overlay", "Title Color (hex)", "string", argbToRgba(ps3.overlayTitleColor))
        put("PS3/Overlay", "Title Background (hex)", "string", argbToRgba(ps3.overlayTitleBg))
        put("PS3/Video", "MSAA", "enum", ps3.msaaMode.toString())
        put("PS3/Video", "Shader Mode", "enum", ps3.shaderMode.toString())
        put("PS3/Video", "Frame Generation", "enum", ps3.frameGeneration.toString())
        put("PS3/Video", "Frame Generation Performance Mode", "bool", ps3.frameGenPerformance.toString())
        put("PS3/Video", "Frame Generation Flow Scale", "int", ps3.frameGenFlowScale.toString())
        put("PS3/Video", "Frame Generation Target Rate", "int", ps3.frameGenTargetRate.toString())
        // Temporary: the value reaches the core as 0 whatever the UI is set to, and all six
        // plumbing sites read correctly. This says what the object being applied actually holds.
        android.util.Log.i("FRAMEGEN", "applyTo: targetRate=${ps3.frameGenTargetRate} mult=${ps3.frameGeneration}")
        put("PS3/Video", "Write Color Buffers", "bool", ps3.writeColorBuffers.toString())
        put("PS3/Video", "Write Depth Buffer", "bool", ps3.writeDepthBuffer.toString())
        put("PS3/Video", "Read Color Buffers", "bool", ps3.readColorBuffers.toString())
        put("PS3/Video", "Read Depth Buffer", "bool", ps3.readDepthBuffer.toString())
        put("PS3/Video", "Strict Rendering Mode", "bool", ps3.strictRendering.toString())
        put("PS3/Video", "Multithreaded RSX", "bool", ps3.multithreadedRsx.toString())
        put("PS3/Video", "Disable ZCull Occlusion Queries", "bool", ps3.disableZcull.toString())
        put("PS3/Video", "Relaxed ZCULL Sync", "bool", ps3.relaxedZcull.toString())
        put("PS3/Video", "Use GPU texture scaling", "bool", ps3.gpuTextureScaling.toString())
        put("PS3/Video", "Force CPU Blit", "bool", ps3.forceCpuBlit.toString())
        put("PS3/Video", "Shader Compiler Threads", "int", ps3.shaderCompThreads.toString())
        put("PS3/Video", "Texture LOD Bias Addend", "int", ps3.textureLodBias.toString())
        put("PS3/Video", "VRAM allocation limit (MB)", "int", ps3.vramLimitMb.toString())
        put("PS3/Video", "Asynchronous Texture Streaming", "bool", ps3.asyncTexStream.toString())
        put("PS3/Audio", "Audio Format", "enum", ps3.audioFormat.toString())
        put("PS3/Audio", "Audio Channel Layout", "enum", ps3.audioChannels.toString())
        put("PS3/Audio", "Enable Time Stretching", "bool", ps3.audioTimeStretch.toString())
        put("PS3/Audio", "Enable Buffering", "bool", ps3.audioBuffering.toString())
        put("PS3/Audio", "Desired Audio Buffer Duration", "int", ps3.audioBufferMs.toString())
        put("PS3/Audio", "Cubeb Backend", "int", ps3.audioCubebBackend.toString())
        put("PS3/Net", "Internet enabled", "enum", ps3.netEnabled.toString())
        put("PS3/Net", "PSN status", "enum", ps3.psnStatus.toString())
        put("PS3/Net", "UPNP Enabled", "bool", ps3.upnpEnabled.toString())
        put("PS3/Net", "IP address", "string", ps3.ipAddress)
        put("PS3/Net", "Bind address", "string", ps3.bindAddress)
        put("PS3/Net", "DNS address", "string", ps3.dnsAddress)
        put("PS3/Net", "IP swap list", "string", ps3.ipSwapList)
        put("PS3/Net", "Derive MAC from PSID", "bool", ps3.deriveMacFromPsid.toString())
        put("PS3/Net", "PSN Country", "string", ps3.psnCountry)
        put("PS3/Net", "Clans Enabled", "bool", ps3.clansEnabled.toString())
        put("PS3/System", "Enter button assignment", "enum", ps3.enterButtonAssign.toString())
        put("PS3/System", "Language", "enum", ps3.consoleLanguage.toString())
        put("PS3/System", "License Area", "enum", ps3.consoleRegion.toString())
        put("PS3/System", "Keyboard Type", "enum", ps3.keyboardType.toString())
        put("PS3/System", "Date Format", "enum", ps3.dateFormat.toString())
        put("PS3/System", "Time Format", "enum", ps3.timeFormat.toString())
        put("PS3/Core", "SPU XFloat Accuracy", "enum", ps3.spuXFloat.toString())
        put("PS3/Core", "Accurate SPU Reservations", "bool", ps3.accurateSpuRsv.toString())
        put("PS3/Core", "Accurate Cache Line Stores", "bool", ps3.accurateCacheLine.toString())
        put("PS3/Core", "Accurate RSX reservation access", "bool", ps3.accurateRsxRsv.toString())
        put("PS3/Core", "PPU Reservation Priority Over SPUs", "bool", ps3.ppuRsvPriority.toString())
        put("PS3/Core", "SPU Verification", "bool", ps3.spuVerification.toString())
        put("PS3/Core", "Precise SPU Verification", "bool", ps3.preciseSpuVerification.toString())
        put("PS3/Core", "PPU Vector NaN Handling", "bool", ps3.ppuNanHandling.toString())
        put("PS3/Core", "Use Accurate DFMA", "bool", ps3.accurateDfma.toString())
        put("PS3/Core", "Set DAZ and FTZ", "bool", ps3.setDazFtz.toString())
        put("PS3/Core", "HLE lwmutex", "bool", ps3.hleLwmutex.toString())
        put("PS3/Core", "Sleep Timers Accuracy", "enum", ps3.sleepTimers.toString())
        put("PS3/Core", "Debug Console Mode", "bool", ps3.debugConsoleMode.toString())
        put("PS3/Video", "Resolution", "enum", ps3.resolution.toString())
        put("PS3/Video", "Anisotropic Filter Override", "int", ps3.anisoFilter.toString())
        put("PS3/Audio", "Renderer", "enum", ps3.audioRenderer.toString())
        put("EmuCore/Speedhacks", "EECycleRate", "int", eeCycleRate.toString())
        put("EmuCore/Speedhacks", "EECycleSkip", "int", eeCycleSkip.toString())
        // EE/FPU + VU clamping (recompiler accuracy). Each mode unpacks to the
        // PCSX2 bit flags below; both VUs get the same mode. Needs a recompiler
        // reset (commitSettings / game restart) to take effect.
        put("EmuCore/CPU/Recompiler", "fpuOverflow", "bool", (eeClampMode >= 1).toString())
        put("EmuCore/CPU/Recompiler", "fpuExtraOverflow", "bool", (eeClampMode >= 2).toString())
        put("EmuCore/CPU/Recompiler", "fpuFullMode", "bool", (eeClampMode >= 3).toString())
        for (vu in arrayOf("vu0", "vu1")) {
            put("EmuCore/CPU/Recompiler", "${vu}Overflow", "bool", (vuClampMode >= 1).toString())
            put("EmuCore/CPU/Recompiler", "${vu}ExtraOverflow", "bool", (vuClampMode >= 2).toString())
            put("EmuCore/CPU/Recompiler", "${vu}SignOverflow", "bool", (vuClampMode >= 3).toString())
        }
        put("EmuCore/Speedhacks", "vuThread", "bool", mtvu.toString())
        put("EmuCore/Speedhacks", "vu1Instant", "bool", vu1Instant.toString())
        put("EmuCore/Speedhacks", "vuFlagHack", "bool", vuFlagHack.toString())
        put("EmuCore/Speedhacks", "fastCDVD", "bool", fastCDVD.toString())
        put("EmuCore/Speedhacks", "IntcStat", "bool", intcStat.toString())
        put("EmuCore/Speedhacks", "WaitLoop", "bool", waitLoop.toString())
        put("EmuCore/Speedhacks", "vuNeonFusions", "bool", vuNeonFusions.toString())
        put("EmuCore/Speedhacks", "vuDeferredWrites", "bool", vuDeferredWrites.toString())
        put("EmuCore/Speedhacks", "vuSkipStallSim", "bool", vuSkipStallSim.toString())
        // GS frame limit. The setting key is persisted (read by runVMThread
        // after Initialize so cold starts honor the preference) AND the live
        // limiter mode is poked via speedhackLimitermode so toggling in-game
        // takes effect immediately. 0 = Nominal (capped at native rate),
        // 3 = Unlimited.
        put("EmuCore/GS", "FrameLimitEnable", "bool", frameLimitEnable.toString())
        // Preserve an active fast-forward / slow-down latch, exactly as the in-game overlay's
        // own frame-limit path does (MainActivityRuntime). Forcing 0/3 unconditionally here
        // clobbered Turbo on ANY settings apply while fast-forward was engaged — and since
        // fastForwardToggleActive stayed true, the UI kept reporting "Fast Forward ON" with
        // the emulator back at nominal speed. Frame-limit-off masked the bug: that path IS
        // mode 3, so re-asserting it changed nothing, which is why users saw "frame limit off
        // fast-forwards but fast-forward doesn't".
        if (emitSink == null) {
            NativeApp.speedhackLimitermode(
                when {
                    MainActivityRuntime.fastForwardToggleActive -> MainActivityRuntime.ffLimiterMode()
                    MainActivityRuntime.slowDownToggleActive -> 2
                    else -> if (frameLimitEnable) 0 else 3
                }
            )
        }
        // Framerate/NominalScalar — custom speed / FPS cap as a fraction of
        // native. commitSettings → ApplySettings → CheckForEmulationSpeedConfigChanges
        // → UpdateTargetSpeed picks this up live. Clamp mirrors emucore's
        // EmulationSpeedOptions::SanityCheck (0.05..10.0).
        put("Framerate", "NominalScalar", "float",
            (nominalSpeedPercent.coerceIn(10, 1000) / 100f).toString())
        // Live-apply: the setSetting above only persists; the running frame
        // pacer needs a direct re-pace (mirrors speedhackLimitermode).
        if (emitSink == null) NativeApp.setNominalSpeed(nominalSpeedPercent.coerceIn(10, 1000))
        // Max presented-FPS cap — independent of the Speed Limit % above. Caps
        // the display rate by dropping presents on the GS thread (emulation keeps
        // full speed, NominalScalar untouched); 0 = off. See GSRenderer::VSync.
        if (emitSink == null) NativeApp.setFpsCap(fpsLimit.coerceIn(-1, 1000)) // -1 = PS3 native pacing
        // Manual frameskip (0..5) — present 1 of every (N+1) frames. Held as a
        // GS-thread global, applied live; no persisted EmuCore key needed.
        if (emitSink == null) NativeApp.setFrameSkip(frameSkip.coerceIn(0, 5))
        if (emitSink == null) NativeApp.setPortraitRenderTop(portraitRenderTop)
        if (emitSink == null) NativeApp.setLandscapeRenderTop(landscapeRenderTop)
        // Audio (SPU2). Volume/mute are live native setters; the rest are written
        // to the base layer and applied on commit (SPU2 stream reconfigure).
        if (emitSink == null) NativeApp.setAudioVolume(audioVolume.coerceIn(0, 200))
        if (emitSink == null) NativeApp.setAudioMuted(audioMuted)
        if (emitSink == null) NativeApp.setAudioSwapChannels(audioSwapChannels)
        put("SPU2/Output", "SyncMode", "string", if (audioTimeStretch) "TimeStretch" else "Disabled")
        put("SPU2/Output", "BufferMS", "int", audioBufferMs.coerceIn(10, 200).toString())
        put("SPU2/Output", "OutputLatencyMS", "int", audioOutputLatencyMs.coerceIn(5, 200).toString())
        put("SPU2/Output", "FastForwardVolume", "int", audioFastForwardVolume.coerceIn(0, 200).toString())
        // Opt-in NEON reverb FIR (ARM64). Read by SPU2::InternalReset on the
        // next game boot; default off = scalar reference (unchanged audio).
        put("SPU2", "NeonReverbSIMD", "bool", spu2NeonReverb.toString())
        // Opt-in OpenSL ES output (Oboe). Lives in the SPU2/Output StreamParameters,
        // so ApplySettings → CheckForConfigChanges recreates the stream on toggle.
        put("SPU2/Output", "AndroidOpenSLES", "bool", audioOpenSLES.toString())
        // Lightweight mix (skip reverb) — read live in MixCore via EmuConfig.SPU2.
        put("SPU2/Output", "LightweightMode", "bool", spu2LightweightMix.toString())
        // Patches / cheats (EmuCore). Reloaded by ApplySettings →
        // CheckForPatchConfigChanges; widescreen/no-interlacing take effect on
        // the next boot for most games.
        put("EmuCore", "EnablePatches", "bool", enablePatches.toString())
        put("EmuCore", "EnableCheats", "bool", enableCheats.toString())
        put("EmuCore", "EnableWideScreenPatches", "bool", enableWideScreenPatches.toString())
        put("EmuCore", "EnableNoInterlacingPatches", "bool", enableNoInterlacingPatches.toString())
        put("EmuCore", "EnableFastBoot", "bool", enableFastBoot.toString())
        put("EmuCore", "HostFs", "bool", hostFs.toString())
        put("EmuCore", "EnableGameFixes", "bool", enableGameFixes.toString())
        put("EmuCore/Gamefixes", "SoftwareRendererFMVHack", "bool", gamefixSoftwareRendererFmv.toString())
        put("EmuCore/Gamefixes", "SkipMPEGHack", "bool", gamefixSkipMpeg.toString())
        put("EmuCore/Gamefixes", "EETimingHack", "bool", gamefixEETiming.toString())
        put("EmuCore/Gamefixes", "InstantDMAHack", "bool", gamefixInstantDma.toString())
        put("EmuCore/Gamefixes", "BlitInternalFPSHack", "bool", gamefixBlitInternalFps.toString())
        put("EmuCore/Gamefixes", "FpuMulHack", "bool", gamefixFpuMul.toString())
        put("EmuCore/Gamefixes", "OPHFlagHack", "bool", gamefixOphFlag.toString())
        put("EmuCore/Gamefixes", "GIFFIFOHack", "bool", gamefixGifFifo.toString())
        put("EmuCore/Gamefixes", "DMABusyHack", "bool", gamefixDmaBusy.toString())
        put("EmuCore/Gamefixes", "VIF1StallHack", "bool", gamefixVif1Stall.toString())
        put("EmuCore/Gamefixes", "IbitHack", "bool", gamefixIbit.toString())
        put("EmuCore/Gamefixes", "FullVU0SyncHack", "bool", gamefixFullVu0Sync.toString())
        put("EmuCore/Gamefixes", "VuAddSubHack", "bool", gamefixVuAddSub.toString())
        put("EmuCore/Gamefixes", "VUOverflowHack", "bool", gamefixVuOverflow.toString())
        put("EmuCore/Gamefixes", "XgKickHack", "bool", gamefixXgkick.toString())
        put("EmuCore/Gamefixes", "GoemonTlbHack", "bool", gamefixGoemonTlb.toString())
        put("EmuCore/Gamefixes", "VUSyncHack", "bool", gamefixVuSync.toString())
        put("EmuCore/GS", "SkipDuplicateFrames", "bool", skipDuplicateFrames.toString())
        put("EmuCore/CPU", "FPU.Roundmode", "int", eeFpuRoundMode.coerceIn(0, 3).toString())
        put("EmuCore/CPU", "VU0.Roundmode", "int", vu0RoundMode.coerceIn(0, 3).toString())
        put("EmuCore/CPU", "VU1.Roundmode", "int", vu1RoundMode.coerceIn(0, 3).toString())
        // Display + GS renderer + hardware/upscaling-fix keys are all written
        // together in writeGsToNative() below (shared with applyGsLive()).
        // DEV9. Networking/HDD are initialized with the VM, so changes
        // made from the in-game overlay are persisted for the next boot.
        put("DEV9/Eth", "EthEnable", "bool", dev9EthEnable.toString())
        put("DEV9/Eth", "EthApi", "string", dev9EthApi)
        put("DEV9/Eth", "LocalLinkHost", "bool", localLinkHost.toString())
        put("DEV9/Eth", "LocalLinkAddress", "string", localLinkAddress)
        put("DEV9/Eth", "LocalLinkPort", "int", localLinkPort.coerceIn(1, 65535).toString())
        put("DEV9/Eth", "LocalLinkPeerId", "int", localLinkPeerId.coerceIn(1, 65533).toString())
        put("DEV9/Eth", "LocalLinkRoomCode", "string", localLinkRoomCode)
        put("DEV9/Eth", "EthDevice", "string", dev9EthDevice.ifEmpty { "Auto" })
        put("DEV9/Eth", "EthLogDHCP", "bool", dev9EthLogDhcp.toString())
        put("DEV9/Eth", "EthLogDNS", "bool", dev9EthLogDns.toString())
        put("DEV9/Eth", "InterceptDHCP", "bool", dev9InterceptDhcp.toString())
        put("DEV9/Eth", "PS2IP", "string", dev9Ps2Ip.ifEmpty { "0.0.0.0" })
        put("DEV9/Eth", "Mask", "string", dev9Mask.ifEmpty { "0.0.0.0" })
        put("DEV9/Eth", "Gateway", "string", dev9Gateway.ifEmpty { "0.0.0.0" })
        put("DEV9/Eth", "DNS1", "string", dev9Dns1.ifEmpty { "0.0.0.0" })
        put("DEV9/Eth", "DNS2", "string", dev9Dns2.ifEmpty { "0.0.0.0" })
        put("DEV9/Eth", "AutoMask", "bool", dev9AutoMask.toString())
        put("DEV9/Eth", "AutoGateway", "bool", dev9AutoGateway.toString())
        put("DEV9/Eth", "ModeDNS1", "string", dev9ModeDns1.ifEmpty { "Auto" })
        put("DEV9/Eth", "ModeDNS2", "string", dev9ModeDns2.ifEmpty { "Auto" })
        // Internal-DNS host overrides. Count gates how many Host{i} sections the core reads.
        put("DEV9/Eth/Hosts", "Count", "int", dev9EthHosts.size.toString())
        dev9EthHosts.forEachIndexed { i, h ->
            put("DEV9/Eth/Hosts/Host$i", "Url", "string", h.url)
            put("DEV9/Eth/Hosts/Host$i", "Desc", "string", "ARMSX2")
            put("DEV9/Eth/Hosts/Host$i", "Address", "string", h.ip.ifEmpty { "0.0.0.0" })
            put("DEV9/Eth/Hosts/Host$i", "Enabled", "bool", h.enabled.toString())
        }
        put("DEV9/Hdd", "HddEnable", "bool", dev9HddEnable.toString())
        put("DEV9/Hdd", "HddFile", "string", dev9HddFile.ifEmpty { "DEV9hdd.raw" })
        put("MemoryCards", "Slot1_Enable", "bool", memoryCardSlot1Enabled.toString())
        put("MemoryCards", "Slot1_Filename", "string", memoryCardSlot1Filename.ifEmpty { "mcd001.ps2" })
        put("MemoryCards", "Slot2_Enable", "bool", memoryCardSlot2Enabled.toString())
        put("MemoryCards", "Slot2_Filename", "string", memoryCardSlot2Filename.ifEmpty { "mcd002.ps2" })
        // Keyboard: NOT written here. [USB1] Type = hidkbd is a PCSX2 key -- there is
        // no such USB device in RPCS3, so that write only ever reached
        // Unsupported.note("USB1/Type"). The PS3 equivalent is the keyboard handler,
        // pushed by NativeApp.usbSetKeyboardEnabled below.
        // Recompiler enables. Picked up by VMManager::ApplySettings →
        // SysCpuProviderPack rebind. Toggling these on a running VM swaps
        // the dispatch pointer; existing JIT block caches are flushed by
        // ApplySettings's CpusChanged path.
        put("EmuCore/CPU/Recompiler", "EnableEE", "bool", recEE.toString())
        put("EmuCore/CPU/Recompiler", "EnableIOP", "bool", recIOP.toString())
        put("EmuCore/CPU/Recompiler", "EnableVU0", "bool", recVU0.toString())
        put("EmuCore/CPU/Recompiler", "EnableVU1", "bool", recVU1.toString())
        put("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", enableFastmem.toString())
        // Force the single macOS/PCSX2 ARM64 backend. VMManager also ignores
        // stale UseMac* values, but writing true cleans old persisted settings.
        put("EmuCore/CPU/Recompiler", "UseMacEE", "bool", "true")
        put("EmuCore/CPU/Recompiler", "UseMacIOP", "bool", "true")
        put("EmuCore/CPU/Recompiler", "UseMacVU0", "bool", "true")
        put("EmuCore/CPU/Recompiler", "UseMacVU1", "bool", "true")
        put("EmuCore/CPU/Recompiler", "Vu1InlineFmacStall", "bool", vu1InlineFmacStall.toString())
        put("EmuCore/CPU/Recompiler", "Vu1CrossBlockPState", "bool", vu1CrossBlockPState.toString())
        put("EmuCore/CPU/Recompiler", "Vu1InlineDrainTestPipes", "bool", vu1InlineDrainTestPipes.toString())
        put("EmuCore/CPU/Recompiler", "Vu1FmacInstanceRouting", "bool", vu1FmacInstanceRouting.toString())
        writeGsToNative()
        // Per-game INI export is capturing the key set only — writeGsToNative()
        // above was the last persisted emit, so stop before the live pokes /
        // commit (they'd re-poke the VM and double-park it; the export must not).
        if (emitSink != null) return
        // Live convenience pokes. Harmless when the GS is closed; commitSettings()
        // below performs the authoritative apply for a cold start / restart.
        // 0..5 — the upper bound is the LAST aspect index, so adding a ratio means widening this
        // too. Left at 4 it silently sent 10:7 to the core no matter what the picker showed, which
        // is the worst version of this bug: the UI looks correct and nothing happens.
        NativeApp.setAspectRatio(aspectRatio.coerceIn(0, 8))
        NativeApp.setFmvAspectRatio(fmvAspectRatio.coerceIn(0, 8))
        NativeApp.renderTvShader(tvShader.coerceIn(0, 7))
        NativeApp.renderShadeBoost(
            shadeBoost,
            shadeBoostBrightness.coerceIn(1, 100),
            shadeBoostContrast.coerceIn(1, 100),
            shadeBoostSaturation.coerceIn(1, 100),
            shadeBoostGamma.coerceIn(1, 100),
        )
        NativeApp.osdShowFPS(osdShowFps)
        NativeApp.osdSetScale(osdScale.toFloat())
        NativeApp.osdSetColor(osdColor)
        NativeApp.osdShowVPS(osdShowVps)
        NativeApp.osdShowSpeed(osdShowSpeed)
        NativeApp.osdShowCPU(osdShowCpu)
        NativeApp.osdShowGPU(osdShowGpu)
        NativeApp.osdShowResolution(osdShowResolution)
        NativeApp.osdShowGSStats(osdShowGsStats)
        NativeApp.osdShowFrameTimes(osdShowFrameTimes)
        NativeApp.osdShowHardwareInfo(osdShowHardwareInfo)
        NativeApp.osdShowMessages(osdShowMessages)
        NativeApp.osdShowGpuStats(osdShowGpuStats)
        NativeApp.osdShowVersion(osdShowVersion)
        NativeApp.osdShowSettings(osdShowSettings)
        NativeApp.osdShowInputs(osdShowInputs)
        // Keyboard handler (#254). Installed by Emulator::Load, so this is a persist,
        // not a live attach: a game already running keeps whatever it booted with.
        NativeApp.usbSetKeyboardEnabled(0, usbKeyboard)
        // Vblank at the PS3's own rate, pushed on every apply rather than left to a
        // migration.
        //
        // A PS3 runs a 60Hz vblank and every game was written against it. The stored value
        // was 120, which asks the emulator for twice the frames the hardware ever produced:
        // twice the RSX command volume, twice the vertex upload, twice the GPU work, on a
        // handheld. Recording it as a core override did not hold, so it goes through the
        // curated push like any other default.
        //
        // A deliberate change in All Core Settings still wins, because CoreSettingOverrides
        // replays immediately below this.
        runCatching { net.rpcsx.RPCSX.instance.settingsSet("Video@@Vblank Rate", "60") }
        // Frame limit is deliberately NOT forced here any more.
        //
        // It used to be written to "60" on every push as a belt-and-braces way to reach a 60Hz cap
        // alongside the Vblank Rate above. But this is the same node the Display FPS Cap row writes,
        // and this push runs after it, so choosing 30 wrote the enum and then this overwrote it --
        // the cap did nothing for every preset value while 20 and 45 worked, because those take the
        // free-form Second Frame Limit path instead. The core reported frame_limit=_60 on every flip
        // no matter what the UI had just been told.
        //
        // The 60Hz intent survives without it: Frame limit Auto resolves to the vblank rate, which
        // the line above pins to 60. ConfigStore also clears the stale core override that pinned
        // this node, since that replayed even later than this did.
        // Left at upstream's 100: busy-wait on a reservation rather than sleeping.
        //
        // This was dropped to 20 while the emulator was starved for cores, on the reasoning that
        // a spinning SPU steals a core from threads doing real work. That reasoning was sound
        // for the machine as it was and is wrong for the machine as it is now. Two things
        // changed underneath it: the affinity mask stopped confining six SPU threads to four
        // cores, and turning off the global lock contention in the reservation path freed the
        // rest. Measured after both, in game: 34% of eight cores busy, two to four threads
        // runnable, five cores idle.
        //
        // Nothing is saturated at that point, so the frame is waiting on a dependency chain
        // rather than on throughput, and sleeping to save a core that nobody wants only adds
        // wake-up latency to the chain that is actually holding the frame.
        runCatching { net.rpcsx.RPCSX.instance.settingsSet("Core@@SPU GETLLAR Busy Waiting Percentage", "100") }
        // Compatible Savestate Mode is no longer forced off here; applyTo writes it from
        // ps3.savestateCompatibleMode above, so the two costs it carries -- SPU performance
        // and a 500MB to 3GB state file -- are the user's to accept rather than a decision
        // taken for them. Defaulted on so the feature works without hunting for a switch,
        // and written every boot either way, so a user who turns it off has that respected
        // rather than re-enabled on the next launch.

        // Settings a specific title needs in order to run at all, then the user's own core
        // edits on top. Order matters: game defaults are a floor, an explicit user choice
        // still wins over them, and both have to land after the curated push above.
        runCatching { GameDefaults.apply(MainActivityRuntime.currentGame.value?.serial) }
        // Core edits are global plus this title's own set, and replay orders them that way.
        // Keyed on settingsKey, the identity ConfigStore keys per-game settings on, so the
        // two stores agree on which title is being configured (serial for discs, filename
        // stem for serial-less ELF/homebrew). Null with no game loaded, which skips the
        // per-game tier rather than letting the last title played leak into a BIOS boot.
        runCatching {
            CoreSettingOverrides.replay(MainActivityRuntime.currentGame.value?.settingsKey)
        }
        NativeApp.commitSettings()
    }

    /** Reverse of [applyTo]: rebuild a Settings from a parsed PCSX2-Android.ini map
     *  (keys "Section/Key" -> raw string value). Any key absent from the map keeps this
     *  Settings' current value (call on Settings() to default-fill). Used to recover an
     *  existing native config when the new UI has no stored config.global (fresh install
     *  over a reused data folder). Mirror applyTo's field->(section,key) mapping EXACTLY.
     *
     *  Only keys applyTo/writeGsToNative actually persist via [put] are inverted here.
     *  Live-only pokes (fpsLimit, frameSkip, audioVolume/Muted/SwapChannels) and the
     *  launch-time renderer/upscaleFloat helpers write no base-layer key, so those fields
     *  keep their current value. UseMac* are legacy/forced-on (applyTo always writes
     *  "true"), so they are forced true here to match fromJson. */
    fun readFromIni(ini: Map<String, String>): Settings {
        // Typed lookups: null when the key is absent (or unparseable) so callers
        // fall back to `this.<field>` via ?:.
        fun boolAt(key: String): Boolean? = ini[key]?.let { it == "true" || it == "1" }
        fun intAt(key: String): Int? = ini[key]?.toIntOrNull()
        fun floatAt(key: String): Float? = ini[key]?.toFloatOrNull()
        fun strAt(key: String): String? = ini[key]

        // EE/FPU clamp (0 None / 1 Normal / 2 Extra / 3 Full) is packed by applyTo into
        // three cumulative bool keys (fpuOverflow>=1, fpuExtraOverflow>=2, fpuFullMode>=3).
        val eeClamp = run {
            val fo = boolAt("EmuCore/CPU/Recompiler/fpuOverflow")
            val fe = boolAt("EmuCore/CPU/Recompiler/fpuExtraOverflow")
            val ff = boolAt("EmuCore/CPU/Recompiler/fpuFullMode")
            if (fo == null && fe == null && ff == null) this.eeClampMode
            else if (ff == true) 3 else if (fe == true) 2 else if (fo == true) 1 else 0
        }
        // VU clamp (0 None / 1 Normal / 2 Extra / 3 Extra+Sign) — same packing on vu0*
        // (applyTo writes vu0 and vu1 identically, so reading vu0 recovers the mode).
        val vuClamp = run {
            val o = boolAt("EmuCore/CPU/Recompiler/vu0Overflow")
            val e = boolAt("EmuCore/CPU/Recompiler/vu0ExtraOverflow")
            val sgn = boolAt("EmuCore/CPU/Recompiler/vu0SignOverflow")
            if (o == null && e == null && sgn == null) this.vuClampMode
            else if (sgn == true) 3 else if (e == true) 2 else if (o == true) 1 else 0
        }

        // renderer + upscale aren't written by applyTo's put() — the core / renderUpscalemultiplier
        // persist them to the base layer directly — so recover them from the native keys.
        // GSRendererType: Auto=-1, OGL=12, SW=13, VK=14.
        val recoveredRenderer = when (intAt("EmuCore/GS/Renderer")) {
            -1 -> "auto"
            12 -> "opengl"
            13 -> "software"
            14 -> "vulkan"
            else -> this.renderer
        }

        return this.copy(
            // ---- Renderer + upscale (base-layer keys, not applyTo put()) ----
            renderer = recoveredRenderer,
            upscaleFloat = floatAt("EmuCore/GS/upscale_multiplier") ?: this.upscaleFloat,
            // ---- EmuCore/Speedhacks ----
            eeCycleRate = intAt("EmuCore/Speedhacks/EECycleRate") ?: this.eeCycleRate,
            eeCycleSkip = intAt("EmuCore/Speedhacks/EECycleSkip") ?: this.eeCycleSkip,
            eeClampMode = eeClamp,
            vuClampMode = vuClamp,
            mtvu = boolAt("EmuCore/Speedhacks/vuThread") ?: this.mtvu,
            vu1Instant = boolAt("EmuCore/Speedhacks/vu1Instant") ?: this.vu1Instant,
            vuFlagHack = boolAt("EmuCore/Speedhacks/vuFlagHack") ?: this.vuFlagHack,
            fastCDVD = boolAt("EmuCore/Speedhacks/fastCDVD") ?: this.fastCDVD,
            intcStat = boolAt("EmuCore/Speedhacks/IntcStat") ?: this.intcStat,
            waitLoop = boolAt("EmuCore/Speedhacks/WaitLoop") ?: this.waitLoop,
            vuNeonFusions = boolAt("EmuCore/Speedhacks/vuNeonFusions") ?: this.vuNeonFusions,
            vuDeferredWrites = boolAt("EmuCore/Speedhacks/vuDeferredWrites") ?: this.vuDeferredWrites,
            vuSkipStallSim = boolAt("EmuCore/Speedhacks/vuSkipStallSim") ?: this.vuSkipStallSim,
            // ---- Frame limiter (nominalSpeedPercent stored as the 0.10..10.0 scalar) ----
            frameLimitEnable = boolAt("EmuCore/GS/FrameLimitEnable") ?: this.frameLimitEnable,
            nominalSpeedPercent = floatAt("Framerate/NominalScalar")?.let { Math.round(it * 100f) }
                ?: this.nominalSpeedPercent,
            // ---- Audio (SPU2/Output) — SyncMode is TimeStretch/Disabled ----
            audioTimeStretch = strAt("SPU2/Output/SyncMode")?.let { it == "TimeStretch" } ?: this.audioTimeStretch,
            audioBufferMs = intAt("SPU2/Output/BufferMS") ?: this.audioBufferMs,
            audioOutputLatencyMs = intAt("SPU2/Output/OutputLatencyMS") ?: this.audioOutputLatencyMs,
            audioFastForwardVolume = intAt("SPU2/Output/FastForwardVolume") ?: this.audioFastForwardVolume,
            spu2NeonReverb = boolAt("SPU2/NeonReverbSIMD") ?: this.spu2NeonReverb,
            audioOpenSLES = boolAt("SPU2/Output/AndroidOpenSLES") ?: this.audioOpenSLES,
            spu2LightweightMix = boolAt("SPU2/Output/LightweightMode") ?: this.spu2LightweightMix,
            // ---- EmuCore patches / cheats ----
            enablePatches = boolAt("EmuCore/EnablePatches") ?: this.enablePatches,
            enableCheats = boolAt("EmuCore/EnableCheats") ?: this.enableCheats,
            enableWideScreenPatches = boolAt("EmuCore/EnableWideScreenPatches") ?: this.enableWideScreenPatches,
            enableNoInterlacingPatches = boolAt("EmuCore/EnableNoInterlacingPatches") ?: this.enableNoInterlacingPatches,
            enableFastBoot = boolAt("EmuCore/EnableFastBoot") ?: this.enableFastBoot,
            hostFs = boolAt("EmuCore/HostFs") ?: this.hostFs,
            enableGameFixes = boolAt("EmuCore/EnableGameFixes") ?: this.enableGameFixes,
            // ---- EmuCore/Gamefixes ----
            gamefixSoftwareRendererFmv = boolAt("EmuCore/Gamefixes/SoftwareRendererFMVHack") ?: this.gamefixSoftwareRendererFmv,
            gamefixSkipMpeg = boolAt("EmuCore/Gamefixes/SkipMPEGHack") ?: this.gamefixSkipMpeg,
            gamefixEETiming = boolAt("EmuCore/Gamefixes/EETimingHack") ?: this.gamefixEETiming,
            gamefixInstantDma = boolAt("EmuCore/Gamefixes/InstantDMAHack") ?: this.gamefixInstantDma,
            gamefixBlitInternalFps = boolAt("EmuCore/Gamefixes/BlitInternalFPSHack") ?: this.gamefixBlitInternalFps,
            gamefixFpuMul = boolAt("EmuCore/Gamefixes/FpuMulHack") ?: this.gamefixFpuMul,
            gamefixOphFlag = boolAt("EmuCore/Gamefixes/OPHFlagHack") ?: this.gamefixOphFlag,
            gamefixGifFifo = boolAt("EmuCore/Gamefixes/GIFFIFOHack") ?: this.gamefixGifFifo,
            gamefixDmaBusy = boolAt("EmuCore/Gamefixes/DMABusyHack") ?: this.gamefixDmaBusy,
            gamefixVif1Stall = boolAt("EmuCore/Gamefixes/VIF1StallHack") ?: this.gamefixVif1Stall,
            gamefixIbit = boolAt("EmuCore/Gamefixes/IbitHack") ?: this.gamefixIbit,
            gamefixFullVu0Sync = boolAt("EmuCore/Gamefixes/FullVU0SyncHack") ?: this.gamefixFullVu0Sync,
            gamefixVuAddSub = boolAt("EmuCore/Gamefixes/VuAddSubHack") ?: this.gamefixVuAddSub,
            gamefixVuOverflow = boolAt("EmuCore/Gamefixes/VUOverflowHack") ?: this.gamefixVuOverflow,
            gamefixXgkick = boolAt("EmuCore/Gamefixes/XgKickHack") ?: this.gamefixXgkick,
            gamefixGoemonTlb = boolAt("EmuCore/Gamefixes/GoemonTlbHack") ?: this.gamefixGoemonTlb,
            gamefixVuSync = boolAt("EmuCore/Gamefixes/VUSyncHack") ?: this.gamefixVuSync,
            skipDuplicateFrames = boolAt("EmuCore/GS/SkipDuplicateFrames") ?: this.skipDuplicateFrames,
            eeFpuRoundMode = intAt("EmuCore/CPU/FPU.Roundmode") ?: this.eeFpuRoundMode,
            vu0RoundMode = intAt("EmuCore/CPU/VU0.Roundmode") ?: this.vu0RoundMode,
            vu1RoundMode = intAt("EmuCore/CPU/VU1.Roundmode") ?: this.vu1RoundMode,
            // ---- DEV9 — Ethernet / HDD ----
            dev9EthEnable = boolAt("DEV9/Eth/EthEnable") ?: this.dev9EthEnable,
            dev9EthApi = strAt("DEV9/Eth/EthApi") ?: this.dev9EthApi,
            localLinkHost = boolAt("DEV9/Eth/LocalLinkHost") ?: this.localLinkHost,
            localLinkAddress = strAt("DEV9/Eth/LocalLinkAddress") ?: this.localLinkAddress,
            localLinkPort = intAt("DEV9/Eth/LocalLinkPort") ?: this.localLinkPort,
            localLinkPeerId = intAt("DEV9/Eth/LocalLinkPeerId") ?: this.localLinkPeerId,
            localLinkRoomCode = strAt("DEV9/Eth/LocalLinkRoomCode") ?: this.localLinkRoomCode,
            dev9EthDevice = strAt("DEV9/Eth/EthDevice") ?: this.dev9EthDevice,
            dev9EthLogDhcp = boolAt("DEV9/Eth/EthLogDHCP") ?: this.dev9EthLogDhcp,
            dev9EthLogDns = boolAt("DEV9/Eth/EthLogDNS") ?: this.dev9EthLogDns,
            dev9InterceptDhcp = boolAt("DEV9/Eth/InterceptDHCP") ?: this.dev9InterceptDhcp,
            dev9Ps2Ip = strAt("DEV9/Eth/PS2IP") ?: this.dev9Ps2Ip,
            dev9Mask = strAt("DEV9/Eth/Mask") ?: this.dev9Mask,
            dev9Gateway = strAt("DEV9/Eth/Gateway") ?: this.dev9Gateway,
            dev9Dns1 = strAt("DEV9/Eth/DNS1") ?: this.dev9Dns1,
            dev9Dns2 = strAt("DEV9/Eth/DNS2") ?: this.dev9Dns2,
            dev9AutoMask = boolAt("DEV9/Eth/AutoMask") ?: this.dev9AutoMask,
            dev9AutoGateway = boolAt("DEV9/Eth/AutoGateway") ?: this.dev9AutoGateway,
            dev9ModeDns1 = strAt("DEV9/Eth/ModeDNS1") ?: this.dev9ModeDns1,
            dev9ModeDns2 = strAt("DEV9/Eth/ModeDNS2") ?: this.dev9ModeDns2,
            // Internal-DNS host overrides — Count gates Host{i} sections (Desc is ignored).
            dev9EthHosts = run {
                val count = intAt("DEV9/Eth/Hosts/Count") ?: return@run this.dev9EthHosts
                (0 until count).mapNotNull { idx ->
                    val url = ini["DEV9/Eth/Hosts/Host$idx/Url"] ?: return@mapNotNull null
                    Dev9HostMapping(
                        url = url,
                        ip = (ini["DEV9/Eth/Hosts/Host$idx/Address"] ?: "0.0.0.0").ifEmpty { "0.0.0.0" },
                        enabled = boolAt("DEV9/Eth/Hosts/Host$idx/Enabled") ?: true,
                    )
                }.filter { it.url.isNotBlank() }
            },
            dev9HddEnable = boolAt("DEV9/Hdd/HddEnable") ?: this.dev9HddEnable,
            dev9HddFile = strAt("DEV9/Hdd/HddFile") ?: this.dev9HddFile,
            // ---- MemoryCards ----
            memoryCardSlot1Enabled = boolAt("MemoryCards/Slot1_Enable") ?: this.memoryCardSlot1Enabled,
            memoryCardSlot1Filename = strAt("MemoryCards/Slot1_Filename") ?: this.memoryCardSlot1Filename,
            memoryCardSlot2Enabled = boolAt("MemoryCards/Slot2_Enable") ?: this.memoryCardSlot2Enabled,
            memoryCardSlot2Filename = strAt("MemoryCards/Slot2_Filename") ?: this.memoryCardSlot2Filename,
            // ---- USB keyboard (USB1/Type = hidkbd/None) ----
            usbKeyboard = strAt("USB1/Type")?.let { it == "hidkbd" } ?: this.usbKeyboard,
            // ---- EmuCore/CPU/Recompiler enables ----
            recEE = boolAt("EmuCore/CPU/Recompiler/EnableEE") ?: this.recEE,
            recIOP = boolAt("EmuCore/CPU/Recompiler/EnableIOP") ?: this.recIOP,
            recVU0 = boolAt("EmuCore/CPU/Recompiler/EnableVU0") ?: this.recVU0,
            recVU1 = boolAt("EmuCore/CPU/Recompiler/EnableVU1") ?: this.recVU1,
            enableFastmem = boolAt("EmuCore/CPU/Recompiler/EnableFastmem") ?: this.enableFastmem,
            // Legacy/forced-on ARM64 backend flags — always "true" in the INI (mirror fromJson).
            useMacEE = true,
            useMacIOP = true,
            useMacVU0 = true,
            useMacVU1 = true,
            vu1InlineFmacStall = boolAt("EmuCore/CPU/Recompiler/Vu1InlineFmacStall") ?: this.vu1InlineFmacStall,
            vu1CrossBlockPState = boolAt("EmuCore/CPU/Recompiler/Vu1CrossBlockPState") ?: this.vu1CrossBlockPState,
            vu1InlineDrainTestPipes = boolAt("EmuCore/CPU/Recompiler/Vu1InlineDrainTestPipes") ?: this.vu1InlineDrainTestPipes,
            vu1FmacInstanceRouting = boolAt("EmuCore/CPU/Recompiler/Vu1FmacInstanceRouting") ?: this.vu1FmacInstanceRouting,
            // ---- EmuCore/GS (writeGsToNative). Aspect/FMV/gpuProfile stored as names. ----
            customAspectRatio = floatAt("EmuCore/GS/CustomAspectRatio") ?: this.customAspectRatio,
            aspectRatio = when (strAt("EmuCore/GS/AspectRatio")) {
                "Stretch" -> 0
                "Auto 4:3/3:2" -> 1
                "4:3" -> 2
                "16:9" -> 3
                "10:7" -> 4
                "21:9" -> 5
                "20:9" -> 6
                "19.5:9" -> 7
                "Custom" -> 8
                else -> this.aspectRatio
            },
            fmvAspectRatio = when (strAt("EmuCore/GS/FMVAspectRatioSwitch")) {
                "Off" -> 0
                "Auto 4:3/3:2" -> 1
                "4:3" -> 2
                "16:9" -> 3
                "10:7" -> 4
                "21:9" -> 5
                "20:9" -> 6
                "19.5:9" -> 7
                "Custom" -> 8
                else -> this.fmvAspectRatio
            },
            deinterlaceMode = intAt("EmuCore/GS/deinterlace_mode") ?: this.deinterlaceMode,
            framerateNtsc = floatAt("EmuCore/GS/FramerateNTSC") ?: this.framerateNtsc,
            frameratePal = floatAt("EmuCore/GS/FrameratePAL") ?: this.frameratePal,
            hwMipmap = boolAt("EmuCore/GS/hw_mipmap") ?: this.hwMipmap,
            accurateBlendingUnit = intAt("EmuCore/GS/accurate_blending_unit") ?: this.accurateBlendingUnit,
            textureFiltering = intAt("EmuCore/GS/filter") ?: this.textureFiltering,
            displayBilinear = intAt("EmuCore/GS/linear_present_mode") ?: this.displayBilinear,
            texturePreloading = intAt("EmuCore/GS/texture_preloading") ?: this.texturePreloading,
            hardwareDownloadMode = intAt("EmuCore/GS/HWDownloadMode") ?: this.hardwareDownloadMode,
            tvShader = intAt("EmuCore/GS/TVShader") ?: this.tvShader,
            shadeBoost = boolAt("EmuCore/GS/ShadeBoost") ?: this.shadeBoost,
            shadeBoostBrightness = intAt("EmuCore/GS/ShadeBoost_Brightness") ?: this.shadeBoostBrightness,
            shadeBoostContrast = intAt("EmuCore/GS/ShadeBoost_Contrast") ?: this.shadeBoostContrast,
            shadeBoostSaturation = intAt("EmuCore/GS/ShadeBoost_Saturation") ?: this.shadeBoostSaturation,
            shadeBoostGamma = intAt("EmuCore/GS/ShadeBoost_Gamma") ?: this.shadeBoostGamma,
            fxaa = boolAt("EmuCore/GS/fxaa") ?: this.fxaa,
            shaderChainEnabled = boolAt("EmuCore/GS/ShaderChainEnabled") ?: this.shaderChainEnabled,
            shaderChainPreset = strAt("EmuCore/GS/ShaderChainPreset") ?: this.shaderChainPreset,
            shaderChainParams = strAt("EmuCore/GS/ShaderChainParams")?.let { raw ->
                // Hand-editable file, so a malformed blob is a real possibility: keep the
                // rest of the recovered settings rather than throwing the lot away.
                runCatching { shaderChainParamsFromJson(JSONObject(raw)) }.getOrNull()
            } ?: this.shaderChainParams,
            casMode = intAt("EmuCore/GS/CASMode") ?: this.casMode,
            casSharpness = intAt("EmuCore/GS/CASSharpness") ?: this.casSharpness,
            loadTextureReplacements = boolAt("EmuCore/GS/LoadTextureReplacements") ?: this.loadTextureReplacements,
            loadTextureReplacementsAsync = boolAt("EmuCore/GS/LoadTextureReplacementsAsync") ?: this.loadTextureReplacementsAsync,
            precacheTextureReplacements = boolAt("EmuCore/GS/PrecacheTextureReplacements") ?: this.precacheTextureReplacements,
            dumpReplaceableTextures = boolAt("EmuCore/GS/DumpReplaceableTextures") ?: this.dumpReplaceableTextures,
            osdShowTextureReplacements = boolAt("EmuCore/GS/OsdShowTextureReplacements") ?: this.osdShowTextureReplacements,
            osdShowFps = boolAt("EmuCore/GS/OsdShowFPS") ?: this.osdShowFps,
            osdScale = intAt("EmuCore/GS/OsdScale") ?: this.osdScale,
            osdColor = intAt("EmuCore/GS/OsdColor") ?: this.osdColor,
            vsyncEnable = boolAt("EmuCore/GS/VsyncEnable") ?: this.vsyncEnable,
            osdShowVps = boolAt("EmuCore/GS/OsdShowVPS") ?: this.osdShowVps,
            osdShowSpeed = boolAt("EmuCore/GS/OsdShowSpeed") ?: this.osdShowSpeed,
            osdShowCpu = boolAt("EmuCore/GS/OsdShowCPU") ?: this.osdShowCpu,
            osdShowGpu = boolAt("EmuCore/GS/OsdShowGPU") ?: this.osdShowGpu,
            osdShowResolution = boolAt("EmuCore/GS/OsdShowResolution") ?: this.osdShowResolution,
            osdShowGsStats = boolAt("EmuCore/GS/OsdShowGSStats") ?: this.osdShowGsStats,
            osdShowFrameTimes = boolAt("EmuCore/GS/OsdShowFrameTimes") ?: this.osdShowFrameTimes,
            osdShowHardwareInfo = boolAt("EmuCore/GS/OsdShowHardwareInfo") ?: this.osdShowHardwareInfo,
            // OsdMessagesPos is an enum int (0 None / 1 TopLeft); applyTo writes 1 when shown.
            osdShowMessages = intAt("EmuCore/GS/OsdMessagesPos")?.let { it != 0 } ?: this.osdShowMessages,
            osdShowGpuStats = boolAt("EmuCore/GS/OsdShowGPUStats") ?: this.osdShowGpuStats,
            osdShowVersion = boolAt("EmuCore/GS/OsdShowVersion") ?: this.osdShowVersion,
            osdShowSettings = boolAt("EmuCore/GS/OsdShowSettings") ?: this.osdShowSettings,
            osdShowInputs = boolAt("EmuCore/GS/OsdShowInputs") ?: this.osdShowInputs,
            screenOffsets = boolAt("EmuCore/GS/pcrtc_offsets") ?: this.screenOffsets,
            showOverscan = boolAt("EmuCore/GS/pcrtc_overscan") ?: this.showOverscan,
            antiBlur = boolAt("EmuCore/GS/pcrtc_antiblur") ?: this.antiBlur,
            disableInterlaceOffset = boolAt("EmuCore/GS/disable_interlace_offset") ?: this.disableInterlaceOffset,
            syncToHostRefresh = boolAt("EmuCore/GS/SyncToHostRefreshRate") ?: this.syncToHostRefresh,
            disableFramebufferFetch = boolAt("EmuCore/GS/DisableFramebufferFetch") ?: this.disableFramebufferFetch,
            hwRov = boolAt("EmuCore/GS/HWROV") ?: this.hwRov,
            hwAa1 = boolAt("EmuCore/GS/HWAA1") ?: this.hwAa1,
            adrenoFbFetch = boolAt("EmuCore/GS/EnableAdrenoFramebufferFetch") ?: this.adrenoFbFetch,
            coalesceRenderPasses = boolAt("EmuCore/GS/CoalesceRenderPasses") ?: this.coalesceRenderPasses,
            forceMaliFbFetch = boolAt("EmuCore/GS/ForceMaliFramebufferFetch") ?: this.forceMaliFbFetch,
            useAngleOpenGL = boolAt("EmuCore/GS/AndroidUseAngleOpenGL") ?: this.useAngleOpenGL,
            overrideTextureBarriers = intAt("EmuCore/GS/OverrideTextureBarriers") ?: this.overrideTextureBarriers,
            gsBackThreadMode = intAt("EmuCore/GS/GSBackThreadMode") ?: this.gsBackThreadMode,
            disableVertexShaderExpand = boolAt("EmuCore/GS/DisableVertexShaderExpand") ?: this.disableVertexShaderExpand,
            useBlitSwapChain = boolAt("EmuCore/GS/UseBlitSwapChain") ?: this.useBlitSwapChain,
            disableShaderCache = boolAt("EmuCore/GS/DisableShaderCache") ?: this.disableShaderCache,
            hwAccurateAlphaTest = boolAt("EmuCore/GS/HWAccurateAlphaTest") ?: this.hwAccurateAlphaTest,
            drawBuffering = boolAt("EmuCore/GS/UserHacks_DrawBuffering") ?: this.drawBuffering,
            spinGpuReadbacks = boolAt("EmuCore/GS/HWSpinGPUForReadbacks") ?: this.spinGpuReadbacks,
            spinCpuReadbacks = boolAt("EmuCore/GS/HWSpinCPUForReadbacks") ?: this.spinCpuReadbacks,
            integerScaling = boolAt("EmuCore/GS/IntegerScaling") ?: this.integerScaling,
            cropLeft = intAt("EmuCore/GS/CropLeft") ?: this.cropLeft,
            cropTop = intAt("EmuCore/GS/CropTop") ?: this.cropTop,
            cropRight = intAt("EmuCore/GS/CropRight") ?: this.cropRight,
            cropBottom = intAt("EmuCore/GS/CropBottom") ?: this.cropBottom,
            dithering = intAt("EmuCore/GS/dithering_ps2") ?: this.dithering,
            vsyncQueueSize = intAt("EmuCore/GS/VsyncQueueSize") ?: this.vsyncQueueSize,
            autoFlushSw = boolAt("EmuCore/GS/autoflush_sw") ?: this.autoFlushSw,
            mipmapSw = boolAt("EmuCore/GS/mipmap") ?: this.mipmapSw,
            swThreads = intAt("EmuCore/GS/extrathreads") ?: this.swThreads,
            swThreadsHeight = intAt("EmuCore/GS/extrathreads_height") ?: this.swThreadsHeight,
            skipDrawStart = intAt("EmuCore/GS/UserHacks_SkipDraw_Start") ?: this.skipDrawStart,
            skipDrawEnd = intAt("EmuCore/GS/UserHacks_SkipDraw_End") ?: this.skipDrawEnd,
            // "UserHacks" is applyTo's derived master (manualUserHacks OR any hack set);
            // recovering it into manualUserHacks is idempotent — the individual hacks below
            // re-derive it when re-applied, and it preserves a master-on-with-no-hacks state.
            manualUserHacks = boolAt("EmuCore/GS/UserHacks") ?: this.manualUserHacks,
            autoFlush = intAt("EmuCore/GS/UserHacks_AutoFlushLevel") ?: this.autoFlush,
            halfPixelOffset = intAt("EmuCore/GS/UserHacks_HalfPixelOffset") ?: this.halfPixelOffset,
            limit24BitDepth = intAt("EmuCore/GS/UserHacks_Limit24BitDepth") ?: this.limit24BitDepth,
            textureInsideRt = intAt("EmuCore/GS/UserHacks_TextureInsideRt") ?: this.textureInsideRt,
            nativeScaling = intAt("EmuCore/GS/UserHacks_native_scaling") ?: this.nativeScaling,
            roundSprite = intAt("EmuCore/GS/UserHacks_round_sprite_offset") ?: this.roundSprite,
            bilinearUpscale = intAt("EmuCore/GS/UserHacks_BilinearHack") ?: this.bilinearUpscale,
            gpuTargetClut = intAt("EmuCore/GS/UserHacks_GPUTargetCLUTMode") ?: this.gpuTargetClut,
            cpuSpriteRenderBw = intAt("EmuCore/GS/UserHacks_CPUSpriteRenderBW") ?: this.cpuSpriteRenderBw,
            cpuSpriteRenderLevel = intAt("EmuCore/GS/UserHacks_CPUSpriteRenderLevel") ?: this.cpuSpriteRenderLevel,
            cpuClutRender = intAt("EmuCore/GS/UserHacks_CPUCLUTRender") ?: this.cpuClutRender,
            alignSprite = boolAt("EmuCore/GS/UserHacks_align_sprite_X") ?: this.alignSprite,
            mergeSprite = boolAt("EmuCore/GS/UserHacks_merge_pp_sprite") ?: this.mergeSprite,
            forceEvenSpritePosition = boolAt("EmuCore/GS/UserHacks_ForceEvenSpritePosition") ?: this.forceEvenSpritePosition,
            unscaledPaletteDraw = boolAt("EmuCore/GS/UserHacks_NativePaletteDraw") ?: this.unscaledPaletteDraw,
            textureOffsetX = intAt("EmuCore/GS/UserHacks_TCOffsetX") ?: this.textureOffsetX,
            textureOffsetY = intAt("EmuCore/GS/UserHacks_TCOffsetY") ?: this.textureOffsetY,
            gpuPaletteConversion = boolAt("EmuCore/GS/paltex") ?: this.gpuPaletteConversion,
            cpuFramebufferConversion = boolAt("EmuCore/GS/UserHacks_CPU_FB_Conversion") ?: this.cpuFramebufferConversion,
            readTargetsWhenClosing = boolAt("EmuCore/GS/UserHacks_ReadTCOnClose") ?: this.readTargetsWhenClosing,
            disableDepthEmulation = boolAt("EmuCore/GS/UserHacks_DisableDepthSupport") ?: this.disableDepthEmulation,
            disablePartialInvalidation = boolAt("EmuCore/GS/UserHacks_DisablePartialInvalidation") ?: this.disablePartialInvalidation,
            disableSafeFeatures = boolAt("EmuCore/GS/UserHacks_Disable_Safe_Features") ?: this.disableSafeFeatures,
            disableRenderFixes = boolAt("EmuCore/GS/UserHacks_DisableRenderFixes") ?: this.disableRenderFixes,
            preloadFrameData = boolAt("EmuCore/GS/preload_frame_with_gs_data") ?: this.preloadFrameData,
            estimateTextureRegion = boolAt("EmuCore/GS/UserHacks_EstimateTextureRegion") ?: this.estimateTextureRegion,
            triFilter = intAt("EmuCore/GS/TriFilter") ?: this.triFilter,
            maxAnisotropy = intAt("EmuCore/GS/MaxAnisotropy") ?: this.maxAnisotropy,
            gpuProfile = when (strAt("EmuCore/GS/AndroidGpuProfileOverride")) {
                "mali" -> 1
                "adreno" -> 2
                "powervr" -> 3
                "xclipse" -> 4
                "auto" -> 0
                else -> this.gpuProfile
            },
        )
    }

    /** Upstream-style per-game export (mirrors PCSX2's FullscreenUI): write only
     *  the keys that differ from [global] into the running game's
     *  gamesettings/<serial>_<CRC>.ini, so the on-disk layer is sparse and
     *  portable (a later global tweak still reaches the game for keys it didn't
     *  override). Reuses applyTo's exact field→key mapping via [emitSink]: the
     *  global pass captures a baseline, the effective pass writes the diff. The
     *  running game already reflects the change live, so the native commit does
     *  not reload — the INI applies as the game layer on the next boot. No-op
     *  when no VM is running. */
    fun writeGameSettingsIni(global: Settings, serial: String? = null) {
        // Baseline: global's persisted keys. applyTo early-returns before the
        // live pokes/commit while emitSink is set, so nothing touches the VM.
        val baseline = HashMap<String, String>()
        emitSink = { section, key, _, value -> baseline["$section$key"] = value }
        try {
            global.applyTo()
        } finally {
            emitSink = null
        }
        // With a running VM the target is the current game (gameIniBeginWrite). With no VM — a
        // per-game Reset done from the library — pass [serial] to locate the file directly; false
        // there means no stale override file exists, so there is nothing to rewrite.
        val began = if (serial == null) NativeApp.gameIniBeginWrite()
                    else NativeApp.gameIniBeginWriteForSerial(serial)
        if (!began) return
        // Effective pass: stream only the keys that differ from the baseline.
        emitSink = { section, key, _, value ->
            if (baseline["$section$key"] != value)
                NativeApp.gameIniPut(section, key, value)
        }
        try {
            applyTo()
        } finally {
            emitSink = null
        }
        NativeApp.gameIniCommitWrite()
    }

    /** Writes every EmuCore/GS key (display + renderer + hardware/upscaling
     *  fixes) into the native BASE settings layer. Pure persistence — no live
     *  pokes, no commit. Shared by [applyTo] (cold start / restart) and
     *  [applyGsLive] (running VM). Keep the key list in sync with
     *  Pcsx2Config::GSOptions::LoadSave. */
    private fun writeGsToNative() {
        val aspectRatioName = when (aspectRatio.coerceIn(0, 8)) {
            0 -> "Stretch"
            2 -> "4:3"
            3 -> "16:9"
            4 -> "10:7"
            5 -> "21:9"
            6 -> "20:9"
            7 -> "19.5:9"
            8 -> "Custom"
            else -> "Auto 4:3/3:2"
        }
        put("EmuCore/GS", "AspectRatio", "string", aspectRatioName)
        val fmvAspectRatioName = when (fmvAspectRatio.coerceIn(0, 8)) {
            1 -> "Auto 4:3/3:2"
            2 -> "4:3"
            3 -> "16:9"
            4 -> "10:7"
            5 -> "21:9"
            6 -> "20:9"
            7 -> "19.5:9"
            8 -> "Custom"
            else -> "Off"
        }
        put("EmuCore/GS", "FMVAspectRatioSwitch", "string", fmvAspectRatioName)
        put("EmuCore/GS", "CustomAspectRatio", "float", customAspectRatio.coerceIn(0.5f, 5.0f).toString())
        put("EmuCore/GS", "deinterlace_mode", "int", deinterlaceMode.coerceIn(0, 9).toString())
        put("EmuCore/GS", "FramerateNTSC", "float", framerateNtsc.toString())
        put("EmuCore/GS", "FrameratePAL", "float", frameratePal.toString())
        put("EmuCore/GS", "hw_mipmap", "bool", hwMipmap.toString())
        put("EmuCore/GS", "accurate_blending_unit", "int", accurateBlendingUnit.toString())
        put("EmuCore/GS", "filter", "int", textureFiltering.toString())
        put("EmuCore/GS", "linear_present_mode", "int", displayBilinear.coerceIn(0, 2).toString())
        put("EmuCore/GS", "texture_preloading", "int", texturePreloading.toString())
        // Upper bound MUST match the highest GSHardwareDownloadMode value (now 5 = Asynchronous).
        // This clamp silently swallowed anything above it, so a new mode would have looked like it
        // simply did nothing — the same failure shape that cost hours on the DEV9 hunt.
        put("EmuCore/GS", "HWDownloadMode", "int", hardwareDownloadMode.coerceIn(0, 5).toString())
        put("EmuCore/GS", "TVShader", "int", tvShader.coerceIn(0, 7).toString())
        put("EmuCore/GS", "ShadeBoost", "bool", shadeBoost.toString())
        put("EmuCore/GS", "ShadeBoost_Brightness", "int", shadeBoostBrightness.coerceIn(1, 100).toString())
        put("EmuCore/GS", "ShadeBoost_Contrast", "int", shadeBoostContrast.coerceIn(1, 100).toString())
        put("EmuCore/GS", "ShadeBoost_Saturation", "int", shadeBoostSaturation.coerceIn(1, 100).toString())
        put("EmuCore/GS", "ShadeBoost_Gamma", "int", shadeBoostGamma.coerceIn(1, 100).toString())
        put("EmuCore/GS", "fxaa", "bool", fxaa.toString())
        // Scaling Mode writes Output Scaling Mode unconditionally, the shader chain only
        // when it is on, so CAS has to go first for the chain to keep the last word.
        put("EmuCore/GS", "CASMode", "int", casMode.coerceIn(0, 4).toString())
        put("EmuCore/GS", "SGSRSharpness", "int", sgsrSharpness.coerceIn(0, 200).toString())
        put("EmuCore/GS", "CASSharpness", "int", casSharpness.coerceIn(0, 100).toString())
        put("EmuCore/GS", "ShaderChainEnabled", "bool", shaderChainEnabled.toString())
        put("EmuCore/GS", "ShaderChainPreset", "string", shaderChainPreset)
        // Parameter overrides, as one opaque JSON blob. Nothing in emucore reads this key —
        // there is no GSConfig field behind it, and the live values reach the renderer via
        // the push below, not through here. It is written so the map survives the same
        // round-trips every other field gets: settings export/import, and the reused-folder
        // recovery that rebuilds prefs from the INI after a fresh install.
        put("EmuCore/GS", "ShaderChainParams", "string", shaderChainParamsToJson(shaderChainParams).toString())
        // The actual live apply. Only the CURRENT preset's values are pushed (the rest are
        // kept for when the user picks those presets again), and only the overrides — a
        // parameter left out keeps what the chain has, which for the freshly built or
        // rebuilt chain this runs against is the author's initial. Resets are handled by
        // the UI's own pushEffective, which sends initials explicitly to a live chain.
        // Skipped under emitSink: an export has no renderer to push to.
        if (emitSink == null)
            ShaderParams.push(shaderChainPreset, shaderChainParams[shaderChainPreset].orEmpty())
        put("EmuCore/GS", "LoadTextureReplacements", "bool", loadTextureReplacements.toString())
        put("EmuCore/GS", "LoadTextureReplacementsAsync", "bool", loadTextureReplacementsAsync.toString())
        put("EmuCore/GS", "PrecacheTextureReplacements", "bool", precacheTextureReplacements.toString())
        put("EmuCore/GS", "DumpReplaceableTextures", "bool", dumpReplaceableTextures.toString())
        put("EmuCore/GS", "OsdShowTextureReplacements", "bool", osdShowTextureReplacements.toString())
        put("EmuCore/GS", "OsdShowFPS", "bool", osdShowFps.toString())
        put("EmuCore/GS", "OsdScale", "int", osdScale.coerceIn(25, 500).toString())
        put("EmuCore/GS", "OsdColor", "int", (osdColor and 0xFFFFFF).toString())
        put("EmuCore/GS", "VsyncEnable", "bool", vsyncEnable.toString())
        put("EmuCore/GS", "OsdShowVPS", "bool", osdShowVps.toString())
        put("EmuCore/GS", "OsdShowSpeed", "bool", osdShowSpeed.toString())
        put("EmuCore/GS", "OsdShowCPU", "bool", osdShowCpu.toString())
        put("EmuCore/GS", "OsdShowGPU", "bool", osdShowGpu.toString())
        put("EmuCore/GS", "OsdShowResolution", "bool", osdShowResolution.toString())
        put("EmuCore/GS", "OsdShowGSStats", "bool", osdShowGsStats.toString())
        put("EmuCore/GS", "OsdShowFrameTimes", "bool", osdShowFrameTimes.toString())
        put("EmuCore/GS", "OsdShowHardwareInfo", "bool", osdShowHardwareInfo.toString())
        put("EmuCore/GS", "OsdMessagesPos", "int", if (osdShowMessages) "1" else "0")
        put("EmuCore/GS", "OsdShowGPUStats", "bool", osdShowGpuStats.toString())
        put("EmuCore/GS", "OsdShowVersion", "bool", osdShowVersion.toString())
        put("EmuCore/GS", "OsdShowSettings", "bool", osdShowSettings.toString())
        put("EmuCore/GS", "OsdShowInputs", "bool", osdShowInputs.toString())
        // Display / PCRTC fixes (not gated by the UserHacks master).
        put("EmuCore/GS", "pcrtc_offsets", "bool", screenOffsets.toString())
        put("EmuCore/GS", "pcrtc_overscan", "bool", showOverscan.toString())
        put("EmuCore/GS", "pcrtc_antiblur", "bool", antiBlur.toString())
        put("EmuCore/GS", "disable_interlace_offset", "bool", disableInterlaceOffset.toString())
        put("EmuCore/GS", "SyncToHostRefreshRate", "bool", syncToHostRefresh.toString())
        put("EmuCore/GS", "DisableFramebufferFetch", "bool", disableFramebufferFetch.toString())
        put("EmuCore/GS", "HWROV", "bool", hwRov.toString())
        put("EmuCore/GS", "HWAA1", "bool", hwAa1.toString())
        put("EmuCore/GS", "EnableAdrenoFramebufferFetch", "bool", adrenoFbFetch.toString())
        put("EmuCore/GS", "CoalesceRenderPasses", "bool", coalesceRenderPasses.toString())
        put("EmuCore/GS", "ForceMaliFramebufferFetch", "bool", forceMaliFbFetch.toString())
        // Parity write (native reads the ARMSX2_ANGLE_EGL_LIBRARY env var set by
        // MainActivityRuntime.applyAngleEnv, not this key) — kept so the config file
        // reflects the toggle.
        put("EmuCore/GS", "AndroidUseAngleOpenGL", "bool", useAngleOpenGL.toString())
        put("EmuCore/GS", "OverrideTextureBarriers", "int", overrideTextureBarriers.coerceIn(-1, 1).toString())
        put("EmuCore/GS", "GSBackThreadMode", "int", gsBackThreadMode.coerceIn(0, 3).toString())
        put("EmuCore/GS", "DisableVertexShaderExpand", "bool", disableVertexShaderExpand.toString())
        put("EmuCore/GS", "UseBlitSwapChain", "bool", useBlitSwapChain.toString())
        put("EmuCore/GS", "DisableShaderCache", "bool", disableShaderCache.toString())
        put("EmuCore/GS", "HWAccurateAlphaTest", "bool", hwAccurateAlphaTest.toString())
        put("EmuCore/GS", "UserHacks_DrawBuffering", "bool", drawBuffering.toString())
        put("EmuCore/GS", "HWSpinGPUForReadbacks", "bool", spinGpuReadbacks.toString())
        put("EmuCore/GS", "HWSpinCPUForReadbacks", "bool", spinCpuReadbacks.toString())
        put("EmuCore/GS", "IntegerScaling", "bool", integerScaling.toString())
        // Display zoom (#383) overrides the manual crops while active: trim every edge by the
        // same fraction so the picture scales up without distortion. Nominal 640x448 native
        // frame; the zoom factor is what matters visually, so an approximate frame size is fine.
        // (1 - 100/Z)/2 is the per-edge fraction that leaves 1/Z of the image visible, centred.
        val zoom = displayZoom.coerceIn(100, 150)
        val zCropX = if (zoom > 100) ((640.0 * (1.0 - 100.0 / zoom)) / 2.0).toInt() else -1
        val zCropY = if (zoom > 100) ((448.0 * (1.0 - 100.0 / zoom)) / 2.0).toInt() else -1
        val effLeft = if (zCropX >= 0) zCropX else cropLeft
        val effRight = if (zCropX >= 0) zCropX else cropRight
        val effTop = if (zCropY >= 0) zCropY else cropTop
        val effBottom = if (zCropY >= 0) zCropY else cropBottom
        put("EmuCore/GS", "CropLeft", "int", effLeft.coerceIn(0, 640).toString())
        put("EmuCore/GS", "CropTop", "int", effTop.coerceIn(0, 640).toString())
        put("EmuCore/GS", "CropRight", "int", effRight.coerceIn(0, 640).toString())
        put("EmuCore/GS", "CropBottom", "int", effBottom.coerceIn(0, 640).toString())
        put("EmuCore/GS", "dithering_ps2", "int", dithering.coerceIn(0, 3).toString())
        put("EmuCore/GS", "VsyncQueueSize", "int", vsyncQueueSize.coerceIn(0, 3).toString())
        put("EmuCore/GS", "autoflush_sw", "bool", autoFlushSw.toString())
        put("EmuCore/GS", "mipmap", "bool", mipmapSw.toString())
        put("EmuCore/GS", "extrathreads", "int", swThreads.coerceIn(0, 10).toString())
        put("EmuCore/GS", "extrathreads_height", "int", swThreadsHeight.coerceIn(0, 8).toString())
        // Skip-draw is a UserHack (gated by the master toggle below).
        put("EmuCore/GS", "UserHacks_SkipDraw_Start", "int", skipDrawStart.coerceAtLeast(0).toString())
        put("EmuCore/GS", "UserHacks_SkipDraw_End", "int", skipDrawEnd.coerceAtLeast(0).toString())
        // Master hardware-fixes toggle. Auto-enables when ANY individual hack is
        // non-default so the user doesn't have to flip it; PCSX2 masks every
        // UserHacks_* key when this is off (GSOptions::MaskUserHacks).
        put("EmuCore/GS", "UserHacks", "bool", anyUserHackEnabled().toString())
        put("EmuCore/GS", "UserHacks_AutoFlushLevel", "int", autoFlush.coerceIn(0, 2).toString())
        put("EmuCore/GS", "UserHacks_HalfPixelOffset", "int", halfPixelOffset.coerceIn(0, 5).toString())
        put("EmuCore/GS", "UserHacks_Limit24BitDepth", "int", limit24BitDepth.coerceIn(0, 2).toString())
        put("EmuCore/GS", "UserHacks_TextureInsideRt", "int", textureInsideRt.coerceIn(0, 2).toString())
        put("EmuCore/GS", "UserHacks_native_scaling", "int", nativeScaling.coerceIn(0, 4).toString())
        put("EmuCore/GS", "UserHacks_round_sprite_offset", "int", roundSprite.coerceIn(0, 2).toString())
        put("EmuCore/GS", "UserHacks_BilinearHack", "int", bilinearUpscale.coerceIn(0, 3).toString())
        put("EmuCore/GS", "UserHacks_GPUTargetCLUTMode", "int", gpuTargetClut.coerceIn(0, 2).toString())
        put("EmuCore/GS", "UserHacks_CPUSpriteRenderBW", "int", cpuSpriteRenderBw.coerceIn(0, 3).toString())
        put("EmuCore/GS", "UserHacks_CPUSpriteRenderLevel", "int", cpuSpriteRenderLevel.coerceIn(0, 5).toString())
        put("EmuCore/GS", "UserHacks_CPUCLUTRender", "int", cpuClutRender.coerceIn(0, 2).toString())
        // Upscaling fixes (parity additions)
        put("EmuCore/GS", "UserHacks_align_sprite_X", "bool", alignSprite.toString())
        put("EmuCore/GS", "UserHacks_merge_pp_sprite", "bool", mergeSprite.toString())
        put("EmuCore/GS", "UserHacks_ForceEvenSpritePosition", "bool", forceEvenSpritePosition.toString())
        put("EmuCore/GS", "UserHacks_NativePaletteDraw", "bool", unscaledPaletteDraw.toString())
        put("EmuCore/GS", "UserHacks_TCOffsetX", "int", textureOffsetX.coerceIn(0, 10000).toString())
        put("EmuCore/GS", "UserHacks_TCOffsetY", "int", textureOffsetY.coerceIn(0, 10000).toString())
        // Hardware fixes (parity additions)
        put("EmuCore/GS", "paltex", "bool", gpuPaletteConversion.toString())
        put("EmuCore/GS", "UserHacks_CPU_FB_Conversion", "bool", cpuFramebufferConversion.toString())
        put("EmuCore/GS", "UserHacks_ReadTCOnClose", "bool", readTargetsWhenClosing.toString())
        put("EmuCore/GS", "UserHacks_DisableDepthSupport", "bool", disableDepthEmulation.toString())
        put("EmuCore/GS", "UserHacks_DisablePartialInvalidation", "bool", disablePartialInvalidation.toString())
        put("EmuCore/GS", "UserHacks_Disable_Safe_Features", "bool", disableSafeFeatures.toString())
        put("EmuCore/GS", "UserHacks_DisableRenderFixes", "bool", disableRenderFixes.toString())
        put("EmuCore/GS", "preload_frame_with_gs_data", "bool", preloadFrameData.toString())
        put("EmuCore/GS", "UserHacks_EstimateTextureRegion", "bool", estimateTextureRegion.toString())
        put("EmuCore/GS", "TriFilter", "int", triFilter.toString())
        put("EmuCore/GS", "MaxAnisotropy", "int", maxAnisotropy.toString())
        val gpuProfileStr = when (gpuProfile) {
            1 -> "mali"
            2 -> "adreno"
            3 -> "powervr"
            4 -> "xclipse"
            else -> "auto"
        }
        put("EmuCore/GS", "AndroidGpuProfileOverride", "string", gpuProfileStr)
    }

    /** True when any hardware/upscaling fix is non-default — used to auto-enable
     *  the UserHacks master so individual hacks aren't silently masked off. */
    private fun anyUserHackEnabled(): Boolean =
        manualUserHacks ||
            autoFlush != 0 || halfPixelOffset != 0 || limit24BitDepth != 0 ||
            textureInsideRt != 0 || nativeScaling != 0 || roundSprite != 0 ||
            bilinearUpscale != 0 || gpuTargetClut != 0 || cpuSpriteRenderBw != 0 ||
            cpuSpriteRenderLevel != 0 || cpuClutRender != 0 ||
            textureOffsetX != 0 || textureOffsetY != 0 ||
            alignSprite || mergeSprite || forceEvenSpritePosition || unscaledPaletteDraw ||
            gpuPaletteConversion || cpuFramebufferConversion || readTargetsWhenClosing ||
            disableDepthEmulation || disablePartialInvalidation || disableSafeFeatures ||
            disableRenderFixes || preloadFrameData || estimateTextureRegion || drawBuffering ||
            skipDrawStart != 0 || skipDrawEnd != 0

    /** Live GS-only apply for a running VM: persist all EmuCore/GS keys, then
     *  reconfigure the GS thread without the heavy CPU/JIT rebuild commitSettings()
     *  does. Lets renderer / hardware-fix / upscaling-fix changes apply instantly
     *  mid-game. */
    fun applyGsLive(): Boolean {
        writeGsToNative()
        return NativeApp.applyGSSettingsLive()
    }

    /** True when any field a live GS reconfigure ([applyGsLive]) can pick up
     *  differs from [other]. Lets the in-game delta path skip the GS thread
     *  park when only non-GS settings (audio, frame limit, …) changed.
     *  Excludes display aspect (its own live setter) and gpuProfile (device-init
     *  only — needs a renderer restart). */
    // NOTE: FramerateNTSC/PAL are intentionally NOT here — the generic GS live
    // reconfigure (applyGSSettingsLive) doesn't recompute the vsync target. They
    // get their OWN live path instead: applySafeLiveDelta routes a framerate change
    // to LiveGsApplyQueue.applyFramerate → NativeApp.applyFramerateLive, which parks
    // the VM and recomputes vsync. Keeping them out of here avoids a redundant
    // (and park-free, thus ineffective) GS reconfigure for a framerate-only edit.
    fun gsDiffersFrom(other: Settings): Boolean =
        deinterlaceMode != other.deinterlaceMode ||
            textureFiltering != other.textureFiltering ||
            displayBilinear != other.displayBilinear ||
            texturePreloading != other.texturePreloading ||
            hardwareDownloadMode != other.hardwareDownloadMode ||
            tvShader != other.tvShader ||
            shadeBoost != other.shadeBoost ||
            shadeBoostBrightness != other.shadeBoostBrightness ||
            shadeBoostContrast != other.shadeBoostContrast ||
            shadeBoostSaturation != other.shadeBoostSaturation ||
            shadeBoostGamma != other.shadeBoostGamma ||
            fxaa != other.fxaa ||
            casMode != other.casMode ||
            sgsrSharpness != other.sgsrSharpness ||
            casSharpness != other.casSharpness ||
            accurateBlendingUnit != other.accurateBlendingUnit ||
            hwMipmap != other.hwMipmap ||
            triFilter != other.triFilter ||
            maxAnisotropy != other.maxAnisotropy ||
            manualUserHacks != other.manualUserHacks ||
            autoFlush != other.autoFlush ||
            halfPixelOffset != other.halfPixelOffset ||
            limit24BitDepth != other.limit24BitDepth ||
            textureInsideRt != other.textureInsideRt ||
            nativeScaling != other.nativeScaling ||
            roundSprite != other.roundSprite ||
            bilinearUpscale != other.bilinearUpscale ||
            gpuTargetClut != other.gpuTargetClut ||
            cpuSpriteRenderBw != other.cpuSpriteRenderBw ||
            cpuSpriteRenderLevel != other.cpuSpriteRenderLevel ||
            cpuClutRender != other.cpuClutRender ||
            alignSprite != other.alignSprite ||
            mergeSprite != other.mergeSprite ||
            forceEvenSpritePosition != other.forceEvenSpritePosition ||
            unscaledPaletteDraw != other.unscaledPaletteDraw ||
            textureOffsetX != other.textureOffsetX ||
            textureOffsetY != other.textureOffsetY ||
            gpuPaletteConversion != other.gpuPaletteConversion ||
            cpuFramebufferConversion != other.cpuFramebufferConversion ||
            readTargetsWhenClosing != other.readTargetsWhenClosing ||
            disableDepthEmulation != other.disableDepthEmulation ||
            disablePartialInvalidation != other.disablePartialInvalidation ||
            disableSafeFeatures != other.disableSafeFeatures ||
            disableRenderFixes != other.disableRenderFixes ||
            preloadFrameData != other.preloadFrameData ||
            estimateTextureRegion != other.estimateTextureRegion ||
            hwAccurateAlphaTest != other.hwAccurateAlphaTest ||
            drawBuffering != other.drawBuffering ||
            // Texture-replacement toggles: without these here the in-game "Load Texture
            // Packs" switch only wrote the base layer (setSetting) and never fired the
            // live GS reconfigure, so a just-imported pack didn't appear until the next
            // game boot. Including them routes through applyGSSettingsLive → GSUpdateConfig
            // → GSTextureReplacements reload/purge, so the pack loads immediately.
            loadTextureReplacements != other.loadTextureReplacements ||
            loadTextureReplacementsAsync != other.loadTextureReplacementsAsync ||
            precacheTextureReplacements != other.precacheTextureReplacements ||
            dumpReplaceableTextures != other.dumpReplaceableTextures ||
            osdShowTextureReplacements != other.osdShowTextureReplacements

    fun toJson(): JSONObject = JSONObject().apply {
        put("eeCycleRate", eeCycleRate)
        put("ps3PpuDecoder", ps3.ppuDecoder)
        put("ps3SpuDecoder", ps3.spuDecoder)
        put("ps3SpuBlockSize", ps3.spuBlockSize)
        put("ps3PpuThreads", ps3.ppuThreads)
        put("ps3LlvmThreads", ps3.llvmThreads)
        put("ps3PreferredSpuThreads", ps3.preferredSpuThreads)
        put("ps3MaxSpursThreads", ps3.maxSpursThreads)
        put("ps3SpuLoopDetection", ps3.spuLoopDetection)
        put("ps3SpuCache", ps3.spuCache)
        put("ps3LlvmPrecompile", ps3.llvmPrecompile)
        put("ps3AccurateSpuDma", ps3.accurateSpuDma)
        put("ps3SavestateCompatibleMode", ps3.savestateCompatibleMode)
        put("ps3ClocksScale", ps3.clocksScale)
        put("ps3ResolutionScale", ps3.resolutionScale)
        put("ps3MsaaMode", ps3.msaaMode)
        put("ps3AudioCubebBackend", ps3.audioCubebBackend)
        put("ps3ShaderMode", ps3.shaderMode)
        put("ps3FrameGeneration", ps3.frameGeneration)
        put("ps3FrameGenPerformance", ps3.frameGenPerformance)
        put("ps3FrameGenFlowScale", ps3.frameGenFlowScale)
        put("ps3FrameGenTargetRate", ps3.frameGenTargetRate)
        put("ps3WriteColorBuffers", ps3.writeColorBuffers)
        put("ps3GpuTurbo", ps3.gpuTurbo)
        put("ps3SilenceAllLogs", ps3.silenceAllLogs)
        put("ps3WriteDepthBuffer", ps3.writeDepthBuffer)
        put("ps3ReadColorBuffers", ps3.readColorBuffers)
        put("ps3ReadDepthBuffer", ps3.readDepthBuffer)
        put("ps3StrictRendering", ps3.strictRendering)
        put("ps3MultithreadedRsx", ps3.multithreadedRsx)
        put("ps3DisableZcull", ps3.disableZcull)
        put("ps3RelaxedZcull", ps3.relaxedZcull)
        put("ps3GpuTextureScaling", ps3.gpuTextureScaling)
        put("ps3ForceCpuBlit", ps3.forceCpuBlit)
        put("ps3ShaderCompThreads", ps3.shaderCompThreads)
        put("ps3TextureLodBias", ps3.textureLodBias)
        put("ps3VramLimitMb", ps3.vramLimitMb)
        put("ps3AsyncTexStream", ps3.asyncTexStream)
        put("ps3AudioFormat", ps3.audioFormat)
        put("ps3AudioChannels", ps3.audioChannels)
        put("ps3AudioTimeStretch", ps3.audioTimeStretch)
        put("ps3AudioBuffering", ps3.audioBuffering)
        put("ps3AudioBufferMs", ps3.audioBufferMs)
        put("ps3NetEnabled", ps3.netEnabled)
        put("ps3PsnStatus", ps3.psnStatus)
        put("ps3UpnpEnabled", ps3.upnpEnabled)
        put("ps3IpAddress", ps3.ipAddress)
        put("ps3BindAddress", ps3.bindAddress)
        put("ps3DnsAddress", ps3.dnsAddress)
        put("ps3IpSwapList", ps3.ipSwapList)
        put("ps3DeriveMacFromPsid", ps3.deriveMacFromPsid)
        put("ps3PsnCountry", ps3.psnCountry)
        put("ps3ClansEnabled", ps3.clansEnabled)
        put("ps3EnterButtonAssign", ps3.enterButtonAssign)
        put("ps3ConsoleLanguage", ps3.consoleLanguage)
        put("ps3ConsoleRegion", ps3.consoleRegion)
        put("ps3KeyboardType", ps3.keyboardType)
        put("ps3DateFormat", ps3.dateFormat)
        put("ps3TimeFormat", ps3.timeFormat)
        put("ps3SpuXFloat", ps3.spuXFloat)
        put("ps3AccurateSpuRsv", ps3.accurateSpuRsv)
        put("ps3AccurateCacheLine", ps3.accurateCacheLine)
        put("ps3AccurateRsxRsv", ps3.accurateRsxRsv)
        put("ps3PpuRsvPriority", ps3.ppuRsvPriority)
        put("ps3SpuVerification", ps3.spuVerification)
        put("ps3PreciseSpuVerification", ps3.preciseSpuVerification)
        put("ps3PpuNanHandling", ps3.ppuNanHandling)
        put("ps3AccurateDfma", ps3.accurateDfma)
        put("ps3SetDazFtz", ps3.setDazFtz)
        // RPCS3 performance-overlay fields. These were declared on Ps3Settings and wired
        // into OverlayTab but never added to ANY of the four serialisation paths, so every
        // one of them lived only in memory: changing "Performance Overlay" or "Detail Level"
        // worked until SettingsViewModel.load() re-read the store on the next screen entry,
        // which snapped them back to the defaults. Reported as "anything I change on this
        // settings tab just reverts to default".
        put("ps3DisplayAspect", ps3.displayAspect)
        put("ps3OverlayEnabled", ps3.overlayEnabled)
        put("ps3OverlayDetail", ps3.overlayDetail)
        put("ps3OverlayPosition", ps3.overlayPosition)
        put("ps3OverlayFontSize", ps3.overlayFontSize)
        put("ps3OverlayOpacity", ps3.overlayOpacity)
        put("ps3OverlayFramerateGraph", ps3.overlayFramerateGraph)
        put("ps3OverlayFrametimeGraph", ps3.overlayFrametimeGraph)
        put("ps3OverlayBodyColor", ps3.overlayBodyColor)
        put("ps3OverlayBodyBg", ps3.overlayBodyBg)
        put("ps3OverlayTitleColor", ps3.overlayTitleColor)
        put("ps3OverlayTitleBg", ps3.overlayTitleBg)
        put("ps3HleLwmutex", ps3.hleLwmutex)
        put("ps3SleepTimers", ps3.sleepTimers)
        put("ps3DebugConsoleMode", ps3.debugConsoleMode)
        put("ps3Resolution", ps3.resolution)
        put("ps3AnisoFilter", ps3.anisoFilter)
        put("ps3AudioRenderer", ps3.audioRenderer)
        put("eeCycleSkip", eeCycleSkip)
        put("eeClampMode", eeClampMode)
        put("vuClampMode", vuClampMode)
        put("mtvu", mtvu)
        put("vu1Instant", vu1Instant)
        put("vuFlagHack", vuFlagHack)
        put("fastCDVD", fastCDVD)
        put("intcStat", intcStat)
        put("waitLoop", waitLoop)
        put("vuNeonFusions", vuNeonFusions)
        put("vuDeferredWrites", vuDeferredWrites)
        put("vuSkipStallSim", vuSkipStallSim)
        put("frameLimitEnable", frameLimitEnable)
        put("nominalSpeedPercent", nominalSpeedPercent)
        put("fpsLimit", fpsLimit)
        put("frameSkip", frameSkip)
        put("audioVolume", audioVolume)
        put("audioMuted", audioMuted)
        put("audioSwapChannels", audioSwapChannels)
        put("audioTimeStretch", audioTimeStretch)
        put("audioBufferMs", audioBufferMs)
        put("audioOutputLatencyMs", audioOutputLatencyMs)
        put("audioFastForwardVolume", audioFastForwardVolume)
        put("spu2NeonReverb", spu2NeonReverb)
        put("audioOpenSLES", audioOpenSLES)
        put("spu2LightweightMix", spu2LightweightMix)
        put("renderer", renderer)
        put("upscaleFloat", upscaleFloat.toDouble())
        put("customDriverId", customDriverId)
        put("orientation", orientation)
        put("portraitRenderTop", portraitRenderTop)
        put("landscapeRenderTop", landscapeRenderTop)
        put("autoProgressiveScan", autoProgressiveScan)
        put("affinityMode", affinityMode)
        put("framerateNtsc", framerateNtsc.toDouble())
        put("frameratePal", frameratePal.toDouble())
        put("enablePatches", enablePatches)
        put("enableCheats", enableCheats)
        put("enableWideScreenPatches", enableWideScreenPatches)
        put("enableNoInterlacingPatches", enableNoInterlacingPatches)
        put("enableFastBoot", enableFastBoot)
        put("hostFs", hostFs)
        put("enableGameFixes", enableGameFixes)
        put("gamefixSoftwareRendererFmv", gamefixSoftwareRendererFmv)
        put("gamefixSkipMpeg", gamefixSkipMpeg)
        put("gamefixEETiming", gamefixEETiming)
        put("gamefixInstantDma", gamefixInstantDma)
        put("gamefixBlitInternalFps", gamefixBlitInternalFps)
        put("gamefixFpuMul", gamefixFpuMul)
        put("gamefixOphFlag", gamefixOphFlag)
        put("gamefixGifFifo", gamefixGifFifo)
        put("gamefixDmaBusy", gamefixDmaBusy)
        put("gamefixVif1Stall", gamefixVif1Stall)
        put("gamefixIbit", gamefixIbit)
        put("gamefixFullVu0Sync", gamefixFullVu0Sync)
        put("gamefixVuAddSub", gamefixVuAddSub)
        put("gamefixVuOverflow", gamefixVuOverflow)
        put("gamefixXgkick", gamefixXgkick)
        put("gamefixGoemonTlb", gamefixGoemonTlb)
        put("gamefixVuSync", gamefixVuSync)
        put("skipDuplicateFrames", skipDuplicateFrames)
        put("eeFpuRoundMode", eeFpuRoundMode)
        put("vu0RoundMode", vu0RoundMode)
        put("vu1RoundMode", vu1RoundMode)
        put("screenOffsets", screenOffsets)
        put("showOverscan", showOverscan)
        put("antiBlur", antiBlur)
        put("disableInterlaceOffset", disableInterlaceOffset)
        put("syncToHostRefresh", syncToHostRefresh)
        put("disableFramebufferFetch", disableFramebufferFetch)
        put("hwRov", hwRov)
        put("hwAa1", hwAa1)
        put("adrenoFbFetch", adrenoFbFetch)
        put("coalesceRenderPasses", coalesceRenderPasses)
        put("forceMaliFbFetch", forceMaliFbFetch)
        put("useAngleOpenGL", useAngleOpenGL)
        put("overrideTextureBarriers", overrideTextureBarriers)
        put("gsBackThreadMode", gsBackThreadMode)
        put("disableVertexShaderExpand", disableVertexShaderExpand)
        put("useBlitSwapChain", useBlitSwapChain)
        put("disableShaderCache", disableShaderCache)
        put("hwAccurateAlphaTest", hwAccurateAlphaTest)
        put("skipDrawStart", skipDrawStart)
        put("skipDrawEnd", skipDrawEnd)
        put("spinGpuReadbacks", spinGpuReadbacks)
        put("spinCpuReadbacks", spinCpuReadbacks)
        put("integerScaling", integerScaling)
        put("cropLeft", cropLeft)
        put("displayZoom", displayZoom)
        put("cropTop", cropTop)
        put("cropRight", cropRight)
        put("cropBottom", cropBottom)
        put("dithering", dithering)
        put("vsyncQueueSize", vsyncQueueSize)
        put("hwScaler", hwScaler)
        put("displayFitMode", displayFitMode)
        put("screenResOverride", screenResOverride)
        put("autoFlushSw", autoFlushSw)
        put("mipmapSw", mipmapSw)
        put("swThreads", swThreads)
        put("swThreadsHeight", swThreadsHeight)
        put("aspectRatio", aspectRatio)
        put("fmvAspectRatio", fmvAspectRatio)
        put("customAspectRatio", customAspectRatio.toDouble())
        put("deinterlaceMode", deinterlaceMode)
        put("dev9EthEnable", dev9EthEnable)
        put("dev9EthApi", dev9EthApi)
        put("localLinkHost", localLinkHost)
        put("localLinkAddress", localLinkAddress)
        put("localLinkPort", localLinkPort)
        put("localLinkPeerId", localLinkPeerId)
        put("localLinkRoomCode", localLinkRoomCode)
        put("dev9EthDevice", dev9EthDevice)
        put("dev9EthLogDhcp", dev9EthLogDhcp)
        put("dev9EthLogDns", dev9EthLogDns)
        put("dev9InterceptDhcp", dev9InterceptDhcp)
        put("dev9Ps2Ip", dev9Ps2Ip)
        put("dev9Mask", dev9Mask)
        put("dev9Gateway", dev9Gateway)
        put("dev9Dns1", dev9Dns1)
        put("dev9Dns2", dev9Dns2)
        put("dev9AutoMask", dev9AutoMask)
        put("dev9AutoGateway", dev9AutoGateway)
        put("dev9ModeDns1", dev9ModeDns1)
        put("dev9ModeDns2", dev9ModeDns2)
        put("dev9EthHosts", JSONArray().apply {
            dev9EthHosts.forEach { h ->
                put(JSONObject().apply {
                    put("url", h.url)
                    put("ip", h.ip)
                    put("enabled", h.enabled)
                })
            }
        })
        put("dev9HddEnable", dev9HddEnable)
        put("dev9HddFile", dev9HddFile)
        put("memoryCardSlot1Enabled", memoryCardSlot1Enabled)
        put("memoryCardSlot1Filename", memoryCardSlot1Filename)
        put("biosFilename", biosFilename)
        put("memoryCardSlot2Enabled", memoryCardSlot2Enabled)
        put("memoryCardSlot2Filename", memoryCardSlot2Filename)
        put("usbKeyboard", usbKeyboard)
        put("recEE", recEE)
        put("recIOP", recIOP)
        put("recVU0", recVU0)
        put("recVU1", recVU1)
        put("enableFastmem", enableFastmem)
        put("vu1InlineFmacStall", vu1InlineFmacStall)
        put("vu1CrossBlockPState", vu1CrossBlockPState)
        put("vu1InlineDrainTestPipes", vu1InlineDrainTestPipes)
        put("vu1FmacInstanceRouting", vu1FmacInstanceRouting)
        put("hwMipmap", hwMipmap)
        put("accurateBlendingUnit", accurateBlendingUnit)
        put("textureFiltering", textureFiltering)
        put("displayBilinear", displayBilinear)
        put("texturePreloading", texturePreloading)
        put("hardwareDownloadMode", hardwareDownloadMode)
        put("tvShader", tvShader)
        put("shadeBoost", shadeBoost)
        put("shadeBoostBrightness", shadeBoostBrightness)
        put("shadeBoostContrast", shadeBoostContrast)
        put("shadeBoostSaturation", shadeBoostSaturation)
        put("shadeBoostGamma", shadeBoostGamma)
        put("fxaa", fxaa)
        put("shaderChainEnabled", shaderChainEnabled)
        put("shaderChainPreset", shaderChainPreset)
        put("shaderChainParams", shaderChainParamsToJson(shaderChainParams))
        put("casMode", casMode)
        put("sgsrSharpness", sgsrSharpness)
        put("casSharpness", casSharpness)
        put("loadTextureReplacements", loadTextureReplacements)
        put("loadTextureReplacementsAsync", loadTextureReplacementsAsync)
        put("precacheTextureReplacements", precacheTextureReplacements)
        put("dumpReplaceableTextures", dumpReplaceableTextures)
        put("osdShowTextureReplacements", osdShowTextureReplacements)
        put("osdShowFps", osdShowFps)
        put("osdScale", osdScale)
        put("osdColor", osdColor)
        put("vsyncEnable", vsyncEnable)
        put("osdShowVps", osdShowVps)
        put("osdShowSpeed", osdShowSpeed)
        put("osdShowCpu", osdShowCpu)
        put("osdShowGpu", osdShowGpu)
        put("osdShowResolution", osdShowResolution)
        put("osdShowGsStats", osdShowGsStats)
        put("osdShowFrameTimes", osdShowFrameTimes)
        put("osdShowHardwareInfo", osdShowHardwareInfo)
        put("osdShowMessages", osdShowMessages)
        put("osdShowGpuStats", osdShowGpuStats)
        put("osdShowVersion", osdShowVersion)
        put("osdShowSettings", osdShowSettings)
        put("osdShowInputs", osdShowInputs)
        put("autoFlush", autoFlush)
        put("halfPixelOffset", halfPixelOffset)
        put("limit24BitDepth", limit24BitDepth)
        put("manualUserHacks", manualUserHacks)
        put("textureInsideRt", textureInsideRt)
        put("nativeScaling", nativeScaling)
        put("roundSprite", roundSprite)
        put("bilinearUpscale", bilinearUpscale)
        put("gpuTargetClut", gpuTargetClut)
        put("cpuSpriteRenderBw", cpuSpriteRenderBw)
        put("cpuSpriteRenderLevel", cpuSpriteRenderLevel)
        put("alignSprite", alignSprite)
        put("mergeSprite", mergeSprite)
        put("forceEvenSpritePosition", forceEvenSpritePosition)
        put("unscaledPaletteDraw", unscaledPaletteDraw)
        put("textureOffsetX", textureOffsetX)
        put("textureOffsetY", textureOffsetY)
        put("gpuPaletteConversion", gpuPaletteConversion)
        put("cpuFramebufferConversion", cpuFramebufferConversion)
        put("readTargetsWhenClosing", readTargetsWhenClosing)
        put("disableDepthEmulation", disableDepthEmulation)
        put("disablePartialInvalidation", disablePartialInvalidation)
        put("disableSafeFeatures", disableSafeFeatures)
        put("disableRenderFixes", disableRenderFixes)
        put("preloadFrameData", preloadFrameData)
        put("estimateTextureRegion", estimateTextureRegion)
        put("drawBuffering", drawBuffering)
        put("cpuClutRender", cpuClutRender)
        put("triFilter", triFilter)
        put("maxAnisotropy", maxAnisotropy)
        put("gpuProfile", gpuProfile)
    }

    companion object {
        /** When non-null, [put] routes persisted-key emits here instead of the
         *  native base layer. Set transiently by [writeGameSettingsIni] to
         *  capture the key set for the sparse per-game INI export without
         *  touching the base layer or re-poking the running VM. */
        @JvmStatic
        internal var emitSink: ((String, String, String, String) -> Unit)? = null

        /** One-tap "Low-End" performance snapshot applied on top of [base].
         *  Only cheap, safe-for-most levers that already exist as fields:
         *    - accurate_blending_unit = Minimum (0)   — cheapest blend path
         *    - internal resolution   = 1x (native)     — biggest GPU win
         *    - hw mipmap off, GPU palette conversion off — drop optional GPU work
         *    - texture preloading    = Partial (1)      — lower upload stalls
         *    - HW ROV off                                — never a win on tilers
         *    - EE cycle skip         = 1                 — mild CPU headroom
         *    - MTVU                   = device-aware      — only when >= 6 cores
         *  [mtvu] is passed in (from [com.armsx2.DeviceTier.mtvuDefault]) rather
         *  than read here so config/ stays free of Android context deps.
         *  NOTE: intentionally does NOT touch CAS — there is no CAS Settings
         *  field wired in this build. */
        fun lowEndPreset(base: Settings, mtvu: Boolean): Settings = base.copy(
            accurateBlendingUnit = 0,   // Minimum
            upscaleFloat = 1.0f,        // native resolution
            hwMipmap = false,           // mipmap off
            gpuPaletteConversion = false,
            texturePreloading = 1,      // Partial
            hwRov = false,              // ROV off
            eeCycleSkip = 1,
            mtvu = mtvu,
        )

        /** Lenient parse — missing keys fall back to defaults so old saved
         *  blobs survive when new fields are added. */
        fun fromJson(json: JSONObject): Settings {
            val def = Settings()
            return Settings(
                eeCycleRate = json.optInt("eeCycleRate", def.eeCycleRate),
                ps3 = Ps3Settings(
                    ppuDecoder = json.optInt("ps3PpuDecoder", def.ps3.ppuDecoder),
                    spuDecoder = json.optInt("ps3SpuDecoder", def.ps3.spuDecoder),
                    spuBlockSize = json.optInt("ps3SpuBlockSize", def.ps3.spuBlockSize),
                    ppuThreads = json.optInt("ps3PpuThreads", def.ps3.ppuThreads),
                    llvmThreads = json.optInt("ps3LlvmThreads", def.ps3.llvmThreads),
                    preferredSpuThreads = json.optInt("ps3PreferredSpuThreads", def.ps3.preferredSpuThreads),
                    maxSpursThreads = json.optInt("ps3MaxSpursThreads", def.ps3.maxSpursThreads),
                    spuLoopDetection = json.optBoolean("ps3SpuLoopDetection", def.ps3.spuLoopDetection),
                    spuCache = json.optBoolean("ps3SpuCache", def.ps3.spuCache),
                    llvmPrecompile = json.optBoolean("ps3LlvmPrecompile", def.ps3.llvmPrecompile),
                    accurateSpuDma = json.optBoolean("ps3AccurateSpuDma", def.ps3.accurateSpuDma),
                    savestateCompatibleMode = json.optBoolean("ps3SavestateCompatibleMode", def.ps3.savestateCompatibleMode),
                    clocksScale = json.optInt("ps3ClocksScale", def.ps3.clocksScale),
                    resolutionScale = json.optInt("ps3ResolutionScale", def.ps3.resolutionScale),
                    msaaMode = json.optInt("ps3MsaaMode", def.ps3.msaaMode),
                    audioCubebBackend = json.optInt("ps3AudioCubebBackend", def.ps3.audioCubebBackend),
                    shaderMode = json.optInt("ps3ShaderMode", def.ps3.shaderMode),
                    frameGeneration = json.optInt("ps3FrameGeneration", def.ps3.frameGeneration),
                    frameGenPerformance = json.optBoolean("ps3FrameGenPerformance", def.ps3.frameGenPerformance),
                    frameGenFlowScale = json.optInt("ps3FrameGenFlowScale", def.ps3.frameGenFlowScale),
                    frameGenTargetRate = json.optInt("ps3FrameGenTargetRate", def.ps3.frameGenTargetRate),
                    writeColorBuffers = json.optBoolean("ps3WriteColorBuffers", def.ps3.writeColorBuffers),
                    gpuTurbo = json.optBoolean("ps3GpuTurbo", def.ps3.gpuTurbo),
                    silenceAllLogs = json.optBoolean("ps3SilenceAllLogs", def.ps3.silenceAllLogs),
                    writeDepthBuffer = json.optBoolean("ps3WriteDepthBuffer", def.ps3.writeDepthBuffer),
                    readColorBuffers = json.optBoolean("ps3ReadColorBuffers", def.ps3.readColorBuffers),
                    readDepthBuffer = json.optBoolean("ps3ReadDepthBuffer", def.ps3.readDepthBuffer),
                    strictRendering = json.optBoolean("ps3StrictRendering", def.ps3.strictRendering),
                    multithreadedRsx = json.optBoolean("ps3MultithreadedRsx", def.ps3.multithreadedRsx),
                    disableZcull = json.optBoolean("ps3DisableZcull", def.ps3.disableZcull),
                    relaxedZcull = json.optBoolean("ps3RelaxedZcull", def.ps3.relaxedZcull),
                    gpuTextureScaling = json.optBoolean("ps3GpuTextureScaling", def.ps3.gpuTextureScaling),
                    forceCpuBlit = json.optBoolean("ps3ForceCpuBlit", def.ps3.forceCpuBlit),
                    shaderCompThreads = json.optInt("ps3ShaderCompThreads", def.ps3.shaderCompThreads),
                    textureLodBias = json.optInt("ps3TextureLodBias", def.ps3.textureLodBias),
                    vramLimitMb = json.optInt("ps3VramLimitMb", def.ps3.vramLimitMb),
                    asyncTexStream = json.optBoolean("ps3AsyncTexStream", def.ps3.asyncTexStream),
                    audioFormat = json.optInt("ps3AudioFormat", def.ps3.audioFormat),
                    audioChannels = json.optInt("ps3AudioChannels", def.ps3.audioChannels),
                    audioTimeStretch = json.optBoolean("ps3AudioTimeStretch", def.ps3.audioTimeStretch),
                    audioBuffering = json.optBoolean("ps3AudioBuffering", def.ps3.audioBuffering),
                    audioBufferMs = json.optInt("ps3AudioBufferMs", def.ps3.audioBufferMs),
                    netEnabled = json.optBoolean("ps3NetEnabled", def.ps3.netEnabled),
                    // optInt with a Boolean fallback for installs written before this was a
                    // tri-state: a stored `true` reads back as 1 (Simulated), which is what it
                    // meant.
                    psnStatus = if (json.opt("ps3PsnStatus") is Boolean)
                        (if (json.optBoolean("ps3PsnStatus")) 1 else 0)
                    else json.optInt("ps3PsnStatus", def.ps3.psnStatus),
                    upnpEnabled = json.optBoolean("ps3UpnpEnabled", def.ps3.upnpEnabled),
                    ipAddress = json.optString("ps3IpAddress", def.ps3.ipAddress),
                    bindAddress = json.optString("ps3BindAddress", def.ps3.bindAddress),
                    dnsAddress = json.optString("ps3DnsAddress", def.ps3.dnsAddress),
                    ipSwapList = json.optString("ps3IpSwapList", def.ps3.ipSwapList),
                    deriveMacFromPsid = json.optBoolean("ps3DeriveMacFromPsid", def.ps3.deriveMacFromPsid),
                    psnCountry = json.optString("ps3PsnCountry", def.ps3.psnCountry),
                    clansEnabled = json.optBoolean("ps3ClansEnabled", def.ps3.clansEnabled),
                    enterButtonAssign = json.optInt("ps3EnterButtonAssign", def.ps3.enterButtonAssign),
                    consoleLanguage = json.optInt("ps3ConsoleLanguage", def.ps3.consoleLanguage),
                    consoleRegion = json.optInt("ps3ConsoleRegion", def.ps3.consoleRegion),
                    keyboardType = json.optInt("ps3KeyboardType", def.ps3.keyboardType),
                    dateFormat = json.optInt("ps3DateFormat", def.ps3.dateFormat),
                    timeFormat = json.optInt("ps3TimeFormat", def.ps3.timeFormat),
                    spuXFloat = json.optInt("ps3SpuXFloat", def.ps3.spuXFloat),
                    accurateSpuRsv = json.optBoolean("ps3AccurateSpuRsv", def.ps3.accurateSpuRsv),
                    accurateCacheLine = json.optBoolean("ps3AccurateCacheLine", def.ps3.accurateCacheLine),
                    accurateRsxRsv = json.optBoolean("ps3AccurateRsxRsv", def.ps3.accurateRsxRsv),
                    ppuRsvPriority = json.optBoolean("ps3PpuRsvPriority", def.ps3.ppuRsvPriority),
                    spuVerification = json.optBoolean("ps3SpuVerification", def.ps3.spuVerification),
                    preciseSpuVerification = json.optBoolean("ps3PreciseSpuVerification", def.ps3.preciseSpuVerification),
                    ppuNanHandling = json.optBoolean("ps3PpuNanHandling", def.ps3.ppuNanHandling),
                    accurateDfma = json.optBoolean("ps3AccurateDfma", def.ps3.accurateDfma),
                    setDazFtz = json.optBoolean("ps3SetDazFtz", def.ps3.setDazFtz),
                    displayAspect = json.optInt("ps3DisplayAspect", def.ps3.displayAspect),
                    overlayEnabled = json.optBoolean("ps3OverlayEnabled", def.ps3.overlayEnabled),
                    overlayDetail = json.optInt("ps3OverlayDetail", def.ps3.overlayDetail),
                    overlayPosition = json.optInt("ps3OverlayPosition", def.ps3.overlayPosition),
                    overlayFontSize = json.optInt("ps3OverlayFontSize", def.ps3.overlayFontSize),
                    overlayOpacity = json.optInt("ps3OverlayOpacity", def.ps3.overlayOpacity),
                    overlayFramerateGraph = json.optBoolean("ps3OverlayFramerateGraph", def.ps3.overlayFramerateGraph),
                    overlayFrametimeGraph = json.optBoolean("ps3OverlayFrametimeGraph", def.ps3.overlayFrametimeGraph),
                    overlayBodyColor = json.optInt("ps3OverlayBodyColor", def.ps3.overlayBodyColor),
                    overlayBodyBg = json.optInt("ps3OverlayBodyBg", def.ps3.overlayBodyBg),
                    overlayTitleColor = json.optInt("ps3OverlayTitleColor", def.ps3.overlayTitleColor),
                    overlayTitleBg = json.optInt("ps3OverlayTitleBg", def.ps3.overlayTitleBg),
                    hleLwmutex = json.optBoolean("ps3HleLwmutex", def.ps3.hleLwmutex),
                    sleepTimers = json.optInt("ps3SleepTimers", def.ps3.sleepTimers),
                    debugConsoleMode = json.optBoolean("ps3DebugConsoleMode", def.ps3.debugConsoleMode),
                    resolution = json.optInt("ps3Resolution", def.ps3.resolution),
                    anisoFilter = json.optInt("ps3AnisoFilter", def.ps3.anisoFilter),
                    audioRenderer = json.optInt("ps3AudioRenderer", def.ps3.audioRenderer),
                ),
                eeCycleSkip = json.optInt("eeCycleSkip", def.eeCycleSkip),
                eeClampMode = json.optInt("eeClampMode", def.eeClampMode),
                vuClampMode = json.optInt("vuClampMode", def.vuClampMode),
                mtvu = json.optBoolean("mtvu", def.mtvu),
                vu1Instant = json.optBoolean("vu1Instant", def.vu1Instant),
                vuFlagHack = json.optBoolean("vuFlagHack", def.vuFlagHack),
                fastCDVD = json.optBoolean("fastCDVD", def.fastCDVD),
                intcStat = json.optBoolean("intcStat", def.intcStat),
                waitLoop = json.optBoolean("waitLoop", def.waitLoop),
                vuNeonFusions = json.optBoolean("vuNeonFusions", def.vuNeonFusions),
                vuDeferredWrites = json.optBoolean("vuDeferredWrites", def.vuDeferredWrites),
                vuSkipStallSim = json.optBoolean("vuSkipStallSim", def.vuSkipStallSim),
                frameLimitEnable = json.optBoolean("frameLimitEnable", def.frameLimitEnable),
                nominalSpeedPercent = json.optInt("nominalSpeedPercent", def.nominalSpeedPercent),
                fpsLimit = json.optInt("fpsLimit", def.fpsLimit),
                frameSkip = json.optInt("frameSkip", def.frameSkip),
                audioVolume = json.optInt("audioVolume", def.audioVolume),
                audioMuted = json.optBoolean("audioMuted", def.audioMuted),
                audioSwapChannels = json.optBoolean("audioSwapChannels", def.audioSwapChannels),
                audioTimeStretch = json.optBoolean("audioTimeStretch", def.audioTimeStretch),
                audioBufferMs = json.optInt("audioBufferMs", def.audioBufferMs),
                audioOutputLatencyMs = json.optInt("audioOutputLatencyMs", def.audioOutputLatencyMs),
                audioFastForwardVolume = json.optInt("audioFastForwardVolume", def.audioFastForwardVolume),
                spu2NeonReverb = json.optBoolean("spu2NeonReverb", def.spu2NeonReverb),
                audioOpenSLES = json.optBoolean("audioOpenSLES", def.audioOpenSLES),
                spu2LightweightMix = json.optBoolean("spu2LightweightMix", def.spu2LightweightMix),
                renderer = json.optString("renderer", def.renderer),
                upscaleFloat = json.optDouble("upscaleFloat", def.upscaleFloat.toDouble()).toFloat(),
                customDriverId = json.optString("customDriverId", def.customDriverId),
                orientation = json.optInt("orientation", def.orientation),
                portraitRenderTop = json.optBoolean("portraitRenderTop", def.portraitRenderTop),
                landscapeRenderTop = json.optBoolean("landscapeRenderTop", def.landscapeRenderTop),
                autoProgressiveScan = json.optBoolean("autoProgressiveScan", def.autoProgressiveScan),
                affinityMode = json.optInt("affinityMode", def.affinityMode),
                framerateNtsc = json.optDouble("framerateNtsc", def.framerateNtsc.toDouble()).toFloat(),
                frameratePal = json.optDouble("frameratePal", def.frameratePal.toDouble()).toFloat(),
                enablePatches = json.optBoolean("enablePatches", def.enablePatches),
                enableCheats = json.optBoolean("enableCheats", def.enableCheats),
                enableWideScreenPatches = json.optBoolean("enableWideScreenPatches", def.enableWideScreenPatches),
                enableNoInterlacingPatches = json.optBoolean("enableNoInterlacingPatches", def.enableNoInterlacingPatches),
                enableFastBoot = json.optBoolean("enableFastBoot", def.enableFastBoot),
                hostFs = json.optBoolean("hostFs", def.hostFs),
                enableGameFixes = json.optBoolean("enableGameFixes", def.enableGameFixes),
                gamefixSoftwareRendererFmv = json.optBoolean("gamefixSoftwareRendererFmv", def.gamefixSoftwareRendererFmv),
                gamefixSkipMpeg = json.optBoolean("gamefixSkipMpeg", def.gamefixSkipMpeg),
                gamefixEETiming = json.optBoolean("gamefixEETiming", def.gamefixEETiming),
                gamefixInstantDma = json.optBoolean("gamefixInstantDma", def.gamefixInstantDma),
                gamefixBlitInternalFps = json.optBoolean("gamefixBlitInternalFps", def.gamefixBlitInternalFps),
                gamefixFpuMul = json.optBoolean("gamefixFpuMul", def.gamefixFpuMul),
                gamefixOphFlag = json.optBoolean("gamefixOphFlag", def.gamefixOphFlag),
                gamefixGifFifo = json.optBoolean("gamefixGifFifo", def.gamefixGifFifo),
                gamefixDmaBusy = json.optBoolean("gamefixDmaBusy", def.gamefixDmaBusy),
                gamefixVif1Stall = json.optBoolean("gamefixVif1Stall", def.gamefixVif1Stall),
                gamefixIbit = json.optBoolean("gamefixIbit", def.gamefixIbit),
                gamefixFullVu0Sync = json.optBoolean("gamefixFullVu0Sync", def.gamefixFullVu0Sync),
                gamefixVuAddSub = json.optBoolean("gamefixVuAddSub", def.gamefixVuAddSub),
                gamefixVuOverflow = json.optBoolean("gamefixVuOverflow", def.gamefixVuOverflow),
                gamefixXgkick = json.optBoolean("gamefixXgkick", def.gamefixXgkick),
                gamefixGoemonTlb = json.optBoolean("gamefixGoemonTlb", def.gamefixGoemonTlb),
                gamefixVuSync = json.optBoolean("gamefixVuSync", def.gamefixVuSync),
                skipDuplicateFrames = json.optBoolean("skipDuplicateFrames", def.skipDuplicateFrames),
                eeFpuRoundMode = json.optInt("eeFpuRoundMode", def.eeFpuRoundMode),
                vu0RoundMode = json.optInt("vu0RoundMode", def.vu0RoundMode),
                vu1RoundMode = json.optInt("vu1RoundMode", def.vu1RoundMode),
                screenOffsets = json.optBoolean("screenOffsets", def.screenOffsets),
                showOverscan = json.optBoolean("showOverscan", def.showOverscan),
                antiBlur = json.optBoolean("antiBlur", def.antiBlur),
                disableInterlaceOffset = json.optBoolean("disableInterlaceOffset", def.disableInterlaceOffset),
                syncToHostRefresh = json.optBoolean("syncToHostRefresh", def.syncToHostRefresh),
                disableFramebufferFetch = json.optBoolean("disableFramebufferFetch", def.disableFramebufferFetch),
                hwRov = json.optBoolean("hwRov", def.hwRov),
                hwAa1 = json.optBoolean("hwAa1", def.hwAa1),
                hwAat = false,
                adrenoFbFetch = json.optBoolean("adrenoFbFetch", def.adrenoFbFetch),
                coalesceRenderPasses = json.optBoolean("coalesceRenderPasses", def.coalesceRenderPasses),
                forceMaliFbFetch = json.optBoolean("forceMaliFbFetch", def.forceMaliFbFetch),
                useAngleOpenGL = json.optBoolean("useAngleOpenGL", def.useAngleOpenGL),
                overrideTextureBarriers = json.optInt("overrideTextureBarriers", def.overrideTextureBarriers),
                gsBackThreadMode = json.optInt("gsBackThreadMode", def.gsBackThreadMode),
                disableVertexShaderExpand = json.optBoolean("disableVertexShaderExpand", def.disableVertexShaderExpand),
                useBlitSwapChain = json.optBoolean("useBlitSwapChain", def.useBlitSwapChain),
                disableShaderCache = json.optBoolean("disableShaderCache", def.disableShaderCache),
                hwAccurateAlphaTest = json.optBoolean(
                    "hwAccurateAlphaTest",
                    json.optBoolean("hwAat", def.hwAccurateAlphaTest),
                ),
                skipDrawStart = json.optInt("skipDrawStart", def.skipDrawStart),
                skipDrawEnd = json.optInt("skipDrawEnd", def.skipDrawEnd),
                spinGpuReadbacks = json.optBoolean("spinGpuReadbacks", def.spinGpuReadbacks),
                spinCpuReadbacks = json.optBoolean("spinCpuReadbacks", def.spinCpuReadbacks),
                integerScaling = json.optBoolean("integerScaling", def.integerScaling),
                cropLeft = json.optInt("cropLeft", def.cropLeft),
                displayZoom = json.optInt("displayZoom", def.displayZoom),
                cropTop = json.optInt("cropTop", def.cropTop),
                cropRight = json.optInt("cropRight", def.cropRight),
                cropBottom = json.optInt("cropBottom", def.cropBottom),
                dithering = json.optInt("dithering", def.dithering),
                vsyncQueueSize = json.optInt("vsyncQueueSize", def.vsyncQueueSize),
                hwScaler = json.optInt("hwScaler", def.hwScaler),
                displayFitMode = json.optInt("displayFitMode", def.displayFitMode),
                screenResOverride = json.optString("screenResOverride", def.screenResOverride).ifEmpty { def.screenResOverride },
                autoFlushSw = json.optBoolean("autoFlushSw", def.autoFlushSw),
                mipmapSw = json.optBoolean("mipmapSw", def.mipmapSw),
                swThreads = json.optInt("swThreads", def.swThreads),
                swThreadsHeight = json.optInt("swThreadsHeight", def.swThreadsHeight),
                aspectRatio = json.optInt("aspectRatio", def.aspectRatio),
                fmvAspectRatio = json.optInt("fmvAspectRatio", def.fmvAspectRatio),
                customAspectRatio = json.optDouble("customAspectRatio", def.customAspectRatio.toDouble()).toFloat(),
                deinterlaceMode = json.optInt("deinterlaceMode", def.deinterlaceMode),
                dev9EthEnable = json.optBoolean("dev9EthEnable", def.dev9EthEnable),
                dev9EthApi = json.optString("dev9EthApi", def.dev9EthApi).ifEmpty { def.dev9EthApi },
                localLinkHost = json.optBoolean("localLinkHost", def.localLinkHost),
                localLinkAddress = json.optString("localLinkAddress", def.localLinkAddress),
                localLinkPort = json.optInt("localLinkPort", def.localLinkPort),
                localLinkPeerId = json.optInt("localLinkPeerId", def.localLinkPeerId),
                localLinkRoomCode = json.optString("localLinkRoomCode", def.localLinkRoomCode),
                dev9EthDevice = json.optString("dev9EthDevice", def.dev9EthDevice).ifEmpty { def.dev9EthDevice },
                dev9EthLogDhcp = json.optBoolean("dev9EthLogDhcp", def.dev9EthLogDhcp),
                dev9EthLogDns = json.optBoolean("dev9EthLogDns", def.dev9EthLogDns),
                dev9InterceptDhcp = json.optBoolean("dev9InterceptDhcp", def.dev9InterceptDhcp),
                dev9Ps2Ip = json.optString("dev9Ps2Ip", def.dev9Ps2Ip).ifEmpty { def.dev9Ps2Ip },
                dev9Mask = json.optString("dev9Mask", def.dev9Mask).ifEmpty { def.dev9Mask },
                dev9Gateway = json.optString("dev9Gateway", def.dev9Gateway).ifEmpty { def.dev9Gateway },
                dev9Dns1 = json.optString("dev9Dns1", def.dev9Dns1).ifEmpty { def.dev9Dns1 },
                dev9Dns2 = json.optString("dev9Dns2", def.dev9Dns2).ifEmpty { def.dev9Dns2 },
                dev9AutoMask = json.optBoolean("dev9AutoMask", def.dev9AutoMask),
                dev9AutoGateway = json.optBoolean("dev9AutoGateway", def.dev9AutoGateway),
                dev9ModeDns1 = json.optString("dev9ModeDns1", def.dev9ModeDns1).ifEmpty { def.dev9ModeDns1 },
                dev9ModeDns2 = json.optString("dev9ModeDns2", def.dev9ModeDns2).ifEmpty { def.dev9ModeDns2 },
                dev9EthHosts = json.optJSONArray("dev9EthHosts")?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        arr.optJSONObject(idx)?.let { o ->
                            Dev9HostMapping(
                                url = o.optString("url", ""),
                                ip = o.optString("ip", "0.0.0.0").ifEmpty { "0.0.0.0" },
                                enabled = o.optBoolean("enabled", true),
                            )
                        }
                    }.filter { it.url.isNotBlank() }
                } ?: def.dev9EthHosts,
                dev9HddEnable = json.optBoolean("dev9HddEnable", def.dev9HddEnable),
                dev9HddFile = json.optString("dev9HddFile", def.dev9HddFile).ifEmpty { def.dev9HddFile },
                memoryCardSlot1Enabled = json.optBoolean("memoryCardSlot1Enabled", def.memoryCardSlot1Enabled),
                memoryCardSlot1Filename = json.optString("memoryCardSlot1Filename", def.memoryCardSlot1Filename).ifEmpty { def.memoryCardSlot1Filename },
                biosFilename = json.optString("biosFilename", def.biosFilename),
                memoryCardSlot2Enabled = json.optBoolean("memoryCardSlot2Enabled", def.memoryCardSlot2Enabled),
                memoryCardSlot2Filename = json.optString("memoryCardSlot2Filename", def.memoryCardSlot2Filename).ifEmpty { def.memoryCardSlot2Filename },
                usbKeyboard = json.optBoolean("usbKeyboard", def.usbKeyboard),
                recEE = json.optBoolean("recEE", def.recEE),
                recIOP = json.optBoolean("recIOP", def.recIOP),
                recVU0 = json.optBoolean("recVU0", def.recVU0),
                recVU1 = json.optBoolean("recVU1", def.recVU1),
                enableFastmem = json.optBoolean("enableFastmem", def.enableFastmem),
                useMacEE = true,
                useMacIOP = true,
                useMacVU0 = true,
                useMacVU1 = true,
                vu1InlineFmacStall = json.optBoolean("vu1InlineFmacStall", def.vu1InlineFmacStall),
                vu1CrossBlockPState = json.optBoolean("vu1CrossBlockPState", def.vu1CrossBlockPState),
                vu1InlineDrainTestPipes = json.optBoolean("vu1InlineDrainTestPipes", def.vu1InlineDrainTestPipes),
                vu1FmacInstanceRouting = json.optBoolean("vu1FmacInstanceRouting", def.vu1FmacInstanceRouting),
                hwMipmap = json.optBoolean("hwMipmap", def.hwMipmap),
                accurateBlendingUnit = json.optInt("accurateBlendingUnit", def.accurateBlendingUnit),
                textureFiltering = json.optInt("textureFiltering", def.textureFiltering),
                displayBilinear = json.optInt("displayBilinear", def.displayBilinear),
                texturePreloading = json.optInt("texturePreloading", def.texturePreloading),
                hardwareDownloadMode = json.optInt("hardwareDownloadMode", def.hardwareDownloadMode),
                tvShader = json.optInt("tvShader", def.tvShader),
                shadeBoost = json.optBoolean("shadeBoost", def.shadeBoost),
                shadeBoostBrightness = json.optInt("shadeBoostBrightness", def.shadeBoostBrightness),
                shadeBoostContrast = json.optInt("shadeBoostContrast", def.shadeBoostContrast),
                shadeBoostSaturation = json.optInt("shadeBoostSaturation", def.shadeBoostSaturation),
                shadeBoostGamma = json.optInt("shadeBoostGamma", def.shadeBoostGamma),
                fxaa = json.optBoolean("fxaa", def.fxaa),
                shaderChainEnabled = json.optBoolean("shaderChainEnabled", def.shaderChainEnabled),
                shaderChainPreset = json.optString("shaderChainPreset", def.shaderChainPreset),
                shaderChainParams = json.optJSONObject("shaderChainParams")
                    ?.let { shaderChainParamsFromJson(it) } ?: def.shaderChainParams,
                casMode = json.optInt("casMode", def.casMode),
                sgsrSharpness = json.optInt("sgsrSharpness", def.sgsrSharpness),
                casSharpness = json.optInt("casSharpness", def.casSharpness),
                loadTextureReplacements = json.optBoolean("loadTextureReplacements", def.loadTextureReplacements),
                loadTextureReplacementsAsync = json.optBoolean("loadTextureReplacementsAsync", def.loadTextureReplacementsAsync),
                precacheTextureReplacements = json.optBoolean("precacheTextureReplacements", def.precacheTextureReplacements),
                dumpReplaceableTextures = json.optBoolean("dumpReplaceableTextures", def.dumpReplaceableTextures),
                osdShowTextureReplacements = json.optBoolean("osdShowTextureReplacements", def.osdShowTextureReplacements),
                osdShowFps = json.optBoolean("osdShowFps", def.osdShowFps),
                osdScale = json.optInt("osdScale", def.osdScale),
                osdColor = json.optInt("osdColor", def.osdColor),
                vsyncEnable = json.optBoolean("vsyncEnable", def.vsyncEnable),
                osdShowVps = json.optBoolean("osdShowVps", def.osdShowVps),
                osdShowSpeed = json.optBoolean("osdShowSpeed", def.osdShowSpeed),
                osdShowCpu = json.optBoolean("osdShowCpu", def.osdShowCpu),
                osdShowGpu = json.optBoolean("osdShowGpu", def.osdShowGpu),
                osdShowResolution = json.optBoolean("osdShowResolution", def.osdShowResolution),
                osdShowGsStats = json.optBoolean("osdShowGsStats", def.osdShowGsStats),
                osdShowFrameTimes = json.optBoolean("osdShowFrameTimes", def.osdShowFrameTimes),
                osdShowHardwareInfo = json.optBoolean("osdShowHardwareInfo", def.osdShowHardwareInfo),
                osdShowMessages = json.optBoolean("osdShowMessages", def.osdShowMessages),
                osdShowGpuStats = json.optBoolean("osdShowGpuStats", def.osdShowGpuStats),
                osdShowVersion = json.optBoolean("osdShowVersion", def.osdShowVersion),
                osdShowSettings = json.optBoolean("osdShowSettings", def.osdShowSettings),
                osdShowInputs = json.optBoolean("osdShowInputs", def.osdShowInputs),
                autoFlush = json.optInt("autoFlush", def.autoFlush),
                halfPixelOffset = json.optInt("halfPixelOffset", def.halfPixelOffset),
                limit24BitDepth = json.optInt("limit24BitDepth", def.limit24BitDepth),
                manualUserHacks = json.optBoolean("manualUserHacks", def.manualUserHacks),
                textureInsideRt = json.optInt("textureInsideRt", def.textureInsideRt),
                nativeScaling = json.optInt("nativeScaling", def.nativeScaling),
                roundSprite = json.optInt("roundSprite", def.roundSprite),
                bilinearUpscale = json.optInt("bilinearUpscale", def.bilinearUpscale),
                gpuTargetClut = json.optInt("gpuTargetClut", def.gpuTargetClut),
                cpuSpriteRenderBw = json.optInt("cpuSpriteRenderBw", def.cpuSpriteRenderBw),
                cpuSpriteRenderLevel = json.optInt("cpuSpriteRenderLevel", def.cpuSpriteRenderLevel),
                alignSprite = json.optBoolean("alignSprite", def.alignSprite),
                mergeSprite = json.optBoolean("mergeSprite", def.mergeSprite),
                forceEvenSpritePosition = json.optBoolean("forceEvenSpritePosition", def.forceEvenSpritePosition),
                unscaledPaletteDraw = json.optBoolean("unscaledPaletteDraw", def.unscaledPaletteDraw),
                textureOffsetX = json.optInt("textureOffsetX", def.textureOffsetX),
                textureOffsetY = json.optInt("textureOffsetY", def.textureOffsetY),
                gpuPaletteConversion = json.optBoolean("gpuPaletteConversion", def.gpuPaletteConversion),
                cpuFramebufferConversion = json.optBoolean("cpuFramebufferConversion", def.cpuFramebufferConversion),
                readTargetsWhenClosing = json.optBoolean("readTargetsWhenClosing", def.readTargetsWhenClosing),
                disableDepthEmulation = json.optBoolean("disableDepthEmulation", def.disableDepthEmulation),
                disablePartialInvalidation = json.optBoolean("disablePartialInvalidation", def.disablePartialInvalidation),
                disableSafeFeatures = json.optBoolean("disableSafeFeatures", def.disableSafeFeatures),
                disableRenderFixes = json.optBoolean("disableRenderFixes", def.disableRenderFixes),
                preloadFrameData = json.optBoolean("preloadFrameData", def.preloadFrameData),
                estimateTextureRegion = json.optBoolean("estimateTextureRegion", def.estimateTextureRegion),
                drawBuffering = json.optBoolean("drawBuffering", def.drawBuffering),
                cpuClutRender = json.optInt("cpuClutRender", def.cpuClutRender),
                triFilter = json.optInt("triFilter", def.triFilter),
                maxAnisotropy = json.optInt("maxAnisotropy", def.maxAnisotropy),
                gpuProfile = json.optInt("gpuProfile", def.gpuProfile),
            )
        }

        /** Treat any field present in [overrides] as a delta over [base]. */
        /**
         * Compute the sparse override JSON between two Settings: returns
         * only fields where `current` differs from `base`. Used by the
         * overlay's per-game save path so we only persist what the user
         * actually changed for this title — global tweaks still flow
         * through fields the user hasn't touched. Mirrors the field set
         * of [merge] above (must stay in sync).
         */
        fun diff(base: Settings, current: Settings): JSONObject {
            val j = JSONObject()
            if (current.eeCycleRate         != base.eeCycleRate)         j.put("eeCycleRate", current.eeCycleRate)
            if (current.ps3.ppuDecoder != base.ps3.ppuDecoder) j.put("ps3PpuDecoder", current.ps3.ppuDecoder)
            if (current.ps3.spuDecoder != base.ps3.spuDecoder) j.put("ps3SpuDecoder", current.ps3.spuDecoder)
            if (current.ps3.spuBlockSize != base.ps3.spuBlockSize) j.put("ps3SpuBlockSize", current.ps3.spuBlockSize)
            if (current.ps3.ppuThreads != base.ps3.ppuThreads) j.put("ps3PpuThreads", current.ps3.ppuThreads)
            if (current.ps3.llvmThreads != base.ps3.llvmThreads) j.put("ps3LlvmThreads", current.ps3.llvmThreads)
            if (current.ps3.preferredSpuThreads != base.ps3.preferredSpuThreads) j.put("ps3PreferredSpuThreads", current.ps3.preferredSpuThreads)
            if (current.ps3.maxSpursThreads != base.ps3.maxSpursThreads) j.put("ps3MaxSpursThreads", current.ps3.maxSpursThreads)
            if (current.ps3.spuLoopDetection != base.ps3.spuLoopDetection) j.put("ps3SpuLoopDetection", current.ps3.spuLoopDetection)
            if (current.ps3.spuCache != base.ps3.spuCache) j.put("ps3SpuCache", current.ps3.spuCache)
            if (current.ps3.llvmPrecompile != base.ps3.llvmPrecompile) j.put("ps3LlvmPrecompile", current.ps3.llvmPrecompile)
            if (current.ps3.accurateSpuDma != base.ps3.accurateSpuDma) j.put("ps3AccurateSpuDma", current.ps3.accurateSpuDma)
            if (current.ps3.savestateCompatibleMode != base.ps3.savestateCompatibleMode) j.put("ps3SavestateCompatibleMode", current.ps3.savestateCompatibleMode)
            if (current.ps3.clocksScale != base.ps3.clocksScale) j.put("ps3ClocksScale", current.ps3.clocksScale)
            if (current.ps3.resolutionScale != base.ps3.resolutionScale) j.put("ps3ResolutionScale", current.ps3.resolutionScale)
            if (current.ps3.msaaMode != base.ps3.msaaMode) j.put("ps3MsaaMode", current.ps3.msaaMode)
            if (current.ps3.audioCubebBackend != base.ps3.audioCubebBackend) j.put("ps3AudioCubebBackend", current.ps3.audioCubebBackend)
            if (current.ps3.shaderMode != base.ps3.shaderMode) j.put("ps3ShaderMode", current.ps3.shaderMode)
            if (current.ps3.frameGeneration != base.ps3.frameGeneration) j.put("ps3FrameGeneration", current.ps3.frameGeneration)
            if (current.ps3.frameGenPerformance != base.ps3.frameGenPerformance) j.put("ps3FrameGenPerformance", current.ps3.frameGenPerformance)
            if (current.ps3.frameGenFlowScale != base.ps3.frameGenFlowScale) j.put("ps3FrameGenFlowScale", current.ps3.frameGenFlowScale)
            if (current.ps3.frameGenTargetRate != base.ps3.frameGenTargetRate) j.put("ps3FrameGenTargetRate", current.ps3.frameGenTargetRate)
            if (current.ps3.writeColorBuffers != base.ps3.writeColorBuffers) j.put("ps3WriteColorBuffers", current.ps3.writeColorBuffers)
            if (current.ps3.gpuTurbo != base.ps3.gpuTurbo) j.put("ps3GpuTurbo", current.ps3.gpuTurbo)
            if (current.ps3.silenceAllLogs != base.ps3.silenceAllLogs) j.put("ps3SilenceAllLogs", current.ps3.silenceAllLogs)
            if (current.ps3.writeDepthBuffer != base.ps3.writeDepthBuffer) j.put("ps3WriteDepthBuffer", current.ps3.writeDepthBuffer)
            if (current.ps3.readColorBuffers != base.ps3.readColorBuffers) j.put("ps3ReadColorBuffers", current.ps3.readColorBuffers)
            if (current.ps3.readDepthBuffer != base.ps3.readDepthBuffer) j.put("ps3ReadDepthBuffer", current.ps3.readDepthBuffer)
            if (current.ps3.strictRendering != base.ps3.strictRendering) j.put("ps3StrictRendering", current.ps3.strictRendering)
            if (current.ps3.multithreadedRsx != base.ps3.multithreadedRsx) j.put("ps3MultithreadedRsx", current.ps3.multithreadedRsx)
            if (current.ps3.disableZcull != base.ps3.disableZcull) j.put("ps3DisableZcull", current.ps3.disableZcull)
            if (current.ps3.relaxedZcull != base.ps3.relaxedZcull) j.put("ps3RelaxedZcull", current.ps3.relaxedZcull)
            if (current.ps3.gpuTextureScaling != base.ps3.gpuTextureScaling) j.put("ps3GpuTextureScaling", current.ps3.gpuTextureScaling)
            if (current.ps3.forceCpuBlit != base.ps3.forceCpuBlit) j.put("ps3ForceCpuBlit", current.ps3.forceCpuBlit)
            if (current.ps3.shaderCompThreads != base.ps3.shaderCompThreads) j.put("ps3ShaderCompThreads", current.ps3.shaderCompThreads)
            if (current.ps3.textureLodBias != base.ps3.textureLodBias) j.put("ps3TextureLodBias", current.ps3.textureLodBias)
            if (current.ps3.vramLimitMb != base.ps3.vramLimitMb) j.put("ps3VramLimitMb", current.ps3.vramLimitMb)
            if (current.ps3.asyncTexStream != base.ps3.asyncTexStream) j.put("ps3AsyncTexStream", current.ps3.asyncTexStream)
            if (current.ps3.audioFormat != base.ps3.audioFormat) j.put("ps3AudioFormat", current.ps3.audioFormat)
            if (current.ps3.audioChannels != base.ps3.audioChannels) j.put("ps3AudioChannels", current.ps3.audioChannels)
            if (current.ps3.audioTimeStretch != base.ps3.audioTimeStretch) j.put("ps3AudioTimeStretch", current.ps3.audioTimeStretch)
            if (current.ps3.audioBuffering != base.ps3.audioBuffering) j.put("ps3AudioBuffering", current.ps3.audioBuffering)
            if (current.ps3.audioBufferMs != base.ps3.audioBufferMs) j.put("ps3AudioBufferMs", current.ps3.audioBufferMs)
            if (current.ps3.netEnabled != base.ps3.netEnabled) j.put("ps3NetEnabled", current.ps3.netEnabled)
            if (current.ps3.psnStatus != base.ps3.psnStatus) j.put("ps3PsnStatus", current.ps3.psnStatus)
            if (current.ps3.upnpEnabled != base.ps3.upnpEnabled) j.put("ps3UpnpEnabled", current.ps3.upnpEnabled)
            if (current.ps3.ipAddress != base.ps3.ipAddress) j.put("ps3IpAddress", current.ps3.ipAddress)
            if (current.ps3.bindAddress != base.ps3.bindAddress) j.put("ps3BindAddress", current.ps3.bindAddress)
            if (current.ps3.dnsAddress != base.ps3.dnsAddress) j.put("ps3DnsAddress", current.ps3.dnsAddress)
            if (current.ps3.ipSwapList != base.ps3.ipSwapList) j.put("ps3IpSwapList", current.ps3.ipSwapList)
            if (current.ps3.deriveMacFromPsid != base.ps3.deriveMacFromPsid) j.put("ps3DeriveMacFromPsid", current.ps3.deriveMacFromPsid)
            if (current.ps3.psnCountry != base.ps3.psnCountry) j.put("ps3PsnCountry", current.ps3.psnCountry)
            if (current.ps3.clansEnabled != base.ps3.clansEnabled) j.put("ps3ClansEnabled", current.ps3.clansEnabled)
            if (current.ps3.enterButtonAssign != base.ps3.enterButtonAssign) j.put("ps3EnterButtonAssign", current.ps3.enterButtonAssign)
            if (current.ps3.consoleLanguage != base.ps3.consoleLanguage) j.put("ps3ConsoleLanguage", current.ps3.consoleLanguage)
            if (current.ps3.consoleRegion != base.ps3.consoleRegion) j.put("ps3ConsoleRegion", current.ps3.consoleRegion)
            if (current.ps3.keyboardType != base.ps3.keyboardType) j.put("ps3KeyboardType", current.ps3.keyboardType)
            if (current.ps3.dateFormat != base.ps3.dateFormat) j.put("ps3DateFormat", current.ps3.dateFormat)
            if (current.ps3.timeFormat != base.ps3.timeFormat) j.put("ps3TimeFormat", current.ps3.timeFormat)
            if (current.ps3.spuXFloat != base.ps3.spuXFloat) j.put("ps3SpuXFloat", current.ps3.spuXFloat)
            if (current.ps3.accurateSpuRsv != base.ps3.accurateSpuRsv) j.put("ps3AccurateSpuRsv", current.ps3.accurateSpuRsv)
            if (current.ps3.accurateCacheLine != base.ps3.accurateCacheLine) j.put("ps3AccurateCacheLine", current.ps3.accurateCacheLine)
            if (current.ps3.accurateRsxRsv != base.ps3.accurateRsxRsv) j.put("ps3AccurateRsxRsv", current.ps3.accurateRsxRsv)
            if (current.ps3.ppuRsvPriority != base.ps3.ppuRsvPriority) j.put("ps3PpuRsvPriority", current.ps3.ppuRsvPriority)
            if (current.ps3.spuVerification != base.ps3.spuVerification) j.put("ps3SpuVerification", current.ps3.spuVerification)
            if (current.ps3.preciseSpuVerification != base.ps3.preciseSpuVerification) j.put("ps3PreciseSpuVerification", current.ps3.preciseSpuVerification)
            if (current.ps3.ppuNanHandling != base.ps3.ppuNanHandling) j.put("ps3PpuNanHandling", current.ps3.ppuNanHandling)
            if (current.ps3.accurateDfma != base.ps3.accurateDfma) j.put("ps3AccurateDfma", current.ps3.accurateDfma)
            if (current.ps3.setDazFtz != base.ps3.setDazFtz) j.put("ps3SetDazFtz", current.ps3.setDazFtz)
            if (current.ps3.displayAspect != base.ps3.displayAspect) j.put("ps3DisplayAspect", current.ps3.displayAspect)
            if (current.ps3.overlayEnabled != base.ps3.overlayEnabled) j.put("ps3OverlayEnabled", current.ps3.overlayEnabled)
            if (current.ps3.overlayDetail != base.ps3.overlayDetail) j.put("ps3OverlayDetail", current.ps3.overlayDetail)
            if (current.ps3.overlayPosition != base.ps3.overlayPosition) j.put("ps3OverlayPosition", current.ps3.overlayPosition)
            if (current.ps3.overlayFontSize != base.ps3.overlayFontSize) j.put("ps3OverlayFontSize", current.ps3.overlayFontSize)
            if (current.ps3.overlayOpacity != base.ps3.overlayOpacity) j.put("ps3OverlayOpacity", current.ps3.overlayOpacity)
            if (current.ps3.overlayFramerateGraph != base.ps3.overlayFramerateGraph) j.put("ps3OverlayFramerateGraph", current.ps3.overlayFramerateGraph)
            if (current.ps3.overlayFrametimeGraph != base.ps3.overlayFrametimeGraph) j.put("ps3OverlayFrametimeGraph", current.ps3.overlayFrametimeGraph)
            if (current.ps3.overlayBodyColor != base.ps3.overlayBodyColor) j.put("ps3OverlayBodyColor", current.ps3.overlayBodyColor)
            if (current.ps3.overlayBodyBg != base.ps3.overlayBodyBg) j.put("ps3OverlayBodyBg", current.ps3.overlayBodyBg)
            if (current.ps3.overlayTitleColor != base.ps3.overlayTitleColor) j.put("ps3OverlayTitleColor", current.ps3.overlayTitleColor)
            if (current.ps3.overlayTitleBg != base.ps3.overlayTitleBg) j.put("ps3OverlayTitleBg", current.ps3.overlayTitleBg)
            if (current.ps3.hleLwmutex != base.ps3.hleLwmutex) j.put("ps3HleLwmutex", current.ps3.hleLwmutex)
            if (current.ps3.sleepTimers != base.ps3.sleepTimers) j.put("ps3SleepTimers", current.ps3.sleepTimers)
            if (current.ps3.debugConsoleMode != base.ps3.debugConsoleMode) j.put("ps3DebugConsoleMode", current.ps3.debugConsoleMode)
            if (current.ps3.resolution != base.ps3.resolution) j.put("ps3Resolution", current.ps3.resolution)
            if (current.ps3.anisoFilter != base.ps3.anisoFilter) j.put("ps3AnisoFilter", current.ps3.anisoFilter)
            if (current.ps3.audioRenderer != base.ps3.audioRenderer) j.put("ps3AudioRenderer", current.ps3.audioRenderer)
            if (current.eeCycleSkip         != base.eeCycleSkip)         j.put("eeCycleSkip", current.eeCycleSkip)
            if (current.eeClampMode         != base.eeClampMode)         j.put("eeClampMode", current.eeClampMode)
            if (current.vuClampMode         != base.vuClampMode)         j.put("vuClampMode", current.vuClampMode)
            if (current.mtvu                != base.mtvu)                j.put("mtvu", current.mtvu)
            if (current.vu1Instant          != base.vu1Instant)          j.put("vu1Instant", current.vu1Instant)
            if (current.vuFlagHack          != base.vuFlagHack)          j.put("vuFlagHack", current.vuFlagHack)
            if (current.fastCDVD            != base.fastCDVD)            j.put("fastCDVD", current.fastCDVD)
            if (current.intcStat            != base.intcStat)            j.put("intcStat", current.intcStat)
            if (current.waitLoop            != base.waitLoop)            j.put("waitLoop", current.waitLoop)
            if (current.vuNeonFusions       != base.vuNeonFusions)       j.put("vuNeonFusions", current.vuNeonFusions)
            if (current.vuDeferredWrites    != base.vuDeferredWrites)    j.put("vuDeferredWrites", current.vuDeferredWrites)
            if (current.vuSkipStallSim      != base.vuSkipStallSim)      j.put("vuSkipStallSim", current.vuSkipStallSim)
            if (current.frameLimitEnable    != base.frameLimitEnable)    j.put("frameLimitEnable", current.frameLimitEnable)
            if (current.nominalSpeedPercent != base.nominalSpeedPercent) j.put("nominalSpeedPercent", current.nominalSpeedPercent)
            if (current.fpsLimit            != base.fpsLimit)            j.put("fpsLimit", current.fpsLimit)
            if (current.frameSkip != base.frameSkip) j.put("frameSkip", current.frameSkip)
            if (current.audioVolume != base.audioVolume) j.put("audioVolume", current.audioVolume)
            if (current.audioMuted != base.audioMuted) j.put("audioMuted", current.audioMuted)
            if (current.audioSwapChannels != base.audioSwapChannels) j.put("audioSwapChannels", current.audioSwapChannels)
            if (current.audioTimeStretch != base.audioTimeStretch) j.put("audioTimeStretch", current.audioTimeStretch)
            if (current.audioBufferMs != base.audioBufferMs) j.put("audioBufferMs", current.audioBufferMs)
            if (current.audioOutputLatencyMs != base.audioOutputLatencyMs) j.put("audioOutputLatencyMs", current.audioOutputLatencyMs)
            if (current.audioFastForwardVolume != base.audioFastForwardVolume) j.put("audioFastForwardVolume", current.audioFastForwardVolume)
            if (current.spu2NeonReverb != base.spu2NeonReverb) j.put("spu2NeonReverb", current.spu2NeonReverb)
            if (current.audioOpenSLES != base.audioOpenSLES) j.put("audioOpenSLES", current.audioOpenSLES)
            if (current.spu2LightweightMix != base.spu2LightweightMix) j.put("spu2LightweightMix", current.spu2LightweightMix)
            if (current.renderer != base.renderer) j.put("renderer", current.renderer)
            if (current.upscaleFloat != base.upscaleFloat) j.put("upscaleFloat", current.upscaleFloat.toDouble())
            if (current.customDriverId != base.customDriverId) j.put("customDriverId", current.customDriverId)
            if (current.orientation != base.orientation) j.put("orientation", current.orientation)
            if (current.portraitRenderTop != base.portraitRenderTop) j.put("portraitRenderTop", current.portraitRenderTop)
            if (current.landscapeRenderTop != base.landscapeRenderTop) j.put("landscapeRenderTop", current.landscapeRenderTop)
            if (current.autoProgressiveScan != base.autoProgressiveScan) j.put("autoProgressiveScan", current.autoProgressiveScan)
            if (current.affinityMode != base.affinityMode) j.put("affinityMode", current.affinityMode)
            if (current.framerateNtsc != base.framerateNtsc) j.put("framerateNtsc", current.framerateNtsc.toDouble())
            if (current.frameratePal != base.frameratePal) j.put("frameratePal", current.frameratePal.toDouble())
            if (current.enablePatches != base.enablePatches) j.put("enablePatches", current.enablePatches)
            if (current.enableCheats != base.enableCheats) j.put("enableCheats", current.enableCheats)
            if (current.enableWideScreenPatches != base.enableWideScreenPatches) j.put("enableWideScreenPatches", current.enableWideScreenPatches)
            if (current.enableNoInterlacingPatches != base.enableNoInterlacingPatches) j.put("enableNoInterlacingPatches", current.enableNoInterlacingPatches)
            if (current.enableFastBoot != base.enableFastBoot) j.put("enableFastBoot", current.enableFastBoot)
            if (current.hostFs != base.hostFs) j.put("hostFs", current.hostFs)
            if (current.enableGameFixes != base.enableGameFixes) j.put("enableGameFixes", current.enableGameFixes)
            if (current.gamefixSoftwareRendererFmv != base.gamefixSoftwareRendererFmv) j.put("gamefixSoftwareRendererFmv", current.gamefixSoftwareRendererFmv)
            if (current.gamefixSkipMpeg != base.gamefixSkipMpeg) j.put("gamefixSkipMpeg", current.gamefixSkipMpeg)
            if (current.gamefixEETiming != base.gamefixEETiming) j.put("gamefixEETiming", current.gamefixEETiming)
            if (current.gamefixInstantDma != base.gamefixInstantDma) j.put("gamefixInstantDma", current.gamefixInstantDma)
            if (current.gamefixBlitInternalFps != base.gamefixBlitInternalFps) j.put("gamefixBlitInternalFps", current.gamefixBlitInternalFps)
            if (current.gamefixFpuMul        != base.gamefixFpuMul)        j.put("gamefixFpuMul", current.gamefixFpuMul)
            if (current.gamefixOphFlag       != base.gamefixOphFlag)       j.put("gamefixOphFlag", current.gamefixOphFlag)
            if (current.gamefixGifFifo       != base.gamefixGifFifo)       j.put("gamefixGifFifo", current.gamefixGifFifo)
            if (current.gamefixDmaBusy       != base.gamefixDmaBusy)       j.put("gamefixDmaBusy", current.gamefixDmaBusy)
            if (current.gamefixVif1Stall     != base.gamefixVif1Stall)     j.put("gamefixVif1Stall", current.gamefixVif1Stall)
            if (current.gamefixIbit          != base.gamefixIbit)          j.put("gamefixIbit", current.gamefixIbit)
            if (current.gamefixFullVu0Sync   != base.gamefixFullVu0Sync)   j.put("gamefixFullVu0Sync", current.gamefixFullVu0Sync)
            if (current.gamefixVuAddSub      != base.gamefixVuAddSub)      j.put("gamefixVuAddSub", current.gamefixVuAddSub)
            if (current.gamefixVuOverflow    != base.gamefixVuOverflow)    j.put("gamefixVuOverflow", current.gamefixVuOverflow)
            if (current.gamefixXgkick        != base.gamefixXgkick)        j.put("gamefixXgkick", current.gamefixXgkick)
            if (current.gamefixGoemonTlb     != base.gamefixGoemonTlb)     j.put("gamefixGoemonTlb", current.gamefixGoemonTlb)
            if (current.gamefixVuSync        != base.gamefixVuSync)        j.put("gamefixVuSync", current.gamefixVuSync)
            if (current.skipDuplicateFrames  != base.skipDuplicateFrames)  j.put("skipDuplicateFrames", current.skipDuplicateFrames)
            if (current.eeFpuRoundMode       != base.eeFpuRoundMode)       j.put("eeFpuRoundMode", current.eeFpuRoundMode)
            if (current.vu0RoundMode         != base.vu0RoundMode)         j.put("vu0RoundMode", current.vu0RoundMode)
            if (current.vu1RoundMode         != base.vu1RoundMode)         j.put("vu1RoundMode", current.vu1RoundMode)
            if (current.screenOffsets        != base.screenOffsets)        j.put("screenOffsets", current.screenOffsets)
            if (current.showOverscan         != base.showOverscan)         j.put("showOverscan", current.showOverscan)
            if (current.antiBlur             != base.antiBlur)             j.put("antiBlur", current.antiBlur)
            if (current.disableInterlaceOffset != base.disableInterlaceOffset) j.put("disableInterlaceOffset", current.disableInterlaceOffset)
            if (current.syncToHostRefresh    != base.syncToHostRefresh)    j.put("syncToHostRefresh", current.syncToHostRefresh)
            if (current.disableFramebufferFetch != base.disableFramebufferFetch) j.put("disableFramebufferFetch", current.disableFramebufferFetch)
            if (current.hwRov != base.hwRov) j.put("hwRov", current.hwRov)
            if (current.hwAa1 != base.hwAa1) j.put("hwAa1", current.hwAa1)
            if (current.adrenoFbFetch != base.adrenoFbFetch) j.put("adrenoFbFetch", current.adrenoFbFetch)
            if (current.coalesceRenderPasses != base.coalesceRenderPasses) j.put("coalesceRenderPasses", current.coalesceRenderPasses)
            if (current.forceMaliFbFetch != base.forceMaliFbFetch) j.put("forceMaliFbFetch", current.forceMaliFbFetch)
            if (current.useAngleOpenGL != base.useAngleOpenGL) j.put("useAngleOpenGL", current.useAngleOpenGL)
            if (current.overrideTextureBarriers != base.overrideTextureBarriers) j.put("overrideTextureBarriers", current.overrideTextureBarriers)
            if (current.gsBackThreadMode != base.gsBackThreadMode) j.put("gsBackThreadMode", current.gsBackThreadMode)
            if (current.disableVertexShaderExpand != base.disableVertexShaderExpand) j.put("disableVertexShaderExpand", current.disableVertexShaderExpand)
            if (current.useBlitSwapChain     != base.useBlitSwapChain)     j.put("useBlitSwapChain", current.useBlitSwapChain)
            if (current.disableShaderCache   != base.disableShaderCache)   j.put("disableShaderCache", current.disableShaderCache)
            if (current.hwAccurateAlphaTest  != base.hwAccurateAlphaTest)  j.put("hwAccurateAlphaTest", current.hwAccurateAlphaTest)
            if (current.skipDrawStart        != base.skipDrawStart)        j.put("skipDrawStart", current.skipDrawStart)
            if (current.skipDrawEnd          != base.skipDrawEnd)          j.put("skipDrawEnd", current.skipDrawEnd)
            if (current.spinGpuReadbacks     != base.spinGpuReadbacks)     j.put("spinGpuReadbacks", current.spinGpuReadbacks)
            if (current.spinCpuReadbacks     != base.spinCpuReadbacks)     j.put("spinCpuReadbacks", current.spinCpuReadbacks)
            if (current.integerScaling       != base.integerScaling)       j.put("integerScaling", current.integerScaling)
            if (current.cropLeft             != base.cropLeft)             j.put("cropLeft", current.cropLeft)
            if (current.displayZoom          != base.displayZoom)          j.put("displayZoom", current.displayZoom)
            if (current.cropTop              != base.cropTop)              j.put("cropTop", current.cropTop)
            if (current.cropRight            != base.cropRight)            j.put("cropRight", current.cropRight)
            if (current.cropBottom           != base.cropBottom)           j.put("cropBottom", current.cropBottom)
            if (current.dithering            != base.dithering)            j.put("dithering", current.dithering)
            if (current.vsyncQueueSize       != base.vsyncQueueSize)       j.put("vsyncQueueSize", current.vsyncQueueSize)
            if (current.hwScaler             != base.hwScaler)             j.put("hwScaler", current.hwScaler)
            if (current.displayFitMode != base.displayFitMode) j.put("displayFitMode", current.displayFitMode)
            if (current.screenResOverride    != base.screenResOverride)    j.put("screenResOverride", current.screenResOverride)
            if (current.autoFlushSw          != base.autoFlushSw)          j.put("autoFlushSw", current.autoFlushSw)
            if (current.mipmapSw             != base.mipmapSw)             j.put("mipmapSw", current.mipmapSw)
            if (current.swThreads            != base.swThreads)            j.put("swThreads", current.swThreads)
            if (current.swThreadsHeight      != base.swThreadsHeight)      j.put("swThreadsHeight", current.swThreadsHeight)
            if (current.aspectRatio         != base.aspectRatio)         j.put("aspectRatio", current.aspectRatio)
            if (current.fmvAspectRatio      != base.fmvAspectRatio)      j.put("fmvAspectRatio", current.fmvAspectRatio)
            if (current.customAspectRatio   != base.customAspectRatio)   j.put("customAspectRatio", current.customAspectRatio.toDouble())
            if (current.deinterlaceMode     != base.deinterlaceMode)     j.put("deinterlaceMode", current.deinterlaceMode)
            if (current.dev9EthEnable       != base.dev9EthEnable)       j.put("dev9EthEnable", current.dev9EthEnable)
            if (current.dev9EthApi          != base.dev9EthApi)          j.put("dev9EthApi", current.dev9EthApi)
            if (current.localLinkHost != base.localLinkHost) j.put("localLinkHost", current.localLinkHost)
            if (current.localLinkAddress != base.localLinkAddress) j.put("localLinkAddress", current.localLinkAddress)
            if (current.localLinkPort != base.localLinkPort) j.put("localLinkPort", current.localLinkPort)
            if (current.localLinkPeerId != base.localLinkPeerId) j.put("localLinkPeerId", current.localLinkPeerId)
            if (current.localLinkRoomCode != base.localLinkRoomCode) j.put("localLinkRoomCode", current.localLinkRoomCode)
            if (current.dev9EthDevice       != base.dev9EthDevice)       j.put("dev9EthDevice", current.dev9EthDevice)
            if (current.dev9EthLogDhcp      != base.dev9EthLogDhcp)      j.put("dev9EthLogDhcp", current.dev9EthLogDhcp)
            if (current.dev9EthLogDns       != base.dev9EthLogDns)       j.put("dev9EthLogDns", current.dev9EthLogDns)
            if (current.dev9InterceptDhcp   != base.dev9InterceptDhcp)   j.put("dev9InterceptDhcp", current.dev9InterceptDhcp)
            if (current.dev9Ps2Ip           != base.dev9Ps2Ip)           j.put("dev9Ps2Ip", current.dev9Ps2Ip)
            if (current.dev9Mask            != base.dev9Mask)            j.put("dev9Mask", current.dev9Mask)
            if (current.dev9Gateway         != base.dev9Gateway)         j.put("dev9Gateway", current.dev9Gateway)
            if (current.dev9Dns1            != base.dev9Dns1)            j.put("dev9Dns1", current.dev9Dns1)
            if (current.dev9Dns2            != base.dev9Dns2)            j.put("dev9Dns2", current.dev9Dns2)
            if (current.dev9AutoMask        != base.dev9AutoMask)        j.put("dev9AutoMask", current.dev9AutoMask)
            if (current.dev9AutoGateway     != base.dev9AutoGateway)     j.put("dev9AutoGateway", current.dev9AutoGateway)
            if (current.dev9ModeDns1        != base.dev9ModeDns1)        j.put("dev9ModeDns1", current.dev9ModeDns1)
            if (current.dev9ModeDns2        != base.dev9ModeDns2)        j.put("dev9ModeDns2", current.dev9ModeDns2)
            if (current.dev9EthHosts        != base.dev9EthHosts) {
                j.put("dev9EthHosts", JSONArray().apply {
                    current.dev9EthHosts.forEach { host ->
                        put(JSONObject().apply {
                            put("url", host.url)
                            put("ip", host.ip)
                            put("enabled", host.enabled)
                        })
                    }
                })
            }
            if (current.dev9HddEnable       != base.dev9HddEnable)       j.put("dev9HddEnable", current.dev9HddEnable)
            if (current.dev9HddFile         != base.dev9HddFile)         j.put("dev9HddFile", current.dev9HddFile)
            if (current.memoryCardSlot1Enabled != base.memoryCardSlot1Enabled) j.put("memoryCardSlot1Enabled", current.memoryCardSlot1Enabled)
            if (current.memoryCardSlot1Filename != base.memoryCardSlot1Filename) j.put("memoryCardSlot1Filename", current.memoryCardSlot1Filename)
            if (current.biosFilename != base.biosFilename) j.put("biosFilename", current.biosFilename)
            if (current.memoryCardSlot2Enabled != base.memoryCardSlot2Enabled) j.put("memoryCardSlot2Enabled", current.memoryCardSlot2Enabled)
            if (current.memoryCardSlot2Filename != base.memoryCardSlot2Filename) j.put("memoryCardSlot2Filename", current.memoryCardSlot2Filename)
            if (current.usbKeyboard         != base.usbKeyboard)         j.put("usbKeyboard", current.usbKeyboard)
            if (current.recEE               != base.recEE)               j.put("recEE", current.recEE)
            if (current.recIOP              != base.recIOP)              j.put("recIOP", current.recIOP)
            if (current.recVU0              != base.recVU0)              j.put("recVU0", current.recVU0)
            if (current.recVU1              != base.recVU1)              j.put("recVU1", current.recVU1)
            if (current.enableFastmem       != base.enableFastmem)       j.put("enableFastmem", current.enableFastmem)
            if (current.vu1InlineFmacStall  != base.vu1InlineFmacStall)  j.put("vu1InlineFmacStall", current.vu1InlineFmacStall)
            if (current.vu1CrossBlockPState != base.vu1CrossBlockPState) j.put("vu1CrossBlockPState", current.vu1CrossBlockPState)
            if (current.vu1InlineDrainTestPipes != base.vu1InlineDrainTestPipes) j.put("vu1InlineDrainTestPipes", current.vu1InlineDrainTestPipes)
            if (current.vu1FmacInstanceRouting != base.vu1FmacInstanceRouting) j.put("vu1FmacInstanceRouting", current.vu1FmacInstanceRouting)
            if (current.hwMipmap            != base.hwMipmap)            j.put("hwMipmap", current.hwMipmap)
            if (current.accurateBlendingUnit!= base.accurateBlendingUnit)j.put("accurateBlendingUnit", current.accurateBlendingUnit)
            if (current.textureFiltering    != base.textureFiltering)    j.put("textureFiltering", current.textureFiltering)
            if (current.displayBilinear     != base.displayBilinear)     j.put("displayBilinear", current.displayBilinear)
            if (current.texturePreloading   != base.texturePreloading)   j.put("texturePreloading", current.texturePreloading)
            if (current.hardwareDownloadMode!= base.hardwareDownloadMode)j.put("hardwareDownloadMode", current.hardwareDownloadMode)
            if (current.tvShader            != base.tvShader)            j.put("tvShader", current.tvShader)
            if (current.shadeBoost          != base.shadeBoost)          j.put("shadeBoost", current.shadeBoost)
            if (current.shadeBoostBrightness != base.shadeBoostBrightness) j.put("shadeBoostBrightness", current.shadeBoostBrightness)
            if (current.shadeBoostContrast  != base.shadeBoostContrast)  j.put("shadeBoostContrast", current.shadeBoostContrast)
            if (current.shadeBoostSaturation != base.shadeBoostSaturation) j.put("shadeBoostSaturation", current.shadeBoostSaturation)
            if (current.shadeBoostGamma     != base.shadeBoostGamma)     j.put("shadeBoostGamma", current.shadeBoostGamma)
            if (current.fxaa                != base.fxaa)                j.put("fxaa", current.fxaa)
            if (current.shaderChainEnabled  != base.shaderChainEnabled)  j.put("shaderChainEnabled", current.shaderChainEnabled)
            if (current.shaderChainPreset   != base.shaderChainPreset)   j.put("shaderChainPreset", current.shaderChainPreset)
            if (current.shaderChainParams   != base.shaderChainParams)   j.put("shaderChainParams", shaderChainParamsToJson(current.shaderChainParams))
            if (current.casMode             != base.casMode)             j.put("casMode", current.casMode)
            if (current.sgsrSharpness       != base.sgsrSharpness)       j.put("sgsrSharpness", current.sgsrSharpness)
            if (current.casSharpness        != base.casSharpness)        j.put("casSharpness", current.casSharpness)
            if (current.loadTextureReplacements != base.loadTextureReplacements) j.put("loadTextureReplacements", current.loadTextureReplacements)
            if (current.loadTextureReplacementsAsync != base.loadTextureReplacementsAsync) j.put("loadTextureReplacementsAsync", current.loadTextureReplacementsAsync)
            if (current.precacheTextureReplacements != base.precacheTextureReplacements) j.put("precacheTextureReplacements", current.precacheTextureReplacements)
            if (current.dumpReplaceableTextures != base.dumpReplaceableTextures) j.put("dumpReplaceableTextures", current.dumpReplaceableTextures)
            if (current.osdShowTextureReplacements != base.osdShowTextureReplacements) j.put("osdShowTextureReplacements", current.osdShowTextureReplacements)
            if (current.osdShowFps != base.osdShowFps) j.put("osdShowFps", current.osdShowFps)
            if (current.osdScale != base.osdScale) j.put("osdScale", current.osdScale)
            if (current.osdColor != base.osdColor) j.put("osdColor", current.osdColor)
            if (current.vsyncEnable != base.vsyncEnable) j.put("vsyncEnable", current.vsyncEnable)
            if (current.osdShowVps != base.osdShowVps) j.put("osdShowVps", current.osdShowVps)
            if (current.osdShowSpeed != base.osdShowSpeed) j.put("osdShowSpeed", current.osdShowSpeed)
            if (current.osdShowCpu != base.osdShowCpu) j.put("osdShowCpu", current.osdShowCpu)
            if (current.osdShowGpu != base.osdShowGpu) j.put("osdShowGpu", current.osdShowGpu)
            if (current.osdShowResolution != base.osdShowResolution) j.put("osdShowResolution", current.osdShowResolution)
            if (current.osdShowGsStats != base.osdShowGsStats) j.put("osdShowGsStats", current.osdShowGsStats)
            if (current.osdShowFrameTimes != base.osdShowFrameTimes) j.put("osdShowFrameTimes", current.osdShowFrameTimes)
            if (current.osdShowHardwareInfo != base.osdShowHardwareInfo) j.put("osdShowHardwareInfo", current.osdShowHardwareInfo)
            if (current.osdShowMessages != base.osdShowMessages) j.put("osdShowMessages", current.osdShowMessages)
            if (current.osdShowGpuStats != base.osdShowGpuStats) j.put("osdShowGpuStats", current.osdShowGpuStats)
            if (current.osdShowVersion != base.osdShowVersion) j.put("osdShowVersion", current.osdShowVersion)
            if (current.osdShowSettings != base.osdShowSettings) j.put("osdShowSettings", current.osdShowSettings)
            if (current.osdShowInputs != base.osdShowInputs) j.put("osdShowInputs", current.osdShowInputs)
            if (current.autoFlush           != base.autoFlush)           j.put("autoFlush", current.autoFlush)
            if (current.halfPixelOffset     != base.halfPixelOffset)     j.put("halfPixelOffset", current.halfPixelOffset)
            if (current.limit24BitDepth     != base.limit24BitDepth)     j.put("limit24BitDepth", current.limit24BitDepth)
            if (current.manualUserHacks     != base.manualUserHacks)     j.put("manualUserHacks", current.manualUserHacks)
            if (current.textureInsideRt     != base.textureInsideRt)     j.put("textureInsideRt", current.textureInsideRt)
            if (current.nativeScaling       != base.nativeScaling)       j.put("nativeScaling", current.nativeScaling)
            if (current.roundSprite         != base.roundSprite)         j.put("roundSprite", current.roundSprite)
            if (current.bilinearUpscale     != base.bilinearUpscale)     j.put("bilinearUpscale", current.bilinearUpscale)
            if (current.gpuTargetClut       != base.gpuTargetClut)       j.put("gpuTargetClut", current.gpuTargetClut)
            if (current.cpuSpriteRenderBw   != base.cpuSpriteRenderBw)   j.put("cpuSpriteRenderBw", current.cpuSpriteRenderBw)
            if (current.cpuSpriteRenderLevel != base.cpuSpriteRenderLevel) j.put("cpuSpriteRenderLevel", current.cpuSpriteRenderLevel)
            if (current.alignSprite         != base.alignSprite)         j.put("alignSprite", current.alignSprite)
            if (current.mergeSprite         != base.mergeSprite)         j.put("mergeSprite", current.mergeSprite)
            if (current.forceEvenSpritePosition != base.forceEvenSpritePosition) j.put("forceEvenSpritePosition", current.forceEvenSpritePosition)
            if (current.unscaledPaletteDraw != base.unscaledPaletteDraw) j.put("unscaledPaletteDraw", current.unscaledPaletteDraw)
            if (current.textureOffsetX      != base.textureOffsetX)      j.put("textureOffsetX", current.textureOffsetX)
            if (current.textureOffsetY      != base.textureOffsetY)      j.put("textureOffsetY", current.textureOffsetY)
            if (current.gpuPaletteConversion != base.gpuPaletteConversion) j.put("gpuPaletteConversion", current.gpuPaletteConversion)
            if (current.cpuFramebufferConversion != base.cpuFramebufferConversion) j.put("cpuFramebufferConversion", current.cpuFramebufferConversion)
            if (current.readTargetsWhenClosing != base.readTargetsWhenClosing) j.put("readTargetsWhenClosing", current.readTargetsWhenClosing)
            if (current.disableDepthEmulation != base.disableDepthEmulation) j.put("disableDepthEmulation", current.disableDepthEmulation)
            if (current.disablePartialInvalidation != base.disablePartialInvalidation) j.put("disablePartialInvalidation", current.disablePartialInvalidation)
            if (current.disableSafeFeatures != base.disableSafeFeatures) j.put("disableSafeFeatures", current.disableSafeFeatures)
            if (current.disableRenderFixes  != base.disableRenderFixes)  j.put("disableRenderFixes", current.disableRenderFixes)
            if (current.preloadFrameData    != base.preloadFrameData)    j.put("preloadFrameData", current.preloadFrameData)
            if (current.estimateTextureRegion != base.estimateTextureRegion) j.put("estimateTextureRegion", current.estimateTextureRegion)
            if (current.drawBuffering        != base.drawBuffering)        j.put("drawBuffering", current.drawBuffering)
            if (current.cpuClutRender       != base.cpuClutRender)       j.put("cpuClutRender", current.cpuClutRender)
            if (current.triFilter           != base.triFilter)           j.put("triFilter", current.triFilter)
            if (current.maxAnisotropy       != base.maxAnisotropy)       j.put("maxAnisotropy", current.maxAnisotropy)
            if (current.gpuProfile          != base.gpuProfile)          j.put("gpuProfile", current.gpuProfile)
            return j
        }

        fun merge(base: Settings, overrides: JSONObject): Settings = Settings(
            eeCycleRate = if (overrides.has("eeCycleRate")) overrides.getInt("eeCycleRate") else base.eeCycleRate,
                ps3 = base.ps3.copy(
                    ppuDecoder = if (overrides.has("ps3PpuDecoder")) overrides.getInt("ps3PpuDecoder") else base.ps3.ppuDecoder,
                    spuDecoder = if (overrides.has("ps3SpuDecoder")) overrides.getInt("ps3SpuDecoder") else base.ps3.spuDecoder,
                    spuBlockSize = if (overrides.has("ps3SpuBlockSize")) overrides.getInt("ps3SpuBlockSize") else base.ps3.spuBlockSize,
                    ppuThreads = if (overrides.has("ps3PpuThreads")) overrides.getInt("ps3PpuThreads") else base.ps3.ppuThreads,
                    llvmThreads = if (overrides.has("ps3LlvmThreads")) overrides.getInt("ps3LlvmThreads") else base.ps3.llvmThreads,
                    preferredSpuThreads = if (overrides.has("ps3PreferredSpuThreads")) overrides.getInt("ps3PreferredSpuThreads") else base.ps3.preferredSpuThreads,
                    maxSpursThreads = if (overrides.has("ps3MaxSpursThreads")) overrides.getInt("ps3MaxSpursThreads") else base.ps3.maxSpursThreads,
                    spuLoopDetection = if (overrides.has("ps3SpuLoopDetection")) overrides.getBoolean("ps3SpuLoopDetection") else base.ps3.spuLoopDetection,
                    spuCache = if (overrides.has("ps3SpuCache")) overrides.getBoolean("ps3SpuCache") else base.ps3.spuCache,
                    llvmPrecompile = if (overrides.has("ps3LlvmPrecompile")) overrides.getBoolean("ps3LlvmPrecompile") else base.ps3.llvmPrecompile,
                    accurateSpuDma = if (overrides.has("ps3AccurateSpuDma")) overrides.getBoolean("ps3AccurateSpuDma") else base.ps3.accurateSpuDma,
                    savestateCompatibleMode = if (overrides.has("ps3SavestateCompatibleMode")) overrides.getBoolean("ps3SavestateCompatibleMode") else base.ps3.savestateCompatibleMode,
                    clocksScale = if (overrides.has("ps3ClocksScale")) overrides.getInt("ps3ClocksScale") else base.ps3.clocksScale,
                    resolutionScale = if (overrides.has("ps3ResolutionScale")) overrides.getInt("ps3ResolutionScale") else base.ps3.resolutionScale,
                    msaaMode = if (overrides.has("ps3MsaaMode")) overrides.getInt("ps3MsaaMode") else base.ps3.msaaMode,
                    audioCubebBackend = if (overrides.has("ps3AudioCubebBackend")) overrides.getInt("ps3AudioCubebBackend") else base.ps3.audioCubebBackend,
                    shaderMode = if (overrides.has("ps3ShaderMode")) overrides.getInt("ps3ShaderMode") else base.ps3.shaderMode,
                    frameGeneration = if (overrides.has("ps3FrameGeneration")) overrides.getInt("ps3FrameGeneration") else base.ps3.frameGeneration,
                    frameGenPerformance = if (overrides.has("ps3FrameGenPerformance")) overrides.getBoolean("ps3FrameGenPerformance") else base.ps3.frameGenPerformance,
                    frameGenFlowScale = if (overrides.has("ps3FrameGenFlowScale")) overrides.getInt("ps3FrameGenFlowScale") else base.ps3.frameGenFlowScale,
                    frameGenTargetRate = if (overrides.has("ps3FrameGenTargetRate")) overrides.getInt("ps3FrameGenTargetRate") else base.ps3.frameGenTargetRate,
                    writeColorBuffers = if (overrides.has("ps3WriteColorBuffers")) overrides.getBoolean("ps3WriteColorBuffers") else base.ps3.writeColorBuffers,
                    gpuTurbo = if (overrides.has("ps3GpuTurbo")) overrides.getBoolean("ps3GpuTurbo") else base.ps3.gpuTurbo,
                    silenceAllLogs = if (overrides.has("ps3SilenceAllLogs")) overrides.getBoolean("ps3SilenceAllLogs") else base.ps3.silenceAllLogs,
                    writeDepthBuffer = if (overrides.has("ps3WriteDepthBuffer")) overrides.getBoolean("ps3WriteDepthBuffer") else base.ps3.writeDepthBuffer,
                    readColorBuffers = if (overrides.has("ps3ReadColorBuffers")) overrides.getBoolean("ps3ReadColorBuffers") else base.ps3.readColorBuffers,
                    readDepthBuffer = if (overrides.has("ps3ReadDepthBuffer")) overrides.getBoolean("ps3ReadDepthBuffer") else base.ps3.readDepthBuffer,
                    strictRendering = if (overrides.has("ps3StrictRendering")) overrides.getBoolean("ps3StrictRendering") else base.ps3.strictRendering,
                    multithreadedRsx = if (overrides.has("ps3MultithreadedRsx")) overrides.getBoolean("ps3MultithreadedRsx") else base.ps3.multithreadedRsx,
                    disableZcull = if (overrides.has("ps3DisableZcull")) overrides.getBoolean("ps3DisableZcull") else base.ps3.disableZcull,
                    relaxedZcull = if (overrides.has("ps3RelaxedZcull")) overrides.getBoolean("ps3RelaxedZcull") else base.ps3.relaxedZcull,
                    gpuTextureScaling = if (overrides.has("ps3GpuTextureScaling")) overrides.getBoolean("ps3GpuTextureScaling") else base.ps3.gpuTextureScaling,
                    forceCpuBlit = if (overrides.has("ps3ForceCpuBlit")) overrides.getBoolean("ps3ForceCpuBlit") else base.ps3.forceCpuBlit,
                    shaderCompThreads = if (overrides.has("ps3ShaderCompThreads")) overrides.getInt("ps3ShaderCompThreads") else base.ps3.shaderCompThreads,
                    textureLodBias = if (overrides.has("ps3TextureLodBias")) overrides.getInt("ps3TextureLodBias") else base.ps3.textureLodBias,
                    vramLimitMb = if (overrides.has("ps3VramLimitMb")) overrides.getInt("ps3VramLimitMb") else base.ps3.vramLimitMb,
                    asyncTexStream = if (overrides.has("ps3AsyncTexStream")) overrides.getBoolean("ps3AsyncTexStream") else base.ps3.asyncTexStream,
                    audioFormat = if (overrides.has("ps3AudioFormat")) overrides.getInt("ps3AudioFormat") else base.ps3.audioFormat,
                    audioChannels = if (overrides.has("ps3AudioChannels")) overrides.getInt("ps3AudioChannels") else base.ps3.audioChannels,
                    audioTimeStretch = if (overrides.has("ps3AudioTimeStretch")) overrides.getBoolean("ps3AudioTimeStretch") else base.ps3.audioTimeStretch,
                    audioBuffering = if (overrides.has("ps3AudioBuffering")) overrides.getBoolean("ps3AudioBuffering") else base.ps3.audioBuffering,
                    audioBufferMs = if (overrides.has("ps3AudioBufferMs")) overrides.getInt("ps3AudioBufferMs") else base.ps3.audioBufferMs,
                    netEnabled = if (overrides.has("ps3NetEnabled")) overrides.getBoolean("ps3NetEnabled") else base.ps3.netEnabled,
                    psnStatus = if (overrides.has("ps3PsnStatus"))
                        (if (overrides.opt("ps3PsnStatus") is Boolean)
                            (if (overrides.getBoolean("ps3PsnStatus")) 1 else 0)
                        else overrides.getInt("ps3PsnStatus"))
                    else base.ps3.psnStatus,
                    upnpEnabled = if (overrides.has("ps3UpnpEnabled")) overrides.getBoolean("ps3UpnpEnabled") else base.ps3.upnpEnabled,
                    ipAddress = if (overrides.has("ps3IpAddress")) overrides.getString("ps3IpAddress") else base.ps3.ipAddress,
                    bindAddress = if (overrides.has("ps3BindAddress")) overrides.getString("ps3BindAddress") else base.ps3.bindAddress,
                    dnsAddress = if (overrides.has("ps3DnsAddress")) overrides.getString("ps3DnsAddress") else base.ps3.dnsAddress,
                    ipSwapList = if (overrides.has("ps3IpSwapList")) overrides.getString("ps3IpSwapList") else base.ps3.ipSwapList,
                    deriveMacFromPsid = if (overrides.has("ps3DeriveMacFromPsid")) overrides.getBoolean("ps3DeriveMacFromPsid") else base.ps3.deriveMacFromPsid,
                    psnCountry = if (overrides.has("ps3PsnCountry")) overrides.getString("ps3PsnCountry") else base.ps3.psnCountry,
                    clansEnabled = if (overrides.has("ps3ClansEnabled")) overrides.getBoolean("ps3ClansEnabled") else base.ps3.clansEnabled,
                    enterButtonAssign = if (overrides.has("ps3EnterButtonAssign")) overrides.getInt("ps3EnterButtonAssign") else base.ps3.enterButtonAssign,
                    consoleLanguage = if (overrides.has("ps3ConsoleLanguage")) overrides.getInt("ps3ConsoleLanguage") else base.ps3.consoleLanguage,
                    consoleRegion = if (overrides.has("ps3ConsoleRegion")) overrides.getInt("ps3ConsoleRegion") else base.ps3.consoleRegion,
                    keyboardType = if (overrides.has("ps3KeyboardType")) overrides.getInt("ps3KeyboardType") else base.ps3.keyboardType,
                    dateFormat = if (overrides.has("ps3DateFormat")) overrides.getInt("ps3DateFormat") else base.ps3.dateFormat,
                    timeFormat = if (overrides.has("ps3TimeFormat")) overrides.getInt("ps3TimeFormat") else base.ps3.timeFormat,
                    spuXFloat = if (overrides.has("ps3SpuXFloat")) overrides.getInt("ps3SpuXFloat") else base.ps3.spuXFloat,
                    accurateSpuRsv = if (overrides.has("ps3AccurateSpuRsv")) overrides.getBoolean("ps3AccurateSpuRsv") else base.ps3.accurateSpuRsv,
                    accurateCacheLine = if (overrides.has("ps3AccurateCacheLine")) overrides.getBoolean("ps3AccurateCacheLine") else base.ps3.accurateCacheLine,
                    accurateRsxRsv = if (overrides.has("ps3AccurateRsxRsv")) overrides.getBoolean("ps3AccurateRsxRsv") else base.ps3.accurateRsxRsv,
                    ppuRsvPriority = if (overrides.has("ps3PpuRsvPriority")) overrides.getBoolean("ps3PpuRsvPriority") else base.ps3.ppuRsvPriority,
                    spuVerification = if (overrides.has("ps3SpuVerification")) overrides.getBoolean("ps3SpuVerification") else base.ps3.spuVerification,
                    preciseSpuVerification = if (overrides.has("ps3PreciseSpuVerification")) overrides.getBoolean("ps3PreciseSpuVerification") else base.ps3.preciseSpuVerification,
                    ppuNanHandling = if (overrides.has("ps3PpuNanHandling")) overrides.getBoolean("ps3PpuNanHandling") else base.ps3.ppuNanHandling,
                    accurateDfma = if (overrides.has("ps3AccurateDfma")) overrides.getBoolean("ps3AccurateDfma") else base.ps3.accurateDfma,
                    setDazFtz = if (overrides.has("ps3SetDazFtz")) overrides.getBoolean("ps3SetDazFtz") else base.ps3.setDazFtz,
                    displayAspect = if (overrides.has("ps3DisplayAspect")) overrides.getInt("ps3DisplayAspect") else base.ps3.displayAspect,
                    overlayEnabled = if (overrides.has("ps3OverlayEnabled")) overrides.getBoolean("ps3OverlayEnabled") else base.ps3.overlayEnabled,
                    overlayDetail = if (overrides.has("ps3OverlayDetail")) overrides.getInt("ps3OverlayDetail") else base.ps3.overlayDetail,
                    overlayPosition = if (overrides.has("ps3OverlayPosition")) overrides.getInt("ps3OverlayPosition") else base.ps3.overlayPosition,
                    overlayFontSize = if (overrides.has("ps3OverlayFontSize")) overrides.getInt("ps3OverlayFontSize") else base.ps3.overlayFontSize,
                    overlayOpacity = if (overrides.has("ps3OverlayOpacity")) overrides.getInt("ps3OverlayOpacity") else base.ps3.overlayOpacity,
                    overlayFramerateGraph = if (overrides.has("ps3OverlayFramerateGraph")) overrides.getBoolean("ps3OverlayFramerateGraph") else base.ps3.overlayFramerateGraph,
                    overlayFrametimeGraph = if (overrides.has("ps3OverlayFrametimeGraph")) overrides.getBoolean("ps3OverlayFrametimeGraph") else base.ps3.overlayFrametimeGraph,
                    overlayBodyColor = if (overrides.has("ps3OverlayBodyColor")) overrides.getInt("ps3OverlayBodyColor") else base.ps3.overlayBodyColor,
                    overlayBodyBg = if (overrides.has("ps3OverlayBodyBg")) overrides.getInt("ps3OverlayBodyBg") else base.ps3.overlayBodyBg,
                    overlayTitleColor = if (overrides.has("ps3OverlayTitleColor")) overrides.getInt("ps3OverlayTitleColor") else base.ps3.overlayTitleColor,
                    overlayTitleBg = if (overrides.has("ps3OverlayTitleBg")) overrides.getInt("ps3OverlayTitleBg") else base.ps3.overlayTitleBg,
                    hleLwmutex = if (overrides.has("ps3HleLwmutex")) overrides.getBoolean("ps3HleLwmutex") else base.ps3.hleLwmutex,
                    sleepTimers = if (overrides.has("ps3SleepTimers")) overrides.getInt("ps3SleepTimers") else base.ps3.sleepTimers,
                    debugConsoleMode = if (overrides.has("ps3DebugConsoleMode")) overrides.getBoolean("ps3DebugConsoleMode") else base.ps3.debugConsoleMode,
                    resolution = if (overrides.has("ps3Resolution")) overrides.getInt("ps3Resolution") else base.ps3.resolution,
                    anisoFilter = if (overrides.has("ps3AnisoFilter")) overrides.getInt("ps3AnisoFilter") else base.ps3.anisoFilter,
                    audioRenderer = if (overrides.has("ps3AudioRenderer")) overrides.getInt("ps3AudioRenderer") else base.ps3.audioRenderer,
                ),
            eeCycleSkip = if (overrides.has("eeCycleSkip")) overrides.getInt("eeCycleSkip") else base.eeCycleSkip,
            eeClampMode = if (overrides.has("eeClampMode")) overrides.getInt("eeClampMode") else base.eeClampMode,
            vuClampMode = if (overrides.has("vuClampMode")) overrides.getInt("vuClampMode") else base.vuClampMode,
            mtvu = if (overrides.has("mtvu")) overrides.getBoolean("mtvu") else base.mtvu,
            vu1Instant = if (overrides.has("vu1Instant")) overrides.getBoolean("vu1Instant") else base.vu1Instant,
            vuFlagHack = if (overrides.has("vuFlagHack")) overrides.getBoolean("vuFlagHack") else base.vuFlagHack,
            fastCDVD = if (overrides.has("fastCDVD")) overrides.getBoolean("fastCDVD") else base.fastCDVD,
            intcStat = if (overrides.has("intcStat")) overrides.getBoolean("intcStat") else base.intcStat,
            waitLoop = if (overrides.has("waitLoop")) overrides.getBoolean("waitLoop") else base.waitLoop,
            vuNeonFusions = if (overrides.has("vuNeonFusions")) overrides.getBoolean("vuNeonFusions") else base.vuNeonFusions,
            vuDeferredWrites = if (overrides.has("vuDeferredWrites")) overrides.getBoolean("vuDeferredWrites") else base.vuDeferredWrites,
            vuSkipStallSim = if (overrides.has("vuSkipStallSim")) overrides.getBoolean("vuSkipStallSim") else base.vuSkipStallSim,
            frameLimitEnable = if (overrides.has("frameLimitEnable")) overrides.getBoolean("frameLimitEnable") else base.frameLimitEnable,
            nominalSpeedPercent = if (overrides.has("nominalSpeedPercent")) overrides.getInt("nominalSpeedPercent") else base.nominalSpeedPercent,
            fpsLimit = if (overrides.has("fpsLimit")) overrides.getInt("fpsLimit") else base.fpsLimit,
            frameSkip = if (overrides.has("frameSkip")) overrides.getInt("frameSkip") else base.frameSkip,
            audioVolume = if (overrides.has("audioVolume")) overrides.getInt("audioVolume") else base.audioVolume,
            audioMuted = if (overrides.has("audioMuted")) overrides.getBoolean("audioMuted") else base.audioMuted,
            audioSwapChannels = if (overrides.has("audioSwapChannels")) overrides.getBoolean("audioSwapChannels") else base.audioSwapChannels,
            audioTimeStretch = if (overrides.has("audioTimeStretch")) overrides.getBoolean("audioTimeStretch") else base.audioTimeStretch,
            audioBufferMs = if (overrides.has("audioBufferMs")) overrides.getInt("audioBufferMs") else base.audioBufferMs,
            audioOutputLatencyMs = if (overrides.has("audioOutputLatencyMs")) overrides.getInt("audioOutputLatencyMs") else base.audioOutputLatencyMs,
            audioFastForwardVolume = if (overrides.has("audioFastForwardVolume")) overrides.getInt("audioFastForwardVolume") else base.audioFastForwardVolume,
            spu2NeonReverb = if (overrides.has("spu2NeonReverb")) overrides.getBoolean("spu2NeonReverb") else base.spu2NeonReverb,
            audioOpenSLES = if (overrides.has("audioOpenSLES")) overrides.getBoolean("audioOpenSLES") else base.audioOpenSLES,
            spu2LightweightMix = if (overrides.has("spu2LightweightMix")) overrides.getBoolean("spu2LightweightMix") else base.spu2LightweightMix,
            renderer = if (overrides.has("renderer")) overrides.getString("renderer") else base.renderer,
            upscaleFloat = if (overrides.has("upscaleFloat")) overrides.getDouble("upscaleFloat").toFloat() else base.upscaleFloat,
            customDriverId = if (overrides.has("customDriverId")) overrides.getString("customDriverId") else base.customDriverId,
            orientation = if (overrides.has("orientation")) overrides.getInt("orientation") else base.orientation,
            portraitRenderTop = if (overrides.has("portraitRenderTop")) overrides.getBoolean("portraitRenderTop") else base.portraitRenderTop,
            landscapeRenderTop = if (overrides.has("landscapeRenderTop")) overrides.getBoolean("landscapeRenderTop") else base.landscapeRenderTop,
            autoProgressiveScan = if (overrides.has("autoProgressiveScan")) overrides.getBoolean("autoProgressiveScan") else base.autoProgressiveScan,
            affinityMode = if (overrides.has("affinityMode")) overrides.getInt("affinityMode") else base.affinityMode,
            framerateNtsc = if (overrides.has("framerateNtsc")) overrides.getDouble("framerateNtsc").toFloat() else base.framerateNtsc,
            frameratePal = if (overrides.has("frameratePal")) overrides.getDouble("frameratePal").toFloat() else base.frameratePal,
            enablePatches = if (overrides.has("enablePatches")) overrides.getBoolean("enablePatches") else base.enablePatches,
            enableCheats = if (overrides.has("enableCheats")) overrides.getBoolean("enableCheats") else base.enableCheats,
            enableWideScreenPatches = if (overrides.has("enableWideScreenPatches")) overrides.getBoolean("enableWideScreenPatches") else base.enableWideScreenPatches,
            enableNoInterlacingPatches = if (overrides.has("enableNoInterlacingPatches")) overrides.getBoolean("enableNoInterlacingPatches") else base.enableNoInterlacingPatches,
            enableFastBoot = if (overrides.has("enableFastBoot")) overrides.getBoolean("enableFastBoot") else base.enableFastBoot,
            hostFs = if (overrides.has("hostFs")) overrides.getBoolean("hostFs") else base.hostFs,
            enableGameFixes = if (overrides.has("enableGameFixes")) overrides.getBoolean("enableGameFixes") else base.enableGameFixes,
            gamefixSoftwareRendererFmv = if (overrides.has("gamefixSoftwareRendererFmv")) overrides.getBoolean("gamefixSoftwareRendererFmv") else base.gamefixSoftwareRendererFmv,
            gamefixSkipMpeg = if (overrides.has("gamefixSkipMpeg")) overrides.getBoolean("gamefixSkipMpeg") else base.gamefixSkipMpeg,
            gamefixEETiming = if (overrides.has("gamefixEETiming")) overrides.getBoolean("gamefixEETiming") else base.gamefixEETiming,
            gamefixInstantDma = if (overrides.has("gamefixInstantDma")) overrides.getBoolean("gamefixInstantDma") else base.gamefixInstantDma,
            gamefixBlitInternalFps = if (overrides.has("gamefixBlitInternalFps")) overrides.getBoolean("gamefixBlitInternalFps") else base.gamefixBlitInternalFps,
            gamefixFpuMul = if (overrides.has("gamefixFpuMul")) overrides.getBoolean("gamefixFpuMul") else base.gamefixFpuMul,
            gamefixOphFlag = if (overrides.has("gamefixOphFlag")) overrides.getBoolean("gamefixOphFlag") else base.gamefixOphFlag,
            gamefixGifFifo = if (overrides.has("gamefixGifFifo")) overrides.getBoolean("gamefixGifFifo") else base.gamefixGifFifo,
            gamefixDmaBusy = if (overrides.has("gamefixDmaBusy")) overrides.getBoolean("gamefixDmaBusy") else base.gamefixDmaBusy,
            gamefixVif1Stall = if (overrides.has("gamefixVif1Stall")) overrides.getBoolean("gamefixVif1Stall") else base.gamefixVif1Stall,
            gamefixIbit = if (overrides.has("gamefixIbit")) overrides.getBoolean("gamefixIbit") else base.gamefixIbit,
            gamefixFullVu0Sync = if (overrides.has("gamefixFullVu0Sync")) overrides.getBoolean("gamefixFullVu0Sync") else base.gamefixFullVu0Sync,
            gamefixVuAddSub = if (overrides.has("gamefixVuAddSub")) overrides.getBoolean("gamefixVuAddSub") else base.gamefixVuAddSub,
            gamefixVuOverflow = if (overrides.has("gamefixVuOverflow")) overrides.getBoolean("gamefixVuOverflow") else base.gamefixVuOverflow,
            gamefixXgkick = if (overrides.has("gamefixXgkick")) overrides.getBoolean("gamefixXgkick") else base.gamefixXgkick,
            gamefixGoemonTlb = if (overrides.has("gamefixGoemonTlb")) overrides.getBoolean("gamefixGoemonTlb") else base.gamefixGoemonTlb,
            gamefixVuSync = if (overrides.has("gamefixVuSync")) overrides.getBoolean("gamefixVuSync") else base.gamefixVuSync,
            skipDuplicateFrames = if (overrides.has("skipDuplicateFrames")) overrides.getBoolean("skipDuplicateFrames") else base.skipDuplicateFrames,
            eeFpuRoundMode = if (overrides.has("eeFpuRoundMode")) overrides.getInt("eeFpuRoundMode") else base.eeFpuRoundMode,
            vu0RoundMode = if (overrides.has("vu0RoundMode")) overrides.getInt("vu0RoundMode") else base.vu0RoundMode,
            vu1RoundMode = if (overrides.has("vu1RoundMode")) overrides.getInt("vu1RoundMode") else base.vu1RoundMode,
            screenOffsets = if (overrides.has("screenOffsets")) overrides.getBoolean("screenOffsets") else base.screenOffsets,
            showOverscan = if (overrides.has("showOverscan")) overrides.getBoolean("showOverscan") else base.showOverscan,
            antiBlur = if (overrides.has("antiBlur")) overrides.getBoolean("antiBlur") else base.antiBlur,
            disableInterlaceOffset = if (overrides.has("disableInterlaceOffset")) overrides.getBoolean("disableInterlaceOffset") else base.disableInterlaceOffset,
            syncToHostRefresh = if (overrides.has("syncToHostRefresh")) overrides.getBoolean("syncToHostRefresh") else base.syncToHostRefresh,
            disableFramebufferFetch = if (overrides.has("disableFramebufferFetch")) overrides.getBoolean("disableFramebufferFetch") else base.disableFramebufferFetch,
            hwRov = if (overrides.has("hwRov")) overrides.getBoolean("hwRov") else base.hwRov,
            hwAa1 = if (overrides.has("hwAa1")) overrides.getBoolean("hwAa1") else base.hwAa1,
            hwAat = false,
            adrenoFbFetch = if (overrides.has("adrenoFbFetch")) overrides.getBoolean("adrenoFbFetch") else base.adrenoFbFetch,
            coalesceRenderPasses = if (overrides.has("coalesceRenderPasses")) overrides.getBoolean("coalesceRenderPasses") else base.coalesceRenderPasses,
            forceMaliFbFetch = if (overrides.has("forceMaliFbFetch")) overrides.getBoolean("forceMaliFbFetch") else base.forceMaliFbFetch,
            useAngleOpenGL = if (overrides.has("useAngleOpenGL")) overrides.getBoolean("useAngleOpenGL") else base.useAngleOpenGL,
            overrideTextureBarriers = if (overrides.has("overrideTextureBarriers")) overrides.getInt("overrideTextureBarriers") else base.overrideTextureBarriers,
            gsBackThreadMode = if (overrides.has("gsBackThreadMode")) overrides.getInt("gsBackThreadMode") else base.gsBackThreadMode,
            disableVertexShaderExpand = if (overrides.has("disableVertexShaderExpand")) overrides.getBoolean("disableVertexShaderExpand") else base.disableVertexShaderExpand,
            useBlitSwapChain = if (overrides.has("useBlitSwapChain")) overrides.getBoolean("useBlitSwapChain") else base.useBlitSwapChain,
            disableShaderCache = if (overrides.has("disableShaderCache")) overrides.getBoolean("disableShaderCache") else base.disableShaderCache,
            hwAccurateAlphaTest = when {
                overrides.has("hwAccurateAlphaTest") -> overrides.getBoolean("hwAccurateAlphaTest")
                overrides.has("hwAat") -> overrides.getBoolean("hwAat")
                else -> base.hwAccurateAlphaTest
            },
            skipDrawStart = if (overrides.has("skipDrawStart")) overrides.getInt("skipDrawStart") else base.skipDrawStart,
            skipDrawEnd = if (overrides.has("skipDrawEnd")) overrides.getInt("skipDrawEnd") else base.skipDrawEnd,
            spinGpuReadbacks = if (overrides.has("spinGpuReadbacks")) overrides.getBoolean("spinGpuReadbacks") else base.spinGpuReadbacks,
            spinCpuReadbacks = if (overrides.has("spinCpuReadbacks")) overrides.getBoolean("spinCpuReadbacks") else base.spinCpuReadbacks,
            integerScaling = if (overrides.has("integerScaling")) overrides.getBoolean("integerScaling") else base.integerScaling,
            cropLeft = if (overrides.has("cropLeft")) overrides.getInt("cropLeft") else base.cropLeft,
            displayZoom = if (overrides.has("displayZoom")) overrides.getInt("displayZoom") else base.displayZoom,
            cropTop = if (overrides.has("cropTop")) overrides.getInt("cropTop") else base.cropTop,
            cropRight = if (overrides.has("cropRight")) overrides.getInt("cropRight") else base.cropRight,
            cropBottom = if (overrides.has("cropBottom")) overrides.getInt("cropBottom") else base.cropBottom,
            dithering = if (overrides.has("dithering")) overrides.getInt("dithering") else base.dithering,
            vsyncQueueSize = if (overrides.has("vsyncQueueSize")) overrides.getInt("vsyncQueueSize") else base.vsyncQueueSize,
            hwScaler = if (overrides.has("hwScaler")) overrides.getInt("hwScaler") else base.hwScaler,
            displayFitMode = if (overrides.has("displayFitMode")) overrides.getInt("displayFitMode") else base.displayFitMode,
            screenResOverride = if (overrides.has("screenResOverride")) overrides.getString("screenResOverride") else base.screenResOverride,
            autoFlushSw = if (overrides.has("autoFlushSw")) overrides.getBoolean("autoFlushSw") else base.autoFlushSw,
            mipmapSw = if (overrides.has("mipmapSw")) overrides.getBoolean("mipmapSw") else base.mipmapSw,
            swThreads = if (overrides.has("swThreads")) overrides.getInt("swThreads") else base.swThreads,
            swThreadsHeight = if (overrides.has("swThreadsHeight")) overrides.getInt("swThreadsHeight") else base.swThreadsHeight,
            aspectRatio = if (overrides.has("aspectRatio")) overrides.getInt("aspectRatio") else base.aspectRatio,
            fmvAspectRatio = if (overrides.has("fmvAspectRatio")) overrides.getInt("fmvAspectRatio") else base.fmvAspectRatio,
            customAspectRatio = if (overrides.has("customAspectRatio")) overrides.getDouble("customAspectRatio").toFloat() else base.customAspectRatio,
            deinterlaceMode = if (overrides.has("deinterlaceMode")) overrides.getInt("deinterlaceMode") else base.deinterlaceMode,
            dev9EthEnable = if (overrides.has("dev9EthEnable")) overrides.getBoolean("dev9EthEnable") else base.dev9EthEnable,
            dev9EthApi = if (overrides.has("dev9EthApi")) overrides.getString("dev9EthApi").ifEmpty { base.dev9EthApi } else base.dev9EthApi,
            localLinkHost = if (overrides.has("localLinkHost")) overrides.getBoolean("localLinkHost") else base.localLinkHost,
            localLinkAddress = if (overrides.has("localLinkAddress")) overrides.getString("localLinkAddress") else base.localLinkAddress,
            localLinkPort = if (overrides.has("localLinkPort")) overrides.getInt("localLinkPort") else base.localLinkPort,
            localLinkPeerId = if (overrides.has("localLinkPeerId")) overrides.getInt("localLinkPeerId") else base.localLinkPeerId,
            localLinkRoomCode = if (overrides.has("localLinkRoomCode")) overrides.getString("localLinkRoomCode") else base.localLinkRoomCode,
            dev9EthDevice = if (overrides.has("dev9EthDevice")) overrides.getString("dev9EthDevice").ifEmpty { base.dev9EthDevice } else base.dev9EthDevice,
            dev9EthLogDhcp = if (overrides.has("dev9EthLogDhcp")) overrides.getBoolean("dev9EthLogDhcp") else base.dev9EthLogDhcp,
            dev9EthLogDns = if (overrides.has("dev9EthLogDns")) overrides.getBoolean("dev9EthLogDns") else base.dev9EthLogDns,
            dev9InterceptDhcp = if (overrides.has("dev9InterceptDhcp")) overrides.getBoolean("dev9InterceptDhcp") else base.dev9InterceptDhcp,
            dev9Ps2Ip = if (overrides.has("dev9Ps2Ip")) overrides.getString("dev9Ps2Ip").ifEmpty { base.dev9Ps2Ip } else base.dev9Ps2Ip,
            dev9Mask = if (overrides.has("dev9Mask")) overrides.getString("dev9Mask").ifEmpty { base.dev9Mask } else base.dev9Mask,
            dev9Gateway = if (overrides.has("dev9Gateway")) overrides.getString("dev9Gateway").ifEmpty { base.dev9Gateway } else base.dev9Gateway,
            dev9Dns1 = if (overrides.has("dev9Dns1")) overrides.getString("dev9Dns1").ifEmpty { base.dev9Dns1 } else base.dev9Dns1,
            dev9Dns2 = if (overrides.has("dev9Dns2")) overrides.getString("dev9Dns2").ifEmpty { base.dev9Dns2 } else base.dev9Dns2,
            dev9AutoMask = if (overrides.has("dev9AutoMask")) overrides.getBoolean("dev9AutoMask") else base.dev9AutoMask,
            dev9AutoGateway = if (overrides.has("dev9AutoGateway")) overrides.getBoolean("dev9AutoGateway") else base.dev9AutoGateway,
            dev9ModeDns1 = if (overrides.has("dev9ModeDns1")) overrides.getString("dev9ModeDns1").ifEmpty { base.dev9ModeDns1 } else base.dev9ModeDns1,
            dev9ModeDns2 = if (overrides.has("dev9ModeDns2")) overrides.getString("dev9ModeDns2").ifEmpty { base.dev9ModeDns2 } else base.dev9ModeDns2,
            dev9EthHosts = if (overrides.has("dev9EthHosts")) {
                overrides.optJSONArray("dev9EthHosts")?.let { array ->
                    buildList {
                        repeat(array.length()) { index ->
                            array.optJSONObject(index)?.let { host ->
                                add(
                                    Dev9HostMapping(
                                        url = host.optString("url"),
                                        ip = host.optString("ip", "0.0.0.0"),
                                        enabled = host.optBoolean("enabled", true),
                                    ),
                                )
                            }
                        }
                    }
                } ?: base.dev9EthHosts
            } else base.dev9EthHosts,
            dev9HddEnable = if (overrides.has("dev9HddEnable")) overrides.getBoolean("dev9HddEnable") else base.dev9HddEnable,
            dev9HddFile = if (overrides.has("dev9HddFile")) overrides.getString("dev9HddFile").ifEmpty { base.dev9HddFile } else base.dev9HddFile,
            memoryCardSlot1Enabled = if (overrides.has("memoryCardSlot1Enabled")) overrides.getBoolean("memoryCardSlot1Enabled") else base.memoryCardSlot1Enabled,
            memoryCardSlot1Filename = if (overrides.has("memoryCardSlot1Filename")) overrides.getString("memoryCardSlot1Filename").ifEmpty { base.memoryCardSlot1Filename } else base.memoryCardSlot1Filename,
            biosFilename = if (overrides.has("biosFilename")) overrides.getString("biosFilename") else base.biosFilename,
            memoryCardSlot2Enabled = if (overrides.has("memoryCardSlot2Enabled")) overrides.getBoolean("memoryCardSlot2Enabled") else base.memoryCardSlot2Enabled,
            memoryCardSlot2Filename = if (overrides.has("memoryCardSlot2Filename")) overrides.getString("memoryCardSlot2Filename").ifEmpty { base.memoryCardSlot2Filename } else base.memoryCardSlot2Filename,
            usbKeyboard = if (overrides.has("usbKeyboard")) overrides.getBoolean("usbKeyboard") else base.usbKeyboard,
            recEE = if (overrides.has("recEE")) overrides.getBoolean("recEE") else base.recEE,
            recIOP = if (overrides.has("recIOP")) overrides.getBoolean("recIOP") else base.recIOP,
            recVU0 = if (overrides.has("recVU0")) overrides.getBoolean("recVU0") else base.recVU0,
            recVU1 = if (overrides.has("recVU1")) overrides.getBoolean("recVU1") else base.recVU1,
            enableFastmem = if (overrides.has("enableFastmem")) overrides.getBoolean("enableFastmem") else base.enableFastmem,
            useMacEE = true,
            useMacIOP = true,
            useMacVU0 = true,
            useMacVU1 = true,
            vu1InlineFmacStall = if (overrides.has("vu1InlineFmacStall")) overrides.getBoolean("vu1InlineFmacStall") else base.vu1InlineFmacStall,
            vu1CrossBlockPState = if (overrides.has("vu1CrossBlockPState")) overrides.getBoolean("vu1CrossBlockPState") else base.vu1CrossBlockPState,
            vu1InlineDrainTestPipes = if (overrides.has("vu1InlineDrainTestPipes")) overrides.getBoolean("vu1InlineDrainTestPipes") else base.vu1InlineDrainTestPipes,
            vu1FmacInstanceRouting = if (overrides.has("vu1FmacInstanceRouting")) overrides.getBoolean("vu1FmacInstanceRouting") else base.vu1FmacInstanceRouting,
            hwMipmap = if (overrides.has("hwMipmap")) overrides.getBoolean("hwMipmap") else base.hwMipmap,
            accurateBlendingUnit = if (overrides.has("accurateBlendingUnit")) overrides.getInt("accurateBlendingUnit") else base.accurateBlendingUnit,
            textureFiltering = if (overrides.has("textureFiltering")) overrides.getInt("textureFiltering") else base.textureFiltering,
            displayBilinear = if (overrides.has("displayBilinear")) overrides.getInt("displayBilinear") else base.displayBilinear,
            texturePreloading = if (overrides.has("texturePreloading")) overrides.getInt("texturePreloading") else base.texturePreloading,
            hardwareDownloadMode = if (overrides.has("hardwareDownloadMode")) overrides.getInt("hardwareDownloadMode") else base.hardwareDownloadMode,
            tvShader = if (overrides.has("tvShader")) overrides.getInt("tvShader") else base.tvShader,
            shadeBoost = if (overrides.has("shadeBoost")) overrides.getBoolean("shadeBoost") else base.shadeBoost,
            shadeBoostBrightness = if (overrides.has("shadeBoostBrightness")) overrides.getInt("shadeBoostBrightness") else base.shadeBoostBrightness,
            shadeBoostContrast = if (overrides.has("shadeBoostContrast")) overrides.getInt("shadeBoostContrast") else base.shadeBoostContrast,
            shadeBoostSaturation = if (overrides.has("shadeBoostSaturation")) overrides.getInt("shadeBoostSaturation") else base.shadeBoostSaturation,
            shadeBoostGamma = if (overrides.has("shadeBoostGamma")) overrides.getInt("shadeBoostGamma") else base.shadeBoostGamma,
            fxaa = if (overrides.has("fxaa")) overrides.getBoolean("fxaa") else base.fxaa,
            shaderChainEnabled = if (overrides.has("shaderChainEnabled")) overrides.getBoolean("shaderChainEnabled") else base.shaderChainEnabled,
            shaderChainPreset = if (overrides.has("shaderChainPreset")) overrides.getString("shaderChainPreset") else base.shaderChainPreset,
            // Replaces the global map wholesale rather than merging per parameter: a
            // per-game tweak means "this game's chain looks like THIS", and merging would
            // let a later global edit leak into a game the user had already dialled in.
            shaderChainParams = if (overrides.has("shaderChainParams")) {
                shaderChainParamsFromJson(overrides.optJSONObject("shaderChainParams"))
            } else base.shaderChainParams,
            casMode = if (overrides.has("casMode")) overrides.getInt("casMode") else base.casMode,
            sgsrSharpness = if (overrides.has("sgsrSharpness")) overrides.getInt("sgsrSharpness") else base.sgsrSharpness,
            casSharpness = if (overrides.has("casSharpness")) overrides.getInt("casSharpness") else base.casSharpness,
            loadTextureReplacements = if (overrides.has("loadTextureReplacements")) overrides.getBoolean("loadTextureReplacements") else base.loadTextureReplacements,
            loadTextureReplacementsAsync = if (overrides.has("loadTextureReplacementsAsync")) overrides.getBoolean("loadTextureReplacementsAsync") else base.loadTextureReplacementsAsync,
            precacheTextureReplacements = if (overrides.has("precacheTextureReplacements")) overrides.getBoolean("precacheTextureReplacements") else base.precacheTextureReplacements,
            dumpReplaceableTextures = if (overrides.has("dumpReplaceableTextures")) overrides.getBoolean("dumpReplaceableTextures") else base.dumpReplaceableTextures,
            osdShowTextureReplacements = if (overrides.has("osdShowTextureReplacements")) overrides.getBoolean("osdShowTextureReplacements") else base.osdShowTextureReplacements,
            osdShowFps = if (overrides.has("osdShowFps")) overrides.getBoolean("osdShowFps") else base.osdShowFps,
            osdScale = if (overrides.has("osdScale")) overrides.getInt("osdScale") else base.osdScale,
            osdColor = if (overrides.has("osdColor")) overrides.getInt("osdColor") else base.osdColor,
            vsyncEnable = if (overrides.has("vsyncEnable")) overrides.getBoolean("vsyncEnable") else base.vsyncEnable,
            osdShowVps = if (overrides.has("osdShowVps")) overrides.getBoolean("osdShowVps") else base.osdShowVps,
            osdShowSpeed = if (overrides.has("osdShowSpeed")) overrides.getBoolean("osdShowSpeed") else base.osdShowSpeed,
            osdShowCpu = if (overrides.has("osdShowCpu")) overrides.getBoolean("osdShowCpu") else base.osdShowCpu,
            osdShowGpu = if (overrides.has("osdShowGpu")) overrides.getBoolean("osdShowGpu") else base.osdShowGpu,
            osdShowResolution = if (overrides.has("osdShowResolution")) overrides.getBoolean("osdShowResolution") else base.osdShowResolution,
            osdShowGsStats = if (overrides.has("osdShowGsStats")) overrides.getBoolean("osdShowGsStats") else base.osdShowGsStats,
            osdShowFrameTimes = if (overrides.has("osdShowFrameTimes")) overrides.getBoolean("osdShowFrameTimes") else base.osdShowFrameTimes,
            osdShowHardwareInfo = if (overrides.has("osdShowHardwareInfo")) overrides.getBoolean("osdShowHardwareInfo") else base.osdShowHardwareInfo,
            osdShowMessages = if (overrides.has("osdShowMessages")) overrides.getBoolean("osdShowMessages") else base.osdShowMessages,
            osdShowGpuStats = if (overrides.has("osdShowGpuStats")) overrides.getBoolean("osdShowGpuStats") else base.osdShowGpuStats,
            osdShowVersion = if (overrides.has("osdShowVersion")) overrides.getBoolean("osdShowVersion") else base.osdShowVersion,
            osdShowSettings = if (overrides.has("osdShowSettings")) overrides.getBoolean("osdShowSettings") else base.osdShowSettings,
            osdShowInputs = if (overrides.has("osdShowInputs")) overrides.getBoolean("osdShowInputs") else base.osdShowInputs,
            autoFlush = if (overrides.has("autoFlush")) overrides.getInt("autoFlush") else base.autoFlush,
            halfPixelOffset = if (overrides.has("halfPixelOffset")) overrides.getInt("halfPixelOffset") else base.halfPixelOffset,
            limit24BitDepth = if (overrides.has("limit24BitDepth")) overrides.getInt("limit24BitDepth") else base.limit24BitDepth,
            manualUserHacks = if (overrides.has("manualUserHacks")) overrides.getBoolean("manualUserHacks") else base.manualUserHacks,
            textureInsideRt = if (overrides.has("textureInsideRt")) overrides.getInt("textureInsideRt") else base.textureInsideRt,
            nativeScaling = if (overrides.has("nativeScaling")) overrides.getInt("nativeScaling") else base.nativeScaling,
            roundSprite = if (overrides.has("roundSprite")) overrides.getInt("roundSprite") else base.roundSprite,
            bilinearUpscale = if (overrides.has("bilinearUpscale")) overrides.getInt("bilinearUpscale") else base.bilinearUpscale,
            gpuTargetClut = if (overrides.has("gpuTargetClut")) overrides.getInt("gpuTargetClut") else base.gpuTargetClut,
            cpuSpriteRenderBw = if (overrides.has("cpuSpriteRenderBw")) overrides.getInt("cpuSpriteRenderBw") else base.cpuSpriteRenderBw,
            cpuSpriteRenderLevel = if (overrides.has("cpuSpriteRenderLevel")) overrides.getInt("cpuSpriteRenderLevel") else base.cpuSpriteRenderLevel,
            alignSprite = if (overrides.has("alignSprite")) overrides.getBoolean("alignSprite") else base.alignSprite,
            mergeSprite = if (overrides.has("mergeSprite")) overrides.getBoolean("mergeSprite") else base.mergeSprite,
            forceEvenSpritePosition = if (overrides.has("forceEvenSpritePosition")) overrides.getBoolean("forceEvenSpritePosition") else base.forceEvenSpritePosition,
            unscaledPaletteDraw = if (overrides.has("unscaledPaletteDraw")) overrides.getBoolean("unscaledPaletteDraw") else base.unscaledPaletteDraw,
            textureOffsetX = if (overrides.has("textureOffsetX")) overrides.getInt("textureOffsetX") else base.textureOffsetX,
            textureOffsetY = if (overrides.has("textureOffsetY")) overrides.getInt("textureOffsetY") else base.textureOffsetY,
            gpuPaletteConversion = if (overrides.has("gpuPaletteConversion")) overrides.getBoolean("gpuPaletteConversion") else base.gpuPaletteConversion,
            cpuFramebufferConversion = if (overrides.has("cpuFramebufferConversion")) overrides.getBoolean("cpuFramebufferConversion") else base.cpuFramebufferConversion,
            readTargetsWhenClosing = if (overrides.has("readTargetsWhenClosing")) overrides.getBoolean("readTargetsWhenClosing") else base.readTargetsWhenClosing,
            disableDepthEmulation = if (overrides.has("disableDepthEmulation")) overrides.getBoolean("disableDepthEmulation") else base.disableDepthEmulation,
            disablePartialInvalidation = if (overrides.has("disablePartialInvalidation")) overrides.getBoolean("disablePartialInvalidation") else base.disablePartialInvalidation,
            disableSafeFeatures = if (overrides.has("disableSafeFeatures")) overrides.getBoolean("disableSafeFeatures") else base.disableSafeFeatures,
            disableRenderFixes = if (overrides.has("disableRenderFixes")) overrides.getBoolean("disableRenderFixes") else base.disableRenderFixes,
            preloadFrameData = if (overrides.has("preloadFrameData")) overrides.getBoolean("preloadFrameData") else base.preloadFrameData,
            estimateTextureRegion = if (overrides.has("estimateTextureRegion")) overrides.getBoolean("estimateTextureRegion") else base.estimateTextureRegion,
            drawBuffering = if (overrides.has("drawBuffering")) overrides.getBoolean("drawBuffering") else base.drawBuffering,
            cpuClutRender = if (overrides.has("cpuClutRender")) overrides.getInt("cpuClutRender") else base.cpuClutRender,
            triFilter = if (overrides.has("triFilter")) overrides.getInt("triFilter") else base.triFilter,
            maxAnisotropy = if (overrides.has("maxAnisotropy")) overrides.getInt("maxAnisotropy") else base.maxAnisotropy,
            gpuProfile = if (overrides.has("gpuProfile")) overrides.getInt("gpuProfile") else base.gpuProfile,
        )
    }
}

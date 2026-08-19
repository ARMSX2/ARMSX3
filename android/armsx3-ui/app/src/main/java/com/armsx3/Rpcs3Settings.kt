package com.armsx3

import net.rpcsx.RPCSX

/**
 * ARMSX2 UI setting -> RPCS3 config node.
 *
 * THIS FILE IS THE WHOLE POINT OF THE SETTINGS PORT. Every path and every enum
 * string below was read out of RPCS3's own config tree
 * (rpcs3/Emu/system_config.h, 258 entries) and its serialisers
 * (system_config_types.cpp). They are matched LITERALLY by the core:
 *
 *   _rpcsx_settingsSet(path, jsonValue)
 *     -> find_cfg_node() splits `path` on "@@" and compares each element
 *        against cfg node names with ==
 *     -> cfg::_enum::from_string() compares the value against the exact string
 *        its fmt_class_string formatter emits
 *
 * So "Video@@Resolution Scale" works and "Video@@ResolutionScale" silently does
 * nothing - from_string returns false, settingsSet returns false, and the UI
 * shows the old value back with no error. Same for enum values: "16:9" is
 * right, "16_9" is not. Do not guess names here; read them out of the tree.
 *
 * Section names are node names and DO contain spaces and slashes:
 *   Core, VFS, Video, Video@@Vulkan, Video@@Performance Overlay,
 *   Audio, Input/Output, System, Net, Savestate, Miscellaneous
 */
object Rpcs3Settings {

    // ---- value encoding -------------------------------------------------
    // settingsSet takes JSON: bools and numbers bare, enums and strings quoted.

    private fun setRaw(path: String, jsonValue: String): Boolean =
        runCatching { RPCSX.instance.settingsSet(path, jsonValue) }.getOrDefault(false)

    fun setBool(path: String, value: Boolean) = setRaw(path, if (value) "true" else "false")
    fun setInt(path: String, value: Int) = setRaw(path, value.toString())
    fun setFloat(path: String, value: Float) = setRaw(path, value.toString())
    fun setEnum(path: String, value: String) =
        setRaw(path, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

    fun setString(path: String, value: String) = setEnum(path, value)

    // ---- paths ----------------------------------------------------------

    private const val CORE = "Core"
    private const val VIDEO = "Video"
    private const val VULKAN = "Video@@Vulkan"
    private const val OVERLAY = "Video@@Performance Overlay"
    private const val AUDIO = "Audio"
    private const val IO = "Input/Output"
    private const val SAVESTATE = "Savestate"
    private const val MISC = "Miscellaneous"
    private const val SYSTEM = "System"

    // ---- Console (System) -----------------------------------------------
    //
    // What the emulated console reports to games: cellSysutil reads these, so they change the
    // language a game picks, which region it thinks it is in, and how it formats dates.
    //
    // Every one is a cfg::_enum, which serialises by NAME, so these tables must match the core's
    // own formatters exactly -- cellSysutil.cpp, KeyboardHandler.cpp, system_config_types.cpp.
    // Two traps live here, hence tables rather than arithmetic on the index:
    //
    //   - Order is by NUMERIC enum value, not by the order the formatter prints. The formatter
    //     lists English (UK) before Portuguese (Brazil); the values are 17 for Brazil and 18 for
    //     the UK, so following the formatter would silently swap two languages.
    //   - License Area is NOT contiguous: J..C are 0-5 and OTHER is 100. Anything mapping an
    //     index onto a value would land on 6 and be rejected or clamped.
    //
    // The index is a position in these lists and nothing else; the core only ever sees the name.

    private val CONSOLE_LANGUAGES = listOf(
        "Japanese", "English (US)", "French", "Spanish", "German", "Italian", "Dutch",
        "Portuguese (Portugal)", "Russian", "Korean", "Chinese (Traditional)",
        "Chinese (Simplified)", "Finnish", "Swedish", "Danish", "Norwegian", "Polish",
        "Portuguese (Brazil)", "English (UK)", "Turkish",
    )

    private val CONSOLE_REGIONS = listOf("SCEJ", "SCEA", "SCEE", "SCEH", "SCEK", "SCH", "Other")

    private val KEYBOARD_TYPES = listOf(
        "English keyboard (US standard)", "Japanese keyboard", "Japanese keyboard (Kana state)",
        "German keyboard", "Spanish keyboard", "French keyboard", "Italian keyboard",
        "Dutch keyboard", "Portuguese keyboard (Portugal)", "Russian keyboard",
        "English keyboard (UK standard)", "Korean keyboard", "Norwegian keyboard",
        "Finnish keyboard", "Danish keyboard", "Swedish keyboard",
        "Chinese keyboard (Traditional)", "Chinese keyboard (Simplified)",
        "French keyboard (Switzerland)", "German keyboard (Switzerland)",
        "French keyboard (Canada)", "French keyboard (Belgium)", "Polish keyboard",
        "Portuguese keyboard (Brazil)", "Turkish keyboard",
    )

    private val DATE_FORMATS = listOf("yyyymmdd", "ddmmyyyy", "mmddyyyy")
    private val TIME_FORMATS = listOf("clock12", "clock24")
    private val ENTER_BUTTONS = listOf("Enter with circle", "Enter with cross")

    /** Names, in the order the pickers show them, so the UI never repeats these tables. */
    fun consoleLanguageNames(): List<String> = CONSOLE_LANGUAGES
    fun consoleRegionNames(): List<String> = CONSOLE_REGIONS
    fun keyboardTypeNames(): List<String> = KEYBOARD_TYPES

    private fun setIndexedEnum(path: String, names: List<String>, index: Int, fallback: Int) =
        setEnum(path, names.getOrElse(index) { names[fallback] })

    fun setConsoleLanguage(index: Int) = setIndexedEnum("$SYSTEM@@Language", CONSOLE_LANGUAGES, index, 1)
    fun setConsoleRegion(index: Int) = setIndexedEnum("$SYSTEM@@License Area", CONSOLE_REGIONS, index, 1)
    fun setKeyboardType(index: Int) = setIndexedEnum("$SYSTEM@@Keyboard Type", KEYBOARD_TYPES, index, 0)

    /** Which keyboard handler cellKb is served by. "Basic" is the Android handler
     *  (virtual_keyboard_handler); "Null" reports no keyboard attached, which is the
     *  default and what every build before this one always used. Read once, during
     *  Emulator::Load. */
    fun setKeyboardHandler(enabled: Boolean) = setEnum("$IO@@Keyboard", if (enabled) "Basic" else "Null")
    fun setDateFormat(index: Int) = setIndexedEnum("$SYSTEM@@Date Format", DATE_FORMATS, index, 1)
    fun setTimeFormat(index: Int) = setIndexedEnum("$SYSTEM@@Time Format", TIME_FORMATS, index, 1)
    fun setEnterButtonAssign(index: Int) =
        setIndexedEnum("$SYSTEM@@Enter button assignment", ENTER_BUTTONS, index, 1)

    // ---- Renderer -------------------------------------------------------

    /**
     * video_renderer: exactly "Null" | "OpenGL" | "Vulkan".
     *
     * The UI sends ARMSX2's own lowercase ids ("vulkan", "opengl", "auto",
     * "software"), which from_string would reject outright -- the renderer
     * picker would appear to do nothing. So normalise here.
     *
     * "auto" and "software" have no RPCS3 equivalent (there is no PS3 software
     * rasteriser), and both mean "just render" to the user, so they resolve to
     * Vulkan -- the only backend currently complete on Android.
     */
    fun setRenderer(name: String): Boolean {
        val renderer = when (name.trim().lowercase()) {
            "vulkan", "auto", "software" -> "Vulkan"
            "opengl", "gl", "gles", "angle" -> "OpenGL"
            "null", "none" -> "Null"
            else -> "Vulkan"
        }
        return setEnum("$VIDEO@@Renderer", renderer)
    }

    /**
     * ARMSX2 sends an upscale MULTIPLIER (1.0 = native). RPCS3 wants a
     * PERCENTAGE (100 = native), clamped 25..800 by the config itself - values
     * outside that are rejected, not clamped, so clamp here.
     */
    fun setUpscaleMultiplier(multiplier: Float) =
        setInt("$VIDEO@@Resolution Scale", (multiplier * 100f).toInt().coerceIn(25, 800))

    /** video_aspect: exactly "4:3" or "16:9". */
    fun setAspectRatio(sixteenNine: Boolean) =
        setEnum("$VIDEO@@Aspect ratio", if (sixteenNine) "16:9" else "4:3")

    /**
     * frame_limit_type is an ENUM of preset rates, not a free integer:
     * Off | 30 | 50 | 60 | 120 | Display | Auto | PS3 Native | Infinite
     */
    fun setFrameLimitMode(mode: String): Boolean {
        val ok = setEnum("$VIDEO@@Frame limit", mode)
        return ok
    }

    /**
     * ARMSX2's Display FPS Cap slider is free-form (0..60, any value), which the
     * enum above cannot express -- snapping 47 to "50" would silently give the
     * user a rate they did not pick.
     *
     * So: exact presets use the enum, and everything else goes to Second Frame
     * Limit, which IS a free float. Only one of the two is ever armed, otherwise
     * the lower of the pair wins and the slider appears to stick.
     */
    fun setFrameLimit(fps: Int) {
        // Remembered so the PCSX2 FrameLimitEnable key can restore the rate the user actually
        // picked instead of overwriting it -- see setFrameLimitEnabled.
        lastExplicitFpsCap = fps

        val preset = when (fps) {
            30, 50, 60, 120 -> fps.toString()
            else -> null
        }
        if (fps <= 0) {
            setFrameLimitMode("Off")
            setSecondFrameLimit(0f)
        } else if (preset != null) {
            setFrameLimitMode(preset)
            setSecondFrameLimit(0f)
        } else {
            setFrameLimitMode("Off")
            setSecondFrameLimit(fps.toFloat())
        }
    }

    /** The last rate the explicit FPS-cap control asked for. 0 = no cap set. */
    @Volatile
    private var lastExplicitFpsCap: Int = 0

    /**
     * PCSX2's "limiter on/off" toggle, which shares one RPCS3 node with the explicit FPS cap.
     *
     * It used to write "Auto" whenever it was on. Both are pushed on every apply and this one goes
     * LAST, so a user who picked 30 or 60 got "Auto" a moment later and the cap never took effect
     * -- reported as "FPS cap (30/60) doesn't seem to apply". Exactly the collision the
     * SkipDuplicateFrames and IntegerScaling comments below describe, in the node above them.
     *
     * Dropping the key was not an option: unlike those two it has a visible row behind it, so it
     * would have gone inert. Instead it defers to the explicit cap, which makes the result
     * independent of the order the two are emitted in -- the property the other fixes rely on
     * applyToInner's ordering for.
     */
    fun setFrameLimitEnabled(enabled: Boolean) {

        if (!enabled) {
            setFrameLimitMode("Off")
            setSecondFrameLimit(0f)
            return
        }

        val cap = lastExplicitFpsCap
        if (cap > 0) {
            setFrameLimit(cap)
        } else {
            // No explicit rate to honour, so "limit on" means the console's own pacing.
            setFrameLimitMode("Auto")
        }
    }

    /** Free-form secondary cap (0 disables). Use for values the enum cannot express. */
    fun setSecondFrameLimit(fps: Float) =
        setFloat("$VIDEO@@Second Frame Limit", fps.coerceIn(0f, 1000f))

    fun setDisableShaderCache(disabled: Boolean) =
        setBool("$VIDEO@@Disable On-Disk Shader Cache", disabled)

    fun setVsync(enabled: Boolean) =
        setEnum("$VIDEO@@VSync Mode", if (enabled) "Full" else "Disabled")

    fun setFrameSkip(skip: Int) {
        setBool("$VIDEO@@Enable Frame Skip", skip > 0)
        if (skip > 0) setInt("$VIDEO@@Consecutive Frames To Skip", skip.coerceIn(1, 8))
    }

    fun setVblankRate(hz: Int) = setInt("$VIDEO@@Vblank Rate", hz.coerceIn(1, 6000))

    fun setAnisotropicFilter(level: Int) =
        setInt("$VIDEO@@Anisotropic Filter Override", level.coerceIn(0, 16))

    fun setStretchToDisplay(enabled: Boolean) = setBool("$VIDEO@@Stretch To Display Area", enabled)

    /** Output aspect override in permille (1778 = 16:9), 0 = follow the game. */
    fun setDisplayAspectPermille(permille: Int) =
        setInt("$VIDEO@@Display Aspect Override", permille.coerceIn(0, 4000))

    fun setWriteColorBuffers(enabled: Boolean) = setBool("$VIDEO@@Write Color Buffers", enabled)

    fun setOutputScaling(mode: String) = setEnum("$VIDEO@@Output Scaling Mode", mode)

    fun setShaderPresetPath(path: String) = setString("$VIDEO@@Shader Preset Path", path)

    /** FidelityFX CAS sharpening, 0..100. Only applies with FSR output scaling. */
    fun setCasSharpening(percent: Int) =
        setInt("$VIDEO@@FidelityFX CAS Sharpening Intensity", percent.coerceIn(0, 100))

    fun setVramLimitMb(mb: Int) =
        setInt("$VULKAN@@VRAM allocation limit (MB)", mb.coerceIn(256, 65536))

    fun setAsyncTextureStreaming(enabled: Boolean) =
        setBool("$VULKAN@@Asynchronous Texture Streaming", enabled)

    // ---- Performance overlay --------------------------------------------
    //
    // ARMSX2 has a boolean per element (osdShowFPS, osdShowCPU, ...). RPCS3
    // does NOT: it has one Enabled flag plus a detail_level enum that decides
    // which elements appear. So the per-element toggles collapse onto this.

    fun setOverlayEnabled(enabled: Boolean) = setBool("$OVERLAY@@Enabled", enabled)

    /** detail_level: None | Minimal | Low | Medium | High */
    fun setOverlayDetail(level: String) = setEnum("$OVERLAY@@Detail level", level)

    fun setOverlayFramerateGraph(enabled: Boolean) =
        setBool("$OVERLAY@@Enable Framerate Graph", enabled)

    fun setOverlayFrametimeGraph(enabled: Boolean) =
        setBool("$OVERLAY@@Enable Frametime Graph", enabled)

    fun setOverlayFontSize(px: Int) = setInt("$OVERLAY@@Font size (px)", px.coerceIn(4, 36))

    fun setOverlayOpacity(percent: Int) = setInt("$OVERLAY@@Opacity (%)", percent.coerceIn(0, 100))

    // ---- Audio ----------------------------------------------------------

    /** Master Volume is 0..200 (percent), so "muted" is volume 0. */
    fun setMasterVolume(percent: Int) = setInt("$AUDIO@@Master Volume", percent.coerceIn(0, 200))

    fun setAudioFormat(format: String) = setEnum("$AUDIO@@Audio Format", format)

    fun setAudioBuffering(enabled: Boolean) = setBool("$AUDIO@@Enable Buffering", enabled)

    /**
     * Desired buffer duration in ms. cfg::_int<4, 250> -- out-of-range values
     * are REJECTED, not clamped, so the clamp has to match exactly. ARMSX2's
     * slider goes to 200, which fits.
     */
    fun setAudioBufferDuration(ms: Int) =
        setInt("$AUDIO@@Desired Audio Buffer Duration", ms.coerceIn(4, 250))

    fun setTimeStretching(enabled: Boolean) = setBool("$AUDIO@@Enable Time Stretching", enabled)

    /**
     * Which cubeb backend delivers the audio, or auto.
     *
     * Android builds aaudio, opensl and audiotrack, and cubeb's auto order takes AAudio first on
     * anything modern -- so OpenSL has never run for a user. AAudio's low-latency path takes the
     * smallest buffers the device will grant, which underruns first when the emulator cannot hold
     * full speed; OpenSL is higher latency and much harder to starve. Exposed because the devices
     * reporting audio stutter are ones we cannot reproduce on, so it has to be A/B-able in the
     * field rather than guessed at here.
     *
     * A backend that is unavailable falls back to auto in the core, so a bad pick cannot leave
     * someone with no sound.
     */
    fun setCubebBackend(index: Int) = setString(
        "$AUDIO@@Cubeb Backend",
        when (index) {
            1 -> "aaudio"
            2 -> "opensl"
            3 -> "audiotrack"
            else -> "" // auto
        },
    )

    // ---- Core / CPU -----------------------------------------------------

    /**
     * thread_scheduler_mode strings are verbose and NOT the enum identifiers:
     * "Operating System" | "RPCS3 Scheduler" | "RPCS3 Alternative Scheduler"
     */
    fun setThreadScheduler(mode: String) = setEnum("$CORE@@Thread Scheduler Mode", mode)

    /** Emulated clock speed as a percentage, 10..3000. ARMSX2 calls this nominal speed. */
    fun setClocksScale(percent: Int) = setInt("$CORE@@Clocks scale", percent.coerceIn(10, 3000))

    fun setPpuThreads(count: Int) = setInt("$CORE@@PPU Threads", count.coerceIn(1, 8))

    /**
     * How many PPU modules LLVM compiles at once. 0 = one per host core.
     *
     * The upstream auto value is a desktop assumption. Each in-flight module
     * compile holds its whole LLVM module in memory, and a big title's modules
     * run to hundreds of thousands of blocks -- eight at once exhausted the
     * allocator on an 8 GB phone and scudo aborted the process partway through
     * precompilation.
     */
    fun setLlvmThreads(count: Int) = setInt("$CORE@@Max LLVM Compile Threads", count.coerceIn(0, 1024))

    /**
     * Verify cached SPU blocks by full compare instead of the xorsum checksum.
     *
     * Matters because SPU job managers stream different job code through the SAME
     * local-store addresses. The checksum is what decides whether a cached
     * compiled block still matches what is in local store; a collision runs one
     * job's code against another's data. The checksum has its own hand-written
     * ARM64 NEON implementation, so a fault there would be invisible on desktop.
     */
    fun setPreciseSpuVerification(enabled: Boolean) =
        setBool("$CORE@@Precise SPU Verification", enabled)

    fun setMaxSpursThreads(count: Int) = setInt("$CORE@@Max SPURS Threads", count.coerceIn(1, 6))

    fun setLlvmPrecompilation(enabled: Boolean) = setBool("$CORE@@LLVM Precompilation", enabled)

    fun setSpuCache(enabled: Boolean) = setBool("$CORE@@SPU Cache", enabled)

    /** sleep_timers_accuracy_level. */
    fun setSleepTimersAccuracy(level: String) = setEnum("$CORE@@Sleep Timers Accuracy", level)

    // ---- Savestates -----------------------------------------------------

    fun setStartPaused(enabled: Boolean) = setBool("$SAVESTATE@@Start Paused", enabled)

    fun setSuspendModeSavestates(enabled: Boolean) =
        setBool("$SAVESTATE@@Suspend Emulation Savestate Mode", enabled)

    /** SPU codegen that keeps the thread state capturable, which savestates require. */
    fun setCompatibleSavestateMode(enabled: Boolean) =
        setBool("$SAVESTATE@@Compatible Savestate Mode", enabled)

    fun setMaxSavestateFiles(count: Int) =
        setInt("$SAVESTATE@@Maximum SaveState Files", count.coerceIn(0, 64))

    // ---- Input ----------------------------------------------------------

    fun setBackgroundInput(enabled: Boolean) = setBool("$IO@@Background input enabled", enabled)

    fun setKeepPadsConnected(enabled: Boolean) = setBool("$IO@@Keep pads connected", enabled)

    // ---- Misc -----------------------------------------------------------

    fun setShowTrophyPopups(enabled: Boolean) = setBool("$MISC@@Show trophy popups", enabled)

    fun setSilenceAllLogs(enabled: Boolean) = setBool("$MISC@@Silence All Logs", enabled)

    fun setPauseOnHomeMenu(enabled: Boolean) =
        setBool("$MISC@@Pause Emulation During Home Menu", enabled)

    fun setPreventDisplaySleep(enabled: Boolean) =
        setBool("$MISC@@Prevent display sleep while running games", enabled)

    fun setShaderCompilationHint(enabled: Boolean) =
        setBool("$MISC@@Show shader compilation hint", enabled)

    fun setPpuCompilationHint(enabled: Boolean) =
        setBool("$MISC@@Show PPU compilation hint", enabled)

    // ---- PS3 core (PPU / SPU) ------------------------------------------
    //
    // Enum VALUES are matched literally by cfg::_enum::from_string, so these
    // arrays are the exact fmt_class_string output, in ordinal order. An index
    // that falls off the end would send a bare number and silently no-op.

    private val PPU_DECODERS = arrayOf("Interpreter (static)", "Recompiler (LLVM)")
    private val SPU_DECODERS = arrayOf(
        "Interpreter (static)", "Interpreter (dynamic)",
        "Recompiler (ASMJIT)", "Recompiler (LLVM)",
    )
    private val SPU_BLOCK_SIZES = arrayOf("Safe", "Mega", "Giga")

    fun setPpuDecoder(index: Int) =
        setEnum("$CORE@@PPU Decoder", PPU_DECODERS.getOrElse(index) { PPU_DECODERS.last() })

    fun setSpuDecoder(index: Int) =
        setEnum("$CORE@@SPU Decoder", SPU_DECODERS.getOrElse(index) { SPU_DECODERS.last() })

    fun setSpuBlockSize(index: Int) =
        setEnum("$CORE@@SPU Block Size", SPU_BLOCK_SIZES.getOrElse(index) { SPU_BLOCK_SIZES.first() })

    fun setPreferredSpuThreads(count: Int) =
        setInt("$CORE@@Preferred SPU Threads", count.coerceIn(0, 6))

    fun setSpuLoopDetection(enabled: Boolean) =
        setBool("$CORE@@SPU loop detection", enabled)

    fun setAccurateSpuDma(enabled: Boolean) =
        setBool("$CORE@@Accurate SPU DMA", enabled)

    // ---- PS3 video / audio / net ---------------------------------------

    private val MSAA_LEVELS = arrayOf("Disabled", "Auto")
    private val SHADER_MODES = arrayOf(
        "Legacy Recompiler (single-threaded)",
        "Async Recompiler (multi-threaded)",
        "Async Recompiler with Shader Interpreter",
        "Shader Interpreter only",
    )
    private val AUDIO_FORMATS = arrayOf("Stereo", "Surround 5.1", "Surround 7.1", "Automatic", "Manual")
    private val CHANNEL_LAYOUTS = arrayOf(
        "Automatic", "Mono", "Stereo", "Stereo LFE",
        "Quadraphonic", "Quadraphonic LFE", "Surround 5.1", "Surround 7.1",
    )

    fun setResolutionScalePercent(percent: Int) =
        setInt("$VIDEO@@Resolution Scale", percent.coerceIn(25, 800))

    fun setMsaa(index: Int) =
        setEnum("$VIDEO@@MSAA", MSAA_LEVELS.getOrElse(index) { "Auto" })

    fun setShaderMode(index: Int) =
        setEnum("$VIDEO@@Shader Mode", SHADER_MODES.getOrElse(index) { SHADER_MODES[2] })

    /** Frame Generation. Names, not indices -- cfg::_enum matches on the string the core's
     *  fmt_class_string produces, and these have to stay in step with frame_generation_mode. */
    private val FRAME_GENERATION = arrayOf("Off", "x2", "x3", "x4")

    fun setFrameGenPerformance(on: Boolean) =
        setBool("$VIDEO@@Frame Generation Performance Mode", on)

    fun setFrameGenFlowScale(percent: Int) =
        setInt("$VIDEO@@Frame Generation Flow Scale", percent.coerceIn(25, 100))

    fun setFrameGeneration(index: Int) =
        setEnum("$VIDEO@@Frame Generation", FRAME_GENERATION.getOrElse(index) { FRAME_GENERATION[0] })

    fun setWriteDepthBuffer(v: Boolean) = setBool("$VIDEO@@Write Depth Buffer", v)
    fun setReadColorBuffers(v: Boolean) = setBool("$VIDEO@@Read Color Buffers", v)
    fun setReadDepthBuffer(v: Boolean) = setBool("$VIDEO@@Read Depth Buffer", v)
    fun setStrictRendering(v: Boolean) = setBool("$VIDEO@@Strict Rendering Mode", v)
    fun setMultithreadedRsx(v: Boolean) = setBool("$VIDEO@@Multithreaded RSX", v)
    fun setDisableZcull(v: Boolean) = setBool("$VIDEO@@Disable ZCull Occlusion Queries", v)
    fun setRelaxedZcull(v: Boolean) = setBool("$VIDEO@@Relaxed ZCULL Sync", v)
    fun setGpuTextureScaling(v: Boolean) = setBool("$VIDEO@@Use GPU texture scaling", v)
    fun setForceCpuBlit(v: Boolean) = setBool("$VIDEO@@Force CPU Blit", v)

    /** 0 = auto (RPCS3 picks from core count). */
    fun setShaderCompilerThreads(n: Int) =
        setInt("$VIDEO@@Shader Compiler Threads", n.coerceIn(0, 16))

    fun setTextureLodBias(bias: Int) =
        setFloat("$VIDEO@@Texture LOD Bias Addend", bias.coerceIn(-16, 16).toFloat())

    fun setAudioFormat(index: Int) =
        setEnum("$AUDIO@@Audio Format", AUDIO_FORMATS.getOrElse(index) { "Stereo" })

    fun setAudioChannelLayout(index: Int) =
        setEnum("$AUDIO@@Audio Channel Layout", CHANNEL_LAYOUTS.getOrElse(index) { "Automatic" })

    /** np_internet_status: "Disconnected" | "Connected". */
    fun setInternetEnabled(enabled: Boolean) =
        setEnum("Net@@Internet enabled", if (enabled) "Connected" else "Disconnected")

    /** np_psn_status: "Disconnected" | "Simulated" | "RPCN". Simulated is offline-safe. */
    /** np_psn_status: 0 Disconnected, 1 Simulated, 2 RPCN.
     *
     *  This took a Boolean and could only ever write the first two, so "RPCN" -- the state
     *  that actually connects, and whose client is fully compiled into the core -- had no
     *  writer anywhere in the app. */
    private val PSN_STATES = arrayOf("Disconnected", "Simulated", "RPCN")

    fun setPsnStatus(state: Int) =
        setEnum("Net@@PSN status", PSN_STATES.getOrElse(state) { PSN_STATES[0] })

    fun setUpnp(enabled: Boolean) = setBool("Net@@UPNP Enabled", enabled)

    // ---- PS3 advanced (core accuracy) ----------------------------------

    private val XFLOAT = arrayOf("Accurate", "Approximate", "Relaxed", "Inaccurate")
    private val SLEEP_TIMERS = arrayOf("As Host", "Usleep Only", "All Timers")
    // Index -> core enum NAME (the core parses by name, not ordinal, so this array is the
    // contract). Oboe appends rather than inserting: the first four indices are baked into
    // saved settings.
    private val AUDIO_RENDERERS = arrayOf("Null", "XAudio2", "Cubeb", "FAudio", "Oboe")

    /** PS3 output resolution. Ordinals must match video_resolution exactly. */
    val RESOLUTIONS = arrayOf(
        "1920x1080", "1920x1080i", "1280x720", "720x480", "720x480i",
        "720x576", "720x576i", "1600x1080", "1440x1080", "1280x1080", "960x1080",
    )

    fun setSpuXFloat(index: Int) =
        setEnum("$CORE@@SPU XFloat Accuracy", XFLOAT.getOrElse(index) { "Approximate" })

    fun setAccurateSpuReservations(v: Boolean) = setBool("$CORE@@Accurate SPU Reservations", v)
    fun setAccurateCacheLineStores(v: Boolean) = setBool("$CORE@@Accurate Cache Line Stores", v)
    fun setAccurateRsxReservation(v: Boolean) = setBool("$CORE@@Accurate RSX reservation access", v)
    fun setPpuReservationPriority(v: Boolean) = setBool("$CORE@@PPU Reservation Priority Over SPUs", v)
    fun setSpuVerification(v: Boolean) = setBool("$CORE@@SPU Verification", v)
    fun setPpuNanHandling(v: Boolean) = setBool("$CORE@@PPU Vector NaN Handling", v)
    fun setAccurateDfma(v: Boolean) = setBool("$CORE@@Use Accurate DFMA", v)
    fun setDazFtz(v: Boolean) = setBool("$CORE@@Set DAZ and FTZ", v)
    fun setHleLwmutex(v: Boolean) = setBool("$CORE@@HLE lwmutex", v)
    fun setDebugConsoleMode(v: Boolean) = setBool("$CORE@@Debug Console Mode", v)

    fun setSleepTimersIndex(index: Int) =
        setEnum("$CORE@@Sleep Timers Accuracy", SLEEP_TIMERS.getOrElse(index) { "As Host" })

    fun setResolution(index: Int) =
        setEnum("$VIDEO@@Resolution", RESOLUTIONS.getOrElse(index) { "1280x720" })

    /**
     * Audio backend. Only Cubeb and Null are real on Android -- XAudio2 is
     * Windows and FAudio is not built here -- so the UI must not offer the other
     * two, or picking one silently kills audio.
     */
    fun setAudioRenderer(index: Int) =
        setEnum("$AUDIO@@Renderer", AUDIO_RENDERERS.getOrElse(index) { "Cubeb" })

    // ---- Performance overlay (custom colours) ---------------------------

    fun setOverlayPosition(index: Int) = setEnum(
        "$OVERLAY@@Position",
        arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right").getOrElse(index) { "Top Left" },
    )

    fun setOverlayDetailIndex(index: Int) = setEnum(
        "$OVERLAY@@Detail level",
        arrayOf("None", "Minimal", "Low", "Medium", "High").getOrElse(index) { "Medium" },
    )

    /** Colours are "#RRGGBBAA" strings; the caller has already formatted them. */
    fun setOverlayBodyColor(hex: String) = setString("$OVERLAY@@Body Color (hex)", hex)
    fun setOverlayBodyBackground(hex: String) = setString("$OVERLAY@@Body Background (hex)", hex)
    fun setOverlayTitleColor(hex: String) = setString("$OVERLAY@@Title Color (hex)", hex)
    fun setOverlayTitleBackground(hex: String) = setString("$OVERLAY@@Title Background (hex)", hex)
}

package com.armsx3

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import net.rpcsx.Digital1Flags
import net.rpcsx.Digital2Flags
import android.view.KeyEvent
import net.rpcsx.EmulatorState
import net.rpcsx.RPCSX
import java.io.File

/**
 * Translation layer between ARMSX2's [NativeApp] surface and RPCS3.
 *
 * NativeApp exists to keep ARMSX2's 129-file UI compiling unchanged. This is
 * where the two models are actually reconciled, and they differ in ways that
 * matter:
 *
 *  - PAD INPUT. ARMSX2 sends per-button edges (setPadButton(index, range,
 *    pressed)). RPCS3 takes the WHOLE pad state every call
 *    (overlayPadData(digital1, digital2, lx, ly, rx, ry)). So we hold the
 *    state here and push a full snapshot on every change - forwarding one
 *    button in isolation would clear every other button that was held.
 *
 *  - SETTINGS. ARMSX2 addresses a PCSX2 INI by (section, key). RPCS3 has a
 *    typed config tree addressed by a "@@"-joined path, and its setter takes a
 *    JSON-encoded value.
 */
object Rpcs3Bridge {

    private var appContext: Context? = null

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @JvmStatic
    fun initializeOnce(context: Context) {
        appContext = context.applicationContext

        // Bring up the emulator core. System.loadLibrary in RPCSX's companion
        // only loads the JNI GLUE; the core is a separate .so that the glue
        // dlopen()s by absolute path. Until this runs, every _rpcsx_* forwarder
        // is a null pointer and the glue answers false to everything -- which
        // looks like "the emulator does nothing" rather than "it was not loaded".
        if (RPCSX.activeLibrary.value == null) {
            val libDir = context.applicationInfo.nativeLibraryDir
            RPCSX.nativeLibDirectory = libDir
            val core = File(libDir, "libarmsx3-core.so")
            if (!core.exists()) {
                android.util.Log.e("ARMSX3", "core missing at ${'$'}{core.absolutePath}")
                return
            }
            if (!RPCSX.openLibrary(core.absolutePath)) {
                android.util.Log.e("ARMSX3", "core failed to load: ${'$'}{core.absolutePath}")
                return
            }
        }

        // Initialise the emulator against the user's data root.
        //
        // This lives here, not in initialize(path, ..), because NOTHING calls
        // that: ARMSX2's UI has no PS2-style "hand the core a BIOS folder" step,
        // so the core would otherwise never be initialised and every boot would
        // fail with no diagnostic. The root is resolved by the app's own
        // assetCopyRoot so it matches the path kickoffEmucoreInit records as the
        // pinned data root -- two different answers here means the library scans
        // one tree while the emulator uses another.
        val root = runCatching {
            com.armsx2.runtime.MainActivityRuntime.assetCopyRoot(context)
        }.getOrElse { context.dataDir.absolutePath }

        initialize(root)

        // Restore firmware state from <root>/fw.json. Without this the version
        // and status start at None on every launch, so setup would demand a PUP
        // again even though dev_flash is already populated.
        //
        // Must run AFTER initialize(), which is what sets RPCSX.rootDirectory --
        // load() reads that path, so calling it earlier silently reads "/fw.json".
        runCatching { net.rpcsx.FirmwareRepository.load() }
    }

    @Volatile
    private var pumpsStarted = false

    @JvmStatic
    fun initialize(rootPath: String) {
        if (RPCSX.initialized) return

        RPCSX.rootDirectory = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
        RPCSX.instance.initialize(RPCSX.rootDirectory, "00000001")
        RPCSX.initialized = true

        // Two blocking service loops the core needs someone to run for it.
        //
        // startMainThreadProcessor drains Emu.CallFromMainThread. Without it,
        // anything posted there is QUEUED AND NEVER RUN -- which is not a crash,
        // it is silence: save state and load state both post through it, so they
        // would return true and simply never happen.
        //
        // processCompilationQueue drains PPU/SPU compilation work.
        //
        // Each blocks for the process lifetime, so both need their own plain
        // thread -- a coroutine scope would tie up a dispatcher permanently.
        if (!pumpsStarted) {
            pumpsStarted = true
            Thread({ runCatching { RPCSX.instance.startMainThreadProcessor() } },
                "armsx3-main-thread-processor").apply { isDaemon = true }.start()
            Thread({ runCatching { RPCSX.instance.processCompilationQueue() } },
                "armsx3-compilation-queue").apply { isDaemon = true }.start()
        }
    }

    /**
     * Boot a game, or the XMB when handed an empty path.
     *
     * ARMSX2 signals "boot the BIOS" by passing an empty path (PCSX2 boots to the
     * PS2 browser that way). RPCS3's equivalent is the VSH -- the actual PS3
     * dashboard -- which lives in dev_flash and is a normal SELF, so it has to be
     * named explicitly. An empty path would just fail to boot.
     *
     * dev_flash only exists once firmware is installed, hence the existence check:
     * booting a missing vsh.self otherwise fails deep in the loader with a much
     * less obvious message than "install firmware first".
     */
    @JvmStatic
    fun boot(path: String): Boolean {
        val target = if (path.isNotEmpty()) path else {
            val vsh = File(RPCSX.rootDirectory + "config/dev_flash/vsh/module/vsh.self")
            if (!vsh.isFile) {
                android.util.Log.e("ARMSX3", "XMB requested but ${'$'}{vsh.absolutePath} is missing - install firmware first")
                return false
            }
            vsh.absolutePath
        }
        if (RPCSX.boot(target).ordinal != 0) {
            return false
        }

        // BLOCK until the emulator actually stops.
        //
        // This is the contract MainActivityRuntime.start() is built around: it
        // treats runVMThread() as owning the VM's whole lifetime and, in its
        // finally block, drops eState back to STOPPED once it returns.
        //
        // RPCS3's BootGame does not work that way -- it loads the executable,
        // starts the emulator threads and returns, in about two seconds. So the
        // app decided the game had already exited while it was still compiling
        // PPU modules. Everything keyed off eState went wrong from there, and the
        // visible symptom was fatal: with state back at STOPPED, the next launch
        // took the "not running, just start it" branch and called BootGame into a
        // live emulator, which trips ensure(IsStopped()) in Emulator::BootGame and
        // takes the process down. That is the "starts to precompile, then crashes"
        // report.
        //
        // Polling rather than a callback because the core exposes no
        // stopped-notification to the JNI layer; at 100 ms it costs nothing next
        // to a running PS3 emulator.
        stopRequested = false

        var settleMs = 0L
        while (!stopRequested && RPCSX.getState() == EmulatorState.Stopped &&
            settleMs < BOOT_SETTLE_TIMEOUT_MS
        ) {
            Thread.sleep(POLL_INTERVAL_MS)
            settleMs += POLL_INTERVAL_MS
        }

        // stopRequested is the escape hatch, and it is not optional.
        //
        // Waiting purely on "state == Stopped" assumes the core always gets back
        // there. It does not: when a game's threads die abnormally (an SPU job
        // thread hitting a bad STOP, say) the emulator can tear the session down
        // -- VFS unmounted, everything gone -- while getState() never reports
        // Stopped. This loop then spun forever, runVMThread never returned, and
        // MainActivityRuntime went on believing state=RUNNING, so Close and
        // Restart both became silent no-ops with no way out but force-stop.
        while (!stopRequested && RPCSX.getState() != EmulatorState.Stopped) {
            Thread.sleep(POLL_INTERVAL_MS)
        }

        return true
    }

    /**
     * How long to wait for the emulator to leave Stopped after a successful boot.
     * Load() should have moved it already, so this only exists so a core that
     * fails to start can never wedge the caller.
     */
    private const val BOOT_SETTLE_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 100L

    @JvmStatic
    fun hasActiveVm(): Boolean = RPCSX.getState() != EmulatorState.Stopped

    @JvmStatic
    fun pause() {
        // RPCS3 has no explicit pause entry point; the home menu overlay owns
        // pause/resume. Track intent so vmSetPaused round-trips.
        paused = true
    }

    @JvmStatic
    fun resume() {
        paused = false
        RPCSX.instance.resume()
    }

    /** Set by shutdown() to release boot()'s wait; see the loop there. */
    @Volatile
    private var stopRequested = false

    @JvmStatic
    fun shutdown() {
        // Release the waiter FIRST. kill() can block, or leave the core in a state
        // it never reports as Stopped, and the app's stop path is synchronous --
        // if boot() is still parked when this returns, Close does nothing.
        stopRequested = true
        runCatching { RPCSX.instance.kill() }
        resetPadState()
    }

    @Volatile
    private var paused = false

    // ---------------------------------------------------------------
    // Surface
    // ---------------------------------------------------------------

    // Event codes match the core's surfaceEvent contract.
    private const val SURFACE_CREATED = 0
    private const val SURFACE_CHANGED = 1
    private const val SURFACE_DESTROYED = 2

    private var currentSurface: Surface? = null

    @JvmStatic
    fun surfaceCreated() { /* the Surface arrives with surfaceChanged */ }

    @JvmStatic
    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        currentSurface = surface
        val event = if (currentSurface == null) SURFACE_CREATED else SURFACE_CHANGED
        RPCSX.instance.surfaceEvent(surface, event)
    }

    @JvmStatic
    fun surfaceDestroyed() {
        currentSurface?.let { RPCSX.instance.surfaceEvent(it, SURFACE_DESTROYED) }
        currentSurface = null
    }

    // ---------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------

    /**
     * ARMSX2's generic (section, key) setter -- THE settings entry point.
     *
     * Settings.applyTo() pushes EVERY field through here (209 distinct PCSX2
     * (section, key) pairs), so this function is the difference between the
     * settings UI working and the settings UI being decorative. It is not the
     * minor path the name suggests.
     *
     * Translation is explicit and per-key. It cannot be a naive
     * "Section@@Key" join: find_cfg_node splits on "@@" and compares node names
     * with ==, so a wrong path returns null, settingsSet returns false, and the
     * UI shows the new value while the core keeps the old one -- a setting that
     * "does nothing" with no error anywhere.
     *
     * Most of the 209 are PS2 silicon (EE/VU clamping, speedhacks, GS user
     * hacks, DEV9, memory cards) and have no PS3 counterpart. Those are NOT
     * silently dropped: each logs once to ARMSX3-Unsupported, so a control that
     * does nothing is visible in logcat instead of being a mystery.
     */
    @JvmStatic
    fun setSetting(section: String, key: String, type: String, value: String) {
        if (handle(section, key, value)) return
        Unsupported.note("$section/$key")
    }

    private fun asBool(v: String) = v.equals("true", ignoreCase = true) || v == "1"
    private fun asInt(v: String) = v.toFloatOrNull()?.toInt() ?: 0
    private fun asFloat(v: String) = v.toFloatOrNull() ?: 0f

    /** Returns true when the key was translated and applied. */
    private fun handle(section: String, key: String, value: String): Boolean {
        when (section) {
            "EmuCore/GS" -> when (key) {
                // Written as a NAME ("Stretch"/"4:3"/"16:9"/...), not an ordinal.
                // RPCS3's video_aspect only has 4:3 and 16:9; the PS2-era ultrawide
                // ratios have no counterpart, so they take the nearest wide option
                // and "Stretch" additionally turns off aspect preservation.
                "AspectRatio" -> {
                    Rpcs3Settings.setStretchToDisplay(value == "Stretch")
                    Rpcs3Settings.setAspectRatio(value != "4:3")
                }
                "FrameLimitEnable" ->
                    // "Auto" is RPCS3's per-title native pacing -- the right
                    // meaning of "limit on" for a console that isn't fixed at 60.
                    Rpcs3Settings.setFrameLimitMode(if (asBool(value)) "Auto" else "Off")
                "SyncToHostRefreshRate" ->
                    if (asBool(value)) Rpcs3Settings.setFrameLimitMode("Display")
                "VsyncEnable" -> Rpcs3Settings.setVsync(asBool(value))
                "MaxAnisotropy" -> Rpcs3Settings.setAnisotropicFilter(asInt(value))
                "SkipDuplicateFrames" -> Rpcs3Settings.setFrameSkip(if (asBool(value)) 1 else 0)
                "DisableShaderCache" -> Rpcs3Settings.setDisableShaderCache(asBool(value))
                "IntegerScaling" ->
                    // Nearest-neighbour is the closest RPCS3 has to integer scaling;
                    // bilinear otherwise. Overridden below if CAS/librashader is on.
                    Rpcs3Settings.setOutputScaling(if (asBool(value)) "Nearest" else "Bilinear")
                "linear_present_mode" ->
                    Rpcs3Settings.setOutputScaling(if (asInt(value) == 0) "Nearest" else "Bilinear")
                // CAS and the librashader chain are both OUTPUT SCALING MODES in
                // RPCS3, not independent post-effects, so enabling either has to
                // claim the mode. Shader chain wins -- it is the more specific ask.
                "CASMode" ->
                    if (asInt(value) > 0) Rpcs3Settings.setOutputScaling("FidelityFX Super Resolution")
                "CASSharpness" -> Rpcs3Settings.setCasSharpening(asInt(value))
                "ShaderChainEnabled" ->
                    if (asBool(value)) Rpcs3Settings.setOutputScaling("Shader chain (librashader)")
                "ShaderChainPreset" -> Rpcs3Settings.setShaderPresetPath(value)
                // The 12 OsdShow* flags are aggregated by NativeApp.osdApplyFlags,
                // which maps them onto RPCS3's single Enabled + detail_level pair.
                // Handling them one at a time here would fight that, since each
                // would recompute the detail level from one flag.
                else -> return key.startsWith("Osd")
            }

            "Framerate" -> when (key) {
                // 100 = full speed in both, but RPCS3 clamps to 10..3000.
                "NominalScalar" -> Rpcs3Settings.setClocksScale((asFloat(value) * 100f).toInt())
                else -> return false
            }

            "SPU2/Output" -> when (key) {
                "SyncMode" -> Rpcs3Settings.setTimeStretching(value == "TimeStretch")
                // PCSX2 splits buffer and output latency; RPCS3 has one duration.
                // OutputLatencyMS is the one the user's slider actually paces on.
                "OutputLatencyMS" -> Rpcs3Settings.setAudioBufferDuration(asInt(value))
                "BufferMS" -> Rpcs3Settings.setAudioBuffering(asInt(value) > 0)
                else -> return false
            }

            // Settings.applyTo emits these with a "PS3/<section>" pseudo-section so
            // they are unambiguous against the PCSX2 keys sharing this function.
            "PS3/Core" -> when (key) {
                "PPU Decoder" -> Rpcs3Settings.setPpuDecoder(asInt(value))
                "SPU Decoder" -> Rpcs3Settings.setSpuDecoder(asInt(value))
                "SPU Block Size" -> Rpcs3Settings.setSpuBlockSize(asInt(value))
                "PPU Threads" -> Rpcs3Settings.setPpuThreads(asInt(value))
                "Max LLVM Compile Threads" -> Rpcs3Settings.setLlvmThreads(asInt(value))
                "Precise SPU Verification" -> Rpcs3Settings.setPreciseSpuVerification(asBool(value))
                "Preferred SPU Threads" -> Rpcs3Settings.setPreferredSpuThreads(asInt(value))
                "Max SPURS Threads" -> Rpcs3Settings.setMaxSpursThreads(asInt(value))
                "SPU loop detection" -> Rpcs3Settings.setSpuLoopDetection(asBool(value))
                "SPU Cache" -> Rpcs3Settings.setSpuCache(asBool(value))
                "LLVM Precompilation" -> Rpcs3Settings.setLlvmPrecompilation(asBool(value))
                "Accurate SPU DMA" -> Rpcs3Settings.setAccurateSpuDma(asBool(value))
                "Clocks scale" -> Rpcs3Settings.setClocksScale(asInt(value))
                "SPU XFloat Accuracy" -> Rpcs3Settings.setSpuXFloat(asInt(value))
                "Accurate SPU Reservations" -> Rpcs3Settings.setAccurateSpuReservations(asBool(value))
                "Accurate Cache Line Stores" -> Rpcs3Settings.setAccurateCacheLineStores(asBool(value))
                "Accurate RSX reservation access" -> Rpcs3Settings.setAccurateRsxReservation(asBool(value))
                "PPU Reservation Priority Over SPUs" -> Rpcs3Settings.setPpuReservationPriority(asBool(value))
                "SPU Verification" -> Rpcs3Settings.setSpuVerification(asBool(value))
                "PPU Vector NaN Handling" -> Rpcs3Settings.setPpuNanHandling(asBool(value))
                "Use Accurate DFMA" -> Rpcs3Settings.setAccurateDfma(asBool(value))
                "Set DAZ and FTZ" -> Rpcs3Settings.setDazFtz(asBool(value))
                "HLE lwmutex" -> Rpcs3Settings.setHleLwmutex(asBool(value))
                "Sleep Timers Accuracy" -> Rpcs3Settings.setSleepTimersIndex(asInt(value))
                "Debug Console Mode" -> Rpcs3Settings.setDebugConsoleMode(asBool(value))
                else -> return false
            }

            "PS3/Video" -> when (key) {
                "Resolution Scale" -> Rpcs3Settings.setResolutionScalePercent(asInt(value))
                "MSAA" -> Rpcs3Settings.setMsaa(asInt(value))
                "Shader Mode" -> Rpcs3Settings.setShaderMode(asInt(value))
                "Write Color Buffers" -> Rpcs3Settings.setWriteColorBuffers(asBool(value))
                "Write Depth Buffer" -> Rpcs3Settings.setWriteDepthBuffer(asBool(value))
                "Read Color Buffers" -> Rpcs3Settings.setReadColorBuffers(asBool(value))
                "Read Depth Buffer" -> Rpcs3Settings.setReadDepthBuffer(asBool(value))
                "Strict Rendering Mode" -> Rpcs3Settings.setStrictRendering(asBool(value))
                "Multithreaded RSX" -> Rpcs3Settings.setMultithreadedRsx(asBool(value))
                "Disable ZCull Occlusion Queries" -> Rpcs3Settings.setDisableZcull(asBool(value))
                "Relaxed ZCULL Sync" -> Rpcs3Settings.setRelaxedZcull(asBool(value))
                "Use GPU texture scaling" -> Rpcs3Settings.setGpuTextureScaling(asBool(value))
                "Force CPU Blit" -> Rpcs3Settings.setForceCpuBlit(asBool(value))
                "Shader Compiler Threads" -> Rpcs3Settings.setShaderCompilerThreads(asInt(value))
                "Texture LOD Bias Addend" -> Rpcs3Settings.setTextureLodBias(asInt(value))
                "VRAM allocation limit (MB)" -> Rpcs3Settings.setVramLimitMb(asInt(value))
                "Asynchronous Texture Streaming" -> Rpcs3Settings.setAsyncTextureStreaming(asBool(value))
                "Resolution" -> Rpcs3Settings.setResolution(asInt(value))
                "Anisotropic Filter Override" -> Rpcs3Settings.setAnisotropicFilter(asInt(value))
                else -> return false
            }

            "PS3/Audio" -> when (key) {
                "Audio Format" -> Rpcs3Settings.setAudioFormat(asInt(value))
                "Audio Channel Layout" -> Rpcs3Settings.setAudioChannelLayout(asInt(value))
                "Enable Time Stretching" -> Rpcs3Settings.setTimeStretching(asBool(value))
                "Enable Buffering" -> Rpcs3Settings.setAudioBuffering(asBool(value))
                "Desired Audio Buffer Duration" -> Rpcs3Settings.setAudioBufferDuration(asInt(value))
                "Renderer" -> Rpcs3Settings.setAudioRenderer(asInt(value))
                else -> return false
            }

            "PS3/Overlay" -> when (key) {
                "Enabled" -> Rpcs3Settings.setOverlayEnabled(asBool(value))
                "Detail level" -> Rpcs3Settings.setOverlayDetailIndex(asInt(value))
                "Enable Framerate Graph" -> Rpcs3Settings.setOverlayFramerateGraph(asBool(value))
                "Enable Frametime Graph" -> Rpcs3Settings.setOverlayFrametimeGraph(asBool(value))
                "Font size (px)" -> Rpcs3Settings.setOverlayFontSize(asInt(value))
                "Opacity (%)" -> Rpcs3Settings.setOverlayOpacity(asInt(value))
                "Position" -> Rpcs3Settings.setOverlayPosition(asInt(value))
                "Body Color (hex)" -> Rpcs3Settings.setOverlayBodyColor(value)
                "Body Background (hex)" -> Rpcs3Settings.setOverlayBodyBackground(value)
                "Title Color (hex)" -> Rpcs3Settings.setOverlayTitleColor(value)
                "Title Background (hex)" -> Rpcs3Settings.setOverlayTitleBackground(value)
                else -> return false
            }

            "PS3/Net" -> when (key) {
                "Internet enabled" -> Rpcs3Settings.setInternetEnabled(asBool(value))
                "PSN status" -> Rpcs3Settings.setPsnStatus(asBool(value))
                "UPNP Enabled" -> Rpcs3Settings.setUpnp(asBool(value))
                else -> return false
            }

            else -> return false
        }
        return true
    }

    @JvmStatic
    fun commitSettings() { /* RPCS3 persists on each set */ }

    @JvmStatic
    fun setRenderer(name: String) = Rpcs3Settings.setRenderer(name)

    @JvmStatic
    fun setResolutionScale(percent: Int) =
        Rpcs3Settings.setUpscaleMultiplier(percent / 100f)

    @JvmStatic
    fun setFrameLimit(fps: Int) = Rpcs3Settings.setFrameLimit(fps)

    // ---------------------------------------------------------------
    // Save states
    // ---------------------------------------------------------------

    /**
     * SLOT SEMANTICS DIFFER FROM PCSX2 -- read this before touching it.
     *
     * PCSX2 slots are addressable: save to 3, load 3, get that state back.
     * RPCS3 keeps a rolling HISTORY instead: writing a state pushes it to the
     * front, so index 1 is the newest, 2 the one before it, and so on (depth is
     * Savestate@@"Maximum SaveState Files"). There is no way to pin a state to a
     * chosen number.
     *
     * So saving ignores the slot entirely, and loading treats it as "how many
     * states back". The ARMSX2 slot UI is 0-based, RPCS3's index is 1-based.
     */
    @JvmStatic
    fun saveState(slot: Int): Boolean =
        runCatching { RPCSX.instance.saveState() }.getOrDefault(false)

    @JvmStatic
    fun loadState(slot: Int): Boolean =
        runCatching { RPCSX.instance.loadState(slot + 1) }.getOrDefault(false)

    /** Whether a state exists that far back in the history. */
    @JvmStatic
    fun hasState(slot: Int): Boolean =
        runCatching { RPCSX.instance.hasState(slot + 1) }.getOrDefault(false)

    // ---------------------------------------------------------------
    // Identity / stats
    // ---------------------------------------------------------------

    @JvmStatic
    fun getTitleId(): String = runCatching { RPCSX.instance.getTitleId() }.getOrDefault("")

    @JvmStatic
    fun getVersion(): String = runCatching { RPCSX.instance.getVersion() }.getOrDefault("")

    @JvmStatic
    fun getFps(): Float {
        Unsupported.note("getFPS")
        return 0f
    }

    // ---------------------------------------------------------------
    // Pad input
    // ---------------------------------------------------------------

    /** PS3 pad ports. CELL_PAD_MAX_PORT_NUM -- no multitap, these are all real. */
    const val MAX_PORTS = 7

    /**
     * Live pad state, per port.
     *
     * RPCS3 wants a full snapshot each call, so a per-button event has to be
     * merged into the port's state and the whole thing re-sent. This was a
     * single set of fields, which is why every player shared one pad.
     *
     * Stick DIRECTIONS are held separately rather than collapsed into an axis
     * byte on arrival. The app sends one direction at a time (code 110-123 with
     * a magnitude), so folding them together immediately loses the information
     * needed to release one direction without recentring the axis while its
     * opposite is still held -- the diagonal would snap back to centre.
     */
    private class PadState {
        var digital1 = 0
        var digital2 = 0
        /** [lsUp, lsRight, lsDown, lsLeft, rsUp, rsRight, rsDown, rsLeft], 0f..1f. */
        val dir = FloatArray(8)

        /** Opposing directions cancel; 128 is centre, matching CELL_PAD. */
        private fun axis(pos: Int, neg: Int) =
            (128f + (dir[pos] - dir[neg]) * 127f).toInt().coerceIn(0, 255)

        fun leftX() = axis(1, 3)
        fun leftY() = axis(2, 0)
        fun rightX() = axis(5, 7)
        fun rightY() = axis(6, 4)

        fun reset() {
            digital1 = 0
            digital2 = 0
            dir.fill(0f)
        }
    }

    private val pads = Array(MAX_PORTS) { PadState() }

    /**
     * ARMSX2 pad button indices -> PS3 CELL_PAD bits.
     *
     * ARMSX2's indices come from PCSX2's pad enum. The face buttons do NOT line
     * up positionally between the two consoles' enums, so this is an explicit
     * table rather than arithmetic - an off-by-one here swaps Cross and Circle,
     * which is the kind of bug that looks like "controls feel wrong" instead of
     * an obvious failure.
     */
    /**
     * ARMSX2 button codes -> PS3 CELL_PAD bits.
     *
     * The codes are ANDROID KEYCODES, not PCSX2 pad indices. Every button in
     * ControllerMappings.ACTIONS carries a KeyEvent.KEYCODE_BUTTON_* as its
     * native code, and both the touch overlay and the physical-pad dispatch send
     * that value straight through.
     *
     * This table was originally written against PCSX2's 0..16 pad enum, so every
     * real press (cross = 96, L2 = 104, ...) fell through to `else -> 0` and NO
     * button ever reached the emulator. It was invisible for a long time because
     * nothing had booted far enough to need input; the first thing to expose it
     * was a cellMsgDialog whose OK could not be pressed.
     *
     * Analog sticks do not come through here -- they arrive on the separate
     * 110-123 block, see dirSlot().
     */
    private fun applyButton(pad: PadState, index: Int, pressed: Boolean) {
        val d1 = when (index) {
            KeyEvent.KEYCODE_DPAD_UP -> Digital1Flags.CELL_PAD_CTRL_UP.bit
            KeyEvent.KEYCODE_DPAD_RIGHT -> Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            KeyEvent.KEYCODE_DPAD_DOWN -> Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            KeyEvent.KEYCODE_DPAD_LEFT -> Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            KeyEvent.KEYCODE_BUTTON_SELECT -> Digital1Flags.CELL_PAD_CTRL_SELECT.bit
            KeyEvent.KEYCODE_BUTTON_START -> Digital1Flags.CELL_PAD_CTRL_START.bit
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Digital1Flags.CELL_PAD_CTRL_L3.bit
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Digital1Flags.CELL_PAD_CTRL_R3.bit
            else -> 0
        }
        val d2 = when (index) {
            KeyEvent.KEYCODE_BUTTON_Y -> Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit
            KeyEvent.KEYCODE_BUTTON_B -> Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit
            KeyEvent.KEYCODE_BUTTON_A -> Digital2Flags.CELL_PAD_CTRL_CROSS.bit
            KeyEvent.KEYCODE_BUTTON_X -> Digital2Flags.CELL_PAD_CTRL_SQUARE.bit
            KeyEvent.KEYCODE_BUTTON_L1 -> Digital2Flags.CELL_PAD_CTRL_L1.bit
            KeyEvent.KEYCODE_BUTTON_R1 -> Digital2Flags.CELL_PAD_CTRL_R1.bit
            KeyEvent.KEYCODE_BUTTON_L2 -> Digital2Flags.CELL_PAD_CTRL_L2.bit
            KeyEvent.KEYCODE_BUTTON_R2 -> Digital2Flags.CELL_PAD_CTRL_R2.bit
            else -> 0
        }

        if (d1 != 0) pad.digital1 = if (pressed) pad.digital1 or d1 else pad.digital1 and d1.inv()
        if (d2 != 0) pad.digital2 = if (pressed) pad.digital2 or d2 else pad.digital2 and d2.inv()
    }

    /**
     * Analog stick directions. The input layer sends these as pad "buttons" with
     * a 0..32767 magnitude, using PCSX2's analog code block:
     *
     *   110 L-up  111 L-right  112 L-down  113 L-left
     *   120 R-up  121 R-right  122 R-down  123 R-left
     *
     * applyButton had no case for them, so every one fell through to `else -> 0`
     * and both sticks read dead centre forever. Nothing reported it as a stick
     * bug because the d-pad still worked.
     */
    private fun dirSlot(index: Int) = when (index) {
        110 -> 0; 111 -> 1; 112 -> 2; 113 -> 3
        120 -> 4; 121 -> 5; 122 -> 6; 123 -> 7
        else -> -1
    }

    @JvmStatic
    fun setPadButton(port: Int, index: Int, range: Int, pressed: Boolean) {
        val pad = pads.getOrNull(port) ?: return
        val slot = dirSlot(index)
        if (slot >= 0) {
            // `range` is the deflection. A press with range 0 (the input layer's
            // "full press" convention for digital keys bound to a stick row)
            // still means fully deflected.
            pad.dir[slot] = when {
                !pressed -> 0f
                range <= 0 -> 1f
                else -> (range / 32767f).coerceIn(0f, 1f)
            }
        } else {
            applyButton(pad, index, pressed)
        }
        push(port)
    }

    @JvmStatic
    fun setStick(port: Int, left: Boolean, x: Int, y: Int) {
        val pad = pads.getOrNull(port) ?: return
        // Absolute axis -> the two opposing deflections it implies.
        fun split(v: Int): Pair<Float, Float> {
            val n = ((v.coerceIn(0, 255) - 128) / 127f).coerceIn(-1f, 1f)
            return if (n >= 0f) n to 0f else 0f to -n
        }
        val (xPos, xNeg) = split(x)
        val (yPos, yNeg) = split(y)
        val base = if (left) 0 else 4
        pad.dir[base + 1] = xPos; pad.dir[base + 3] = xNeg  // right / left
        pad.dir[base + 2] = yPos; pad.dir[base + 0] = yNeg  // down / up
        push(port)
    }

    private fun push(port: Int) {
        val pad = pads.getOrNull(port) ?: return
        runCatching {
            RPCSX.instance.overlayPadData(
                port, pad.digital1, pad.digital2,
                pad.leftX(), pad.leftY(), pad.rightX(), pad.rightY(),
            )
        }
    }

    private fun resetPadState() {
        pads.forEach { it.reset() }
    }

    // ---------------------------------------------------------------
    // Android-side knobs (handled outside the core)
    // ---------------------------------------------------------------

    @JvmStatic
    fun setAdpfEnabled(enabled: Boolean) { Unsupported.note("setAdpfEnabled") }
    @JvmStatic
    fun setAffinityMode(mode: Int) { Unsupported.note("setAffinityMode") }

    @JvmStatic
    fun log(msg: String) { android.util.Log.i("ARMSX3", msg) }

    // ---------------------------------------------------------------
    // Java-side helpers ARMSX2's UI expects
    // ---------------------------------------------------------------

    @JvmStatic
    fun getContext(): Context? = appContext

    @JvmStatic
    fun createDirectoryPath(path: String): Boolean =
        runCatching { File(path).let { it.exists() || it.mkdirs() } }.getOrDefault(false)

    @JvmStatic
    fun createFilePath(path: String): Boolean = runCatching {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.exists() || f.createNewFile()
    }.getOrDefault(false)

    @JvmStatic
    fun openContentUri(uriString: String): Int = runCatching {
        val ctx = appContext ?: return -1
        val uri = android.net.Uri.parse(uriString)
        ctx.contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: -1
    }.getOrDefault(-1)

    @JvmStatic
    fun playSound(path: String) { Unsupported.note("playSound") }

    @JvmStatic
    fun touchHaptic() {
        val ctx = appContext ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

package com.armsx3

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import net.rpcsx.BootResult
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

    /**
     * Keep the emulator's data out of the user's gallery.
     *
     * The data root holds hundreds of real PNGs that are not the user's pictures: trophy
     * icons under dev_hdd0/home, the whole dev_flash VSH resource set, and an ICON0.PNG for
     * every installed game. On a device where the data root is on shared storage the media
     * scanner indexes all of it, and they turn up in the camera roll. Reported with 216
     * images already indexed.
     *
     * An empty ".nomedia" at the root excludes the entire subtree. Writing it before the
     * core initialises matters, because that is what unpacks the firmware and creates most
     * of those files -- once they are indexed, getting them back out is the hard part.
     *
     * Cheap and idempotent, so it runs on every startup rather than being gated on a pref:
     * a user who wipes their data folder gets it back automatically.
     */
    private fun shieldFromMediaScanner(root: String) {
        runCatching {
            val marker = java.io.File(root, ".nomedia")
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.createNewFile()
            }
        }
    }

    @JvmStatic
    fun initialize(rootPath: String) {
        if (RPCSX.initialized) return

        RPCSX.rootDirectory = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
        shieldFromMediaScanner(RPCSX.rootDirectory)
        // Discard database configs split by an older build, so a setting later found to
        // break a game is not left applying forever on machines that already downloaded.
        runCatching { com.armsx2.config.ConfigDatabase.purgeIfStale() }
        RPCSX.instance.initialize(RPCSX.rootDirectory, "00000001", com.armsx2.DeviceTier.socIdentity())
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
    /**
     * Why the last boot() attempt failed — a [BootResult] name, or a short reason for
     * pre-boot failures. Null after a successful boot. MainActivityRuntime reads this to
     * tell the user WHY a launch bounced back to the library, because boot()'s Boolean
     * cannot: BootGame's return code names the exact cause (DecryptionError = missing
     * licence, FirmwareMissing, ...) and it used to be discarded right here.
     */
    @Volatile
    var lastBootError: String? = null

    @JvmStatic
    fun boot(path: String): Boolean {
        val target = if (path.isNotEmpty()) path else {
            val vsh = File(RPCSX.rootDirectory + "config/dev_flash/vsh/module/vsh.self")
            if (!vsh.isFile) {
                lastBootError = "firmware not installed"
                android.util.Log.e("ARMSX3", "XMB requested but ${'$'}{vsh.absolutePath} is missing - install firmware first")
                return false
            }
            vsh.absolutePath
        }
        // Canary patches must be in patch.yml BEFORE the core reads it, which happens
        // inside boot. Doing it here rather than at app start also means it runs after
        // the config directory exists: patchesImport writes into it, and on a first-ever
        // run that directory only appears once setup has picked a storage location.
        //
        // Cheap after the first success -- it is a single preference read once the
        // bundled revision matches.
        appContext?.let { com.armsx2.Ps3PatchRepo.ensureBundledPatches(it) }

        // Before the core can draw a native overlay. cellSaveData's list is one, and it draws
        // its rows with save.png/new.png -- absent, the load menu a game opens never appeared.
        // Same reasoning as the patches above for doing it here: the config directory exists by
        // now, and it is a preference read once staged.
        appContext?.let { com.armsx2.OverlayIcons.ensureBundled(it) }

        val result = RPCSX.boot(target)
        // AlreadyAdded is not a failure: it says the title was already in games.yml, which is
        // the normal case for anything booted before. RPCS3's own front-end passes it through
        // for that reason. Treating it as an error turned a re-boot into "Game failed to
        // start: AlreadyAdded" and sent the user back to the library.
        if (result != BootResult.NoErrors && result != BootResult.AlreadyAdded) {
            lastBootError = result.name
            android.util.Log.e("ARMSX3", "boot failed: ${result.name} path=$target")
            return false
        }
        lastBootError = null

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
        // This used to set `paused` and stop there, on the belief that RPCS3 has no explicit pause
        // entry point. It does -- Emu.Pause() -- and the core has always called it on surface loss,
        // which is why BACKGROUNDING the app was the only thing that actually paused while the
        // in-game pause menu left the game running underneath it. resume() reached the core and
        // pause() did not, so the pair was asymmetric and "Pause" was decorative.
        paused = true
        RPCSX.instance.pause()
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

        // Then WAIT for the core to actually be stopped, because kill() does not guarantee it.
        //
        // Restart is where that mattered: stop() ran this, it returned with the emulator still
        // reporting a live VM (logged as "shutdown_return active=true runLoop=true state=RUNNING"),
        // the run loop's finally then started the replacement VM -- and the teardown this kill()
        // had set in motion finished afterwards and took the NEW VM down with it. The log showed
        // two BootGame calls and then "Unloading ISO / Quit with main_window::closeEvent", after
        // which the restart flag was already spent, so the app fell through to the return-to-library
        // branch. That is the "Restart kicks you back to the library" report.
        //
        // Bounded, and deliberately so: a core that never returns to Stopped is a real state (see
        // the second loop in boot() for the same hazard), and a stop that gives up after a few
        // seconds is far better than one that hangs the app forever.
        var waited = 0L
        while (RPCSX.getState() != EmulatorState.Stopped && waited < SHUTDOWN_SETTLE_TIMEOUT_MS) {
            Thread.sleep(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }

        if (RPCSX.getState() != EmulatorState.Stopped) {
            android.util.Log.w(
                "ARMSX3-VM",
                "shutdown: core still ${RPCSX.getState()} after ${waited}ms; proceeding anyway",
            )
        }
    }

    /** How long shutdown() waits for the core to report Stopped before giving up. */
    private const val SHUTDOWN_SETTLE_TIMEOUT_MS = 8_000L

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
        // BEFORE the event. The core reads the size off the renderer thread as soon as it
        // has a live window, and a window whose size it has not been told yet is the case
        // this whole path exists to avoid.
        runCatching { RPCSX.instance.surfaceSizeChanged(width, height) }

        // Read the previous surface first. This assigned currentSurface and THEN tested it
        // for null, so the test could never be true and the event was always CHANGED --
        // which the core does not treat as a reason to resume, leaving the emulator paused
        // after a surface came back.
        val event = if (currentSurface == null) SURFACE_CREATED else SURFACE_CHANGED
        currentSurface = surface
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
                // Defers to the explicit FPS cap rather than forcing "Auto" over it; see
                // Rpcs3Settings.setFrameLimitEnabled for why that mattered.
                "FrameLimitEnable" -> Rpcs3Settings.setFrameLimitEnabled(asBool(value))
                "VsyncEnable" -> Rpcs3Settings.setVsync(asBool(value))
                // PCSX2's "skip duplicate frames" means "do not present a frame identical to
                // the last one". It is harmless there and on by default, which is why Settings
                // defaults it to true. RPCS3 has no equivalent, and this used to map onto
                // Enable Frame Skip, which means something else entirely: drop one frame in
                // every two, unconditionally. Every title therefore presented at half the rate
                // the guest asked for, out of the box, on a fresh install.
                //
                // It also made the frameskip row inert, since applyToInner pushes the explicit
                // frameskip first and this key afterwards, so this one always won. Measured on
                // Mirror's Edge: the guest asks for 30 flips/s, SurfaceFlinger presented 15.0.
                //
                // Dropped rather than remapped. Frameskip belongs to the explicit control,
                // which is the one the user actually set.
                "SkipDuplicateFrames" -> return true
                "DisableShaderCache" -> Rpcs3Settings.setDisableShaderCache(asBool(value))
                // Four keys used to write Output Scaling Mode, and the two with no UI behind
                // them were winning. IntegerScaling and linear_present_mode are PCSX2 keys that
                // no screen in this app exposes, and both wrote the node unconditionally, with
                // IntegerScaling emitted last. So whatever the visible Scaling Mode row asked
                // for was overwritten a moment later.
                //
                // They own nothing the user can see, so they no longer write it at all.
                "IntegerScaling" -> return true
                "linear_present_mode" -> return true
                // This is the visible Scaling Mode row in RendererTab: Nearest, Bilinear, FSR.
                // It used to be read as PCSX2's CAS mode, where anything above zero meant "CAS
                // on", so picking Bilinear asked for FSR and picking Nearest asked for nothing.
                // Map the row's own indices instead.
                //
                // Writes unconditionally, so it has to be emitted before ShaderChainEnabled for
                // the chain to keep the last word, which is what applyToInner now does.
                "CASMode" -> Rpcs3Settings.setOutputScaling(
                    when (asInt(value)) {
                        0 -> "Nearest"
                        2 -> "FidelityFX Super Resolution"
                        else -> "Bilinear"
                    },
                )
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

            // NominalScalar used to drive Core@@Clocks scale from here. It is now unhandled,
            // for the reason described above the PS3 pseudo-sections below: PS3/Core writes
            // the same node from ps3.clocksScale, which is what the Performance tab and the
            // in-game menu are bound to, and this ran afterwards and overwrote it. The live
            // turbo path does not come through here at all (NativeApp.setTurboScalar calls
            // Rpcs3Settings directly), so it is unaffected.
            "Framerate" -> return false

            // SyncMode, OutputLatencyMS and BufferMS: all unhandled now, same reason. Each
            // had a PS3/Audio counterpart writing the same RPCS3 node, and being emitted
            // later, these won every time. BufferMS was wrong on its own terms besides: it
            // turned a buffer size in milliseconds into the boolean "buffering enabled".
            "SPU2/Output" -> return false

            // Settings.applyTo emits these with a "PS3/<section>" pseudo-section so
            // they are unambiguous against the PCSX2 keys sharing this function.
            //
            // Being unambiguous HERE was never enough, though. Six PCSX2 keys used to reach
            // the same RPCS3 nodes these do, from a leftover mapping written before the
            // pseudo-sections existed:
            //
            //   Enable Time Stretching        <- SPU2/Output/SyncMode
            //   Desired Audio Buffer Duration <- SPU2/Output/OutputLatencyMS
            //   Enable Buffering              <- SPU2/Output/BufferMS
            //   Anisotropic Filter Override   <- EmuCore/GS/MaxAnisotropy
            //   Clocks scale                  <- Framerate/NominalScalar
            //   Frame limit                   <- EmuCore/GS/SyncToHostRefreshRate
            //
            // put() calls setSetting immediately rather than collecting into a map, so the
            // order in applyTo IS the call order, and every one of these PS3 writes happens
            // first (L939-994) with the PCSX2 write later (L1044-1765). The leftover won
            // every single time, and the field the UI is bound to is the PS3 one. That is
            // why the Audio tab's Time Stretching toggle did nothing: it wrote false, and
            // SyncMode wrote true ninety lines later.
            //
            // SyncToHostRefreshRate was the worst of them. It mapped to Frame limit
            // "Display", which resolves to the host panel's refresh, so on a 120Hz handheld
            // it asked for a 120fps cap. It was also guarded by `if (asBool(value))`, so it
            // could only ever set the mode and never clear it: turning the setting off left
            // the cap where it was.
            //
            // Anything added here in future needs the same check. A PCSX2 key and a PS3 key
            // reaching one node is not a merge, it is a race that the source order decides.
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
                "Frame Generation" -> Rpcs3Settings.setFrameGeneration(asInt(value))
                "Frame Generation Performance Mode" -> Rpcs3Settings.setFrameGenPerformance(asBool(value))
                "Frame Generation Flow Scale" -> Rpcs3Settings.setFrameGenFlowScale(asInt(value))
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
                // Both of these were written by applyTo() but had no case here, so they hit
                // `else -> return false` and vanished into Unsupported.note(). Stretch only
                // ever reached the core through the legacy EmuCore/GS AspectRatio key above,
                // which is why the Display Mode row looked inert and the new Screen Aspect
                // Ratio row did nothing at all.
                "Stretch To Display Area" -> Rpcs3Settings.setStretchToDisplay(asBool(value))
                "Display Aspect Override" -> Rpcs3Settings.setDisplayAspectPermille(asInt(value))
                else -> return false
            }

            "PS3/Audio" -> when (key) {
                "Audio Format" -> Rpcs3Settings.setAudioFormat(asInt(value))
                "Audio Channel Layout" -> Rpcs3Settings.setAudioChannelLayout(asInt(value))
                "Enable Time Stretching" -> Rpcs3Settings.setTimeStretching(asBool(value))
                "Enable Buffering" -> Rpcs3Settings.setAudioBuffering(asBool(value))
                "Desired Audio Buffer Duration" -> Rpcs3Settings.setAudioBufferDuration(asInt(value))
                "Renderer" -> Rpcs3Settings.setAudioRenderer(asInt(value))
                "Cubeb Backend" -> Rpcs3Settings.setCubebBackend(asInt(value))
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

            "PS3/Misc" -> when (key) {
                "Silence All Logs" -> Rpcs3Settings.setSilenceAllLogs(asBool(value))
                else -> return false
            }

            "PS3/Net" -> when (key) {
                "Internet enabled" -> Rpcs3Settings.setInternetEnabled(asBool(value))
                "PSN status" -> Rpcs3Settings.setPsnStatus(asInt(value))
                "UPNP Enabled" -> Rpcs3Settings.setUpnp(asBool(value))
                "IP address" -> Rpcs3Settings.setIpAddress(value)
                "Bind address" -> Rpcs3Settings.setBindAddress(value)
                "DNS address" -> Rpcs3Settings.setDnsAddress(value)
                "IP swap list" -> Rpcs3Settings.setIpSwapList(value)
                "Derive MAC from PSID" -> Rpcs3Settings.setDeriveMacFromPsid(asBool(value))
                "PSN Country" -> Rpcs3Settings.setPsnCountry(value)
                "Clans Enabled" -> Rpcs3Settings.setClansEnabled(asBool(value))
                else -> return false
            }

            // The console's own identity, as cellSysutil reports it to games.
            //
            // This whole section was missing, which made "Enter button assignment" inert: it had
            // a field, a UI row and an applyTo write, and then fell through to Unsupported.note()
            // and never reached the core. Everything Settings.applyTo emits has to appear here or
            // it is silently dropped -- there is no build error for a forgotten key.
            //
            // Values cross as indices and Rpcs3Settings turns them into the core's enum NAMES;
            // see the tables there for why an index cannot be mapped arithmetically.
            "PS3/System" -> when (key) {
                "Language" -> Rpcs3Settings.setConsoleLanguage(asInt(value))
                "License Area" -> Rpcs3Settings.setConsoleRegion(asInt(value))
                "Keyboard Type" -> Rpcs3Settings.setKeyboardType(asInt(value))
                "Date Format" -> Rpcs3Settings.setDateFormat(asInt(value))
                "Time Format" -> Rpcs3Settings.setTimeFormat(asInt(value))
                "Enter button assignment" -> Rpcs3Settings.setEnterButtonAssign(asInt(value))
                else -> return false
            }

            // Named for the config node it writes, unlike the PS3/* pseudo-sections above.
            // Without this case the key fell through to the else and was dropped, so the
            // setting never reached the core: savestates failed to lock the SPUs and told
            // the user to enable an option that had no effect however they set it.
            "Savestate" -> when (key) {
                "Compatible Savestate Mode" ->
                    Rpcs3Settings.setCompatibleSavestateMode(asBool(value))
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
     * Addressable slots: save to 3, load 3, get that state back.
     *
     * RPCS3 natively offers only a rolling HISTORY -- writing a state pushes it to the front,
     * so index 1 is the newest, 2 the one before it (depth is Savestate@@"Maximum SaveState
     * Files") -- and nothing can be pinned to a chosen number. These used to hand that model
     * straight through, dropping the slot on save and reading it as an age on load, so "save
     * to slot 3" pushed a new newest state and "load slot 3" fetched the fourth-newest. The
     * numbers on screen meant nothing and states appeared to wander between slots.
     *
     * The history is still RPCS3's to manage. The core now also parks a COPY of each save
     * under its slot number, so a slot holds what was put in it until it is overwritten.
     * See _rpcsx_saveStateToSlot in rpcsx-android.cpp. Slot numbers are 0-based on both
     * sides now, with no index arithmetic in between.
     */
    @JvmStatic
    fun saveState(slot: Int): Boolean =
        runCatching { RPCSX.instance.saveStateToSlot(slot) }.getOrDefault(false)

    @JvmStatic
    fun loadState(slot: Int): Boolean =
        runCatching { RPCSX.instance.loadStateFromSlot(slot) }.getOrDefault(false)

    /** Whether this slot holds a state. */
    @JvmStatic
    fun hasState(slot: Int): Boolean =
        runCatching { RPCSX.instance.hasStateInSlot(slot) }.getOrDefault(false)

    /**
     * The auto-save lives one slot above the ten the picker shows.
     *
     * Auto-save-on-exit, auto-load-on-boot and the interval auto-save were ARMSX2 shims that
     * returned false and were never ported, so all three toggles persisted, read back, and
     * did nothing -- the interval job woke on schedule for a function that always failed.
     * Nothing bounds a slot number on either side of the JNI, so they reuse the numbered-slot
     * path that works rather than growing a second mechanism, and the user's ten stay theirs.
     */
    private const val AUTOSAVE_SLOT = 10

    @JvmStatic
    fun hasAutosaveState(): Boolean = hasState(AUTOSAVE_SLOT)

    @JvmStatic
    fun saveAutosaveState(): Boolean = saveState(AUTOSAVE_SLOT)

    @JvmStatic
    fun loadAutosaveState(): Boolean = loadState(AUTOSAVE_SLOT)

    /**
     * Absolute path a slot's state file would occupy, whether or not one is there.
     *
     * gamePathForSlot answers with the TITLE ID -- the picker wants it as a subtitle -- so
     * anything treating it as a path gets a relative name that resolves against the process
     * working directory. Import did exactly that. This is the real location, built the way
     * armsx3_slot_dir builds it natively.
     */
    @JvmStatic
    fun slotFilePath(slot: Int): String? = runCatching {
        val title = RPCSX.instance.getTitleId().takeIf { it.isNotEmpty() } ?: return null
        val root = com.armsx2.runtime.MainActivityRuntime.systemDirPosix()
            ?: com.armsx2.runtime.MainActivityRuntime.instance
                ?.applicationContext?.getExternalFilesDir(null)?.absolutePath
            ?: return null
        java.io.File(root, "config/savestates/$title/armsx3_slots/slot$slot.SAVESTAT.zst").absolutePath
    }.getOrNull()

    /**
     * Occupancy for the slot picker, which treats a non-empty string as "this slot has
     * something in it" and shows the last path segment as the tile's subtitle.
     *
     * This was a stub returning "", so every tile read as empty and Load was disabled on all
     * ten of them no matter what was on disk. Saving had been working the whole time and
     * there was no way to see it. hasState was written for exactly this and had no callers.
     *
     * The title id is the subtitle rather than a file path: the picker strips to the last
     * segment and drops the extension, which would turn the real path into "slot0.SAVESTAT".
     * Empty when no game is running, since slots are per title and the core cannot resolve
     * which title's slots to answer for.
     */
    /**
     * Slot preview as PNG bytes, or null.
     *
     * The core writes "AX3T" + u32 width + u32 height + RGBA8 beside the state when it saves.
     * Read here rather than through JNI because the file sits under a path this side already
     * knows, and re-encoded to PNG because the picker decodes with BitmapFactory. Encoding on
     * this side keeps a compressor out of the emulator core for what is only ever a tile.
     */
    @JvmStatic
    fun thumbnailForSlot(slot: Int): ByteArray? = runCatching {
        val title = RPCSX.instance.getTitleId().takeIf { it.isNotEmpty() } ?: return null
        // systemDirPosix() is null on the DEFAULT install, where no folder was ever picked,
        // so this returned no thumbnail at all for most setups -- the tiles read as empty
        // while the files sat on disk beside the states they belong to. Same fallback
        // inputProfilesDir() uses, and getExternalFilesDir is where the native core roots
        // fs::get_config_dir(), which is where it wrote these.
        val root = com.armsx2.runtime.MainActivityRuntime.systemDirPosix()
            ?: com.armsx2.runtime.MainActivityRuntime.instance
                ?.applicationContext?.getExternalFilesDir(null)?.absolutePath
            ?: return null
        val file = java.io.File(root, "config/savestates/$title/armsx3_slots/slot$slot.thumb")
        if (!file.isFile) return null

        val raw = file.readBytes()
        val header = 12
        if (raw.size < header || raw[0] != 'A'.code.toByte() || raw[1] != 'X'.code.toByte() ||
            raw[2] != '3'.code.toByte() || raw[3] != 'T'.code.toByte()
        ) return null

        val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val width = buf.getInt(4)
        val height = buf.getInt(8)
        // Trust nothing about a file on shared storage: a bad size here is an allocation, not
        // a parse error.
        if (width !in 1..4096 || height !in 1..4096) return null
        if (raw.size < header + width * height * 4) return null

        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(raw, header, width * height * 4))

        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    @JvmStatic
    fun gamePathForSlot(slot: Int): String {
        if (!hasState(slot)) return ""
        return runCatching { RPCSX.instance.getTitleId() }.getOrDefault("").ifEmpty { "SAVESTATE" }
    }

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

        /** Analog pressure per pressure-capable button, in CELL_PAD press-offset
         *  order, 1..255, or 0 for "leave this one digital". See [PRESSURE_SLOT]. */
        val pressure = IntArray(PRESSURE_SLOTS)

        /** Last array actually pushed, so an all-digital pad does not send one
         *  JNI call per input event for values that never change. */
        val pressureSent = IntArray(PRESSURE_SLOTS)

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
            pressure.fill(0)
            // NOT pressureSent: it tracks what the core currently holds, and the
            // core is not reset here. Clearing it would skip the push that tells
            // the core to drop a pressure left applied from before the reset.
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

    // ---- Digital transition pacing -----------------------------------------------------------
    //
    // The guest polls cellPad at ITS rate -- 33ms at 30fps -- and a press plus its release are two
    // separate snapshot pushes with nothing between them. A press shorter than one poll interval
    // therefore lands entirely between polls and the game never sees it. Steady presses always
    // span a poll, which is why a button works everywhere except during a rapid mash, and why a
    // held button (crouch) keeps working while everything tapped alongside it does not.
    //
    // GestureLayer already knew this and works around it locally with a 40ms hold in pulse();
    // ordinary touch taps and physical controller presses had no equivalent. Reported on Iron
    // Man's quick-time event, where circle has to be pressed repeatedly and does not register.
    //
    // Simply DELAYING a too-short release is not enough, and would look identical to the bug: in a
    // mash, press N's deferred release collides with press N+1 and the game sees one long press
    // instead of several, while a QTE is counting presses. So each transition is given its own
    // slot instead -- press visible for MIN_MS, then release visible for MIN_MS, then the next
    // press -- which is what makes a mash arrive as distinct presses rather than a hold.
    //
    // 40ms matches the gesture path and clears one 60Hz sample; it caps sustained mashing at
    // ~12 presses/second, comfortably above human rate (~6-8).
    private const val TRANSITION_MIN_MS = 40L

    // Queue depth guard: if the guest stops consuming (paused, load screen) a held-down mash must
    // not accumulate unboundedly. Beyond this, the oldest pending transitions are what matter
    // least, so new ones are dropped rather than growing the backlog.
    private const val TRANSITION_MAX_PENDING = 8

    private val transitionScheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "armsx3-pad-pacing").apply { isDaemon = true }
        }
    }

    // Per (port, button) next instant a transition may become visible, and how many are queued.
    private val nextTransitionAt = HashMap<Long, Long>()
    private val pendingTransitions = HashMap<Long, Int>()

    // The last state each button was asked for, so a REPEAT can be told from an EDGE.
    //
    // Analog triggers write through here on every motion event -- dozens a second while held,
    // every one of them "pressed" -- and pacing a repeat is meaningless: there is no
    // transition to hold open. Worse, the repeats consumed the queue, so the release that
    // followed was the entry that hit the cap and got dropped, leaving the button stuck down
    // until the next press. That is the "R2 double-presses, or sticks until I press it again"
    // report, and it is why only triggers showed it.
    private val lastRequested = HashMap<Long, Boolean>()

    /**
     * Apply a digital press/release, pacing it so the guest cannot miss it.
     *
     * Returns true when the caller should apply and push immediately; false when this transition
     * has been scheduled instead and the caller must do nothing.
     */
    private fun paceDigital(port: Int, index: Int, pressed: Boolean): Boolean {
        val key = (port.toLong() shl 32) or (index.toLong() and 0xffffffffL)
        val now = android.os.SystemClock.uptimeMillis()

        synchronized(nextTransitionAt) {
            // Not an edge: the button is already being asked for the state it is in. Let it
            // straight through without touching the pacing budget, so an analog trigger's
            // stream of same-state updates keeps its pressure current and cannot starve the
            // release that follows it.
            if (lastRequested[key] == pressed) {
                return true
            }

            lastRequested[key] = pressed

            val ready = nextTransitionAt[key] ?: 0L

            if (now >= ready) {
                // Nothing queued ahead of this one: let it through now and reserve its slot.
                nextTransitionAt[key] = now + TRANSITION_MIN_MS
                return true
            }

            val queued = pendingTransitions[key] ?: 0

            if (queued >= TRANSITION_MAX_PENDING) {
                // Never drop a release. Losing a press costs one input; losing a release
                // leaves the pad holding a button the user let go of, and nothing clears it
                // until they press it again. Apply it now and reset the budget -- a release
                // arriving late is worth less than a button that stays down.
                if (!pressed) {
                    nextTransitionAt[key] = now + TRANSITION_MIN_MS
                    return true
                }

                return false
            }

            pendingTransitions[key] = queued + 1
            nextTransitionAt[key] = ready + TRANSITION_MIN_MS

            transitionScheduler.schedule({
                synchronized(nextTransitionAt) {
                    pendingTransitions[key] = (pendingTransitions[key] ?: 1) - 1
                }

                runCatching {
                    val pad = pads.getOrNull(port)

                    if (pad != null) {
                        applyButton(pad, index, pressed)
                        push(port)
                    }
                }
            }, ready - now, java.util.concurrent.TimeUnit.MILLISECONDS)

            return false
        }
    }

    /**
     * Attach or detach the emulated PS3 keyboard.
     *
     * RPCS3 decides this once, in Emulator::Load, from Input/Output@@Keyboard --
     * there is no live attach the way a USB device has one. So this writes the
     * setting and the next boot picks it up; a running game keeps whatever it
     * started with. Flipping it mid-game and expecting cellKb to notice is the one
     * thing this cannot do.
     */
    @JvmStatic
    fun setKeyboardEnabled(enabled: Boolean) {
        Rpcs3Settings.setKeyboardHandler(enabled)
    }

    // ---- RPCN ----
    //
    // Thin pass-throughs; every one blocks on the network and the callers run them off the
    // main thread. A failure comes back as a sentence from the core rather than a code, so
    // the two error enums are not duplicated here.

    @JvmStatic
    fun rpcnGetConfig(): String =
        runCatching { RPCSX.instance.rpcnGetConfig() }.getOrDefault("")

    @JvmStatic
    fun rpcnSetConfig(host: String, npid: String, password: String, token: String) {
        runCatching { RPCSX.instance.rpcnSetConfig(host, npid, password, token) }
    }

    @JvmStatic
    fun rpcnCreateAccount(npid: String, password: String, onlineName: String, email: String): String =
        runCatching { RPCSX.instance.rpcnCreateAccount(npid, password, onlineName, email) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnResendToken(npid: String, password: String): String =
        runCatching { RPCSX.instance.rpcnResendToken(npid, password) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnSendResetToken(npid: String, email: String): String =
        runCatching { RPCSX.instance.rpcnSendResetToken(npid, email) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnResetPassword(npid: String, token: String, password: String): String =
        runCatching { RPCSX.instance.rpcnResetPassword(npid, token, password) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnTestLogin(): String =
        runCatching { RPCSX.instance.rpcnTestLogin() }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnAddHost(desc: String, host: String): String =
        runCatching { RPCSX.instance.rpcnAddHost(desc, host) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnDelHost(desc: String, host: String): String =
        runCatching { RPCSX.instance.rpcnDelHost(desc, host) }
            .getOrElse { "Could not reach the emulator core." }

    @JvmStatic
    fun rpcnResetHosts() {
        runCatching { RPCSX.instance.rpcnResetHosts() }
    }

    @JvmStatic
    fun rpcnSetIpv6(enabled: Boolean) {
        runCatching { RPCSX.instance.rpcnSetIpv6(enabled) }
    }

    /** One key transition for cellKb. See RPCSX.keyboardKey. */
    @JvmStatic
    fun keyboardKey(androidKeyCode: Int, unicode: Int, pressed: Boolean): Boolean =
        runCatching {
            RPCSX.instance.keyboardKey(androidKeyCode, unicode, pressed, false)
        }.getOrDefault(false)

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
            // Paced: a press too short to span a guest poll would otherwise be dropped entirely.
            if (!paceDigital(port, index, pressed)) {
                return
            }

            applyButton(pad, index, pressed)

            // `range` was dropped here for every non-stick button, so a physical
            // trigger and the touch overlay's pressure modifier both arrived with
            // a magnitude that nothing ever read: the button was pressed or it was
            // not. Same "range 0 means a full press" convention as the stick rows
            // above, so a plain digital press still reads as 255.
            val pslot = PRESSURE_SLOT[index] ?: -1
            if (pslot >= 0) {
                pad.pressure[pslot] = when {
                    !pressed -> 0
                    range <= 0 -> 255
                    else -> ((range / 32767f) * 255f).toInt().coerceIn(1, 255)
                }
            }
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
            // Pressure first: overlayPadData is what writes each button's value, so
            // it has to see the current pressure to apply it in the same push.
            if (!pad.pressure.contentEquals(pad.pressureSent)) {
                if (RPCSX.instance.overlayPadPressure(port, pad.pressure)) {
                    pad.pressure.copyInto(pad.pressureSent)
                }
            }
            RPCSX.instance.overlayPadData(
                port, pad.digital1, pad.digital2,
                pad.leftX(), pad.leftY(), pad.rightX(), pad.rightY(),
            )
        }
    }

    /**
     * Android keycode -> its slot in [PadState.pressure], which is CELL_PAD press
     * offset order (PRESS_RIGHT..PRESS_R2, contiguous in pad_types.h). Only the
     * twelve buttons a PS3 pad reports pressure for; Select, Start, L3 and R3 have
     * no press byte and are absent on purpose.
     */
    private val PRESSURE_SLOT: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_DPAD_RIGHT to 0,
        KeyEvent.KEYCODE_DPAD_LEFT to 1,
        KeyEvent.KEYCODE_DPAD_UP to 2,
        KeyEvent.KEYCODE_DPAD_DOWN to 3,
        KeyEvent.KEYCODE_BUTTON_Y to 4,   // triangle
        KeyEvent.KEYCODE_BUTTON_B to 5,   // circle
        KeyEvent.KEYCODE_BUTTON_A to 6,   // cross
        KeyEvent.KEYCODE_BUTTON_X to 7,   // square
        KeyEvent.KEYCODE_BUTTON_L1 to 8,
        KeyEvent.KEYCODE_BUTTON_R1 to 9,
        KeyEvent.KEYCODE_BUTTON_L2 to 10,
        KeyEvent.KEYCODE_BUTTON_R2 to 11,
    )

    private const val PRESSURE_SLOTS = 12

    private fun resetPadState() {
        pads.forEach { it.reset() }
    }

    // ---------------------------------------------------------------
    // Android-side knobs (handled outside the core)
    // ---------------------------------------------------------------

    @JvmStatic
    /** Drive PerformanceHintManager from the core's own frame timing. Was a no-op stub
     *  inherited from the ARMSX2 UI port, so the settings toggle did nothing. */
    fun setAdpfEnabled(enabled: Boolean) {
        val ctx = com.armsx2.runtime.MainActivityRuntime.instance?.applicationContext ?: return
        AdpfHinter.setEnabled(ctx, enabled)
    }
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

    // ---- Rumble -------------------------------------------------------
    //
    // The pad already advertises CELL_PAD_CAPABILITY_ACTUATOR, so games have always
    // believed rumble existed here and asked for it; nothing was listening. cellPad
    // gives no notification when the guest writes the motors, so this polls what the
    // core currently wants and drives the phone's vibrator to match.

    private var rumbleThread: Thread? = null
    @Volatile private var rumbleRunning = false
    @Volatile private var rumbleEnabled = true

    private fun vibrator(): Vibrator? {
        val ctx = appContext ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @JvmStatic
    fun setPadVibration(on: Boolean) {
        rumbleEnabled = on
        if (!on) vibrator()?.cancel()
    }

    /** Start following the core's motor state. Idempotent. */
    @JvmStatic
    fun startRumblePump() {
        if (rumbleRunning) return
        val vib = vibrator() ?: return
        if (!vib.hasVibrator()) return

        rumbleRunning = true
        rumbleThread = Thread {
            var lastAmplitude = 0
            while (rumbleRunning) {
                val packed = runCatching { RPCSX.instance.getPadRumble(0) }.getOrDefault(0)
                val large = (packed shr 8) and 0xFF
                val small = packed and 0xFF

                // One vibrator, two motors: take the stronger. The small motor is the
                // high-frequency one and reads as weaker for the same value, so it is
                // scaled down rather than competing with the large one on equal terms.
                val want = if (!rumbleEnabled) 0 else maxOf(large, small * 2 / 3)

                if (want != lastAmplitude) {
                    runCatching {
                        if (want <= 0) {
                            vib.cancel()
                        } else {
                            // Repeating one-shot rather than a fixed duration: the guest
                            // decides when rumble stops, and a timed effect would either cut
                            // a long rumble short or outlive a brief one.
                            vib.vibrate(
                                VibrationEffect.createWaveform(
                                    longArrayOf(0, 60), intArrayOf(0, want.coerceIn(1, 255)), 0
                                )
                            )
                        }
                    }
                    lastAmplitude = want
                }

                try { Thread.sleep(30) } catch (_: InterruptedException) { break }
            }
            runCatching { vib.cancel() }
        }.apply { isDaemon = true; name = "rumble-pump"; start() }
    }

    @JvmStatic
    fun stopRumblePump() {
        rumbleRunning = false
        rumbleThread?.interrupt()
        rumbleThread = null
        runCatching { vibrator()?.cancel() }
    }

    /** Settings' test button: a short burst so the user can tell the motor works. */
    @JvmStatic
    fun testRumble(port: Int) {
        val vib = vibrator() ?: return
        runCatching {
            vib.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Text for the test toast. Returned "" before, which is why the popup had none. */
    @JvmStatic
    fun rumbleStatusForPort(port: Int): String {
        val vib = vibrator()
        return when {
            vib == null || !vib.hasVibrator() -> "This device has no vibration motor"
            !rumbleEnabled -> "Vibration is switched off in settings"
            else -> "Vibration test sent to player ${port + 1}"
        }
    }

    // ---- SIXAXIS motion ------------------------------------------------

    /**
     * Feed the phone's orientation to the pad's motion sensors.
     *
     * [ax]/[ay]/[az] are gravity-relative acceleration in g, [gyro] a yaw rate in
     * rad/s. The PS3 reports each axis 0..1023 with 512 at rest and roughly 113 units
     * per g, which is what a DualShock 3 produces lying flat.
     */
    @JvmStatic
    fun setPadMotion(port: Int, ax: Float, ay: Float, az: Float, gyro: Float) {
        val center = 512
        val perG = 113f
        fun axis(v: Float) = (center + (v * perG)).toInt().coerceIn(0, 1023)
        // Gyro is a rate, not a position: scale so a brisk turn approaches the rails
        // without a gentle one being lost in the noise.
        val g = (center + (gyro * 120f)).toInt().coerceIn(0, 1023)
        runCatching { RPCSX.instance.setPadSensor(port, axis(ax), axis(ay), axis(az), g) }
    }

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

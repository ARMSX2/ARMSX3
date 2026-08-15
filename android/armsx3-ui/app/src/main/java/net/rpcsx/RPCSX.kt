package net.rpcsx

import android.view.Surface
import androidx.compose.runtime.mutableStateOf

enum class Digital1Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_SELECT(0x00000001),
    CELL_PAD_CTRL_L3(0x00000002),
    CELL_PAD_CTRL_R3(0x00000004),
    CELL_PAD_CTRL_START(0x00000008),
    CELL_PAD_CTRL_UP(0x00000010),
    CELL_PAD_CTRL_RIGHT(0x00000020),
    CELL_PAD_CTRL_DOWN(0x00000040),
    CELL_PAD_CTRL_LEFT(0x00000080),
    CELL_PAD_CTRL_PS(0x00000100),
}

enum class Digital2Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_L2(0x00000001),
    CELL_PAD_CTRL_R2(0x00000002),
    CELL_PAD_CTRL_L1(0x00000004),
    CELL_PAD_CTRL_R1(0x00000008),
    CELL_PAD_CTRL_TRIANGLE(0x00000010),
    CELL_PAD_CTRL_CIRCLE(0x00000020),
    CELL_PAD_CTRL_CROSS(0x00000040),
    CELL_PAD_CTRL_SQUARE(0x00000080),
};

enum class EmulatorState {
    Stopped,
    Loading,
    Stopping,
    Running,
    Paused,
    Frozen, // paused but cannot resume
    Ready,
    Starting;

    companion object {
        fun fromInt(value: Int) = EmulatorState.entries.first { it.ordinal == value }
    }
}

enum class BootResult
{
    NoErrors,
    GenericError,
    NothingToBoot,
    WrongDiscLocation,
    InvalidFileOrFolder,
    InvalidBDvdFolder,
    InstallFailed,
    DecryptionError,
    FileCreationError,
    FirmwareMissing,
    UnsupportedDiscType,
    SavestateCorrupted,
    SavestateVersionUnsupported,
    StillRunning,
    AlreadyAdded,
    CurrentlyRestricted;

    companion object {
        fun fromInt(value: Int) = entries.first { it.ordinal == value }
    }
};

class RPCSX {
    external fun openLibrary(path: String): Boolean

    /** Extract the Lossless Scaling shaders from a real filesystem path. Returns how many were
     *  found, or a negative value; frameGenShaderError() then explains why. */
    external fun frameGenImportShaders(path: String): Int
    external fun frameGenShaderCount(): Int
    external fun frameGenShaderError(): String
    external fun getLibraryVersion(path: String): String?
    external fun initialize(rootDir: String, user: String, socInfo: String): Boolean
    external fun installFw(fd: Int, progressId: Long): Boolean
    external fun install(fd: Int, progressId: Long): Boolean
    /** Install several .pkg parts of one split package together, in order. */
    external fun installSplitPkg(fds: IntArray, progressId: Long): Boolean
    /** Delete an installed title's directory. Refused for paths outside dev_hdd0/game. */
    external fun uninstallGame(path: String): Boolean
    external fun installKey(fd: Int, requestId: Long, gamePath: String): Boolean
    external fun boot(path: String): Int
    external fun surfaceEvent(surface: Surface, event: Int): Boolean

    /**
     * The surface's pixel size, as SurfaceHolder reports it.
     *
     * Separate from surfaceEvent because rotation is a size change and nothing else: the
     * activity keeps the same Surface, so the core has no other way to learn the window
     * changed shape.
     */
    external fun surfaceSizeChanged(width: Int, height: Int)
    external fun usbDeviceEvent(fd: Int, vendorId: Int, productId: Int, event: Int): Boolean
    external fun processCompilationQueue(): Boolean
    external fun startMainThreadProcessor(): Boolean
    external fun overlayPadData(port: Int, digital1: Int, digital2: Int, leftStickX: Int, leftStickY: Int, rightStickX: Int, rightStickY: Int): Boolean
    /** Analog pressure per pressure-capable button, in CELL_PAD press-offset order
     *  (RIGHT, LEFT, UP, DOWN, TRIANGLE, CIRCLE, CROSS, SQUARE, L1, R1, L2, R2),
     *  each 1..255, or 0 to leave that button digital. */
    external fun overlayPadPressure(port: Int, values: IntArray): Boolean
    external fun collectGameInfo(rootDir: String, progressId: Long): Boolean
    external fun systemInfo(): String
    external fun settingsGet(path: String): String
    external fun settingsSet(path: String, value: String): Boolean
    /** Coalesce the config file writes of every settingsSet until [settingsEndBatch]. */
    external fun settingsBeginBatch()
    external fun settingsEndBatch()
    external fun getState() : Int
    external fun kill()
    external fun resume()
    external fun pause()
    external fun openHomeMenu()
    external fun loginUser(userId: String)
    external fun getUser(): String
    external fun getTitleId(): String
    /** The running game's trophy folder name (NPWR comm id, e.g. "NPWR05636_00").
     *
     *  Empty when no game is running, when the game has not called
     *  sceNpTrophyCreateContext yet (many only do on reaching a menu), or when it has
     *  no trophies. Returns null on a core too old to export it, so callers must treat
     *  the result as nullable despite the declared type. */
    external fun getCurrentTrophyName(): String
    /** Pin the Adreno GPU to max clocks (no DVFS ramp) or release it. No-ops on non-Adreno.
     *  Costs heat and battery, so it is opt-in. Safe before the core is loaded -- adrenotools
     *  is linked into the JNI library, not the core. */
    external fun setGpuTurbo(on: Boolean)
    /** ADPF telemetry: flip-to-flip period, CPU work in that window, and the presenting
     *  thread's OS tid. All return 0 when not yet measured -- treat 0 as "skip". */
    external fun getFramePeriodNs(): Long
    external fun getFrameWorkNs(): Long
    external fun getRsxThreadTid(): Int
    external fun supportsCustomDriverLoading(): Boolean
    external fun saveState(): Boolean
    external fun loadState(index: Int): Boolean
    external fun hasState(index: Int): Boolean

    // Numbered slots. The three above walk RPCS3's rolling history by age (1 = most
    // recent); these address a fixed slot, which is what the ten-slot UI means.
    external fun saveStateToSlot(slot: Int): Boolean
    external fun loadStateFromSlot(slot: Int): Boolean
    external fun hasStateInSlot(slot: Int): Boolean
    external fun patchEngineVersion(): String
    external fun patchesImport(content: String): Int
    external fun patchesList(serial: String): String
    external fun probeDiscInfo(isoPath: String, iconOut: String): String
    external fun patchSetEnabled(
        hash: String, description: String, serial: String,
        appVersion: String, enabled: Boolean,
    ): Boolean
    external fun isInstallableFile(fd: Int) : Boolean
    external fun getDirInstallPath(sfoFd: Int) : String?
    external fun getVersion(): String
    external fun setCustomDriver(path: String, libraryName: String, hookDir: String): Boolean


    companion object {
        var initialized = false
        val instance = RPCSX()
        var rootDirectory = ""
        var nativeLibDirectory = ""
        var lastPlayedGame = ""
        var activeGame = mutableStateOf<String?>(null)
        var state = mutableStateOf(EmulatorState.Stopped)
        var activeLibrary = mutableStateOf<String?>(null)

        fun boot(path: String): BootResult {
            return BootResult.fromInt(instance.boot(path))
        }

        fun updateState() {
            val newState = EmulatorState.fromInt(instance.getState())
            if (newState != state.value) {
                state.value = newState
            }
        }

        fun getState(): EmulatorState {
            updateState()
            return state.value
        }

        fun getHdd0Dir(): String {
            return rootDirectory + "config/dev_hdd0/"
        }

        fun openLibrary(path: String): Boolean {
            if (!instance.openLibrary(path)) {
                return false
            }

            activeLibrary.value = path
            return true
        }

        init {
            // This loads the JNI GLUE (app/src/main/cpp/native-lib.cpp), not the
            // emulator core. The glue is what implements every external fun here;
            // it then dlopen()s libarmsx3-core.so by path via openLibrary(), and
            // forwards each call to the core's _rpcsx_* entry points.
            //
            // Two libraries, two load steps -- loading the core here instead
            // would resolve nothing, because the core exports no JNI symbols.
            System.loadLibrary("armsx3-jni")
        }
    }
}

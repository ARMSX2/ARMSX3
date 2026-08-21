package com.armsx3;

import android.content.Context;
import android.view.Surface;

import com.armsx2.BiosInfo;

/**
 * ARMSX3's native seam.
 *
 * WHY THIS EXISTS
 * ARMSX2's UI reached native through NativeApp in the kr.co.iefriends.pcsx2
 * package - 134 native methods belonging to a THIRD-PARTY rights holder, and
 * bound to PCSX2 besides. We do not copy that. This class re-declares the same
 * functional surface and implements it against RPCS3 instead, so the ARMSX2 UI
 * runs unchanged apart from its import line and nothing third-party crosses over.
 *
 * THREE KINDS OF METHOD LIVE HERE, and the distinction matters:
 *
 *   [MAPPED]      Backed by a real RPCS3 entry point. Works.
 *
 *   [PS3-N/A]     The concept does not exist on PS3 (PS2 memory cards, PNACH
 *                 patches, GUNCON lightguns, EE/VU speedhacks, RetroAchievements
 *                 - which has no PS3 support at all). These return a neutral
 *                 value AND the screen that drives them must be removed. A
 *                 control that silently does nothing is worse than an absent one.
 *
 *   [TODO]        Real PS3 analogue exists in RPCS3 but is not wired yet.
 *                 Distinguished from PS3-N/A on purpose: these are work items,
 *                 not dead ends.
 *
 * Every unimplemented method logs once via Unsupported.note() so a screen that
 * looks alive but is not shows up in logcat rather than being discovered by a
 * user.
 */
public final class NativeApp {

    private NativeApp() {}

    // ---------------------------------------------------------------
    // Low-level binding to the RPCS3 core.
    // The core is dlopen()ed and its _rpcsx_* entry points resolved by
    // the JNI bridge; see Rpcs3Bridge.
    // ---------------------------------------------------------------

    // ===== Lifecycle =====

    /** [MAPPED] */
    public static void initializeOnce(Context context) {
        Rpcs3Bridge.initializeOnce(context);
    }

    /** [MAPPED] -> _rpcsx_initialize */
    public static void initialize(String path, String biosFolder, int apiVer) {
        // biosFolder is a PS2 concept; PS3 firmware lives in dev_flash under the
        // root dir and is installed, not pointed at. apiVer is unused by RPCS3.
        Rpcs3Bridge.initialize(path);
    }

    /** [MAPPED] -> _rpcsx_boot */
    public static boolean runVMThread(String path) {
        return Rpcs3Bridge.boot(path);
    }

    /** [MAPPED] -> _rpcsx_getState */
    public static boolean hasActiveVM() {
        return Rpcs3Bridge.hasActiveVm();
    }

    /** [MAPPED] -> _rpcsx_pause */
    public static void pause() { Rpcs3Bridge.pause(); }

    /** [MAPPED] -> _rpcsx_resume */
    public static void resume() { Rpcs3Bridge.resume(); }

    /** [MAPPED] -> _rpcsx_kill */
    public static void shutdown() { Rpcs3Bridge.shutdown(); }

    /** [MAPPED] */
    public static void vmSetPaused(boolean paused) {
        if (paused) pause(); else resume();
    }

    // ===== Surface =====

    /** [MAPPED] -> _rpcsx_surfaceEvent */
    public static void onNativeSurfaceCreated() { Rpcs3Bridge.surfaceCreated(); }

    /** [MAPPED] -> _rpcsx_surfaceEvent */
    public static void onNativeSurfaceChanged(Surface surface, int w, int h) {
        Rpcs3Bridge.surfaceChanged(surface, w, h);
    }

    /** [MAPPED] -> _rpcsx_surfaceEvent */
    public static void onNativeSurfaceDestroyed() { Rpcs3Bridge.surfaceDestroyed(); }

    // ===== Settings =====

    /**
     * [MAPPED] -> _rpcsx_settingsSet
     *
     * ARMSX2 addresses settings as (section, key, type, value) against a PCSX2
     * INI. RPCS3's tree is addressed by a "@@"-joined path and takes a
     * JSON-encoded value, so we translate. The `type` argument tells us how to
     * encode: strings and enums must be quoted, numbers and bools must not.
     */
    public static void setSetting(String section, String key, String type, String value) {
        Rpcs3Bridge.setSetting(section, key, type, value);
    }

    /** [MAPPED] */
    public static void commitSettings() { Rpcs3Bridge.commitSettings(); }

    // ===== Save states =====

    /** [MAPPED] RPCS3 has savestates; slots map to its own save files. */
    public static boolean saveStateToSlot(int slot) { return Rpcs3Bridge.saveState(slot); }

    /** [MAPPED] */
    public static boolean loadStateFromSlot(int slot) { return Rpcs3Bridge.loadState(slot); }

    /** [MAPPED] Slot preview, captured by the core at save time. Null if the slot has none. */
    public static byte[] getImageSlot(int slot) { return Rpcs3Bridge.thumbnailForSlot(slot); }

    /** [TODO] */
    public static byte[] getSaveStateImage(String path) { Unsupported.note("getSaveStateImage"); return null; }

    /** [MAPPED] Occupancy for the save slot picker. Non-empty means the slot holds a state. */
    public static String getGamePathSlot(int slot) { return Rpcs3Bridge.gamePathForSlot(slot); }

    /** [TODO] Autosave is an ARMSX2 feature layered on PCSX2 savestates. */
    /** [MAPPED] Auto-save state, kept in a reserved slot above the ten the picker shows. */
    public static boolean hasAutosaveState() { return Rpcs3Bridge.hasAutosaveState(); }
    public static boolean saveAutosaveState() { return Rpcs3Bridge.saveAutosaveState(); }
    public static boolean loadAutosaveState() { return Rpcs3Bridge.loadAutosaveState(); }

    /** [MAPPED] Where a slot's state file lives. Not getGamePathSlot, which answers a title id. */
    public static String getSlotFilePath(int slot) { return Rpcs3Bridge.slotFilePath(slot); }

    /** [MAPPED] Whether a slot holds a state. Ask this rather than probing a path. */
    public static boolean hasStateInSlot(int slot) { return Rpcs3Bridge.hasState(slot); }

    /** [MAPPED] Delete a slot's state and thumbnail. The core resolves the real filename. */
    public static boolean deleteStateFromSlot(int slot) { return Rpcs3Bridge.deleteState(slot); }
    public static String getAutosaveGamePath() { Unsupported.note("getAutosaveGamePath"); return ""; }
    public static byte[] getAutosaveImage() { Unsupported.note("getAutosaveImage"); return null; }

    // ===== Game identity =====

    /** [MAPPED] -> _rpcsx_getTitleId. PS3 title id, e.g. BLUS30443. */
    public static String getGameSerial() { return Rpcs3Bridge.getTitleId(); }

    /** [TODO] */
    public static String getGameTitle(String path) { Unsupported.note("getGameTitle"); return ""; }

    /** [MAPPED] -> _rpcsx_getVersion */
    public static String getBuildVersion() { return Rpcs3Bridge.getVersion(); }

    /** [PS3-N/A] PCSX2 identifies discs by CRC; PS3 uses the title id. */
    public static String getGameCRC() { Unsupported.note("getGameCRC"); return ""; }

    /** [TODO] */
    public static String getGameSerialFromFd(int fd) { Unsupported.note("getGameSerialFromFd"); return ""; }
    public static String getPauseGameSerial() { Unsupported.note("getPauseGameSerial"); return getGameSerial(); }
    public static String getPauseGameTitle() { Unsupported.note("getPauseGameTitle"); return ""; }
    public static String getRegionForSerial(String serial) { Unsupported.note("getRegionForSerial"); return ""; }
    public static String getTitlesForSerial(String serial) { Unsupported.note("getTitlesForSerial"); return ""; }
    public static int getCompatibilityForSerial(String serial) { Unsupported.note("getCompatibilityForSerial"); return 0; }

    /** [PS3-N/A] PS2 BIOS is a ROM you point at; PS3 firmware is an installed PUP. */
    public static BiosInfo getBiosInfoFromFd(int fd) { Unsupported.note("getBiosInfoFromFd"); return null; }

    // ===== Stats / OSD =====

    /** [TODO] RPCS3 has a perf overlay with its own config; not bridged yet. */
    public static float getFPS() { return Rpcs3Bridge.getFps(); }
    public static float getNominalFrameRate() { Unsupported.note("getNominalFrameRate"); return 60f; }
    public static int getPresentedFrameCount() { Unsupported.note("getPresentedFrameCount"); return 0; }

    // RPCS3's overlay is configured through its own Video/Performance Overlay
    // config node rather than per-element toggles, so these route into settings
    // rather than each having a native call.
    public static void osdShowAll(boolean e) { Unsupported.note("osdShowAll"); }
    public static void osdShowFPS(boolean e) { Unsupported.note("osdShowFPS"); }
    public static void osdShowCPU(boolean e) { Unsupported.note("osdShowCPU"); }
    public static void osdShowGPU(boolean e) { Unsupported.note("osdShowGPU"); }
    public static void osdShowVPS(boolean e) { Unsupported.note("osdShowVPS"); }
    public static void osdShowSpeed(boolean e) { Unsupported.note("osdShowSpeed"); }
    public static void osdShowInputs(boolean e) { Unsupported.note("osdShowInputs"); }
    public static void osdShowMessages(boolean e) { Unsupported.note("osdShowMessages"); }
    public static void osdShowSettings(boolean e) { Unsupported.note("osdShowSettings"); }
    public static void osdShowVersion(boolean e) { Unsupported.note("osdShowVersion"); }
    public static void osdShowGSStats(boolean e) { Unsupported.note("osdShowGSStats"); }
    public static void osdShowGpuStats(boolean e) { Unsupported.note("osdShowGpuStats"); }
    public static void osdShowFrameTimes(boolean e) { Unsupported.note("osdShowFrameTimes"); }
    public static void osdShowResolution(boolean e) { Unsupported.note("osdShowResolution"); }
    public static void osdShowHardwareInfo(boolean e) { Unsupported.note("osdShowHardwareInfo"); }
    public static void osdSetColor(int rgb) { Unsupported.note("osdSetColor"); }
    public static void osdSetScale(float scale) { Unsupported.note("osdSetScale"); }

    /**
     * [TODO] Signature must match ARMSX2's exactly - the UI passes all twelve
     * flags positionally, so a shorter overload silently fails to resolve at
     * every call site.
     *
     * RPCS3's equivalent is its Performance Overlay config node rather than
     * twelve independent booleans, so wiring this means mapping onto that.
     */
    public static void osdApplyFlags(boolean fps, boolean vps, boolean speed, boolean cpu,
                                     boolean gpu, boolean res, boolean gsStats,
                                     boolean frameTimes, boolean hwInfo, boolean version,
                                     boolean settings, boolean inputs) {
        // RPCS3 has no per-element toggles: one Enabled flag plus a detail_level
        // enum decides what appears. So collapse the twelve booleans onto that -
        // anything on means show the overlay, and the more the user asked for,
        // the higher the detail level.
        boolean any = fps || vps || speed || cpu || gpu || res || gsStats
            || frameTimes || hwInfo || version || settings || inputs;

        Rpcs3Settings.INSTANCE.setOverlayEnabled(any);
        if (!any) return;

        int count = 0;
        for (boolean b : new boolean[] { fps, vps, speed, cpu, gpu, res, gsStats,
                                         frameTimes, hwInfo, version, settings, inputs }) {
            if (b) count++;
        }

        String detail = count <= 1 ? "Minimal" : count <= 3 ? "Low"
                      : count <= 6 ? "Medium" : "High";
        Rpcs3Settings.INSTANCE.setOverlayDetail(detail);

        // These two ARE independent flags in RPCS3.
        Rpcs3Settings.INSTANCE.setOverlayFramerateGraph(fps);
        Rpcs3Settings.INSTANCE.setOverlayFrametimeGraph(frameTimes);
    }

    // ---------------------------------------------------------------
    // Public state ARMSX2's UI reads directly (not via a method).
    // These are `volatile` fields on ARMSX2's NativeApp, so they have to exist
    // as fields here too - a getter would not resolve.
    // ---------------------------------------------------------------

    /** Input device id used for rumble; -1 = none selected. */
    public static volatile int sRumbleDeviceId = -1;

    /** Master rumble toggle. */
    public static volatile boolean sRumbleEnabled = true;

    /** Volume applied to UI sounds played through NativeApp.playSound. */
    public static volatile float sSoundVolume = 1.0f;

    /** Multiplier applied to touch haptic intensity. */
    public static volatile float sHapticScale = 1.0f;

    /**
     * [MAPPED] Custom Vulkan driver via adrenotools.
     *
     * The JNI glue opens the driver with adrenotools_open_libvulkan and hands
     * the resulting handle to the core, which points its Vulkan dispatch table
     * at it. Adreno only -- adrenotools patches Qualcomm's loader behaviour, and
     * supportsCustomVulkanDriver() probes /dev/kgsl-3d0 for exactly that.
     *
     * MUST be called before the renderer creates its VkInstance. Afterwards the
     * table is bound and swapping under a live device would invalidate every
     * object already created against the old driver.
     *
     * redirectDir is accepted for source compatibility with ARMSX2's caller but
     * is not forwarded: ADRENOTOOLS_DRIVER_FILE_REDIRECT is not enabled here.
     */
    public static void setCustomVulkanDriver(String driverDir, String driverName,
                                             String redirectDir, String hookLibDir) {
        if (driverDir == null || driverDir.isEmpty()) {
            // Empty path clears the custom driver and reverts to the system one.
            net.rpcsx.RPCSX.Companion.getInstance().setCustomDriver("", "", "");
            android.util.Log.i("ARMSX3-Driver", "reverted to the system Vulkan driver");
            return;
        }

        boolean ok = net.rpcsx.RPCSX.Companion.getInstance()
                .setCustomDriver(driverDir, driverName, hookLibDir);
        if (ok) {
            android.util.Log.i("ARMSX3-Driver", "custom Vulkan driver applied: " + driverName);
        } else {
            android.util.Log.e("ARMSX3-Driver",
                    "custom Vulkan driver REJECTED: " + driverName + " in " + driverDir);
        }
    }

    /** Whether this device can load a user-supplied Vulkan driver (Adreno only). */
    public static boolean supportsCustomVulkanDriver() {
        try {
            return net.rpcsx.RPCSX.Companion.getInstance().supportsCustomDriverLoading();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== Renderer =====

    /** [MAPPED] RPCS3's renderer is a config enum; these set it. */
    public static void renderVulkan() { Rpcs3Settings.INSTANCE.setRenderer("Vulkan"); }

    /** [PS3-N/A on Android] RPCS3's GL renderer is desktop-GL-only and is
     *  compiled out of the Android build (WITHOUT_OPENGL=1). */
    public static void renderOpenGL() { Unsupported.note("renderOpenGL"); }

    /** [PS3-N/A] PCSX2 has a software rasteriser; RPCS3 does not. */
    public static void renderSoftware() { Unsupported.note("renderSoftware"); }
    public static void renderAuto() { renderVulkan(); }
    public static boolean isHardwareRenderer() { return true; }

    /** [MAPPED] RPCS3's "Resolution Scale" is a percentage, not a multiplier. */
    public static void renderUpscalemultiplier(float value) {
        Rpcs3Settings.INSTANCE.setUpscaleMultiplier(value);
    }

    /** [PS3-N/A] PCSX2 GS hacks with no RSX equivalent. */
    public static void renderHalfpixeloffset(int v) { Unsupported.note("renderHalfpixeloffset"); }
    public static void renderMipmap(int v) { Unsupported.note("renderMipmap"); }
    public static void renderPreloading(int v) { Unsupported.note("renderPreloading"); }
    public static void renderTvShader(int v) { Unsupported.note("renderTvShader"); }
    public static void renderShadeBoost(boolean e, int b, int c, int s, int g) { Unsupported.note("renderShadeBoost"); }
    public static boolean applyGSSettingsLive() { Unsupported.note("applyGSSettingsLive"); return false; }
    public static void setPreferVulkan(boolean e) { /* Vulkan is the only backend */ }

    /**
     * [MAPPED] The console aspect index the UI stores, which is RendererTab's
     * picker: 0 = stretch, 1 = Auto, 2 = 4:3, 3 = 16:9, 4..8 = the ultrawide
     * ratios ARMSX2 offered.
     *
     * RPCS3 only has 4:3 and 16:9 plus a separate "Stretch To Display Area"
     * flag, so index 0 maps to the flag rather than a third enum value, and
     * everything that is not 4:3 lands on 16:9.
     *
     * The doc here used to describe ARMSX2's PS2 enum, where 1 was 4:3, and the
     * check below was written against it. ARMSX3's picker inserted Auto at 1 and
     * pushed 4:3 to 2, so Auto was resolving to 4:3.
     */
    public static void setAspectRatio(int type) {
        if (type == 0) {
            Rpcs3Settings.INSTANCE.setStretchToDisplay(true);
        } else {
            Rpcs3Settings.INSTANCE.setStretchToDisplay(false);
            Rpcs3Settings.INSTANCE.setAspectRatio(type != 2);
        }
    }

    /** [PS3-N/A] PCSX2 applies a separate aspect during FMV playback. */
    public static void setFmvAspectRatio(int type) { Unsupported.note("setFmvAspectRatio"); }

    /** [MAPPED] -> Video@@Frame limit */
    public static void setFpsCap(int fps) { Rpcs3Settings.INSTANCE.setFrameLimit(fps); }

    /** [MAPPED] -> Video@@Enable Frame Skip + Consecutive Frames To Skip */
    public static void setFrameSkip(int skip) { Rpcs3Settings.INSTANCE.setFrameSkip(skip); }

    /**
     * The HOST PANEL's refresh rate. Deliberately dropped.
     *
     * This used to write it to Video@@Vblank Rate, which is a different thing entirely:
     * that is the frequency of the emulated console's vblank, and a PS3 runs 60Hz no
     * matter what display is attached. On a 120Hz handheld the panel rate went in as the
     * console rate and the emulator was asked to produce 120 frames a second, twice the
     * RSX command volume and twice the GPU work, for frames no PS3 game was written to
     * produce. Frame limit Auto follows vblank, so the 60 cap went with it.
     *
     * It also could not be corrected from settings: EmulationSurface reports the panel
     * rate on every surfaceChanged, which is after ApplySettings on boot and again on
     * every rotation and resume, so it overwrote the pushed 60 every time.
     *
     * Nothing is lost by dropping it. RPCS3 reads the host rate itself through
     * get_display_refresh_rate() and uses it for Frame limit "Display"; it never wanted
     * to be told.
     */
    public static void setDisplayRefreshRate(float hz) { Unsupported.note("setDisplayRefreshRate"); }

    /** [MAPPED] emulated clock speed -> Core@@Clocks scale (10..3000 %) */
    public static void setNominalSpeed(int percent) {
        Rpcs3Settings.INSTANCE.setClocksScale(percent);
    }

    /** [MAPPED] turbo multiplier -> the same clock scale. */
    public static void setTurboScalar(float scalar) {
        Rpcs3Settings.INSTANCE.setClocksScale(Math.round(scalar * 100f));
    }

    /**
     * [MAPPED] PS2 had distinct NTSC/PAL refresh rates; PS3 does not split them
     * that way. Use whichever is non-zero as the vblank rate.
     */
    public static void applyFramerateLive(float ntsc, float pal) {
        float hz = ntsc > 0 ? ntsc : pal;
        if (hz > 0) Rpcs3Settings.INSTANCE.setVblankRate(Math.round(hz));
    }
    public static void setLandscapeRenderTop(boolean top) { Unsupported.note("setLandscapeRenderTop"); }
    public static void setPortraitRenderTop(boolean top) { Unsupported.note("setPortraitRenderTop"); }
    public static void setPortraitRenderTopInset(int px) { Unsupported.note("setPortraitRenderTopInset"); }

    // ===== Audio =====

    /**
     * [MAPPED] RPCS3 has no mute flag - Master Volume is 0..200, so mute is
     * volume 0. Remember the level so unmuting restores it instead of jumping
     * to 100.
     */
    private static volatile int lastVolume = 100;

    public static void setAudioMuted(boolean muted) {
        Rpcs3Settings.INSTANCE.setMasterVolume(muted ? 0 : lastVolume);
    }

    /** [MAPPED] -> Audio@@Master Volume */
    public static void setAudioVolume(int volume) {
        lastVolume = Math.max(0, Math.min(200, volume));
        Rpcs3Settings.INSTANCE.setMasterVolume(lastVolume);
    }

    /** [PS3-N/A] PCSX2-specific channel swap. */
    public static void setAudioSwapChannels(boolean swap) { Unsupported.note("setAudioSwapChannels"); }

    /** [TODO] Suppressing output while paused is handled by the core. */
    public static void setOutputPauseSuppressed(boolean s) { Unsupported.note("setOutputPauseSuppressed"); }

    // ===== Input =====

    /** [MAPPED] -> _rpcsx_overlayPadData (the virtual pad handler). */
    public static void setPadButton(int index, int range, boolean pressed) {
        Rpcs3Bridge.setPadButton(0, index, range, pressed);
    }

    /** [MAPPED] */
    public static void setPadButtonForPort(int port, int index, int range, boolean pressed) {
        Rpcs3Bridge.setPadButton(port, index, range, pressed);
    }

    /** [TODO] */
    public static void resetKeyStatus() { Unsupported.note("resetKeyStatus"); }
    public static void setPadVibration(boolean on) { Rpcs3Bridge.setPadVibration(on); }
    /** Unused here: the core is polled instead, since cellPad gives no rumble notification. */
    public static void onPadRumble(int pad, int large, int small) { Unsupported.note("onPadRumble"); }
    public static void testRumble(int port) { Rpcs3Bridge.testRumble(port); }
    public static String rumbleStatusForPort(int port) { return Rpcs3Bridge.rumbleStatusForPort(port); }
    public static void startRumblePump() { Rpcs3Bridge.startRumblePump(); }
    public static void stopRumblePump() { Rpcs3Bridge.stopRumblePump(); }
    public static void setPadMotion(int port, float ax, float ay, float az, float gyro) {
        Rpcs3Bridge.setPadMotion(port, ax, ay, az, gyro);
    }
    public static void enablePad2() { Unsupported.note("enablePad2"); }


    /**
     * [PS3-N/A] GUNCON is a PS2 lightgun peripheral.
     *
     * The constants are kept only so ARMSX2's touch overlay still compiles -
     * Lightgun.kt, LightgunLayer.kt and TouchControlsOverlay.kt all reference
     * them. The lightgun controls themselves should be removed from the touch
     * overlay; until then every call logs through Unsupported so they show up
     * as dead rather than being discovered by a user.
     */
    public static final int GUNCON_C = 1;
    public static final int GUNCON_B = 2;
    public static final int GUNCON_A = 3;
    public static final int GUNCON_DPAD_UP = 4;
    public static final int GUNCON_DPAD_RIGHT = 5;
    public static final int GUNCON_DPAD_DOWN = 6;
    public static final int GUNCON_DPAD_LEFT = 7;
    public static final int GUNCON_TRIGGER = 13;
    public static final int GUNCON_SELECT = 14;
    public static final int GUNCON_START = 15;
    public static final int GUNCON_SHOOT_OFFSCREEN = 16;
    public static final int GUNCON_RECALIBRATE = 17;

    public static void usbLightgunAim(float x, float y) { Unsupported.note("usbLightgunAim"); }
    public static void usbLightgunButton(int p, int b, boolean pressed) { Unsupported.note("usbLightgunButton"); }
    public static void usbSetDeviceType(int port, String type) { Unsupported.note("usbSetDeviceType"); }
    public static void usbSetDeviceSubtype(int port, int subtype) { Unsupported.note("usbSetDeviceSubtype"); }
    /** [MAPPED] Attach or detach the emulated PS3 keyboard (cellKb).
     *
     *  There is no PS3 equivalent of PCSX2's USB HID keyboard device, so the ARMSX2
     *  name is kept but the thing it drives is RPCS3's keyboard handler: Basic
     *  installs the Android handler, Null reports nothing attached. Takes effect on
     *  the next boot, since the handler is created during Emulator::Load.
     *
     *  port is ignored: RPCS3 has one keyboard handler, not one per USB port. */
    public static void usbSetKeyboardEnabled(int port, boolean e) {
        Rpcs3Bridge.setKeyboardEnabled(e);
    }

    /** [MAPPED] -> _rpcsx_keyboardKey. port is ignored (single handler). */
    public static boolean usbKeyboardKey(int p, int k, boolean pressed) {
        return Rpcs3Bridge.keyboardKey(k, 0, pressed);
    }

    /** [MAPPED] As above, plus the character the key produced (KeyEvent.getUnicodeChar()).
     *  cellKb derives its own character from the raw code and the live modifier state, so
     *  this only matters to the emulator's own overlays. */
    public static boolean usbKeyboardKey(int p, int k, int unicode, boolean pressed) {
        return Rpcs3Bridge.keyboardKey(k, unicode, pressed);
    }
    public static String usbDeviceTypes() { return ""; }

    // ===== PS2-only subsystems: these screens must be REMOVED, not stubbed =====

    /** [PS3-N/A] PS2 memory cards. PS3 uses HDD save data. */
    public static boolean createMemoryCard(String n, int t, int f) { Unsupported.note("createMemoryCard"); return false; }
    public static boolean isMemoryCard(String name) { Unsupported.note("isMemoryCard"); return false; }
    public static boolean isMemcardBusy() { return false; }

    /** [PS3-N/A] PNACH cheat/patch format is PCSX2-specific. */
    public static int reloadPatches() { Unsupported.note("reloadPatches"); return 0; }
    public static void setEnabledPatches(boolean c, String[] all, String[] on) { Unsupported.note("setEnabledPatches"); }
    public static void purgeGlobalPatchEnableLists() { Unsupported.note("purgeGlobalPatchEnableLists"); }

    /** [PS3-N/A] RetroAchievements has NO PS3 support. Remove the screens. */
    public static String getAchievementsJSON() { Unsupported.note("getAchievementsJSON"); return ""; }
    public static String getAchievementsHashForPath(String p) { Unsupported.note("getAchievementsHash"); return ""; }
    public static String loginAchievements(String u, String p) { Unsupported.note("loginAchievements"); return ""; }
    public static void logoutAchievements() { Unsupported.note("logoutAchievements"); }
    public static boolean isHardcoreMode() { return false; }
    public static boolean isHardcorePersisted() { return false; }
    public static void setHardcoreMode(boolean e) { Unsupported.note("setHardcoreMode"); }
    public static void setAchievementsOption(String k, boolean e) { Unsupported.note("setAchievementsOption"); }
    public static void setAchievementsOptionInt(String k, int v) { Unsupported.note("setAchievementsOptionInt"); }
    public static void setAchievementsHostOverride(String h) { Unsupported.note("setAchievementsHostOverride"); }
    public static void clearAchievementsHostOverride() { Unsupported.note("clearAchievementsHostOverride"); }
    public static void setAchievementsUnlockSound(String p) { Unsupported.note("setAchievementsUnlockSound"); }
    public static String getRichPresence() { return ""; }

    /** [PS3-N/A] EE/VU speedhacks are PS2 CPU concepts. */
    public static void speedhackEecyclerate(int v) { Unsupported.note("speedhackEecyclerate"); }
    public static void speedhackEecycleskip(int v) { Unsupported.note("speedhackEecycleskip"); }
    public static void speedhackLimitermode(int v) { Unsupported.note("speedhackLimitermode"); }
    public static void setInstantVU1(boolean e) { Unsupported.note("setInstantVU1"); }

    /** [PS3-N/A] PCSX2 per-game INI. RPCS3 has its own per-title config. */
    public static boolean gameIniBeginWrite() { Unsupported.note("gameIniBeginWrite"); return false; }
    public static boolean gameIniBeginWriteForSerial(String s) { Unsupported.note("gameIniBeginWriteForSerial"); return false; }
    public static void gameIniPut(String s, String k, String v) { Unsupported.note("gameIniPut"); }
    public static boolean gameIniCommitWrite() { Unsupported.note("gameIniCommitWrite"); return false; }

    // ===== Misc =====

    /** [TODO] RPCS3 supports disc swapping. */
    public static boolean changeDisc(String path) { Unsupported.note("changeDisc"); return false; }

    /** [TODO] */
    public static void saveScreenshot(String pngPath) { Unsupported.note("saveScreenshot"); }
    public static void captureGsDump(int frames) { Unsupported.note("captureGsDump"); }
    public static void flushShaderCache() { Unsupported.note("flushShaderCache"); }
    public static void dumpPgoProfile() { Unsupported.note("dumpPgoProfile"); }
    public static boolean reloadTextureReplacements() { Unsupported.note("reloadTextureReplacements"); return false; }
    public static boolean toggleTextureDumping() { Unsupported.note("toggleTextureDumping"); return false; }

    /** [MAPPED] shader chains run through RPCS3's output scaling pass. */
    public static String shaderPresetParams(String presetPath) { Unsupported.note("shaderPresetParams"); return ""; }
    public static void setShaderChainParams(String p, String[] n, float[] v) { Unsupported.note("setShaderChainParams"); }

    /** [MAPPED] ADPF and affinity are handled Android-side, not in the core. */
    public static void setAdpfEnabled(boolean enabled) { Rpcs3Bridge.setAdpfEnabled(enabled); }
    public static void setAffinityMode(int mode) {
        // ARMSX2: 0 = OS default, 1/2 = its own big-core schemes.
        // RPCS3's analogue is Thread Scheduler Mode; the strings are verbose
        // and are NOT the enum identifiers.
        String s = mode == 0 ? "Operating System"
                 : mode == 1 ? "RPCS3 Scheduler"
                 : "RPCS3 Alternative Scheduler";
        Rpcs3Settings.INSTANCE.setThreadScheduler(s);
    }

    /** [MAPPED] */
    public static void emulog(String msg) { Rpcs3Bridge.log(msg); }

    // Helpers ARMSX2 implements in Java, not native - carried as-is.
    public static Context getContext() { return Rpcs3Bridge.getContext(); }
    public static boolean createDirectoryPath(String path) { return Rpcs3Bridge.createDirectoryPath(path); }
    public static boolean createFilePath(String path) { return Rpcs3Bridge.createFilePath(path); }
    public static int openContentUri(String uriString) { return Rpcs3Bridge.openContentUri(uriString); }
    public static void playSound(String path) { Rpcs3Bridge.playSound(path); }
    public static void touchHaptic() { Rpcs3Bridge.touchHaptic(); }
}

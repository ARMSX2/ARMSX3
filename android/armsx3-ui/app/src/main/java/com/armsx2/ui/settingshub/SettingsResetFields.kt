package com.armsx2.ui.settingshub

import com.armsx2.config.Settings
import com.armsx2.navigation.SettingsCategory
import org.json.JSONObject

/**
 * Which [Settings] fields each settings tab owns, so Reset can scope to the tab you're
 * looking at instead of wiping everything.
 *
 * The Reset button used to call `Settings()` (or clear the whole per-game override blob),
 * which reset EVERY tab: pressing Reset on the Renderer page also wiped Audio, Network and
 * Fixes. Controller settings appeared to survive only because they live in a separate store
 * (ControllerMappings), not because Reset was scoped — the Controls tab owns no Settings
 * fields at all, which is why it has no entry below.
 *
 * Names are the JSON keys from [Settings.toJson], which IS the persistence format
 * (ConfigStore.saveGlobal stores `toJson().toString()` and loads it back through
 * `Settings.fromJson`). That round-trip is therefore known-complete, which is what lets
 * [resetCategory] swap individual values against a default Settings() safely.
 *
 * KEEP IN SYNC with the matching *Tab.kt when you add a setting — a field missing here just
 * won't be reset by its tab's button (it is never destructive, only incomplete).
 */
internal val SETTINGS_CATEGORY_FIELDS: Map<SettingsCategory, List<String>> = mapOf(
    // PerformanceTab.kt
    SettingsCategory.Performance to listOf(
        "accurateBlendingUnit", "affinityMode", "eeClampMode", "eeCycleRate", "eeCycleSkip",
        "eeFpuRoundMode", "fastCDVD", "fpsLimit", "frameSkip", "framerateNtsc", "frameratePal",
        "hwMipmap", "hwRov", "hwScaler", "intcStat", "mtvu", "nominalSpeedPercent",
        "ps3AccurateCacheLine", "ps3AccurateSpuDma", "ps3AccurateSpuRsv", "ps3ClocksScale",
        "ps3GpuTurbo", "ps3LlvmPrecompile", "ps3LlvmThreads", "ps3MaxSpursThreads",
        "ps3PpuDecoder", "ps3PreferredSpuThreads", "ps3SavestateCompatibleMode",
        "ps3SilenceAllLogs", "ps3SpuBlockSize", "ps3SpuCache", "ps3SpuDecoder",
        "ps3SpuLoopDetection", "ps3SpuXFloat", "screenResOverride", "skipDuplicateFrames",
        "texturePreloading", "upscaleFloat", "vu0RoundMode", "vu1Instant", "vu1RoundMode",
        "vuClampMode", "vuDeferredWrites", "vuFlagHack", "vuNeonFusions", "vuSkipStallSim",
        "waitLoop",
    ),
    // RendererTab.kt
    SettingsCategory.Graphics to listOf(
        "adrenoFbFetch", "aspectRatio", "autoProgressiveScan", "casMode", "casSharpness",
        "customAspectRatio", "customDriverId", "deinterlaceMode", "displayBilinear",
        "displayFitMode", "dumpReplaceableTextures", "fmvAspectRatio", "forceMaliFbFetch",
        "fxaa", "gpuProfile", "gsBackThreadMode", "hardwareDownloadMode", "hwAa1",
        "hwAccurateAlphaTest", "landscapeRenderTop", "loadTextureReplacements",
        "loadTextureReplacementsAsync", "maxAnisotropy", "orientation",
        "osdShowTextureReplacements", "portraitRenderTop", "precacheTextureReplacements",
        "ps3AnisoFilter", "ps3AsyncTexStream", "ps3DisableZcull", "ps3DisplayAspect",
        "ps3MsaaMode", "ps3MultithreadedRsx", "ps3ReadColorBuffers", "ps3ReadDepthBuffer",
        "ps3RelaxedZcull", "ps3Resolution", "ps3ShaderMode", "ps3StrictRendering",
        "ps3VramLimitMb", "ps3WriteColorBuffers", "ps3WriteDepthBuffer", "renderer",
        "shadeBoost", "shadeBoostBrightness", "shadeBoostContrast", "shadeBoostGamma",
        "shadeBoostSaturation", "shaderChainEnabled", "shaderChainParams", "shaderChainPreset",
        "textureFiltering", "triFilter", "tvShader", "upscaleFloat", "useAngleOpenGL",
        "vsyncEnable",
    ),
    // AudioTab.kt
    SettingsCategory.Audio to listOf(
        "audioBufferMs", "audioFastForwardVolume", "audioMuted", "audioOpenSLES",
        "audioOutputLatencyMs", "audioSwapChannels", "audioTimeStretch", "audioVolume",
        "ps3AudioBufferMs", "ps3AudioChannels", "ps3AudioCubebBackend", "ps3AudioFormat",
        "ps3AudioRenderer", "ps3AudioTimeStretch", "spu2LightweightMix", "spu2NeonReverb",
    ),
    // NetworkTab.kt
    SettingsCategory.Network to listOf(
        "dev9AutoGateway", "dev9AutoMask", "dev9Dns1", "dev9Dns2", "dev9EthApi",
        "dev9EthDevice", "dev9EthEnable", "dev9EthHosts", "dev9EthLogDhcp", "dev9EthLogDns",
        "dev9Gateway", "dev9HddEnable", "dev9HddFile", "dev9InterceptDhcp", "dev9Mask",
        "dev9ModeDns1", "dev9ModeDns2", "dev9Ps2Ip", "ip", "ps3NetEnabled", "ps3PsnStatus",
        "ps3UpnpEnabled", "url", "usbKeyboard",
    ),
    // OverlayTab.kt
    SettingsCategory.OnScreen to listOf(
        "osdColor", "osdScale", "osdShowCpu", "osdShowFps", "osdShowFrameTimes", "osdShowGpu",
        "osdShowGpuStats", "osdShowGsStats", "osdShowHardwareInfo", "osdShowInputs",
        "osdShowMessages", "osdShowResolution", "osdShowSettings", "osdShowSpeed",
        "osdShowVersion", "osdShowVps", "ps3OverlayBodyBg", "ps3OverlayBodyColor",
        "ps3OverlayDetail", "ps3OverlayEnabled", "ps3OverlayFontSize",
        "ps3OverlayFramerateGraph", "ps3OverlayFrametimeGraph", "ps3OverlayOpacity",
        "ps3OverlayPosition", "ps3OverlayTitleBg", "ps3OverlayTitleColor",
    ),
    // FixesTab.kt — also owns the GameDB fixes and the recompiler toggles, which moved here
    // from Performance and from the retired Recompiler tab.
    SettingsCategory.Advanced to listOf(
        "alignSprite", "antiBlur", "autoFlush", "autoFlushSw", "bilinearUpscale",
        "cpuClutRender", "cpuFramebufferConversion", "cpuSpriteRenderBw",
        "cpuSpriteRenderLevel", "cropBottom", "cropLeft", "cropRight", "cropTop",
        "disableDepthEmulation", "disableFramebufferFetch", "disableInterlaceOffset",
        "disablePartialInvalidation", "disableRenderFixes", "disableSafeFeatures",
        "disableShaderCache", "disableVertexShaderExpand", "displayZoom", "dithering",
        "drawBuffering", "enableFastBoot", "enableFastmem", "enableGameFixes",
        "estimateTextureRegion", "forceEvenSpritePosition", "gamefixBlitInternalFps",
        "gamefixDmaBusy", "gamefixEETiming", "gamefixFpuMul", "gamefixFullVu0Sync",
        "gamefixGifFifo", "gamefixGoemonTlb", "gamefixIbit", "gamefixInstantDma",
        "gamefixOphFlag", "gamefixSkipMpeg", "gamefixSoftwareRendererFmv", "gamefixVif1Stall",
        "gamefixVuAddSub", "gamefixVuOverflow", "gamefixVuSync", "gamefixXgkick",
        "gpuPaletteConversion", "gpuTargetClut", "halfPixelOffset", "hwAccurateAlphaTest",
        "integerScaling", "limit24BitDepth", "manualUserHacks", "mergeSprite", "mipmapSw",
        "nativeScaling", "overrideTextureBarriers", "preloadFrameData", "ps3AccurateCacheLine",
        "ps3AccurateDfma", "ps3AccurateRsxRsv", "ps3AccurateSpuRsv", "ps3DebugConsoleMode",
        "ps3HleLwmutex", "ps3PpuNanHandling", "ps3PpuRsvPriority", "ps3PreciseSpuVerification",
        "ps3SetDazFtz", "ps3SleepTimers", "ps3SpuVerification", "ps3SpuXFloat",
        "readTargetsWhenClosing", "recEE", "recIOP", "recVU0", "recVU1", "roundSprite",
        "screenOffsets", "showOverscan", "skipDrawEnd", "skipDrawStart", "spinCpuReadbacks",
        "spinGpuReadbacks", "swThreads", "swThreadsHeight", "syncToHostRefresh",
        "textureInsideRt", "textureOffsetX", "textureOffsetY", "unscaledPaletteDraw",
        "useBlitSwapChain", "vsyncQueueSize",
    ),
    // Controls / Hotkeys / Skins / General / Info / Patches / About own no Settings fields —
    // Controls keeps its binds and tunables in ControllerMappings and has its own reset row.
)

/** Default values for just this category's fields, leaving every other tab untouched. */
internal fun Settings.resetCategory(category: SettingsCategory): Settings {
    val fields = SETTINGS_CATEGORY_FIELDS[category] ?: return this
    val current = toJson()
    val defaults = Settings().toJson()
    for (key in fields) {
        if (defaults.has(key)) current.put(key, defaults.get(key)) else current.remove(key)
    }
    return Settings.fromJson(current)
}

/** The per-game override keys belonging to [category], for a scoped per-game reset. */
internal fun categoryOverrideKeys(category: SettingsCategory): List<String> =
    SETTINGS_CATEGORY_FIELDS[category].orEmpty()

/** True when this tab has anything the Reset button could restore. */
internal fun categoryHasResettableSettings(category: SettingsCategory): Boolean =
    !SETTINGS_CATEGORY_FIELDS[category].isNullOrEmpty()

/** Strip [keys] from a per-game override blob; null when nothing is left to store. */
internal fun pruneOverrides(overrides: JSONObject, keys: List<String>): JSONObject? {
    for (key in keys) overrides.remove(key)
    return if (overrides.length() == 0) null else overrides
}

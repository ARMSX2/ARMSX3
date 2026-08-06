package net.rpcsx

import net.rpcsx.utils.GeneralSettings

/**
 * GPU vendor detection, and the per-vendor knobs that hang off it.
 *
 * Detection is by string match on the device name RPCS3 reports through
 * systemInfo() -- deliberately NOT by driverID or vendorID. Both of those are
 * only available once a Vulkan instance exists, and this has to answer before
 * the first boot so defaults can be seeded.
 *
 * IMPORTANT, learned the hard way on ARMSX2: a vendor is not a driver. Two
 * devices with the same Adreno vendor can need opposite settings depending on
 * driver branch, and gating a workaround on vendor when the real discriminator
 * was driverID produced bugs that only reproduced on some phones. So this type
 * exists to pick DEFAULTS and to label the UI. Anything that must key off an
 * actual driver quirk belongs in the core, next to the Vulkan caps it depends
 * on -- not here.
 */
enum class GpuVendor(val displayName: String) {
    ADRENO("Qualcomm Adreno"),
    MALI("ARM Mali"),
    XCLIPSE("Samsung Xclipse"),
    POWERVR("Imagination PowerVR"),
    NVIDIA("NVIDIA"),
    UNKNOWN("Unknown GPU");

    companion object {
        private const val CachedKey = "gpu.vendor.cached"

        /**
         * Classify a GPU/renderer name.
         *
         * Order matters: Xclipse reports as an AMD-derived RDNA part on Samsung
         * silicon and must be checked before any generic match, or it lands in
         * the wrong bucket.
         */
        fun fromDeviceName(name: String?): GpuVendor {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                n.contains("xclipse") -> XCLIPSE
                n.contains("adreno") -> ADRENO
                n.contains("mali") -> MALI
                n.contains("powervr") || n.contains("img") -> POWERVR
                n.contains("nvidia") || n.contains("tegra") -> NVIDIA
                else -> UNKNOWN
            }
        }

        /**
         * Vendor for this device.
         *
         * systemInfo() enumerates physical devices, which needs a Vulkan
         * instance, so the answer is cached once obtained -- callers on the
         * startup path get the cached value rather than forcing enumeration.
         */
        fun detect(): GpuVendor {
            if (GeneralSettings.isInitialized()) {
                GeneralSettings.raw.getString(CachedKey, null)?.let { cached ->
                    runCatching { return valueOf(cached) }
                }
            }

            val info = runCatching { RPCSX.instance.systemInfo() }.getOrNull()
            // systemInfo formats as "GPU: <name>\n\nDriver: v<x>"
            val gpuLine = info?.lineSequence()?.firstOrNull { it.startsWith("GPU:") }
            val vendor = fromDeviceName(gpuLine?.removePrefix("GPU:")?.trim())

            if (vendor != UNKNOWN && GeneralSettings.isInitialized()) {
                GeneralSettings.raw.edit().putString(CachedKey, vendor.name).apply()
            }
            return vendor
        }

        /** Forget the cached vendor (after a custom driver swap, for example). */
        fun invalidateCache() {
            if (!GeneralSettings.isInitialized()) return
            GeneralSettings.raw.edit().remove(CachedKey).apply()
        }
    }

    /**
     * Whether custom driver loading is worth offering.
     *
     * adrenotools only replaces the Adreno userspace driver -- there is no
     * equivalent for Mali, Xclipse or PowerVR, so offering the option there is
     * a dead end that looks like a broken feature.
     *
     * Note this is separate from whether the CORE supports driver loading at
     * all, which it currently does not (see _rpcsx_setCustomDriver).
     */
    val supportsCustomDrivers: Boolean get() = this == ADRENO
}

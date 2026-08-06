package net.rpcsx

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import net.rpcsx.utils.GeneralSettings

/**
 * First-run state.
 *
 * rpcsx-ui-android has no setup flow at all: its nav host starts straight on
 * "games", and a missing firmware only surfaces as a nag dialog once you tap a
 * title. That leaves a new user staring at an empty grid with no indication
 * that a PS3 firmware (PS3UPDAT.PUP) is required at all.
 *
 * NOTE ON PS2 vs PS3 -- this is NOT the ARMSX2 BIOS flow renamed. A PS2 BIOS is
 * a ROM file you point the emulator at, so ARMSX2 can scan a folder, parse
 * ROMVER and offer a list. PS3 firmware is a signed PUP that has to be
 * *installed*: decrypted and unpacked into dev_flash, after which the version
 * is read back with utils::get_firmware_version(). So the setup step here is
 * "install and wait", not "pick a file", and there is no region/version list to
 * present up front.
 */
object SetupRepository {
    private const val CompletedKey = "setup.completed"

    /** True once the user has been through (or explicitly skipped) first-run. */
    val completed: MutableState<Boolean> = mutableStateOf(false)

    fun load() {
        if (!GeneralSettings.isInitialized()) return
        completed.value = GeneralSettings.raw.getBoolean(CompletedKey, false)
    }

    fun markCompleted() {
        completed.value = true
        GeneralSettings.raw.edit().putBoolean(CompletedKey, true).apply()
    }

    /** Re-run setup from the settings screen. */
    fun reset() {
        completed.value = false
        GeneralSettings.raw.edit().putBoolean(CompletedKey, false).apply()
    }

    /**
     * Firmware is the one genuinely blocking prerequisite -- without dev_flash
     * populated, BootGame returns firmware_missing for every title.
     */
    fun firmwareInstalled(): Boolean = FirmwareRepository.version.value != null
}

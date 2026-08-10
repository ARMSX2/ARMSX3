package com.armsx2.config

import net.rpcsx.RPCSX

/**
 * Per-title core settings that a game needs to run, keyed by serial.
 *
 * These are workarounds for emulation bugs, not preferences: a title that will not get past
 * its own boot without one is not something a user should have to find out about on Discord
 * and then hand-edit config.yml for. RPCS3 desktop carries the same idea in its per-game
 * configs.
 *
 * Applied at boot BEFORE [CoreSettingOverrides], so anything the user set on the All Core
 * Settings screen still wins. They are a floor, not a ceiling.
 *
 * Values are JSON-encoded exactly as settingsSet expects: bare for numbers and bools,
 * quoted for enums and strings.
 */
object GameDefaults {

    private val BY_SERIAL: Map<String, Map<String, String>> = mapOf(
        // Uncharted 3. Dies on its own "PS3 application has crashed" path; skipping the
        // trap gets it past that. Confirmed on device by a tester, who found it by setting
        // the node by hand.
        "BCUS98233" to mapOf("Core@@Stub PPU Traps" to "1"),
        "BCES01175" to mapOf("Core@@Stub PPU Traps" to "1"),
    )

    fun forSerial(serial: String?): Map<String, String> =
        BY_SERIAL[serial?.uppercase()?.trim().orEmpty()].orEmpty()

    /** True when this title has known-required overrides, for surfacing in the UI. */
    fun has(serial: String?): Boolean = forSerial(serial).isNotEmpty()

    /**
     * Push this title's required settings. Batched, since each settingsSet otherwise
     * serialises the whole config and writes it out.
     */
    fun apply(serial: String?) {
        val settings = forSerial(serial)
        if (settings.isEmpty()) return

        runCatching { RPCSX.instance.settingsBeginBatch() }
        try {
            settings.forEach { (path, value) ->
                runCatching { RPCSX.instance.settingsSet(path, value) }
                android.util.Log.i("ARMSX3", "game default for $serial: $path = $value")
            }
        } finally {
            runCatching { RPCSX.instance.settingsEndBatch() }
        }
    }
}

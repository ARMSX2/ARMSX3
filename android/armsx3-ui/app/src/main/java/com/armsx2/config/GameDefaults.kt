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

    /**
     * Stock value for every path any title here can set.
     *
     * Required, not decorative. The Android port keeps ONE global config.yml: settingsSet
     * persists through Emulator::SaveSettings(g_cfg.to_string(), "") and an empty title id is
     * the global path. So a value written for one game stays written for the next one.
     *
     * [apply] used to return early for a title with no entry, writing nothing. Booting
     * Uncharted 3 therefore set Core@@Stub PPU Traps to 1 and left it there, and every game
     * launched afterwards ran with a PPU that silently skips an instruction on any trap
     * instead of stopping. Nothing on screen said so, and nothing else writes that node: it
     * is not in the curated push, and CoreSettingOverrides only replays paths a user
     * explicitly recorded.
     *
     * Anything added to [BY_SERIAL] must gain its upstream default here.
     */
    private val STOCK: Map<String, String> = mapOf(
        // system_config.h: cfg::_int<-64, 64> stub_ppu_traps{ this, "Stub PPU Traps", 0, true }
        "Core@@Stub PPU Traps" to "0",
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
        // Every managed path is written on every boot, this title's value where it has one
        // and the stock value where it does not. Returning early for a title with no entry
        // is what let Uncharted 3's Stub PPU Traps follow the user into every other game;
        // see [STOCK]. A workaround for one title must not become a setting for all of them.
        val settings = STOCK + forSerial(serial)

        runCatching { RPCSX.instance.settingsBeginBatch() }
        try {
            settings.forEach { (path, value) ->
                runCatching { RPCSX.instance.settingsSet(path, value) }
                val why = if (forSerial(serial).containsKey(path)) "game default for $serial" else "stock"
                android.util.Log.i("ARMSX3", "$why: $path = $value")
            }
        } finally {
            runCatching { RPCSX.instance.settingsEndBatch() }
        }
    }
}

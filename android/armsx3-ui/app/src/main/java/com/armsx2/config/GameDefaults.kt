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

        // Yakuza: Dead Souls. Runs at 1fps with FIFO reordering on -- not slowly, but in
        // one-second steps: the RSX blocks on nv406e::semaphore_acquire until the wait times
        // out, draws, and does it again. 145 timeouts in one session, all on semaphore
        // 0x50300FE0, while the GPU itself was doing 3.06 ms of work per frame. The acquire
        // consistently outruns the release that should satisfy it -- awaited 0x68 against a
        // last_observed of 0x60 -- from the very first frame onward.
        //
        // Turning the flattener off clears it completely and the game boots and plays.
        //
        // Cause not established. The obvious candidate does not hold: flattening_helper only
        // drops registers marked always_ignore, and that set is four INVALIDATE methods with
        // no semaphore among them -- a semaphore release hits the default branch and flushes
        // the batch, which is the safe path. So this is an empirical per-title workaround
        // rather than a fix, and the real mechanism is still open.
        "BLUS30826" to mapOf("Video@@Disable FIFO Reordering" to "true"),
        "NPUB31509" to mapOf("Video@@Disable FIFO Reordering" to "true"),
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
        // system_config.h: cfg::_bool disable_FIFO_reordering{ this, "Disable FIFO Reordering", false }
        "Video@@Disable FIFO Reordering" to "false",
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

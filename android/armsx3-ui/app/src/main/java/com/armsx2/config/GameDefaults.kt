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
        //
        // The other two are for a SECOND failure, further in: the FIFO desyncs and reads a RET
        // with an empty call stack -- 19 of them in one session, last cmd 0x20000 every time --
        // recover_fifo() resets it each time, and eventually gives up and kills the RSX thread
        // outright ("Dead FIFO commands queue state"). The game then sits at 0 fps with audio
        // still playing perfectly, because everything except the renderer is still alive. The
        // stray semaphore acquires that time out alongside it are downstream of the same thing:
        // a desynced FIFO never runs the release that would satisfy them.
        //
        // These two are what the fatal message itself recommends, and they work. Which of the
        // two is doing the work is not established -- both were changed at once and the game
        // has not been A/B'd since -- so both are kept. Ordered & Atomic is the likelier of the
        // pair given the symptom, and both cost performance, which is why they are scoped to
        // this title rather than turned on globally.
        "BLUS30826" to mapOf(
            "Video@@Disable FIFO Reordering" to "true",
            "Core@@RSX FIFO Fetch Accuracy" to "\"Ordered & Atomic\"",
            "Video@@Driver Wake-Up Delay" to "20",
        ),
        "NPUB31509" to mapOf(
            "Video@@Disable FIFO Reordering" to "true",
            "Core@@RSX FIFO Fetch Accuracy" to "\"Ordered & Atomic\"",
            "Video@@Driver Wake-Up Delay" to "20",
        ),
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
        // system_config.h: fifo_setting rsx_fifo_accuracy{ this, "RSX FIFO Fetch Accuracy", rsx_fifo_mode::atomic }
        "Core@@RSX FIFO Fetch Accuracy" to "\"Atomic\"",
        // system_config.h: cfg::uint<0, 16667> driver_wakeup_delay{ this, "Driver Wake-Up Delay", 0, true }
        "Video@@Driver Wake-Up Delay" to "0",
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

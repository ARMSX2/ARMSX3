package com.armsx2.input

import android.view.InputDevice

/**
 * Auto-assigns physical controllers to PS3 pad ports for local multiplayer.
 *
 * The first distinct gamepad to send an event claims port 0, the next claims port 1, and
 * so on up to [MAX_PADS]. Nothing has to be armed first: the PS3 has seven real ports and
 * no multitap accessory, so a pad that joins simply takes the next free one.
 *
 * (This used to model the PS2's layout — two ports, each optionally split into four
 * multitap slots, with slots 2-7 armed natively at boot. None of that applies here.)
 *
 * The on-screen touch controls and all menu navigation always use port 0 — they never go
 * through here. Reset on VM start AND stop ([reset]) so each session re-pairs
 * deterministically.
 *
 * Called only from the in-game input dispatch where a real `event.deviceId` is live.
 */
object PadRouter {
    // Nintendo USB/Bluetooth vendor id. A Joy-Con pair enumerates as TWO InputDevices
    // (L + R) under this vendor, but they are one physical controller — so both halves
    // are routed to a single port instead of being split across players.
    private const val NINTENDO_VENDOR_ID = 0x057E
    // Unified slot -> claimed Android deviceId (-1 = unclaimed).
    /** CELL_PAD_MAX_PORT_NUM. The PS3 exposes seven pad ports natively. */
    const val MAX_PADS = 7

    private val slots = IntArray(MAX_PADS) { -1 }
    @Volatile private var pad2Enabled = false

    /** Fired exactly once, the first time a second controller joins, so the app can
     *  react before any P2 input is sent. Ports 2-6 need no equivalent: the core opens
     *  every port up front, so they are live the moment a pad claims one. */
    @Volatile var onPlayer2Joined: (() -> Unit)? = null

    fun reset() {
        for (i in slots.indices) slots[i] = -1
        pad2Enabled = false
    }

    /** Release the slot a now-departed device held, so a re-enumerated controller re-claims from the
     *  top. Called on device removal: AYANEO handhelds power-cycle the built-in pad on sleep/wake and
     *  it comes back with a NEW deviceId — the stale id otherwise keeps owning Player 1's slot, and
     *  the woken pad claims slot 1 (an un-armed PS2 port 2), so gameplay input goes nowhere (#394). */
    fun forgetDevice(deviceId: Int) {
        if (deviceId < 0) return
        for (i in slots.indices) if (slots[i] == deviceId) slots[i] = -1
    }

    /** Free every slot whose claimed device is no longer connected. Called on resume / focus regain
     *  as a backstop for [forgetDevice] when the remove event landed while we were paused.
     *  [activeDeviceIds] is `InputDevice.getDeviceIds()`. */
    fun pruneStale(activeDeviceIds: IntArray) {
        for (i in slots.indices) {
            val id = slots[i]
            if (id >= 0 && id !in activeDeviceIds) slots[i] = -1
        }
    }

    /** True once a second controller has joined this session (P2 main is live). */
    fun coopActive(): Boolean = slots[1] != -1

    /** Android InputDevice id assigned to a unified pad slot, or -1 if unclaimed.
     *  Lets per-slot PS2 rumble buzz the right pad. */
    fun deviceIdForPort(port: Int): Int =
        if (port in slots.indices) slots[port] else -1

    /**
     * Map a physical input device to a PS3 pad port (0..6), claiming the next free
     * slot. Synthetic / virtual events (deviceId < 0)
     * and non-gamepad nodes never claim a slot — they're treated as Player 1.
     */
    fun portForDevice(deviceId: Int): Int {
        if (deviceId < 0) return 0
        // Fast path: already-claimed nodes (no InputDevice lookup).
        for (i in slots.indices) if (slots[i] == deviceId) return i
        val dev = InputDevice.getDevice(deviceId)
        // Nintendo Joy-Cons (vendor 0x057E) enumerate as TWO InputDevices — the L and R
        // halves of ONE physical controller. Collapse BOTH onto Player 1 (port 0) so a
        // pair drives a single PS2 pad instead of splitting across P1/P2 (which made a
        // 1-player game respond to only one half, and a co-op game see two controllers).
        // They never claim a slot, so onPlayer2Joined stays silent for a lone pair. Gated
        // strictly on the Nintendo vendor id — every other controller keeps its routing.
        if (dev?.vendorId == NINTENDO_VENDOR_ID) return 0
        // Only a real GAMEPAD/JOYSTICK node may claim a slot. One physical controller
        // (notably a DualSense over Bluetooth) enumerates as SEVERAL InputDevices — a
        // gamepad node PLUS a touchpad/mouse node. Gating claims to gamepad sources stops
        // a secondary node from eating a slot and splitting one pad across two players.
        val src = dev?.sources ?: 0
        val isPad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isPad) return 0 // touchpad / mouse / keyboard node → treat as P1, don't claim
        // Always all seven. The PS2 needed a multitap accessory to go past two
        // controllers; the PS3 does not -- extra pads simply connect, so gating
        // on a multitap toggle would cap us at two for no reason.
        val maxSlots = MAX_PADS
        for (i in 0 until maxSlots) {
            if (slots[i] == -1) {
                slots[i] = deviceId
                if (i == 1 && !pad2Enabled) { pad2Enabled = true; onPlayer2Joined?.invoke() }
                return i
            }
        }
        return 0 // all slots taken -> fold into Player 1
    }
}

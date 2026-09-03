package com.armsx2.input

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

/**
 * Reads a PlayStation pad's input reports straight off USB and feeds them to the core.
 *
 * This exists because of a single hard constraint. On a handheld whose Android build rewrites
 * controller identity, the input API's motors for the pad are fiction: it advertises vibrators,
 * accepts every vibrate() call, and moves nothing. The only route an app has to the real motors
 * is UsbManager, and reaching them means claiming the HID interface -- which detaches the driver
 * feeding the pad's INPUT. A DualSense has exactly one HID interface (index 3; the rest are
 * audio), so it cannot be held for output while leaving input alone: claim it and every button,
 * stick and trigger goes dead.
 *
 * The way out is to stop half-using the device. Once claimed, read its input reports here too and
 * push them to the core, so the pad works entirely through USB. That also picks up what the
 * platform was dropping anyway -- genuine analog triggers, and both motors driven independently
 * rather than flattened into one amplitude.
 *
 * USB only. Bluetooth HID output reports need the privileged HID host profile, which no ordinary
 * app can obtain, so a wireless pad cannot be reached this way.
 */
object UsbPadTakeover {
    private const val TAG = "ARMSX3Rumble"

    // CELL_PAD digital1 (pad_types.h).
    private const val SELECT = 0x0001
    private const val L3 = 0x0002
    private const val R3 = 0x0004
    private const val START = 0x0008
    private const val UP = 0x0010
    private const val RIGHT = 0x0020
    private const val DOWN = 0x0040
    private const val LEFT = 0x0080
    private const val PS = 0x0100

    // CELL_PAD digital2.
    private const val L2 = 0x0001
    private const val R2 = 0x0002
    private const val L1 = 0x0004
    private const val R1 = 0x0008
    private const val TRIANGLE = 0x0010
    private const val CIRCLE = 0x0020
    private const val CROSS = 0x0040
    private const val SQUARE = 0x0080

    /** What the guest should see for a trigger held down. */
    private const val TRIGGER_DIGITAL_POINT = 32

    @Volatile private var reader: Thread? = null
    @Volatile private var running = false

    fun active(): Boolean = running

    /**
     * Start feeding the core from [connection].
     *
     * [port] is the PS3 pad port this controller owns, so a pinned player slot is honoured the
     * same way it is for a pad coming through Android.
     */
    fun start(
        connection: UsbDeviceConnection,
        endpointIn: UsbEndpoint,
        dualsense: Boolean,
        port: Int,
    ) {
        stop()
        running = true
        reader = Thread {
            val buf = ByteArray(64)
            // A report that failed to arrive is not an error worth logging every time: the pad
            // simply had nothing to say within the timeout, which happens constantly when the
            // player is not touching it.
            while (running) {
                val n = runCatching {
                    connection.bulkTransfer(endpointIn, buf, buf.size, 200)
                }.getOrDefault(-1)

                if (n <= 0) continue
                runCatching {
                    if (dualsense) feedDualSense(buf, n, port) else feedDualShock4(buf, n, port)
                }
            }
        }.apply { isDaemon = true; name = "usb-pad"; start() }
        Log.i(TAG, "usb pad takeover started on port ${port + 1} (dualsense=$dualsense)")
    }

    fun stop() {
        running = false
        reader?.interrupt()
        reader = null
    }

    /**
     * DualSense USB input report.
     *
     * Report id 0x01, then the layout hid-playstation calls dualsense_input_report: sticks,
     * both triggers, a sequence counter, then four button bytes. The D-pad arrives as a HAT
     * value (0..7 clockwise from north, 8 = centred), not as four bits.
     */
    private fun feedDualSense(buf: ByteArray, len: Int, port: Int) {
        if (len < 11 || buf[0].toInt() and 0xFF != 0x01) return

        val lx = buf[1].toInt() and 0xFF
        val ly = buf[2].toInt() and 0xFF
        val rx = buf[3].toInt() and 0xFF
        val ry = buf[4].toInt() and 0xFF
        val l2 = buf[5].toInt() and 0xFF
        val r2 = buf[6].toInt() and 0xFF
        val b0 = buf[8].toInt() and 0xFF
        val b1 = buf[9].toInt() and 0xFF
        val b2 = buf[10].toInt() and 0xFF

        var d1 = hatToDigital1(b0 and 0x0F)
        var d2 = 0

        if (b0 and 0x10 != 0) d2 = d2 or SQUARE
        if (b0 and 0x20 != 0) d2 = d2 or CROSS
        if (b0 and 0x40 != 0) d2 = d2 or CIRCLE
        if (b0 and 0x80 != 0) d2 = d2 or TRIANGLE

        if (b1 and 0x01 != 0) d2 = d2 or L1
        if (b1 and 0x02 != 0) d2 = d2 or R1
        if (b1 and 0x10 != 0) d1 = d1 or SELECT   // Create
        if (b1 and 0x20 != 0) d1 = d1 or START    // Options
        if (b1 and 0x40 != 0) d1 = d1 or L3
        if (b1 and 0x80 != 0) d1 = d1 or R3
        if (b2 and 0x01 != 0) d1 = d1 or PS

        // The triggers are analog on both machines, so the digital bit follows the travel rather
        // than the pad's own click bit -- that is what makes a half-pressed R2 read as half.
        if (l2 >= TRIGGER_DIGITAL_POINT) d2 = d2 or L2
        if (r2 >= TRIGGER_DIGITAL_POINT) d2 = d2 or R2

        com.armsx3.Rpcs3Bridge.usbPadState(port, d1, d2, lx, ly, rx, ry, l2, r2)
    }

    /** DualShock 4 USB input report: same idea, different offsets, triggers at the end. */
    private fun feedDualShock4(buf: ByteArray, len: Int, port: Int) {
        if (len < 10 || buf[0].toInt() and 0xFF != 0x01) return

        val lx = buf[1].toInt() and 0xFF
        val ly = buf[2].toInt() and 0xFF
        val rx = buf[3].toInt() and 0xFF
        val ry = buf[4].toInt() and 0xFF
        val b0 = buf[5].toInt() and 0xFF
        val b1 = buf[6].toInt() and 0xFF
        val b2 = buf[7].toInt() and 0xFF
        val l2 = buf[8].toInt() and 0xFF
        val r2 = buf[9].toInt() and 0xFF

        var d1 = hatToDigital1(b0 and 0x0F)
        var d2 = 0

        if (b0 and 0x10 != 0) d2 = d2 or SQUARE
        if (b0 and 0x20 != 0) d2 = d2 or CROSS
        if (b0 and 0x40 != 0) d2 = d2 or CIRCLE
        if (b0 and 0x80 != 0) d2 = d2 or TRIANGLE

        if (b1 and 0x01 != 0) d2 = d2 or L1
        if (b1 and 0x02 != 0) d2 = d2 or R1
        if (b1 and 0x10 != 0) d1 = d1 or SELECT   // Share
        if (b1 and 0x20 != 0) d1 = d1 or START    // Options
        if (b1 and 0x40 != 0) d1 = d1 or L3
        if (b1 and 0x80 != 0) d1 = d1 or R3
        if (b2 and 0x01 != 0) d1 = d1 or PS

        if (l2 >= TRIGGER_DIGITAL_POINT) d2 = d2 or L2
        if (r2 >= TRIGGER_DIGITAL_POINT) d2 = d2 or R2

        com.armsx3.Rpcs3Bridge.usbPadState(port, d1, d2, lx, ly, rx, ry, l2, r2)
    }

    /** HAT value to the four CELL_PAD direction bits. 8 (and anything unexpected) is centred. */
    private fun hatToDigital1(hat: Int): Int = when (hat) {
        0 -> UP
        1 -> UP or RIGHT
        2 -> RIGHT
        3 -> DOWN or RIGHT
        4 -> DOWN
        5 -> DOWN or LEFT
        6 -> LEFT
        7 -> UP or LEFT
        else -> 0
    }
}

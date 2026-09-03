package com.armsx2.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Rumble for a PlayStation controller, written straight to the pad over USB.
 *
 * Android's input API cannot drive these motors on every handheld. A device whose firmware
 * bridges an attached controller through its own HID node re-presents it under the handheld's
 * own vendor id, advertises a full vibrator inventory for it -- ids, hasVibrator, the lot --
 * accepts every vibrate() call without error, and moves nothing, because force feedback is
 * never forwarded to the pad. Nothing in the input API distinguishes that from a working motor.
 *
 * The real device is still on the USB bus underneath, so this addresses it directly with a HID
 * SET_REPORT and bypasses the bridge entirely.
 *
 * Deliberately a CONTROL transfer on endpoint 0, not an interrupt endpoint: claiming the
 * interface would detach whatever driver owns it and the pad's BUTTONS would stop working, which
 * is a far worse bug than no rumble. Control transfers need no claim, so input is untouched.
 *
 * USB only. Bluetooth HID output reports are not reachable from an app.
 */
object UsbRumble {
    private const val TAG = "ARMSX3Rumble"

    private const val VENDOR_SONY = 0x054C
    // DualSense, DualSense Edge.
    private val DUALSENSE = setOf(0x0CE6, 0x0DF2)
    // DualShock 4 v1, v2, and the USB adapter.
    private val DUALSHOCK4 = setOf(0x05C4, 0x09CC, 0x0BA0)

    private const val ACTION_PERMISSION = "com.armsx3.action.USB_RUMBLE_PERMISSION"

    /** An open pad we can write reports to. */
    class Pad(
        val label: String,
        val productName: String,
        private val connection: UsbDeviceConnection,
        private val interfaceId: Int,
        private val dualsense: Boolean,
    ) {
        private var lastLarge = -1
        private var lastSmall = -1

        /** Motor levels, 0..255. Returns true when the pad accepted the report. */
        @Synchronized
        fun rumble(large: Int, small: Int): Boolean {
            if (large == lastLarge && small == lastSmall) return true
            lastLarge = large
            lastSmall = small

            val report = if (dualsense) dualsenseReport(large, small) else dualshock4Report(large, small)
            // 0x21 = host-to-device | class | interface; 0x09 = SET_REPORT;
            // wValue high byte 0x02 = Output report, low byte = report id.
            val sent = runCatching {
                connection.controlTransfer(
                    0x21, 0x09, 0x0200 or (report[0].toInt() and 0xFF), interfaceId,
                    report, report.size, 500,
                )
            }.getOrDefault(-1)

            if (sent < 0) Log.i(TAG, "usb rumble write failed on $label (large=$large small=$small)")
            return sent >= 0
        }

        @Synchronized
        fun stop() {
            rumble(0, 0)
        }

        @Synchronized
        fun close() {
            runCatching { rumble(0, 0) }
            runCatching { connection.close() }
        }

        /**
         * DualSense USB output report.
         *
         * Byte 0 is the report id; the common block follows, so valid_flag0 lands at 1,
         * valid_flag1 at 2, then the right (high-frequency) and left (low-frequency) motors.
         * flag0 0x03 is COMPATIBLE_VIBRATION | HAPTICS_SELECT -- the pair that puts the pad in
         * classic rumble mode rather than its haptic actuators.
         */
        private fun dualsenseReport(large: Int, small: Int) = ByteArray(48).also {
            it[0] = 0x02
            it[1] = 0x03
            it[2] = 0x00
            it[3] = small.coerceIn(0, 255).toByte()
            it[4] = large.coerceIn(0, 255).toByte()
        }

        /** DualShock 4 USB output report: flags byte 0x01 = rumble only, leaving the lightbar alone. */
        private fun dualshock4Report(large: Int, small: Int) = ByteArray(32).also {
            it[0] = 0x05
            it[1] = 0x01
            it[4] = small.coerceIn(0, 255).toByte()
            it[5] = large.coerceIn(0, 255).toByte()
        }
    }

    @Volatile private var pad: Pad? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    /** True when a PlayStation pad is open and can be driven. */
    fun available(): Boolean = pad != null

    /**
     * The open pad, if [dev] looks like the same controller.
     *
     * Matched on NAME, because the bridge is exactly what hides the identity: the input node
     * carries the handheld's vendor id, so only the product string survives to be compared.
     */
    fun padFor(dev: android.view.InputDevice?): Pad? {
        val p = pad ?: return null
        val name = dev?.name ?: return null
        return if (namesMatch(name, p.productName)) p else null
    }

    private fun namesMatch(inputName: String, productName: String): Boolean {
        val a = inputName.lowercase()
        val b = productName.lowercase()
        if (b.isNotBlank() && (a.contains(b) || b.contains(a))) return true
        // The product string is not always carried through verbatim; the family name is enough
        // to tell a PlayStation pad from a handheld's built-in controller.
        return listOf("dualsense", "dualshock", "wireless controller").any { a.contains(it) && b.contains(it) }
    }

    /** Scan, ask for permission if needed, and follow attach/detach. Safe to call repeatedly. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_PERMISSION, UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val gone = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                            if (gone == null || isPlayStationPad(gone)) {
                                pad?.close()
                                pad = null
                                Log.i(TAG, "usb pad detached")
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    app.registerReceiver(r, filter)
                }
                receiver = r
            }
        }
        refresh(app)
    }

    fun stop() {
        pad?.close()
        pad = null
        receiver?.let { r -> runCatching { appContext?.unregisterReceiver(r) } }
        receiver = null
    }

    private fun isPlayStationPad(dev: UsbDevice): Boolean =
        dev.vendorId == VENDOR_SONY && (dev.productId in DUALSENSE || dev.productId in DUALSHOCK4)

    /** Open the first PlayStation pad on the bus, requesting permission if we do not have it. */
    private fun refresh(ctx: Context) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val device = usb.deviceList.values.firstOrNull { isPlayStationPad(it) }

        if (device == null) {
            pad?.close()
            pad = null
            return
        }
        if (pad != null) return

        if (!usb.hasPermission(device)) {
            // The system prompt is the only way in. Asking on attach means it appears while the
            // user is plugging the pad in, which is when it makes sense to them.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags,
            )
            runCatching { usb.requestPermission(device, intent) }
            Log.i(TAG, "usb rumble: asked for permission on ${device.productName}")
            return
        }

        val dualsense = device.productId in DUALSENSE
        // The HID interface, which is the one SET_REPORT is addressed to.
        var ifaceId = -1
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) { ifaceId = iface.id; break }
        }
        if (ifaceId < 0) {
            Log.i(TAG, "usb rumble: ${device.productName} exposes no HID interface")
            return
        }

        val conn = runCatching { usb.openDevice(device) }.getOrNull()
        if (conn == null) {
            Log.i(TAG, "usb rumble: could not open ${device.productName}")
            return
        }

        val name = device.productName ?: (if (dualsense) "DualSense" else "DualShock 4")
        pad = Pad("$name (USB)", name, conn, ifaceId, dualsense)
        Log.i(TAG, "usb rumble ready: $name iface=$ifaceId dualsense=$dualsense")
    }
}

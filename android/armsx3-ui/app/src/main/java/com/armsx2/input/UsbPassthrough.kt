package com.armsx2.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.armsx3.Rpcs3Bridge

/**
 * Real toy portals plugged into the device, passed through to the game: Skylanders, Disney Infinity,
 * Lego Dimensions and Kamen Rider, the ones the core both knows and emulates (sys_usbd's allow list).
 *
 * An app reaches a USB device on Android only through UsbManager, after the user allows it, and libusb
 * cannot open one by itself there. So permission is asked as soon as a portal is plugged in (or found
 * at start), and before a game boots each allowed portal is opened and its file descriptor handed to
 * the core, which wraps it in libusb in place of its own scan. A real portal replaces the emulated one.
 *
 * A game's portals are closed when the next one starts, not when it ends: the core still holds them
 * while it tears down, and a closed descriptor's number could be reused under it.
 */
object UsbPassthrough {
    private const val ACTION_PERMISSION = "com.armsx2.input.USB_PERMISSION"

    /** Vendor and product ids, as in sys_usbd's device allow list. */
    private val PORTALS = setOf(
        0x1430 to 0x0150, // Skylanders portal
        0x0E6F to 0x0129, // Disney Infinity base
        0x0E6F to 0x0241, // Lego Dimensions toy pad
        0x0E6F to 0x200A, // Kamen Rider Summonride portal
    )

    /** A real Skylanders portal is open for the running game. Main thread. */
    val realSkylanderPortal = mutableStateOf(false)

    private val connections = mutableListOf<UsbDeviceConnection>()
    private val asked = HashSet<String>()
    private var watching = false

    private fun isPortal(device: UsbDevice) = (device.vendorId to device.productId) in PORTALS

    private fun usb(context: Context) = context.getSystemService(UsbManager::class.java)

    /** Asks for each plugged-in portal not allowed yet, once per plug-in. */
    private fun askForPortals(context: Context) {
        val manager = usb(context) ?: return

        for (device in manager.deviceList.values) {
            if (!isPortal(device) || manager.hasPermission(device) || !asked.add(device.deviceName)) continue

            // Nothing listens for the answer: openForGame opens whatever is allowed when a game starts.
            // Explicit and mutable all the same, as targetSdk 34 requires of a PendingIntent Android fills in.
            // Runs at app start with a portal plugged in, so nothing here may take the app down
            runCatching {
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
                manager.requestPermission(device, pending)
            }
        }
    }

    /** Asks for portals as they are plugged in, and for any already there. Main thread. */
    fun watch(context: Context) {
        val app = context.applicationContext

        if (!watching) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> askForPortals(app)
                        // Unplugged: the next plug-in asks again if Android forgot the permission
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            device?.let { asked.remove(it.deviceName) }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            watching = true
        }

        askForPortals(app)
    }

    /** Opens each allowed portal for the game about to boot. Any thread. */
    fun openForGame(context: Context) {
        val manager = usb(context)

        // The previous game is gone by now (see the class comment)
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }

        val fds = mutableListOf<Int>()
        var skylanders = false

        if (manager != null) {
            for (device in manager.deviceList.values) {
                if (!isPortal(device) || !manager.hasPermission(device)) continue
                val connection = runCatching { manager.openDevice(device) }.getOrNull() ?: continue
                synchronized(connections) { connections += connection }
                fds += connection.fileDescriptor
                if (device.vendorId == 0x1430) skylanders = true
            }
        }

        Rpcs3Bridge.usbSetPassthrough(fds.joinToString(","))
        realSkylanderPortal.value = skylanders
    }
}

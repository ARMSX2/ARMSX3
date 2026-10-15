package com.armsx2.remoteplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio awake while a stream runs. A radio in power saving only picks up waiting
 * packets every 100 ms or so, which shows up as delay spikes in the picture on one side and in
 * player 2's input on the other. Held from the start of a session to its end and never otherwise,
 * so it costs nothing when remote play is not in use. Android only honours it while the app is in
 * front with the screen on, which is exactly when a stream runs.
 */
internal class RemotePlayWifi(context: Context) {
    private val lock = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(
                // The low-latency mode is new in Android 10. Before it, high performance is the
                // closest: it keeps the radio out of power saving too.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "armsx3:remoteplay",
            )
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    fun hold() {
        runCatching { lock?.acquire() }
    }

    fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}

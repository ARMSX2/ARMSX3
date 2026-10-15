package com.armsx2.remoteplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio awake while a stream runs. A radio in power saving only picks up waiting
 * packets every 100 ms or so, which shows up as delay spikes in the picture on one side and in
 * player 2's input on the other. Held from the start of a session to its end and never otherwise,
 * so it costs nothing when remote play is not in use.
 *
 * Two locks, as Moonlight holds them: the low-latency mode (Android 10 and up), which Android
 * honours only while the app is in front with the screen on and only where the device supports it,
 * and high performance, which keeps the radio out of power saving on devices without that mode.
 */
internal class RemotePlayWifi(context: Context) {
    private val locks: List<WifiManager.WifiLock> = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        buildList {
            @Suppress("DEPRECATION")
            add(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "armsx3:remoteplay"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "armsx3:remoteplay-low-latency"))
            }
        }.onEach { it.setReferenceCounted(false) }
    }.getOrDefault(emptyList())

    fun hold() {
        locks.forEach { runCatching { it.acquire() } }
    }

    fun release() {
        locks.forEach { runCatching { if (it.isHeld) it.release() } }
    }
}

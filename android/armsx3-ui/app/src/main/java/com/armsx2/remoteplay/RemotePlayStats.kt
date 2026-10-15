package com.armsx2.remoteplay

import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.Locale

/**
 * One line of what the stream is doing, from WebRTC's own statistics: codec and which encoder or
 * decoder runs it, picture size, frame rate, bitrate and round trip, plus why quality is limited
 * (host) or how long frames wait and take to decode (viewer). The host logs it; the viewer shows
 * it when its stats are on.
 *
 * Times and rates cover the time since the previous sample, not the whole session, so a slow
 * start does not hang over the numbers for minutes.
 */
internal class RemotePlayStats(private val sending: Boolean) {
    private var last: Map<String, Double> = emptyMap()
    private var lastTimeUs = 0.0

    /** Any thread; [onLine] runs on WebRTC's thread. */
    fun sample(peer: PeerConnection, onLine: (String) -> Unit) {
        runCatching { peer.getStats { report -> onLine(describe(report)) } }
    }

    @Synchronized
    private fun describe(report: RTCStatsReport): String {
        val all = report.statsMap.values
        val rtp = all.firstOrNull { it.type == (if (sending) "outbound-rtp" else "inbound-rtp") && it.members["kind"] == "video" }
            ?: return "no video yet"
        val m = rtp.members

        val codec = (m["codecId"] as? String)?.let { report.statsMap[it] }?.members?.get("mimeType")?.toString()?.substringAfter('/') ?: "?"
        val implementation = m[if (sending) "encoderImplementation" else "decoderImplementation"]?.toString()
        val width = (m["frameWidth"] as? Number)?.toInt() ?: 0
        val height = (m["frameHeight"] as? Number)?.toInt() ?: 0
        val fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0

        // Running totals, kept for the next sample.
        val totals = listOf(
            "bytesSent", "bytesReceived", "totalDecodeTime", "framesDecoded",
            "jitterBufferDelay", "jitterBufferEmittedCount", "framesDropped", "packetsLost",
        ).mapNotNull { key -> (m[key] as? Number)?.toDouble()?.let { key to it } }.toMap()
        val seconds = if (lastTimeUs > 0 && rtp.timestampUs > lastTimeUs) (rtp.timestampUs - lastTimeUs) / 1e6 else 0.0
        fun delta(key: String): Double? {
            val now = totals[key] ?: return null
            val before = last[key] ?: return null
            return (now - before).takeIf { seconds > 0 && it >= 0 }
        }
        // A running total in seconds over a frame count, as milliseconds per frame in this window.
        fun perFrameMs(total: String, count: String): Double? {
            val frames = delta(count)?.takeIf { it > 0 } ?: return null
            return delta(total)?.let { it * 1000 / frames }
        }

        val bytes = delta(if (sending) "bytesSent" else "bytesReceived")
        val mbps = if (bytes != null) bytes * 8 / seconds / 1e6 else 0.0

        val pair = all.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val rttMs = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)

        val parts = mutableListOf(
            if (implementation.isNullOrBlank()) codec else "$codec ($implementation)",
            "${width}x$height",
            String.format(Locale.US, "%.0f fps", fps),
            String.format(Locale.US, "%.1f Mbps", mbps),
        )
        rttMs?.let { parts += String.format(Locale.US, "rtt %.0f ms", it) }
        if (sending) {
            m["qualityLimitationReason"]?.toString()?.takeIf { it != "none" }?.let { parts += "limited by $it" }
        } else {
            perFrameMs("jitterBufferDelay", "jitterBufferEmittedCount")?.let { parts += String.format(Locale.US, "buffer %.0f ms", it) }
            perFrameMs("totalDecodeTime", "framesDecoded")?.let { parts += String.format(Locale.US, "decode %.1f ms", it) }
            delta("framesDropped")?.takeIf { it > 0 }?.let { parts += String.format(Locale.US, "dropped %.0f", it) }
            delta("packetsLost")?.takeIf { it > 0 }?.let { parts += String.format(Locale.US, "lost %.0f", it) }
        }

        last = totals
        lastTimeUs = rtp.timestampUs
        return parts.joinToString(" · ")
    }
}

/** The host's side of the stream ([RemotePlayProtocol.HostStats]) as one line of the stats. */
internal fun describeHost(stats: RemotePlayProtocol.HostStats): String {
    val parts = mutableListOf(
        "host: game ${stats.gameFps} fps",
        "captured ${stats.capturedFps}",
        "encoded ${stats.encodedFps}",
    )
    if (stats.dropped > 0) parts += "dropped ${stats.dropped}"
    if (stats.encoder.isNotEmpty()) parts += stats.encoder
    return parts.joinToString(" · ")
}

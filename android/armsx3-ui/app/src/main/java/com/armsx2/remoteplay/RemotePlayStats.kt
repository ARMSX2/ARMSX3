package com.armsx2.remoteplay

import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.Locale

/**
 * One line of what the stream is doing, from WebRTC's own statistics: codec and which encoder or
 * decoder runs it, picture size, frame rate, bitrate and round trip, plus why quality is limited
 * (host) or how long frames wait to be shown (viewer). The host logs it; the viewer shows it.
 */
internal class RemotePlayStats(private val sending: Boolean) {
    private var lastBytes = -1L
    private var lastTimeUs = 0.0

    /** Any thread; [onLine] runs on WebRTC's thread. */
    fun sample(peer: PeerConnection, onLine: (String) -> Unit) {
        runCatching { peer.getStats { report -> onLine(describe(report)) } }
    }

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

        val bytes = (m[if (sending) "bytesSent" else "bytesReceived"] as? Number)?.toLong() ?: 0L
        val mbps = if (lastBytes >= 0 && rtp.timestampUs > lastTimeUs) {
            (bytes - lastBytes) * 8.0 / (rtp.timestampUs - lastTimeUs)
        } else {
            0.0
        }
        lastBytes = bytes
        lastTimeUs = rtp.timestampUs

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
            perFrameMs(m, "totalDecodeTime", "framesDecoded")?.let { parts += String.format(Locale.US, "decode %.1f ms", it) }
            perFrameMs(m, "jitterBufferDelay", "jitterBufferEmittedCount")?.let { parts += String.format(Locale.US, "buffer %.0f ms", it) }
            (m["framesDropped"] as? Number)?.toLong()?.takeIf { it > 0 }?.let { parts += "dropped $it" }
        }
        return parts.joinToString(" · ")
    }

    // A running total in seconds over a frame count, as milliseconds per frame.
    private fun perFrameMs(m: Map<String, Any>, total: String, count: String): Double? {
        val seconds = (m[total] as? Number)?.toDouble() ?: return null
        val frames = (m[count] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return null
        return seconds * 1000 / frames
    }
}

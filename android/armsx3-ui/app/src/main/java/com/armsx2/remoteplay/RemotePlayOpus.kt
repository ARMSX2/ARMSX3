package com.armsx2.remoteplay

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "RemotePlay"

// How long either end waits on its codec, which takes and returns audio within a millisecond or two.
private const val CODEC_TIMEOUT_US = 20_000L

/**
 * The game's sound as Opus, for sessions online, where plain samples (1.5 Mbps) are a real share of
 * a host's upload. Android's own encoder, which is libopus, on every device from Android 10; below
 * that there is none and the audio goes out uncompressed. It makes one packet per 20 ms, its only
 * size, out of the core's 10 ms chunks. rp-audio thread only.
 */
internal class OpusEncoder private constructor(private val codec: MediaCodec) {
    val name: String = codec.name

    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    private var packetsOut = 0L

    /**
     * Encodes [frames] stereo frames from the start of [pcm]. Each packet this completes goes to
     * [onPacket] as soon as it is out. Throws IllegalStateException when the codec fails.
     */
    fun encode(pcm: ByteBuffer, frames: Int, onPacket: (ByteArray) -> Unit) {
        val bytes = frames * 4
        val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        val chunk = pcm.duplicate()
        chunk.position(0).limit(bytes)
        input.put(chunk)
        codec.queueInputBuffer(index, 0, bytes, framesIn * 1_000_000 / RemotePlayProtocol.SAMPLE_RATE, 0)
        framesIn += frames

        // Waits for the packets this chunk completes, so each leaves the moment it exists instead
        // of with the next chunk, 10 ms later.
        val due = framesIn / FRAME_SAMPLES
        while (true) {
            val waiting = packetsOut < due
            val out = codec.dequeueOutputBuffer(info, if (waiting) CODEC_TIMEOUT_US else 0)
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Late after all: it goes out with the next chunk.
                if (waiting) packetsOut = due
                return
            }
            if (out < 0) continue
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val buffer = codec.getOutputBuffer(out)
            if (!config && buffer != null && info.size > 0) {
                val packet = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(packet)
                packetsOut++
                onPacket(packet)
            }
            codec.releaseOutputBuffer(out, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        private const val FRAME_SAMPLES = 960 // 20 ms
        private const val PLATFORM_ENCODER = "c2.android.opus.encoder"

        /** Null where there is no Opus encoder: before Android 10, or on a device without one. */
        fun create(): OpusEncoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            var codec: MediaCodec? = null
            return try {
                codec = runCatching { MediaCodec.createByCodecName(PLATFORM_ENCODER) }.getOrNull()
                    ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RemotePlayProtocol.SAMPLE_RATE, 2)
                format.setInteger(MediaFormat.KEY_BIT_RATE, RemotePlayProtocol.OPUS_BITRATE)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                OpusEncoder(codec)
            } catch (e: Exception) {
                Log.w(TAG, "no Opus encoder", e)
                codec?.release()
                null
            }
        }
    }
}

/**
 * Turns the host's Opus packets back into samples. Android's own decoder, on every device from
 * Android 5. One thread only ([OpusIn]'s).
 */
internal class OpusDecoder private constructor(private val codec: MediaCodec) {
    private val info = MediaCodec.BufferInfo()
    private var timeUs = 0L

    /**
     * Decodes [packet]; its sound goes to [onPcm] (16-bit stereo) as soon as it is out. Throws
     * IllegalStateException when the codec fails.
     */
    fun decode(packet: ByteArray, onPcm: (ByteArray) -> Unit) {
        val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        input.put(packet)
        codec.queueInputBuffer(index, 0, packet.size, timeUs, 0)
        timeUs += 20_000

        var wait = CODEC_TIMEOUT_US
        while (true) {
            val out = codec.dequeueOutputBuffer(info, wait)
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (out < 0) continue
            val buffer = codec.getOutputBuffer(out)
            if (buffer != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(pcm)
                onPcm(pcm)
            }
            codec.releaseOutputBuffer(out, false)
            // Takes whatever else is already done, then goes back for the next packet.
            wait = 0
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        private fun format(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RemotePlayProtocol.SAMPLE_RATE, 2)

        /** Whether this device plays Opus: any Android from 5 does, unless its maker took it out. */
        fun available(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format()) != null
        }.getOrDefault(false)

        fun create(): OpusDecoder? {
            var codec: MediaCodec? = null
            return try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val format = format().apply {
                    // What an Opus decoder on Android takes: the stream's header (RFC 7845), then the
                    // samples to drop at the start and the seek pre-roll, in nanoseconds. Nothing is
                    // dropped: a live stream has no start to trim.
                    setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                    setByteBuffer("csd-1", nanoseconds(0))
                    setByteBuffer("csd-2", nanoseconds(80_000_000))
                }
                codec.configure(format, null, null, 0)
                codec.start()
                OpusDecoder(codec)
            } catch (e: Exception) {
                Log.w(TAG, "no Opus decoder", e)
                codec?.release()
                null
            }
        }

        private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1.toByte()) // version
            .put(2.toByte()) // channels
            .putShort(0) // samples to skip at the start
            .putInt(RemotePlayProtocol.SAMPLE_RATE)
            .putShort(0) // output gain
            .put(0.toByte()) // channel mapping: plain mono or stereo
            .array()

        private fun nanoseconds(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}

/**
 * The host's Opus packets, decoded on a thread of their own and handed to [out]. Each packet
 * carries the one before it too: when one goes missing, the copy in the next is played in its
 * place, so a single lost packet is never heard. A packet that arrives after the next one is
 * dropped, having been played from that copy already.
 */
internal class OpusIn(private val out: AudioOut) {
    private val queue = LinkedBlockingQueue<RemotePlayProtocol.OpusPacket>()
    private var worker: Thread? = null
    private var closed = false

    /** WebRTC's thread. */
    @Synchronized
    fun offer(packet: RemotePlayProtocol.OpusPacket) {
        if (closed) return
        if (worker == null) worker = thread(name = "rp-audio-in", isDaemon = true) { run() }
        // A stalled decoder must not pile up a backlog; what is current matters.
        if (queue.size < MAX_QUEUED) queue.offer(packet)
    }

    fun stop() {
        val running = synchronized(this) {
            closed = true
            worker.also { worker = null }
        }
        running?.interrupt()
        running?.join(300)
        queue.clear()
    }

    private fun run() {
        val decoder = OpusDecoder.create() ?: return
        var expected = 0
        var started = false
        try {
            while (true) {
                val packet = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (started) {
                    val gap = packet.seq - expected
                    if (gap < 0) continue
                    // The packet before this one went missing: its copy plays first.
                    if (gap > 0) packet.previous?.let { decoder.decode(it, out::enqueue) }
                }
                decoder.decode(packet.frame, out::enqueue)
                started = true
                expected = packet.seq + 1
            }
        } catch (_: InterruptedException) {
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Opus decoder failed", e)
        } finally {
            decoder.release()
        }
    }

    private companion object {
        const val MAX_QUEUED = 16
    }
}

package com.armsx2.remoteplay

import android.view.KeyEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Remote play: the host's running game streamed to a friend's device, where the friend plays as
 * player 2. Only the host emulates; the viewer shows video, plays audio and sends its pad.
 *
 * Wire format, shared by [RemotePlayHost] and [RemotePlayViewerActivity]:
 *  - Signaling: newline-delimited JSON over TCP on [PORT], advertised over DNS-SD as
 *    [SERVICE_TYPE] so a viewer on the same Wi-Fi finds the host without typing anything.
 *    Messages: hello (viewer, first), offer (host), answer (viewer), ice (both), bye (both).
 *  - Video: a WebRTC video track, host to viewer.
 *  - Audio and player 2's pad: WebRTC data channels, unordered and never retransmitted, so a lost
 *    packet is skipped rather than delaying everything behind it. The game's audio does not go
 *    through WebRTC's audio stack, which is built around the microphone.
 */
internal object RemotePlayProtocol {
    const val VERSION = 1
    const val PORT = 38433
    const val SERVICE_TYPE = "_armsx3rp._tcp"

    const val CHANNEL_INPUT = "input"
    const val CHANNEL_AUDIO = "audio"

    /** The stream is at most this size; the presenter scales larger frames down on the GPU. */
    const val MAX_WIDTH = 1280
    const val MAX_HEIGHT = 720
    const val MAX_BITRATE_BPS = 10_000_000

    const val SAMPLE_RATE = 48_000
    const val AUDIO_CHUNK_FRAMES = 480 // 10 ms

    private const val PACKET_PAD: Byte = 1
    private const val PACKET_AUDIO: Byte = 2
    private const val ZERO: Byte = 0
    private const val PAD_PACKET_SIZE = 16
    const val AUDIO_HEADER_SIZE = 8

    // Player 2's buttons, one bit each, in the order of PAD_KEYCODES. L2 and R2 travel as analog
    // bytes in PadState instead.
    const val PAD_UP = 1 shl 0
    const val PAD_RIGHT = 1 shl 1
    const val PAD_DOWN = 1 shl 2
    const val PAD_LEFT = 1 shl 3
    const val PAD_SELECT = 1 shl 4
    const val PAD_START = 1 shl 5
    const val PAD_L3 = 1 shl 6
    const val PAD_R3 = 1 shl 7
    const val PAD_TRIANGLE = 1 shl 8
    const val PAD_CIRCLE = 1 shl 9
    const val PAD_CROSS = 1 shl 10
    const val PAD_SQUARE = 1 shl 11
    const val PAD_L1 = 1 shl 12
    const val PAD_R1 = 1 shl 13

    /** The Android keycodes Rpcs3Bridge.setPadButton takes for each bit, by bit index. */
    val PAD_KEYCODES = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
    )

    /**
     * Player 2's whole pad. Sent whole on every change and as a heartbeat, so a lost packet is
     * corrected by the next one. Sticks are 0..255 with 128 at rest, the PS3's own range.
     */
    data class PadState(
        val buttons: Int = 0,
        val lx: Int = 128,
        val ly: Int = 128,
        val rx: Int = 128,
        val ry: Int = 128,
        val l2: Int = 0,
        val r2: Int = 0,
    )

    fun encodePad(seq: Int, pad: PadState): ByteBuffer {
        val out = ByteBuffer.allocate(PAD_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        out.put(PACKET_PAD)
        out.putInt(seq)
        out.putInt(pad.buttons)
        out.put(pad.lx.toByte()).put(pad.ly.toByte()).put(pad.rx.toByte()).put(pad.ry.toByte())
        out.put(pad.l2.toByte()).put(pad.r2.toByte()).put(ZERO)
        out.flip()
        return out
    }

    /** Returns the packet's sequence number and pad, or null for anything that is not a pad. */
    fun decodePad(data: ByteBuffer): Pair<Int, PadState>? {
        val buf = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < PAD_PACKET_SIZE || buf.get() != PACKET_PAD) return null
        val seq = buf.int
        val buttons = buf.int
        fun u8() = buf.get().toInt() and 0xff
        return seq to PadState(buttons, u8(), u8(), u8(), u8(), u8(), u8())
    }

    /** An audio packet: header, then [frames] stereo s16 frames copied from [pcm]. */
    fun encodeAudio(seq: Int, pcm: ByteBuffer, frames: Int): ByteBuffer {
        val bytes = frames * 4
        val out = ByteBuffer.allocate(AUDIO_HEADER_SIZE + bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put(PACKET_AUDIO).put(ZERO).put(ZERO).put(ZERO)
        out.putInt(seq)
        val src = pcm.duplicate()
        src.position(0).limit(bytes)
        out.put(src)
        out.flip()
        return out
    }

    /** The PCM bytes of an audio packet, or null for anything else. */
    fun decodeAudio(data: ByteBuffer): ByteArray? {
        val buf = data.duplicate()
        if (buf.remaining() <= AUDIO_HEADER_SIZE || buf.get() != PACKET_AUDIO) return null
        buf.position(buf.position() - 1 + AUDIO_HEADER_SIZE)
        val pcm = ByteArray(buf.remaining() and 3.inv())
        buf.get(pcm)
        return pcm
    }

    /** A stick axis in [-1, 1] as a PS3 stick byte: 0 at full up/left, 255 at full down/right. */
    fun axisByte(value: Float): Int {
        val v = value.coerceIn(-1f, 1f)
        return (128.5f + v * (if (v < 0f) 128f else 127f)).toInt().coerceIn(0, 255)
    }
}

package com.armsx2.ui.xmb

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes VAG, the PS3's (and PS2's) ADPCM sound clips: what the firmware's XMB sounds are.
 *
 * A VAG is "VAGp", a version, a word, then the data size at 0x0C and the sample rate at 0x10, both
 * big-endian, in a 48-byte header. The data is 16-byte frames of 28 samples: a byte of predictor
 * (high nibble) and shift (low nibble), a flags byte (7 ends the clip), then 14 bytes of 4-bit
 * samples, low nibble first, each scaled by the shift and added to the predicted value.
 */
object Vag {
    class Pcm(val samples: ShortArray, val rate: Int)

    private val COEF0 = intArrayOf(0, 60, 115, 98, 122)
    private val COEF1 = intArrayOf(0, 0, -52, -55, -60)
    private const val MAX_BYTES = 8 shl 20

    fun decode(vag: ByteArray): Pcm? {
        if (vag.size < 48 || String(vag, 0, 4, Charsets.ISO_8859_1) != "VAGp") return null
        fun be32(at: Int) = ((vag[at].toInt() and 0xFF) shl 24) or ((vag[at + 1].toInt() and 0xFF) shl 16) or
            ((vag[at + 2].toInt() and 0xFF) shl 8) or (vag[at + 3].toInt() and 0xFF)
        val size = be32(12).coerceIn(0, minOf(vag.size - 48, MAX_BYTES))
        val rate = be32(16)
        if (rate !in 8000..96000) return null
        val out = ShortArray(size / 16 * 28)
        var n = 0
        var h1 = 0
        var h2 = 0
        var frame = 48
        while (frame + 16 <= 48 + size) {
            val pred = (vag[frame].toInt() shr 4) and 0x0F
            val shift = vag[frame].toInt() and 0x0F
            val flags = vag[frame + 1].toInt() and 0xFF
            if (flags == 7) break
            val c0 = if (pred < 5) COEF0[pred] else 0
            val c1 = if (pred < 5) COEF1[pred] else 0
            for (i in 0 until 28) {
                val byte = vag[frame + 2 + i / 2].toInt() and 0xFF
                val nibble = (byte shr (4 * (i and 1))) and 0x0F
                var s = (if (nibble >= 8) nibble - 16 else nibble) shl 12
                s = s shr shift
                s += (h1 * c0 + h2 * c1 + 32) shr 6
                s = s.coerceIn(-32768, 32767)
                out[n++] = s.toShort()
                h2 = h1
                h1 = s
            }
            frame += 16
        }
        return Pcm(out.copyOf(n), rate)
    }

    /** One or two channels (left first) as a 16-bit WAV file, for SoundPool. */
    fun writeWav(channels: List<Pcm>, file: File): Boolean {
        if (channels.isEmpty()) return false
        val rate = channels[0].rate
        val frames = channels.minOf { it.samples.size }
        val count = channels.size
        val data = ByteBuffer.allocate(frames * count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) for (c in 0 until count) data.putShort(channels[c].samples[i])
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(count.toShort())
            putInt(rate); putInt(rate * count * 2); putShort((count * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
            true
        }.getOrDefault(false)
    }
}

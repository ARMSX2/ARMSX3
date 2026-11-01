package com.armsx2.ui.xmb

import android.graphics.Bitmap

/**
 * Decodes the GIM images the PS3 firmware keeps its XMB icons in.
 *
 * A GIM is ".GIM1.00" (PS3, big-endian) or "MIG.00.1" (PSP, little-endian), then blocks: an id, a
 * size, the offset of the next block and of the block's data. The root (2) holds pictures (3),
 * which hold the image (4). The image header has the format at +4, the pixel order at +6, the
 * size at +8 and +10, the pitch alignment at +14, and the offset of the pixels at +0x1C.
 *
 * Formats handled, all in plain (not swizzled) order, the only order the firmware's icons use:
 *  - 3: 32-bit RGBA, rows padded to the pitch alignment;
 *  - 8, 9, 10: DXT1, DXT3, DXT5. The firmware's 128px icons are DXT5. The blocks are stored as on
 *    any GPU, little-endian, even inside a big-endian PS3 GIM (checked against the real icons).
 */
object Gim {
    private const val MAX_SIDE = 4096

    fun decode(gim: ByteArray): Bitmap? = try {
        decodeChecked(gim)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun decodeChecked(gim: ByteArray): Bitmap? {
        if (gim.size < 16) return null
        val big = when (String(gim, 0, 8, Charsets.ISO_8859_1)) {
            ".GIM1.00" -> true
            "MIG.00.1" -> false
            else -> return null
        }
        fun u16(at: Long): Int {
            if (at < 0 || at + 2 > gim.size) return -1
            val i = at.toInt()
            val a = gim[i].toInt() and 0xFF
            val b = gim[i + 1].toInt() and 0xFF
            return if (big) (a shl 8) or b else (b shl 8) or a
        }
        fun u32(at: Long): Long {
            if (at < 0 || at + 4 > gim.size) return -1
            val i = at.toInt()
            val b0 = gim[i].toLong() and 0xFF
            val b1 = gim[i + 1].toLong() and 0xFF
            val b2 = gim[i + 2].toLong() and 0xFF
            val b3 = gim[i + 3].toLong() and 0xFF
            return if (big) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3 else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }
        fun findImage(start: Long, end: Long, depth: Int): Long? {
            var off = start
            while (depth < 4 && off + 16 <= end) {
                val id = u16(off)
                val size = u32(off + 4)
                val next = u32(off + 8)
                val data = u32(off + 12)
                if (id < 0 || size < 16 || size > end - off || data < 16 || data > size) return null
                if (id == 4) return off + data
                if (id == 2 || id == 3) findImage(off + data, off + size, depth + 1)?.let { return it }
                if (next <= 0) return null
                off += next
            }
            return null
        }
        val image = findImage(16, gim.size.toLong(), 0) ?: return null
        val format = u16(image + 4)
        val order = u16(image + 6)
        val width = u16(image + 8)
        val height = u16(image + 10)
        val pitchAlign = u16(image + 14).coerceAtLeast(1)
        val pixels = u32(image + 0x1C)
        if (order != 0 || width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || pixels < 0) return null
        val first = image + pixels
        val argb = IntArray(width * height)
        when (format) {
            3 -> {
                val row = width * 4
                val pitch = (row + pitchAlign - 1) / pitchAlign * pitchAlign
                if (first + pitch.toLong() * (height - 1) + row > gim.size) return null
                for (y in 0 until height) {
                    var at = (first + pitch.toLong() * y).toInt()
                    for (x in 0 until width) {
                        val r = gim[at].toInt() and 0xFF
                        val g = gim[at + 1].toInt() and 0xFF
                        val b = gim[at + 2].toInt() and 0xFF
                        val a = gim[at + 3].toInt() and 0xFF
                        argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        at += 4
                    }
                }
            }
            8, 9, 10 -> {
                val blockBytes = if (format == 8) 8 else 16
                val blocksWide = (width + 3) / 4
                val blocksHigh = (height + 3) / 4
                if (first + blocksWide.toLong() * blocksHigh * blockBytes > gim.size) return null
                decodeDxt(gim, first.toInt(), width, height, format, argb)
            }
            else -> return null
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    /** DXT1 (8), DXT3 (9) or DXT5 (10) blocks at [at] into [out], row-major 4x4 blocks. */
    fun decodeDxt(src: ByteArray, at: Int, width: Int, height: Int, format: Int, out: IntArray) {
        val blockBytes = if (format == 8) 8 else 16
        val blocksWide = (width + 3) / 4
        val colors = IntArray(4)
        val alphas = IntArray(8)
        var block = at
        for (by in 0 until (height + 3) / 4) {
            for (bx in 0 until blocksWide) {
                val colorAt = block + blockBytes - 8
                val c0 = le16(src, colorAt)
                val c1 = le16(src, colorAt + 2)
                colors[0] = rgb565(c0)
                colors[1] = rgb565(c1)
                if (format != 8 || c0 > c1) {
                    colors[2] = mix(colors[0], colors[1], 2, 1, 3)
                    colors[3] = mix(colors[0], colors[1], 1, 2, 3)
                } else {
                    colors[2] = mix(colors[0], colors[1], 1, 1, 2)
                    colors[3] = 0 // transparent black in 1-bit alpha DXT1
                }
                val indices = le32(src, colorAt + 4)
                if (format == 10) {
                    val a0 = src[block].toInt() and 0xFF
                    val a1 = src[block + 1].toInt() and 0xFF
                    alphas[0] = a0
                    alphas[1] = a1
                    if (a0 > a1) {
                        for (i in 1..6) alphas[i + 1] = ((7 - i) * a0 + i * a1) / 7
                    } else {
                        for (i in 1..4) alphas[i + 1] = ((5 - i) * a0 + i * a1) / 5
                        alphas[6] = 0
                        alphas[7] = 255
                    }
                }
                var alphaBits = 0L
                if (format == 10) {
                    for (i in 0 until 6) alphaBits = alphaBits or ((src[block + 2 + i].toLong() and 0xFF) shl (8 * i))
                } else if (format == 9) {
                    for (i in 0 until 8) alphaBits = alphaBits or ((src[block + i].toLong() and 0xFF) shl (8 * i))
                }
                for (i in 0 until 16) {
                    val x = bx * 4 + (i and 3)
                    val y = by * 4 + (i shr 2)
                    if (x >= width || y >= height) continue
                    val rgb = colors[((indices ushr (2 * i)) and 3L).toInt()]
                    val alpha = when (format) {
                        10 -> alphas[((alphaBits ushr (3 * i)) and 7L).toInt()]
                        9 -> (((alphaBits ushr (4 * i)) and 15L).toInt()) * 17
                        else -> if (colors[3] == 0 && ((indices ushr (2 * i)) and 3L) == 3L && c0 <= c1) 0 else 255
                    }
                    out[y * width + x] = (alpha shl 24) or (rgb and 0xFFFFFF)
                }
                block += blockBytes
            }
        }
    }

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun rgb565(c: Int): Int {
        val r = (c shr 11) and 31
        val g = (c shr 5) and 63
        val b = c and 31
        return ((r shl 3 or (r shr 2)) shl 16) or ((g shl 2 or (g shr 4)) shl 8) or (b shl 3 or (b shr 2))
    }

    private fun mix(a: Int, b: Int, wa: Int, wb: Int, d: Int): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * wa + ((b shr shift) and 0xFF) * wb) / d) and 0xFF
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

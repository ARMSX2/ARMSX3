package com.armsx2.ui.home

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads a PS3 theme (.p3t) far enough to take a picture out of it for the library background.
 *
 * Only what that needs is parsed, and every number in the file is treated as hostile: a theme is
 * user input, usually from a download site. Each offset and length is checked against the table it
 * points into before anything is read, in 64-bit arithmetic so no sum can wrap, and a file that
 * fails a structural check is reported as damaged instead of being read past its end.
 *
 * Layout, big-endian throughout. The header is the magic "P3TF", a version, then six
 * (offset, size) pairs for the element, id, string, integer, float and file tables. An element is
 * a 28-byte header (name, attribute count, then parent, sibling and child links, which this does
 * not need because it walks the table in order) followed by 16-byte attributes: name, type, and
 * two words that are a value or an (offset, size) pair. Type 6 points into the file table.
 *
 * Where the pictures are:
 *  - bgimagetable/bgimage holds "hd" and "sd" JPEGs on a static theme, stored as-is.
 *  - A dynamic theme's bgimage holds "anim" instead: a zlib-packed RAF scene whose textures are
 *    PS3 GPU (.gtf) images. Its place in the file is reported as [Result.anim] for [P3tAnimation].
 *  - info holds "preview", a zlib-packed GIM image of the whole theme with its XMB icons drawn in.
 *    It is the only picture a dynamic theme has, so it is the fallback when the scene is not a
 *    slideshow.
 */
object P3tTheme {
    /** Random access to the theme, so a large one never has to be read whole. */
    interface Bytes {
        val size: Long

        /** Exactly [length] bytes at [offset]. The parser only asks for ranges it has checked. */
        fun read(offset: Long, length: Int): ByteArray
    }

    class ArrayBytes(private val data: ByteArray) : Bytes {
        override val size: Long get() = data.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    enum class Source { HD, SD, PREVIEW }

    /** A picture from a theme: JPEG bytes as stored, or a decoded preview as RGBA pixels. */
    class Picture(val source: Source, val jpeg: ByteArray?, val rgba: ByteArray?, val width: Int, val height: Int)

    enum class Failure { NOT_A_THEME, DAMAGED, NO_PICTURE, DYNAMIC_ONLY }

    /** Where a dynamic theme's packed RAF scene is in the file. */
    class Anim(val offset: Long, val size: Long)

    /**
     * [picture] is null exactly when [failure] is set. [dynamic] marks an animated theme, and [anim]
     * is its scene when the reference to it is sound. [name] is the theme's own title, when it has
     * one ("Fallout NV Custom Dynamic").
     */
    class Result(val picture: Picture?, val dynamic: Boolean, val failure: Failure?, val anim: Anim? = null, val name: String? = null)

    private const val MAGIC = 0x50335446L // "P3TF"
    private const val HEADER_BYTES = 56L
    private const val ELEMENT_BYTES = 28L
    private const val ATTRIBUTE_BYTES = 16L
    private const val TYPE_STRING = 3L
    private const val TYPE_FILE = 6L

    // Bounds on what a real theme holds, so a crafted size cannot make us allocate without limit.
    private const val MAX_TABLE_BYTES = 16 shl 20
    private const val MAX_PICTURE_BYTES = 32 shl 20
    private const val MAX_PACKED_PREVIEW_BYTES = 16 shl 20
    private const val MAX_PREVIEW_BYTES = 64 shl 20
    private const val MAX_PREVIEW_SIDE = 4096
    private const val MAX_NAME_BYTES = 256
    private const val PROBE_BYTES = 256 shl 10

    fun isTheme(head: ByteArray): Boolean = head.size >= 4 && be32(head, 0) == MAGIC

    fun read(src: Bytes): Result = try {
        parse(src)
    } catch (_: Damaged) {
        Result(null, false, Failure.DAMAGED)
    } catch (_: IOException) {
        Result(null, false, Failure.DAMAGED)
    } catch (_: RuntimeException) {
        // Every read above is bounds-checked first. This is the backstop for a check that missed:
        // a bad theme is reported as damaged, never allowed to take the app down with it.
        Result(null, false, Failure.DAMAGED)
    }

    /** Anything structurally wrong with the file. Thrown by every bounds check. */
    private class Damaged : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Table(val offset: Long, val size: Long)

    private fun parse(src: Bytes): Result {
        if (src.size < HEADER_BYTES) {
            return Result(null, false, if (src.size >= 4 && isTheme(src.read(0, 4))) Failure.DAMAGED else Failure.NOT_A_THEME)
        }
        val header = src.read(0, HEADER_BYTES.toInt())
        if (be32(header, 0) != MAGIC) return Result(null, false, Failure.NOT_A_THEME)

        val tables = List(6) { i ->
            val offset = be32(header, 8 + i * 8)
            val size = be32(header, 12 + i * 8)
            if (offset > src.size || size > src.size - offset) throw Damaged()
            Table(offset, size)
        }
        val elementTable = tables[0]
        val stringTable = tables[2]
        val fileTable = tables[5]
        if (elementTable.size > MAX_TABLE_BYTES || stringTable.size > MAX_TABLE_BYTES) throw Damaged()
        val elements = src.read(elementTable.offset, elementTable.size.toInt())
        val strings = src.read(stringTable.offset, stringTable.size.toInt())

        // (offset, size) into the file table, or null when the reference points outside it.
        fun file(offset: Long, size: Long, cap: Int): Pair<Long, Int>? {
            if (offset > fileTable.size || size > fileTable.size - offset || size > cap || size < 4) return null
            return (fileTable.offset + offset) to size.toInt()
        }

        // Candidates are recorded by reference and only the chosen one is read whole, so a theme
        // with many backgrounds costs one picture's worth of memory, not all of them.
        class Candidate(val source: Source, val offset: Long, val size: Int, val width: Int, val height: Int)
        val hd = ArrayList<Candidate>()
        val sd = ArrayList<Candidate>()
        var preview: Pair<Long, Int>? = null
        var dynamic = false
        var anim: Anim? = null
        var title: String? = null
        // The scene is streamed out by the importer, never read whole here, so only its bounds matter.
        fun scene(offset: Long, size: Long): Anim? =
            if (offset > fileTable.size || size > fileTable.size - offset || size < 8) null
            else Anim(fileTable.offset + offset, size)

        var pos = 0L
        while (pos + ELEMENT_BYTES <= elements.size) {
            val name = cString(strings, be32(elements, pos.toInt()))
            val count = be32(elements, pos.toInt() + 4)
            if (count > (elements.size - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES) throw Damaged()
            val wanted = name == "bgimage" || name == "info"
            if (wanted) for (i in 0L until count) {
                val at = (pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES).toInt()
                // info's name: UTF-8 at (offset, length) in the string table.
                if (name == "info" && be32(elements, at + 4) == TYPE_STRING && cString(strings, be32(elements, at)) == "name") {
                    val offset = be32(elements, at + 8)
                    val length = be32(elements, at + 12)
                    if (offset <= strings.size && length <= minOf(strings.size - offset, MAX_NAME_BYTES.toLong())) {
                        title = String(strings, offset.toInt(), length.toInt(), Charsets.UTF_8).trimEnd('\u0000').trim().ifEmpty { null }
                    }
                    continue
                }
                if (be32(elements, at + 4) != TYPE_FILE) continue
                val attribute = cString(strings, be32(elements, at))
                val offset = be32(elements, at + 8)
                val size = be32(elements, at + 12)
                when {
                    name == "info" && attribute == "preview" -> preview = file(offset, size, MAX_PACKED_PREVIEW_BYTES)
                    name == "bgimage" && attribute == "anim" -> {
                        dynamic = true
                        if (anim == null) anim = scene(offset, size)
                    }
                    name == "bgimage" && (attribute == "hd" || attribute == "sd") -> {
                        val ref = file(offset, size, MAX_PICTURE_BYTES) ?: continue
                        // The frame header sits near the start; the whole file only if it does not.
                        val probe = src.read(ref.first, minOf(ref.second, PROBE_BYTES))
                        if (isRaf(probe)) {
                            dynamic = true
                            if (anim == null) anim = scene(offset, size)
                            continue
                        }
                        val dims = jpegSize(probe)
                            ?: (if (ref.second > PROBE_BYTES) jpegSize(src.read(ref.first, ref.second)) else null)
                            ?: continue
                        val source = if (attribute == "hd") Source.HD else Source.SD
                        val candidate = Candidate(source, ref.first, ref.second, dims.first, dims.second)
                        if (source == Source.HD) hd.add(candidate) else sd.add(candidate)
                    }
                }
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        // Largest picture of the best kind: HD over SD, then the preview.
        val best = hd.maxByOrNull { it.width.toLong() * it.height } ?: sd.maxByOrNull { it.width.toLong() * it.height }
        if (best != null) {
            val jpeg = src.read(best.offset, best.size)
            return Result(Picture(best.source, jpeg, null, best.width, best.height), dynamic, null, anim, title)
        }
        val fallback = preview?.let { decodePreview(src.read(it.first, it.second)) }
        if (fallback != null) return Result(fallback, dynamic, null, anim, title)
        return Result(null, dynamic, if (dynamic) Failure.DYNAMIC_ONLY else Failure.NO_PICTURE, anim, title)
    }

    private fun isRaf(b: ByteArray): Boolean =
        b.size >= 4 && (b[0] == '_'.code.toByte() || b[0] == 'R'.code.toByte()) &&
            String(b, 0, 4, Charsets.ISO_8859_1).let { it == "_RAF" || it == "RAFO" }

    /** Width and height from a JPEG's frame header, or null when it is not a readable JPEG. */
    internal fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue }                       // fill byte
                marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue } // no length
                marker == 0xD9 || marker == 0xDA -> return null           // end, or scan before a frame
            }
            val length = be16(b, i + 2)
            if (length < 2) return null
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (i + 9 > b.size) return null
                val height = be16(b, i + 5)
                val width = be16(b, i + 7)
                return if (width > 0 && height > 0) width to height else null
            }
            i += 2 + length
        }
        return null
    }

    /**
     * The theme preview as RGBA pixels. Handles the format themes use, 32-bit RGBA stored in plain
     * row order, in either the PS3 (".GIM1.00", big-endian) or PSP ("MIG.00.1") byte order. Any
     * other GIM, swizzled or paletted, is no fallback rather than a wrong picture.
     */
    internal fun decodePreview(packed: ByteArray): Picture? {
        val gim = inflate(packed, MAX_PREVIEW_BYTES) ?: return null
        if (gim.size < 16) return null
        val big = when (String(gim, 0, 8, Charsets.ISO_8859_1)) {
            ".GIM1.00" -> true
            "MIG.00.1" -> false
            else -> return null
        }
        val u16 = { at: Int -> if (at < 0 || at + 2 > gim.size) -1 else if (big) be16(gim, at) else le16(gim, at) }
        val u32 = { at: Int -> if (at < 0 || at + 4 > gim.size) -1L else if (big) be32(gim, at) else le32(gim, at) }

        // Blocks: id u16, unused u16, size u32, next u32, data u32; the last three relative to the
        // block. The root (2) holds pictures (3), which hold the image (4).
        fun findImage(start: Long, end: Long, depth: Int): Int? {
            var off = start
            while (depth < 4 && off + 16 <= end) {
                val id = u16(off.toInt())
                val size = u32(off.toInt() + 4)
                val next = u32(off.toInt() + 8)
                val data = u32(off.toInt() + 12)
                if (id < 0 || size < 16 || size > end - off || data < 16 || data > size) return null
                if (id == 4) return (off + data).toInt()
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
        val bpp = u16(image + 12)
        val pitchAlign = u16(image + 14).coerceAtLeast(1)
        val pixels = u32(image + 0x1C)
        if (format != 3 || order != 0 || bpp != 32) return null
        if (width !in 1..MAX_PREVIEW_SIDE || height !in 1..MAX_PREVIEW_SIDE || pixels < 0) return null
        val row = width * 4
        val pitch = (row + pitchAlign - 1) / pitchAlign * pitchAlign
        val first = image + pixels
        if (first < 0 || first + pitch.toLong() * (height - 1) + row > gim.size) return null

        val rgba = ByteArray(row * height)
        for (y in 0 until height) System.arraycopy(gim, (first + pitch.toLong() * y).toInt(), rgba, y * row, row)
        return Picture(Source.PREVIEW, null, rgba, width, height)
    }

    /** zlib, refusing to produce more than [cap] bytes so a crafted stream cannot exhaust memory. */
    private fun inflate(input: ByteArray, cap: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 shl 10)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > cap) return null
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun cString(table: ByteArray, offset: Long): String {
        if (offset < 0 || offset >= table.size) throw Damaged()
        val start = offset.toInt()
        var end = start
        while (end < table.size && end - start < MAX_NAME_BYTES && table[end] != 0.toByte()) end++
        return String(table, start, end - start, Charsets.ISO_8859_1)
    }

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun be32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) throw Damaged()
        return ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}

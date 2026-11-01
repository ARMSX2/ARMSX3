package com.armsx2.ui.xmb

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A PS3 resource container (.rco) from the installed firmware, read far enough to take named
 * images and sounds out of it: the XMB's own icons and system sounds.
 *
 * Layout, big-endian, worked out from the firmware's own files (xmb_ingame.rco,
 * explore_category_*.rco, system_plugin.rco) with the PSP's RCO format as the guide:
 *  - "FRP\0", a version (0x130 here), a word, and a compression word. With its 0x10 bit set, the
 *    tables after the 0xA4-byte header are one zlib stream: three words at 0xA4 (packed size,
 *    unpacked size, longest text), the stream at 0xB0. Every table offset in the header then
 *    points into the header followed by the inflated block.
 *  - Header words used here: 0x1C the sound table, 0x24 the image table, 0x40 the names (labels),
 *    and the data areas 0x80 (images) and 0x88 (sounds). Those two are offsets in the FILE.
 *  - A table entry is a 0x28-byte header (label offset at +4, child count at +0x10, distance to
 *    the next sibling at +0x14) followed by its own fields:
 *      image: u16 format (5 = GIM), u16 compression (1 = zlib), u32 packed size, u32 offset into
 *             the image data, and the unpacked size when compressed;
 *      sound: u16 format (1 = VAG), u8 channel count, a pad byte, u32 total, u32 offset, then a
 *             (size, offset) pair per channel, each channel a whole VAG file.
 *
 * The firmware is the user's own install, but it is still read defensively: every offset and size
 * is checked against what it points into, and anything that does not fit counts as absent.
 */
class Rco private constructor(private val file: ByteArray, private val table: ByteArray) {
    private class ImageEntry(val compression: Int, val packed: Int, val offset: Long, val size: Int)
    private class SoundEntry(val format: Int, val channels: List<Pair<Long, Int>>)

    private val images = HashMap<String, ImageEntry>()
    private val sounds = HashMap<String, SoundEntry>()

    val imageNames: Set<String> get() = images.keys
    val soundNames: Set<String> get() = sounds.keys

    private fun be16(b: ByteArray, at: Long): Int {
        if (at < 0 || at + 2 > b.size) throw Damaged()
        val i = at.toInt()
        return ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }

    private fun be32(b: ByteArray, at: Long): Long {
        if (at < 0 || at + 4 > b.size) throw Damaged()
        val i = at.toInt()
        return ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    }

    private fun label(base: Long, offset: Long): String {
        val start = base + offset
        if (start < 0 || start >= table.size) throw Damaged()
        var end = start.toInt()
        while (end < table.size && end - start < MAX_NAME && table[end] != 0.toByte()) end++
        return String(table, start.toInt(), end - start.toInt(), Charsets.ISO_8859_1)
    }

    private fun parse() {
        val labels = be32(file, 0x40)
        val imageTable = be32(file, 0x24)
        if (imageTable != NONE) {
            walk(imageTable) { entry, name ->
                val compression = be16(table, entry + 0x2A)
                val packed = be32(table, entry + 0x2C)
                val offset = be32(table, entry + 0x30)
                val size = if (compression != 0) be32(table, entry + 0x34) else packed
                if (packed in 1..MAX_IMAGE && size in 1..MAX_IMAGE) {
                    images[label(labels, name)] = ImageEntry(compression, packed.toInt(), offset, size.toInt())
                }
            }
        }
        val soundTable = be32(file, 0x1C)
        if (soundTable != NONE) {
            walk(soundTable) { entry, name ->
                val format = be16(table, entry + 0x28)
                val count = table.getOrElse((entry + 0x2A).toInt()) { 0 }.toInt() and 0xFF
                if (count in 1..2) {
                    val channels = List(count) { c ->
                        val size = be32(table, entry + 0x34 + 8L * c)
                        val offset = be32(table, entry + 0x38 + 8L * c)
                        offset to size.toInt()
                    }
                    if (channels.all { it.second in 1..MAX_SOUND }) sounds[label(labels, name)] = SoundEntry(format, channels)
                }
            }
        }
    }

    /** Calls [each] with every child of the table at [at]: the entry's offset and its label. */
    private fun walk(at: Long, each: (entry: Long, label: Long) -> Unit) {
        val count = be32(table, at + 0x10)
        if (count > MAX_ENTRIES) throw Damaged()
        var entry = at + HEADER
        repeat(count.toInt()) {
            if (entry + HEADER > table.size) throw Damaged()
            each(entry, be32(table, entry + 4))
            val next = be32(table, entry + 0x14)
            if (next <= 0 || next > table.size) return
            entry += next
        }
    }

    /** The image called [name], unpacked (a GIM for every image the firmware's XMB uses). */
    fun image(name: String): ByteArray? {
        val e = images[name] ?: return null
        val data = be32(file, 0x80)
        val at = data + e.offset
        if (data == NONE || at < 0 || at + e.packed > file.size) return null
        val bytes = file.copyOfRange(at.toInt(), at.toInt() + e.packed)
        return if (e.compression == 1) inflate(bytes, e.size) else bytes
    }

    /** The sound called [name] as one VAG file per channel, left first. */
    fun sound(name: String): List<ByteArray>? {
        val e = sounds[name] ?: return null
        if (e.format != 1) return null
        val data = be32(file, 0x88)
        if (data == NONE) return null
        return e.channels.map { (offset, size) ->
            val at = data + offset
            if (at < 0 || at + size > file.size) return null
            file.copyOfRange(at.toInt(), at.toInt() + size)
        }
    }

    private class Damaged : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private const val MAGIC = 0x46525000L // "FRP\0"
        private const val NONE = 0xFFFFFFFFL
        private const val HEADER = 0x28L
        private const val MAX_FILE = 32 shl 20
        private const val MAX_TABLE = 16 shl 20
        private const val MAX_IMAGE = 8L shl 20
        private const val MAX_SOUND = 4 shl 20
        private const val MAX_ENTRIES = 4096L
        private const val MAX_NAME = 256

        /** The container in [f], or null when it is missing or not one this can read. */
        fun open(f: File): Rco? {
            return try {
                if (!f.isFile || f.length() < 0xB0 || f.length() > MAX_FILE) null else fromBytes(f.readBytes())
            } catch (_: Exception) {
                null
            }
        }

        fun fromBytes(bytes: ByteArray): Rco? {
            fun word(at: Int): Long = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
            if (bytes.size < 0xB0 || word(0) != MAGIC) return null
            return try {
                val table = if ((word(0x0C) and 0xF0L) == 0x10L) {
                    val packed = word(0xA4)
                    val unpacked = word(0xA8)
                    if (packed <= 0 || packed > bytes.size - 0xB0L || unpacked <= 0 || unpacked > MAX_TABLE) return null
                    val inflated = inflate(bytes.copyOfRange(0xB0, 0xB0 + packed.toInt()), unpacked.toInt()) ?: return null
                    bytes.copyOfRange(0, 0xA4) + inflated
                } else {
                    bytes
                }
                Rco(bytes, table).also { it.parse() }
            } catch (_: Damaged) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        /** zlib, expecting exactly [size] bytes and refusing more. */
        private fun inflate(input: ByteArray, size: Int): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(input)
                val out = ByteArrayOutputStream(size)
                val buffer = ByteArray(64 shl 10)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    out.write(buffer, 0, n)
                    if (out.size() > size) return null
                }
                out.toByteArray().takeIf { it.size == size }
            } catch (_: DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }
    }
}

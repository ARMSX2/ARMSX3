package com.armsx2

import net.rpcsx.RPCSX
import java.io.File

/**
 * Minimal PARAM.SFO reader — enough to answer "which version of this game am I running?".
 *
 * The info tab could not say whether a title update had installed, which matters more than it
 * sounds: Portal 2 (BLUS30732) on the unpatched 1.00 disc behaves differently from the patched
 * build, and the only way to tell them apart was to read APP_VER out of the emulator log.
 *
 * The format is a fixed header, a table of fixed-size entries, then a key blob and a data blob:
 *
 *   header   magic "\0PSF", version, keyTableStart, dataTableStart, entryCount   (all LE)
 *   entry    keyOffset:u16 fmt:u16 len:u32 maxLen:u32 dataOffset:u32             (16 bytes)
 *   key      NUL-terminated string at keyTableStart + keyOffset
 *   data     at dataTableStart + dataOffset, `len` bytes, NUL-terminated when fmt is a string
 *
 * Only UTF-8 string values (fmt 0x0204) are returned; the fields worth showing are all strings
 * and an integer reader would be dead code.
 */
object Ps3Sfo {

    private const val MAGIC = 0x46535000 // "\0PSF" little-endian

    /** Parsed key/value pairs, or an empty map for anything that is not a readable SFO. */
    fun read(file: File): Map<String, String> = runCatching {
        if (!file.isFile || file.length() < 20 || file.length() > 1 shl 20) return emptyMap()

        val bytes = file.readBytes()

        fun u32(at: Int): Int =
            (bytes[at].toInt() and 0xff) or
                ((bytes[at + 1].toInt() and 0xff) shl 8) or
                ((bytes[at + 2].toInt() and 0xff) shl 16) or
                ((bytes[at + 3].toInt() and 0xff) shl 24)

        fun u16(at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        if (u32(0) != MAGIC) return emptyMap()

        val keyTable = u32(8)
        val dataTable = u32(12)
        val count = u32(16)

        // A corrupt or truncated file must read as "unknown", never throw into the UI.
        if (keyTable !in 0..bytes.size || dataTable !in 0..bytes.size || count !in 0..4096) {
            return emptyMap()
        }

        buildMap {
            for (i in 0 until count) {
                val entry = 20 + i * 16
                if (entry + 16 > bytes.size) break

                val keyAt = keyTable + u16(entry)
                val fmt = u16(entry + 2)
                val len = u32(entry + 4)
                val dataAt = dataTable + u32(entry + 12)

                if (fmt != 0x0204) continue
                if (keyAt !in 0 until bytes.size || len < 0) continue
                if (dataAt < 0 || dataAt + len > bytes.size) continue

                var keyEnd = keyAt
                while (keyEnd < bytes.size && bytes[keyEnd].toInt() != 0) keyEnd++

                val key = String(bytes, keyAt, keyEnd - keyAt, Charsets.UTF_8)

                // Strings carry their terminator inside `len`.
                var valueEnd = dataAt + len
                while (valueEnd > dataAt && bytes[valueEnd - 1].toInt() == 0) valueEnd--

                if (key.isNotEmpty()) {
                    put(key, String(bytes, dataAt, valueEnd - dataAt, Charsets.UTF_8))
                }
            }
        }
    }.getOrDefault(emptyMap())

    /** `APP_VER` of the installed title update for [serial], or null when none is installed. */
    fun installedUpdateVersion(serial: String?): String? {
        val id = serial?.takeIf { it.isNotBlank() } ?: return null
        val sfo = File(RPCSX.rootDirectory, "config/dev_hdd0/game/$id/PARAM.SFO")
        return read(sfo)["APP_VER"]?.takeIf { it.isNotBlank() }
    }
}

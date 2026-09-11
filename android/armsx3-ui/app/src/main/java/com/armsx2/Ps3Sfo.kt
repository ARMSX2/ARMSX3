package com.armsx2

import com.armsx2.runtime.MainActivityRuntime
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
    /**
     * Every plausible emulator root, best first.
     *
     * RPCSX.rootDirectory alone is not safe to rely on here. It is empty until
     * Rpcs3Bridge.initialize runs, and that only happens when the NATIVE CORE initialises -- so
     * anything asking this question before a game has been booted resolved "config/dev_hdd0/..."
     * as a RELATIVE path, found nothing, and reported every title as having no update installed.
     * Open the app and go straight to the package screen or a game's info tab and that is exactly
     * what happened: Batman and Watch Dogs both had 01.04 on disk and both read as out of date.
     *
     * systemDirPosix() is the configured root and needs no native init, which is why it comes
     * second rather than not at all. The external files dir is the fallback for an install that
     * never had a custom root set.
     */
    private fun hdd0Roots(): List<File> = buildList {
        RPCSX.rootDirectory.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        runCatching { MainActivityRuntime.systemDirPosix() }.getOrNull()?.let { add(File(it)) }
        runCatching {
            MainActivityRuntime.instance?.applicationContext?.getExternalFilesDir(null)
        }.getOrNull()?.let { add(it) }
    }.distinctBy { it.absolutePath }

    /** The game directory for [id] under whichever root actually holds it, or null. */
    private fun gameDir(id: String): File? =
        hdd0Roots().map { File(it, "config/dev_hdd0/game/$id") }.firstOrNull { it.isDirectory }

    fun installedUpdateVersion(serial: String?): String? {
        val id = serial?.takeIf { it.isNotBlank() } ?: return null
        val dir = gameDir(id) ?: return null
        return read(File(dir, "PARAM.SFO"))["APP_VER"]?.takeIf { it.isNotBlank() }
    }

    /**
     * How many add-ons are installed for this title, counted from two places.
     *
     * There is no single "DLC installed" flag to read. A package unpacks into
     * dev_hdd0/game/<its own install dir>/, and for most PS3 add-ons that directory is the base
     * game's own title id -- the same directory a title update lands in -- so add-on files merge
     * into the update's tree and directory presence alone cannot tell the two apart. That is why
     * this does not simply look for a folder.
     *
     * What it counts instead:
     *  - licence files in home/<user>/exdata/. Each paid add-on installs one .rap or .edat named
     *    by its content id, and a content id embeds the title's serial
     *    (UP0001-BLUS30443_00-SOMEDLCID000001.rap), so these are countable and unambiguous.
     *  - any OTHER directory under dev_hdd0/game/ whose PARAM.SFO names this serial as its
     *    TITLE_ID, which catches add-ons that do install somewhere of their own.
     *
     * The known gap is free add-ons that need no licence and unpack into the game's own folder:
     * they leave nothing this can distinguish from the update, and are undercounted. A count that
     * is right for paid content and silent about the rest beats a badge that guesses.
     */
    fun installedDlcCount(serial: String?): Int {
        val id = serial?.takeIf { it.isNotBlank() } ?: return 0
        val root = hdd0Roots()
            .map { File(it, "config/dev_hdd0") }
            .firstOrNull { it.isDirectory }
            ?: return 0

        val licences = File(root, "home").listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { user ->
                File(user, "exdata").listFiles()
                    ?.count { f ->
                        f.isFile &&
                            (f.extension.equals("rap", true) || f.extension.equals("edat", true)) &&
                            f.name.contains(id, ignoreCase = true)
                    } ?: 0
            } ?: 0

        val contentDirs = File(root, "game").listFiles()
            ?.count { dir ->
                dir.isDirectory &&
                    !dir.name.equals(id, ignoreCase = true) &&
                    read(File(dir, "PARAM.SFO"))["TITLE_ID"]?.equals(id, ignoreCase = true) == true
            } ?: 0

        return licences + contentDirs
    }
}

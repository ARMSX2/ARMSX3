package com.armsx2.input

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.Rpcs3Bridge
import net.rpcsx.RPCSX
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The emulated toy portals: Skylanders, Disney Infinity and Lego Dimensions, and which figure files
 * are on them.
 *
 * The core attaches all three to every game, unless a real one is passed through (UsbPassthrough),
 * and only those series' games talk to them. A game sees a figure placed or lifted the moment it
 * happens here, as with RPCS3's own managers. Figures are dumps in the data folder, one directory per
 * series, and a placed file stays open so the game's changes to the figure are written back into it.
 *
 * The portals outlive a game in the core, so what is placed does too, here as there.
 */
object ToyPortals {
    private const val KEY_MENU = "toyPortal.menu"

    /** Portal in the in-game menu (USB Devices settings). On unless switched off. */
    fun menuEnabled(): Boolean = MainActivityRuntime.prefs.getBoolean(KEY_MENU, true)

    fun setMenuEnabled(on: Boolean) {
        MainActivityRuntime.prefs.edit().putBoolean(KEY_MENU, on).apply()
    }

    internal fun folder(name: String): File = File(RPCSX.rootDirectory, name).apply { mkdirs() }

    internal fun figures(folder: File, extensions: Set<String>): List<File> =
        folder.listFiles { file -> file.isFile && file.extension.lowercase() in extensions }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** Figure dumps are a few hundred bytes to a kilobyte. A bigger file is something else, a disc
     *  image's .bin above all, and is not copied in. */
    private const val MAX_FIGURE_BYTES = 64 * 1024

    /** A file picked to add: its name, and how to read it. */
    class Offered(val name: String, val open: () -> InputStream?)

    fun offered(file: File) = Offered(file.name) { file.inputStream() }

    /** From the system picker. Not on the main thread: asks the provider for the name. */
    fun offered(context: Context, uri: Uri): Offered {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return Offered(name) { context.contentResolver.openInputStream(uri) }
    }

    /** What became of the files offered to [add]: how many are in the folder now, and which were not, by name. */
    data class Added(val count: Int, val notFigures: List<String>, val failed: List<String>)

    /**
     * Copies figure files into [folder], where the portal lists them, and the game saves to the copy
     * from then on. A file is taken when it has one of [extensions] and holds at least [size] bytes,
     * what the core reads of a figure, without being too big to be one. A name already in use gets a
     * number rather than replacing that file, which may be on the portal; the same figure again, byte
     * for byte, is already there. IO thread.
     */
    internal fun add(folder: File, extensions: Set<String>, size: Int, files: List<Offered>): Added {
        var added = 0
        val notFigures = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (file in files) {
            // The data folder can be on an SD card, whose FAT names take none of these
            val name = file.name.substringAfterLast('/')
                .map { if (it < ' ' || it in "\\:*?\"<>|") '_' else it }
                .joinToString("")
                .trim()
            if (name.substringAfterLast('.', "").lowercase() !in extensions || name.substringBeforeLast('.').isBlank()) {
                notFigures += name
                continue
            }

            val data = runCatching { file.open()?.use { readUpTo(it, MAX_FIGURE_BYTES + 1) } }.getOrNull()
            when {
                data == null -> failed += name
                data.size !in size..MAX_FIGURE_BYTES -> notFigures += name
                store(folder, name, data) -> added++
                else -> failed += name
            }
        }

        return Added(added, notFigures, failed)
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun store(folder: File, name: String, data: ByteArray): Boolean {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        // Written beside the target and renamed into place, so the list never shows half a figure
        val part = File(folder, "$name.part")

        val stored = runCatching {
            var target = File(folder, name)
            var n = 1
            while (target.exists()) {
                if (target.length() == data.size.toLong() && target.readBytes().contentEquals(data)) return true
                target = File(folder, "$base (${++n}).$extension")
            }
            part.writeBytes(data)
            part.renameTo(target)
        }.getOrDefault(false)

        if (!stored) part.delete()
        return stored
    }
}

/** Skylanders: the portal finds a place for each figure itself, up to 8. */
object SkylanderPortal {
    /** Portal place -> file name, for what is on it now. */
    val placed = mutableStateMapOf<Int, String>()

    // RPCS3's own list, *.dump included (skylander_dialog.cpp)
    val extensions = setOf("sky", "bin", "dmp", "dump")

    fun folder(): File = ToyPortals.folder("skylanders")

    fun figures(): List<File> = ToyPortals.figures(folder(), extensions)

    /** A figure is 64 blocks of 16 bytes, what load_skylander reads. IO thread. */
    fun add(files: List<ToyPortals.Offered>) = ToyPortals.add(folder(), extensions, 0x40 * 0x10, files)

    sealed interface Result {
        data object Placed : Result
        data object NotAFigure : Result
        data object Full : Result
    }

    fun place(file: File): Result =
        when (val slot = Rpcs3Bridge.skylanderLoad(file.absolutePath)) {
            -2 -> Result.Full
            in 0..7 -> {
                placed[slot] = file.name
                Result.Placed
            }
            else -> Result.NotAFigure
        }

    fun lift(slot: Int) {
        if (Rpcs3Bridge.skylanderRemove(slot)) placed.remove(slot)
    }
}

/**
 * A portal where the player picks the position: the Disney Infinity base and the Lego Dimensions toy
 * pad. Positions are the core's own (RPCS3's dialogs'), listed in the order a player reads them.
 * [size] is what the core reads of a figure.
 */
abstract class PositionPortal(private val directory: String, private val size: Int) {
    /** Position -> file name, for what is on it now. */
    val placed = mutableStateMapOf<Int, String>()

    /** Positions in reading order, each with its I18n key and any argument it takes. */
    abstract val positions: List<Pair<Int, Pair<String, Int?>>>

    protected abstract fun load(path: String, position: Int): Boolean
    protected abstract fun remove(position: Int): Boolean

    fun folder(): File = ToyPortals.folder(directory)

    val extensions = setOf("bin", "dmp")

    fun figures(): List<File> = ToyPortals.figures(folder(), extensions)

    /** IO thread. */
    fun add(files: List<ToyPortals.Offered>) = ToyPortals.add(folder(), extensions, size, files)

    /** False when the file is not a figure for this portal. */
    fun place(position: Int, file: File): Boolean {
        if (!load(file.absolutePath, position)) return false
        placed[position] = file.name
        return true
    }

    fun lift(position: Int) {
        if (remove(position)) placed.remove(position)
    }
}

// 20 blocks of 16 bytes (Infinity.h)
object InfinityBase : PositionPortal("infinity", 0x14 * 0x10) {
    override val positions = listOf(
        0 to ("portal.infinity.playset" to null),
        1 to ("portal.infinity.disc" to 2),
        2 to ("portal.infinity.disc" to 3),
        3 to ("portal.infinity.player" to 1),
        4 to ("portal.infinity.ability1" to 1),
        5 to ("portal.infinity.ability2" to 1),
        6 to ("portal.infinity.player" to 2),
        7 to ("portal.infinity.ability1" to 2),
        8 to ("portal.infinity.ability2" to 2),
    )

    override fun load(path: String, position: Int) = Rpcs3Bridge.infinityLoad(path, position)
    override fun remove(position: Int) = Rpcs3Bridge.infinityRemove(position)
}

// 45 pages of 4 bytes (Dimensions.h)
object DimensionsToyPad : PositionPortal("dimensions", 0x2D * 0x04) {
    // Index 1 is the centre pad; 0, 3 and 4 the left; 2, 5 and 6 the right (Dimensions.cpp)
    override val positions = listOf(
        1 to ("portal.dimensions.centre" to null),
        0 to ("portal.dimensions.left" to 1),
        3 to ("portal.dimensions.left" to 2),
        4 to ("portal.dimensions.left" to 3),
        2 to ("portal.dimensions.right" to 1),
        5 to ("portal.dimensions.right" to 2),
        6 to ("portal.dimensions.right" to 3),
    )

    override fun load(path: String, position: Int) = Rpcs3Bridge.dimensionsLoad(path, position)
    override fun remove(position: Int) = Rpcs3Bridge.dimensionsRemove(position)
}

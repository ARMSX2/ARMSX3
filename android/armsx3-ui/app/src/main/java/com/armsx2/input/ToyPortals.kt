package com.armsx2.input

import androidx.compose.runtime.mutableStateMapOf
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.Rpcs3Bridge
import net.rpcsx.RPCSX
import java.io.File

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
}

/** Skylanders: the portal finds a place for each figure itself, up to 8. */
object SkylanderPortal {
    /** Portal place -> file name, for what is on it now. */
    val placed = mutableStateMapOf<Int, String>()

    fun folder(): File = ToyPortals.folder("skylanders")

    fun figures(): List<File> = ToyPortals.figures(folder(), setOf("sky", "bin", "dmp"))

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
 */
abstract class PositionPortal(private val directory: String) {
    /** Position -> file name, for what is on it now. */
    val placed = mutableStateMapOf<Int, String>()

    /** Positions in reading order, each with its I18n key and any argument it takes. */
    abstract val positions: List<Pair<Int, Pair<String, Int?>>>

    protected abstract fun load(path: String, position: Int): Boolean
    protected abstract fun remove(position: Int): Boolean

    fun folder(): File = ToyPortals.folder(directory)

    fun figures(): List<File> = ToyPortals.figures(folder(), setOf("bin", "dmp"))

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

object InfinityBase : PositionPortal("infinity") {
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

object DimensionsToyPad : PositionPortal("dimensions") {
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

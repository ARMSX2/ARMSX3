package com.armsx2.input

import androidx.compose.runtime.mutableStateMapOf
import com.armsx3.Rpcs3Bridge
import net.rpcsx.RPCSX
import java.io.File

/**
 * The emulated Skylanders portal: which figure files are on it.
 *
 * The core attaches the portal to every game unless a real one is passed through (UsbPassthrough),
 * and a game sees a figure placed or lifted the moment it happens here, as with RPCS3's Skylanders
 * manager. Figures are .sky dumps in the data folder's skylanders directory; the portal keeps a
 * placed file open and writes the game's changes to the figure back into it.
 *
 * The portal itself outlives a game in the core, so what is placed does too, here as there.
 */
object SkylanderPortal {
    /** Portal place -> file name, for what is on it now. */
    val placed = mutableStateMapOf<Int, String>()

    fun folder(): File = File(RPCSX.rootDirectory, "skylanders").apply { mkdirs() }

    fun figures(): List<File> =
        folder().listFiles { file -> file.isFile && file.extension.equals("sky", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

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

package com.armsx2.data.library

import net.rpcsx.RPCSX
import java.io.File

/**
 * Licence (.rap) installation.
 *
 * A RAP's FILENAME is the content id it unlocks, so installing one is a copy into the user's
 * exdata directory and nothing else -- which is exactly what RPCS3 desktop's
 * InstallFileInExData does. The native installKey path cannot serve here: its RAP branch
 * derives the content id by decrypting the game's EBOOT, so it needs a game path, and calling
 * it with an empty one fails with "Failed to fetch NPDRM of SELF".
 */
object Licences {

    /** Where RPCS3 looks for the logged-in user's licence files. */
    fun exdataDir(): File = File(
        RPCSX.getHdd0Dir(),
        "home/${currentUser()}/exdata",
    )

    private fun currentUser(): String =
        runCatching { RPCSX.instance.getUser() }.getOrNull().orEmpty().ifBlank { "00000001" }

    /**
     * Copy [file] into exdata under its own name.
     *
     * The extension is written lower-case because that is what unself.cpp looks for when it
     * searches exdata for a licence matching a game's content id.
     */
    fun installRap(file: File): Boolean = runCatching {
        val bytes = file.readBytes()
        // Same floor as InstallFileInExData: anything shorter is not a key.
        if (bytes.size < 0x10) return false
        val dir = exdataDir()
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, file.nameWithoutExtension + ".rap").writeBytes(bytes)
        true
    }.getOrDefault(false)
}

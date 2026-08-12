package com.armsx2.data.library

import android.content.Context
import android.os.ParcelFileDescriptor
import net.rpcsx.ProgressRepository
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
    /**
     * Install [file] as the licence for the game at [gamePath].
     *
     * Prefers the content id read out of the game's own EBOOT over the one in the file name.
     * The name is only a convention: a licence saved as "license(1).rap", renamed, or handed
     * around by someone who tidied it up copies into exdata under a name nothing looks for,
     * so the install reports success and the game stays locked -- which is exactly what it
     * looks like from the outside, and what it was reported as.
     *
     * The native path decrypts the EBOOT's supplemental header to read the content id, which
     * works on a LOCKED game because that header is not what the licence protects. Falls back
     * to the name when there is no game to ask, or when the header cannot be read.
     */
    fun installRapForGame(context: Context, file: File, gamePath: String?): Boolean {
        if (!gamePath.isNullOrBlank() && RPCSX.initialized) {
            val installed = runCatching {
                val id = ProgressRepository.create(context, "Installing ${file.name}")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    RPCSX.instance.installKey(fd.fd, id, gamePath)
                }
            }.getOrDefault(false)

            if (installed) return true
        }

        return installRap(file)
    }

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

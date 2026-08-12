package com.armsx2

import java.io.File

/**
 * Cover art extracted from PS3 discs.
 *
 * ARMSX2 fetched covers from a GitHub repo keyed by serial. There is no
 * equivalent repo for the PS3, but there is something better: every disc
 * carries its own artwork at PS3_GAME/ICON0.PNG -- the image the console shows
 * on the XMB, and the one desktop RPCS3 shows in its game grid.
 *
 * The scanner extracts it once, on the pass that reads PARAM.SFO for the title
 * ID, so a cover costs nothing after the first scan: no network, no 404s, and
 * it always matches the disc it came from.
 *
 * Keyed by title ID (BLUS30464, BCES01234, ...), which is what PARAM.SFO gives
 * us and what everything else in the app already uses as the serial.
 */
object DiscIcons {

    private fun dir(): File =
        File(Pasx2Application.appContext.filesDir, "disc-icons").apply { mkdirs() }

    fun fileFor(titleId: String): File = File(dir(), "$titleId.png")

    /**
     * True when this game's icon has already been extracted.
     *
     * Length, not isFile: an empty file is indistinguishable from a real one to isFile, and
     * it is a shape this can actually end up in -- the extraction writes to a staging name
     * and renames, and neither the write nor the rename is checked. An empty icon then reads
     * as "already extracted" forever, and the card falls through to the text placeholder
     * because there is nothing for Coil to decode. Requiring bytes makes that self-repairing:
     * the next scan re-probes and overwrites it.
     */
    fun has(titleId: String): Boolean = fileFor(titleId).length() > 0L

    fun clear() {
        runCatching { dir().listFiles()?.forEach { it.delete() } }
    }
}

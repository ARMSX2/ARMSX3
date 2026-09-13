package com.armsx2.mods

import com.armsx2.EmuState
import com.armsx2.Ps3Sfo
import com.armsx2.runtime.MainActivityRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loose-file game mods, applied by mounting them over the game's own files.
 *
 * A PS3 mod is almost always a handful of files that replace things under the game's own
 * directory: textures, audio, a rebuilt archive. Doing that by hand means finding the install
 * folder, remembering what you overwrote, and hoping you can put it back.
 *
 * ## Nothing is copied
 *
 * Enabling a mod writes a state file and nothing else. The core reads those at boot and mounts
 * each of the mod's files over the matching path inside the game, because vfs::get walks the
 * mount list in reverse and takes the first entry carrying a host path, so the deepest mount
 * wins. See apply_game_mods in System.cpp.
 *
 * Three things follow, and they are the whole reason for doing it this way:
 *
 *  - The install is never written to, so a mod cannot damage a game and there is nothing to
 *    back up or restore. Storage cost is the mod itself and nothing more.
 *  - Disc images work. There is no folder inside an .iso to copy into, but there is a virtual
 *    path to mount over.
 *  - Turning a mod off is deleting one small file.
 *
 * Per FILE, not per directory. Mounting a mod's folder over USRDIR would replace the whole
 * directory and the game would see only the mod's files.
 *
 * ## When it takes effect
 *
 * Mounts are placed while a game boots, so toggling one changes what the game loads the next
 * time it starts. Nothing is applied underneath a running title, which is also why none of this
 * can corrupt a game mid-session.
 */
object ModManager {

    private const val TAG = "ARMSX3-Mods"

    /** Read by apply_game_mods in the core. Its presence is what "enabled" means. */
    private const val STATE_DIR = ".state"

    data class Mod(
        val name: String,
        val directory: File,
        val enabled: Boolean,
        /** Files the mod contains, for the UI to show what it would touch. */
        val fileCount: Int,
    )

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Where a game's mods live: `<root>/mods/<serial>/<mod name>/`, mirroring the layout inside
     * the game. Deliberately outside `config/dev_hdd0` so that reinstalling a title, or the
     * backup manager restoring one, cannot take a user's mods with it. The core builds the same
     * path from get_emu_dir(), so the two must stay in step.
     */
    fun modsRoot(serial: String): File? {
        val id = serial.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        val root = Ps3Sfo.storageRoot() ?: return null
        return File(File(root, "mods"), id)
    }

    /** The install directory, when the title has one. Informational only now that mods mount. */
    fun installDirFor(serial: String): File? =
        serial.trim().uppercase().takeIf { it.isNotEmpty() }?.let { Ps3Sfo.installDir(it) }

    /**
     * Whether a library entry can take mods.
     *
     * Any title with a serial can: mounting works over a disc image and an installed folder
     * alike, since both boot with a virtual root to mount into. This used to also require an
     * install directory, from back when enabling a mod meant copying files into one.
     */
    fun isModdable(@Suppress("UNUSED_PARAMETER") extension: String, serial: String?): Boolean =
        !serial.isNullOrBlank()

    /**
     * Places inside the game already holding a file called [fileName].
     *
     * A loose mod file carries no location: patch.ff does not know it is a Call of Duty file,
     * let alone that it belongs in USRDIR/english. But the GAME knows, because the file the mod
     * replaces is sitting there under the same name. Searching for it turns "type the path from
     * the readme" into "pick the one you meant", and catches the typo that would otherwise leave
     * a mod mounted somewhere nothing reads.
     *
     * Often more than one answer, and that is the point rather than a flaw: Call of Duty keeps a
     * patch.ff per language, and which one is a decision only the user can make.
     *
     * Returns empty for a disc image, which has no install directory to walk. The core could
     * enumerate one, but not from here.
     */
    fun matchingPaths(serial: String, fileName: String): List<String> {
        val name = fileName.trim().lowercase().takeIf { it.isNotEmpty() } ?: return emptyList()
        val install = installDirFor(serial) ?: return emptyList()
        val root = install.absolutePath.trimEnd('/')

        // Bounded: a game folder is normally small, but a mistyped root should not walk a card.
        var seen = 0
        return install.walkTopDown()
            .onEach { seen++ }
            .takeWhile { seen < 60_000 }
            .filter { it.isFile && it.name.lowercase() == name }
            .map { it.absolutePath.removePrefix(root).trimStart('/') }
            .take(12)
            .toList()
    }

    /**
     * Mods present for [serial], enabled state included.
     *
     * Dot-directories are skipped: [STATE_DIR] is our own bookkeeping sitting in the same
     * folder, and listing it as a mod would let a user toggle the record of what is on.
     */
    fun list(serial: String): List<Mod> {
        val root = modsRoot(serial) ?: return emptyList()
        val children = root.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                Mod(
                    name = dir.name,
                    directory = dir,
                    enabled = stateFile(serial, dir.name)?.isFile == true,
                    fileCount = dir.walkTopDown().count { it.isFile },
                )
            }
    }

    fun setEnabled(serial: String, modName: String, enable: Boolean): Result {
        val state = stateFile(serial, modName) ?: return Result.Failed("No storage available yet")

        if (!enable) {
            state.delete()
            android.util.Log.i(TAG, "disabled '$modName' for $serial")
            return Result.Ok
        }

        val modDir = modsRoot(serial)?.let { File(it, modName) }?.takeIf { it.isDirectory }
            ?: return Result.Failed("Mod folder is missing")
        val files = modDir.walkTopDown().count { it.isFile }
        if (files == 0) return Result.Failed("That mod has no files in it")

        state.parentFile?.mkdirs()
        val ok = runCatching { state.writeText("{}") }.isSuccess
        if (!ok) return Result.Failed("Could not save the mod state")

        android.util.Log.i(TAG, "enabled '$modName' for $serial: $files file(s) will be mounted")
        return Result.Ok
    }

    /**
     * Delete an imported mod and its enabled state.
     *
     * Only ever touches the mod store. Nothing was copied into the game to undo, which is the
     * point of mounting: removing a mod is deleting the files it brought and forgetting it was
     * on, and the game is untouched either way.
     */
    fun delete(serial: String, modName: String): Result {
        val root = modsRoot(serial) ?: return Result.Failed("No storage available yet")
        val dir = File(root, modName)

        // The state file goes first. If the delete fails halfway, a mod that is off with some
        // files missing is recoverable; one still marked enabled would try to mount them.
        stateFile(serial, modName)?.delete()

        if (dir.isDirectory && !dir.deleteRecursively()) {
            return Result.Failed("Could not remove '$modName'")
        }

        android.util.Log.i(TAG, "removed '$modName' from $serial")
        return Result.Ok
    }

    private fun stateFile(serial: String, modName: String): File? =
        modsRoot(serial)?.let { File(File(it, STATE_DIR), "$modName.json") }
}

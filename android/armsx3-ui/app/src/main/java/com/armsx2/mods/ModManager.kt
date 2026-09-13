package com.armsx2.mods

import com.armsx2.EmuState
import com.armsx2.Ps3Sfo
import com.armsx2.runtime.MainActivityRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loose-file game mods, applied to an installed title.
 *
 * A PS3 mod is almost always a handful of files that replace things under the game's own
 * directory -- textures, audio, a rebuilt archive. Doing that by hand means finding the install
 * folder, remembering what you overwrote, and hoping you can put it back. This keeps the same
 * mechanism and takes the bookkeeping off the user.
 *
 * ## Why files are copied rather than layered
 *
 * The obvious design is to mount the mod folder over the game's and never touch the install at
 * all. The VFS cannot do it: `vfs::mount` maps one virtual path to exactly one host path, with
 * no overlay, priority or fallback, so mounting a mod directory over USRDIR would REPLACE it --
 * the game would see only the mod's files and nothing else. Until the VFS grows a layered mount,
 * copying is the only way to apply a partial set of files.
 *
 * ## What that costs, and what is kept
 *
 * Only files a mod actually REPLACES are backed up, never the game. A mod that swaps three
 * textures costs three textures' worth of backup, not the size of the install. Files the mod
 * merely ADDS are recorded by name and deleted again on disable, with nothing stored.
 *
 * ## Disc images cannot be modded
 *
 * There is nowhere to put the files. You cannot write into an .iso, and with no overlay mount
 * there is no layer to put in front of it. [installDirFor] returns null for those and the UI
 * says so rather than offering a toggle that could not work.
 */
object ModManager {

    private const val TAG = "ARMSX3-Mods"

    /** Written into the mod store, not the game, so a wiped game folder cannot orphan it. */
    private const val STATE_DIR = ".state"
    private const val BACKUP_DIR = ".originals"

    /** One file a mod placed in the game directory. [replaced] decides how it is undone. */
    private data class AppliedFile(val relativePath: String, val replaced: Boolean)

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
     * the game directory. Deliberately outside `config/dev_hdd0` so that reinstalling a title,
     * or the backup manager restoring one, cannot take a user's mods with it.
     */
    fun modsRoot(serial: String): File? {
        val id = serial.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        val root = Ps3Sfo.storageRoot() ?: return null
        return File(File(root, "mods"), id)
    }

    /** The install directory a mod would be applied to, or null when there isn't one. */
    fun installDirFor(serial: String): File? =
        serial.trim().uppercase().takeIf { it.isNotEmpty() }?.let { Ps3Sfo.installDir(it) }

    /**
     * Mods present for [serial], enabled state included.
     *
     * Directories only, and dot-directories are skipped: [STATE_DIR] and [BACKUP_DIR] are our own
     * bookkeeping sitting in the same folder, and listing them as mods would let a user toggle
     * the record of what a mod did.
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
        // A mod rewrites files the running title has open. Applying one underneath a live core
        // is how you get a half-read archive and a crash that looks like an emulator bug.
        if (MainActivityRuntime.eState.value != EmuState.STOPPED) {
            return Result.Failed("Close the game first")
        }
        return if (enable) enable(serial, modName) else disable(serial, modName)
    }

    private fun enable(serial: String, modName: String): Result {
        val install = installDirFor(serial)
            ?: return Result.Failed("This game is not installed as a folder, so it cannot be modded")
        val modDir = modsRoot(serial)?.let { File(it, modName) }?.takeIf { it.isDirectory }
            ?: return Result.Failed("Mod folder is missing")
        if (stateFile(serial, modName)?.isFile == true) return Result.Ok

        val backupRoot = File(File(modsRoot(serial), BACKUP_DIR), modName)
        val applied = mutableListOf<AppliedFile>()

        val installPath = install.canonicalPath
        val files = modDir.walkTopDown().filter { it.isFile }.toList()

        for (source in files) {
            val relative = source.relativeTo(modDir).path
            val target = File(install, relative)

            // A mod is untrusted input: a "../.." in a path would otherwise write outside the
            // game directory entirely. Resolve first and refuse anything that escapes.
            val targetPath = runCatching { target.canonicalFile.path }.getOrNull()
            if (targetPath == null || !(targetPath == installPath || targetPath.startsWith("$installPath/"))) {
                android.util.Log.w(TAG, "refusing '$relative': resolves outside the game directory")
                rollback(install, backupRoot, applied)
                return Result.Failed("Mod contains an unsafe path and was not applied")
            }

            val replaced = target.isFile
            val ok = runCatching {
                if (replaced) {
                    val backup = File(backupRoot, relative)
                    backup.parentFile?.mkdirs()
                    target.copyTo(backup, overwrite = true)
                }
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }.isSuccess

            if (!ok) {
                android.util.Log.e(TAG, "failed to apply '$relative'; rolling back")
                rollback(install, backupRoot, applied)
                return Result.Failed("Could not write '$relative'")
            }

            applied += AppliedFile(relative, replaced)
        }

        writeState(serial, modName, applied)
        android.util.Log.i(TAG, "enabled '$modName' for $serial: ${applied.size} file(s), " +
            "${applied.count { it.replaced }} replaced")
        return Result.Ok
    }

    private fun disable(serial: String, modName: String): Result {
        val install = installDirFor(serial) ?: return Result.Failed("Game folder is missing")
        val state = stateFile(serial, modName)?.takeIf { it.isFile } ?: return Result.Ok
        val backupRoot = File(File(modsRoot(serial), BACKUP_DIR), modName)

        val applied = readState(state)
        rollback(install, backupRoot, applied)

        state.delete()
        backupRoot.deleteRecursively()
        android.util.Log.i(TAG, "disabled '$modName' for $serial: ${applied.size} file(s) reverted")
        return Result.Ok
    }

    /**
     * Undo [applied], newest first.
     *
     * Also used when applying fails partway, which is the reason it takes the list rather than
     * reading the state file: a half-applied mod has no state file yet, and leaving those files
     * behind would be the worst outcome of the lot -- a game modified by a mod the UI reports as
     * off, with nothing recording what changed.
     */
    private fun rollback(install: File, backupRoot: File, applied: List<AppliedFile>) {
        applied.asReversed().forEach { entry ->
            val target = File(install, entry.relativePath)
            runCatching {
                if (entry.replaced) {
                    val backup = File(backupRoot, entry.relativePath)
                    if (backup.isFile) backup.copyTo(target, overwrite = true)
                } else {
                    target.delete()
                }
            }.onFailure {
                android.util.Log.e(TAG, "could not revert '${entry.relativePath}': ${it.message}")
            }
        }
    }

    private fun stateFile(serial: String, modName: String): File? =
        modsRoot(serial)?.let { File(File(it, STATE_DIR), "$modName.json") }

    private fun writeState(serial: String, modName: String, applied: List<AppliedFile>) {
        val file = stateFile(serial, modName) ?: return
        file.parentFile?.mkdirs()
        val array = JSONArray()
        applied.forEach {
            array.put(JSONObject().put("path", it.relativePath).put("replaced", it.replaced))
        }
        runCatching { file.writeText(JSONObject().put("files", array).toString()) }
    }

    private fun readState(file: File): List<AppliedFile> = runCatching {
        val array = JSONObject(file.readText()).optJSONArray("files") ?: return emptyList()
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            AppliedFile(o.optString("path"), o.optBoolean("replaced"))
        }
    }.getOrDefault(emptyList())
}

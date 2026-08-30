package com.armsx2

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.armsx2.data.library.ParamSfo
import net.rpcsx.RPCSX
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Imports PS3 save data into `config/dev_hdd0/home/<user>/savedata/` from a SAF-picked folder or
 * archive.
 *
 * This exists because of a platform rule, not a bug of ours. Android 11 blocks third-party file
 * managers from writing into `Android/data/<pkg>/`, so a user who downloads a roster or a save
 * cannot put it where the emulator reads from: ZArchiver reports `EACCES (Permission denied)` and
 * there is no way round it from outside the app. Reported against All Pro Football 2K8 on an Ayn
 * Thor Pro. We are the only process that can still write there, so the copy has to happen in here.
 *
 * The destination folder name comes from the save's own PARAM.SFO, not from what the user's folder
 * or archive happened to be called. That is the whole reliability argument for this class. Games
 * enumerate saves by matching `dirNamePrefix` against the directory name (cellSaveData.cpp:543), so
 * a save placed under the wrong name is not an error the user ever sees -- the game simply reports
 * no save data and offers to start fresh, which looks like the import silently did nothing. The
 * core writes SAVEDATA_DIRECTORY into every PARAM.SFO it saves (cellSaveData.cpp:1695) and reads it
 * back to populate dirName (cellSaveData.cpp:248), so the correct name travels inside the save.
 *
 * Follows [TexturePackInstaller] for staging and commit: everything lands in a scratch directory on
 * the same filesystem, is validated there, and only then is renamed into place. Nothing half-formed
 * is ever visible under `savedata/`, and a failure part-way cannot destroy a save the user already
 * had. The pieces here that are not savedata-specific -- [stageArchive], [stageTree], [commit] --
 * are what the frame-generation plugin installer needs too (pick a file, verify it, atomically
 * place it somewhere the app owns); they are written to be lifted rather than reimplemented.
 */
object SaveDataImporter {
    private const val TAG = "SaveDataImporter"

    /** Guards against a decompression bomb: real save data is kilobytes to a few megabytes. */
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    sealed interface Progress {
        data object Scanning : Progress
        data class Copying(val done: Int, val total: Int) : Progress
        data object Installing : Progress
    }

    /** One save found in the source, named as it will actually be written. */
    data class Imported(val dirName: String, val title: String?, val replaced: Boolean)

    data class Outcome(
        val ok: Boolean,
        val saves: List<Imported> = emptyList(),
        val error: String? = null,
        /** Names of imported saves whose data files are still encrypted. See [looksEncrypted]. */
        val encrypted: List<String> = emptyList(),
        /** `dirName to fileNames` for loose files merged into an existing save. */
        val merged: List<Pair<String, List<String>>> = emptyList(),
    )

    // ---- entry points ---------------------------------------------------------------------

    /**
     * Imports from a `.zip` picked with `ActivityResultContracts.OpenDocument`.
     *
     * Blocking; call from a background dispatcher.
     */
    fun importArchive(
        context: Context,
        uri: Uri,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Outcome = runImport(onProgress) { staging ->
        // Opened separately rather than with `?.use { } ?: openFailed`. These stages answer null
        // to mean "no problem, carry on", so folding them together made the SUCCESS path -- a null
        // from stageArchive -- select the elvis branch and report every single archive import as
        // "could not open the selected file", while the staged files were discarded unread.
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@runImport Outcome(false, error = "Could not open the selected file")

        val archiveResult = input.use { stageArchive(it, staging, onProgress, isCancelled) }

        // A decrypted save file is what a save decrypter hands back -- one loose file, not an
        // archive -- and ZipInputStream reports no entries for it, which surfaced as "Archive was
        // empty": true, useless, and about the wrong thing.
        //
        // Keyed on nothing having been STAGED, not on what stageArchive returned. It returns an
        // Outcome for the empty case rather than null, so testing the return value here meant this
        // never ran and the same misleading error came back unchanged.
        if (!isCancelled() && staging.walkTopDown().none { it.isFile }) {
            val name = displayName(context, uri)
                ?: return@runImport Outcome(false, error = "Could not read the selected file's name")

            val safe = sanitizedDirName(name)
                ?: return@runImport Outcome(false, error = "Unsupported file name: $name")

            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    File(staging, safe).outputStream().use { out -> raw.copyTo(out) }
                } != null
            }.getOrDefault(false)

            // Carry on to discover/merge rather than reporting the archive failure, which was
            // only ever a statement about the file not being a zip.
            if (copied) return@runImport null
        }

        archiveResult
    }

    /** The picked document's file name, which for a loose file is the only clue to its identity. */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
    }.getOrNull()

    /**
     * Imports from a folder picked with `ActivityResultContracts.OpenDocumentTree`.
     *
     * Accepts either the save folder itself or a parent holding several, since a user who
     * downloaded a pack of rosters has no reason to know which of those they picked.
     */
    fun importFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Outcome = runImport(onProgress) { staging ->
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@runImport Outcome(false, error = "Could not open the selected folder")
        stageTree(context, root, staging, onProgress, isCancelled)
    }

    // ---- shared driver --------------------------------------------------------------------

    /**
     * Stages, validates, then commits. [stage] does only the copy; it must not touch the live
     * savedata directory, which is what makes a cancelled or failed import a no-op.
     */
    private fun runImport(
        onProgress: (Progress) -> Unit,
        stage: (File) -> Outcome?,
    ): Outcome {
        val savedataRoot = savedataRoot() ?: return Outcome(
            false,
            error = "No user profile yet — boot a game once, then import.",
        )

        // A sibling of the destination, so the commit below is a rename and not a copy across
        // filesystems. Leading dot keeps it out of the way of anything that lists savedata/.
        val staging = File(savedataRoot, ".import-tmp")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return Outcome(false, error = "Could not create a staging folder")
        }

        try {
            onProgress(Progress.Scanning)
            stage(staging)?.let { return it }

            val found = discover(staging)
            if (found.isEmpty()) {
                // No PARAM.SFO anywhere, so this is not a save in its own right. It may still be
                // the useful half of one: a decrypter returns the data files on their own, and
                // those have to go back into a save that already exists. See [resolveMergeTarget]
                // for why the target is only ever accepted when it is unambiguous.
                val loose = staging.walkTopDown().filter { it.isFile && !isJunk(it.name) }.toList()
                if (loose.isNotEmpty()) {
                    return mergeLoose(savedataRoot, loose)
                }

                return Outcome(
                    false,
                    error = "No save data found. A save is a folder containing PARAM.SFO.",
                )
            }

            onProgress(Progress.Installing)
            val imported = mutableListOf<Imported>()
            val encryptedSaves = mutableListOf<String>()
            for ((staged, dirName) in found) {
                val dest = File(savedataRoot, dirName)
                val replaced = dest.exists()
                if (!commit(staged, dest)) {
                    return Outcome(
                        false,
                        imported,
                        "Could not write $dirName into the savedata folder",
                    )
                }
                imported += Imported(
                    dirName = dirName,
                    title = ParamSfo.string(File(dest, "PARAM.SFO"), "TITLE"),
                    replaced = replaced,
                )
                if (looksEncrypted(dest)) encryptedSaves += dirName
            }
            return Outcome(true, imported, encrypted = encryptedSaves)
        } catch (e: Exception) {
            Log.w(TAG, "import failed: ${e.message}")
            return Outcome(false, error = e.message ?: "Import failed")
        } finally {
            staging.deleteRecursively()
        }
    }

    // ---- discovery and naming --------------------------------------------------------------

    /**
     * Finds every staged directory holding a PARAM.SFO, paired with the name it must be written
     * under. That is the same test the core uses to decide a directory is a save at all: it loads
     * `<entry>/PARAM.SFO` per directory when enumerating (cellSaveData.cpp:240).
     *
     * Searched recursively because the source shape is not ours to dictate -- a user may hand us
     * the save, its parent, or an archive that wraps both in a download folder.
     */
    /**
     * Whether a save still carries the copy protection a real PS3 applies, which makes it
     * unusable here.
     *
     * We store secure files as plaintext -- the id is recorded in the PSF and nothing is ever
     * encrypted -- so a save lifted straight off a console hands the game 175KB of ciphertext
     * where its own structure should be. The game follows a pointer out of it and the PPU
     * thread segfaults inside recompiled code, which reads as an emulator crash and gives the
     * user nothing to act on. It is worth a message instead.
     *
     * Two signals, both required, because either alone is wrong:
     *  - PARAM.PFD exists. We never write one, so it came from a console. But a properly
     *    decrypted save keeps its PFD too, which is why this cannot decide on its own.
     *  - A data file still reads as ciphertext. Save data is structured -- headers, padding,
     *    counters, runs of zeroes -- while encrypted data is close to uniform. A 4KB window
     *    with nearly every byte value present and no repeated run is not plaintext.
     */
    private fun looksEncrypted(dir: File): Boolean {
        if (!File(dir, "PARAM.PFD").isFile) return false

        val candidates = dir.listFiles()
            ?.filter { it.isFile && it.length() >= 4096 && it.name.uppercase() !in SKIP_ENTROPY }
            ?: return false

        return candidates.any { file ->
            runCatching {
                val buf = ByteArray(4096)
                val read = file.inputStream().use { it.read(buf) }
                if (read < 4096) return@runCatching false

                val seen = BooleanArray(256)
                var distinct = 0
                var longestRun = 0
                var run = 0

                for (i in 0 until read) {
                    val b = buf[i].toInt() and 0xff
                    if (!seen[b]) { seen[b] = true; distinct++ }
                    run = if (i > 0 && buf[i] == buf[i - 1]) run + 1 else 1
                    if (run > longestRun) longestRun = run
                }

                distinct >= 250 && longestRun < 5
            }.getOrDefault(false)
        }
    }

    /** Media and metadata: compressed images are high-entropy too and would false-positive. */
    private val SKIP_ENTROPY = setOf("ICON0.PNG", "ICON1.PAM", "PIC1.PNG", "SND0.AT3", "PARAM.SFO", "PARAM.PFD")

    /**
     * Merges loose save files into a save that already exists.
     *
     * A save decrypter hands back the data files alone -- no PARAM.SFO, no folder we can name --
     * so there is nothing in them that says which save they belong to. Guessing wrong overwrites
     * the wrong game's progress, so this only proceeds when the answer is forced, by one of two
     * signals, and refuses with an explanation otherwise:
     *
     *  - The staged folder's own name matches an existing save directory. This is the deliberate
     *    path: put the files in a folder named after the save and import that folder.
     *  - Failing that, exactly ONE existing save already contains a file with that name. One match
     *    is an answer; two is a coin flip, and a coin flip here costs a save file.
     *
     * The replaced file is kept alongside as `.old-import` until the whole merge succeeds, so a
     * failure part way through does not leave the save short of a file it had before.
     */
    private fun mergeLoose(savedataRoot: File, loose: List<File>): Outcome {
        val saves = savedataRoot.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "PARAM.SFO").isFile }

        if (saves.isEmpty()) {
            return Outcome(false, error = "There are no saves here yet to merge these files into.")
        }

        val byFolderName = loose.mapNotNull { it.parentFile?.name }.distinct()
            .firstNotNullOfOrNull { n -> saves.firstOrNull { it.name.equals(n, true) } }

        val target = byFolderName ?: run {
            val names = loose.map { it.name }
            val hits = saves.filter { save ->
                names.any { n -> File(save, n).isFile }
            }
            when (hits.size) {
                1 -> hits.first()
                0 -> return Outcome(
                    false,
                    error = "No existing save contains ${loose.joinToString(", ") { it.name }}. " +
                        "Put the file in a folder named after the save and import that folder.",
                )
                else -> return Outcome(
                    false,
                    error = "${hits.size} saves contain a file by that name, so this would be a " +
                        "guess. Put the file in a folder named after the save you want " +
                        "(${hits.joinToString(", ") { it.name }}) and import that folder.",
                )
            }
        }

        val backups = mutableListOf<Pair<File, File>>()
        val names = mutableListOf<String>()

        for (file in loose) {
            val dest = File(target, file.name)
            if (dest.exists()) {
                val backup = File(target, "${file.name}.old-import")
                backup.delete()
                if (!dest.renameTo(backup)) {
                    backups.forEach { (b, d) -> b.renameTo(d) }
                    return Outcome(false, error = "Could not replace ${file.name} in ${target.name}")
                }
                backups += backup to dest
            }
            if (!file.renameTo(dest)) {
                backups.forEach { (b, d) -> b.renameTo(d) }
                return Outcome(false, error = "Could not write ${file.name} into ${target.name}")
            }
            names += file.name
        }

        backups.forEach { (b, _) -> b.delete() }
        return Outcome(true, merged = listOf(target.name to names))
    }

    private fun discover(staging: File): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            if (File(dir, "PARAM.SFO").isFile) {
                resolveDirName(dir)?.let { out += dir to it }
                // A save has no nested saves; stopping also stops a PARAM.SFO in a subfolder from
                // being imported as a second, bogus save.
                return
            }
            dir.listFiles().orEmpty().filter { it.isDirectory }.forEach { walk(it, depth + 1) }
        }
        walk(staging, 0)
        return out
    }

    /**
     * The directory name to write this save under: PARAM.SFO's SAVEDATA_DIRECTORY when it has one,
     * else the folder's own name.
     *
     * Preferring the SFO is what makes a renamed download still work. Names look like
     * `<SERIAL><TAG>` (`BLUS30760SM2011_SAVE`), which is not something a user can be expected to
     * reconstruct after their file manager or a zip tool has flattened or renamed a folder.
     *
     * The fallback is not a formality: a save copied by hand out of another emulator may have had
     * its SFO rewritten. Both paths go through [sanitizedDirName] because a value read out of a
     * file is untrusted input no matter which file it came from.
     */
    private fun resolveDirName(dir: File): String? {
        val fromSfo = ParamSfo.string(File(dir, "PARAM.SFO"), "SAVEDATA_DIRECTORY")
        return sanitizedDirName(fromSfo) ?: sanitizedDirName(dir.name)
    }

    /**
     * A directory name safe to join onto the savedata root.
     *
     * Rejects rather than repairs. A name carrying a separator or a `..` is not a name we can
     * correct into the user's intent, and quietly writing it somewhere else would be worse than
     * saying so: this is the value that decides where the copy lands.
     */
    private fun sanitizedDirName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.length > 64) return null
        if (name.any { it == '/' || it == '\\' || it < ' ' }) return null
        // Deliberately NOT narrowed to a character set. This name comes from the game's own
        // SAVEDATA_DIRECTORY, and rejecting one for holding a character we did not anticipate
        // would refuse a good save with "no save data found" -- the silent-looking failure this
        // whole class exists to avoid. Only separators and control characters can redirect a
        // write, and a leading dot would make a directory no file browser shows.
        if (name.startsWith('.')) return null
        return name
    }

    // ---- staging: archive ------------------------------------------------------------------

    /**
     * Extracts [input] into [staging].
     *
     * Entry paths are rebuilt from sanitized components rather than used as given. A crafted
     * `../../lib/foo.so` would otherwise be written wherever the app can reach, and the app can
     * reach its own native library directory -- so this is a code-execution path, not a tidiness
     * one. Any entry containing a `..` component fails the whole archive: an archive carrying one
     * is not an archive to half-extract and then trust.
     */
    private fun stageArchive(
        input: InputStream,
        staging: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome? {
        val stagingCanonical = staging.canonicalPath + File.separator
        var entries = 0
        var totalBytes = 0L
        var written = 0

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                if (isCancelled()) return Outcome(false, error = null)
                val entry: ZipEntry = zip.nextEntry ?: break
                try {
                    if (++entries > MAX_ENTRIES) {
                        return Outcome(false, error = "Archive has too many files")
                    }
                    if (entry.isDirectory) continue

                    val rel = safeRelativePath(entry.name)
                        ?: return Outcome(false, error = "Archive contains an unsafe path")
                    if (rel.isEmpty() || isJunk(entry.name)) continue

                    val out = File(staging, rel)
                    // Belt and braces. safeRelativePath already dropped every `..`, so reaching
                    // this is a bug in it rather than a crafted archive -- but the cost of the
                    // check is nothing and the cost of being wrong is arbitrary file write.
                    if (!out.canonicalPath.startsWith(stagingCanonical)) {
                        Log.w(TAG, "zip-slip entry rejected: ${entry.name}")
                        return Outcome(false, error = "Archive contains an unsafe path")
                    }
                    out.parentFile?.mkdirs()

                    var entryBytes = 0L
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) return Outcome(false, error = null)
                            val n = zip.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            totalBytes += n
                            // Sizes are checked while writing, not from the entry header: the
                            // header is attacker-controlled and can simply lie.
                            if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                return Outcome(false, error = "Archive is unexpectedly large")
                            }
                            fos.write(buf, 0, n)
                        }
                    }
                    written++
                    if (written % 16 == 0) onProgress(Progress.Copying(written, 0))
                } finally {
                    zip.closeEntry()
                }
            }
        }

        if (written == 0) return Outcome(false, error = "Archive was empty")
        return null
    }

    /**
     * Rebuilds an entry path from its own components, keeping only the basename of each.
     *
     * Every component is reduced to its last path-ish token and anything left that is `.` or `..`
     * is dropped, so no combination of separators, doubled slashes or backslashes can climb out of
     * the staging directory. Depth is capped because the structure a save needs is at most a
     * folder and its files.
     */
    private fun safeRelativePath(name: String): String? {
        val norm = name.replace('\\', '/')
        val parts = norm.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        if (parts.isEmpty()) return ""
        // Drop leading wrappers so a "Download/BLUS30760SAVE/PARAM.SFO" still stages usefully;
        // discover() walks anyway, so this only keeps the tree shallow.
        // Control characters only. Stripping spaces here silently renamed the user's folders,
        // and a wrapper like "All Pro Football 2K8 roster/" is a completely ordinary thing for
        // a file manager to produce.
        val kept = parts.takeLast(3).map { part -> part.filterNot { c -> c < ' ' } }
        if (kept.any { it.isEmpty() }) return null
        return kept.joinToString("/")
    }

    // ---- staging: folder -------------------------------------------------------------------

    /** Copies a picked SAF tree into [staging], mirroring its structure. */
    private fun stageTree(
        context: Context,
        root: DocumentFile,
        staging: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome? {
        var copied = 0
        var totalBytes = 0L

        fun walk(node: DocumentFile, dest: File, depth: Int): Outcome? {
            if (depth > 6) return null
            for (child in node.listFiles()) {
                if (isCancelled()) return Outcome(false, error = null)
                val rawName = child.name ?: continue
                // The picker gives us display names, which are not path components; a name with a
                // separator in it is malformed and is dropped rather than joined.
                if (rawName.any { it == '/' || it == '\\' || it < ' ' }) continue
                if (rawName == "." || rawName == "..") continue
                if (isJunk(rawName)) continue

                if (child.isDirectory) {
                    val sub = File(dest, rawName)
                    if (!sub.exists() && !sub.mkdirs()) continue
                    walk(child, sub, depth + 1)?.let { return it }
                    continue
                }

                val out = File(dest, rawName)
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            totalBytes += n
                            if (totalBytes > MAX_TOTAL_BYTES) return@use
                            fos.write(buf, 0, n)
                        }
                    }
                }
                if (totalBytes > MAX_TOTAL_BYTES) {
                    return Outcome(false, error = "Folder is unexpectedly large")
                }
                copied++
                if (copied % 16 == 0) onProgress(Progress.Copying(copied, 0))
            }
            return null
        }

        // The picked folder may itself be the save, so its own name has to survive into staging or
        // the dirName fallback would see the scratch directory instead.
        val rootName = root.name?.takeIf { n ->
            n.none { it == '/' || it == '\\' || it < ' ' } && n != "." && n != ".."
        }
        val base = if (rootName != null) File(staging, rootName).also { it.mkdirs() } else staging

        walk(root, base, 0)?.let { return it }
        if (copied == 0) return Outcome(false, error = "Folder contained no files")
        return null
    }

    // ---- commit ----------------------------------------------------------------------------

    /**
     * Moves [staged] to [target], keeping any existing save until the new one is in place.
     *
     * Overwriting matters more here than for a texture pack: the thing being replaced is the
     * user's own progress, and a rename that fails half way must leave what they had rather than
     * nothing at all.
     */
    private fun commit(staged: File, target: File): Boolean {
        val backup = File(target.parentFile, "${target.name}.old-import")
        backup.deleteRecursively()
        target.parentFile?.mkdirs()

        val hadPrevious = target.exists()
        if (hadPrevious && !target.renameTo(backup)) {
            Log.w(TAG, "could not move existing ${target.name} aside")
            return false
        }
        if (!staged.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            Log.w(TAG, "could not move staged ${target.name} into place")
            return false
        }
        backup.deleteRecursively()
        return true
    }

    // ---- paths -----------------------------------------------------------------------------

    /**
     * `config/dev_hdd0/home/<user>/savedata`, created if the user directory already exists.
     *
     * Prefers the logged-in user and falls back to whichever home directory is actually there,
     * matching how the trophy browser resolves the same ambiguity: getUser() reaches through JNI
     * into the core and answers null before a game has been opened, and refusing to import until
     * then would be a confusing rule to explain. Answers null only when there is no user directory
     * at all, which is a genuinely fresh install.
     */
    internal fun savedataRoot(): File? {
        val home = File(RPCSX.getHdd0Dir(), "home")
        val preferred = runCatching { RPCSX.instance.getUser() }.getOrNull()
            ?.takeIf { it.isNotBlank() }

        val user = preferred
            ?.let { File(home, it) }
            ?.takeIf { it.isDirectory }
            ?: home.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.length == 8 && it.name.all(Char::isDigit) }
                .minByOrNull { it.name }
            ?: return null

        return File(user, "savedata").also { it.mkdirs() }.takeIf { it.isDirectory }
    }

    private fun isJunk(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("__macosx/") || lower.contains("/__macosx/") ||
            lower == "__macosx" || lower.endsWith("/.ds_store") || lower == ".ds_store" ||
            lower.endsWith("thumbs.db")
    }
}

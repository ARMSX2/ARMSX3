package com.armsx2.mods

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Bringing a mod into the mod store.
 *
 * The store lives under the app's own external files directory, which Android 11 and up closes
 * to other apps: a user cannot simply open a file manager and drop a folder into it. Without an
 * import path the whole feature is reachable only over adb, so this is not a convenience --
 * it is how mods get in at all.
 *
 * Both shapes people actually have are accepted: a `.zip` off a mod site, and a folder they have
 * already unpacked. Either way the result is the same tree under
 * `<root>/mods/<serial>/<mod name>/`, which is what [ModManager] applies from.
 *
 * Staged into a temporary directory and moved into place only once the whole copy has
 * succeeded, so a cancelled or failed import cannot leave a half-written mod that the tab would
 * happily offer to enable.
 */
object ModImporter {

    private const val TAG = "ARMSX3-Mods"

    /** A mod is loose game files; anything near this count is a mistake or a hostile archive. */
    private const val MAX_ENTRIES = 20_000

    sealed interface Result {
        data class Ok(val modName: String, val fileCount: Int) : Result
        data class Failed(val reason: String) : Result
    }

    fun importZip(context: Context, serial: String, uri: Uri): Result {
        val destRoot = modsRootOrFail(serial) ?: return Result.Failed("No storage available yet")
        val rawName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Mod"
        val modName = uniqueName(destRoot, sanitize(rawName.substringBeforeLast('.')))

        val staging = File(destRoot, ".import-$modName")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingCanonical = staging.canonicalPath

        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return fail(staging, "Could not open that file")

        var written = 0
        val outcome = runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) return fail(staging, "Archive has too many files")
                    if (entry.isDirectory) continue

                    val rel = safeRelativePath(entry.name)
                        ?: return fail(staging, "Archive contains an unsafe path")
                    if (rel.isEmpty()) continue

                    val out = File(staging, rel)
                    // safeRelativePath already dropped every "..", so reaching this means a bug
                    // in it rather than a crafted archive -- but the check costs nothing and
                    // being wrong costs an arbitrary file write.
                    if (!out.canonicalPath.startsWith(stagingCanonical)) {
                        Log.w(TAG, "zip-slip entry rejected: ${entry.name}")
                        return fail(staging, "Archive contains an unsafe path")
                    }

                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zip.copyTo(fos, 64 * 1024) }
                    written++
                }
            }
        }

        if (outcome.isFailure) {
            return fail(staging, "Could not read that archive")
        }
        if (written == 0) {
            // Reached by a .7z or .rar as well as a genuinely empty zip: ZipInputStream reports
            // no entries rather than failing, so "empty" is what every unreadable archive looks
            // like from here. Name the likely cause instead of the symptom.
            return fail(staging, "Nothing could be read from that file. Only .zip archives are supported, so unpack a .7z or .rar first and import the folder.")
        }

        return commit(staging, File(destRoot, modName), modName, written)
    }

    /**
     * Extract a .pkg into the mod store instead of installing it.
     *
     * Plenty of PS3 mods ship as packages, and installing one writes its files permanently into
     * the title with no record of what it overwrote. A package's contents are already laid out
     * relative to the game, which is the same shape a loose mod has, so unpacking it here turns
     * it into something that can be switched off again.
     *
     * Copied to a real file first. The core seeks around a package while reading it and a SAF
     * descriptor is not always seekable, which the package installer handles with the same
     * fallback; going straight to a local file makes the seekable case the only case.
     */
    fun importPkg(context: Context, serial: String, uri: Uri): Result {
        val destRoot = modsRootOrFail(serial) ?: return Result.Failed("No storage available yet")
        val rawName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Mod"
        val modName = uniqueName(destRoot, sanitize(rawName.substringBeforeLast('.')))

        val staging = File(destRoot, ".import-$modName")
        staging.deleteRecursively()
        staging.mkdirs()

        val scratch = File(destRoot, ".import-$modName.pkg")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            } != null
        }.getOrDefault(false)

        if (!copied || scratch.length() == 0L) {
            scratch.delete()
            return fail(staging, "Could not read that package")
        }

        val ok = runCatching {
            val progress = ProgressRepository.create(context, "Extracting $modName", silent = true)
            android.os.ParcelFileDescriptor.open(
                scratch, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { pfd ->
                RPCSX.instance.extractPkgTo(pfd.fd, progress, staging.absolutePath)
            }
        }.getOrElse {
            Log.w(TAG, "pkg extract threw: ${it.message}")
            false
        }

        scratch.delete()

        if (!ok) return fail(staging, "That package could not be extracted")

        val written = staging.walkTopDown().count { it.isFile }
        if (written == 0) return fail(staging, "That package has no files in it")

        return commit(staging, File(destRoot, modName), modName, written)
    }

    /**
     * Import one loose file, placed at [relativePath] inside the game.
     *
     * The common shape a small mod is distributed in: a single replacement file and a readme
     * saying which folder to drop it in. There is nothing in the file itself that says where it
     * belongs, so the path has to come from the user, which is why this takes one rather than
     * guessing from the extension.
     *
     * Without this, a bare file fell through to the archive reader and came back as "that
     * archive has nothing in it", which is true and completely unhelpful.
     */
    fun importFile(context: Context, serial: String, uri: Uri, relativePath: String): Result {
        val destRoot = modsRootOrFail(serial) ?: return Result.Failed("No storage available yet")
        val rawName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Mod"

        val rel = safeRelativePath(relativePath.trim())
            ?: return Result.Failed("That path is not valid")
        if (rel.isEmpty()) return Result.Failed("Enter where the file goes inside the game")

        // A path ending in a separator names the folder, so keep the file's own name.
        val target = if (relativePath.trim().endsWith("/")) "$rel/$rawName" else rel

        val modName = uniqueName(destRoot, sanitize(rawName.substringBeforeLast('.')))
        val staging = File(destRoot, ".import-$modName")
        staging.deleteRecursively()
        staging.mkdirs()

        val out = File(staging, target)
        if (!out.canonicalPath.startsWith(staging.canonicalPath)) {
            return fail(staging, "That path is not valid")
        }

        val copied = runCatching {
            out.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { fos -> input.copyTo(fos, 64 * 1024) }
            } != null
        }.getOrDefault(false)

        if (!copied || out.length() == 0L) return fail(staging, "Could not read that file")
        return commit(staging, File(destRoot, modName), modName, 1)
    }

    fun importFolder(context: Context, serial: String, treeUri: Uri): Result {
        val destRoot = modsRootOrFail(serial) ?: return Result.Failed("No storage available yet")
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result.Failed("Could not open that folder")
        val modName = uniqueName(destRoot, sanitize(tree.name ?: "Mod"))

        val staging = File(destRoot, ".import-$modName")
        staging.deleteRecursively()
        staging.mkdirs()

        val written = runCatching { copyTree(context, tree, staging, 0) }.getOrElse {
            Log.w(TAG, "folder import failed: ${it.message}")
            return fail(staging, "Could not copy that folder")
        }

        if (written == 0) return fail(staging, "That folder has no files in it")
        return commit(staging, File(destRoot, modName), modName, written)
    }

    // ---- internals ---------------------------------------------------------------------

    private fun copyTree(context: Context, dir: DocumentFile, dest: File, depth: Int): Int {
        // A mod is a shallow tree of game files. This only guards against a pathological or
        // looping provider, not against anything legitimate.
        if (depth > 24) return 0
        var written = 0
        dir.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name == "." || name == "..") return@forEach
            if (child.isDirectory) {
                val sub = File(dest, name)
                sub.mkdirs()
                written += copyTree(context, child, sub, depth + 1)
            } else {
                val out = File(dest, name)
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(out).use { fos -> input.copyTo(fos, 64 * 1024) }
                    written++
                }
            }
        }
        return written
    }

    private fun commit(staging: File, target: File, modName: String, written: Int): Result {
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            // Same filesystem, so a rename should never fail -- fall back rather than lose the
            // import, and only give up if the copy fails too.
            val copied = runCatching { staging.copyRecursively(target, overwrite = true) }.getOrDefault(false)
            staging.deleteRecursively()
            if (!copied) return Result.Failed("Could not place the mod")
        }
        Log.i(TAG, "imported '$modName': $written file(s)")
        return Result.Ok(modName, written)
    }

    private fun fail(staging: File, reason: String): Result.Failed {
        staging.deleteRecursively()
        return Result.Failed(reason)
    }

    private fun modsRootOrFail(serial: String): File? =
        ModManager.modsRoot(serial)?.also { it.mkdirs() }?.takeIf { it.isDirectory }

    /** Strip anything that would make a directory name awkward or escape the store. */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim(' ', '.')
            .take(64)
            .ifEmpty { "Mod" }

    /** Importing the same mod twice should not silently replace the first one. */
    private fun uniqueName(root: File, base: String): String {
        if (!File(root, base).exists()) return base
        var n = 2
        while (File(root, "$base ($n)").exists()) n++
        return "$base ($n)"
    }

    /**
     * A zip entry's path, reduced to something safe to join onto the staging directory:
     * every `..` and absolute root removed, separators normalised.
     */
    private fun safeRelativePath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}

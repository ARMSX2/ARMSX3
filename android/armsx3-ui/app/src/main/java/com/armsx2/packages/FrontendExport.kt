package com.armsx2.packages

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.Ps3Sfo
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.rpcsx.RPCSX
import org.json.JSONObject
import java.io.File

/**
 * A `.ps3` file for every installed game, in a folder the user picks, for frontends (issue #157).
 *
 * A disc image or a game folder is something a frontend such as ES-DE finds by scanning a ROM
 * folder. A game installed from a .pkg is not: it lives inside our own storage, under
 * dev_hdd0/game. A frontend can already START one by title id (`--es title_id BLUS12345`,
 * issue #13); what it had no way to learn is which ids exist. This writes that list in the shape
 * frontends already read for Vita3K's .psvita files: one file per game, named after the game,
 * holding nothing but its title id.
 *
 * The folder is the user's and can hold anything, so nothing in it is overwritten, and the only
 * files ever deleted are ones on this export's record (the manifest) that still hold the id they
 * were recorded with. A file goes on the record when this writes it, or when it already holds
 * the id of a game being exported (an earlier export, or one renamed or made by hand), which is
 * taken over rather than listed twice. A name that something else uses gets the title id added.
 */
object FrontendExport {

    private const val TAG = "ARMSX3-FrontendExport"
    private const val KEY_FOLDER = "frontendExport.folder"
    private const val KEY_MANIFEST = "frontendExport.manifest"
    private const val KEY_FORMAT = "frontendExport.format"
    private const val EXTENSION = ".ps3"

    // octet-stream, not text/plain: a provider appends the extension it associates with the
    // type, so text/plain would turn "Killzone 3.ps3" into "Killzone 3.ps3.txt".
    private const val MIME = "application/octet-stream"

    /** The shape the library accepts as a title id (GameLibraryRepository.titleIdLine). */
    private val titleIdShape = Regex("^[A-Z]{4}[0-9]{5}$")
    private val tagPrefix = Regex("^\\[title_id]", RegexOption.IGNORE_CASE)

    /**
     * What a file holds. One file cannot hold both: ES-DE's %INJECT% joins every line of the file
     * and pastes the result into the launch command, so anything after the bare id, a second
     * line included, becomes part of the id it passes on. Hence a choice, the bare id first.
     */
    enum class Format(val key: String, val sample: String) {
        /** The .psvita / .steam convention, and what ES-DE's %INJECT% needs. */
        TitleId("id", "BLUS12345"),

        /** The tagged line issue #157 gave as its example, for a launcher that reads that. */
        Tagged("tagged", "[title_id] BLUS12345"),
    }

    /** The format files are written in. Changing it rewrites the files already there. */
    val format = mutableStateOf(Format.TitleId)

    enum class Problem {
        /** The folder cannot be listed or written: a revoked grant, or a card that is out. */
        Folder,

        /** What is installed cannot be read, so nothing was changed. */
        Storage,
    }

    /** The chosen folder as a tree uri, or null while exporting is off. */
    val folder = mutableStateOf<String?>(null)

    /** How many files the folder holds for installed games, as of the last sync. */
    val exported = mutableIntStateOf(0)

    /** Why the last sync could not finish, or null when it did. */
    val problem = mutableStateOf<Problem?>(null)

    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private data class Title(val id: String, val name: String)

    private data class Child(val uri: Uri, val name: String, val isDirectory: Boolean)

    private sealed interface Placement {
        data class Done(val name: String) : Placement

        /** Both names it could have belong to something else in the folder. */
        data object Taken : Placement

        /** The folder refused the write. */
        data object Failed : Placement
    }

    /** Read the saved folder once. Everything else here assumes it has been. */
    @Synchronized
    fun load() {
        if (loaded) return
        val saved = MainActivityRuntime.prefs.getString(KEY_FOLDER, null)
        val savedFormat = MainActivityRuntime.prefs.getString(KEY_FORMAT, null)
        folder.value = saved
        format.value = Format.entries.firstOrNull { it.key == savedFormat } ?: Format.TitleId
        exported.intValue = saved?.let { readManifest(it).size } ?: 0
        loaded = true
    }

    /** Write files as [value] from now on, and bring the ones already written into line. */
    fun setFormat(context: Context, value: Format) {
        load()
        if (value == format.value) return
        MainActivityRuntime.prefs.edit { putString(KEY_FORMAT, value.key) }
        format.value = value
        if (folder.value != null) requestSync(context, force = true)
    }

    /**
     * The title id in [text], written either way a file can hold it ("BLUS12345" or
     * "[title_id] BLUS12345"), or null.
     *
     * Also what a launch by title id runs its extra through, so a frontend that passes a file's
     * whole content on starts the game whichever format the file is in.
     */
    fun titleIdIn(text: String): String? {
        val bare = text.trim().replace(tagPrefix, "").trim().replace("-", "").uppercase()
        return bare.takeIf { titleIdShape.matches(it) }
    }

    private fun contentFor(id: String): String =
        if (format.value == Format.Tagged) "[title_id] $id" else id

    /**
     * Start exporting into [uri], or stop when it is null.
     *
     * Another folder starts a new record, and the old folder's files stay where they are: they
     * are the user's to delete, and removing them unasked would empty a launcher's list the
     * moment the folder is changed. Stopping keeps them for the same reason. Picking the same
     * folder again is how a user asks for it to be checked, so that syncs in full.
     */
    fun setFolder(context: Context, uri: Uri?) {
        load()
        val value = uri?.toString()
        if (value != folder.value) {
            MainActivityRuntime.prefs.edit {
                if (value == null) remove(KEY_FOLDER) else putString(KEY_FOLDER, value)
                remove(KEY_MANIFEST)
            }
            folder.value = value
            exported.intValue = 0
            problem.value = null
        }
        if (value != null) requestSync(context, force = true)
    }

    /**
     * Bring the folder in line with what is installed, off the calling thread.
     *
     * Called after every install and uninstall and after each library scan. Without [force] it
     * returns before touching the folder when the installed set is the one already exported, so
     * the scan hook costs a directory listing and nothing more. [force] also recreates files the
     * user deleted by hand, which an ordinary sync does not notice.
     */
    fun requestSync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            runCatching { sync(app, force) }
                .onFailure { Log.w(TAG, "export failed: ${it.message}", it) }
        }
    }

    private suspend fun sync(context: Context, force: Boolean) = lock.withLock {
        load()
        val target = folder.value ?: return@withLock
        val manifest = readManifest(target)

        val titles = installedTitles(context, hasRecord = manifest.isNotEmpty())
        if (titles == null) {
            report(target, manifest.size, Problem.Storage)
            return@withLock
        }
        val wanted = titles.map { it.id }.toSet()
        if (!force && manifest.values.toSet() == wanted) {
            report(target, manifest.size, null)
            return@withLock
        }

        val tree = Uri.parse(target)
        val children = listChildren(context, tree)
        if (children == null) {
            report(target, manifest.size, Problem.Folder)
            return@withLock
        }

        // The record first. A file that has gone, or no longer holds the id written into it, is
        // not ours any more: forget it and leave it alone. One whose game is no longer
        // installed is deleted, and stays on the record if that fails so the next sync retries.
        var failed = false
        val records = manifest.entries.iterator()
        while (records.hasNext()) {
            val (name, id) = records.next()
            val child = children[name.lowercase()]
            if (child == null) {
                records.remove()
                continue
            }
            if (id in wanted) {
                // A forced sync also brings each file to the chosen format, which is how a
                // change of format reaches the files already written.
                if (force) {
                    val content = readContent(context, child.uri)
                    if (content?.let { titleIdIn(it) } != id) {
                        records.remove()
                    } else if (content != contentFor(id) && !write(context, child.uri, contentFor(id), truncate = true)) {
                        failed = true
                    }
                }
                continue
            }
            if (readContent(context, child.uri)?.let { titleIdIn(it) } != id) {
                records.remove()
                continue
            }
            if (delete(context, child.uri)) {
                records.remove()
                children.remove(name.lowercase())
                Log.i(TAG, "removed '$name' ($id is no longer installed)")
            }
        }

        val owned = manifest.values.toHashSet()
        val missing = titles.filter { it.id !in owned }
        val holding: Map<String, Child> = if (missing.isEmpty()) emptyMap() else
            filesHolding(context, children, manifest, missing.map { it.id }.toSet())

        for (title in missing) {
            val found = holding[title.id]
            if (found != null) {
                manifest[found.name] = title.id
                Log.i(TAG, "took over '${found.name}' for ${title.id}")
                if (readContent(context, found.uri) != contentFor(title.id) &&
                    !write(context, found.uri, contentFor(title.id), truncate = true)
                ) {
                    failed = true
                }
                continue
            }
            when (val placed = place(context, tree, children, manifest, title)) {
                is Placement.Done -> manifest[placed.name] = title.id
                Placement.Taken -> Unit
                Placement.Failed -> failed = true
            }
        }

        writeManifest(target, manifest)
        report(target, manifest.size, if (failed) Problem.Folder else null)
    }

    /**
     * The .ps3 files not on the record that already hold one of [ids], by that id.
     *
     * An earlier export to this folder, a file of ours the user renamed, or one made by hand
     * before this existed. Writing a second file for the same game would list it twice. Only
     * asked when something is missing, since every read is a round trip to the provider.
     */
    private fun filesHolding(
        context: Context,
        children: Map<String, Child>,
        manifest: Map<String, String>,
        ids: Set<String>,
    ): Map<String, Child> {
        val recorded = manifest.keys.mapTo(HashSet()) { it.lowercase() }
        val out = HashMap<String, Child>()
        for ((key, child) in children) {
            if (child.isDirectory || !key.endsWith(EXTENSION) || key in recorded) continue
            val id = readContent(context, child.uri)?.let { titleIdIn(it) } ?: continue
            if (id in ids) out.putIfAbsent(id, child)
        }
        return out
    }

    /**
     * Write [title]'s file under the first name that is free.
     *
     * The game's own name first, then with its title id added, which is what keeps two regions
     * of one game installed side by side apart, and a folder-format copy of the same game (a
     * directory named "Killzone 3.ps3") clear of the file for the installed one.
     */
    private fun place(
        context: Context,
        tree: Uri,
        children: MutableMap<String, Child>,
        manifest: Map<String, String>,
        title: Title,
    ): Placement {
        val base = fileName(title.name).ifEmpty { title.id }
        for (name in listOf(base + EXTENSION, "$base [${title.id}]$EXTENSION")) {
            if (name.lowercase() in children) continue
            if (manifest.keys.any { it.equals(name, ignoreCase = true) }) continue
            val created = create(context, tree, name, title.id) ?: return Placement.Failed
            children[created.name.lowercase()] = created
            Log.i(TAG, "wrote '${created.name}' for ${title.id}")
            return Placement.Done(created.name)
        }
        Log.w(TAG, "no free name for ${title.id} ('${title.name}')")
        return Placement.Taken
    }

    /**
     * Every game in dev_hdd0/game, or null when what is installed cannot be known.
     *
     * Null is not "nothing". Taking a directory that cannot be read as "no games" would delete
     * every file in the folder the moment an SD card is out. The one exception is a directory
     * that does not exist while nothing is on record, which is someone who has never installed
     * a package: there is nothing to delete, so that reads as zero rather than as a problem.
     *
     * The library's test for an installed title (GameLibraryRepository.isPs3GameFolder), so
     * every id written is one a launch by title id can find: a PARAM.SFO beside USRDIR, and not
     * game data (CATEGORY GD), which is a disc game's update and cannot boot on its own.
     */
    private fun installedTitles(context: Context, hasRecord: Boolean): List<Title>? {
        val root = root(context) ?: return null
        val entries = runCatching { File(root, "config/dev_hdd0/game").listFiles() }.getOrNull()
            ?: return if (hasRecord) null else emptyList()
        return entries.mapNotNull { titleOf(it) }.distinctBy { it.id }.sortedBy { it.id }
    }

    /**
     * The install folder of the game with title id [id], by the same test the export uses, or
     * null. For a launch by title id that the library cache cannot answer yet: a game's file is
     * written the moment its package installs, and the cache only learns of the game at the next
     * library scan, so a frontend can be holding an id the cache has never seen.
     *
     * Checked against the shape of a title id before it goes anywhere near a path, since it
     * arrives in an intent from another app.
     */
    fun installedDir(context: Context, id: String): File? {
        val wanted = titleIdIn(id) ?: return null
        val games = File(root(context) ?: return null, "config/dev_hdd0/game")
        File(games, wanted).takeIf { titleOf(it)?.id == wanted }?.let { return it }
        // A folder named for something other than its PARAM.SFO's id: rare, but the file holds
        // the SFO's id, so that is the one to look for.
        return runCatching { games.listFiles() }.getOrNull()?.firstOrNull { titleOf(it)?.id == wanted }
    }

    private fun titleOf(dir: File): Title? {
        if (!dir.isDirectory) return null
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val sfo = entries.firstOrNull { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
            ?: return null
        if (entries.none { it.isDirectory && it.name.equals("USRDIR", ignoreCase = true) }) return null
        val fields = Ps3Sfo.read(sfo)
        if (fields["CATEGORY"]?.trim().equals("GD", ignoreCase = true)) return null
        // The PARAM.SFO's id is the one the library files the game under. The folder name is
        // only the fallback for an SFO that cannot be read, and is the same id for a .pkg.
        val id = sequenceOf(fields["TITLE_ID"], dir.name)
            .mapNotNull { it?.trim()?.replace("-", "")?.uppercase() }
            .firstOrNull { titleIdShape.matches(it) }
            ?: return null
        return Title(id, fields["TITLE"].orEmpty())
    }

    /**
     * The directory holding dev_hdd0, when it can be trusted to describe the user's games.
     *
     * A data folder the user chose, and only that one. When it is unavailable (an SD card that
     * is out) the core falls back to the app folder for the session (assetCopyRoot), and what is
     * installed there says nothing about the games in the folder that is missing. The choice is
     * read from its preference rather than only its in-memory copy, so a sync that runs before
     * the activity has loaded it cannot take "not loaded yet" for "the default folder".
     */
    private fun root(context: Context): File? {
        if (MainActivityRuntime.prefs.getString("systemDir", null) != null) {
            return MainActivityRuntime.systemDirPosix()?.let(::File)?.takeIf { it.isDirectory }
        }
        return RPCSX.rootDirectory.takeIf { it.isNotBlank() }?.let(::File)
            ?: context.getExternalFilesDir(null)
    }

    /**
     * [title] as a file name that any folder a frontend reads from will accept.
     *
     * FAT and exFAT, which is what an SD card is, refuse \ / : * ? " < > | and control
     * characters, and PS3 titles carry line breaks (two-line names) and trademark signs. A colon
     * becomes " - ", the usual spelling in ROM sets, so "Killzone 3: Multiplayer" is listed the
     * way a scraper expects to find it.
     */
    internal fun fileName(title: String): String =
        title
            .replace(Regex("\\s*:\\s*"), " - ")
            .replace(Regex("[\\\\/*?\"<>|™®©]"), "")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim()
            .trimStart('.', '-', ' ')
            .take(120)
            .trimEnd('.', ' ')

    private fun report(target: String, count: Int, issue: Problem?) {
        // A sync for a folder that has since been changed describes nothing on screen.
        if (folder.value != target) return
        exported.intValue = count
        problem.value = issue
    }

    /** Files written into [target], name to title id. Empty when the record is another folder's. */
    private fun readManifest(target: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = MainActivityRuntime.prefs.getString(KEY_MANIFEST, null) ?: return out
        runCatching {
            val json = JSONObject(raw)
            if (json.optString("folder") != target) return out
            val files = json.optJSONObject("files") ?: return out
            files.keys().forEach { name -> out[name] = files.getString(name) }
        }
        return out
    }

    private fun writeManifest(target: String, files: Map<String, String>) {
        // The folder is part of the record so a sync still running when the folder changes
        // cannot hand its files to the new one.
        if (folder.value != target) return
        val json = JSONObject().put("folder", target).put("files", JSONObject(files))
        MainActivityRuntime.prefs.edit { putString(KEY_MANIFEST, json.toString()) }
    }

    /**
     * The folder's entries by lower-cased name, from one query, or null when it cannot be read.
     *
     * One query rather than DocumentFile.listFiles(), which asks the provider again for every
     * entry's name and type: a ROM folder picked as the target can hold thousands of files. The
     * names are lower-cased because an SD card is case-insensitive, so "KILLZONE 3.ps3" is the
     * name "Killzone 3.ps3" would collide with.
     */
    private fun listChildren(context: Context, tree: Uri): MutableMap<String, Child>? = runCatching {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(query, columns, null, null, null)?.use { cursor ->
            val out = HashMap<String, Child>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out[name.lowercase()] = Child(uri, name, cursor.getString(2) == Document.MIME_TYPE_DIR)
            }
            out
        }
    }.getOrNull()

    private fun create(context: Context, tree: Uri, name: String, id: String): Child? {
        val resolver = context.contentResolver
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return null
        val uri = runCatching { DocumentsContract.createDocument(resolver, parent, MIME, name) }
            .onFailure { Log.w(TAG, "could not create '$name': ${it.message}") }
            .getOrNull() ?: return null
        if (!write(context, uri, contentFor(id), truncate = false)) {
            delete(context, uri)
            return null
        }
        // The provider has the last word on the name ("Game (1).ps3" when one appeared in the
        // meantime), so the record holds what it used.
        return Child(uri, displayName(context, uri) ?: name, isDirectory = false)
    }

    /**
     * Put [text] in the file at [uri]. [truncate] for one that already has content: "w" alone is
     * allowed to leave the old bytes past the new end in place.
     *
     * No trailing newline: a frontend passes the content on as it is, and ES-DE's %INJECT% would
     * carry a newline into the launch command.
     */
    private fun write(context: Context, uri: Uri, text: String, truncate: Boolean): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, if (truncate) "wt" else "w")
            ?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    /** The file's text, trimmed, reading no more than a file of ours could hold. */
    private fun readContent(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            String(buffer, 0, total, Charsets.UTF_8).trim()
        }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}

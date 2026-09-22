package com.armsx2.packages

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A `.ps3` file for every installed game, in folders the user picks, for frontends (issue #157).
 *
 * A disc image or a game folder is something a frontend such as ES-DE finds by scanning a ROM
 * folder. A game installed from a .pkg is not: it lives inside our own storage, under
 * dev_hdd0/game. A frontend can already START one by title id (`--es title_id BLUS12345`,
 * issue #13); what it had no way to learn is which ids exist. This writes that list in the shape
 * frontends already read for Vita3K's .psvita files: one file per game, named after the game,
 * holding nothing but its title id.
 *
 * More than one folder, because people keep more than one ROM folder, and each has its own file
 * format, because two folders can be read by two different frontends.
 *
 * A folder is the user's and can hold anything, so nothing in it is overwritten, and the only
 * files ever deleted are ones on that folder's record (its manifest) that still hold the id they
 * were recorded with. A file goes on the record when this writes it, or when it already holds
 * the id of a game being exported (an earlier export, or one renamed or made by hand), which is
 * taken over rather than listed twice. A name that something else uses gets the title id added.
 */
object FrontendExport {

    private const val TAG = "ARMSX3-FrontendExport"
    private const val KEY_TARGETS = "frontendExport.targets"
    private const val KEY_MANIFESTS = "frontendExport.manifests"

    // The first build of this kept a single folder under its own keys; see load().
    private const val OLD_FOLDER = "frontendExport.folder"
    private const val OLD_FORMAT = "frontendExport.format"
    private const val OLD_MANIFEST = "frontendExport.manifest"

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

    /** One folder to export into: a tree uri, and what its files hold. */
    data class Target(val uri: String, val format: Format)

    /** The folders, in the order they were added. Empty while exporting is off. */
    val targets = mutableStateOf<List<Target>>(emptyList())

    /** How many files each folder holds for installed games, as of the last sync, by uri. */
    val exported = mutableStateMapOf<String, Int>()

    /** Folders the last sync could not list or write (a revoked grant, a card that is out). */
    val folderProblem = mutableStateMapOf<String, Boolean>()

    /** What is installed could not be read, so the last sync changed nothing in any folder. */
    val storageProblem = mutableStateOf(false)

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

    /** Read the saved folders once. Everything else here assumes it has been. */
    @Synchronized
    fun load() {
        if (loaded) return
        val prefs = MainActivityRuntime.prefs
        var list = readTargets()
        // The first build kept one folder under keys of its own. Fold it into the list. Its
        // files need no record carried over: the first sync takes them over by the id they hold.
        prefs.getString(OLD_FOLDER, null)?.let { old ->
            val oldFormat = Format.entries.firstOrNull { it.key == prefs.getString(OLD_FORMAT, null) }
            if (list.none { it.uri == old }) list = list + Target(old, oldFormat ?: Format.TitleId)
            writeTargets(list)
            prefs.edit {
                remove(OLD_FOLDER)
                remove(OLD_FORMAT)
                remove(OLD_MANIFEST)
            }
        }
        targets.value = list
        val manifests = readManifests()
        list.forEach { exported[it.uri] = manifests[it.uri]?.size ?: 0 }
        loaded = true
    }

    /**
     * Start exporting into [uri] as well. Picking a folder that is already listed is how a user
     * asks for it to be checked again, so either way it syncs in full.
     */
    fun addFolder(context: Context, uri: Uri) {
        load()
        val value = uri.toString()
        if (targets.value.none { it.uri == value }) {
            // A record left from an earlier time in the list would describe files this has not
            // looked at since; start clean and let the sync take over what is still there.
            val manifests = readManifests()
            manifests.remove(value)
            writeManifests(manifests)
            setTargets(targets.value + Target(value, Format.TitleId))
            exported[value] = 0
        }
        requestSync(context, force = true)
    }

    /**
     * Stop exporting into [uri]. Its files stay where they are: they are the user's to delete,
     * and removing them unasked would empty a launcher's list the moment a folder is dropped.
     */
    fun removeFolder(uri: String) {
        load()
        setTargets(targets.value.filterNot { it.uri == uri })
        val manifests = readManifests()
        manifests.remove(uri)
        writeManifests(manifests)
        exported.remove(uri)
        folderProblem.remove(uri)
    }

    /** Write [uri]'s files as [format] from now on, and bring the ones already there into line. */
    fun setFormat(context: Context, uri: String, format: Format) {
        load()
        val current = targets.value
        if (current.none { it.uri == uri && it.format != format }) return
        setTargets(current.map { if (it.uri == uri) it.copy(format = format) else it })
        requestSync(context, force = true)
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

    private fun contentFor(id: String, format: Format): String =
        if (format == Format.Tagged) "[title_id] $id" else id

    /**
     * Bring every folder in line with what is installed, off the calling thread.
     *
     * Called after every install and uninstall and after each library scan. Without [force] a
     * folder is left untouched when the installed set is the one already exported into it, so
     * the scan hook costs a directory listing and nothing more. [force] also recreates files the
     * user deleted by hand, which an ordinary sync does not notice, and rewrites files whose
     * content is not in their folder's format.
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
        val list = targets.value
        if (list.isEmpty()) return@withLock
        val manifests = readManifests()

        val titles = installedTitles(context, hasRecord = list.any { manifests[it.uri].orEmpty().isNotEmpty() })
        storageProblem.value = titles == null
        if (titles == null) return@withLock

        for (target in list) {
            val manifest = manifests.getOrPut(target.uri) { LinkedHashMap() }
            val ok = syncFolder(context, target, manifest, titles, force)
            // A folder removed while this ran describes nothing on screen any more.
            if (targets.value.none { it.uri == target.uri }) continue
            exported[target.uri] = manifest.size
            if (ok) folderProblem.remove(target.uri) else folderProblem[target.uri] = true
        }
        writeManifests(manifests)
    }

    /** One folder's share of [sync]. False when the folder could not be listed or written. */
    private fun syncFolder(
        context: Context,
        target: Target,
        manifest: LinkedHashMap<String, String>,
        titles: List<Title>,
        force: Boolean,
    ): Boolean {
        val wanted = titles.map { it.id }.toSet()
        if (!force && manifest.values.toSet() == wanted) return true

        val tree = Uri.parse(target.uri)
        val children = listChildren(context, tree) ?: return false

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
                // A forced sync also brings each file to the folder's format, which is how a
                // change of format reaches the files already written.
                if (force) {
                    val content = readContent(context, child.uri)
                    if (content?.let { titleIdIn(it) } != id) {
                        records.remove()
                    } else if (content != contentFor(id, target.format) &&
                        !write(context, child.uri, contentFor(id, target.format), truncate = true)
                    ) {
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
            val content = contentFor(title.id, target.format)
            val found = holding[title.id]
            if (found != null) {
                manifest[found.name] = title.id
                Log.i(TAG, "took over '${found.name}' for ${title.id}")
                if (readContent(context, found.uri) != content &&
                    !write(context, found.uri, content, truncate = true)
                ) {
                    failed = true
                }
                continue
            }
            when (val placed = place(context, tree, children, manifest, title, content)) {
                is Placement.Done -> manifest[placed.name] = title.id
                Placement.Taken -> Unit
                Placement.Failed -> failed = true
            }
        }
        return !failed
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
        content: String,
    ): Placement {
        val base = fileName(title.name).ifEmpty { title.id }
        for (name in listOf(base + EXTENSION, "$base [${title.id}]$EXTENSION")) {
            if (name.lowercase() in children) continue
            if (manifest.keys.any { it.equals(name, ignoreCase = true) }) continue
            val created = create(context, tree, name, content) ?: return Placement.Failed
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
     * every file in every folder the moment an SD card is out. The one exception is a directory
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

    private fun setTargets(list: List<Target>) {
        targets.value = list
        writeTargets(list)
    }

    private fun readTargets(): List<Target> = runCatching {
        val array = JSONArray(MainActivityRuntime.prefs.getString(KEY_TARGETS, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Target(uri, Format.entries.firstOrNull { it.key == item.optString("format") } ?: Format.TitleId)
        }.distinctBy { it.uri }
    }.getOrDefault(emptyList())

    private fun writeTargets(list: List<Target>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("uri", it.uri).put("format", it.format.key)) }
        MainActivityRuntime.prefs.edit { putString(KEY_TARGETS, array.toString()) }
    }

    /** Every folder's record, uri to (file name to title id). */
    private fun readManifests(): HashMap<String, LinkedHashMap<String, String>> {
        val out = HashMap<String, LinkedHashMap<String, String>>()
        val raw = MainActivityRuntime.prefs.getString(KEY_MANIFESTS, null) ?: return out
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { uri ->
                val files = json.optJSONObject(uri) ?: return@forEach
                val record = LinkedHashMap<String, String>()
                files.keys().forEach { name -> record[name] = files.getString(name) }
                out[uri] = record
            }
        }
        return out
    }

    /**
     * Save the records of the folders still listed. Filtered here rather than by the callers,
     * so a sync still running when a folder is removed cannot write that folder's record back.
     */
    private fun writeManifests(manifests: Map<String, Map<String, String>>) {
        val listed = targets.value.mapTo(HashSet()) { it.uri }
        val json = JSONObject()
        manifests.forEach { (uri, files) -> if (uri in listed) json.put(uri, JSONObject(files)) }
        MainActivityRuntime.prefs.edit { putString(KEY_MANIFESTS, json.toString()) }
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

    private fun create(context: Context, tree: Uri, name: String, content: String): Child? {
        val resolver = context.contentResolver
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return null
        val uri = runCatching { DocumentsContract.createDocument(resolver, parent, MIME, name) }
            .onFailure { Log.w(TAG, "could not create '$name': ${it.message}") }
            .getOrNull() ?: return null
        if (!write(context, uri, content, truncate = false)) {
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

package com.armsx2.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.annotation.Keep
import androidx.core.net.toUri

/**
 * Reaching a user-picked folder from the core, on a build with no filesystem permission.
 *
 * The Play build cannot hold MANAGE_EXTERNAL_STORAGE, so the only thing a folder picker can
 * hand back is a tree URI: a grant, not a location. Everything under it is reachable through
 * ContentResolver and through nothing else, and in particular `/storage/emulated/0/Games` is
 * a real string that names a real folder the process is not allowed to open.
 *
 * That gap is what testers hit. A picked folder produced a library with no covers and games
 * that dropped back to the library on launch, because the scan and the boot were both opening
 * a path that resolves to EACCES.
 *
 * ## What this is
 *
 * The Kotlin half of a virtual filesystem device. The core asks for paths shaped like
 *
 *     <device prefix>/t0/PS3_GAME/USRDIR/EBOOT.BIN
 *
 * and the functions below answer them against the tree URI registered as `t0`. See
 * saf_device.cpp for the other half, and fs::device_base for the interface the core already
 * had waiting for exactly this.
 *
 * ## Why a document id cannot be built, only found
 *
 * SAF has no paths. A document is named by an opaque id, and a child's id is not derivable
 * from its parent's, so the only way down a path is to list each directory and match the
 * next component by display name. One query per component, each one a Binder round trip into
 * whichever app provides the storage.
 *
 * Done literally that is slow enough to notice, because a boot touches hundreds of paths and
 * most of them share a parent. So a listing keeps every sibling it saw, not just the child
 * that was asked for: the query that resolves the first file in USRDIR resolves the rest of
 * USRDIR too, and the walk collapses to roughly one query per directory per session.
 */
object ContentUri {

    private const val TAG = "ARMSX3-SAF"

    /** Where the tree key registry lives. Keys are handed to the core inside paths, and the
     *  library cache keeps those paths, so they have to mean the same thing next launch. */
    private const val PREFS = "armsx3_saf_trees"

    private val lock = Any()

    private var appContext: Context? = null

    /** Resolved nodes, keyed by `<tree key>/<relative path>`. */
    private val entries = HashMap<String, Entry>()

    /** Directories whose children are all present in [entries]. */
    private val listed = HashSet<String>()

    /** Keys known to name nothing. The core probes for optional files constantly, and a miss
     *  costs the same query as a hit. Dropped wholesale whenever anything is written. */
    private val missing = HashSet<String>()

    private val treeByKey = HashMap<String, String>()

    private class Entry(val docId: String, val isDir: Boolean, val size: Long, val mtime: Long)

    // -----------------------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------------------

    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for ((key, value) in prefs.all) {
                (value as? String)?.let { treeByKey[key] = it }
            }
        }
    }

    /**
     * The key a picked folder is known by, taking a persistable grant on first sight.
     *
     * Stable for the life of the install: the same folder picked twice keeps its first key,
     * so a library entry recorded months ago still points at the folder it was scanned from.
     */
    fun keyForTree(treeUri: Uri): String? {
        val context = appContext ?: return null
        val uriString = treeUri.toString()

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        synchronized(lock) {
            treeByKey.entries.firstOrNull { it.value == uriString }?.let { return it.key }

            var n = 0
            while (treeByKey.containsKey("t$n")) n++
            val key = "t$n"
            treeByKey[key] = uriString
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, uriString)
                .apply()
            android.util.Log.i(TAG, "tree $key -> $uriString")
            return key
        }
    }

    fun treeForKey(key: String): String? = synchronized(lock) { treeByKey[key] }

    /**
     * The device path for a document inside one of the picked folders, or null if it is not
     * in one.
     *
     * The library scan records games by document URI, because that is what it can read covers
     * and PARAM.SFO through. The core cannot open one, so this is the translation applied on
     * the way into boot.
     *
     * A document id is opaque by contract, but every provider that backs a storage volume
     * builds it as `<volume>:<path>`, and that is the only kind of provider a games folder is
     * ever on. Where the shape does not hold there is nothing to fall back to, so the caller
     * keeps the original URI and the boot fails the way it did before.
     */
    fun devicePathForDocument(documentUri: Uri): String? {
        val context = appContext ?: return null
        val docId = runCatching {
            DocumentsContract.getDocumentId(documentUri)
        }.getOrNull() ?: return null

        synchronized(lock) {
            for ((key, treeUriString) in treeByKey) {
                val treeUri = runCatching { treeUriString.toUri() }.getOrNull() ?: continue
                val treeDocId = runCatching {
                    DocumentsContract.getTreeDocumentId(treeUri)
                }.getOrNull() ?: continue

                if (docId == treeDocId) return "$DEVICE_PREFIX/$key"
                if (!docId.startsWith("$treeDocId/")) continue

                // Confirm with the provider rather than trusting the string. Two trees can
                // share a prefix (Games and Games2 under the same volume) and the shorter
                // one would otherwise claim the other's documents.
                val treeDoc = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                }.getOrNull() ?: continue
                val child = runCatching {
                    DocumentsContract.isChildDocument(context.contentResolver, treeDoc, documentUri)
                }.getOrDefault(true)
                if (!child) continue

                return "$DEVICE_PREFIX/$key/" + docId.removePrefix("$treeDocId/")
            }
        }

        return null
    }

    /** [devicePathForDocument] for anything the launcher might be holding: a document URI
     *  becomes a device path, a plain path or an already-translated one is returned as is. */
    fun bootPathFor(raw: String): String {
        if (!raw.startsWith("content://")) return raw
        val uri = runCatching { raw.toUri() }.getOrNull() ?: return raw
        return devicePathForDocument(uri) ?: raw
    }

    /** Forget a folder the user removed. Its key is not reused, so a stale path stays dead
     *  rather than quietly resolving into somebody else's folder. */
    fun forgetTree(treeUri: Uri) {
        val context = appContext ?: return
        val uriString = treeUri.toString()
        synchronized(lock) {
            val key = treeByKey.entries.firstOrNull { it.value == uriString }?.key ?: return
            treeByKey.remove(key)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
            invalidateLocked()
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** The device path the core should be given for a picked folder. */
    fun devicePathFor(treeUri: Uri): String? = keyForTree(treeUri)?.let { "$DEVICE_PREFIX/$it" }

    /**
     * Must match saf_device.h. Positional, not cosmetic: fs::get_virtual_device requires an
     * underscore at index 29 and the registered name to start after the first underscore at
     * or past index 7, which is why the iso overlay device is spelled the way it is too.
     */
    const val DEVICE_PREFIX = "/vfsv0_virtual_saf_storage_fs_dev"

    fun isDevicePath(path: String?): Boolean = path != null && path.startsWith(DEVICE_PREFIX)

    // -----------------------------------------------------------------------------------
    // Called from saf_device.cpp
    // -----------------------------------------------------------------------------------

    /** [-1] missing, else [kind, size, mtime] with kind 0 file / 1 directory. */
    @Keep
    @JvmStatic
    fun statPath(path: String): LongArray = synchronized(lock) {
        val entry = resolveLocked(path) ?: return longArrayOf(-1)
        longArrayOf(if (entry.isDir) 1 else 0, entry.size, entry.mtime)
    }

    /**
     * One record per child, as `<D|F>\t<size>\t<mtime>\t<name>`.
     *
     * Packed into strings because the alternative is a JNI call per field per child, and a
     * PS3 game directory can hold thousands of files. The name is last so that a display
     * name containing a tab cannot shift the fields in front of it.
     */
    @Keep
    @JvmStatic
    fun listDir(path: String): Array<String> = synchronized(lock) {
        val entry = resolveLocked(path) ?: return emptyArray()
        if (!entry.isDir) return emptyArray()
        val key = normalize(path)
        val children = childrenLocked(key, entry) ?: return emptyArray()
        children.map { (name, child) ->
            "${if (child.isDir) "D" else "F"}\t${child.size}\t${child.mtime}\t$name"
        }.toTypedArray()
    }

    /**
     * A detached file descriptor, or -1.
     *
     * Detached because ownership crosses into the core, which closes it through the ordinary
     * unix_file path. A ParcelFileDescriptor that went out of scope here would close the fd
     * out from under a running game.
     */
    @Keep
    @JvmStatic
    fun openFd(path: String, mode: String, create: Boolean): Int {
        val context = appContext ?: return -1
        synchronized(lock) {
            var entry = resolveLocked(path)

            if (entry == null) {
                if (!create) return -1
                entry = createFileLocked(path) ?: return -1
            }
            if (entry.isDir) return -1

            val uri = documentUriLocked(path, entry) ?: return -1
            return runCatching {
                context.contentResolver.openFileDescriptor(uri, mode)?.detachFd() ?: -1
            }.getOrElse { failure ->
                android.util.Log.w(TAG, "open '$path' ($mode): ${failure.message}")
                -1
            }
        }
    }

    @Keep
    @JvmStatic
    fun createDir(path: String): Boolean = synchronized(lock) {
        if (resolveLocked(path) != null) return false
        val created = createNodeLocked(path, Document.MIME_TYPE_DIR) != null
        if (created) invalidateLocked()
        created
    }

    @Keep
    @JvmStatic
    fun deletePath(path: String): Boolean {
        val context = appContext ?: return false
        synchronized(lock) {
            val entry = resolveLocked(path) ?: return false
            val uri = documentUriLocked(path, entry) ?: return false
            val ok = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            if (ok) invalidateLocked()
            return ok
        }
    }

    @Keep
    @JvmStatic
    fun renamePath(from: String, to: String): Boolean {
        val context = appContext ?: return false
        synchronized(lock) {
            val entry = resolveLocked(from) ?: return false
            val uri = documentUriLocked(from, entry) ?: return false

            val fromParent = parentOf(normalize(from))
            val toParent = parentOf(normalize(to))
            val toName = normalize(to).substringAfterLast('/')

            // SAF splits what POSIX does in one step, and a move across directories has to be
            // asked for separately from a change of name.
            var current = uri
            if (fromParent != toParent) {
                val source = resolveLocked(fromParent) ?: return false
                val target = resolveLocked(toParent) ?: return false
                val sourceUri = documentUriLocked(fromParent, source) ?: return false
                val targetUri = documentUriLocked(toParent, target) ?: return false
                current = runCatching {
                    DocumentsContract.moveDocument(
                        context.contentResolver, current, sourceUri, targetUri,
                    )
                }.getOrNull() ?: return false
            }

            if (normalize(from).substringAfterLast('/') != toName) {
                val renamed = runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, current, toName)
                }.getOrNull() ?: return false
                if (renamed.toString().isEmpty()) return false
            }

            invalidateLocked()
            return true
        }
    }

    /** Drop everything cached. Cheap, and correct in the one case that matters: something
     *  outside this process changed the folder while a game was not looking at it. */
    fun invalidate() = synchronized(lock) { invalidateLocked() }

    // -----------------------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------------------

    private fun invalidateLocked() {
        entries.clear()
        listed.clear()
        missing.clear()
    }

    private fun normalize(path: String): String =
        path.removePrefix(DEVICE_PREFIX).trim('/')

    private fun parentOf(normalized: String): String =
        normalized.substringBeforeLast('/', "")

    private fun treeUriLocked(key: String): Uri? =
        treeByKey[key]?.let { runCatching { it.toUri() }.getOrNull() }

    /**
     * Walk a path to the node it names, listing each directory on the way down.
     *
     * The first component is the tree key and is resolved from the registry rather than by
     * query, since the root of a tree is the one document whose id SAF does hand over.
     */
    private fun resolveLocked(path: String): Entry? {
        val key = normalize(path)
        if (key.isEmpty()) return null
        entries[key]?.let { return it }
        if (key in missing) return null

        val treeKey = key.substringBefore('/')
        val treeUri = treeUriLocked(treeKey) ?: return null

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val root = Entry(rootId, isDir = true, size = 0, mtime = 0)
        entries[treeKey] = root
        if (key == treeKey) return root

        val parent = parentOf(key)
        val parentEntry = resolveLocked(parent) ?: return null
        if (!parentEntry.isDir) return null

        if (parent !in listed) childrenLocked(parent, parentEntry)

        val found = entries[key]
        if (found == null) missing.add(key)
        return found
    }

    /**
     * Every child of an already-resolved directory, cached on the way past.
     *
     * This is the whole performance story. Resolving one file caches its siblings, so the
     * second through thousandth lookup in the same directory cost nothing.
     */
    private fun childrenLocked(dirKey: String, dir: Entry): List<Pair<String, Entry>>? {
        val context = appContext ?: return null
        val treeUri = treeUriLocked(dirKey.substringBefore('/')) ?: return null

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.docId)
        }.getOrNull() ?: return null

        val projection = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        val result = ArrayList<Pair<String, Entry>>()
        val queried = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)
                ?.use { cursor -> readChildren(cursor, dirKey, result) }
            true
        }.getOrElse { failure ->
            android.util.Log.w(TAG, "list '$dirKey': ${failure.message}")
            false
        }

        if (!queried) return null
        listed.add(dirKey)
        return result
    }

    private fun readChildren(
        cursor: Cursor,
        dirKey: String,
        into: MutableList<Pair<String, Entry>>,
    ) {
        while (cursor.moveToNext()) {
            val name = cursor.getString(0) ?: continue
            val docId = cursor.getString(1) ?: continue
            val isDir = Document.MIME_TYPE_DIR == cursor.getString(2)
            val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
            // SAF reports milliseconds; stat_t is in seconds.
            val mtime = if (cursor.isNull(4)) 0L else cursor.getLong(4) / 1000L
            val entry = Entry(docId, isDir, size, mtime)
            entries["$dirKey/$name"] = entry
            missing.remove("$dirKey/$name")
            into.add(name to entry)
        }
    }

    private fun documentUriLocked(path: String, entry: Entry): Uri? {
        val treeUri = treeUriLocked(normalize(path).substringBefore('/')) ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
        }.getOrNull()
    }

    private fun createFileLocked(path: String): Entry? {
        // A PS3 file has no meaningful MIME type, and claiming one invites the provider to
        // append a matching extension to the name.
        val created = createNodeLocked(path, "application/octet-stream")
        if (created != null) missing.remove(normalize(path))
        return created
    }

    private fun createNodeLocked(path: String, mimeType: String): Entry? {
        val context = appContext ?: return null
        val key = normalize(path)
        val parent = parentOf(key)
        val name = key.substringAfterLast('/')
        if (parent.isEmpty() || name.isEmpty()) return null

        val parentEntry = resolveLocked(parent) ?: return null
        val parentUri = documentUriLocked(parent, parentEntry) ?: return null

        val createdUri = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
        }.getOrNull() ?: return null

        val docId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull()
            ?: return null

        // Some providers quietly rename on collision, so the node that came back is not
        // necessarily at the path that was asked for. Re-listing the parent is the only way
        // to learn what it actually called it.
        listed.remove(parent)
        val entry = Entry(docId, mimeType == Document.MIME_TYPE_DIR, 0, 0)
        entries[key] = entry
        return entry
    }
}

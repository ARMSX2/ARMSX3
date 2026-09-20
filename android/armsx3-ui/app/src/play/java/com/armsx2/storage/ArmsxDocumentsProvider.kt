package com.armsx2.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.webkit.MimeTypeMap
import com.armsx2.R
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import java.io.FileNotFoundException

/**
 * ARMSX3's own data folder, published to the system file picker.
 *
 * ## Why this exists
 *
 * The emulator core opens games by filesystem path. Under scoped storage the only places it can
 * read by path without a permission are the app's own directories, so on the Play build that is
 * where games have to live. The app already offers both of them at setup: internal, or the SD
 * card's `Android/data/<pkg>/files`, which is app-specific and raw-writable with no permission.
 *
 * The problem with that answer on its own is that Android 11 stopped letting file managers browse
 * `Android/data` at all, so the folder games must go in became one the user cannot reach. This
 * closes that: the app publishes its own storage as a root, and ARMSX3 appears in Files like any
 * other location. The restriction does not apply to a provider serving its own package's data.
 *
 * So the shape is the opposite of the obvious one. Rather than teaching the emulator to read the
 * user's storage through SAF, which would put a JNI round trip on every file operation in a
 * filesystem RPCS3 hammers, the app hands its storage to the user through the picker they already
 * have, and the core keeps opening plain paths.
 *
 * ## What the root points at
 *
 * Whichever data folder is actually in use, internal or SD card, resolved per query rather than
 * cached. The folder moves when the user changes it in setup, and a stale root would quietly
 * serve files from the volume they stopped using.
 *
 * Document ids are paths RELATIVE to that root, for the same reason: an absolute id would break
 * the moment the data folder moved, and every id the picker had handed out would dangle.
 */
class ArmsxDocumentsProvider : android.provider.DocumentsProvider() {

    private companion object {
        const val ROOT_ID = "armsx3"

        val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    override fun onCreate(): Boolean = true

    // ---- root -------------------------------------------------------------------------

    /**
     * The data folder currently in use, or null before setup has chosen one.
     *
     * Falls back to the app's primary external files dir rather than returning null when the
     * runtime has not initialised: the provider can be queried by the Files app while ARMSX3 is
     * not running at all, which is exactly when someone is dropping games in.
     */
    private fun rootDir(): File? {
        val configured = runCatching { MainActivityRuntime.systemDirPosix() }.getOrNull()
        if (!configured.isNullOrBlank()) {
            val dir = File(configured)
            if (dir.isDirectory) return dir
        }
        return runCatching { context?.getExternalFilesDir(null) }.getOrNull()?.takeIf { it.isDirectory }
    }

    /**
     * The file a document id names, or null when the id escapes the root.
     *
     * Ids arrive from outside the app, so ".." is rejected on the resolved path rather than
     * textually: a provider that can be talked into serving an arbitrary file is a provider that
     * hands out the whole device.
     */
    private fun fileFor(documentId: String): File? {
        val root = rootDir() ?: return null
        val rootPath = root.canonicalFile.path
        val target = if (documentId.isEmpty()) root else File(root, documentId)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val path = canonical.path
        if (path != rootPath && !path.startsWith("$rootPath/")) return null
        return canonical
    }

    private fun idFor(file: File): String {
        val root = rootDir() ?: return ""
        val rootPath = root.canonicalFile.path
        val path = runCatching { file.canonicalFile.path }.getOrDefault(file.path)
        return path.removePrefix(rootPath).trimStart('/')
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS)
        val root = rootDir() ?: return cursor

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, "")
            add(Root.COLUMN_TITLE, "ARMSX3")
            // The path is the summary because a user dropping a 40 GB game in wants to know
            // WHICH volume they are writing to, and internal and SD card look identical here.
            add(Root.COLUMN_SUMMARY, root.absolutePath)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, runCatching { root.usableSpace }.getOrDefault(0L))
        }
        return cursor
    }

    // ---- documents --------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        val file = fileFor(documentId) ?: throw FileNotFoundException("no such document")
        addRow(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException("no such folder")
        parent.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { addRow(cursor, it) }
        return cursor
    }

    private fun addRow(cursor: MatrixCursor, file: File) {
        val isDir = file.isDirectory
        var flags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idFor(file))
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileFor(documentId) ?: throw FileNotFoundException("no such document")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    // ---- writes -----------------------------------------------------------------------
    //
    // The point of the provider is that people can put games IN, so creating, deleting, renaming
    // and moving all have to work. Without them the root is read-only and solves nothing.

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException("no such folder")
        val target = uniqueChild(parent, displayName)

        val ok = runCatching {
            if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        }.getOrDefault(false)

        if (!ok) throw FileNotFoundException("could not create ${target.name}")
        return idFor(target)
    }

    /** Never silently overwrite: a dropped file that lands on an existing one is data loss. */
    private fun uniqueChild(parent: File, displayName: String): File {
        val candidate = File(parent, displayName)
        if (!candidate.exists()) return candidate

        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val name = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            val next = File(parent, name)
            if (!next.exists()) return next
            n++
        }
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId) ?: throw FileNotFoundException("no such document")
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!ok) throw FileNotFoundException("could not delete ${file.name}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId) ?: throw FileNotFoundException("no such document")
        val parent = file.parentFile ?: throw FileNotFoundException("no parent")
        val target = uniqueChild(parent, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("could not rename ${file.name}")
        return idFor(target)
    }

    /**
     * Moving WITHIN this root is a rename, which is what makes reorganising a 40 GB library
     * bearable: no second copy, no waiting. A move from another provider is still a copy and a
     * delete, because that is how Android does cross-provider transfers, and nothing here can
     * change it.
     */
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = fileFor(sourceDocumentId) ?: throw FileNotFoundException("no such document")
        val targetParent = fileFor(targetParentDocumentId)
            ?: throw FileNotFoundException("no such folder")
        val target = uniqueChild(targetParent, source.name)
        if (!source.renameTo(target)) throw FileNotFoundException("could not move ${source.name}")
        return idFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId) ?: return false
        val child = fileFor(documentId) ?: return false
        return child.path.startsWith(parent.path.trimEnd('/') + "/")
    }
}

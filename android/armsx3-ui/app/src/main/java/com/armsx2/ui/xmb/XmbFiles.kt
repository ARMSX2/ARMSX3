package com.armsx2.ui.xmb

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/**
 * Storage as XMB lists, the way the console lists what is on a USB drive: a folder opens as a list
 * of its folders and then the files of the kinds asked for, each picked with Cross.
 */
object XmbFiles {
    /** Each storage the app can read by path (internal, an SD card, a USB drive) under the name Android gives it. */
    fun roots(context: Context): List<Pair<String, File>> = runCatching {
        context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
            val dir = volume.directory?.takeIf { it.canRead() } ?: return@mapNotNull null
            volume.getDescription(context) to dir
        }
    }.getOrDefault(emptyList())

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    /**
     * [dir] as an XMB list: its folders, then its files ending in one of [extensions] (lower case).
     * [onPick] is Cross on a file. With [onPickAll], a folder holding more than one of [together]
     * (a package split in parts, say) leads with an item that takes them all at once, titled by
     * [allTitle] from their count.
     */
    fun folder(
        key: String,
        title: String,
        dir: File,
        icon: XmbIcon,
        folderIcon: XmbIcon,
        extensions: Set<String>,
        fileIcon: (File) -> XmbIcon,
        sub: String? = null,
        together: Set<String> = emptySet(),
        allTitle: ((Int) -> String)? = null,
        onPickAll: ((List<File>) -> Unit)? = null,
        onPick: (File) -> Unit,
    ): XmbNode.Folder = XmbNode.Folder(key, title, icon, sub) {
        val entries = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty().filterNot { it.name.startsWith(".") }
        val dirs = entries.filter { it.isDirectory }.sortedWith(compareBy(com.armsx2.ui.packages.NaturalOrder) { it.name })
        val files = entries.filter { it.isFile && it.extension.lowercase() in extensions }.sortedWith(compareBy(com.armsx2.ui.packages.NaturalOrder) { it.name })
        buildList {
            val group = files.filter { it.extension.lowercase() in together }
            if (onPickAll != null && allTitle != null && group.size > 1) {
                add(XmbNode.Action("$key/*", allTitle(group.size), fileIcon(group.first())) { onPickAll(group) })
            }
            files.forEach { f ->
                add(XmbNode.Action("$key/${f.name}", f.name, fileIcon(f), size(f.length())) { onPick(f) })
            }
            dirs.forEach { d ->
                add(folder("$key/${d.name}", d.name, d, folderIcon, folderIcon, extensions, fileIcon, null, together, allTitle, onPickAll, onPick))
            }
        }
    }
}

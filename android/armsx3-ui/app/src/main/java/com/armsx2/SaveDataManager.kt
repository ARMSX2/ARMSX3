package com.armsx2

import java.io.File

/**
 * Lists and deletes PS3 save data.
 *
 * Exists because nothing else can. Android 11 stopped third-party file managers writing into
 * Android/data, and `adb shell` cannot remove a directory there either -- `rm` on the files
 * succeeds while `rmdir` returns EPERM, and `run-as` is refused for a release build. So a save
 * folder that needs to go could only be removed by reinstalling the app, which takes everything
 * else with it. This process owns those files, so it is the only one that can.
 *
 * The case that prompted it: a half-removed save left a directory with no PARAM.SFO in it, and
 * Sonic '06 hung on cellSaveDataFixedLoad scanning past it -- a black screen with no error. A
 * malformed save is not hypothetical, and there was no way to clear one from inside the app.
 */
object SaveDataManager {

    /**
     * One save folder. [malformed] means PARAM.SFO is missing or unreadable -- the folder is
     * still listed, deliberately, because those are exactly the ones a user needs to remove and
     * the ones every other screen hides.
     */
    data class Entry(
        val dir: File,
        val dirName: String,
        val title: String?,
        val subtitle: String?,
        val bytes: Long,
        val modified: Long,
        val malformed: Boolean,
    ) {
        /** Best available label: the save's own title, else the folder name. */
        val label: String get() = title?.takeIf { it.isNotBlank() } ?: dirName
    }

    fun list(): List<Entry> {
        val root = runCatching { SaveDataImporter.savedataRoot() }.getOrNull() ?: return emptyList()

        return root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { dir ->
                val psf = runCatching { Ps3Sfo.read(File(dir, "PARAM.SFO")) }.getOrDefault(emptyMap())
                Entry(
                    dir = dir,
                    dirName = dir.name,
                    title = psf["TITLE"],
                    subtitle = psf["SUB_TITLE"],
                    bytes = runCatching { dir.walkBottomUp().filter(File::isFile).sumOf { it.length() } }
                        .getOrDefault(0L),
                    modified = dir.lastModified(),
                    malformed = psf["TITLE"].isNullOrBlank(),
                )
            }
            .sortedWith(compareBy({ !it.malformed }, { it.label.lowercase() }))
    }

    /** Delete one save folder. Returns true when it is gone afterwards. */
    fun delete(entry: Entry): Boolean {
        runCatching { entry.dir.deleteRecursively() }
        return !entry.dir.exists()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%d KB".format(bytes / (1L shl 10))
        else -> "$bytes B"
    }
}

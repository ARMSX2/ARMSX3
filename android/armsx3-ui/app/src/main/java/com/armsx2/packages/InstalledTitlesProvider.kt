package com.armsx2.packages

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import com.armsx2.data.library.ParamSfo
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.packages.isInstalledContent
import java.io.File

/**
 * What is installed in ARMSX3, for other apps to read (issue #188). Read only, and only once the
 * user has turned on Settings > App > Share installed games with other apps.
 *
 * A library manager that hands a package to [ExternalInstall] can check here that it landed, and
 * tell a game it downloaded apart from one that is installed. Authority: the application id plus
 * ".installed", so content://com.armsx3.installed (com.armsx3.play.installed for the Play build).
 *
 * - `titles`: one row per installed title in dev_hdd0/game. Columns: title_id (PARAM.SFO's, which
 *   for a game data install like BLUS30464_INSTALL is the game's), folder, title, category (HG for
 *   a PSN game, GD for a disc game's update or data), version (APP_VER, null when there is none).
 * - `licenses`: one row per licence in exdata. Columns: content_id, title_id, type (rap or edat),
 *   user.
 *
 * With sharing off, a query throws SecurityException naming the setting. Nothing here writes, and
 * nothing reaches files: removal is not offered, and saves, trophies and firmware are not listed.
 */
class InstalledTitlesProvider : ContentProvider() {

    companion object {
        /** In the app's own preferences file, the one MainActivityRuntime.prefs opens. */
        internal const val PREF_KEY = "external.shareInstalled"
        private const val PREFS = "ARMSX2"

        private val TITLE_COLUMNS = arrayOf("title_id", "folder", "title", "category", "version")
        private val LICENSE_COLUMNS = arrayOf("content_id", "title_id", "type", "user")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: throw IllegalStateException("not attached")
        // Read from the file rather than from ShareInstalled: this can run with ARMSX3 closed,
        // when nothing has loaded that switch.
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)) {
            throw SecurityException(
                "ARMSX3 is not sharing its installed games. Turn on Settings > App > " +
                    "Share installed games with other apps in ARMSX3.",
            )
        }
        val hdd0 = dataRoot(context)?.let { File(it, "config/dev_hdd0") }
        return when (uri.pathSegments.firstOrNull()) {
            "titles" -> cursor(TITLE_COLUMNS, projection, hdd0?.let(::titles).orEmpty())
            "licenses", "licences" -> cursor(LICENSE_COLUMNS, projection, hdd0?.let(::licenses).orEmpty())
            else -> throw IllegalArgumentException("Unknown URI $uri: use titles or licenses")
        }
    }

    override fun getType(uri: Uri): String? = when (uri.pathSegments.firstOrNull()) {
        "titles" -> "vnd.android.cursor.dir/vnd.armsx3.title"
        "licenses", "licences" -> "vnd.android.cursor.dir/vnd.armsx3.license"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    /**
     * The data folder in use, or null when the chosen one is not there (an SD card that is out):
     * what is installed is then unknown, and the internal folder would answer for the wrong one.
     *
     * From the saved choice rather than the running core: a query can start the process with
     * ARMSX3 closed.
     */
    private fun dataRoot(context: Context): File? {
        val chosen = MainActivityRuntime.savedSystemDirPosix(context) ?: return context.getExternalFilesDir(null)
        return File(chosen).takeIf { it.isDirectory }
    }

    private fun titles(hdd0: File): List<Map<String, String?>> =
        File(hdd0, "game").listFiles()
            ?.filter { it.isDirectory && isInstalledContent(it) }
            ?.sortedBy { it.name }
            ?.map { folder ->
                val sfo = folder.listFiles()?.firstOrNull { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
                fun field(key: String) = sfo?.let { ParamSfo.string(it, key) }?.trim()?.ifBlank { null }
                mapOf(
                    "title_id" to (field("TITLE_ID") ?: folder.name),
                    "folder" to folder.name,
                    // Two-line titles are stored with a line break; one line reads better in a list.
                    "title" to field("TITLE")?.replace(Regex("\\s*[\\r\\n]+\\s*"), " "),
                    "category" to field("CATEGORY"),
                    "version" to field("APP_VER"),
                )
            }
            .orEmpty()

    private fun licenses(hdd0: File): List<Map<String, String?>> =
        File(hdd0, "home").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.flatMap { user ->
                File(user, "exdata").listFiles()
                    ?.filter { it.isFile && (it.extension.equals("rap", true) || it.extension.equals("edat", true)) }
                    ?.sortedBy { it.name }
                    ?.map { file ->
                        mapOf(
                            "content_id" to file.nameWithoutExtension,
                            "title_id" to ExternalInstall.titleIdOf(file.nameWithoutExtension),
                            "type" to file.extension.lowercase(),
                            "user" to user.name,
                        )
                    }
                    .orEmpty()
            }
            .orEmpty()

    /** The columns asked for that exist, in the order asked; all of them when none were named. */
    private fun cursor(all: Array<String>, projection: Array<out String>?, rows: List<Map<String, String?>>): Cursor {
        val columns = projection?.filter { it in all }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: all
        return MatrixCursor(columns, rows.size).apply {
            rows.forEach { row -> addRow(columns.map { row[it] }) }
        }
    }
}

/** Settings > App > Share installed games with other apps: what lets [InstalledTitlesProvider] answer. Off by default. */
object ShareInstalled {

    val enabled = mutableStateOf(false)

    fun load() {
        runCatching {
            enabled.value = MainActivityRuntime.prefs.getBoolean(InstalledTitlesProvider.PREF_KEY, false)
        }
    }

    fun set(value: Boolean) {
        enabled.value = value
        runCatching {
            MainActivityRuntime.prefs.edit().putBoolean(InstalledTitlesProvider.PREF_KEY, value).apply()
        }
    }
}

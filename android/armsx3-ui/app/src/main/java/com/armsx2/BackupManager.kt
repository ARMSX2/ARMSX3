package com.armsx2

import android.content.Context
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file export/import of everything the user would lose by reinstalling: save data, trophies,
 * licences, save states, controller profiles, patches, and every preference.
 *
 * Reinstalling currently wipes all of it. ROMs and BIOS survive because they live outside the app
 * (true-SAF), but app-private data does not — and SharedPreferences are wiped even when the data
 * folder is reused, which is why settings alone are not enough to back up.
 *
 * Archives are portable between the sideload and Play builds: the preference file has a fixed name
 * ("ARMSX2", not the usual "<package>_preferences"), and the data-root entries are stored relative
 * to whatever [MainActivityRuntime.assetCopyRoot] resolves to, so moving between packages — or onto
 * a device with a different data folder — restores into the right place either way.
 *
 * Deliberately does NOT include:
 *  - `config/dev_hdd0/game` — installed titles. A PKG reinstalls; a save does not, which is the
 *    whole distinction this list is drawn on. Unbounded in size.
 *  - `config/dev_flash`, `dev_flash2`, `dev_flash3` — firmware, reinstalled from the user's PUP
 *    (193 MB on the device this was sized against).
 *  - `config/dev_hdd1` — the scratch disk titles use for their own caches (106 MB), regenerated.
 *  - `bios/` — the user's own dumps; they keep those themselves and we should not copy them around.
 *  - `cache/`, `config/config_db`, `logs/`, `pgo/`, `resources/`, `shaders/` — all regenerated on
 *    demand. Shader caches in particular are large, GPU-specific, and actively harmful to restore
 *    onto another device.
 */
object BackupManager {
    private const val MANIFEST = "armsx3-backup.json"

    // The ARMSX2-era names (issue #82). Archives made before the rename still carry them, and
    // restoring an old backup has to keep working.
    private const val LEGACY_MANIFEST = "armsx2-backup.json"
    private const val PREFS_DIR = "prefs/"
    private const val FILES_DIR = "files/"

    /**
     * Data-root entries worth preserving. Anything absent is skipped silently.
     *
     * These are RPCS3's paths, not PCSX2's. The list arrived from ARMSX2 naming sstates,
     * memcards, gamesettings, cheats and snaps -- none of which this emulator creates, so a
     * backup collected a few kilobytes of controller profiles, reported success, and left the
     * save data behind. Nothing warned, because "anything absent is skipped silently" is
     * exactly right for an optional folder and exactly wrong for a list aimed at the wrong app.
     *
     * Nested paths are fine: entries resolve against the data root and are stored relative to
     * it, so a subdirectory restores to the same place.
     */
    private val INCLUDED = listOf(
        // The irreplaceable part: save data, trophies and licences. Everything else here can
        // be rebuilt or re-downloaded; this cannot.
        "config/dev_hdd0/home",
        "config/savestates",
        "config/input_configs",
        "config/patches",
        "inputprofiles",
        "overlays",
    )
    private val INCLUDED_FILES = listOf(
        "armsx3-settings.json",
        // Still collected so a backup taken right after upgrading, before anything has been
        // saved under the new name, is not missing the user's settings.
        "armsx2-settings.json",
        "games.json", "recent_games.json", "fw.json",
        // RPCS3's own configuration. config.yml holds every emulator setting, and the per-game
        // entries in games.yml are what map a title id back to its folder.
        "config/config.yml", "config/games.yml", "config/patch_config.yml",
        "config/rpcn.yml", "config/players_history.yml",
    )

    /** Named to avoid colliding with `kotlin.Result`, which is a default import. */
    data class BackupResult(val ok: Boolean, val detail: String)

    private fun prefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs")

    // ---- export ----------------------------------------------------------------------------

    /** Blocking. Writes a backup archive to [out]. */
    fun export(context: Context, out: OutputStream): BackupResult {
        val root = File(MainActivityRuntime.assetCopyRoot(context))
        var files = 0
        var bytes = 0L
        return runCatching {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(
                    ("{\"schemaVersion\":1,\"package\":\"${context.packageName}\"," +
                        "\"versionName\":\"${appVersion(context)}\"}").toByteArray()
                )
                zip.closeEntry()

                // Preferences live outside the data root (/data/data/<pkg>/shared_prefs) and hold
                // controller mappings, touch layouts, playtime, theme and the settings tiers. They
                // are wiped on reinstall even if the data folder is reused, so they matter most.
                prefsDir(context).listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".xml") }
                    ?.forEach { f ->
                        bytes += addFile(zip, f, PREFS_DIR + f.name)
                        files++
                    }

                INCLUDED_FILES.forEach { name ->
                    val f = File(root, name)
                    if (f.isFile) { bytes += addFile(zip, f, FILES_DIR + name); files++ }
                }
                INCLUDED.forEach { dir ->
                    val d = File(root, dir)
                    if (!d.isDirectory) return@forEach
                    d.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = FILES_DIR + dir + "/" +
                            f.relativeTo(d).path.replace(File.separatorChar, '/')
                        bytes += addFile(zip, f, rel)
                        files++
                    }
                }
            }
            BackupResult(true, "$files files, ${bytes / 1024 / 1024} MB")
        }.getOrElse { BackupResult(false, it.message ?: "export failed") }
    }

    private fun addFile(zip: ZipOutputStream, f: File, entryName: String): Long {
        zip.putNextEntry(ZipEntry(entryName))
        val n = f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        return n
    }

    // ---- import ----------------------------------------------------------------------------

    /** Blocking. Restores from [input]. The app must be restarted afterwards: preferences are read
     *  once at startup, so a live process would keep serving the old values and then overwrite the
     *  restored file on its next write. */
    fun restore(context: Context, input: InputStream): BackupResult {
        val root = File(MainActivityRuntime.assetCopyRoot(context))
        val prefsDir = prefsDir(context)
        var files = 0
        return runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory || name == MANIFEST || name == LEGACY_MANIFEST) {
                        zip.closeEntry(); continue
                    }
                    val dest = when {
                        name.startsWith(PREFS_DIR) -> File(prefsDir, name.removePrefix(PREFS_DIR))
                        name.startsWith(FILES_DIR) -> File(root, name.removePrefix(FILES_DIR))
                        else -> { zip.closeEntry(); continue }
                    }
                    // Zip-slip guard: a crafted entry name like "../../x" would otherwise write
                    // anywhere the app can reach. Compare canonical paths, not the raw strings.
                    val base = if (name.startsWith(PREFS_DIR)) prefsDir else root
                    if (!dest.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
                        zip.closeEntry(); continue
                    }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    files++
                    zip.closeEntry()
                }
            }
            if (files == 0) BackupResult(false, "not a backup archive")
            else BackupResult(true, "$files files")
        }.getOrElse { BackupResult(false, it.message ?: "restore failed") }
    }

    fun suggestedName(context: Context): String =
        "ARMSX2-backup-${appVersion(context)}.zip"

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}

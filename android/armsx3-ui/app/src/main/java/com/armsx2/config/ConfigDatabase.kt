package com.armsx2.config

import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import net.rpcsx.RPCSX
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * RPCS3's per-title recommended settings, fetched from its config database.
 *
 * This is the same database the desktop build offers under "Download Config Database": a
 * curated set of settings that make specific titles work, maintained by the RPCS3 project.
 * The core already knows how to use it. Emulator::Load asks for a title's config through
 * the get_database_config callback and passes it to BootGame as db_config, which layers it
 * under the user's own settings.
 *
 * Desktop does the download and the JSON parsing in Qt, which is not part of this build, so
 * that half lives here. The response is
 *
 *     { "return_code": 0, "games": { "BLUS30443": { "config": "<yaml>" }, ... } }
 *
 * and is split into one file per title under config/config_db/, which is all the native
 * callback then has to read.
 *
 * Splitting rather than keeping the blob whole is deliberate: the callback runs on the boot
 * path, and re-parsing a database of every PS3 game to find one entry would be wasteful.
 */
object ConfigDatabase {
    private const val URL_V1 = "https://api.rpcs3.net/config/?api=v1"
    private const val KEY_UPDATED = "configDb.updatedAt"
    private const val KEY_COUNT = "configDb.titleCount"
    private const val KEY_FORMAT = "configDb.formatVersion"
    private const val KEY_ENABLED = "configDb.enabled"

    /** Bump to discard already-split configs, e.g. after changing [UNSAFE_ON_ANDROID]. */
    private const val FORMAT_VERSION = 3

    /**
     * Settings from the database that must not be applied on this port.
     *
     * The database is maintained against desktop RPCS3. A setting that is merely a tuning
     * choice there can be broken or unimplemented here, and the entries are per-title, so a
     * bad one silently breaks exactly the game it was supposed to help.
     *
     * Matched on the setting name, which is the part before the colon in the YAML. The rest
     * of the entry is kept: these files are mostly LLE library lists and buffer settings,
     * which are the reason the database is worth having.
     *
     * Keep this to settings with EVIDENCE, not suspicion. Every entry should name what broke.
     */
    private val UNSAFE_ON_ANDROID = setOf(
        // Max SPURS Threads: a workaround for LOW-CORE-COUNT DESKTOPS, which no Android device is.
        //
        // Its own tooltip says it "may improve performance ... especially on systems with limited
        // number of hardware threads". Every ARMSX3 device has 8 cores -- armv8.1-a is the compile
        // floor, so nothing weaker can run this at all -- and capping SPURS below that starves the
        // job chain instead of helping it.
        //
        // Measured on Sonic Unleashed (BLUS30244), Snapdragon 8 Elite: the database forces 3, and
        // uncapping to 6 took the problem hub area from 11.1 to 19.1 fps, with useful SPU work
        // rising 27% -> 37%. Nothing else in the entry mattered; this one value was serialising
        // the primary SPURS chain.
        //
        // 19 of the database's 2194 titles set this (12 at 3, 7 at 4), so the blast radius is
        // small and it is wrong for all of them on this platform for the same structural reason.
        // Only Unleashed was measured, and the tooltip does warn the cap is sometimes load-bearing
        // against crashes -- if a title in that list regresses, this is the entry to revisit.
        "Max SPURS Threads",

        // "Multithreaded RSX" was listed here on the belief that it froze
        // Minecraft. It did not: the freeze was Accurate SPU DMA plus Accurate Cache Line
        // Stores turning every SPU DMA into an atomic reservation store, and it reproduced
        // with Multithreaded RSX off and an empty database. Multithreaded RSX is a real
        // upstream feature backed by the RSXOffload thread, not a desktop-only path, so
        // there was never evidence against it. Use the enable toggle to A/B the database
        // rather than denying a setting on suspicion.
    )

    /**
     * ARMSX3's own per-title settings, layered on top of whatever the RPCS3 database says.
     *
     * The upstream database is maintained against desktop, where some of our problems do not
     * exist, so there is nowhere upstream to put a fix that is specific to this port. These are
     * applied whether or not the database was ever downloaded, and re-applied after every
     * refresh -- refresh() clears the directory first, so anything written once would not
     * survive it.
     *
     * Keep this SMALL and evidenced. Every entry names the issue it answers.
     */
    private val LOCAL_OVERRIDES: Map<String, String> = mapOf(
        // Tales of Symphonia Chronicles: reported running at 60fps when the game targets 30,
        // with battles playing at double speed (issue #77).
        //
        // PS3 Native is the only Frame limit mode that honours the game's own
        // cellGcmSetFlipMode(CELL_GCM_DISPLAY_VSYNC); every other mode flips immediately, so a
        // title that paces itself by flipping on alternate vblanks free-runs to the 60 cap.
        //
        // The risk if this is ever wrong for a title: PS3 Native applies NO limit when the game
        // does not request vsync, so it would run unbounded instead of capped. Only add a serial
        // here once someone has confirmed the mode helps on that game.
        "BLUS31213" to "Video:\n  Frame limit: PS3 Native\n",
        "BLES01935" to "Video:\n  Frame limit: PS3 Native\n",
    )

    /**
     * Write the local overrides, merging under any database entry for the same title.
     *
     * Ours go LAST in the file so they win: the core parses the YAML in order and a later key
     * of the same name replaces an earlier one.
     */
    fun ensureLocalOverrides() = applyLocalOverrides()

    private fun applyLocalOverrides() {
        val dir = liveDir().apply { mkdirs() }

        for ((serial, yaml) in LOCAL_OVERRIDES) {
            runCatching {
                val file = File(dir, "$serial.yml")
                val existing = if (file.isFile) file.readText() else ""
                if (existing.contains("Frame limit:")) return@runCatching
                file.writeText(if (existing.isBlank()) yaml else existing.trimEnd() + "\n" + yaml)
            }
        }
    }

    /** Drop denied settings from one title's YAML, keeping everything else intact. */
    private fun sanitise(config: String): String =
        config.lineSequence()
            .filterNot { line ->
                val name = line.substringBefore(':').trim().removePrefix("- ")
                name in UNSAFE_ON_ANDROID
            }
            .joinToString("\n")

    /**
     * Where the native side looks. get_database_config reads config/config_db/<TITLE>.yml
     * on the boot path, so this exact name is what makes the database live.
     */
    private fun liveDir(): File = File(RPCSX.rootDirectory, "config/config_db")

    /**
     * Where the split files sit while the database is switched off.
     *
     * Disabling moves them aside rather than deleting them, so a tester can flip the
     * database on and off across boots to compare without re-downloading 2000-odd titles
     * each time, which is the whole point of having the switch.
     */
    private fun parkedDir(): File = File(RPCSX.rootDirectory, "config/config_db_off")

    private fun directory(): File =
        (if (isEnabled()) liveDir() else parkedDir()).apply { mkdirs() }

    /** Whether downloaded configs are being applied at boot. */
    fun isEnabled(): Boolean = MainActivityRuntime.prefs.getBoolean(KEY_ENABLED, true)

    /**
     * Turn the database on or off, keeping whatever was downloaded.
     *
     * A single rename either exposes the files under the name the native callback reads or
     * hides them from it, so nothing has to be re-parsed and the core needs no flag of its
     * own. Takes effect on the next boot, since the config is read during Emulator::Load.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled()) return
        val from = if (enabled) parkedDir() else liveDir()
        val to = if (enabled) liveDir() else parkedDir()
        runCatching {
            if (from.isDirectory) {
                if (to.exists()) to.deleteRecursively()
                from.renameTo(to)
            }
        }
        MainActivityRuntime.prefs.edit { putBoolean(KEY_ENABLED, enabled) }

        // Ours are ARMSX3 fixes, not part of the downloaded database, so they stay live
        // either way -- the toggle above just moved them into the parked directory.
        applyLocalOverrides()
    }

    /**
     * Delete everything downloaded and go back to stock settings.
     *
     * Both directories go, so this is a true revert rather than a disable: the row returns
     * to offering a download and no title carries a database setting any more.
     */
    fun remove() {
        runCatching { liveDir().deleteRecursively() }
        runCatching { parkedDir().deleteRecursively() }
        MainActivityRuntime.prefs.edit {
            putInt(KEY_COUNT, 0)
            remove(KEY_UPDATED)
            putBoolean(KEY_ENABLED, true)
        }
    }

    /**
     * Throw away configs split by an older build.
     *
     * The files on disk are the output of [sanitise], so when the deny list changes they are
     * stale in a way that matters: a user who downloaded before a setting was found to be
     * broken would keep applying it forever, since nothing re-downloads on its own.
     *
     * Discarding rather than re-fetching, because this runs at startup and must not make a
     * network call. The row goes back to offering a download.
     */
    fun purgeIfStale() {
        if (MainActivityRuntime.prefs.getInt(KEY_FORMAT, 0) == FORMAT_VERSION) return
        if (titleCount() == 0) return

        // Both, since the parked copy is restored verbatim by the enable toggle and would
        // otherwise carry stale sanitising back in the moment someone switched it on.
        runCatching { liveDir().listFiles()?.forEach { it.delete() } }
        runCatching { parkedDir().listFiles()?.forEach { it.delete() } }
        MainActivityRuntime.prefs.edit {
            putInt(KEY_COUNT, 0)
            remove(KEY_UPDATED)
            putInt(KEY_FORMAT, FORMAT_VERSION)
        }
    }

    /** Titles currently stored, 0 when the database has never been fetched. */
    fun titleCount(): Int = MainActivityRuntime.prefs.getInt(KEY_COUNT, 0)

    /** Epoch millis of the last successful fetch, or 0. */
    fun updatedAt(): Long = MainActivityRuntime.prefs.getLong(KEY_UPDATED, 0L)

    fun hasConfigFor(serial: String?): Boolean {
        val id = serial?.takeIf { it.isNotBlank() } ?: return false
        return File(directory(), "${id.uppercase()}.yml").isFile
    }

    /**
     * Fetch and split the database. Blocking, so call it off the main thread.
     *
     * Returns the number of titles written, or -1 on failure. Existing files are replaced
     * wholesale rather than merged: the database is authoritative and a stale entry for a
     * title that has since been fixed would otherwise persist forever.
     */
    fun refresh(): Int {
        val body = runCatching {
            val connection = (URL(URL_V1).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                // The API rejects requests without one.
                setRequestProperty("User-Agent", "ARMSX3")
            }
            try {
                if (connection.responseCode !in 200..299) return -1
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return -1

        return runCatching {
            val json = JSONObject(body)
            // Negative codes are the server reporting an error rather than data:
            // -1 internal, -2 maintenance. Treat them as a failed fetch and keep
            // whatever is already on disk.
            if (json.optInt("return_code", -255) < 0) return -1

            val games = json.optJSONObject("games") ?: return -1
            val dir = directory()
            dir.listFiles()?.forEach { it.delete() }

            var written = 0
            for (serial in games.keys()) {
                val config = games.optJSONObject(serial)?.optString("config").orEmpty()
                if (config.isBlank()) continue
                val safe = sanitise(config)
                if (safe.isBlank()) continue
                runCatching {
                    File(dir, "${serial.uppercase()}.yml").writeText(safe)
                    written++
                }
            }

            // After the split, not before: refresh() clears the directory above.
            applyLocalOverrides()

            MainActivityRuntime.prefs.edit {
                putInt(KEY_COUNT, written)
                putLong(KEY_UPDATED, System.currentTimeMillis())
                putInt(KEY_FORMAT, FORMAT_VERSION)
            }
            written
        }.getOrDefault(-1)
    }
}

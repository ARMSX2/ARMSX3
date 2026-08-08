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

    /** Bump to discard already-split configs, e.g. after changing [UNSAFE_ON_ANDROID]. */
    private const val FORMAT_VERSION = 2

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
        // Minecraft's entry sets this and the game froze on load. The RSX here is
        // single threaded by design and this path is not exercised on Android.
        "Multithreaded RSX",
    )

    /** Drop denied settings from one title's YAML, keeping everything else intact. */
    private fun sanitise(config: String): String =
        config.lineSequence()
            .filterNot { line ->
                val name = line.substringBefore(':').trim().removePrefix("- ")
                name in UNSAFE_ON_ANDROID
            }
            .joinToString("\n")

    private fun directory(): File =
        File(RPCSX.rootDirectory, "config/config_db").apply { mkdirs() }

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

        runCatching { directory().listFiles()?.forEach { it.delete() } }
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

            MainActivityRuntime.prefs.edit {
                putInt(KEY_COUNT, written)
                putLong(KEY_UPDATED, System.currentTimeMillis())
                putInt(KEY_FORMAT, FORMAT_VERSION)
            }
            written
        }.getOrDefault(-1)
    }
}

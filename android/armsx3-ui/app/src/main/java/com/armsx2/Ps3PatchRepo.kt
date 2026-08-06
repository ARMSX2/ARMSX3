package com.armsx2

import net.rpcsx.RPCSX
import org.json.JSONArray

/**
 * RPCS3 per-game patches and graphics mods.
 *
 * RPCS3 keeps two YAML files: `patches/patch.yml` (the database) and
 * `patch_config.yml` (which patches are on, keyed hash -> description -> title
 * -> serial -> app_version). Both have a fiddly nested shape that patch_engine
 * already parses and writes, so all of that stays in the core -- this only
 * downloads the bytes and renders what the core reports back.
 *
 * Reimplementing the YAML here would mean a second parser to keep in step with
 * upstream, and a format drift would silently disable people's patches.
 */
object Ps3PatchRepo {

    /**
     * RPCS3's official patch feed. `v` is the patch-engine version the server
     * uses to decide which schema to hand back, so it is not cosmetic -- an
     * older value returns patches this core cannot parse.
     */
    private const val PATCH_URL = "https://rpcs3.net/compatibility?patch&api=v1&v=1.2"

    data class Patch(
        val hash: String,
        val name: String,
        val author: String,
        val notes: String,
        val version: String,
        val appVersion: String,
        val game: String,
        val enabled: Boolean,
    )

    /**
     * Download the patch database and merge it into patches/patch.yml.
     *
     * Returns the number of patches imported, or -1 on failure. Merging (rather
     * than replacing) is what the core's import path does, so hand-added patches
     * in the same file survive an update.
     */
    /** Distinguishes the failure modes so the UI can say which one happened. */
    sealed interface Result {
        data class Ok(val count: Int) : Result
        data object Network : Result
        data class Server(val code: Int) : Result
        data object Parse : Result
    }

    fun download(): Result {
        val res = runCatching {
            com.armsx3.HttpClient.doRequest(PATCH_URL, userAgent = "ARMSX3")
        }.getOrNull() ?: return Result.Network

        if (res.statusCode != 200 || res.data.isEmpty()) return Result.Network

        // The endpoint returns a JSON ENVELOPE, not raw YAML:
        //   { "return_code": 0, "version": "1.2", "sha256": "...", "patch": "<yaml>" }
        // Handing the envelope straight to the YAML parser fails on the first
        // line, which is exactly what it did.
        val yaml = runCatching {
            val obj = org.json.JSONObject(String(res.data, Charsets.UTF_8))
            val code = obj.optInt("return_code", -1)
            if (code != 0) return Result.Server(code)
            obj.optString("patch")
        }.getOrNull()

        if (yaml.isNullOrBlank()) return Result.Parse

        val n = runCatching { RPCSX.instance.patchesImport(yaml) }.getOrDefault(-1)
        return if (n >= 0) Result.Ok(n) else Result.Parse
    }

    /**
     * Patches applicable to a serial. An empty serial lists everything, which is
     * what the standalone tab shows when no game is selected.
     */
    fun list(serial: String): List<Patch> = runCatching {
        val arr = JSONArray(RPCSX.instance.patchesList(serial))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Patch(
                hash = o.optString("hash"),
                name = o.optString("name"),
                author = o.optString("author"),
                notes = o.optString("notes"),
                version = o.optString("version"),
                appVersion = o.optString("appVersion", "all"),
                game = o.optString("game"),
                enabled = o.optBoolean("enabled"),
            )
        }
    }.getOrDefault(emptyList())

    fun setEnabled(patch: Patch, serial: String, enabled: Boolean): Boolean =
        runCatching {
            RPCSX.instance.patchSetEnabled(
                patch.hash, patch.name, serial, patch.appVersion, enabled,
            )
        }.getOrDefault(false)
}

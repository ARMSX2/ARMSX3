package com.armsx2

import android.content.Context
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
     *
     * The version comes from the core (patch_engine_version) rather than being
     * written here. It was spelled out as 1.2, which is correct only until
     * upstream bumps the constant: patch_engine::load rejects any file whose
     * Version header does not match, so the two have to move together.
     */
    private fun patchUrl(version: String) =
        "https://rpcs3.net/compatibility?patch&api=v1&v=$version"

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
        data object Checksum : Result
    }

    fun download(): Result {
        val engineVersion = runCatching { RPCSX.instance.patchEngineVersion() }.getOrDefault("")
        if (engineVersion.isBlank()) return Result.Parse

        val res = runCatching {
            com.armsx3.HttpClient.doRequest(patchUrl(engineVersion), userAgent = "ARMSX3")
        }.getOrNull() ?: return Result.Network

        if (res.statusCode != 200 || res.data.isEmpty()) return Result.Network

        // The endpoint returns a JSON ENVELOPE, not raw YAML:
        //   { "return_code": 0, "version": "1.2", "sha256": "...", "patch": "<yaml>" }
        // Handing the envelope straight to the YAML parser fails on the first
        // line, which is exactly what it did.
        val envelope = runCatching {
            val obj = org.json.JSONObject(String(res.data, Charsets.UTF_8))
            val code = obj.optInt("return_code", -1)
            if (code != 0) return Result.Server(code)
            obj
        }.getOrNull() ?: return Result.Parse

        // The server picks the schema from the version we asked for, so a reply
        // for a different one is a server-side surprise rather than something to
        // hand to the parser: patch_engine::load would reject the whole file on
        // its Version header anyway, several megabytes later.
        if (envelope.optString("version") != engineVersion) return Result.Parse

        val yaml = envelope.optString("patch")
        if (yaml.isBlank()) return Result.Parse

        // Desktop RPCS3 verifies this digest before it writes anything
        // (patch_manager_dialog::handle_json), and the check was missing here.
        // Patches are writes into the guest executable, and move_file/hide_file
        // patches reach the emulator's own filesystem, so content that is not
        // what the server hashed does not get imported.
        val expected = envelope.optString("sha256")
        if (!expected.equals(sha256(yaml), ignoreCase = true)) return Result.Checksum

        val n = runCatching { RPCSX.instance.patchesImport(yaml) }.getOrDefault(-1)
        return if (n >= 0) Result.Ok(n) else Result.Parse
    }

    /**
     * Import a patch.yml the user picked themselves.
     *
     * No checksum here, unlike [download]: there is no publisher digest to compare a
     * local file against, and the user choosing the file IS the trust decision. The
     * core still parses it, so a malformed file is rejected rather than half-applied.
     *
     * Merges into patches/patch.yml like every other import, so a hand-added patch
     * sits alongside the downloaded database instead of replacing it.
     */
    fun importLocal(context: Context, uri: android.net.Uri): Result {
        val yaml = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        }.getOrNull()

        if (yaml.isNullOrBlank()) return Result.Network
        val n = runCatching { RPCSX.instance.patchesImport(yaml) }.getOrDefault(-1)
        return if (n >= 0) Result.Ok(n) else Result.Parse
    }

    /** Lowercase hex SHA-256, the form rpcs3.net sends and desktop compares against. */
    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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

    // ---------------------------------------------------------------
    // Bundled canary patches
    // ---------------------------------------------------------------

    /**
     * A patch shipped in assets/canary_patches.yml, and the game it belongs to.
     *
     * `hash` and `name` are the two keys patchSetEnabled looks up, and they must
     * match the YAML exactly: the top-level `PPU-...` key (prefix included) and
     * the patch's name key under it. A mismatch is not an error the user can see
     * -- the import still succeeds and the patch just never turns on -- so these
     * are asserted against the YAML in the comment above each entry.
     *
     * appVersion is carried for symmetry with [Patch]; the native side matches on
     * serial and ignores it.
     */
    private data class Bundled(
        val hash: String,
        val name: String,
        val serial: String,
        val appVersion: String,
    )

    private val BUNDLED = listOf(
        // SONIC THE HEDGEHOG (2006), BLUS30008 v01.01 -- without this the game
        // renders only its HUD and skybox. See canary_patches.yml.
        Bundled(
            hash = "PPU-4b46d0161ca657ab16b0a779d9062810ea5ea2dd",
            name = "Graphics Fix",
            serial = "BLUS30008",
            appVersion = "01.01",
        ),
    )

    private const val BUNDLED_ASSET = "canary_patches.yml"

    /**
     * Bumped whenever canary_patches.yml gains or changes a patch, so an existing
     * install re-imports and enables the new ones. Not a timestamp: it has to be
     * something a diff of this file makes obvious.
     */
    private const val BUNDLED_REVISION = 1

    private const val PREFS_NAME = "ARMSX2"
    private const val KEY_BUNDLED_REVISION = "ps3_bundled_patch_revision"

    /**
     * Import the bundled canary patches and switch them on, once per revision.
     *
     * These fix games that are otherwise unplayable, so they default to ON rather
     * than merely being present in the Patch Manager -- a user who has to find and
     * tick a box before Sonic '06 renders has already concluded the emulator is
     * broken.
     *
     * Guarded by a stored revision rather than run every boot, so turning one OFF
     * sticks. Re-enabling on every launch would make the toggle look broken, which
     * is the same class of bug as not having the patch at all.
     *
     * Safe to call on every boot: it is a preference read once the revision matches,
     * and the import itself merges rather than replaces, so a downloaded database
     * and hand-added patches both survive.
     */
    fun ensureBundledPatches(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BUNDLED_REVISION, 0) >= BUNDLED_REVISION) return

        val yaml = runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()

        if (yaml.isNullOrBlank()) {
            android.util.Log.e("ARMSX3", "canary patches: $BUNDLED_ASSET missing from assets")
            return
        }

        // Merges into patches/patch.yml, which is also where a downloaded database
        // lands, so the patch shows up in the Patch Manager next to the online ones.
        val imported = runCatching { RPCSX.instance.patchesImport(yaml) }.getOrDefault(-1)
        if (imported < 0) {
            android.util.Log.e("ARMSX3", "canary patches: import failed")
            return
        }

        // Only mark the revision done if every patch actually turned on. A failure
        // here means the hash or name drifted from the YAML, and retrying next boot
        // is better than silently shipping a game that does not render.
        val allEnabled = BUNDLED.all { b ->
            val ok = runCatching {
                RPCSX.instance.patchSetEnabled(b.hash, b.name, b.serial, b.appVersion, true)
            }.getOrDefault(false)
            if (!ok) {
                android.util.Log.e("ARMSX3", "canary patches: could not enable ${b.name} (${b.hash})")
            }
            ok
        }

        if (allEnabled) {
            prefs.edit().putInt(KEY_BUNDLED_REVISION, BUNDLED_REVISION).apply()
            android.util.Log.i("ARMSX3", "canary patches: imported $imported, enabled ${BUNDLED.size}")
        }
    }
}

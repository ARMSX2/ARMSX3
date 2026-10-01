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
    /** Asked for the current release rather than a pinned asset url, which would rot. */
    private const val ARTEMIS_LATEST_RELEASE =
        "https://api.github.com/repos/chidreams/Artemis-Patch-Collection-Android/releases/latest"

    private fun patchUrl(version: String) =
        "https://rpcs3.net/compatibility?patch&api=v1&v=$version"

    // One file per source under config/patches (the layout is described where the core writes
    // them, in rpcsx-android.cpp). Each download replaces its own file whole, so whatever its
    // source renamed or withdrew is gone after the next download instead of listed beside its
    // replacement; only the user's own imports (imported_patch.yml) accumulate.
    private const val DB_FILE = "patch.yml"
    private const val ARTEMIS_FILE = "artemis_patch.yml"
    private const val BUNDLED_FILE = "armsx3_patch.yml"

    /** The old merged patch.yml, kept once when the files are first split. */
    private const val PRE_SPLIT_BACKUP = "patch.yml.pre-1.0"

    private const val KEY_FILES_SPLIT = "ps3_patch_files_split"

    private fun patchesDir() = java.io.File(RPCSX.rootDirectory, "config/patches")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get an install that still has one merged patch.yml ready to have it replaced.
     *
     * Until 1.0 every source was merged into patch.yml: the database, the Artemis collection,
     * the bundled fixes and anything the user imported, with nothing recording which was which.
     * So there is no taking the old file apart; it is replaced by the first download, which is
     * the clean baseline the Artemis maintainer asked for. Two things are done first, once:
     * the bundled fixes get their own file (switched on but in no file, a patch is simply not
     * applied), and the old file is kept beside it for anyone who had imported patches of their
     * own into it. Returns whether there was an old list to keep, which is when the user is told.
     */
    private fun prepareSplit(context: Context): Boolean {
        ensureBundledPatches(context)
        return runCatching {
            val old = java.io.File(patchesDir(), DB_FILE)
            val backup = java.io.File(patchesDir(), PRE_SPLIT_BACKUP)
            if (!old.isFile) return@runCatching false
            if (!backup.exists()) old.copyTo(backup)
            true
        }.getOrElse {
            android.util.Log.w("ARMSX3", "patches: could not keep the old patch.yml", it)
            false
        }
    }

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

    /** Distinguishes the failure modes so the UI can say which one happened. [Ok.rebuilt] is
     *  set on the one download that replaced an old merged patch.yml (see [prepareSplit]). */
    sealed interface Result {
        data class Ok(val count: Int, val rebuilt: Boolean = false) : Result
        data object Network : Result
        data class Server(val code: Int) : Result
        data object Parse : Result
        data object Checksum : Result
    }

    /**
     * Download the patch database into patches/patch.yml, replacing it.
     *
     * Replaced rather than merged, as desktop RPCS3 does: a merge only ever adds, so a patch
     * the database renamed or withdrew stayed listed forever. Hand-added patches are not in this
     * file any more (they merge into imported_patch.yml), so replacing it costs nobody anything.
     */
    fun download(context: Context): Result {
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

        val splitting = !prefs(context).getBoolean(KEY_FILES_SPLIT, false)
        val keptOldList = splitting && prepareSplit(context)

        val n = runCatching { RPCSX.instance.patchesWrite(DB_FILE, yaml) }.getOrDefault(-1)
        if (n < 0) return Result.Parse
        if (splitting) prefs(context).edit().putBoolean(KEY_FILES_SPLIT, true).apply()
        return Result.Ok(n, rebuilt = keptOldList)
    }

    /**
     * The Artemis collection, a second source of patches.
     *
     * Maintained by @chidreams. Taken from github.com/chidreams/Artemis-Patch-Collection-Android,
     * the edition they publish for ARMSX3 and asked us to use: their workflow copies
     * imported_patch.yml from the main branch of Artemis-Patch-Collection-RPCS3 and releases it,
     * so it carries the same patches without waiting for that repo's own releases. All this does
     * is download their work; the cheats, the testing and the upkeep are theirs.
     *
     * Community cheats, MIT licensed, and already in RPCS3's own patch format: PPU hash keyed,
     * with `[ be32, addr, value ]` entries. No conversion step. It gets a file of its own,
     * patches/artemis_patch.yml, replaced whole by every download: the maintainer renames and
     * retires patches between releases, and merged into patch.yml (as it was until 1.0) the old
     * names stayed listed beside the new ones for good.
     *
     * The release ASSET is asked for rather than hardcoded. It is attached to a GitHub release and
     * the tag moves, so a pinned url would rot the first time they publish. Asking the API which
     * asset is current costs one small request and survives that. Either packaging is taken: the
     * RPCS3 repo released a zip holding the yml up to v1.04bfu and the bare imported_patch.yml
     * from v2026.09.19, which is also what the Android edition releases. Accepting only the zip
     * is what broke the button the day they switched.
     *
     * No checksum to verify: unlike rpcs3.net there is no published digest to compare against.
     * The transport is https and the core parses the result, so a corrupt file is rejected
     * rather than half applied, which is the same guarantee [importLocal] gives.
     */
    fun downloadArtemis(context: Context): Result {
        // An install that has not been split yet may hold an older copy of the collection merged
        // into patch.yml, and writing the new one to its own file would list every renamed patch
        // twice. So the database is refreshed first, once, which replaces that copy.
        var rebuilt = false
        if (!prefs(context).getBoolean(KEY_FILES_SPLIT, false)) {
            val db = download(context)
            if (db !is Result.Ok) return db
            rebuilt = db.rebuilt
        }

        val meta = runCatching {
            com.armsx3.HttpClient.doRequest(ARTEMIS_LATEST_RELEASE, userAgent = "ARMSX3")
        }.getOrNull() ?: return Result.Network

        if (meta.statusCode != 200 || meta.data.isEmpty()) {
            return if (meta.statusCode > 0) Result.Server(meta.statusCode) else Result.Network
        }

        // The yml itself if the release has one, else a zip to take it out of. Anything else (the
        // oldest releases were rars) is not readable here; say so in the log, because the UI can
        // only report it as a file that could not be read.
        val assets = runCatching {
            val list = org.json.JSONObject(String(meta.data, Charsets.UTF_8)).getJSONArray("assets")
            (0 until list.length()).map { list.getJSONObject(it) }
        }.getOrNull() ?: return Result.Parse

        fun assetWith(vararg extensions: String) = assets
            .firstOrNull { a -> extensions.any { a.optString("name").endsWith(it, ignoreCase = true) } }
            ?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }

        val ymlUrl = assetWith(".yml", ".yaml")
        val assetUrl = ymlUrl ?: assetWith(".zip") ?: run {
            android.util.Log.w("ARMSX3", "artemis: no .yml or .zip asset in the latest release: " +
                assets.joinToString { it.optString("name") })
            return Result.Parse
        }

        // Longer than the default: this is a whole collection, not one game's patches, and it
        // arrives over a link the user did not choose the speed of.
        val archive = runCatching {
            com.armsx3.HttpClient.doRequest(assetUrl, userAgent = "ARMSX3", timeoutMs = 60_000)
        }.getOrNull() ?: return Result.Network

        if (archive.statusCode != 200 || archive.data.isEmpty()) {
            return if (archive.statusCode > 0) Result.Server(archive.statusCode) else Result.Network
        }

        val yaml = if (ymlUrl != null) String(archive.data, Charsets.UTF_8) else runCatching {
            java.util.zip.ZipInputStream(archive.data.inputStream()).use { zip ->
                var found: String? = null
                while (found == null) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".yml", ignoreCase = true)) {
                        found = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                found
            }
        }.getOrNull()

        if (yaml.isNullOrBlank()) return Result.Parse

        val n = runCatching { RPCSX.instance.patchesWrite(ARTEMIS_FILE, yaml) }.getOrDefault(-1)
        return if (n >= 0) Result.Ok(n, rebuilt) else Result.Parse
    }

    /**
     * Import a patch.yml the user picked themselves.
     *
     * No checksum here, unlike [download]: there is no publisher digest to compare a
     * local file against, and the user choosing the file IS the trust decision. The
     * core still parses it, so a malformed file is rejected rather than half-applied.
     *
     * Merges into patches/imported_patch.yml, RPCS3's own file for these, so hand-added
     * patches accumulate there and no download ever replaces them.
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
     *
     * sinceRevision is the [BUNDLED_REVISION] this entry first shipped in. It is what
     * keeps a bump from touching the patches that were already here: an install whose
     * stored revision is at or above it has been offered this patch once already, and
     * whatever the user did with the toggle afterwards is their answer.
     */
    private data class Bundled(
        val hash: String,
        val name: String,
        val serial: String,
        val appVersion: String,
        val sinceRevision: Int,
    )

    private val BUNDLED = listOf(
        // NBA 08, BCES00112 v01.00 -- ours. The quickplay loading screen's Lua misses its one
        // "exit after this loop" event when loading beats the screen's 6.5 s intro, which it
        // always does here and never on a PS3; the loading thread now re-sends it every frame.
        // See canary_patches.yml.
        Bundled(
            hash = "PPU-30ce8c9f0a9552914275c90e2980749630f3ea18",
            name = "ARMSX3 quickplay loading fix",
            serial = "BCES00112",
            appVersion = "01.00",
            sinceRevision = 7,
        ),
        // NBA 08, BCES00112 v01.00 -- ours. The intro movie's vdec callback reads a picture
        // that cellVdecGetPicItem did not hand over (a late PICOUT on a first run, while the
        // SPU cache compiles); the patch drops that one picture instead of reading NULL+0x44.
        // See canary_patches.yml.
        Bundled(
            hash = "PPU-30ce8c9f0a9552914275c90e2980749630f3ea18",
            name = "ARMSX3 intro movie crash fix",
            serial = "BCES00112",
            appVersion = "01.00",
            sinceRevision = 6,
        ),
        // BURNOUT PARADISE, BLUS30061 v01.00 -- ours. The audio voice refill asks the heap
        // for a 1.67GB buffer and gets a correct refusal, then writes through the null; and a
        // lookup miss further on takes a null-check branch that dereferences the null anyway.
        // Deterministic crash in the intro before, reaches the open world after. See
        // canary_patches.yml.
        Bundled(
            hash = "PPU-56101fbdbac186fddef72207145015fd314a0be5",
            name = "ARMSX3 Junkyard crash fix",
            serial = "BLUS30061",
            appVersion = "01.00",
            sinceRevision = 6,
        ),
        // SOULCALIBUR V, BLUS30736 v01.00 -- illusion's "Disable MLAA". Without it the
        // title runs at ~1 fps and wedges; with it, ~56-60 fps at the menu. This is a
        // WORKAROUND: the underlying SPU synchronisation defect is still undiagnosed, and
        // the patch only sidesteps it by stopping the game issuing the MLAA job.
        // Confirmed on an Odin 3 (Snapdragon 8 Elite). See canary_patches.yml.
        Bundled(
            hash = "PPU-aa798f32a1fda1c23a20066edb1c623c486d53cc",
            name = "Disable MLAA",
            serial = "BLUS30736",
            appVersion = "01.00",
            sinceRevision = 5,
        ),
        // GRAN TURISMO 6 v01.22, BCUS99247 and BCUS98296 -- illusion's "Disable MLAA" and
        // "Disable Motion Blur", brought to ARMSX3 by mlgprorektm8. Without the first the game
        // needs Write and Read Color Buffers to render right, and the core turns both off while it
        // is applied; without the second replays and track intros are black. Keyed by the two
        // executables they were made for. See canary_patches.yml.
        Bundled(
            hash = "PPU-42367707f4caac2668f10cb46498f64bde9db440",
            name = "Disable MLAA",
            serial = "BCUS99247",
            appVersion = "01.22",
            sinceRevision = 9,
        ),
        Bundled(
            hash = "PPU-42367707f4caac2668f10cb46498f64bde9db440",
            name = "Disable Motion Blur",
            serial = "BCUS99247",
            appVersion = "01.22",
            sinceRevision = 9,
        ),
        Bundled(
            hash = "PPU-4f1e9acd7d98961b4b742fb324a2faba6212ea67",
            name = "Disable MLAA",
            serial = "BCUS98296",
            appVersion = "01.22",
            sinceRevision = 9,
        ),
        Bundled(
            hash = "PPU-4f1e9acd7d98961b4b742fb324a2faba6212ea67",
            name = "Disable Motion Blur",
            serial = "BCUS98296",
            appVersion = "01.22",
            sinceRevision = 9,
        ),
        // SONIC THE HEDGEHOG (2006), BLUS30008 v01.01 -- without this the game
        // renders only its HUD and skybox. See canary_patches.yml.
        Bundled(
            hash = "PPU-4b46d0161ca657ab16b0a779d9062810ea5ea2dd",
            name = "Graphics Fix",
            serial = "BLUS30008",
            appVersion = "01.01",
            sinceRevision = 1,
        ),
        // Tom Clancy's H.A.W.X. 2, BLES00928 -- without this the game hangs forever at
        // the first intro video with a dead SPU. See canary_patches.yml.
        Bundled(
            hash = "SPU-42bae8e5d6a9304068ba1c6bbfdc18d656e287a1",
            name = "Bink overlay skip",
            serial = "BLES00928",
            appVersion = "All",
            sinceRevision = 2,
        ),
        // RATCHET & CLANK -- every game in the family hangs without its freeze fix, so all of
        // them ship enabled. These are Juhn's patches from the community database; the name
        // casing differs between entries ("Freeze Fix" vs "Freeze fix") and patchSetEnabled
        // matches it exactly, so it is reproduced verbatim rather than tidied.
        //
        // A Crack in Time replaces our own "FIFO drain wait fix", which wrote the same word
        // with a different condition register and could only race it. See canary_patches.yml.
        // Killzone 3 -- RPCS3's own patch, on by default because without it the HUD flickers
        // constantly on ARM64 and a burst of coloured noise crosses the screen now and then. The
        // patch stops the game running MLAA on the SPUs, which is where that comes from: turning
        // it on removed the flicker outright, and every other lever we tried (the SPU block size,
        // transfer accuracy, our SHUFB paths) left it exactly as it was.
        //
        // Four entries because the database keys these per executable AND per serial, and the two
        // discs spell the name differently: no space before the bracket on 01.00, one on 01.14.
        // patchSetEnabled matches the name exactly, so both spellings are reproduced verbatim.
        //
        // It also costs nothing to have on: RPCS3's notes say it improves performance and lets
        // resolution scaling work, at the price of some screen effects.
        Bundled(
            hash = "PPU-ae204e2198c9a051a44a69913c48f6591b811082",
            name = "Disable MLAA(Post-processing On SPU)",
            serial = "BCES01007",
            appVersion = "01.00",
            sinceRevision = 8,
        ),
        Bundled(
            hash = "PPU-ae204e2198c9a051a44a69913c48f6591b811082",
            name = "Disable MLAA(Post-processing On SPU)",
            serial = "BCUS98234",
            appVersion = "01.00",
            sinceRevision = 8,
        ),
        Bundled(
            hash = "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387",
            name = "Disable MLAA (Post-processing on SPU)",
            serial = "BCES01007",
            appVersion = "01.14",
            sinceRevision = 8,
        ),
        Bundled(
            hash = "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387",
            name = "Disable MLAA (Post-processing on SPU)",
            serial = "BCUS98234",
            appVersion = "01.14",
            sinceRevision = 8,
        ),
        Bundled(
            hash = "PPU-c4e26433d1eed9166eb0c67b6f66b2268f3704e2",
            name = "Freeze Fix",
            serial = "BCES00052",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-ec77eaf73a4f55d1c4ece532c3be6db0011e49ca",
            name = "Freeze Fix",
            serial = "NPEA00452",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-f67f3e99077ba256728ffa16800c75e006661158",
            name = "Freeze Fix",
            serial = "BCUS98127",
            appVersion = "01.00",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-c14042df6304d3e420a9917e6f8e5fc05cc38b4c",
            name = "Freeze Fix",
            serial = "BCUS98127",
            appVersion = "01.00",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-16506d9d5bf692d615645accd24bca1ee1f8f9a6",
            name = "Freeze Fix",
            serial = "NPUA80965",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-4c819c69904784a56685c31df12f5d492bc0ed64",
            name = "Freeze Fix",
            serial = "BCAS20200",
            appVersion = "01.03",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-4c819c69904784a56685c31df12f5d492bc0ed64",
            name = "Freeze Fix",
            serial = "BCES01141",
            appVersion = "01.03",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-4c819c69904784a56685c31df12f5d492bc0ed64",
            name = "Freeze Fix",
            serial = "BCES01142",
            appVersion = "01.03",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-4c819c69904784a56685c31df12f5d492bc0ed64",
            name = "Freeze Fix",
            serial = "BCUS98175",
            appVersion = "01.03",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b",
            name = "Freeze fix",
            serial = "BCES01908",
            appVersion = "01.00",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b",
            name = "Freeze fix",
            serial = "BCES01949",
            appVersion = "01.00",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08",
            name = "Freeze fix",
            serial = "BCUS99245",
            appVersion = "01.01",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08",
            name = "Freeze fix",
            serial = "NPUA80908",
            appVersion = "01.01",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08",
            name = "Freeze fix",
            serial = "BCES01908",
            appVersion = "01.01",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08",
            name = "Freeze fix",
            serial = "BCES01949",
            appVersion = "01.01",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08",
            name = "Freeze fix",
            serial = "NPEA00457",
            appVersion = "01.01",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b",
            name = "Freeze Fix",
            serial = "BCAS20052",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b",
            name = "Freeze Fix",
            serial = "BCES00301",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b",
            name = "Freeze Fix",
            serial = "NPEA00088",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b",
            name = "Freeze Fix",
            serial = "NPEA00106",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-1d9e99e1f091cfbdf1714d04e690d9cd816e2971",
            name = "Freeze Fix",
            serial = "NPUA80145",
            appVersion = "All",
            sinceRevision = 4,
        ),
        Bundled(
            hash = "PPU-0997e35d2b6738f5cecfda1d76380acca0828365",
            name = "Freeze Fix",
            serial = "BCUS98124",
            appVersion = "01.00",
            sinceRevision = 4,
        ),
    )

    private const val BUNDLED_ASSET = "canary_patches.yml"

    /**
     * Bumped whenever canary_patches.yml gains or changes a patch, so an existing
     * install re-imports and enables the new ones. Not a timestamp: it has to be
     * something a diff of this file makes obvious.
     */
    private const val BUNDLED_REVISION = 9

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
     * Only patches newer than the stored revision are touched, so turning one OFF
     * sticks -- including across a later bump made for some other game. Re-enabling
     * on every launch, or on every bump, would make the toggle look broken, which is
     * the same class of bug as not having the patch at all.
     *
     * Safe to call on every boot: once the revision matches and the file is there, it is a
     * preference read and one stat.
     *
     * The patches live in patches/armsx3_patch.yml, a file of the app's own that the core
     * applies beside the downloaded ones and that no download replaces. Until 1.0 they were
     * merged into patch.yml, which a database download now replaces whole, and a patch that is
     * switched on but in no file is silently not applied: Burnout, Soulcalibur V, Sonic '06 and
     * the Ratchet games would each have lost their fix with no sign of why. The file is checked
     * on every call, not only on a revision bump, because it can go missing on its own (a new
     * data folder, a cleared patches directory) while the stored revision says all is done.
     */
    fun ensureBundledPatches(context: Context) {
        val prefs = prefs(context)
        val storedRevision = prefs.getInt(KEY_BUNDLED_REVISION, 0)
        val fileMissing = !java.io.File(patchesDir(), BUNDLED_FILE).isFile
        if (storedRevision >= BUNDLED_REVISION && !fileMissing) return

        val yaml = runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()

        if (yaml.isNullOrBlank()) {
            android.util.Log.e("ARMSX3", "canary patches: $BUNDLED_ASSET missing from assets")
            return
        }

        val imported = runCatching { RPCSX.instance.patchesWrite(BUNDLED_FILE, yaml) }.getOrDefault(-1)
        if (imported < 0) {
            android.util.Log.e("ARMSX3", "canary patches: could not write $BUNDLED_FILE")
            return
        }

        // Anything at or below the stored revision has had its one chance to be turned
        // on. Re-enabling it here would silently undo a user's OFF, and patch_config.yml
        // stores "disabled" as an absent entry, so there is nothing to read back that
        // would tell us the difference between "opted out" and "never seen". So a file that
        // was only restored enables nothing.
        val pending = BUNDLED.filter { it.sinceRevision > storedRevision }

        if (pending.isEmpty()) {
            prefs.edit().putInt(KEY_BUNDLED_REVISION, BUNDLED_REVISION).apply()
            return
        }

        // Only mark the revision done if every patch actually turned on. A failure
        // here means the hash or name drifted from the YAML, and retrying next boot
        // is better than silently shipping a game that does not render. The retry
        // covers only `pending`, so a patch that is stuck failing cannot drag the
        // already-settled ones back on every boot with it.
        val allEnabled = pending.all { b ->
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
            android.util.Log.i("ARMSX3", "canary patches: imported $imported, enabled ${pending.size}")
        }
    }
}

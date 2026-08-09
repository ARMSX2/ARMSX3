package com.armsx2

import android.content.Context
import android.util.Log
import com.armsx3.HttpClient
import org.json.JSONObject
import java.io.File

/**
 * Browse + download community controller skins from the ARMSX2 skins repo, the same
 * shape as [CustomDriver] (drivers) and [PatchRepo] (patches): fetch an index, hand a
 * downloaded archive to the existing installer.
 *
 * Downloads land in the cache dir and are handed to
 * [ControllerSkinStore.importFromZipFile], so nothing here knows how a skin is
 * extracted or what a valid button image is — that stays in one place.
 *
 * INDEX: `manifest.json` at the repo root is preferred, because it carries the
 * author's display name, a preview image and the button count. The git-trees
 * fallback exists so the browser still works if the manifest is missing or
 * malformed; it recovers filenames, and previews where a Previews/ image pairs with
 * a skin by name, but no author or button count.
 *
 * The manifest is published by someone else, so BOTH paths have to survive it being
 * wrong — see [dropTrailingCommas]. A broken index should cost detail, never the
 * whole browser.
 */
object SkinRepo {
    private const val TAG = "SkinRepo"
    private const val REPO = "bagasromadon/ARMSX2-CustomControllerSkins"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/main"
    private const val MANIFEST_URL = "$RAW_BASE/manifest.json"
    private const val TREE_URL = "https://api.github.com/repos/$REPO/git/trees/main?recursive=1"

    /** Skin zips run ~150 KB–3.5 MB. 32 MB is far above any real pack and still
     *  bounds a hostile or corrupted response, since HttpClient buffers whole. */
    private const val MAX_ZIP_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024

    data class RemoteSkin(
        val name: String,
        val filePath: String,
        val previewPath: String?,
        val author: String,
        val buttons: Int,
        val sizeBytes: Long,
        /** Non-null when the pack ships an iOS layout file. Android ignores layouts,
         *  which is why the UI says so — see the note in SkinsTab. */
        val iosLayout: String?,
    ) {
        val downloadUrl: String get() = "$RAW_BASE/${encodePath(filePath)}"
        val previewUrl: String? get() = previewPath?.let { "$RAW_BASE/${encodePath(it)}" }
    }

    /** Percent-encode each path segment. Nearly every skin filename has spaces
     *  ("God Of War 2.zip"), and raw.githubusercontent 400s on an unencoded space. */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    private fun userAgent(): String = "ARMSX2/" + runCatching {
        com.armsx3.NativeApp.getBuildVersion()
    }.getOrNull().orEmpty().ifEmpty { "dev" }

    private fun get(url: String, timeoutMs: Int = 20_000, maxBytes: Long = MAX_ZIP_BYTES): ByteArray? {
        val resp = runCatching {
            HttpClient.doRequest(url, "GET", null, userAgent(), timeoutMs)
        }.getOrNull() ?: return null
        if (resp.statusCode != 200 || resp.data.isEmpty()) {
            Log.w(TAG, "GET $url -> status=${resp.statusCode} size=${resp.data.size}")
            return null
        }
        if (resp.data.size > maxBytes) {
            Log.w(TAG, "GET $url -> ${resp.data.size} bytes exceeds cap $maxBytes")
            return null
        }
        return resp.data
    }

    /** Available skins, newest index first. Blocking — call on Dispatchers.IO. */
    fun fetch(): List<RemoteSkin> {
        parseManifest(get(MANIFEST_URL, maxBytes = 1L * 1024 * 1024)?.toString(Charsets.UTF_8))
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        Log.w(TAG, "manifest unusable, falling back to git tree")
        return fetchFromTree()
    }

    /**
     * Drop `,` separated only by whitespace from the `}` or `]` that closes its container.
     *
     * A trailing comma is invalid JSON and org.json rejects it, so ONE stray comma in the
     * published manifest took down the whole skin index — [fetch] fell through to
     * [fetchFromTree], which recovers filenames and nothing else, and the browser lost every
     * preview, author and button count at once. That is a lot of blast radius for a comma in a
     * file this app does not own and cannot fix from here, and the failure is silent from the
     * user's side: skins still list, they just go blank.
     *
     * String-aware rather than a regex, so a name that genuinely contains `, ]` survives.
     */
    private fun dropTrailingCommas(body: String): String {
        val out = StringBuilder(body.length)
        var inString = false
        var escaped = false
        for (c in body) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            if (c == '"') inString = true
            else if (c == '}' || c == ']') {
                var i = out.length
                while (i > 0 && out[i - 1].isWhitespace()) i--
                if (i > 0 && out[i - 1] == ',') out.deleteCharAt(i - 1)
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun parseManifest(body: String?): List<RemoteSkin>? {
        if (body.isNullOrBlank()) return null
        // Strict first: a manifest that is already valid must not be rewritten on the way in.
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject(dropTrailingCommas(body)) }.getOrNull()
                ?.also { Log.w(TAG, "manifest has trailing commas, parsed after repair") }
            ?: return null
        return runCatching {
            val arr = root.getJSONArray("skins")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteSkin(
                    name = o.optString("name").takeIf { it.isNotBlank() }
                        ?: file.substringAfterLast('/').removeSuffix(".zip"),
                    filePath = file,
                    previewPath = o.optString("preview").takeIf { it.isNotBlank() },
                    author = o.optString("author").takeIf { it.isNotBlank() } ?: "community",
                    buttons = o.optInt("buttons", 0),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    iosLayout = o.optString("iosLayout").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }

    /** Comparison key for pairing a skin with its preview across the naming schemes the repo
     *  actually uses — "ARMSX1 Skin.zip" against "ARMSX1_Skin_preview.png", "Black Gold
     *  Frosted.zip" against "black-gold-frosted.png". Letters and digits only, trailing
     *  "preview" dropped. Near misses just go without a thumbnail. */
    private fun matchKey(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }.removeSuffix("preview")

    /** Filenames plus whatever previews pair by name — used when the manifest can't be read. */
    private fun fetchFromTree(): List<RemoteSkin> {
        val body = get(TREE_URL, maxBytes = 4L * 1024 * 1024)?.toString(Charsets.UTF_8)
            ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).getJSONArray("tree")
            val paths = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

            // Previews recovered by name. This fallback used to hand back previewPath = null for
            // everything, so the one time it ran — a malformed manifest — the browser showed a
            // list of bare filenames. The tree lists Previews/ too; the images were sitting right
            // there. Pair what pairs, rather than dropping the lot because the index is broken.
            val previews = paths.mapNotNull { o ->
                val p = o.optString("path")
                if (!p.startsWith("Previews/") || !p.endsWith(".png")) null
                else matchKey(p.substringAfterLast('/').removeSuffix(".png")) to p
            }.toMap()

            paths.mapNotNull { o ->
                val path = o.optString("path")
                if (!path.startsWith("Skins/") || !path.endsWith(".zip")) return@mapNotNull null
                // The two-byte CRLF placeholders that used to sit in this repo would
                // download "fine" and then import zero images. Anything under 1 KB
                // cannot hold a button PNG, so drop it rather than surface a skin that
                // installs to nothing.
                val size = o.optLong("size", 0L)
                if (size in 1 until 1024) return@mapNotNull null
                val name = path.substringAfterLast('/').removeSuffix(".zip")
                RemoteSkin(
                    name = name,
                    filePath = path,
                    previewPath = previews[matchKey(name)],
                    author = "community",
                    buttons = 0,
                    sizeBytes = size,
                    iosLayout = null,
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ---- preview images ----

    private fun previewDir(ctx: Context): File =
        File(ctx.cacheDir, "skinpreviews").apply { mkdirs() }

    /** Cached preview file for [skin], downloading on first use. Null when the skin
     *  has no preview or the fetch failed — the UI just shows no thumbnail. */
    fun preview(ctx: Context, skin: RemoteSkin): File? {
        val url = skin.previewUrl ?: return null
        val cached = File(previewDir(ctx), skin.filePath.hashCode().toString(16) + ".png")
        if (cached.isFile && cached.length() > 0) return cached
        val bytes = get(url, timeoutMs = 15_000, maxBytes = MAX_PREVIEW_BYTES) ?: return null
        return runCatching {
            cached.outputStream().use { it.write(bytes) }
            cached
        }.getOrElse { cached.delete(); null }
    }

    // ---- download + install ----

    /** Download [skin] and install it via [ControllerSkinStore]. Returns the new local
     *  skin id, or null if the download failed or the archive held no button images.
     *  Blocking — call on Dispatchers.IO. */
    fun install(ctx: Context, skin: RemoteSkin): String? {
        val bytes = get(skin.downloadUrl, timeoutMs = 60_000) ?: return null
        val tmp = File(ctx.cacheDir, "skin-dl-${System.nanoTime()}.zip")
        return try {
            tmp.outputStream().use { it.write(bytes) }
            ControllerSkinStore.importFromZipFile(ctx, tmp, skin.name)
        } catch (t: Throwable) {
            Log.w(TAG, "install ${skin.name} failed", t)
            null
        } finally {
            tmp.delete()
        }
    }
}

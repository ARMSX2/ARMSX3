package com.armsx2.data.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.armsx2.FilenameParser
import com.armsx2.GameInfo
import com.armsx2.GamePlatform
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.armsx2.CustomCovers
import com.armsx2.DiscIcons
import com.armsx3.NativeApp
import net.rpcsx.GameFlag
import net.rpcsx.RPCSX
import net.rpcsx.GameRepository as NativeGames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GameLibraryRepository(private val context: Context) {
    private val gameExtensions = setOf(
        "iso", "chd", "cso", "zso", "gz", "bin", "mdf", "img", "nrg", "dump", "elf",
    )

    // Recent-games export runs off the launch/UI thread; exportLock serialises the file
    // write so a quick play-then-remove can't interleave two writers on the same file.
    private val exportScope = CoroutineScope(Dispatchers.IO)
    private val exportLock = Any()

    /**
     * Cache identity: the folder set AND the scanner's schema version.
     *
     * The version matters because the cache stores the SCAN RESULT, not the
     * inputs. When the scanner learns to read something new -- PS3 title IDs and
     * disc icons out of PARAM.SFO, say -- every existing cache still says "these
     * folders are done" and the new probe never runs. Bump this whenever the
     * scanner starts extracting a field it did not before.
     */
    fun cacheKey(directories: List<String>): String =
        "v$ScanSchemaVersion|" +
            (directories.sorted() + internalGameDirectories().map { it.absolutePath })
                .joinToString("|")

    /**
     * The emulator's OWN game storage, always scanned on top of the user's ROM folders.
     *
     * A PKG install and anything dropped into RPCS3's games directory land here, never in
     * a ROM folder, so neither was reachable: this library replaced net.rpcsx's
     * GameRepository, which did read both of these, and the paths came with it. Reported as
     * "doesn't detect disc games from config folder like other ps3 emus".
     *
     * Both hold games in folder form, so [isPs3GameFolder] is what actually finds them.
     */
    /**
     * Keep game art out of the user's gallery.
     *
     * An .iso is one opaque file, so the media scanner sees nothing inside it. A disc in
     * FOLDER form lays its ICON0.PNG, PIC1.PNG and every DLC image out on shared storage,
     * where the scanner indexes them and they land in the camera roll. One Minecraft folder
     * accounted for 245 images, which was every image in the whole ROM tree.
     *
     * The marker goes at the ROM directory root the user configured, not inside a game
     * folder: it covers current and future folder games in one file, and it never puts a
     * stray file inside content the emulator mounts as a disc.
     *
     * Zero bytes and reversible, deleting it restores the old behaviour. Best effort, since
     * the ROM folder may be read-only or reached over SAF.
     */
    private fun shieldFromMediaScanner(directory: File) {
        runCatching {
            val marker = File(directory, ".nomedia")
            if (marker.exists()) return

            if (marker.createNewFile()) {
                android.util.Log.i(ScanTag, "wrote .nomedia in ${directory.absolutePath}")
                // Adding the marker does not retroactively drop what MediaStore already
                // indexed. Re-scanning the path is what makes the provider re-evaluate the
                // subtree and forget it.
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(directory.absolutePath), null, null,
                    )
                }
            }
        }
    }

    /** True when the emulator's own storage holds games, ROM folders or not. */
    fun hasInternalGames(): Boolean = internalGameDirectories().any {
        runCatching { it.listFiles()?.isNotEmpty() }.getOrNull() == true
    }

    /**
     * Absolute paths of installed titles the core cannot decrypt without a licence.
     *
     * Asked of the CORE rather than worked out here. The flag comes from actually attempting
     * decrypt_self on the game's EBOOT (fetchGameInfo, rpcsx-android.cpp), which is the only
     * honest answer to "does this need a .rap". Checking PARAM.SFO's CONTENT_ID against
     * exdata instead -- the obvious pure-Kotlin shortcut -- would call every PSN title locked,
     * including the many whose EBOOT needs no licence at all.
     *
     * Canonical paths on both sides: the core resolves the paths it reports, and
     * /storage/emulated/0 and /data/media/0 are one directory under two names.
     *
     * Only the emulator's own storage is asked about, which is where PKG installs land.
     */
    private fun lockedGamePaths(): Set<String> = runCatching {
        if (!RPCSX.initialized) return emptySet()
        // The native repository is a scratch buffer here: nothing else in this app reads it,
        // and collectGameInfo appends into it.
        NativeGames.clear()
        internalGameDirectories().forEach { dir ->
            RPCSX.instance.collectGameInfo(dir.absolutePath, -1)
        }
        NativeGames.list()
            .filter { it.hasFlag(GameFlag.Locked) }
            .mapNotNull { game -> runCatching { File(game.info.path).canonicalPath }.getOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun internalGameDirectories(): List<File> = listOf(
        File(RPCSX.rootDirectory, "config/dev_hdd0/game"),
        File(RPCSX.rootDirectory, "config/games"),
    ).filter { runCatching { it.isDirectory }.getOrDefault(false) }

    /**
     * Drop the cached scan so the next library load re-reads storage.
     *
     * Needed after an install: it adds a game inside a directory that was already in the
     * cache key, so nothing about the key changes and the library would keep serving the
     * pre-install list.
     */
    fun invalidateCache() {
        MainActivityRuntime.prefs.edit {
            remove("gamesCacheKey")
            remove("gamesCacheDir")
        }
    }

    fun loadCached(): CachedLibrary {
        val cachedKey = MainActivityRuntime.prefs.getString("gamesCacheKey", null)
            ?: MainActivityRuntime.prefs.getString("gamesCacheDir", null)
        val json = MainActivityRuntime.prefs.getString("gamesCache", null)
            ?: return CachedLibrary(cachedKey, emptyList())
        val games = runCatching {
            val array = JSONArray(json)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(
                        GameInfo(
                            uri = item.getString("uri").toUri(),
                            title = item.getString("title"),
                            serial = if (item.isNull("serial")) null else item.optString("serial").takeIf(String::isNotBlank),
                            compatibility = item.optInt("compat", 0),
                            extension = item.optString("ext").ifBlank {
                                item.getString("uri").substringAfterLast('.', "").uppercase()
                            },
                            platform = GamePlatform.fromKey(item.optString("platform").takeIf(String::isNotBlank)),
                            // Absent in a cache written before #338 — optString gives "",
                            // which reads as "no separate sort key / not translated", so an
                            // old cache degrades to the previous behaviour until a rescan.
                            titleSort = item.optString("titleSort"),
                            titleEn = item.optString("titleEn"),
                            locked = item.optBoolean("locked", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return CachedLibrary(cachedKey, games)
    }

    suspend fun scan(directories: List<String>): List<GameInfo> = withContext(Dispatchers.IO) {
        // Seed the probe cache from the last scan before touching anything.
        //
        // probeDisc mounts the image into the emulator's GLOBAL vfs to read its PARAM.SFO,
        // and discInfoCache only ever lived in memory on one repository instance, so every
        // rescan re-mounted every disc from scratch. That is slow on a 7 GB image and it is
        // the window in which a scan can collide with a boot or a teardown, which crashed
        // the process inside vfs::mount.
        //
        // Everything the probe produces is already durable: the serial and title are in the
        // library cache, and the icon is on disk under disc-icons. So a disc we have seen
        // before never needs mounting again.
        //
        // That last clause is only true if the extraction actually succeeded once. The serial
        // and title are durable by construction -- this loop is reading them -- but the icon
        // is a separate file that may never have been written: probeDiscInfo answers "{}"
        // whenever a game is loaded, and the serial then comes from the FILENAME instead,
        // which for dev_hdd0/game/<title id> is indistinguishable from one the SFO gave us.
        // Seeding on the serial alone made that miss permanent, because every later rescan
        // skipped the one thing that would repair it. Reported as a PKG-installed title
        // showing a text placeholder for good, with its ICON0.PNG sitting unread in the game
        // folder and its path already recorded in games.json.
        //
        // So gate the skip on the icon as well, for the games that have one. Re-probing costs
        // one mount, once, and only for a game actually missing it; a game whose icon is on
        // disk still never mounts again, which is what the reasoning above is protecting.
        loadCached().games.forEach { game ->
            val serial = game.serial?.takeIf { it.isNotBlank() } ?: return@forEach
            val path = runCatching { game.uri.path }.getOrNull() ?: return@forEach
            // Folders only, and that is not a convenience: re-probing an ISO means load_iso ->
            // vfs::mount, which is the process-wide mount this whole seeding exists to avoid, and
            // it has crashed the app for real -- twice in one day, faulting in
            // manual_typemap::init<vfs_manager> from this very thread while a boot was starting.
            // A directory is read straight off disk by read_sfo_game_info with no mount at all,
            // so it carries none of that risk. The game this was reported for was a PKG install
            // (folder form) whose ICON0.PNG was sitting there unread, which is exactly the case
            // that stays covered.
            val isFolder = game.extension.equals("folder", ignoreCase = true)
            if (isFolder && !DiscIcons.has(serial)) {
                android.util.Log.i(ScanTag, "re-probing folder '$serial': no usable disc icon on disk")
                return@forEach
            }
            discInfoCache.putIfAbsent(path, DiscInfo(serial, game.title))
        }

        val collected = linkedMapOf<String, GameInfo>()
        android.util.Log.i(ScanTag, "scan start: ${directories.size} dir(s), rawStorage=${canUseRawStorage()}")
        directories.forEach { rawUri ->
            val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return@forEach
            val posix = MainActivityRuntime.resolveTreeUriToPosix(rawUri)
            val rawRoot = if (canUseRawStorage()) posix?.let(::File) else null
            android.util.Log.i(ScanTag, "dir=$rawUri -> posix=$posix isDir=${rawRoot?.isDirectory}")
            if (rawRoot?.isDirectory == true) {
                shieldFromMediaScanner(rawRoot)
                scanRawDirectory(rawRoot, collected, 0)
            } else {
                val tree = DocumentFile.fromTreeUri(context, uri)
                android.util.Log.i(ScanTag, "  SAF fallback: tree=${tree?.uri} canRead=${tree?.canRead()} children=${runCatching { tree?.listFiles()?.size }.getOrNull()}")
                tree?.let { scanDocumentTree(it, collected, 0) }
            }
        }
        internalGameDirectories().forEach { dir ->
            android.util.Log.i(ScanTag, "internal dir=${dir.absolutePath}")
            // dev_hdd0/game is the emulator's own install root and its shape is known: one
            // directory per title, nothing else. It gets the strict scan. Everything else is
            // a folder a user pointed us at, where games legitimately sit at any depth.
            if (dir.name == "game") scanInstalledTitles(dir, collected)
            else scanRawDirectory(dir, collected, 0)
        }
        val locked = lockedGamePaths()
        android.util.Log.i(ScanTag, "scan done: ${collected.size} game(s), ${locked.size} locked")
        collected.values
            .map { game ->
                val path = runCatching { game.uri.path?.let { File(it).canonicalPath } }.getOrNull()
                if (path != null && path in locked) game.copy(locked = true) else game
            }
            .sortedBy { it.title.lowercase() }
            .also { saveCache(directories, it) }
    }

    fun recentGames(allGames: List<GameInfo>): List<GameInfo> {
        val raw = MainActivityRuntime.prefs.getString("recentGameUris", null) ?: return emptyList()
        val order = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        val byUri = allGames.associateBy { it.uri.toString() }
        return order.mapNotNull(byUri::get)
    }

    fun markPlayed(game: GameInfo) {
        val uri = game.uri.toString()
        val current = runCatching {
            MainActivityRuntime.prefs.getString("recentGameUris", null)?.let(::JSONArray)?.let { array ->
                MutableList(array.length()) { array.getString(it) }
            }
        }.getOrNull() ?: mutableListOf()
        current.remove(uri)
        current.add(0, uri)
        while (current.size > 12) current.removeAt(current.lastIndex)
        MainActivityRuntime.prefs.edit {
            putString(
                "recentGameUris",
                JSONArray(current).toString()
            )
        }
        val snapshot = current.toList()
        exportScope.launch { exportRecentGamesPublic(snapshot, game) }
    }

    /**
     * Drop a single game from Recently Played without touching the library or the
     * global "Show Recently Played" toggle. It naturally returns to the top of the
     * list the next time it's launched (markPlayed re-adds it).
     */
    fun removeFromRecent(game: GameInfo) {
        val uri = game.uri.toString()
        val current = runCatching {
            MainActivityRuntime.prefs.getString("recentGameUris", null)?.let(::JSONArray)?.let { array ->
                MutableList(array.length()) { array.getString(it) }
            }
        }.getOrNull() ?: return
        if (!current.remove(uri)) return
        MainActivityRuntime.prefs.edit {
            putString(
                "recentGameUris",
                JSONArray(current).toString()
            )
        }
        val snapshot = current.toList()
        exportScope.launch { exportRecentGamesPublic(snapshot) }
    }

    /**
     * Empty Recently Played. Same contract as [removeFromRecent], just for every entry: the
     * library and the "Show Recently Played" toggle are untouched, and games reappear as they
     * are launched again. The public export is refreshed so the shelf doesn't come back from
     * the exported copy.
     */
    fun clearRecent() {
        MainActivityRuntime.prefs.edit { remove("recentGameUris") }
        exportScope.launch { exportRecentGamesPublic(emptyList()) }
    }

    /**
     * Mirrors the recently-played list to a plain `recent_games.json` under the app's data
     * root (the shared-storage folder the user picked, next to gamesettings/ and memcards/;
     * or the app-private externalFilesDir when none was chosen). `recentGameUris` lives in
     * app-private SharedPreferences no other app can read, so this hands companion tools
     * (launchers, offline RA caches) the same "recently played" data they already read from
     * that folder. Runs on exportScope (IO) so the cache parse + write never touch the
     * launch/UI thread; exportLock serialises the write. Feature contributed by misantronic
     * (PR #391), reworked here to run off-thread and to also fire on removal.
     */
    private fun exportRecentGamesPublic(orderedUris: List<String>, justPlayed: GameInfo? = null) {
        val root = MainActivityRuntime.systemDirPosix()
            ?: context.getExternalFilesDir(null)?.absolutePath
            ?: return
        val cached = loadCached().games
        val byUri = (if (justPlayed != null) cached + justPlayed else cached).associateBy { it.uri.toString() }
        val array = JSONArray()
        orderedUris.forEach { uriString ->
            val g = byUri[uriString] ?: return@forEach
            array.put(JSONObject().apply {
                put("uri", g.uri.toString())
                put("title", g.title)
                put("serial", g.serial ?: JSONObject.NULL)
                put("ext", g.extension)
                put("platform", g.platform.key)
            })
        }
        synchronized(exportLock) {
            runCatching { File(root, "recent_games.json").writeText(array.toString()) }
        }
    }

    private fun scanDocumentTree(
        directory: DocumentFile,
        output: MutableMap<String, GameInfo>,
        depth: Int,
    ) {
        if (depth > MaxScanDepth) return
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        children.forEach { file ->
            if (file.isDirectory) {
                // Same leaf rule as the raw scan. No SFO probe here: the core opens
                // by path and a content:// tree has none to give, so the title comes
                // from the folder name -- as it already does for a SAF-listed .iso.
                if (runCatching { isPs3GameDocument(file) }.getOrDefault(false)) {
                    output.putIfAbsent(
                        file.uri.toString(),
                        createGame(file.uri, file.name ?: "", "folder", null),
                    )
                    return@forEach
                }
                scanDocumentTree(file, output, depth + 1)
                return@forEach
            }
            val name = file.name ?: return@forEach
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in gameExtensions) return@forEach
            val probe = if (extension in probeExtensions) probeDocument(file.uri) else null
            output.putIfAbsent(file.uri.toString(), createGame(file.uri, name, extension, probe))
        }
    }

    /**
     * True when this directory IS a game rather than a folder containing games.
     *
     * Two shapes, both common and neither an .iso:
     *   - a JB folder dump, which keeps the disc layout: <dir>/PS3_GAME/PARAM.SFO
     *     (PS3_DISC.SFB alongside it on a real disc rip)
     *   - an installed/HDD game folder -- PARAM.SFO next to USRDIR. This is what
     *     RPCS3's own games directory holds, and the shape prototypes that never
     *     got a retail master ship in.
     *
     * Case-insensitive: these come off FAT/exFAT cards and out of archives, so
     * "PS3_GAME" is as likely to be "ps3_game".
     */
    private fun isPs3GameFolder(directory: File): Boolean {
        fun child(vararg path: String): File? {
            var current: File = directory
            for (segment in path) {
                current = current.listFiles()
                    ?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                    ?: return null
            }
            return current
        }
        if (child("PS3_GAME", "PARAM.SFO")?.isFile == true) return true
        if (child("PS3_DISC.SFB")?.isFile == true) return true
        val sfo = child("PARAM.SFO")?.takeIf { it.isFile } ?: return false
        if (child("USRDIR")?.isDirectory != true) return false
        // A game data install has this exact shape too, a PARAM.SFO next to USRDIR, so the
        // layout alone cannot tell it apart from an HDD game. It holds no EBOOT, so listing it
        // gave every title that installs data a second tile that cannot boot (Skate 3's 1.1 GB
        // BLUS30464_INSTALL, sitting next to the disc it belongs to). CATEGORY is what
        // separates them: GD is data, HG and DG are games. The native scanner already rejects
        // these because fetchGameInfo requires BOOTABLE and game data does not set it. This
        // applies the same rule to the folder path, which never opens the SFO at all.
        return sfoCategory(sfo) != "GD"
    }

    /**
     * CATEGORY out of a PARAM.SFO, or null when it cannot be read.
     *
     * A malformed or truncated file has to degrade to "list it" rather than hide a real game,
     * which is what [ParamSfo] answering null for every failure buys here.
     */
    private fun sfoCategory(sfo: File): String? = ParamSfo.string(sfo, "CATEGORY")

    /** [isPs3GameFolder] over a SAF tree. */
    private fun isPs3GameDocument(directory: DocumentFile): Boolean {
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return false
        fun find(name: String) = children.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (find("PS3_DISC.SFB")?.isFile == true) return true
        find("PS3_GAME")?.takeIf { it.isDirectory }?.let { gameDir ->
            val inner = runCatching { gameDir.listFiles() }.getOrNull().orEmpty()
            if (inner.any { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }) return true
        }
        return find("PARAM.SFO")?.isFile == true && find("USRDIR")?.isDirectory == true
    }

    /**
     * dev_hdd0/game, scanned as what it is: one directory per installed title.
     *
     * The recursive scan cannot be used here. It descends into anything that is not itself a
     * game folder and then accepts any file whose extension is in [gameExtensions], and "img"
     * is one of those. A title's own data is full of them, so once a package unpacked its
     * contents over this directory rather than into a title folder of its own, GTA IV's
     * archives arrived in the library as games: manhat01, props_ab, vehicles, script,
     * weapons. Every one of them a .img sitting where the scanner was willing to look.
     *
     * The extractor no longer unpacks into this directory (see unpkg.cpp set_install_path),
     * but nothing should be relying on that to keep the library clean, and existing installs
     * still have the debris. A direct child here is a title or it is not listed.
     */
    private fun scanInstalledTitles(directory: File, output: MutableMap<String, GameInfo>) {
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        children.forEach { file ->
            if (!file.isDirectory) return@forEach
            if (!runCatching { isPs3GameFolder(file) }.getOrDefault(false)) {
                android.util.Log.i(ScanTag, "  skipping non-title '${file.name}' in dev_hdd0/game")
                return@forEach
            }
            val uri = Uri.fromFile(file)
            android.util.Log.i(ScanTag, "  installed title '${file.name}'")
            output.putIfAbsent(
                uri.toString(),
                createGame(uri, file.name, "folder", null, probeDisc(file)),
            )
        }
    }

    private fun scanRawDirectory(
        directory: File,
        output: MutableMap<String, GameInfo>,
        depth: Int,
    ) {
        if (depth > MaxScanDepth) return
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        children.forEach { file ->
            if (file.isDirectory) {
                // A game folder is a leaf: emit it and do NOT descend. Descending
                // also used to add USRDIR/EBOOT.BIN as its own bogus entry, since
                // "bin" is in gameExtensions.
                if (runCatching { isPs3GameFolder(file) }.getOrDefault(false)) {
                    val folderUri = Uri.fromFile(file)
                    android.util.Log.i(ScanTag, "  game folder '${file.name}'")
                    output.putIfAbsent(
                        folderUri.toString(),
                        createGame(folderUri, file.name, "folder", null, probeDisc(file)),
                    )
                    return@forEach
                }
                scanRawDirectory(file, output, depth + 1)
                return@forEach
            }
            val extension = file.extension.lowercase()
            android.util.Log.i(ScanTag, "  raw file '${file.name}' ext=$extension accepted=${extension in gameExtensions}")
            if (extension !in gameExtensions) return@forEach
            val uri = Uri.fromFile(file)
            val disc = if (extension in probeExtensions) probeDisc(file) else null
            val probe = if (disc == null && extension in probeExtensions) probeRaw(file) else null
            output.putIfAbsent(uri.toString(), createGame(uri, file.name, extension, probe, disc))
        }
    }

    /**
     * Drop the separator, but only from something that actually looks like a title ID.
     *
     * The check is not about which console the game is for -- this emulator runs PS3 titles
     * and nothing else. It is about not manufacturing an identity out of a guess.
     * FilenameParser takes the first four-letters + five-digits token it finds ANYWHERE in
     * the name and reconstructs it in the PS2 dump shape it was written for, so what arrives
     * here may be a real id (BLUS-30917), a token out of a release tag, or an id belonging to
     * a different game entirely.
     *
     * Left hyphenated, a bad guess matches nothing: no cover, no disc icon, no config, and
     * the card shows a placeholder. That is a visible failure and it is the safe one.
     * Stripped, the same guess becomes a WELL-FORMED title id and quietly resolves whatever
     * is filed under it -- another game's cover and curated name, and its config_db entry,
     * which the core applies at boot. So normalise only what carries a real PS3 prefix:
     * B for disc releases (BLUS/BLES/BCUS...), N for PSN (NPUB/NPEB...).
     *
     * Deliberately NOT gated on [GamePlatform]: that enum comes from the same probe that
     * produced the serial, so in the one case this function exists for -- the probe failed
     * and the name came off the filename -- it carries no information at all.
     */
    private fun normalizeSerial(raw: String): String {
        val stripped = raw.replace("-", "")
        return if (ps3SerialRegex.matches(stripped)) stripped else raw
    }

    /**
     * Carry a game's per-serial data across an identity correction.
     *
     * The serial is not just the cover key: it keys config.game.<serial>, per-game core
     * overrides, touch layouts and profiles, pad bindings, play time, the pinned name and
     * the custom cover file. Renaming the game without moving those silently resets every
     * one of them, and nothing prunes the old keys afterwards, so they become unreachable
     * rather than merely unused.
     *
     * A same-named key already under the new id is overwritten. It can only have come from
     * an earlier scan that probed the disc successfully, before this entry regressed to a
     * filename-derived id; the hyphenated one is what the game has actually been running
     * with since, so it is the live value and the older one is stale.
     */
    private fun migrateSerialKeys(old: String, new: String) {
        runCatching {
            val prefs = MainActivityRuntime.prefs
            val snapshot = prefs.all
            val moved = snapshot.keys.filter { it.contains(old) }
            if (moved.isNotEmpty()) {
                prefs.edit().apply {
                    moved.forEach { key ->
                        when (val value = snapshot[key]) {
                            is String -> putString(key.replace(old, new), value)
                            is Int -> putInt(key.replace(old, new), value)
                            is Long -> putLong(key.replace(old, new), value)
                            is Boolean -> putBoolean(key.replace(old, new), value)
                            is Float -> putFloat(key.replace(old, new), value)
                            is Set<*> -> @Suppress("UNCHECKED_CAST")
                                putStringSet(key.replace(old, new), value as Set<String>)
                            else -> return@forEach
                        }
                        remove(key)
                    }
                }.apply()
            }
            CustomCovers.renameSerial(context, old, new)
            android.util.Log.i(ScanTag, "serial '$old' -> '$new' (${moved.size} pref key(s) moved)")
        }
    }

    private fun createGame(
        uri: Uri,
        name: String,
        extension: String,
        rawProbe: String?,
        disc: DiscInfo? = null,
    ): GameInfo {
        val (probeSerial, probePlatform) = parseProbe(rawProbe)
        val (fileTitle, fileSerial) = FilenameParser.parse(name)
        val platform = if (disc != null) GamePlatform.PS3 else probePlatform ?: GamePlatform.PS3
        // The disc's own PARAM.SFO wins: it is the authoritative title ID, where
        // a filename-derived one is a guess off a dump's naming convention.
        //
        // Normalise the result: FilenameParser reconstructs every serial in the PS2 dump
        // shape (SLUS-20312) because that is the convention its regex was written for, so a
        // PS3 game whose serial came off the filename is recorded as BLUS-30917 and matches
        // nothing -- not the cover repo (keyed by the exact PARAM.SFO id), not disc-icons,
        // not config.game.<serial>. Doing it here rather than in FilenameParser is what
        // repairs the entries already cached: they are re-seeded into discInfoCache on every
        // scan and arrive back here as disc.titleId, which has top priority.
        val rawSerial = disc?.titleId ?: probeSerial ?: fileSerial
        val serial = rawSerial?.let(::normalizeSerial)
        if (rawSerial != null && serial != null && rawSerial != serial) {
            migrateSerialKeys(rawSerial, serial)
        }
        val compatibility = serial
            ?.let { runCatching { NativeApp.getCompatibilityForSerial(it) }.getOrDefault(0) }
            ?.minus(1)
            ?.coerceIn(0, 5)
            ?: 0
        // GameDB title first, filename only as the fallback — the same order GameList.cpp
        // uses. The database is the curated name: it drops dump cruft ("(USA) [!] v1.1"),
        // and for a Japanese game it is the ACTUAL Japanese title, which no filename-derived
        // guess can produce. Issue #338.
        val db = serial?.let { dbTitles(it) }
        return GameInfo(
            uri = uri,
            title = disc?.title?.takeIf { it.isNotBlank() }
                ?: db?.name?.takeIf { it.isNotEmpty() }
                ?: fileTitle,
            serial = serial,
            compatibility = compatibility,
            extension = extension.uppercase(),
            platform = platform,
            // Only meaningful alongside a DB title; a filename-derived one has no sort key
            // and is not a translation of anything.
            titleSort = db?.sort.orEmpty(),
            titleEn = db?.en.orEmpty(),
        )
    }

    private data class DbTitles(val name: String, val sort: String, val en: String)

    /** GameDB's three titles for [serial], or null when it isn't in the database. */
    private fun dbTitles(serial: String): DbTitles? {
        val raw = runCatching { NativeApp.getTitlesForSerial(serial) }.getOrNull()
        if (raw.isNullOrEmpty()) return null
        // "<name>\n<name-sort>\n<name-en>" — split with a limit so a title can't lose a
        // trailing field, and tolerate a short string from an older core.
        val parts = raw.split('\n')
        val name = parts.getOrNull(0).orEmpty()
        if (name.isEmpty()) return null
        return DbTitles(name, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
    }

    private fun parseProbe(value: String?): Pair<String?, GamePlatform?> {
        if (value.isNullOrBlank()) return null to null
        val separator = value.indexOf(':')
        if (separator <= 0) return value to null
        return value.substring(separator + 1) to GamePlatform.fromKey(value.substring(0, separator))
    }

    private fun probeDocument(uri: Uri): String? = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        NativeApp.getGameSerialFromFd(descriptor.detachFd())
    }.getOrNull()

    private fun probeRaw(file: File): String? = runCatching {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        NativeApp.getGameSerialFromFd(descriptor.detachFd())
    }.getOrNull()

    /**
     * Read a PS3 disc's title ID, title and cover from the image itself.
     *
     * The inherited probe looks for a PS2 SYSTEM.CNF, so every PS3 ISO came back
     * with no serial -- and no serial meant no title, no compatibility entry and
     * no cover. This asks the core to mount the ISO and read PS3_GAME/PARAM.SFO,
     * extracting PS3_GAME/ICON0.PNG alongside it.
     *
     * Only for POSIX paths: the core opens the image by path, so a content:// URI
     * has nothing to hand it. That is not a real gap here -- the raw scan path is
     * the one that runs whenever all-files access is granted, which is required
     * for the emulator to read the disc at boot anyway.
     */
    private fun probeDisc(file: File): DiscInfo? {
        // One extraction per game: re-reading a 7 GB image on every rescan to
        // recover a PNG we already have would make each scan take minutes.
        val existing = discInfoCache[file.absolutePath]
        if (existing != null) return existing

        val raw = runCatching {
            RPCSX.instance.probeDiscInfo(file.absolutePath, DiscIcons.fileFor(PendingIcon).absolutePath)
        }.getOrNull()
        android.util.Log.i(ScanTag, "  probeDiscInfo('${file.name}') -> $raw")
        if (raw == null) return null

        val info = runCatching {
            val o = JSONObject(raw)
            val id = o.optString("titleId")
            if (id.isBlank()) return@runCatching null
            // The probe writes to a fixed staging name because it cannot know the
            // title ID until it has already parsed the SFO.
            if (o.optBoolean("icon")) {
                val staged = DiscIcons.fileFor(PendingIcon)
                val target = DiscIcons.fileFor(id)
                // renameTo answers false instead of throwing when the target already exists,
                // and the answer was discarded: a re-extraction for a title that already had
                // an icon silently kept the old file and left the staging one behind. Clear
                // the target first, and say so if it still fails -- a stale or empty icon must
                // not outlive the probe that was meant to replace it, because every reader
                // downstream treats "a file is there" as "the cover is good".
                if (staged.length() > 0L) {
                    target.delete()
                    if (!staged.renameTo(target)) {
                        android.util.Log.w(ScanTag, "  could not place disc icon for $id")
                        staged.delete()
                    }
                } else {
                    android.util.Log.w(ScanTag, "  probe claimed an icon for $id, staged 0 bytes")
                    staged.delete()
                }
            }
            DiscInfo(id, o.optString("title"))
        }.getOrNull() ?: return null

        discInfoCache[file.absolutePath] = info
        android.util.Log.i(ScanTag, "  disc probe: ${file.name} -> ${info.titleId} '${info.title}'")
        return info
    }

    private data class DiscInfo(val titleId: String, val title: String)

    private val discInfoCache = HashMap<String, DiscInfo>()

    private fun saveCache(directories: List<String>, games: List<GameInfo>) {
        val array = JSONArray()
        games.forEach { game ->
            array.put(JSONObject().apply {
                put("uri", game.uri.toString())
                put("title", game.title)
                put("serial", game.serial ?: JSONObject.NULL)
                put("compat", game.compatibility)
                put("ext", game.extension)
                put("platform", game.platform.key)
                put("titleSort", game.titleSort)
                put("titleEn", game.titleEn)
                put("locked", game.locked)
            })
        }
        MainActivityRuntime.prefs.edit {
            putString("gamesCacheKey", cacheKey(directories))
                .putString("gamesCache", array.toString())
            }
    }

    private fun canUseRawStorage(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    data class CachedLibrary(val key: String?, val games: List<GameInfo>)

    private companion object {
        /** v2: PS3 title ID + title + ICON0.PNG read from the disc's PARAM.SFO.
         *  v5: folder-format games (JB folder / installed game folder).
         *  v6: PARAM.SFO CATEGORY read, to drop game-data installs.
         *  v7: licence-locked state, asked of the core per installed title.
         *  v8: PS3 serials normalised (BLUS-30917 -> BLUS30917). The scanner does not
         *      extract a NEW field here, it changes the VALUE of one it already stored, which
         *      has the same staleness signature: without a bump an existing install keeps
         *      serving the cached hyphenated ids and never rescans, so the repair never
         *      reaches the libraries that need it. */
        /** PS3 disc ids are B***, PSN ids N***, both four letters and five digits. */
        val ps3SerialRegex = Regex("^[BN][A-Z]{3}[0-9]{5}$")

        const val ScanSchemaVersion = 8
        const val ScanTag = "ARMSX3-Scan"
        /** Staging name for an extracted icon, renamed once the title ID is known. */
        const val PendingIcon = "__pending"
        const val MaxScanDepth = 12
        val probeExtensions = setOf("iso", "bin", "chd", "img", "mdf", "nrg", "dump")
    }
}

/**
 * The one PARAM.SFO field a caller asks for, by key.
 *
 * Grown out of the scanner's CATEGORY reader rather than added beside it: the package screen
 * needs TITLE for the same files, and a second copy of this parsing would be a second place to
 * get the 16-byte index-entry layout wrong.
 *
 * Every failure -- unreadable file, wrong magic, an offset that points past the end -- answers
 * null. A truncated or hand-edited SFO must never take a caller down with it: the scanner uses
 * the answer to decide whether to LIST a game, and the package screen to decide what to CALL
 * one, and both have a sane fallback while an exception has none.
 */
internal object ParamSfo {
    fun string(sfo: File, key: String): String? =
        runCatching { string(sfo.readBytes(), key) }.getOrNull()

    fun string(bytes: ByteArray, key: String): String? {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // "\0PSF", then key-table start, data-table start, entry count.
        if (bytes.size < 20 || buffer.getInt(0) != 0x46535000) return null
        val keyTable = buffer.getInt(8)
        val dataTable = buffer.getInt(12)
        for (index in 0 until buffer.getInt(16)) {
            val entry = 20 + index * 16
            if (entry + 16 > bytes.size) break
            val keyStart = keyTable + (buffer.getShort(entry).toInt() and 0xFFFF)
            if (keyStart < 0 || keyStart >= bytes.size) break
            var keyEnd = keyStart
            while (keyEnd < bytes.size && bytes[keyEnd] != 0.toByte()) keyEnd++
            if (String(bytes, keyStart, keyEnd - keyStart) != key) continue
            val dataStart = dataTable + buffer.getInt(entry + 12)
            val dataLength = buffer.getInt(entry + 4)
            if (dataStart < 0 || dataLength < 0 || dataStart + dataLength > bytes.size) break
            // Values are UTF-8 and padded with NULs to their declared maximum length.
            return String(bytes, dataStart, dataLength, Charsets.UTF_8).trimEnd('\u0000', ' ')
        }
        return null
    }
}

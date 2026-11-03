package com.armsx2.ui.xmb

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.LibraryMusic
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.core.ARMSX3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The music the console's Music column sees: the music folder (ARMSX3's data folder, music/) and
 * the folders added with Add Folder, whose songs are played where they are rather than copied in.
 * Its songs with their tags (title, artist, album, track number), gathered into albums, and each
 * album's cover.
 *
 * A song is known everywhere (the player's queue, the playlists, the index) by one string: its
 * path in the music folder, or its content Uri in an added folder.
 *
 * Tags are read once per song with MediaMetadataRetriever and kept in an index in the cache, keyed
 * by song, size and time, since reading a whole library again on every visit takes seconds.
 *
 * A cover is, in order: an image beside the songs in their folder (cover.jpg, folder.jpg and the
 * like, as ripped albums come, else the folder's biggest picture of any name), then the picture
 * inside a song (MP3, M4A, FLAC and Ogg files all carry one), saved small in the cache; else there
 * is none and the firmware's album icon stands in.
 *
 * Songs with an album tag are one album per album name and album artist, or per folder when there
 * is no album artist, so two albums of the same name by different people stay apart. Songs in a
 * folder of their own with no album tag are an album named after the folder. The rest, loose in
 * the music folder, are listed on their own.
 */
object XmbMusicLibrary {
    private val AUDIO get() = XmbMediaFormats.AUDIO
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp")

    /** The names a cover goes by beside the songs, best first. */
    private val COVERS = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")

    private const val FoldersKey = "xmb.music.folders"

    fun folder(): File = File(ARMSX3.rootDirectory, "music").apply { mkdirs() }

    /** A folder added with Add Folder, by its tree Uri. */
    class Folder(val uri: Uri, val name: String)

    /** The folders added with Add Folder. */
    val folders = mutableStateOf<List<Folder>>(emptyList())
    private var foldersLoaded = false

    class Track(
        /** Where the song is: its path, or its content Uri in an added folder. */
        val id: String,
        /** Its file name. */
        val name: String,
        /** The folder it is in (a path or a content Uri); null loose in the music folder itself. */
        val folder: String?,
        val size: Long,
        val modified: Long,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val number: Int,
        val disc: Int,
        val year: String?,
        val genre: String?,
        val durationMs: Long,
        /** A picture inside the file. */
        val picture: Boolean,
    ) {
        /** Played where it is, from an added folder: not the app's to delete. */
        val inPlace: Boolean get() = !owns(id)
    }

    /** A song in the music folder itself, the app's own copy, rather than one played where it is. */
    fun owns(id: String): Boolean = !LibraryMusic.isDocument(id) && id.startsWith(folder().absolutePath + File.separator)

    /** [artist] is null for an album of several artists' songs (the console's Various Artists). */
    class Album(val key: String, val title: String, val artist: String?, val tracks: List<Track>, val cover: File?)

    /**
     * [looseCovers]: the covers of the loose songs that have one, by song. [chosen]: the covers
     * given with Change Cover, by [coverKey].
     */
    class Library(val albums: List<Album>, val loose: List<Track>, private val looseCovers: Map<String, File>, private val chosen: Set<String>) {
        val all: List<Track> = albums.flatMap { it.tracks } + loose
        val byId: Map<String, Track> = all.associateBy { it.id }
        private val albumOf: Map<String, Album> = albums.flatMap { a -> a.tracks.map { it.id to a } }.toMap()
        fun albumOf(id: String): Album? = albumOf[id]

        /** The cover to show for a song: its album's, else its own picture. */
        fun coverOf(id: String): File? = albumOf(id)?.cover ?: looseCovers[id]

        /** Whose cover Change Cover changes for a song: its album's, all its songs', else the song's own. */
        fun coverKey(id: String): String = albumOf(id)?.let(::albumCoverKey) ?: "song:$id"

        /** Whether [key]'s cover is one given with Change Cover. */
        fun chosen(key: String): Boolean = key in chosen

        /** The songs of the added folder [tree], album by album. */
        fun inFolder(tree: Uri): List<Track> {
            val prefix = if (tree.scheme == "file") tree.path.orEmpty().trimEnd('/') + "/" else "$tree/document/"
            return all.filter { it.id.startsWith(prefix) }
        }
    }

    fun albumCoverKey(a: Album) = "album:${a.key}"

    /** The last scan, for a screen that only looks a song up (Now Playing). */
    val library = mutableStateOf<Library?>(null)

    /** A song or picture as a folder lists it, before its tags are read. */
    private class Found(val id: String, val name: String, val folder: String?, val size: Long, val modified: Long)

    /** Every song in the music folder, its subfolders' too. */
    private fun localSongs(root: File): List<Found> =
        root.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }
            .sortedBy { it.path.lowercase() }
            .map { f ->
                val dir = f.parentFile?.takeIf { it.absolutePath != root.absolutePath }
                Found(f.absolutePath, f.name, dir?.absolutePath, f.length(), f.lastModified())
            }
            .toList()

    /** A folder added from ARMSX3's own browser, read directly, four folders deep. Every song keeps its folder. */
    private fun pathSongs(dir: File): List<Found> =
        dir.walkTopDown().maxDepth(5).onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }
            .sortedBy { it.path.lowercase() }
            .map { f -> Found(f.absolutePath, f.name, f.parentFile?.absolutePath, f.length(), f.lastModified()) }
            .toList()

    /**
     * An added folder's songs, and its pictures by folder, four folders deep: one query of the
     * document provider a folder, as the system picker lists them.
     */
    private fun documents(context: Context, tree: Uri, docId: String, depth: Int, songs: MutableList<Found>, images: MutableMap<String, MutableList<Found>>) {
        if (depth > 4) return
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<String>()
        runCatching {
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += id
                        continue
                    }
                    val size = c.getLong(3)
                    if (size <= 0) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val found = Found(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, folder, size, c.getLong(4))
                    when (ext) {
                        in AUDIO -> songs += found
                        in IMAGES -> images.getOrPut(folder) { ArrayList() } += found
                    }
                }
            }
        }
        dirs.forEach { documents(context, tree, it, depth + 1, songs, images) }
    }

    fun loadFolders() {
        if (foldersLoaded) return
        foldersLoaded = true
        folders.value = runCatching {
            val a = JSONArray(MainActivityRuntime.prefs.getString(FoldersKey, "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Folder(Uri.parse(o.getString("uri")), o.optString("name")) } }
        }.getOrDefault(emptyList())
    }

    private fun saveFolders() {
        MainActivityRuntime.prefs.edit {
            putString(FoldersKey, JSONArray(folders.value.map { JSONObject().put("uri", it.uri.toString()).put("name", it.name) }).toString())
        }
    }

    /**
     * Keep a picked folder and play its songs from where they are: the system picker's, by its grant,
     * or one from ARMSX3's own browser, by its path (a file:// Uri).
     */
    fun addFolder(context: Context, uri: Uri) {
        loadFolders()
        val path = uri.takeIf { it.scheme == "file" }?.path
        if (path == null) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (folders.value.any { it.uri == uri }) return
        val name = path?.let { File(it).name }
            ?: androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        folders.value = folders.value + Folder(uri, name)
        saveFolders()
    }

    /** Stop listing a folder; its songs stay where they are. The grant goes unless Video still uses it. */
    fun removeFolder(context: Context, uri: Uri) {
        if (XmbVideos.folders.value.none { it.uri == uri }) {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        folders.value = folders.value.filter { it.uri != uri }
        saveFolders()
    }

    /** Read the music: tags from the index where a song has not changed, the rest afresh. IO thread. */
    @Synchronized
    fun scan(context: Context): Library {
        loadFolders()
        val root = folder()
        val found = ArrayList(localSongs(root))
        val images = HashMap<String, MutableList<Found>>()
        for (f in folders.value) {
            if (f.uri.scheme == "file") {
                f.uri.path?.let { found += pathSongs(File(it)) }
                continue
            }
            val top = runCatching { DocumentsContract.getTreeDocumentId(f.uri) }.getOrNull() ?: continue
            val songs = ArrayList<Found>()
            documents(context, f.uri, top, 0, songs, images)
            found += songs.sortedBy { it.id.lowercase() }
        }
        val index = loadIndex(context)
        var changed = false
        // A folder added inside another, or inside the music folder, lists its songs once.
        val tracks = found.distinctBy { it.id }.map { f ->
            val known = index.optJSONObject(f.id)
            if (known != null && known.optLong("s") == f.size && known.optLong("m") == f.modified) {
                fromJson(f, known)
            } else {
                changed = true
                read(context, f).also { index.put(f.id, toJson(it)) }
            }
        }
        // Songs that have gone leave the index too.
        val present = tracks.mapTo(HashSet()) { it.id }
        index.keys().asSequence().toList().filter { it !in present }.forEach {
            index.remove(it)
            changed = true
        }
        if (changed) saveIndex(context, index)

        val groups = LinkedHashMap<String, MutableList<Track>>()
        val loose = ArrayList<Track>()
        for (t in tracks) {
            val key = when {
                !t.album.isNullOrBlank() -> "tag:" + t.album.lowercase() + "\u0000" + (t.albumArtist?.lowercase() ?: t.folder.orEmpty())
                t.folder != null -> "dir:" + t.folder
                else -> null
            }
            if (key == null) loose += t else groups.getOrPut(key) { ArrayList() } += t
        }
        val chosen = chosenCovers(context)
        val albums = groups.map { (key, list) ->
            val songs = list.sortedWith(compareBy<Track>({ it.disc }, { it.number }, { it.title.lowercase() }))
            val title = songs.firstNotNullOfOrNull { it.album } ?: folderName(songs.first())
            val artists = songs.mapNotNull { it.artist }.distinct()
            val artist = songs.firstNotNullOfOrNull { it.albumArtist } ?: artists.singleOrNull()
            val albumKey = sha1(key)
            Album(albumKey, title, artist, songs, chosen[sha1("album:$albumKey")] ?: coverFor(context, songs, images))
        }.sortedBy { it.title.lowercase() }
        val looseCovers = loose.mapNotNull { t ->
            (chosen[sha1("song:${t.id}")] ?: t.takeIf { it.picture }?.let { picture(context, it) })?.let { t.id to it }
        }.toMap()
        val chosenKeys = (albums.map(::albumCoverKey) + loose.map { "song:${it.id}" }).filterTo(HashSet()) { sha1(it) in chosen }
        return Library(albums, loose.sortedBy { it.title.lowercase() }, looseCovers, chosenKeys).also { library.value = it }
    }

    /** An untagged album's name: its folder's. */
    private fun folderName(t: Track): String {
        val folder = t.folder ?: return t.title
        if (!LibraryMusic.isDocument(folder)) return File(folder).name
        val id = runCatching { DocumentsContract.getDocumentId(Uri.parse(folder)) }.getOrNull() ?: return t.title
        return id.substringAfterLast('/').substringAfterLast(':').ifEmpty { t.title }
    }

    private fun retriever(context: Context, id: String) = MediaMetadataRetriever().apply {
        if (LibraryMusic.isDocument(id)) setDataSource(context, Uri.parse(id)) else setDataSource(id)
    }

    private fun read(context: Context, f: Found): Track {
        var r: MediaMetadataRetriever? = null
        return try {
            val m = retriever(context, f.id).also { r = it }
            fun s(key: Int) = m.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: Int) = s(key)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            Track(
                id = f.id,
                name = f.name,
                folder = f.folder,
                size = f.size,
                modified = f.modified,
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: f.name.substringBeforeLast('.'),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                number = number(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = number(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = s(MediaMetadataRetriever.METADATA_KEY_YEAR),
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                picture = runCatching { m.embeddedPicture != null }.getOrDefault(false),
            )
        } catch (_: Exception) {
            // Not readable as audio here (a MIDI file, a damaged one): its name, and nothing else.
            Track(f.id, f.name, f.folder, f.size, f.modified, f.name.substringBeforeLast('.'), null, null, null, 0, 0, null, null, 0L, false)
        } finally {
            runCatching { r?.release() }
        }
    }

    private fun toJson(t: Track) = JSONObject().apply {
        put("s", t.size)
        put("m", t.modified)
        put("t", t.title)
        t.artist?.let { put("a", it) }
        t.album?.let { put("al", it) }
        t.albumArtist?.let { put("aa", it) }
        put("n", t.number)
        put("d", t.disc)
        t.year?.let { put("y", it) }
        t.genre?.let { put("g", it) }
        put("ms", t.durationMs)
        put("p", t.picture)
    }

    private fun fromJson(f: Found, o: JSONObject) = Track(
        id = f.id,
        name = f.name,
        folder = f.folder,
        size = f.size,
        modified = f.modified,
        title = o.optString("t").ifEmpty { f.name.substringBeforeLast('.') },
        artist = o.optString("a").ifEmpty { null },
        album = o.optString("al").ifEmpty { null },
        albumArtist = o.optString("aa").ifEmpty { null },
        number = o.optInt("n"),
        disc = o.optInt("d"),
        year = o.optString("y").ifEmpty { null },
        genre = o.optString("g").ifEmpty { null },
        durationMs = o.optLong("ms"),
        picture = o.optBoolean("p"),
    )

    private fun cacheDir(context: Context) = File(context.cacheDir, "xmb-music").apply { mkdirs() }

    private fun loadIndex(context: Context): JSONObject =
        runCatching { JSONObject(File(cacheDir(context), "index.json").readText()) }.getOrElse { JSONObject() }

    private fun saveIndex(context: Context, index: JSONObject) {
        runCatching {
            val file = File(cacheDir(context), "index.json")
            val tmp = File(file.path + ".tmp")
            tmp.writeText(index.toString())
            tmp.renameTo(file)
        }
    }

    /** An album's cover: an image beside its songs (not loose in the music folder itself), else one inside a song. */
    private fun coverFor(context: Context, songs: List<Track>, images: Map<String, List<Found>>): File? {
        songs.mapNotNull { it.folder }.distinct().forEach { folder ->
            if (LibraryMusic.isDocument(folder)) {
                pick(images[folder].orEmpty(), { it.name }, { it.size })?.let { doc -> small(context, doc)?.let { return it } }
            } else {
                File(folder).listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGES && it.length() > 0 }
                    ?.let { files -> pick(files, { it.name }, { it.length() }) }?.let { return it }
            }
        }
        return songs.firstOrNull { it.picture }?.let { picture(context, it) }
    }

    /** The cover among a folder's pictures: by name, then Windows Media Player's AlbumArt_{GUID}_Large, then the biggest. */
    private fun <T> pick(images: List<T>, name: (T) -> String, size: (T) -> Long): T? {
        if (images.isEmpty()) return null
        fun base(t: T) = name(t).substringBeforeLast('.').lowercase()
        for (cover in COVERS) images.firstOrNull { base(it) == cover }?.let { return it }
        // Any name will do: of a cover, a booklet page and a disc scan, the front is the likeliest.
        return images.firstOrNull { base(it).startsWith("albumart") } ?: images.maxByOrNull(size)
    }

    /** A picture in an added folder, saved small in the cache to be drawn from. IO thread. */
    private fun small(context: Context, doc: Found): File? {
        val out = art(context, "${doc.id}|${doc.size}|${doc.modified}")
        if (out.length() > 0) return out
        val bytes = runCatching { context.contentResolver.openInputStream(Uri.parse(doc.id))?.use { it.readBytes() } }.getOrNull() ?: return null
        return save(bytes, out)
    }

    private fun art(context: Context, key: String) = File(File(cacheDir(context), "art").apply { mkdirs() }, sha1(key) + ".jpg")

    /**
     * The picture inside [track], saved once in the cache no larger than 640 px and returned from
     * there afterwards; null when it has none. IO thread.
     */
    fun picture(context: Context, track: Track): File? {
        val out = art(context, "${track.id}|${track.size}|${track.modified}")
        if (out.length() > 0) return out
        val bytes = runCatching {
            retriever(context, track.id).let { r ->
                try {
                    r.embeddedPicture
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrNull() ?: return null
        return save(bytes, out)
    }

    /** Covers given with Change Cover, kept with the app rather than in the cache: <key's hash>-<time>.jpg. */
    private fun chosenDir(context: Context) = File(context.filesDir, "xmb-music-covers").apply { mkdirs() }

    /** The covers given with Change Cover, by their key's hash, the newest of each. */
    private fun chosenCovers(context: Context): Map<String, File> =
        chosenDir(context).listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
            .groupBy { it.name.substringBefore('-') }
            .mapValues { (_, files) -> files.maxBy { it.lastModified() } }

    /**
     * Make a picture the cover for [key] (see [Library.coverKey]: an album's, so all its songs'), kept
     * no larger than 1024 px. Each is a file of its own, so nothing goes on showing the one before. IO thread.
     */
    fun setCover(context: Context, key: String, bytes: ByteArray): Boolean {
        val id = sha1(key)
        val dir = chosenDir(context)
        val out = save(bytes, File(dir, "$id-${System.currentTimeMillis()}.jpg"), 1024) ?: return false
        dir.listFiles().orEmpty().filter { it.name.startsWith("$id-") && it != out }.forEach { it.delete() }
        return true
    }

    /** Back to the cover the songs come with. */
    fun clearCover(context: Context, key: String) {
        val id = sha1(key)
        chosenDir(context).listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
    }

    private fun save(bytes: ByteArray, out: File, largest: Int = 640): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= largest) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return runCatching {
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(out)
            out
        }.getOrNull().also { bitmap.recycle() }
    }

    /** Copy picked songs into the folder, and unpack a picked .zip there as an album; how many songs came in. IO thread. */
    fun import(context: Context, uris: List<Uri>): Int = uris.sumOf { uri ->
        val picked = pickedName(context, uri)
        if (picked != null && picked.endsWith(".zip", ignoreCase = true)) {
            return@sumOf importZip(picked, null) { context.contentResolver.openInputStream(uri) }
        }
        val copied = runCatching {
            val name = picked?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "track-${System.currentTimeMillis()}.mp3"
            var target = File(folder(), name)
            var n = 1
            while (target.exists()) target = File(folder(), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "mp3")}")
            context.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } } != null &&
                target.length() > 0
        }.getOrDefault(false)
        if (copied) 1 else 0
    }

    /** Copy songs taken in ARMSX3's own browser into the folder, and unpack a .zip there as an album; how many songs came in. IO thread. */
    fun importFiles(files: List<File>): Int = files.sumOf { f ->
        if (f.extension.equals("zip", ignoreCase = true)) return@sumOf importZip(f.name, f) { f.inputStream() }
        val name = f.name
        var target = File(folder(), name)
        var n = 1
        while (target.exists()) target = File(folder(), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "mp3")}")
        val copied = runCatching { f.inputStream().use { ins -> target.outputStream().use { ins.copyTo(it) } } }.isSuccess && target.length() > 0
        if (!copied) target.delete()
        if (copied) 1 else 0
    }

    /**
     * Unpack a .zip of an album (or a few) into the music folder, under the zip's name: its songs and
     * pictures in their folders, a folder the whole zip sits in opened out. Names are read as UTF-8,
     * and again as the old DOS code page when that cannot read them. [file] is read through its
     * index, a picked document as a stream from [open]. How many songs came in. IO thread.
     */
    fun importZip(name: String, file: File?, open: () -> java.io.InputStream?): Int {
        val base = name.substringBeforeLast('.').replace('/', '_').trim().ifEmpty { "Album" }
        val dos = runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)
        for (charset in listOf(Charsets.UTF_8, dos)) {
            var target = File(folder(), base)
            var n = 1
            while (target.exists()) target = File(folder(), "$base (${n++})")
            var songs = 0
            val read = runCatching {
                eachInZip(file, open, charset) { entry, ins ->
                    val parts = zipPath(entry) ?: return@eachInZip
                    val out = File(target, parts.joinToString(File.separator))
                    out.parentFile?.mkdirs()
                    try {
                        out.outputStream().use { ins.copyTo(it) }
                    } catch (e: java.io.IOException) {
                        out.delete()
                        throw e
                    }
                    if (out.length() == 0L) out.delete() else if (out.extension.lowercase() in AUDIO) songs++
                }
            }
            // A name UTF-8 cannot read stops it (ZipFile reports it as a bad entry): all of it again in the other.
            if (charset == Charsets.UTF_8 && read.isFailure) {
                target.deleteRecursively()
                continue
            }
            if (songs == 0) {
                target.deleteRecursively()
                return 0
            }
            openOut(target)
            return songs
        }
        return 0
    }

    /** A zip entry's folders and name under the album; null for one left out: outside it, hidden, or neither a song nor a picture. */
    private fun zipPath(entry: String): List<String>? {
        val parts = entry.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.size > 5 || parts.any { it == ".." || it.startsWith(".") || it == "__MACOSX" }) return null
        val ext = parts.last().substringAfterLast('.', "").lowercase()
        return parts.takeIf { ext in AUDIO || ext in IMAGES }
    }

    private fun eachInZip(file: File?, open: () -> java.io.InputStream?, charset: java.nio.charset.Charset, each: (String, java.io.InputStream) -> Unit) {
        if (file != null) {
            java.util.zip.ZipFile(file, charset).use { zip ->
                for (e in zip.entries()) if (!e.isDirectory) zip.getInputStream(e).use { each(e.name, it) }
            }
            return
        }
        java.util.zip.ZipInputStream((open() ?: throw java.io.FileNotFoundException()).buffered(), charset).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) each(e.name, zip)
            }
        }
    }

    /** An album unpacked into [dir] that sits in one folder of its own: that folder's contents moved up into [dir]. */
    private fun openOut(dir: File) {
        val only = dir.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: return
        val moved = File(dir, ".unpacking")
        if (!only.renameTo(moved)) return
        moved.listFiles().orEmpty().forEach { it.renameTo(File(dir, it.name)) }
        moved.delete()
    }

    /** Copy a folder from ARMSX3's own browser in, as [importFolder] does a picked one. How many songs came in. IO thread. */
    fun importDir(dir: File): Int {
        val root = folder()
        // Not the music folder into itself.
        if (root.canonicalPath == dir.canonicalPath || root.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return 0
        val name = dir.name.ifBlank { "Album" }
        var target = File(root, name)
        var n = 1
        while (target.exists()) target = File(root, "$name (${n++})")
        val songs = copyDir(dir, target, 0)
        if (songs == 0) target.deleteRecursively()
        return songs
    }

    private fun copyDir(dir: File, target: File, depth: Int): Int {
        if (depth > 3) return 0
        var songs = 0
        for (f in dir.listFiles().orEmpty()) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                songs += copyDir(f, File(target, f.name), depth + 1)
                continue
            }
            val ext = f.extension.lowercase()
            val song = ext in AUDIO
            if (!song && ext !in IMAGES) continue
            target.mkdirs()
            val out = File(target, f.name)
            val ok = runCatching { f.inputStream().use { ins -> out.outputStream().use { ins.copyTo(it) } } }.isSuccess
            if (ok && out.length() > 0) {
                if (song) songs++
            } else {
                out.delete()
            }
        }
        return songs
    }

    /**
     * Copy a picked folder (an album, a disc's worth, or a folder of albums) into the music folder,
     * as it is: its folders, its songs and its cover images. How many songs came in. IO thread.
     */
    fun importFolder(context: Context, tree: Uri): Int {
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree) ?: return 0
        val name = dir.name?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "Album"
        var target = File(folder(), name)
        var n = 1
        while (target.exists()) target = File(folder(), "$name (${n++})")
        val songs = copyTree(context, dir, target, 0)
        if (songs == 0) target.deleteRecursively()
        return songs
    }

    private fun copyTree(context: Context, dir: androidx.documentfile.provider.DocumentFile, target: File, depth: Int): Int {
        if (depth > 3) return 0
        var songs = 0
        for (f in dir.listFiles()) {
            val name = f.name?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: continue
            if (f.isDirectory) {
                songs += copyTree(context, f, File(target, name), depth + 1)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            val song = ext in AUDIO
            if (!song && ext !in IMAGES) continue
            target.mkdirs()
            val out = File(target, name)
            val ok = runCatching {
                context.contentResolver.openInputStream(f.uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } } != null
            }.getOrDefault(false)
            if (ok && out.length() > 0) {
                if (song) songs++
            } else {
                out.delete()
            }
        }
        return songs
    }

    /** The console's icon for a song with no cover, by its format. */
    fun formatIcon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "music_tex_mp3"
        "m4a", "aac", "3gp" -> "music_tex_aac"
        "wav" -> "music_tex_wav"
        else -> "music_tex_default"
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * Songs Android cannot play itself (ALAC, AIFF, WMA), decoded once by the core's FFmpeg to a WAV in
 * the cache, which the player plays in their place. The last few are kept and the rest deleted: a
 * WAV is ten megabytes a minute.
 */
object XmbDecodedSongs {
    private const val KEEP = 3

    private fun dir(context: Context) = File(context.cacheDir, "xmb-music/decoded").apply { mkdirs() }

    /** A song's size and time, so a changed file is decoded again: from the file, or for a document its provider. */
    fun stamp(context: Context, song: String): String =
        if (!LibraryMusic.isDocument(song)) File(song).let { "${it.length()}|${it.lastModified()}" }
        else runCatching {
            val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(Uri.parse(song), columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) "${c.getLong(0)}|${c.getLong(1)}" else null
            }
        }.getOrNull().orEmpty()

    private fun file(context: Context, song: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest("$song|${stamp(context, song)}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(context), "$key.wav")
    }

    /** [song]'s WAV, when it has been decoded. */
    fun decoded(context: Context, song: String): File? = file(context, song).takeIf { it.length() > 44 }

    /** Decode [song] now; its WAV, or null when FFmpeg cannot play it either. Slow: IO thread. */
    fun decode(context: Context, song: String): File? {
        val out = file(context, song)
        if (out.length() > 44) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        val tmp = File(out.path + ".tmp")
        // A document reaches FFmpeg as the descriptor its provider opens, which FFmpeg reads as a file.
        val pfd = if (LibraryMusic.isDocument(song)) runCatching { context.contentResolver.openFileDescriptor(Uri.parse(song), "r") }.getOrNull() else null
        if (LibraryMusic.isDocument(song) && pfd == null) return null
        val ok = try {
            runCatching { ARMSX3.instance.xmbDecodeSong(pfd?.let { "/proc/self/fd/${it.fd}" } ?: song, tmp.path) }
                .onFailure { android.util.Log.w("XmbDecodedSongs", "decode call failed", it) }.getOrDefault(false)
        } finally {
            runCatching { pfd?.close() }
        }
        if (!ok || !tmp.renameTo(out)) {
            android.util.Log.w("XmbDecodedSongs", "could not decode ${LibraryMusic.songName(song)}")
            tmp.delete()
            return null
        }
        dir(context).listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return out
    }
}

/**
 * A length the console's way, from its own words when the firmware has them: 3'32'', or
 * 1:02'05'' past the hour. [ms] 0 is empty.
 */
internal fun xmbLength(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val two = { v: Long -> v.toString().padStart(2, '0') }
    return if (h > 0) {
        (XmbText.find("explore_plugin_full", "msg_time_num2") ?: "%1:%2'%3''")
            .replace("%1", h.toString()).replace("%2", two(m)).replace("%3", two(s))
    } else {
        (XmbText.find("explore_plugin_full", "msg_time_num3") ?: "%1'%2''")
            .replace("%1", m.toString()).replace("%2", two(s))
    }
}

/** A time on a player's clock: 2:47, or 1:02:05 past the hour. */
internal fun xmbClockTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

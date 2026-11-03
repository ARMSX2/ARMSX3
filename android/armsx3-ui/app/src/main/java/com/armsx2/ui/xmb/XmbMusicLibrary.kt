package com.armsx2.ui.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import com.armsx3.core.ARMSX3
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The music folder (ARMSX3's data folder, music/) as the console's Music column sees it: its songs
 * with their tags (title, artist, album, track number), gathered into albums, and each album's cover.
 *
 * Tags are read once per song with MediaMetadataRetriever and kept in an index in the cache, keyed
 * by path, size and time, since reading a whole library again on every visit takes seconds.
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

    fun folder(): File = File(ARMSX3.rootDirectory, "music").apply { mkdirs() }

    /** Every song in the music folder, its subfolders' too. */
    fun tracks(): List<File> =
        folder().walkTopDown().maxDepth(4)
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }
            .sortedBy { it.path.lowercase() }
            .toList()

    class Track(
        val file: File,
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
    )

    /** [artist] is null for an album of several artists' songs (the console's Various Artists). */
    class Album(val key: String, val title: String, val artist: String?, val tracks: List<Track>, val cover: File?)

    /** [looseCovers]: the pictures inside the loose songs that have one, by path. */
    class Library(val albums: List<Album>, val loose: List<Track>, private val looseCovers: Map<String, File>) {
        val byPath: Map<String, Track> = (albums.flatMap { it.tracks } + loose).associateBy { it.file.absolutePath }
        private val albumOf: Map<String, Album> = albums.flatMap { a -> a.tracks.map { it.file.absolutePath to a } }.toMap()
        fun albumOf(file: File): Album? = albumOf[file.absolutePath]

        /** The cover to show for a song: its album's, else its own picture. */
        fun coverOf(file: File): File? = albumOf(file)?.cover ?: looseCovers[file.absolutePath]
    }

    /** The last scan, for a screen that only looks a song up (Now Playing). */
    val library = mutableStateOf<Library?>(null)

    /** Read the music folder: tags from the index where a song has not changed, the rest afresh. IO thread. */
    @Synchronized
    fun scan(context: Context): Library {
        val root = folder()
        val index = loadIndex(context)
        var changed = false
        val tracks = tracks().map { file ->
            val key = file.absolutePath
            val known = index.optJSONObject(key)
            if (known != null && known.optLong("s") == file.length() && known.optLong("m") == file.lastModified()) {
                fromJson(file, known)
            } else {
                changed = true
                read(file).also { index.put(key, toJson(it)) }
            }
        }
        // Songs that have gone leave the index too.
        val present = tracks.mapTo(HashSet()) { it.file.absolutePath }
        index.keys().asSequence().toList().filter { it !in present }.forEach {
            index.remove(it)
            changed = true
        }
        if (changed) saveIndex(context, index)

        val groups = LinkedHashMap<String, MutableList<Track>>()
        val loose = ArrayList<Track>()
        for (t in tracks) {
            val dir = t.file.parentFile
            val inFolder = dir != null && dir.absolutePath != root.absolutePath
            val key = when {
                !t.album.isNullOrBlank() ->
                    "tag:" + t.album.lowercase() + "\u0000" + (t.albumArtist?.lowercase() ?: dir?.absolutePath.orEmpty())
                inFolder -> "dir:" + dir!!.absolutePath
                else -> null
            }
            if (key == null) loose += t else groups.getOrPut(key) { ArrayList() } += t
        }
        val albums = groups.map { (key, list) ->
            val songs = list.sortedWith(compareBy<Track>({ it.disc }, { it.number }, { it.title.lowercase() }))
            val title = songs.firstNotNullOfOrNull { it.album } ?: songs.first().file.parentFile?.name.orEmpty()
            val artists = songs.mapNotNull { it.artist }.distinct()
            val artist = songs.firstNotNullOfOrNull { it.albumArtist } ?: artists.singleOrNull()
            Album(sha1(key), title, artist, songs, coverFor(context, songs, root))
        }.sortedBy { it.title.lowercase() }
        val looseCovers = loose.filter { it.picture }.mapNotNull { t -> picture(context, t.file)?.let { t.file.absolutePath to it } }.toMap()
        return Library(albums, loose.sortedBy { it.title.lowercase() }, looseCovers).also { library.value = it }
    }

    private fun read(file: File): Track {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            fun s(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: Int) = s(key)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            Track(
                file = file,
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension,
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                number = number(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = number(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = s(MediaMetadataRetriever.METADATA_KEY_YEAR),
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                picture = runCatching { r.embeddedPicture != null }.getOrDefault(false),
            )
        } catch (_: Exception) {
            // Not readable as audio here (a MIDI file, a damaged one): its name, and nothing else.
            Track(file, file.nameWithoutExtension, null, null, null, 0, 0, null, null, 0L, false)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun toJson(t: Track) = JSONObject().apply {
        put("s", t.file.length())
        put("m", t.file.lastModified())
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

    private fun fromJson(file: File, o: JSONObject) = Track(
        file = file,
        title = o.optString("t").ifEmpty { file.nameWithoutExtension },
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
    private fun coverFor(context: Context, songs: List<Track>, root: File): File? {
        songs.mapNotNull { it.file.parentFile }.distinct().filter { it.absolutePath != root.absolutePath }.forEach { dir ->
            folderImage(dir)?.let { return it }
        }
        return songs.firstOrNull { it.picture }?.let { picture(context, it.file) }
    }

    private fun folderImage(dir: File): File? {
        val images = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGES && it.length() > 0 } ?: return null
        for (name in COVERS) {
            images.firstOrNull { it.nameWithoutExtension.lowercase() == name }?.let { return it }
        }
        // Windows Media Player's AlbumArt_{GUID}_Large.jpg, else the folder's biggest picture: any
        // name will do, and of a cover, a booklet page and a disc scan, the front is the likeliest.
        return images.firstOrNull { it.nameWithoutExtension.lowercase().startsWith("albumart") } ?: images.maxByOrNull { it.length() }
    }

    /**
     * The picture inside [file], saved once in the cache no larger than 640 px and returned from
     * there afterwards; null when it has none. IO thread.
     */
    fun picture(context: Context, file: File): File? {
        val out = File(File(cacheDir(context), "art").apply { mkdirs() }, sha1("${file.absolutePath}|${file.length()}|${file.lastModified()}") + ".jpg")
        if (out.length() > 0) return out
        val bytes = runCatching {
            MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(file.absolutePath)
                    r.embeddedPicture
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return runCatching {
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(out)
            out
        }.getOrNull().also { bitmap.recycle() }
    }

    /** Copy picked files into the folder; how many came in. IO thread. */
    fun import(context: Context, uris: List<Uri>): Int = uris.count { uri ->
        runCatching {
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
                ?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "track-${System.currentTimeMillis()}.mp3"
            var target = File(folder(), name)
            var n = 1
            while (target.exists()) target = File(folder(), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "mp3")}")
            context.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } } != null &&
                target.length() > 0
        }.getOrDefault(false)
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
    fun formatIcon(file: File): String = when (file.extension.lowercase()) {
        "mp3" -> "music_tex_mp3"
        "m4a", "aac", "3gp" -> "music_tex_aac"
        "wav" -> "music_tex_wav"
        else -> "music_tex_default"
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
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

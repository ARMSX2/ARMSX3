package com.armsx2.ui.xmb

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.armsx2.LibraryMusic
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.ShaderScan
import com.armsx2.ui.common.findDirectory
import com.armsx2.ui.common.parentKeyWithin
import com.armsx2.ui.common.scanShaderPresets
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
import com.armsx3.core.ARMSX3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What the XMB's players play: the files each one lists, by extension. Android's own player
 * (MediaPlayer and the device's decoders) plays what it can; the rest goes to the core's FFmpeg: a
 * song is decoded once to a WAV the player plays (ALAC, AIFF, WMA; XmbDecodedSongs), a video plays
 * in src/xmb-player.cpp (AVI, MPEG, films with AC3 or DTS sound). A file neither can decode gets the
 * console's own "The data type is not supported."
 */
object XmbMediaFormats {
    val AUDIO = setOf(
        "mp3", "m4a", "m4b", "aac", "3gp", "flac", "ogg", "oga", "opus", "wav", "amr", "awb",
        "mka", "weba", "mid", "midi", "xmf", "mxmf", "rtttl", "rtx", "ota", "imy",
        "aif", "aiff", "aifc", "wma",
    )
    val VIDEO = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "ts", "m2ts", "mts", "mpg", "mpeg", "avi", "divx", "xvid", "vob")

    /** Containers Android cannot read at all: straight to FFmpeg. */
    private val FFMPEG_ONLY = setOf("aif", "aiff", "aifc", "wma", "avi", "divx", "xvid", "vob")

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO
    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO
    fun ffmpegOnly(name: String) = name.substringAfterLast('.', "").lowercase() in FFMPEG_ONLY

    /**
     * Whether Android's own player can play [uri] ([name] its file name): it reads the container and
     * has a decoder for the picture's format and the first sound's. An MKV of a film with DTS sound,
     * or an M4A of ALAC, reads but does not decode. Blocking: IO thread.
     */
    fun native(context: Context, uri: Uri, name: String): Boolean {
        if (ffmpegOnly(name)) return false
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val decoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }.flatMap { it.supportedTypes.map(String::lowercase) }.toSet()
            var video = false
            var audio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.lowercase() ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if ((isVideo && !video) || (isAudio && !audio)) {
                    if (mime !in decoders) return false
                    if (isVideo) video = true else audio = true
                }
            }
            video || audio
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}

/**
 * The Video column's videos: where they are, their first frames, lengths, and where each was left.
 *
 * They come from ARMSX3's video folder (the data folder's video/, filled by Import Video), from each
 * removable storage device that is in (a USB drive or an SD card, as the console lists a USB device),
 * and from folders added with Add Folder (the phone's Movies or Downloads, say). A device is read
 * directly where the app may (the GitHub build with all-files access); otherwise, and for an added
 * folder, through the system's folder picker, whose grant is kept, so no permission is asked for.
 */
object XmbVideos {
    private const val FoldersKey = "xmb.video.folders"
    private const val ResumeKey = "xmb.video.resume"
    private const val ScreenModeKey = "xmb.video.screen"
    private const val AspectKey = "xmb.video.aspect"
    private const val ShaderKey = "xmb.video.shader"

    /**
     * A file or a folder: on disk ([file]), or in a granted folder ([tree] and its [docId]). [key]
     * identifies it everywhere.
     */
    class Entry(val name: String, val uri: Uri, val file: File?, val tree: Uri?, val docId: String?, val dir: Boolean, val modified: Long) {
        val key: String get() = uri.toString()
        val title: String get() = if (dir) name else name.substringBeforeLast('.')
    }

    /** A removable device in now: read [directory] as files, or through a grant in [folders]. */
    class Volume(val title: String, val usb: Boolean, val directory: File?, val readable: Boolean, val intent: Intent?)

    /** A folder added with Add Folder, or a device granted through the picker. */
    class Folder(val uri: Uri, val name: String)

    class Meta(val durationMs: Long, val thumb: ImageBitmap?)

    val folders = mutableStateOf<List<Folder>>(emptyList())
    private val listings = mutableStateMapOf<String, List<Entry>>()
    private val metas = mutableStateMapOf<String, Meta>()
    private val loading = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun folder(): File = File(ARMSX3.rootDirectory, "video").apply { mkdirs() }

    fun load() {
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

    /** Keep the grant for a picked folder and list it. */
    fun addFolder(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (folders.value.any { it.uri == uri }) return
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        folders.value = folders.value + Folder(uri, name)
        saveFolders()
    }

    fun removeFolder(context: Context, uri: Uri) {
        // The grant stays while Music plays from the same folder.
        XmbMusicLibrary.loadFolders()
        if (XmbMusicLibrary.folders.value.none { it.uri == uri }) {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        folders.value = folders.value.filter { it.uri != uri }
        saveFolders()
        listings.remove("tree:$uri")
    }

    /** The removable devices in now, USB drives and SD cards. */
    fun volumes(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.map { v ->
            val dir = v.directory
            val description = v.getDescription(context)
            Volume(
                title = description,
                usb = description.contains("USB", ignoreCase = true),
                directory = dir,
                readable = dir?.let { runCatching { it.canRead() && it.list() != null }.getOrDefault(false) } == true,
                intent = runCatching { v.createOpenDocumentTreeIntent() }.getOrNull(),
            )
        }
    }

    // Folders read before the column was last shown: read again the next time they are, the old
    // list standing until the new one is in, so nothing blinks out.
    private val stale = HashSet<String>()

    /** A folder's videos and the folders in it, when read; null until then (reading starts here). */
    fun listing(context: Context, key: String, read: () -> List<Entry>): List<Entry>? {
        val known = listings[key]
        val again = known != null && synchronized(loading) { stale.remove(key) }
        if (known != null && !again) return known
        synchronized(loading) { if (!loading.add(key)) return known }
        scope.launch {
            val entries = runCatching(read).getOrDefault(emptyList())
            listings[key] = entries
            synchronized(loading) { loading.remove(key) }
            // Each video's first frame and length, one at a time, as the console fills its icons in.
            entries.filter { !it.dir && metas[it.key] == null }.forEach { e -> metas[e.key] = readMeta(context, e) }
        }
        return known
    }

    /** Read a folder again, next time it is shown. */
    fun forget(key: String) {
        listings.remove(key)
    }

    /** Every folder read so far is read again as it is next shown: the column has come into view. */
    fun refreshAll() {
        synchronized(loading) { stale.addAll(listings.keys) }
    }

    fun meta(entry: Entry): Meta? = metas[entry.key]

    fun localEntries(dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && (it.isDirectory || XmbMediaFormats.isVideo(it.name)) }
            .map { Entry(it.name, Uri.fromFile(it), it, null, null, it.isDirectory, it.lastModified()) }
            .sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))

    /**
     * A granted folder's contents, [docId] in [tree] (its top when null), in one query: DocumentFile
     * asks the provider again for each name, type and date, which takes seconds over a big folder.
     */
    fun treeEntries(context: Context, tree: Uri, docId: String? = null): List<Entry> {
        val parent = docId ?: runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Entry>()
        runCatching {
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val dir = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    if (name.startsWith(".") || !(dir || XmbMediaFormats.isVideo(name))) continue
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    out += Entry(name, uri, null, tree, id, dir, c.getLong(3))
                }
            }
        }
        return out.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))
    }

    private fun readMeta(context: Context, entry: Entry): Meta {
        val cache = File(File(context.cacheDir, "xmb-video").apply { mkdirs() }, sha1("${entry.key}|${entry.modified}") + ".jpg")
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, entry.uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val thumb = if (cache.length() > 0) {
                android.graphics.BitmapFactory.decodeFile(cache.path)
            } else {
                // A frame a tenth of the way in, past a black opening, at the size of an icon.
                val at = (duration * 100L).coerceIn(0L, 30_000_000L)
                r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)?.also { b ->
                    runCatching { cache.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                }
            }
            Meta(duration, thumb?.asImageBitmap())
        } catch (_: Exception) {
            Meta(0L, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Where [entry] was left, in ms; 0 for the start. */
    fun resumeAt(entry: Entry): Long =
        runCatching { JSONObject(MainActivityRuntime.prefs.getString(ResumeKey, "{}")).optLong(entry.key) }.getOrDefault(0L)

    /** Remember where [key] was left; near either end it starts from the beginning next time. */
    fun saveResume(key: String, positionMs: Long, durationMs: Long) {
        val o = runCatching { JSONObject(MainActivityRuntime.prefs.getString(ResumeKey, "{}")) }.getOrDefault(JSONObject())
        if (positionMs < 10_000 || (durationMs > 0 && positionMs > durationMs - 15_000)) o.remove(key) else o.put(key, positionMs)
        // The last 200 only, as the console keeps a few.
        val keys = o.keys().asSequence().toList()
        if (keys.size > 200) keys.take(keys.size - 200).forEach { o.remove(it) }
        MainActivityRuntime.prefs.edit { putString(ResumeKey, o.toString()) }
    }

    /** The console's Screen Mode, kept for every video: 0 Normal, 1 Full Screen (until one is picked), 2 Zoom, 3 Original. */
    fun screenMode(): Int = MainActivityRuntime.prefs.getInt(ScreenModeKey, 1).coerceIn(0, 3)

    fun setScreenMode(mode: Int) = MainActivityRuntime.prefs.edit { putInt(ScreenModeKey, mode) }

    /** A video's own Aspect Ratio: 0 its own (Auto), 1 4:3, 2 16:9, 3 21:9. */
    fun aspectMode(key: String): Int =
        runCatching { JSONObject(MainActivityRuntime.prefs.getString(AspectKey, "{}")).optInt(key) }.getOrDefault(0).coerceIn(0, 3)

    fun setAspectMode(key: String, mode: Int) {
        val o = runCatching { JSONObject(MainActivityRuntime.prefs.getString(AspectKey, "{}")) }.getOrDefault(JSONObject())
        if (mode == 0) o.remove(key) else o.put(key, mode)
        MainActivityRuntime.prefs.edit { putString(AspectKey, o.toString()) }
    }

    /** RetroArch Shaders on videos, kept for every video as Screen Mode is: a .slangp, or "" for none. */
    fun shaderPreset(): String = MainActivityRuntime.prefs.getString(ShaderKey, "").orEmpty()

    fun setShaderPreset(path: String) = MainActivityRuntime.prefs.edit { putString(ShaderKey, path) }

    /** Copy picked videos into the video folder; how many came in. IO thread. */
    fun import(context: Context, uris: List<Uri>): Int = uris.count { uri ->
        runCatching {
            val name = DocumentFile.fromSingleUri(context, uri)?.name?.replace('/', '_')?.takeIf { it.isNotBlank() }
                ?: "video-${System.currentTimeMillis()}.mp4"
            var target = File(folder(), name)
            var n = 1
            while (target.exists()) target = File(folder(), "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', "mp4")}")
            context.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } } != null &&
                target.length() > 0
        }.getOrDefault(false)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * The Video column, where the console had it, after Music: Import Video and Add Folder, then the
 * videos in ARMSX3's video folder, each device that is in, and each added folder, as folders to
 * open. A video shows its first frame and its length (and where it was left); Cross plays it full
 * screen from there, and Triangle has Play from Beginning, and Delete for one in ARMSX3's folder.
 */
@Composable
fun xmbVideoColumn(controller: XmbController, shown: Boolean = false): XmbColumn {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val full = "explore_plugin_full"
    val video = "explore_category_video"
    val ok = fw(full, "msg_ok", "xmb.ok")
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { XmbVideos.load() } }
    // Coming into view, the column reads its folders again: videos copied in from elsewhere show.
    LaunchedEffect(shown) {
        if (shown) {
            XmbVideos.refreshAll()
            refresh++
        }
    }
    // A USB drive or a card going in or out shows at once, as the console's USB device does.
    DisposableEffect(Unit) {
        val sm = context.getSystemService(StorageManager::class.java)
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: android.os.storage.StorageVolume) {
                refresh++
            }
        }
        runCatching { sm?.registerStorageVolumeCallback(context.mainExecutor, callback) }
        onDispose { runCatching { sm?.unregisterStorageVolumeCallback(callback) } }
    }
    val volumes by produceState(emptyList<XmbVideos.Volume>(), refresh) { value = withContext(Dispatchers.IO) { XmbVideos.volumes(context) } }
    val importTitle = str("xmb.video.import")
    val importDesc = str("xmb.video.import.desc")
    val imported = str("xmb.video.imported")
    val addTitle = str("xmb.video.addFolder")
    val addDesc = str("xmb.video.addFolder.desc")
    val removeTitle = str("xmb.video.removeFolder")
    val grantDesc = str("xmb.video.grant.desc")
    val usbWord = fw(full, "msg_usb", "xmb.video.usb")
    val none = fw(full, "msg_error_no_title", "xmb.video.none")
    val loadingWord = str("xmb.video.loading")
    val resumeWord = fw(full, "msg_resume", "xmb.video.resume")
    val beginWord = fw(full, "msg_play_begin", "xmb.video.playBegin")
    val deleteTitle = str("action.delete")
    val deleteAsk = str("xmb.music.delete.ask")
    val videoIcon = XmbIcon(video, "video_tex_default", "🎞")
    val folderIcon = XmbIcon(video, "item_tex_plain_folder", "📁")
    val albumIcon = XmbIcon(video, "video_tex_album_default", "📁")
    val loadIcon = XmbIcon(video, "tex_loading_icon", "…")
    val usbIcon = XmbIcon(video, "item_tex_usb_connection", "🔌")
    val sdIcon = XmbIcon(video, "item_tex_sd_icon", "💾")

    fun play(list: List<XmbVideos.Entry>, i: Int, fromBeginning: Boolean) {
        val e = list[i]
        controller.panel = XmbNode.Panel("video.play.${e.key}", e.title, videoIcon, fullscreen = true) { close ->
            XmbVideoPlayer(controller, list, i, fromBeginning, close)
        }
    }

    // One video: its first frame for an icon once read, its length, and where it was left.
    fun videoNode(list: List<XmbVideos.Entry>, i: Int, deletable: Boolean): XmbNode.Action {
        val e = list[i]
        val meta = XmbVideos.meta(e)
        val left = XmbVideos.resumeAt(e)
        val sub = listOfNotNull(
            meta?.durationMs?.let { xmbLength(it) }?.ifEmpty { null },
            if (left > 0) "$resumeWord ${xmbClockTime(left)}" else null,
        ).joinToString("   ")
        val options = buildList {
            if (left > 0) add(XmbOption(beginWord) { play(list, i, fromBeginning = true) })
            if (deletable && e.file != null) {
                add(XmbOption(deleteTitle) {
                    controller.ask(deleteAsk.format(e.title), yes, no, focusNo = true) {
                        e.file.delete()
                        e.file.parentFile?.let { XmbVideos.forget("file:" + it.absolutePath) }
                        refresh++
                    }
                })
            }
        }
        val icon = meta?.thumb?.let { XmbIcon(bitmap = it, glyph = "🎞") } ?: videoIcon
        return XmbNode.Action("video.${e.key}", e.title, icon, sub.ifEmpty { null }, options) { play(list, i, fromBeginning = false) }
    }

    // A folder, read when it is opened: its folders, then its videos.
    fun folderNode(key: String, title: String, icon: XmbIcon, sub: String?, deletable: Boolean, extra: List<XmbNode> = emptyList(), read: () -> List<XmbVideos.Entry>): XmbNode.Folder =
        XmbNode.Folder("video.dir.$key", title, icon, sub, live = true) {
            val entries = XmbVideos.listing(context, key, read)
            val videos = entries.orEmpty().filter { !it.dir }
            val nodes: List<XmbNode> = when {
                entries == null -> listOf(XmbNode.Action("video.dir.$key.loading", loadingWord, loadIcon) {})
                entries.isEmpty() -> listOf(XmbNode.Action("video.dir.$key.none", none, videoIcon) {})
                else -> entries.map { e ->
                    if (e.dir) {
                        val f = e.file
                        val tree = e.tree
                        if (f != null) folderNode("file:" + f.absolutePath, e.name, folderIcon, null, deletable) { XmbVideos.localEntries(f) }
                        else folderNode("tree:" + e.uri, e.name, folderIcon, null, deletable) { tree?.let { XmbVideos.treeEntries(context, it, e.docId) }.orEmpty() }
                    } else {
                        videoNode(videos, videos.indexOf(e), deletable)
                    }
                }
            }
            nodes + extra
        }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) { XmbVideos.import(context, uris) }
            XmbVideos.forget("file:" + XmbVideos.folder().absolutePath)
            refresh++
            controller.tell(imported.format(n), ok, error = n == 0)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            XmbVideos.addFolder(context, uri)
            refresh++
        }
    }
    val volumeGrant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            XmbVideos.addFolder(context, uri)
            refresh++
        }
    }

    val own = XmbVideos.folder()
    val ownEntries = XmbVideos.listing(context, "file:" + own.absolutePath) { XmbVideos.localEntries(own) }
    val ownVideos = ownEntries.orEmpty().filter { !it.dir }
    val items = buildList {
        add(XmbNode.Action("video.import", importTitle, videoIcon, importDesc) { importer.launch(arrayOf("video/*")) })
        add(XmbNode.Action("video.addFolder", addTitle, folderIcon, addDesc) { folderPicker.launch(null) })
        // The devices that are in, as the console lists a USB device.
        volumes.forEach { v ->
            val icon = if (v.usb) usbIcon else sdIcon
            val dir = v.directory
            if (v.readable && dir != null) {
                add(folderNode("file:" + dir.absolutePath, v.title, icon, if (v.usb) usbWord else null, deletable = false) { XmbVideos.localEntries(dir) })
            } else if (v.intent != null && XmbVideos.folders.value.none { it.name == v.title }) {
                add(XmbNode.Action("video.grant.${v.title}", v.title, icon, grantDesc) { volumeGrant.launch(v.intent) })
            }
        }
        // Added folders, each ending in Remove from Video.
        XmbVideos.folders.value.forEach { f ->
            val remove = XmbNode.Action("video.remove.${f.uri}", removeTitle, folderIcon) {
                XmbVideos.removeFolder(context, f.uri)
                controller.back()
                refresh++
            }
            add(folderNode("tree:" + f.uri, f.name, albumIcon, null, deletable = false, extra = listOf(remove)) { XmbVideos.treeEntries(context, f.uri) })
        }
        // ARMSX3's own folder: its folders, then its videos, straight in the column.
        ownEntries.orEmpty().forEach { e ->
            val f = e.file ?: return@forEach
            if (e.dir) {
                add(folderNode("file:" + f.absolutePath, e.name, folderIcon, null, deletable = true) { XmbVideos.localEntries(f) })
            } else {
                add(videoNode(ownVideos, ownVideos.indexOf(e), deletable = true))
            }
        }
        if (ownEntries != null && ownEntries.isEmpty() && volumes.isEmpty() && XmbVideos.folders.value.isEmpty()) {
            add(XmbNode.Action("video.none", none, videoIcon) {})
        }
    }
    return XmbColumn(
        key = "video",
        title = fw("xmb_ingame", "msg_video", "xmb.cat.video"),
        icon = XmbIcon("xmb_ingame", "tex_video", "🎞"),
        items = items,
        defaultFocus = if (ownVideos.isNotEmpty()) 2 + volumes.size + XmbVideos.folders.value.size else 0,
    )
}

/** The turn a video's frames need to stand upright, from its own metadata (0 without). IO thread. */
private fun videoRotation(context: Context, uri: Uri): Int = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    }
}.getOrDefault(0)

/**
 * RetroArch Shaders for videos, in the options' place: None, then the presets the game settings
 * find, a folder at a time (Up one folder first inside one). A pick goes on at once and the list
 * stays to try another; Circle goes up a folder, then back to the options; Triangle closes them.
 */
@Composable
private fun XmbVideoShaderList(
    font: androidx.compose.ui.text.font.FontFamily,
    u: Float,
    title: String,
    noneWord: String,
    scan: ShaderScan?,
    folder: String,
    current: String,
    onFolder: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val root = scan?.root
    val here = root?.let { it.findDirectory(folder) ?: it }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = (40f * u).dp),
        verticalArrangement = Arrangement.spacedBy((4f * u).dp),
    ) {
        Text(title, style = xmbText(font, 16f * u, 0.75f), modifier = Modifier.padding(horizontal = (18f * u).dp))
        if (here != null && here.key.isNotEmpty()) {
            Text(here.label, style = xmbText(font, 22f * u), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = (18f * u).dp))
        }
        @Composable
        fun item(id: String, label: String, detail: String?, chosen: Boolean, onConfirm: () -> Unit) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .controllerFocusable(id, onConfirm = onConfirm, onRight = onConfirm, onSecondary = onClose)
                    .pointerInput(id) { detectTapGestures { onConfirm() } }
                    .padding(horizontal = (18f * u).dp, vertical = (9f * u).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = xmbText(font, 19f * u, if (chosen) 1f else 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    chosen -> Text("✓", style = xmbText(font, 17f * u))
                    detail != null -> Text(detail, style = xmbText(font, 15f * u, 0.6f))
                }
            }
        }
        item("xmb.video.shader.none", noneWord, null, current.isBlank()) { onPick("") }
        when {
            root == null -> Text("…", style = xmbText(font, 19f * u, 0.6f), modifier = Modifier.padding(horizontal = (18f * u).dp))
            root.count == 0 -> {
                // Nothing installed: where the packs come from.
                Text(str("renderer.shaderPack.noneInstalled"), style = xmbText(font, 17f * u, 0.8f), modifier = Modifier.padding(horizontal = (18f * u).dp))
                Text(
                    "${str("tab.renderer")}  ›  ${str("renderer.shaderPack.label")}",
                    style = xmbText(font, 15f * u, 0.6f),
                    modifier = Modifier.padding(horizontal = (18f * u).dp),
                )
            }
            else -> {
                if (here != null && here.key.isNotEmpty()) {
                    item("xmb.video.shader.up.${here.key}", str("renderer.shaderChain.folder.up"), null, false) { onFolder(here.parentKeyWithin(root)) }
                }
                here?.folders.orEmpty().forEach { f ->
                    item("xmb.video.shader.dir.${f.key}", f.label, "${f.count}", false) { onFolder(f.key) }
                }
                here?.presets.orEmpty().forEach { p ->
                    item("xmb.video.shader.preset.${p.path}", p.label, null, p.path == current) { onPick(p.path) }
                }
            }
        }
    }
}

/**
 * A video full screen. Cross plays and pauses; Left and Right go back and on ten seconds; L1 and R1
 * the previous and next video in its folder; Square fits the picture to the screen or fills it;
 * Circle stops, and the video starts where it was left next time. The title and the time bar show
 * on any press and go after three seconds of playing. The music behind the XMB waits meanwhile.
 */
@Composable
internal fun XmbVideoPlayer(
    controller: XmbController,
    list: List<XmbVideos.Entry>,
    start: Int,
    fromBeginning: Boolean,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val font = rememberXmbFont()
    val full = "explore_plugin_full"
    val unsupported = fw(full, "msg_error_unsupported_data", "xmb.media.unsupported")
    val noTitle = fw(full, "msg_error_no_title", "xmb.video.none")
    // The console's video player's own words for its Screen Mode, and its four modes.
    val vp = "videoplayer_plugin"
    val screenModeWord = fw(vp, "msg_viewmode", "xmb.video.screenMode")
    val screenModes = listOf(
        fw(vp, "msg_normal", "xmb.video.normal"),
        fw(vp, "msg_full_screen", "xmb.video.fullScreen"),
        fw(vp, "msg_zoom", "xmb.video.zoom"),
        fw(vp, "msg_original", "xmb.video.original"),
    )
    val aspectWord = str("xmb.video.aspect")
    val aspects = listOf(str("common.auto"), "4:3", "16:9", "21:9")
    val optionsWord = str("xmb.options")
    val shaderWord = str("renderer.shaderChain.label")
    val noShaderWord = str("renderer.shaderChain.preset.none")
    val shaderFailedWord = str("xmb.video.shader.failed")
    var index by remember { mutableIntStateOf(start) }
    var first by remember { mutableStateOf(fromBeginning) }
    val entry = list[index]
    var videoW by remember { mutableIntStateOf(16) }
    var videoH by remember { mutableIntStateOf(9) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Screen Mode, kept for every video as the console keeps it; Aspect Ratio, kept for each video.
    var screenMode by remember { mutableIntStateOf(XmbVideos.screenMode()) }
    var aspectMode by remember(entry.key) { mutableIntStateOf(XmbVideos.aspectMode(entry.key)) }
    // Triangle's options: Screen Mode, Aspect Ratio and RetroArch Shaders, Left and Right to change them.
    var options by remember { mutableStateOf(false) }
    // The row the options open on: Screen Mode, or RetroArch Shaders coming back from their list.
    var optionsFocus by remember { mutableStateOf("xmb.video.opt.screen") }
    // RetroArch Shaders, kept for every video as Screen Mode is (a .slangp, or none), and their list
    // while it is open in the options' place: the presets the game settings find, a folder at a time.
    var shaderPreset by remember { mutableStateOf(XmbVideos.shaderPreset()) }
    var shaderList by remember { mutableStateOf(false) }
    var shaderFolder by remember { mutableStateOf("") }
    var shaderScan by remember { mutableStateOf<ShaderScan?>(null) }
    // A preset that could not run on this video: it plays on without one.
    var shaderBroken by remember(entry.key) { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shown by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var message by remember { mutableStateOf<String?>(null) }
    fun poke() {
        shown = android.os.SystemClock.uptimeMillis()
    }

    val holder = remember { mutableStateOf<android.view.Surface?>(null) }
    // The picture's SurfaceTexture, for a fresh Surface on it. FFmpeg's player writes with the CPU,
    // and a Surface written that way stays tied to it until the Surface itself goes: Android's
    // player or a shader stage could not draw onto it after. So once FFmpeg has had it, anything
    // else gets a new one.
    val pictureTexture = remember { arrayOfNulls<android.graphics.SurfaceTexture>(1) }
    val cpuUsed = remember { booleanArrayOf(false) }
    fun freshPicture() {
        val t = pictureTexture[0] ?: return
        cpuUsed[0] = false
        val old = holder.value
        holder.value = android.view.Surface(t)
        old?.release()
    }
    // Which player: Android's for what the device decodes, else the core's FFmpeg (src/xmb-player.cpp)
    // for AVI, MPEG and films with sound the device has no decoder for. Decided off the main thread
    // for each video; Android's failing on one hands it to FFmpeg too.
    var ffmpeg by remember(entry.key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(entry.key) {
        if (ffmpeg == null) ffmpeg = withContext(Dispatchers.IO) { !XmbMediaFormats.native(context, entry.uri, entry.name) }
    }
    val player = remember(entry.key) { MediaPlayer() }
    // Released last: the effects below are let go of first and still read where it was.
    DisposableEffect(entry.key) { onDispose { player.release() } }
    // The player of the video on now, for the surface's callbacks, made once for the first video.
    val active = remember { arrayOfNulls<MediaPlayer>(1) }
    active[0] = player.takeIf { ffmpeg == false }
    // The FFmpeg player's handle while it has one, 0 otherwise.
    val ff = remember { longArrayOf(0L) }
    fun core() = com.armsx3.core.ARMSX3.instance
    // RetroArch Shaders' stage (XmbVideoShader) while one is on: the decoder writes into its input
    // and it draws through the preset onto the picture's surface. A surface takes one writer at a
    // time: the decoder comes off the stage's input before the stage closes, and goes back onto the
    // picture's surface only once a closing stage has let go of it.
    val stage = remember { arrayOfNulls<XmbVideoShader.Stage>(1) }
    var stageInput by remember { mutableStateOf<android.view.Surface?>(null) }
    var stagesClosing by remember { mutableIntStateOf(0) }
    val wantShader = shaderPreset.isNotBlank() && !shaderBroken
    // Where the decoder draws: the stage's input with a shader on (nowhere until it is open), the
    // picture's surface without (nowhere while a stage still holds it).
    val decoderSurface = when {
        wantShader -> stageInput
        stagesClosing > 0 -> null
        else -> holder.value
    }
    val route = remember { arrayOfNulls<android.view.Surface>(1) }
    route[0] = decoderSurface
    val wantShaderNow = remember { booleanArrayOf(false) }
    wantShaderNow[0] = wantShader
    fun closeStage() {
        val s = stage[0] ?: return
        stage[0] = null
        runCatching { active[0]?.setSurface(null) }
        stageInput = null
        stagesClosing++
        s.close { stagesClosing-- }
    }
    // Closed as the player goes, before its MediaPlayer is released.
    DisposableEffect(Unit) { onDispose { closeStage() } }
    // Opened once the picture has its surface and the player is known, and kept for another preset;
    // closed for none, and opened again for the other player.
    val pictureSurface = holder.value
    LaunchedEffect(pictureSurface, wantShader, shaderPreset, ffmpeg) {
        val out = pictureSurface
        val rgba = ffmpeg == true
        if (out == null || !wantShader || ffmpeg == null) {
            closeStage()
            return@LaunchedEffect
        }
        if (cpuUsed[0] && stage[0]?.output !== out) {
            // Back here with the new Surface.
            freshPicture()
            return@LaunchedEffect
        }
        // The preset's settings as the game settings tune it.
        val params = withContext(Dispatchers.IO) {
            runCatching { com.armsx2.config.ConfigStore.loadGlobal().shaderChainParams[shaderPreset] }.getOrNull().orEmpty()
        }
        val open = stage[0]
        if (open != null && open.output === out && open.rgba == rgba) {
            open.preset(shaderPreset, params)
            return@LaunchedEffect
        }
        closeStage()
        val turn = if (rgba) 0 else withContext(Dispatchers.IO) { videoRotation(context, entry.uri) }
        val opened = XmbVideoShader.open(out, shaderPreset, params, rgba, videoW, videoH)
        if (opened == null) {
            shaderBroken = true
            message = shaderFailedWord
            return@LaunchedEffect
        }
        opened.rotation(turn)
        stage[0] = opened
        stageInput = opened.input
    }
    // The music behind the XMB waits while a video plays, as a game's music does.
    DisposableEffect(Unit) {
        LibraryMusic.startPreview()
        onDispose { LibraryMusic.endPreview() }
    }
    DisposableEffect(entry.key, ffmpeg) {
        if (ffmpeg != false) return@DisposableEffect onDispose {}
        error = null
        position = 0L
        duration = 0L
        runCatching {
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            player.setDataSource(context, entry.uri)
            player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoW = w; videoH = h } }
            player.setOnErrorListener { _, _, _ ->
                // Android's own player could not after all: FFmpeg's turn.
                playing = false
                ffmpeg = true
                true
            }
            player.setOnCompletionListener {
                playing = false
                XmbVideos.saveResume(entry.key, 0, 0)
                poke()
            }
            player.setOnPreparedListener { p ->
                duration = p.duration.toLong().coerceAtLeast(0)
                val at = if (first) 0L else XmbVideos.resumeAt(entry)
                first = false
                if (at > 0) p.seekTo(at, MediaPlayer.SEEK_CLOSEST_SYNC)
                route[0]?.let { runCatching { p.setSurface(it) } }
                p.start()
                playing = true
                poke()
            }
            player.prepareAsync()
        }.onFailure { ffmpeg = true }
        onDispose {
            runCatching { if (duration > 0 && ffmpeg == false) XmbVideos.saveResume(entry.key, player.currentPosition.toLong(), duration) }
            runCatching { player.stop() }
            runCatching { player.setSurface(null) }
        }
    }
    // Android's player draws where the decoder is routed: onto the shader stage's input as it opens,
    // back onto the picture's surface once it has closed.
    LaunchedEffect(decoderSurface, ffmpeg) {
        if (ffmpeg != false) return@LaunchedEffect
        if (decoderSurface != null && decoderSurface === holder.value && cpuUsed[0]) {
            freshPicture()
            return@LaunchedEffect
        }
        runCatching { player.setSurface(decoderSurface) }
    }
    // FFmpeg's player, once it has somewhere to draw (the picture's surface, or the shader stage's
    // input): from where the video was left, or the start. Opened off the main thread (it reads the
    // file's index); closed before the surface goes.
    val surface = decoderSurface
    LaunchedEffect(entry.key, ffmpeg, surface) {
        if (ffmpeg != true || surface == null) return@LaunchedEffect
        error = null
        position = 0L
        duration = 0L
        var handle = 0L
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            val at = if (first) 0L else XmbVideos.resumeAt(entry)
            first = false
            // Not cancellable: a player opened as the video is left must still come back to be closed.
            handle = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                // A document reaches FFmpeg as the descriptor its provider opens, which FFmpeg reads as a file.
                pfd = if (entry.file == null) runCatching { context.contentResolver.openFileDescriptor(entry.uri, "r") }.getOrNull() else null
                val path = entry.file?.absolutePath ?: pfd?.let { "/proc/self/fd/${it.fd}" }
                path?.let { runCatching { core().xmbPlayerOpen(it, surface, at) }.getOrDefault(0L) } ?: 0L
            }
            ff[0] = handle
            if (handle != 0L && surface === holder.value) cpuUsed[0] = true
            android.util.Log.i("XmbVideo", "FFmpeg player $handle for ${entry.name} on surface ${System.identityHashCode(surface)}")
            if (handle == 0L) {
                error = unsupported
                return@LaunchedEffect
            }
            duration = core().xmbPlayer(handle, 5, 0).coerceAtLeast(0)
            val w = core().xmbPlayer(handle, 7, 0).toInt()
            val h = core().xmbPlayer(handle, 8, 0).toInt()
            if (w > 0 && h > 0) { videoW = w; videoH = h }
            position = at
            playing = true
            poke()
            kotlinx.coroutines.awaitCancellation()
        } finally {
            ff[0] = 0L
            android.util.Log.i("XmbVideo", "FFmpeg player $handle closed")
            if (handle != 0L) {
                runCatching {
                    val now = core().xmbPlayer(handle, 4, 0)
                    if (core().xmbPlayer(handle, 6, 0) != 2L && duration > 0) XmbVideos.saveResume(entry.key, now, duration)
                }
                core().xmbPlayerClose(handle)
            }
            runCatching { pfd?.close() }
        }
    }
    // The position for the time bar, the end of a video FFmpeg plays, and, while it plays, no
    // screensaver over the film.
    LaunchedEffect(entry.key) {
        var n = 0
        while (true) {
            val h = ff[0]
            if (h != 0L) {
                position = core().xmbPlayer(h, 4, 0).coerceAtLeast(0)
                if (playing && core().xmbPlayer(h, 6, 0) == 2L) {
                    playing = false
                    XmbVideos.saveResume(entry.key, 0, 0)
                    poke()
                }
            } else {
                runCatching { if (playing) position = player.currentPosition.toLong() }
            }
            if (playing && ++n % 4 == 0) com.armsx2.ui.home.LibraryScreensaver.reset()
            // A shader that stopped working: the video goes on without it.
            stage[0]?.let { s ->
                if (s.failed) {
                    shaderBroken = true
                    message = shaderFailedWord
                    closeStage()
                }
            }
            delay(250)
        }
    }
    fun pauseNow() {
        val h = ff[0]
        if (h != 0L) core().xmbPlayer(h, 2, 0) else runCatching { player.pause() }
        playing = false
    }
    fun playNow() {
        val h = ff[0]
        if (h != 0L) {
            // At the end, Cross plays it again from the start.
            if (core().xmbPlayer(h, 6, 0) == 2L) core().xmbPlayer(h, 3, 0)
            core().xmbPlayer(h, 1, 0)
        } else {
            runCatching { player.start() }
        }
        playing = true
    }
    fun seekNow(to: Long) {
        val h = ff[0]
        if (h != 0L) core().xmbPlayer(h, 3, to) else runCatching { player.seekTo(to, MediaPlayer.SEEK_CLOSEST_SYNC) }
        position = to
    }
    // Leaving the app pauses it; coming back, it stays paused until Cross.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, entry.key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && playing) pauseNow()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    fun toggle() {
        poke()
        if (error != null || duration <= 0) return
        if (playing) pauseNow() else playNow()
    }
    fun seekBy(ms: Long) {
        poke()
        if (duration <= 0) return
        seekNow((position + ms).coerceIn(0, duration - 500))
    }
    fun seekTo(fraction: Float) {
        poke()
        if (duration <= 0) return
        seekNow((duration * fraction.coerceIn(0f, 1f)).toLong().coerceAtMost(duration - 500))
    }
    fun go(step: Int) {
        val next = index + step
        if (next !in list.indices) {
            XmbSounds.play(XmbSounds.Sfx.ERROR)
            message = noTitle
            return
        }
        XmbSounds.play(XmbSounds.Sfx.CURSOR)
        index = next
        playing = false
    }

    // Square (or its hint, touched): the next Screen Mode, named for a moment as it changes.
    fun nextScreenMode() {
        screenMode = (screenMode + 1) % screenModes.size
        XmbVideos.setScreenMode(screenMode)
        message = "$screenModeWord  ${screenModes[screenMode]}"
        poke()
    }
    XmbPanelKeysEffect { key ->
        when (key) {
            XmbPanelKeys.Key.L1 -> go(-1)
            XmbPanelKeys.Key.R1 -> go(1)
            XmbPanelKeys.Key.Square -> nextScreenMode()
        }
        true
    }
    fun changeScreenMode(step: Int) {
        XmbSounds.play(XmbSounds.Sfx.CURSOR)
        screenMode = (screenMode + step + screenModes.size) % screenModes.size
        XmbVideos.setScreenMode(screenMode)
    }
    fun changeAspect(step: Int) {
        XmbSounds.play(XmbSounds.Sfx.CURSOR)
        aspectMode = (aspectMode + step + aspects.size) % aspects.size
        XmbVideos.setAspectMode(entry.key, aspectMode)
    }
    fun openOptions(open: Boolean) {
        XmbSounds.play(if (open) XmbSounds.Sfx.OPTION else XmbSounds.Sfx.CANCEL)
        options = open
        shaderList = false
        optionsFocus = "xmb.video.opt.screen"
        poke()
    }
    // RetroArch Shaders' list, at its top, scanned as it opens: packs may have come in since.
    fun openShaderList() {
        XmbSounds.play(XmbSounds.Sfx.DECIDE)
        shaderFolder = ""
        shaderList = true
        optionsFocus = "xmb.video.opt.shader"
    }
    // A preset picked (or none): on at once, the list kept open to try another.
    fun pickShader(path: String) {
        XmbSounds.play(XmbSounds.Sfx.DECIDE)
        shaderPreset = path
        shaderBroken = false
        XmbVideos.setShaderPreset(path)
    }
    // Circle in the list: up a folder, and out of the list from its top.
    fun shaderBack() {
        XmbSounds.play(XmbSounds.Sfx.CANCEL)
        val root = shaderScan?.root
        val here = root?.findDirectory(shaderFolder)
        if (root != null && here != null && here.key.isNotEmpty()) shaderFolder = here.parentKeyWithin(root)
        else shaderList = false
    }
    LaunchedEffect(shaderList) {
        if (shaderList) shaderScan = withContext(Dispatchers.IO) { runCatching { scanShaderPresets(context) }.getOrNull() }
    }
    // Circle closes the shader list, then the options, before it closes the video.
    DisposableEffect(controller) {
        controller.panelBack = {
            when {
                shaderList -> { shaderBack(); true }
                options -> { options = false; poke(); true }
                else -> false
            }
        }
        onDispose { controller.panelBack = null }
    }
    // The list opens on the preset in use where it is in the folder shown, else on the folder's top.
    val shaderFocus = run {
        val root = shaderScan?.root
        val here = root?.let { it.findDirectory(shaderFolder) ?: it }
        when {
            here != null && here.presets.any { it.path == shaderPreset } -> "xmb.video.shader.preset.$shaderPreset"
            here != null && here.key.isNotEmpty() -> "xmb.video.shader.up.${here.key}"
            else -> "xmb.video.shader.none"
        }
    }
    LaunchedEffect(options, shaderList, shaderFolder, shaderScan) {
        val id = when {
            shaderList -> shaderFocus
            options -> optionsFocus
            else -> "xmb.video.player"
        }
        var tries = 0
        while (!SettingsControllerNav.selectById(id) && tries++ < 10) withFrameNanos {}
    }
    // The bar hides three seconds into playing without a press; paused, it stays.
    val now by produceState(0L) { while (true) { value = android.os.SystemClock.uptimeMillis(); delay(250) } }
    val barShown = !playing || error != null || now - shown < 3_000
    // A tap plays or pauses with the bar up, else brings the bar up. Read through a state: the tap
    // detector runs on from the first touch with what it was given then.
    val tap by rememberUpdatedState { if (options) openOptions(false) else if (barShown) toggle() else poke() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .controllerFocusable(
                "xmb.video.player",
                ring = false,
                onConfirm = ::toggle,
                onLeft = { seekBy(-10_000) },
                onRight = { seekBy(10_000) },
                onSecondary = { openOptions(true) },
            )
            .clipToBounds()
            .pointerInput(entry.key) {
                detectTapGestures(onTap = { tap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        val u = minOf(maxWidth.value / 1280f, maxHeight.value / 720f)
        // The picture's shape: its own, or the Aspect Ratio set for it.
        val aspect = when (aspectMode) {
            1 -> 4f / 3f
            2 -> 16f / 9f
            3 -> 21f / 9f
            else -> videoW.toFloat() / videoH
        }
        val screen = maxWidth.value / maxHeight.value
        // Normal: the whole picture, bars where it is narrower or wider. Full Screen: stretched to
        // the screen. Zoom: the screen filled, its shape kept, the rest cut off. Original: its own
        // pixels, one to one, in the middle.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val (pictureW, pictureH) = when (screenMode) {
            1 -> maxWidth.value to maxHeight.value
            2 -> if (aspect >= screen) maxHeight.value * aspect to maxHeight.value else maxWidth.value to maxWidth.value / aspect
            3 -> (videoH / density) * aspect to videoH / density
            else -> if (aspect >= screen) maxWidth.value to maxWidth.value / aspect else maxHeight.value * aspect to maxHeight.value
        }
        // A TextureView rather than a SurfaceView: a SurfaceView's picture is a window of its own,
        // placed apart from Compose's layout, and inside the panel's fade it sat at the screen's left
        // edge. Android's player decodes on the device's hardware, 4K included; FFmpeg's, for AVI and
        // the like, in software into the same surface.
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    keepScreenOn = true
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            val s = android.view.Surface(texture)
                            pictureTexture[0] = texture
                            cpuUsed[0] = false
                            holder.value = s
                            // Straight on unless a shader is wanted; its stage routes the player then.
                            if (!wantShaderNow[0]) runCatching { active[0]?.setSurface(s) }
                        }
                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            // A paused video's shaded picture, drawn again at the new size.
                            stage[0]?.redraw()
                        }
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            closeStage()
                            runCatching { active[0]?.setSurface(null) }
                            holder.value?.release()
                            holder.value = null
                            pictureTexture[0] = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.requiredSize(pictureW.dp, pictureH.dp),
        )

        error?.let {
            Text(it, style = xmbText(font, 22f * u), modifier = Modifier.align(Alignment.Center).padding((24f * u).dp))
        }
        message?.let {
            Text(it, style = xmbText(font, 20f * u), modifier = Modifier.align(Alignment.Center).padding((24f * u).dp))
        }
        AnimatedVisibility(barShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // The title over a shade at the top, with Back for a touch; the time bar over one at
                // the bottom, its hints taking a touch as their buttons do.
                Row(
                    Modifier.fillMaxWidth().height((110f * u).dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))),
                ) {
                    Text(
                        entry.title,
                        style = xmbText(font, 24f * u),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = (40f * u).dp, top = (22f * u).dp, end = (20f * u).dp),
                    )
                    Row(
                        Modifier.padding(end = (20f * u).dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close)
                            .padding(horizontal = (20f * u).dp, vertical = (20f * u).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("○", style = xmbText(font, 15f * u).copy(color = Color(0xFFE85C5C)))
                        Text(XmbText.get("xmb_ingame", "msg_help_back", ": " + str("xmb.back")), style = xmbText(font, 15f * u, 0.9f))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(horizontal = (40f * u).dp, vertical = (22f * u).dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((14f * u).dp)) {
                        XmbIconView(
                            XmbIcon("explore_category_music", if (playing) "tex_play_status" else "tex_pause_status", if (playing) "▶" else "⏸", shadow = false),
                            (28f * u).dp,
                        )
                        Text(xmbClockTime(position), style = xmbText(font, 17f * u, 0.95f))
                        Box(
                            Modifier.weight(1f).height((26f * u).dp)
                                .pointerInput(entry.key) { detectTapGestures { seekTo(it.x / size.width) } }
                                .pointerInput(entry.key) { detectHorizontalDragGestures { change, _ -> seekTo(change.position.x / size.width) } },
                        ) {
                            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            Canvas(Modifier.fillMaxSize()) {
                                val h = 4f * u * density
                                val y = size.height / 2f - h / 2f
                                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                                drawRoundRect(Color.White, Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h / 2))
                            }
                            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.001f)).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                                XmbIconView(XmbIcon("explore_category_video", "tex_sb_slider", "", shadow = false), (12f * u).dp, width = (34f * u).dp)
                            }
                        }
                        Text(xmbClockTime(duration), style = xmbText(font, 17f * u, 0.95f))
                    }
                    Row(Modifier.padding(top = (10f * u).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (list.size > 1) Text("${index + 1} / ${list.size}", style = xmbText(font, 15f * u, 0.7f), modifier = Modifier.weight(1f))
                        else Box(Modifier.weight(1f))
                        @Composable
                        fun hint(mark: String, markColor: Color, word: String, onTap: () -> Unit) {
                            Row(
                                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
                                    .padding(horizontal = (12f * u).dp, vertical = (10f * u).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(mark, style = xmbText(font, 15f * u).copy(color = markColor))
                                Text(" $word", style = xmbText(font, 15f * u, 0.9f))
                            }
                        }
                        hint("□", Color(0xFFE48BD6), screenModeWord, ::nextScreenMode)
                        hint("△", Color(0xFF6FD3A0), optionsWord) { openOptions(true) }
                    }
                }
            }
        }

        // Triangle's options, at the right as the console's are: each one's name over its setting,
        // Left and Right (or Cross) to change it, Circle or Triangle to close. RetroArch Shaders opens
        // its list in their place.
        AnimatedVisibility(options, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)),
                ),
            ) {
                if (shaderList) {
                    XmbVideoShaderList(
                        font = font,
                        u = u,
                        title = shaderWord,
                        noneWord = noShaderWord,
                        scan = shaderScan,
                        folder = shaderFolder,
                        current = shaderPreset,
                        onFolder = { XmbSounds.play(XmbSounds.Sfx.DECIDE); shaderFolder = it },
                        onPick = ::pickShader,
                        onClose = { openOptions(false) },
                        modifier = Modifier.align(Alignment.CenterEnd).width((440f * u).dp).fillMaxHeight().padding(end = (48f * u).dp),
                    )
                } else Column(
                    Modifier.align(Alignment.CenterEnd).width((440f * u).dp).padding(end = (48f * u).dp),
                    verticalArrangement = Arrangement.spacedBy((14f * u).dp),
                ) {
                    @Composable
                    fun row(id: String, title: String, value: String, change: (Int) -> Unit) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .controllerFocusable(
                                    id,
                                    onConfirm = { change(1) },
                                    onLeft = { change(-1) },
                                    onRight = { change(1) },
                                    onSecondary = { openOptions(false) },
                                )
                                .pointerInput(id) { detectTapGestures { change(1) } }
                                .padding(horizontal = (18f * u).dp, vertical = (10f * u).dp),
                        ) {
                            Text(title, style = xmbText(font, 16f * u, 0.75f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("◀", style = xmbText(font, 15f * u, 0.6f))
                                Text(value, style = xmbText(font, 22f * u), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text("▶", style = xmbText(font, 15f * u, 0.6f))
                            }
                        }
                    }
                    row("xmb.video.opt.screen", screenModeWord, screenModes[screenMode], ::changeScreenMode)
                    row("xmb.video.opt.aspect", aspectWord, aspects[aspectMode], ::changeAspect)
                    row(
                        "xmb.video.opt.shader",
                        shaderWord,
                        if (shaderPreset.isBlank()) noShaderWord else File(shaderPreset).nameWithoutExtension,
                    ) { openShaderList() }
                }
            }
        }
    }
}

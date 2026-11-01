package com.armsx2.ui.xmb

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import com.armsx2.GameInfo
import com.armsx2.Ps3Sfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A game's own XMB media, as the console shows it: ICON0 (the icon), PIC1 (the background behind
 * the XMB while the game is focused), SND0 (its music) and ICON1 (its animated icon).
 *
 * They sit beside PARAM.SFO: in PS3_GAME on a disc in folder form, in dev_hdd0/game/<id> for an
 * installed title, or in the cache when [XmbMediaCache] has taken them out of a disc image.
 */
object XmbMedia {
    /**
     * The folder holding [game]'s PARAM.SFO and its media, when there is one: a disc in folder
     * form (picked by path, or through the system's picker on an SD card), media taken out of a
     * disc image, or an installed title's own folder.
     */
    fun dirOf(context: Context, game: GameInfo, extract: Boolean = true): File? {
        localPath(game)?.let { File(it) }?.let { f ->
            // The folder itself, or its EBOOT.BIN two folders down.
            val top = if (f.isFile && f.name.equals("EBOOT.BIN", ignoreCase = true)) f.parentFile?.parentFile?.parentFile else f
            if (top != null && top.isDirectory) {
                File(top, game.discGameDir ?: "PS3_GAME").takeIf { File(it, "PARAM.SFO").isFile }?.let { return it }
                if (File(top, "PARAM.SFO").isFile) return top
            }
        }
        game.serial?.takeIf { it.isNotBlank() }?.let { serial ->
            XmbMediaCache.dirFor(context, serial)?.let { return it }
            // A disc image keeps its media inside it: taken out once, into the cache.
            if (extract) XmbMediaCache.extract(context, game)?.let { return it }
            // An installed title keeps its own. A disc game's installed update has a folder too,
            // usually without the disc's background and music, so it comes after the disc's.
            Ps3Sfo.installDir(serial)?.takeIf { d -> MEDIA.any { File(d, it).isFile } || File(d, "ICON0.PNG").isFile }?.let { return it }
        }
        return null
    }

    private val MEDIA = listOf("PIC1.PNG", "SND0.AT3", "ICON1.PAM")

    /**
     * Where [game] is on this device's storage as a path: its own, or for a pick through the
     * system's picker the path its document stands for (`<volume>:<path>`), readable here with
     * all-files access. Null when it cannot be read by path.
     */
    private fun localPath(game: GameInfo): String? = when (game.uri.scheme) {
        "file" -> game.uri.path
        "content" -> runCatching {
            val id = android.provider.DocumentsContract.getDocumentId(game.uri)
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val base = if (volume.equals("primary", ignoreCase = true)) android.os.Environment.getExternalStorageDirectory() else File("/storage", volume)
            File(base, rel).takeIf { it.exists() }?.absolutePath
        }.getOrNull()
        else -> null
    }

    fun icon0(game: GameInfo, dir: File?): Any? =
        game.discIconFile ?: dir?.let { File(it, "ICON0.PNG") }?.takeIf { it.isFile } ?: game.coverModel

    fun pic1(dir: File?): File? = dir?.let { File(it, "PIC1.PNG") }?.takeIf { it.isFile }
    fun pic0(dir: File?): File? = dir?.let { File(it, "PIC0.PNG") }?.takeIf { it.isFile }
    fun snd0(dir: File?): File? = dir?.let { File(it, "SND0.AT3") }?.takeIf { it.isFile }
    fun icon1(dir: File?): File? = dir?.let { File(it, "ICON1.PAM") }?.takeIf { it.isFile }

    /**
     * The audio track of the animated icon [pam], decoded into a WAV, cached by its folder and size;
     * null when it has none. Some games have no SND0 and this is their music (THE iDOLM@STER 2).
     */
    fun decodedPamAudio(context: Context, pam: File): File? {
        val out = File(context.cacheDir, "xmb-audio/${pam.parentFile?.name}-ICON1-${pam.length()}.wav")
        if (out.isFile && out.length() > 44) return out
        val none = File(out.path + ".none")
        if (none.isFile) return null
        out.parentFile?.mkdirs()
        val ok = runCatching { com.armsx3.core.ARMSX3.instance.xmbDecodePamAudio(pam.absolutePath, out.absolutePath) }
            .getOrDefault(false)
        if (!ok) runCatching { none.createNewFile() }
        return out.takeIf { ok && it.isFile && it.length() > 44 }
    }

    /**
     * [source] decoded into a WAV the platform can play, cached by name and size. Null when the
     * core cannot decode it (or is not loaded), so the caller can try the file as it is.
     */
    fun decodedAudio(context: Context, source: File): File? {
        val out = File(context.cacheDir, "xmb-audio/${source.name}-${source.length()}.wav")
        if (out.isFile && out.length() > 44) return out
        out.parentFile?.mkdirs()
        val ok = runCatching { com.armsx3.core.ARMSX3.instance.xmbDecodeAudio(source.absolutePath, out.absolutePath) }
            .getOrDefault(false)
        return out.takeIf { ok && it.isFile && it.length() > 44 }
    }
}

/**
 * Media taken out of disc images, which have no folder to read it from: copied out of the image
 * (PS3_GAME's PIC1, PIC0, SND0 and ICON1) the first time its game is looked up, by the core, which
 * mounts the image as it does to read its icon. A disc with none of it is remembered as such.
 */
object XmbMediaCache {
    // A second cache: the first could hold another game's media, read before each disc's own
    // PARAM.SFO came out with it to be checked. It goes the first time this one is used.
    fun root(context: Context): File = File(context.filesDir, "xmb-media2").also {
        if (!dropped) {
            dropped = true
            runCatching { File(context.filesDir, "xmb-media").deleteRecursively() }
        }
    }

    @Volatile private var dropped = false

    /** The disc images the core can open to read (a CHD reads as the image it was made from). */
    private val IMAGES = setOf("ISO", "CHD", "BIN", "IMG", "MDF", "NRG", "DUMP")

    fun dirFor(context: Context, serial: String): File? =
        File(root(context), serial).takeIf { File(it, "PIC1.PNG").isFile || File(it, "SND0.AT3").isFile || File(it, "ICON1.PAM").isFile }

    /** [game]'s media out of its disc image, into the cache; null when it has none. IO thread. */
    fun extract(context: Context, game: GameInfo): File? {
        val serial = game.serial?.takeIf { it.isNotBlank() } ?: return null
        if (game.extension.uppercase() !in IMAGES) return null
        val none = File(root(context), "$serial.none")
        if (none.isFile) return null
        // The core mounts the image only while nothing runs, and only once it is loaded; asked
        // again later otherwise, rather than the disc being marked as having nothing.
        if (com.armsx2.runtime.MainActivityRuntime.eState.value != com.armsx2.EmuState.STOPPED) return null
        if (!com.armsx2.runtime.MainActivityRuntime.nativeReady.value) return null
        val path = when (game.uri.scheme) {
            "file" -> game.uri.path
            "content" -> com.armsx2.storage.ContentUri.devicePathForDocument(game.uri)
            else -> null
        } ?: return null
        val staging = File(root(context), "$serial.part").apply {
            deleteRecursively()
            mkdirs()
        }
        val copied = runCatching { com.armsx3.core.ARMSX3.instance.xmbDiscMedia(path, staging.absolutePath) }.getOrDefault(false)
        // Only this game's: the image read has to say it is the game asked for.
        val found = Ps3Sfo.read(File(staging, "PARAM.SFO"))["TITLE_ID"]?.trim().orEmpty()
        val mine = found.replace("-", "").equals(serial.replace("-", ""), ignoreCase = true)
        if (copied && !mine) android.util.Log.w("ARMSX3", "xmb media: $path says $found, not $serial; not kept")
        if (!copied || !mine) {
            staging.deleteRecursively()
            runCatching { none.createNewFile() }
            return null
        }
        val dir = File(root(context), serial)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) staging.copyRecursively(dir, overwrite = true).also { staging.deleteRecursively() }
        return dirFor(context, serial)
    }
}

/** The focused game's PIC1 behind the XMB, faded in after it has stayed focused a moment. */
@Composable
fun XmbGameBackdrop(node: XmbNode?) {
    val game = (node as? XmbNode.Game)?.takeIf { XmbMode.gameBackgrounds.value }
    val pic1 by produceState<File?>(null, game?.key) {
        value = withContext(Dispatchers.IO) { XmbMedia.pic1(game?.mediaDir) }
    }
    Crossfade(targetState = pic1, animationSpec = tween(700), label = "xmb-pic1") { file ->
        if (file != null) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The focused game's SND0, as the console plays it: after the game has stayed focused a moment,
 * fading in over the library music (which pauses), looping, and stopping when the cursor moves on,
 * something opens over it, or the app leaves the foreground.
 */
@Composable
fun XmbSnd0Preview(controller: XmbController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val node = controller.rootNode() as? XmbNode.Game
    val quiet = controller.busy || controller.levels.isNotEmpty() || XmbMode.booting.value || !resumed || !XmbMode.gameMusic.value
    androidx.compose.runtime.LaunchedEffect(node?.key, quiet) {
        if (node == null || quiet) return@LaunchedEffect
        // SND0, or failing that the animated icon's own sound, which starts with the icon.
        val snd0 = withContext(Dispatchers.IO) { XmbMedia.snd0(node.mediaDir) }
        val pam = if (snd0 == null && XmbMode.animatedIcons.value) withContext(Dispatchers.IO) { XmbMedia.icon1(node.mediaDir) } else null
        kotlinx.coroutines.delay(if (snd0 != null) 1200 else 500)
        val audio = withContext(Dispatchers.IO) {
            snd0?.let { XmbMedia.decodedAudio(context, it) } ?: pam?.let { XmbMedia.decodedPamAudio(context, it) }
        } ?: return@LaunchedEffect
        // Plays only while the app is in front, watching the lifecycle itself: Compose stops
        // recomposing once the activity stops, and the screen going off pauses and stops it at
        // once, so `quiet` never hears of it and the loop played on with the screen off. Back in
        // front, the game's music starts over.
        while (true) {
            lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            val player = runCatching {
                android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_GAME)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setDataSource(audio.absolutePath)
                    isLooping = true
                    setVolume(0f, 0f)
                    prepare()
                }
            }.getOrNull() ?: return@LaunchedEffect
            com.armsx2.LibraryMusic.pause()
            try {
                player.start()
                kotlinx.coroutines.coroutineScope {
                    val fade = launch {
                        var v = 0f
                        while (v < 0.85f) {
                            v = minOf(0.85f, v + 0.05f)
                            player.setVolume(v, v)
                            kotlinx.coroutines.delay(60)
                        }
                    }
                    lifecycle.currentStateFlow.first { !it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                    fade.cancel()
                }
            } finally {
                runCatching { player.stop() }
                player.release()
                // Nothing while the app is away: LibraryMusic waits for onResume.
                com.armsx2.LibraryMusic.resume()
            }
        }
    }
}

/**
 * A game's ICON1.PAM playing in place of its icon, looping, as the console shows the focused game.
 * Frames come from the core's decoder (ARMSX3.xmbPam*) into two bitmaps in turn, paced by their
 * timestamps. [fallback] shows until the first frame, and for good when the icon cannot be played.
 */
@Composable
fun XmbAnimatedIcon(pam: File, modifier: Modifier, fallback: @Composable () -> Unit) {
    var frame by androidx.compose.runtime.remember(pam) { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(pam) {
        // The console waits a beat before the icon moves.
        kotlinx.coroutines.delay(500)
        withContext(Dispatchers.Default) {
            val core = com.armsx3.core.ARMSX3.instance
            fun open() = runCatching { core.xmbPamOpen(pam.absolutePath) }.getOrDefault(0L)
            var handle = open()
            if (handle == 0L) return@withContext
            try {
                val size = core.xmbPamSize(handle)
                val w = size ushr 16
                val h = size and 0xFFFF
                if (w <= 0 || h <= 0) return@withContext
                val buffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                val bitmaps = Array(2) { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
                var which = 0
                var start = android.os.SystemClock.uptimeMillis()
                var first = -1L
                while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                    buffer.clear()
                    val ts = core.xmbPamFrame(handle, buffer)
                    if (ts < 0) {
                        // The end: round again from the start.
                        core.xmbPamClose(handle)
                        handle = open()
                        if (handle == 0L) break
                        start = android.os.SystemClock.uptimeMillis()
                        first = -1L
                        continue
                    }
                    if (first < 0) first = ts
                    val wait = start + (ts - first) - android.os.SystemClock.uptimeMillis()
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    buffer.rewind()
                    val bmp = bitmaps[which]
                    bmp.copyPixelsFromBuffer(buffer)
                    frame = bmp.asImageBitmap()
                    which = 1 - which
                }
            } finally {
                if (handle != 0L) runCatching { core.xmbPamClose(handle) }
            }
        }
    }
    val f = frame
    if (f != null) {
        androidx.compose.foundation.Image(f, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        fallback()
    }
}

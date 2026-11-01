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
    /** The folder holding [game]'s PARAM.SFO and its media, when there is one on disk. */
    fun dirOf(context: Context, game: GameInfo): File? {
        if (game.uri.scheme == "file") {
            val path = game.uri.path
            if (path != null) {
                val f = File(path)
                if (f.isDirectory) {
                    File(f, game.discGameDir ?: "PS3_GAME").takeIf { File(it, "PARAM.SFO").isFile }?.let { return it }
                    if (File(f, "PARAM.SFO").isFile) return f
                }
            }
        }
        game.serial?.takeIf { it.isNotBlank() }?.let { serial ->
            Ps3Sfo.installDir(serial)?.takeIf { File(it, "PIC1.PNG").isFile || File(it, "ICON0.PNG").isFile }?.let { return it }
            XmbMediaCache.dirFor(context, serial)?.let { return it }
        }
        return null
    }

    fun icon0(game: GameInfo, dir: File?): Any? =
        game.discIconFile ?: dir?.let { File(it, "ICON0.PNG") }?.takeIf { it.isFile } ?: game.coverModel

    fun pic1(dir: File?): File? = dir?.let { File(it, "PIC1.PNG") }?.takeIf { it.isFile }
    fun pic0(dir: File?): File? = dir?.let { File(it, "PIC0.PNG") }?.takeIf { it.isFile }
    fun snd0(dir: File?): File? = dir?.let { File(it, "SND0.AT3") }?.takeIf { it.isFile }
    fun icon1(dir: File?): File? = dir?.let { File(it, "ICON1.PAM") }?.takeIf { it.isFile }

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

/** Media taken out of disc images, which have no folder to read it from. Filled by the library scan. */
object XmbMediaCache {
    fun root(context: Context): File = File(context.filesDir, "xmb-media")

    fun dirFor(context: Context, serial: String): File? =
        File(root(context), serial).takeIf { File(it, "PIC1.PNG").isFile || File(it, "SND0.AT3").isFile || File(it, "ICON1.PAM").isFile }
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
        kotlinx.coroutines.delay(1200)
        val audio = withContext(Dispatchers.IO) {
            XmbMedia.snd0(node.mediaDir)?.let { XmbMedia.decodedAudio(context, it) }
        } ?: return@LaunchedEffect
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
            var v = 0f
            while (v < 0.85f) {
                v = minOf(0.85f, v + 0.05f)
                player.setVolume(v, v)
                kotlinx.coroutines.delay(60)
            }
            kotlinx.coroutines.awaitCancellation()
        } finally {
            runCatching { player.stop() }
            player.release()
            com.armsx2.LibraryMusic.resume()
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

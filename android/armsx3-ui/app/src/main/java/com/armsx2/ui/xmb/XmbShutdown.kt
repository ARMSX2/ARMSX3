package com.armsx2.ui.xmb

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.armsx2.LibraryMusic
import com.armsx2.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Leaving XMB mode the way a console is switched between systems: the XMB goes dark as the PS3's
 * does when it powers off, a moment of black, then ARMSX3's own intro, and the library comes up
 * after it, as the app starts. [start] runs it; the overlay ([XmbShutdownOverlay]) sits above
 * every screen, so it stays up while XMB mode ends behind it.
 */
object XmbShutdown {
    val running = mutableStateOf(false)

    fun start() {
        if (running.value) return
        running.value = true
    }
}

/** The leaving sequence, over everything while it runs. Hosted once, by WindowImpl. */
@Composable
fun XmbShutdownOverlay() {
    if (!XmbShutdown.running.value) return
    val context = LocalContext.current.applicationContext
    val dark = remember { Animatable(0f) }
    var intro by remember { mutableStateOf(false) }
    var introDone by remember { mutableStateOf(false) }

    // The pad does nothing to the XMB going dark behind it.
    DisposableEffect(Unit) {
        XmbInput.blocked = true
        XmbInput.skip = null
        onDispose { XmbInput.blocked = false }
    }
    LaunchedEffect(Unit) {
        XmbSounds.play(XmbSounds.Sfx.CANCEL)
        // The music goes down with the screen.
        launch {
            val down = Animatable(1f)
            down.animateTo(0f, tween(1200, easing = LinearEasing)) { LibraryMusic.setShare(maxOf(value, 0.01f)) }
            LibraryMusic.setShare(0f)
        }
        // The wave and its colour sink with the screen, as the console's do.
        launch {
            val sink = Animatable(1f)
            sink.animateTo(0f, tween(1200, easing = LinearEasing)) {
                XmbOriginalColors.bootWave = value
                XmbOriginalColors.bootColor = value
            }
        }
        dark.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        // Black: XMB mode ends behind it, and the library is there when the intro is over.
        XmbMode.exit()
        delay(700)
        intro = true
    }
    LaunchedEffect(introDone) {
        if (!introDone) return@LaunchedEffect
        // The library's music comes in after the intro's, quiet first, as at startup.
        LibraryMusic.setShare(0f)
        LibraryMusic.start(context)
        XmbBootMusic.fadeIn()
        dark.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        XmbShutdown.running.value = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = dark.value }
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { if (intro) introDone = true } },
    ) {
        if (intro && !introDone) XmbIntroVideo { introDone = true }
    }
}

/**
 * ARMSX3's intro, as BootSplashActivity plays it at launch: the bundled one cropped to fill the
 * screen (it is square with the mark in the middle), or the player's own shown whole.
 */
@Composable
private fun XmbIntroVideo(onDone: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // Never stuck on black: a video that will not play, or one that runs long, ends anyway.
    LaunchedEffect(Unit) {
        delay(12_000)
        onDone()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val custom = com.armsx2.BootIntro.installed(ctx)
                        val view = this@apply
                        runCatching {
                            player = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                        .build(),
                                )
                                if (custom != null) setDataSource(custom.absolutePath)
                                else setDataSource(ctx, Uri.parse("android.resource://${ctx.packageName}/${R.raw.boot_intro}"))
                                setSurface(Surface(st))
                                isLooping = false
                                setOnPreparedListener {
                                    scale(view, it, width, height, fill = custom == null)
                                    start()
                                }
                                setOnCompletionListener { onDone() }
                                setOnErrorListener { _, _, _ ->
                                    onDone()
                                    true
                                }
                                prepareAsync()
                            }
                        }.onFailure { onDone() }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        player?.let { scale(this@apply, it, width, height, fill = com.armsx2.BootIntro.installed(ctx) == null) }
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

/** Fill the view and crop the overflow ([fill]), or fit inside it, about the centre. */
private fun scale(view: TextureView, mp: MediaPlayer, viewWidth: Int, viewHeight: Int, fill: Boolean) {
    val videoWidth = mp.videoWidth.takeIf { it > 0 } ?: return
    val videoHeight = mp.videoHeight.takeIf { it > 0 } ?: return
    if (viewWidth <= 0 || viewHeight <= 0) return
    val w = viewWidth.toFloat() / videoWidth
    val h = viewHeight.toFloat() / videoHeight
    val s = if (fill) maxOf(w, h) else minOf(w, h)
    view.setTransform(
        Matrix().apply {
            setScale(videoWidth * s / viewWidth, videoHeight * s / viewHeight, viewWidth / 2f, viewHeight / 2f)
        },
    )
}

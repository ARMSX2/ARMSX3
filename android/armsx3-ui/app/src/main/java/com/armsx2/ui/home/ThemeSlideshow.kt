package com.armsx2.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Plays a dynamic PS3 theme's slides the way the theme's own script does (see [P3tAnimation]): from
 * a random slide, then in order, each change a crossfade, with the camera's slow zoom when the
 * theme has one. The slides were saved at import; this only loads and fades them, at most two in
 * memory, as hardware bitmaps.
 *
 * Each change waits for a frame first, so the show stops when nothing is being drawn: in the
 * background it does not load pictures nobody sees.
 */
@Composable
fun ThemeSlideshow(show: LibraryBackground.Slideshow, modifier: Modifier = Modifier) {
    val timing = show.timing
    var shown by remember(show) { mutableStateOf<ImageBitmap?>(null) }
    var coming by remember(show) { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember(show) { Animatable(0f) }
    LaunchedEffect(show) {
        val frames = show.frames
        val fadeMs = (timing.fade * 1000).roundToInt()
        suspend fun fadeTo(index: Int) {
            withFrameNanos { }
            val picture = withContext(Dispatchers.IO) { load(frames[index]) } ?: return
            fade.snapTo(0f)
            coming = picture
            fade.animateTo(1f, tween(fadeMs, easing = LinearEasing))
            shown = picture
            coming = null
        }
        var index = Random.nextInt(frames.size)
        fadeTo(index) // the theme fades its first slide in as well
        val holdMs = ((timing.interval - timing.fade) * 1000).toLong().coerceAtLeast(0L)
        while (frames.size > 1) {
            delay(holdMs)
            index = (index + 1) % frames.size
            fadeTo(index)
        }
    }
    // The camera starts near and breathes out and back: rest, then move, like the theme's timer.
    val zoom = if (timing.zoom > 0f) {
        val moveMs = (timing.zoomMove * 1000).roundToInt()
        val restMs = ((timing.zoomInterval - timing.zoomMove) * 1000).roundToInt().coerceAtLeast(0)
        rememberInfiniteTransition(label = "themeZoom").animateFloat(
            initialValue = 1f + timing.zoom,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(moveMs, delayMillis = restMs, easing = EaseInOut), RepeatMode.Reverse),
            label = "themeZoomScale",
        )
    } else {
        null
    }
    Box(
        modifier.clipToBounds().graphicsLayer {
            val scale = zoom?.value ?: 1f
            scaleX = scale
            scaleY = scale
        },
    ) {
        shown?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        coming?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop)
        }
    }
}

private fun load(file: File): ImageBitmap? = runCatching {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()

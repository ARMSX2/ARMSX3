package com.armsx2.ui.xmb

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The console's boot animation, with ARMSX3 where it said PS3.
 *
 * The timing is the firmware's own (coldboot.raf's script, coldboot.jsx, read out of the user's
 * dev_flash): the logo is lit by a point light that sweeps in from the left over 1.3 s from 1.5 s,
 * a glow of it rises at 2.55 s for half a second and falls at 3.3 s as the footer line comes in, and
 * everything fades over 1.2 s from 4.3 s. The light's falloff uses the script's attenuation, so the
 * logo lights from the side the light comes from, as on the console.
 *
 * Behind it the XMB comes up from black: dim while the logo shows, then full as the logo fades, and
 * the icons last. [onProgress] reports the seconds for that last part.
 */
object XmbBootTimeline {
    const val LIGHT_START = 1.5f
    const val LIGHT_TIME = 1.3f
    const val GLOW_START = 2.55f
    const val GLOW_TIME = 0.5f
    const val WHOLE_START = 3.3f
    const val WHOLE_TIME = 0.5f
    const val FADE_START = 4.3f
    const val FADE_TIME = 1.2f
    const val REVEAL_START = 5.2f
    const val REVEAL_TIME = 1.0f
    const val END = REVEAL_START + REVEAL_TIME

    fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    fun ease(x: Float): Float = clamp01(x).let { it * it * (3f - 2f * it) }

    /** How dark the XMB behind is: black, then dim while the logo is lit, then gone. */
    fun darkness(t: Float): Float = when {
        t < LIGHT_START -> 1f
        t < FADE_START -> 1f - 0.5f * ease((t - LIGHT_START) / (FADE_START - LIGHT_START))
        else -> 0.5f * (1f - ease((t - FADE_START) / (FADE_TIME + 0.6f)))
    }

    /** 0..1 as the XMB's own icons come in, at the end. */
    fun reveal(t: Float): Float = ease((t - REVEAL_START) / REVEAL_TIME)
}

@Composable
fun XmbBoot(onProgress: (Float) -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val layers by produceState<XmbWordmark.Layers?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching { XmbWordmark.render(1400, withContext(Dispatchers.IO) { XmbFirmware.typeface() }) }.getOrNull()
        }
    }
    var t by remember { mutableFloatStateOf(0f) }
    var skipped by remember { mutableStateOf(false) }
    val finished by rememberUpdatedState(onFinished)
    val progress by rememberUpdatedState(onProgress)

    DisposableEffect(Unit) {
        val sound = XmbBootSound.start(context)
        XmbInput.blocked = true
        XmbInput.skip = { skipped = true }
        com.armsx2.LibraryMusic.pause()
        onDispose {
            sound?.let { runCatching { it.stop(); it.release() } }
            XmbInput.blocked = false
            XmbInput.skip = null
            com.armsx2.LibraryMusic.resume()
        }
    }
    LaunchedEffect(layers != null) {
        // The clock starts once the logo is ready, so a slow first frame cannot eat the sweep.
        if (layers == null) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            var seconds = (now - start) / 1_000_000_000f
            if (skipped) seconds = maxOf(seconds, XmbBootTimeline.FADE_START + XmbBootTimeline.FADE_TIME)
            t = seconds
            progress(seconds)
            if (seconds >= XmbBootTimeline.END) break
        }
        finished()
    }
    // After a skip the clock jumps; keep it moving from there.
    LaunchedEffect(skipped) {
        if (skipped && t < XmbBootTimeline.FADE_START + XmbBootTimeline.FADE_TIME) {
            t = XmbBootTimeline.FADE_START + XmbBootTimeline.FADE_TIME
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { skipped = true } },
    ) {
        // The XMB behind, from black to bright.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = XmbBootTimeline.darkness(t) }.background(Color.Black))
        val l = layers ?: return@BoxWithConstraints
        val logo = remember(l) { l.logo.asImageBitmap() }
        val footer = remember(l) { l.footer.asImageBitmap() }
        val glow = remember(l) { l.glow.asImageBitmap() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // The scene's logo plane is 0.32 of the screen's height at 16:9; keep that, narrower on a
        // tall screen so the word fits across it.
        val boxW = minOf(0.64f * h, 0.86f * w)
        val boxH = boxW / 2f
        val left = (w - boxW) / 2f
        val top = (h - boxH) / 2f

        // The logo, lit. Drawn into its own layer so the light can mask it.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val light = light(t)
            val logoAlpha = XmbBootTimeline.ease(t / XmbBootTimeline.LIGHT_START) * (1f - fade(t))
            if (light == null || logoAlpha <= 0f) return@Canvas
            drawLayer(logo, left, top, boxW, boxH, logoAlpha)
            // Scene units to pixels: the plane is 2.56 units wide.
            val s = boxW / 2.56f
            val centre = Offset(w / 2f + light.x * s, h / 2f - light.y * s)
            drawRect(
                Brush.radialGradient(
                    *light.stops,
                    center = centre,
                    radius = (light.reach * s).coerceAtLeast(1f),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val glowAlpha = XmbBootTimeline.ease((t - XmbBootTimeline.GLOW_START) / XmbBootTimeline.GLOW_TIME) *
                (1f - XmbBootTimeline.ease((t - XmbBootTimeline.WHOLE_START) / XmbBootTimeline.WHOLE_TIME))
            if (glowAlpha > 0f) drawLayer(glow, left, top, boxW, boxH, glowAlpha)
            val footerAlpha = XmbBootTimeline.clamp01((t - XmbBootTimeline.WHOLE_START) / XmbBootTimeline.WHOLE_TIME) * (1f - fade(t))
            if (footerAlpha > 0f) drawLayer(footer, left, top, boxW, boxH, footerAlpha)
        }
    }
}

private fun fade(t: Float) = XmbBootTimeline.ease((t - XmbBootTimeline.FADE_START) / XmbBootTimeline.FADE_TIME)

private fun DrawScope.drawLayer(image: ImageBitmap, left: Float, top: Float, w: Float, h: Float, alpha: Float) {
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.High,
    )
}

/** Where the light is (scene units, relative to the logo) and how it falls off around it. */
private class Light(val x: Float, val y: Float, val reach: Float, val stops: Array<Pair<Float, Color>>)

/**
 * The script's light at [t]: brightness at distance d is color / (a0 + a1 d + a2 d^2), the
 * attenuation going from the scene's (0, 1, 4) to the script's (0.1, 0.1, 0.1) as it sweeps in,
 * and back as it leaves. Null while it is dark.
 */
private fun light(t: Float): Light? {
    val fadeP = fade(t)
    val inP = XmbBootTimeline.clamp01((t - XmbBootTimeline.LIGHT_START) / XmbBootTimeline.LIGHT_TIME)
    val color: Float
    val x: Float
    val y: Float
    val k: Float // 0 = the scene's attenuation, 1 = the script's
    if (t < XmbBootTimeline.FADE_START) {
        color = inP
        x = -3f + 3f * inP
        y = 0.5f
        k = inP
    } else {
        color = 1f - fadeP
        x = 2.25f * fadeP
        y = 0.5f + 1.5f * fadeP
        k = 1f - fadeP
    }
    if (color <= 0.001f) return null
    val a0 = 0.1f * k
    val a1 = 1f + (0.1f - 1f) * k
    val a2 = 4f + (0.1f - 4f) * k
    // Out to where it drops under 1%.
    val target = color / 0.01f
    val reach = ((-a1 + sqrt(a1 * a1 - 4f * a2 * (a0 - target))) / (2f * a2)).coerceAtLeast(0.05f)
    val stops = Array(9) { i ->
        val f = i / 8f
        val d = f * reach
        val denominator = a0 + a1 * d + a2 * d * d
        val b = if (denominator <= 1e-4f) 1f else (color / denominator).coerceIn(0f, 1f)
        f to Color.White.copy(alpha = b)
    }
    return Light(x, y, reach, stops)
}

/**
 * The console's startup sound, from the user's firmware (coldboot_stereo.ac3). Played by the
 * platform where it decodes AC3, and through the core's FFmpeg where it does not.
 */
object XmbBootSound {
    fun start(context: Context): MediaPlayer? {
        val ac3 = XmbFirmware.file("vsh/resource/coldboot_stereo.ac3") ?: return null
        val source = XmbMedia.decodedAudio(context, ac3) ?: ac3
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(source.absolutePath)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }.getOrNull()
    }
}

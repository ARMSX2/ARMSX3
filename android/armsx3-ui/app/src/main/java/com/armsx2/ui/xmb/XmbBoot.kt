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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The console's boot animation, with ARMSX3 where it said PS3.
 *
 * The order and timing are the console's, measured off a recording of a real PS3 starting (less the
 * two and a half seconds of black from power-on): the wave comes up on its own over black, the
 * logo is lit from left to right at the right of the screen, the
 * colour rises behind it from black to the month's own, the logo goes, and the XMB's icons come
 * in as the colour arrives. The light that sweeps the logo uses the falloff of the firmware's own
 * coldboot script (coldboot.jsx), so the logo lights from the side the light comes from.
 *
 * The wave and the colour are XmbGlView's own, held down through [XmbOriginalColors.bootWave] and
 * [XmbOriginalColors.bootColor]. [onProgress] reports the seconds, for the icons at the end.
 */
object XmbBootTimeline {
    const val BLACK_TIME = 0.5f
    const val WAVE_START = 0.5f
    const val WAVE_TIME = 1.5f
    const val LIGHT_START = 2.0f
    const val LIGHT_TIME = 0.75f
    const val COLOR_START = 2.5f
    const val COLOR_TIME = 4.0f
    const val FADE_START = 4.75f
    const val FADE_TIME = 0.5f
    const val REVEAL_START = 5.6f
    const val REVEAL_TIME = 0.9f
    const val END = 6.5f

    fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    fun ease(x: Float): Float = clamp01(x).let { it * it * (3f - 2f * it) }

    /** The black in front of everything before the wave starts (and the GL view has drawn). */
    fun black(t: Float): Float = 1f - ease((t - BLACK_TIME) / 0.25f)

    /** How much of the wave shows. */
    fun wave(t: Float): Float = ease((t - WAVE_START) / WAVE_TIME)

    /** How much of the colour shows. */
    fun color(t: Float): Float = ease((t - COLOR_START) / COLOR_TIME)

    /** 0..1 as the XMB's own icons come in, at the end. */
    fun reveal(t: Float): Float = ease((t - REVEAL_START) / REVEAL_TIME)
}

@Composable
fun XmbBoot(onProgress: (Float) -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    // The firmware's font and boot sound, found a moment after launch (once the core has its data
    // folder), then the logo drawn in that font and the sound made ready, so the two start together.
    val ready by produceState<BootReady?>(null) {
        value = withContext(Dispatchers.IO) {
            XmbFirmwareReady.awaitFirmware(FIRMWARE_WAIT_MS)
            val layers = runCatching { XmbWordmark.render(LOGO_LETTER_PX) }.getOrNull()
            BootReady(layers, if (layers != null) XmbBootSound.prepare(context) else null)
        }
    }
    val layers = ready?.layers
    var t by remember { mutableFloatStateOf(0f) }
    var skipped by remember { mutableStateOf(false) }
    val finished by rememberUpdatedState(onFinished)
    val progress by rememberUpdatedState(onProgress)

    DisposableEffect(Unit) {
        XmbInput.blocked = true
        XmbInput.skip = { skipped = true }
        // The app's music waits, silent, for the boot sound to finish (XmbBootMusic).
        XmbBootMusic.hold()
        XmbOriginalColors.bootWave = 0f
        XmbOriginalColors.bootColor = 0f
        onDispose {
            XmbInput.blocked = false
            XmbInput.skip = null
            XmbOriginalColors.bootWave = 1f
            XmbOriginalColors.bootColor = 1f
            // The sound plays on past the animation, as the console's does; with none, the music
            // comes in now.
            if (ready?.sound == null) XmbBootMusic.release()
        }
    }
    LaunchedEffect(skipped) {
        if (skipped) XmbBootMusic.skip()
    }
    LaunchedEffect(ready) {
        val r = ready ?: return@LaunchedEffect
        // Without a logo to draw there is nothing to show: straight to the XMB.
        if (r.layers == null) {
            XmbOriginalColors.bootWave = 1f
            XmbOriginalColors.bootColor = 1f
            finished()
            return@LaunchedEffect
        }
        // The clock and the sound start together, once the logo is ready, so a slow first frame
        // cannot eat the sweep or put the sound ahead of it.
        XmbBootMusic.play(r.sound)
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            var seconds = (now - start) / 1_000_000_000f
            if (skipped) seconds = maxOf(seconds, XmbBootTimeline.FADE_START + XmbBootTimeline.FADE_TIME)
            t = seconds
            XmbOriginalColors.bootWave = XmbBootTimeline.wave(seconds)
            XmbOriginalColors.bootColor = XmbBootTimeline.color(seconds)
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
        // Black until the wave starts, so nothing of the library shows while GL comes up.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = XmbBootTimeline.black(t) }.background(Color.Black))
        val l = layers ?: return@BoxWithConstraints
        val logo = remember(l) { l.logo.asImageBitmap() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Where the console puts its logo, in a 16:9 frame fitted to the screen: the word's letters
        // 0.058 of the frame high (the PS3 letters were 0.078; ARMSX3 has twice as many, set wide),
        // its middle 0.495 down, the whole ending 0.925 across.
        val frameW = minOf(w, h * 16f / 9f)
        val frameH = frameW * 9f / 16f
        val frameLeft = (w - frameW) / 2f
        val frameTop = (h - frameH) / 2f
        val unit = 0.058f * frameH
        val boxW = l.unitsWide * unit
        val boxH = l.unitsHigh * unit
        val left = frameLeft + 0.925f * frameW - boxW
        val top = frameTop + 0.495f * frameH - (l.wordTop + 0.5f) * unit

        // The logo, lit. Drawn into its own layer so the light can mask it.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val light = light(t)
            val logoAlpha = 1f - fade(t)
            if (light == null || logoAlpha <= 0f) return@Canvas
            drawLayer(logo, left, top, boxW, boxH, logoAlpha)
            // Scene units to pixels: the script's logo plane is 2.56 units wide.
            val s = boxW / 2.56f
            val centre = Offset(left + boxW / 2f + light.x * s, top + boxH / 2f - light.y * s)
            drawRect(
                Brush.radialGradient(
                    *light.stops,
                    center = centre,
                    radius = (light.reach * s).coerceAtLeast(1f),
                ),
                blendMode = BlendMode.DstIn,
            )
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
/** What the boot needs before its clock starts: the logo, and the boot sound when there is one. */
private class BootReady(val layers: XmbWordmark.Layers?, val sound: MediaPlayer?)

/** How long the boot waits for the firmware's font and sound before going on without them. */
private const val FIRMWARE_WAIT_MS = 1500L

/** The logo is drawn with letters this tall, then scaled to the screen. */
private const val LOGO_LETTER_PX = 120f

/**
 * The boot sound handing over to the app's music. The console's boot sound runs ten seconds, on
 * past the animation into the XMB, so it plays to its end rather than stopping with the animation;
 * the app's music waits silent until then ([hold]), and after a moment of quiet fades in. Skipping
 * the boot fades the sound out first. This is the app's handover for music that does not blend:
 * out over 1.5 s, 0.6 s of quiet, in over 2 s.
 */
object XmbBootMusic {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var job: kotlinx.coroutines.Job? = null

    /** The app's music quiet until [release]. */
    fun hold() {
        job?.cancel()
        com.armsx2.LibraryMusic.setShare(0f)
    }

    /** Start the boot sound; the music follows when it ends. */
    fun play(sound: MediaPlayer?) {
        if (sound == null) {
            release()
            return
        }
        player = sound
        sound.setOnCompletionListener { release() }
        runCatching { sound.start() }.onFailure { release() }
    }

    /** The boot was skipped: the sound fades out, then the music comes in. */
    fun skip() {
        val p = player ?: return
        job?.cancel()
        job = scope.launch {
            fade(1500) { v -> runCatching { p.setVolume(1f - v, 1f - v) } }
            release()
        }
    }

    /** The sound is over: quiet a moment, then the music fades in. */
    fun release() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        job?.cancel()
        job = scope.launch { fadeInMusic() }
    }

    /** The app's music in, as after the boot sound, on its own: nothing that leaves cuts it short. */
    fun fadeIn() {
        job?.cancel()
        job = scope.launch { fadeInMusic() }
    }

    /** After 0.6 s of quiet, the app's music in over 2 s, from wherever it was held. */
    private suspend fun fadeInMusic() {
        try {
            com.armsx2.LibraryMusic.setShare(0f)
            kotlinx.coroutines.delay(600)
            fade(2000) { v -> com.armsx2.LibraryMusic.setShare(maxOf(v, 0.01f)) }
        } finally {
            com.armsx2.LibraryMusic.setShare(1f)
        }
    }

    private suspend fun fade(ms: Long, step: (Float) -> Unit) {
        val start = android.os.SystemClock.uptimeMillis()
        while (true) {
            val v = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
            step(v)
            if (v >= 1f) break
            kotlinx.coroutines.delay(30)
        }
    }
}

object XmbBootSound {
    /** The firmware's boot sound, decoded and prepared to start at once, or null. Off the main thread. */
    fun prepare(context: Context): MediaPlayer? {
        val ac3 = XmbFirmware.file("vsh/resource/coldboot_stereo.ac3") ?: return null
        val source = XmbMedia.decodedAudio(context, ac3) ?: ac3
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(source.absolutePath)
            player.prepare()
            player
        } catch (_: Exception) {
            player.release()
            null
        }
    }
}

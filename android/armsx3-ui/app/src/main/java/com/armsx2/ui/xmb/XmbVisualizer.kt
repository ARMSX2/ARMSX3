package com.armsx2.ui.xmb

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The music player's visualizer: how loud a song is, band by band, thirty times a second, worked
 * out ahead of time rather than listened to as it plays. Android's Visualizer hears the music
 * playing, but only with the microphone permission, which a music player has no business asking
 * for. So the song is decoded once in the background (MediaExtractor and MediaCodec, a few seconds
 * for a four-minute song), the last 1024 samples at each thirtieth of a second go through an FFT
 * into [BANDS] bands from 50 Hz up, and the result (about 115 KB for four minutes) is kept in the
 * cache for the next time. The drawing follows the player's position through it.
 */
object XmbVisualizer {
    const val FPS = 30
    const val BANDS = 16
    private const val N = 1024
    private const val MAGIC = 0x58564953 // "XVIS"
    private const val VERSION = 1

    /** A song's bands, 0..1, frame by frame. */
    class Levels(val frames: Int, private val data: ByteArray) {
        fun band(frame: Int, band: Int): Float =
            if (frame !in 0 until frames) 0f else (data[frame * BANDS + band].toInt() and 0xFF) / 255f

        internal fun bytes() = data
    }

    private val memory = object : LinkedHashMap<String, Levels>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Levels>?) = size > 3
    }

    /**
     * [song]'s levels (its path, or its content Uri in an added folder): from memory, the cache, or
     * worked out now. Null for what cannot be decoded (MIDI).
     */
    suspend fun levels(context: Context, song: String): Levels? = withContext(Dispatchers.Default) {
        val key = sha1("$song|${XmbDecodedSongs.stamp(context, song)}")
        synchronized(memory) { memory[key] }?.let { return@withContext it }
        val cached = File(File(context.cacheDir, "xmb-music/vis").apply { mkdirs() }, "$key.vis")
        // A song only FFmpeg plays is read from the WAV it was decoded to.
        val levels = read(cached)
            ?: (analyze(context, song) ?: XmbDecodedSongs.decoded(context, song)?.let { analyze(context, it.path) })?.also { write(cached, it) }
            ?: return@withContext null
        synchronized(memory) { memory[key] = levels }
        levels
    }

    private fun read(file: File): Levels? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { ins ->
            if (ins.readInt() != MAGIC || ins.readInt() != VERSION || ins.readInt() != BANDS) return null
            val frames = ins.readInt()
            if (frames <= 0 || frames > FPS * 60 * 120) return null
            val data = ByteArray(frames * BANDS)
            ins.readFully(data)
            Levels(frames, data)
        }
    }.getOrNull()

    private fun write(file: File, levels: Levels) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(BANDS)
                out.writeInt(levels.frames)
                out.write(levels.bytes())
            }
            tmp.renameTo(file)
        }
    }

    private suspend fun analyze(context: Context, song: String): Levels? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (com.armsx2.LibraryMusic.isDocument(song)) extractor.setDataSource(context, android.net.Uri.parse(song), null)
            else extractor.setDataSource(song)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val analyzer = Analyzer(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var steps = 0
            while (true) {
                if (++steps % 64 == 0) currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i) ?: return null
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    analyzer.reformat(
                        f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT,
                    )
                } else if (o >= 0) {
                    val buffer = decoder.getOutputBuffer(o)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        analyzer.feed(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (analyzer.frames > FPS * 60 * 120) break
                }
            }
            return analyzer.levels()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Mono samples in, a frame of band energies (dB) out every 1/[FPS] s, from the last [N] samples. */
    private class Analyzer(var rate: Int, var channels: Int) {
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private val ring = FloatArray(N)
        private var at = 0
        private var filled = 0
        private var sinceFrame = 0
        private var hop = (rate / FPS).coerceAtLeast(1)
        private var out = FloatArray(BANDS * 1024)
        var frames = 0
            private set
        private val re = FloatArray(N)
        private val im = FloatArray(N)
        private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
        private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }
        private val rev = IntArray(N) { Integer.reverse(it) ushr (32 - 10) }
        private var edges = bandEdges()

        fun reformat(rate: Int, channels: Int, encoding: Int) {
            this.rate = rate
            this.channels = channels.coerceAtLeast(1)
            this.encoding = encoding
            hop = (rate / FPS).coerceAtLeast(1)
            edges = bandEdges()
        }

        // FFT bins bounding each band, log-spaced from 50 Hz to 16 kHz (or half the rate).
        private fun bandEdges(): IntArray {
            val top = minOf(16_000.0, rate / 2.0 - 1)
            return IntArray(BANDS + 1) { b ->
                val hz = 50.0 * (top / 50.0).pow(b / BANDS.toDouble())
                (hz * N / rate).toInt().coerceIn(1, N / 2 - 1)
            }
        }

        fun feed(pcm: java.nio.ByteBuffer) {
            val c = channels.coerceAtLeast(1)
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val f = pcm.asFloatBuffer()
                while (f.remaining() >= c) {
                    var s = 0f
                    repeat(c) { s += f.get() }
                    push(s / c)
                }
            } else {
                val sh = pcm.asShortBuffer()
                while (sh.remaining() >= c) {
                    var s = 0f
                    repeat(c) { s += sh.get() / 32768f }
                    push(s / c)
                }
            }
        }

        private fun push(sample: Float) {
            ring[at] = sample
            at = (at + 1) % N
            if (filled < N) filled++
            if (++sinceFrame >= hop) {
                sinceFrame = 0
                frame()
            }
        }

        private fun frame() {
            for (i in 0 until N) {
                val s = if (i < N - filled) 0f else ring[(at + i) % N]
                re[rev[i]] = s * window[i]
                im[rev[i]] = 0f
            }
            // Iterative radix-2 FFT, in place.
            var size = 2
            while (size <= N) {
                val half = size / 2
                val step = N / size
                var start = 0
                while (start < N) {
                    for (k in 0 until half) {
                        val wr = cosT[k * step]
                        val wi = -sinT[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                    start += size
                }
                size *= 2
            }
            if ((frames + 1) * BANDS > out.size) out = out.copyOf(out.size * 2)
            for (b in 0 until BANDS) {
                val lo = edges[b]
                val hi = maxOf(edges[b + 1], lo + 1)
                var e = 0f
                for (k in lo until hi) e += re[k] * re[k] + im[k] * im[k]
                out[frames * BANDS + b] = (10 * log10(e / (hi - lo) + 1e-12)).toFloat()
            }
            frames++
        }

        /**
         * 0..1 per band: each band against its own loud end (its 97th percentile), across 40 dB,
         * so the quiet top bands move as much as the bass does.
         */
        fun levels(): Levels? {
            if (frames == 0) return null
            val data = ByteArray(frames * BANDS)
            for (b in 0 until BANDS) {
                val column = FloatArray(frames) { out[it * BANDS + b] }
                val sorted = column.sortedArray()
                val ceiling = sorted[((frames - 1) * 0.97f).toInt()]
                val floor = ceiling - 40f
                for (f in 0 until frames) {
                    val v = ((column[f] - floor) / (ceiling - floor)).coerceIn(0f, 1f)
                    data[f * BANDS + b] = (v * 255).toInt().toByte()
                }
            }
            return Levels(frames, data)
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * How hard the song pushes now, 0..1, from its levels at the player's position: mostly the bass,
 * with a kick on each sudden rise over its recent average (a beat), quick to rise and slow to fall
 * as a meter is. Asked once a frame, on whichever thread draws.
 */
internal class MusicDrive {
    private var level = 0f
    private var average = 0f

    fun next(levels: XmbVisualizer.Levels?, positionMs: Long, playing: Boolean): Float {
        if (levels == null || !playing) {
            level *= 0.95f
            return level
        }
        val frame = (positionMs * XmbVisualizer.FPS / 1000).toInt()
        var bass = 0f
        for (b in 0 until 4) bass += levels.band(frame, b)
        var mid = 0f
        for (b in 4 until 10) mid += levels.band(frame, b)
        val energy = bass / 4f * 0.7f + mid / 6f * 0.3f
        average += (energy - average) * 0.03f
        val beat = (energy - average).coerceAtLeast(0f) * 2.5f
        val target = (energy * 0.75f + beat).coerceIn(0f, 1f)
        level += (target - level) * (if (target > level) 0.4f else 0.07f)
        return level
    }
}

/** The visualizers to choose between in Now Playing: the console's, then the PSP's lines. */
internal enum class XmbVisualizerKind(val id: String, val title: String) {
    Waves("waves", "Waves"),
    Lines("lines", "Lines"),
}

/**
 * Waves, as the console's: XMB mode's own wave (XmbGlView, the firmware's wave) in the mood the
 * console's music player gives it (lines.qrc override/music_1: its sky, colours, placement and
 * glare), pushed by the song through [MusicDrive], so it billows on the loud parts and settles on
 * the quiet.
 */
@Composable
internal fun XmbWavesVisualizer(levels: XmbVisualizer.Levels?, positionMs: () -> Long, playing: () -> Boolean, modifier: Modifier = Modifier) {
    val current = androidx.compose.runtime.rememberUpdatedState(levels)
    val position = androidx.compose.runtime.rememberUpdatedState(positionMs)
    val on = androidx.compose.runtime.rememberUpdatedState(playing)
    val drive = remember { MusicDrive() }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            com.armsx2.ui.home.XmbGlView(ctx).apply {
                mood = "music_1"
                this.drive = { drive.next(current.value, position.value(), on.value()) }
            }
        },
        modifier = modifier,
    )
}

/**
 * The visualizer drawn: glowing lines across the screen, still at the edges and swelling in the
 * middle, as the PSP's music player draws them, each line a range of the bands (the bass the
 * slowest and widest), in [color]. [positionMs] is read every frame; at a pause, or with no levels
 * (a song still being read, or MIDI), the lines settle to a slow ripple.
 */
@Composable
internal fun XmbVisualizerView(
    levels: XmbVisualizer.Levels?,
    positionMs: () -> Long,
    playing: () -> Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
) {
    val lines = 5
    val amp = remember { FloatArray(lines) }
    val phase = remember { FloatArray(lines) }
    val last = remember { LongArray(1) }
    val frameTime by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    Canvas(modifier) {
        val now = frameTime
        val dt = if (last[0] == 0L) 0f else ((now - last[0]) / 1e9f).coerceIn(0f, 0.1f)
        last[0] = now
        val on = playing()
        val pos = positionMs()
        val frame = (pos * XmbVisualizer.FPS / 1000).toInt()
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val height = h * 0.34f * strength
        val path = Path()
        for (i in 0 until lines) {
            // Line i follows bands [3i, 3i + 3), the last one the top four.
            val from = i * 3
            val to = if (i == lines - 1) XmbVisualizer.BANDS else from + 3
            var target = 0f
            if (on && levels != null) {
                for (b in from until to) target += levels.band(frame, b)
                target /= (to - from)
            }
            // Quick to rise, slow to fall, as a meter is.
            val k = if (target > amp[i]) 0.45f else 0.10f
            amp[i] += (target - amp[i]) * k
            phase[i] += dt * (0.9f + i * 0.55f) * (0.6f + amp[i] * 1.6f)
            val a = 0.035f + amp[i] * (1f - i * 0.1f)
            val f1 = 1.2f + i * 0.65f
            path.reset()
            val step = 6f
            var x = 0f
            while (x <= w + step) {
                val t = x / w
                val env = exp(-((t - 0.5f) / 0.23f).let { it * it })
                val y = cy + height * a * env * (
                    sin(2f * PI.toFloat() * f1 * t + phase[i]) * 0.72f +
                        sin(2f * PI.toFloat() * (f1 * 2.1f) * t - phase[i] * 1.37f) * 0.28f
                    ) * (if (i % 2 == 0) 1f else -1f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            val glow = color.copy(alpha = 0.10f * strength)
            drawPath(path, glow, style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            drawPath(path, color.copy(alpha = 0.28f * strength), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            drawPath(path, Color.White.copy(alpha = 0.75f * strength), style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        }
    }
}

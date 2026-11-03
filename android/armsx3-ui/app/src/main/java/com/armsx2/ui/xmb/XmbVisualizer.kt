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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The music player's visualizer: how loud a song is, band by band, thirty times a second, worked
 * out ahead of time rather than listened to as it plays. Android's Visualizer hears the music
 * playing, but only with the microphone permission, which a music player has no business asking
 * for. So the song is decoded once in the background (MediaExtractor and MediaCodec, a few seconds
 * for a four-minute song), the last 1024 samples at each thirtieth of a second go through an FFT
 * into [BANDS] bands from 50 Hz up, and the result (about 115 KB for four minutes) is kept in the
 * cache for the next time. The drawing follows the player's position through it.
 *
 * The same pass works out the console's own spectrum too ([ConsoleSpectrum]), sixty times a
 * second, for the visualizers that read it as the console's do (Valley).
 */
object XmbVisualizer {
    const val FPS = 30
    const val BANDS = 16
    /** The console's spectrum, frames a second: one a frame, as its music player hands it over. */
    const val SPECTRUM_FPS = 60
    /** Its points a frame: the 64 of each channel that make one of Valley's rows. */
    const val COLUMNS = 128
    private const val N = 1024
    private const val MAGIC = 0x58564953 // "XVIS"
    private const val VERSION = 2

    /** A song's bands, 0..1, frame by frame, and the console's spectrum ([column]). */
    class Levels(
        val frames: Int,
        private val data: ByteArray,
        val spectrumFrames: Int = 0,
        private val spectrum: ByteArray? = null,
    ) {
        fun band(frame: Int, band: Int): Float =
            if (frame !in 0 until frames) 0f else (data[frame * BANDS + band].toInt() and 0xFF) / 255f

        /**
         * The console's spectrum at [frame] ([SPECTRUM_FPS] a second), point [c] of [COLUMNS]: 0..3,
         * as its music player gives it. Points 0..63 are the left channel from the lows up, 64..127
         * the right from the highs down.
         */
        fun column(frame: Int, c: Int): Float =
            if (spectrum == null || frame !in 0 until spectrumFrames) 0f
            else (spectrum[frame * COLUMNS + c].toInt() and 0xFF) * (3f / 255f)

        internal fun bytes() = data
        internal fun spectrumBytes() = spectrum
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
            if (ins.readInt() != SPECTRUM_FPS || ins.readInt() != COLUMNS) return null
            val spectrumFrames = ins.readInt()
            if (spectrumFrames < 0 || spectrumFrames > ConsoleSpectrum.MAX_FRAMES) return null
            val spectrum = ByteArray(spectrumFrames * COLUMNS)
            ins.readFully(spectrum)
            Levels(frames, data, spectrumFrames, spectrum)
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
                out.writeInt(SPECTRUM_FPS)
                out.writeInt(COLUMNS)
                out.writeInt(levels.spectrumFrames)
                levels.spectrumBytes()?.let { out.write(it, 0, levels.spectrumFrames * COLUMNS) }
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
        private val console = ConsoleSpectrum(rate)

        fun reformat(rate: Int, channels: Int, encoding: Int) {
            this.rate = rate
            this.channels = channels.coerceAtLeast(1)
            this.encoding = encoding
            hop = (rate / FPS).coerceAtLeast(1)
            edges = bandEdges()
            console.reformat(rate)
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
                    var l = 0f
                    var r = 0f
                    for (i in 0 until c) {
                        val v = f.get()
                        s += v
                        if (i == 0) l = v
                        if (i == 1) r = v
                    }
                    push(s / c)
                    console.push(l, if (c > 1) r else l)
                }
            } else {
                val sh = pcm.asShortBuffer()
                while (sh.remaining() >= c) {
                    var s = 0f
                    var l = 0f
                    var r = 0f
                    for (i in 0 until c) {
                        val v = sh.get() / 32768f
                        s += v
                        if (i == 0) l = v
                        if (i == 1) r = v
                    }
                    push(s / c)
                    console.push(l, if (c > 1) r else l)
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
            return Levels(frames, data, console.frames, console.bytes())
        }
    }

    /**
     * The console's own spectrum, worked out as its music player does for the visualizers
     * (soundvisualizer_plugin): each channel's last 512 samples through a Gaussian window and an
     * FFT, the magnitudes smoothed across 17 bins, read along a log-frequency scale of 64 steps an
     * octave, and pressed into 3 ln(1 + 512 m) / ln 513. The console runs at 48 kHz, so the scale
     * is read at the same frequencies for a song at any other rate. Kept are the points of each
     * channel that make one of Valley's rows ([COLUMNS]), [SPECTRUM_FPS] times a second.
     */
    private class ConsoleSpectrum(private var rate: Int) {
        private val ringL = FloatArray(SIZE)
        private val ringR = FloatArray(SIZE)
        private var at = 0
        private var count = 0L
        private var due = SIZE / 2L
        var frames = 0
            private set
        private var out = ByteArray(COLUMNS * 1024)
        private val re = FloatArray(SIZE)
        private val im = FloatArray(SIZE)
        private val smooth = FloatArray(SIZE / 2)
        private val position = FloatArray(COLUMNS)

        init { positions() }

        fun reformat(rate: Int) {
            this.rate = rate.coerceAtLeast(1)
            positions()
        }

        // Where each point reads the spectrum, in this song's bins: the console's log scale (64 steps
        // an octave over its 256 bins) at the console's 48 kHz.
        private fun positions() {
            val top = 2.0.pow(255.015625 / 64) - 1
            fun at(j: Int) = (255 * (2.0.pow((j + 1.0 / 64) / 64) - 1) / top * 48_000.0 / rate).toFloat().coerceAtMost(255f)
            for (i in 0 until 64) {
                val j = i * 255 / 64
                position[i] = at(j)
                position[64 + i] = at(255 - j)
            }
        }

        fun push(l: Float, r: Float) {
            ringL[at] = l
            ringR[at] = r
            at = (at + 1) and (SIZE - 1)
            if (++count < due) return
            // Centred on its moment: frame f covers the samples either side of f / SPECTRUM_FPS s.
            if (frames < MAX_FRAMES) frame()
            due = SIZE / 2L + ((frames.toLong() * rate) / SPECTRUM_FPS)
        }

        fun bytes(): ByteArray = out.copyOf(frames * COLUMNS)

        private fun frame() {
            if ((frames + 1) * COLUMNS > out.size) out = out.copyOf(out.size * 2)
            val base = frames * COLUMNS
            channel(ringL)
            for (i in 0 until 64) out[base + i] = level(position[i])
            channel(ringR)
            for (i in 64 until COLUMNS) out[base + i] = level(position[i])
            frames++
        }

        private fun channel(ring: FloatArray) {
            for (i in 0 until SIZE) {
                val v = ring[(at + i) and (SIZE - 1)] * WINDOW[i]
                re[REV[i]] = v
                im[REV[i]] = 0f
            }
            var size = 2
            while (size <= SIZE) {
                val half = size / 2
                val step = SIZE / size
                var start = 0
                while (start < SIZE) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = -SIN[k * step]
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
            // Magnitudes over N, then spread across the kernel's 17 bins.
            smooth.fill(0f)
            for (j in 0 until SIZE / 2) {
                val m = sqrt(re[j] * re[j] + im[j] * im[j]) / SIZE / KERNEL_SUM
                for (t in 0 until KERNEL.size) {
                    val idx = j - 8 + t
                    if (idx in 0 until SIZE / 2) smooth[idx] += m * KERNEL[t]
                }
            }
        }

        private fun level(p: Float): Byte {
            val i0 = floor(p).toInt()
            val f = p - i0
            val m = if (f > 0f && i0 + 1 < SIZE / 2) smooth[i0] * (1 - f) + smooth[i0 + 1] * f else smooth[i0]
            val s = 3f * ln(1f + 512f * m) / LN513
            return (s / 3f * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }

        companion object {
            private const val SIZE = 512
            /** Twenty minutes a song; past that, Valley rests. */
            const val MAX_FRAMES = SPECTRUM_FPS * 60 * 20
            private val LN513 = ln(513f)

            // The console's window: a Gaussian across 2n + 1 points, x from -2.355 to 2.355. Its
            // first point ends up 0: the firmware subtracts the first point from each in turn,
            // starting with itself.
            private fun gaussian(n: Int): FloatArray {
                val w = FloatArray(2 * n + 1) { i ->
                    val x = (2.355 * (i - n) / n).toFloat()
                    (2.7.pow((-(x * x) / 0.56f).toDouble()) * 2.5 / sqrt(6.36159f)).toFloat()
                }
                w[0] = 0f
                return w
            }

            private val WINDOW = gaussian(SIZE / 2)
            private val KERNEL = gaussian(8)
            private val KERNEL_SUM = KERNEL.sum()
            private val COS = FloatArray(SIZE / 2) { cos(2 * PI * it / SIZE).toFloat() }
            private val SIN = FloatArray(SIZE / 2) { sin(2 * PI * it / SIZE).toFloat() }
            private val REV = IntArray(SIZE) { Integer.reverse(it) ushr (32 - 9) }
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

/**
 * The console's views are made for its 16:9 screen. A narrower one (a phone held upright) sees as
 * much across as that and more above and below, rather than losing the sides: [fovy] (degrees, for
 * 16:9) as it needs to be at [aspect].
 */
internal fun xmbFovy(fovy: Float, aspect: Float): Float {
    if (aspect >= 16f / 9f) return fovy
    val half = Math.toRadians(fovy / 2.0)
    return Math.toDegrees(2.0 * kotlin.math.atan(kotlin.math.tan(half) * (16.0 / 9.0) / aspect)).toFloat().coerceAtMost(150f)
}

/** The visualizers to choose between in Now Playing: the console's, then the PSP's lines. */
internal enum class XmbVisualizerKind(val id: String, val title: String) {
    Waves("waves", "Waves"),
    Valley("valley", "Valley"),
    Gaia("gaia", "Gaia"),
    Lines("lines", "Lines"),
}

/**
 * The right stick, for a visualizer that reads it as the console's player does (Valley's presets):
 * set while one shows; MainActivityRuntime hands it the stick, -1..1, up negative.
 */
internal object XmbVisualizerStick {
    @Volatile var handler: ((Float, Float) -> Unit)? = null
}

/**
 * Gaia, the console's Earth ([XmbGaiaView]): its presets' flights one after another; the right stick
 * or a swipe moves on to another at once.
 */
@Composable
internal fun XmbGaiaVisualizer(modifier: Modifier = Modifier) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        var held = false
        val h: (Float, Float) -> Unit = { x, y ->
            val v = maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) * 128f
            if (!held && v > 70f) {
                held = true
                XmbGaiaPresets.flick()
            } else if (held && v < 66f) {
                held = false
            }
        }
        XmbVisualizerStick.handler = h
        onDispose { if (XmbVisualizerStick.handler === h) XmbVisualizerStick.handler = null }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { ctx -> XmbGaiaView(ctx) }, modifier = modifier)
}

/**
 * Valley, the console's canyon ([XmbValleyView]), on the song's own spectrum as the console works
 * it out. The right stick and a swipe change its presets: left and right another of the group, up
 * and down another group. Each direction goes once a push, as the console's player reads the
 * stick: past 70 of 128 to go, back under 66 to go again.
 */
@Composable
internal fun XmbValleyVisualizer(levels: XmbVisualizer.Levels?, positionMs: () -> Long, playing: () -> Boolean, modifier: Modifier = Modifier) {
    val current = androidx.compose.runtime.rememberUpdatedState(levels)
    val position = androidx.compose.runtime.rememberUpdatedState(positionMs)
    val on = androidx.compose.runtime.rememberUpdatedState(playing)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val held = BooleanArray(4)
        val h: (Float, Float) -> Unit = { x, y ->
            val push = floatArrayOf(-x, x, -y, y)
            for (d in 0 until 4) {
                val v = push[d] * 128f
                if (!held[d] && v > 70f) {
                    held[d] = true
                    when (d) {
                        0 -> XmbValleyPresets.flick(-1, 0)
                        1 -> XmbValleyPresets.flick(1, 0)
                        2 -> XmbValleyPresets.flick(0, -1)
                        else -> XmbValleyPresets.flick(0, 1)
                    }
                } else if (held[d] && v < 66f) {
                    held[d] = false
                }
            }
        }
        XmbVisualizerStick.handler = h
        onDispose { if (XmbVisualizerStick.handler === h) XmbVisualizerStick.handler = null }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            XmbValleyView(ctx).apply {
                source = { out ->
                    val lv = current.value
                    if (lv == null || !on.value()) {
                        out.fill(0f)
                    } else {
                        val frame = (position.value() * XmbVisualizer.SPECTRUM_FPS / 1000).toInt()
                        for (c in out.indices) out[c] = lv.column(frame, c)
                    }
                }
            }
        },
        modifier = modifier,
    )
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

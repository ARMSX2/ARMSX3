package com.armsx2.ui.xmb

import java.io.File
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The PS3's XMB wave, made the way the console's firmware makes it.
 *
 * The console draws it from three parts, all read out of the firmware:
 *  - custom_render_plugin.sprx keeps a grid of springs, 19 lines of 19 points rooted on a small
 *    ring that wanders, pulled out at their free ends and shaken by noise: the LINE1.mnu settings
 *    (damping, length, tension, timestep, perturbation, end). The grid is then narrowed and widened
 *    along its length, and placed in the world by the same file's POS, ANG and ROT.
 *  - spurs/moyou/spline.elf (an SPU job) bends that grid through a lattice of 11x7x7 points, which
 *    lib/moyou/ffd_shader1 moves with time, and tessellates it as uniform cubic B-spline patches.
 *  - lib/moyou/lines1 lights each point by how much of it faces the eye, with a lookup that turns
 *    the folds seen edge on into the thin bright strands (textures/TGA/freslut1).
 *
 * This class is the first two, on the CPU: [update] advances the springs and fills the 19x19
 * control points, in clip space, that XmbFirmwareWave tessellates and lights on the GPU. The
 * settings and the strand lookup are read from the player's own firmware (lines.qrc) when it is
 * there. Two gains (the lattice's lengthwise stretch and the strength of its twist across the
 * band) and the camera were measured from an RSX capture of the console's XMB.
 */
class XmbWaveModel(initialState: FloatArray?) {

    /** LINE1.mnu, with the firmware's own defaults. */
    class Line(
        val damping: Float = 0.0001f,
        val length: Float = 0.306001f,
        val tension: Float = 0.25f,
        val brightness: Float = 0.701917f,
        val mipmapBias: Float = 1.86707f,
        val fresnel: Float = 0.638971f,
        val timestep: Float = 4f,
        val perturbation: Float = 0.0998587f,
        val pos: FloatArray = floatArrayOf(-7.67933f, -1.08844f, -6.40287f),
        val axis: FloatArray = floatArrayOf(-0.994157f, 0.0867576f, 0.065364f),
        val rotDeg: Float = 18.1208f,
        val end: FloatArray = floatArrayOf(3f, 0.4f, 0.2f),
        val scale1: FloatArray = floatArrayOf(5.67726f, 1.00077f, 1f),
        val scale2: FloatArray = floatArrayOf(2.82755f, 1.27579f, 2.88782f),
        val offset: FloatArray = floatArrayOf(0f, -0.469999f, 0f),
        val param1: Float = -1.33509f,
    )

    val line: Line = XmbWaveFirmware.line

    // ---- the spring grid (rows are lines, column 18 their roots) ----
    private val pos = FloatArray(N * 3)
    private val vel = FloatArray(N * 3)
    private val prev = FloatArray(N * 3)
    private var clock = 0f // the noise clock; the lattice's time is ten times it
    private var smooth = 0f // the roots' ring follows this, eased toward the clock
    private var counter = 0 // the noise sequence
    private var acc = 0f // fraction of a 60 Hz step not taken yet

    init {
        if (initialState != null && initialState.size >= N * 6) {
            System.arraycopy(initialState, 0, pos, 0, N * 3)
            System.arraycopy(initialState, N * 3, vel, 0, N * 3)
        } else {
            // Straight lines from the ring; a few seconds of the springs make them the console's.
            for (r in 0 until R) for (c in 0 until R) {
                val i = (r * R + c) * 3
                val ph = r / R.toFloat()
                pos[i] = (R - 1 - c) * 0.33f
                pos[i + 1] = (sin(11f * ph) * 0.5f + 0.5f) * line.end[1]
                pos[i + 2] = (cos(15f * ph) + 1f) * 0.5f * line.end[2]
            }
            repeat(12000) { step() }
        }
        System.arraycopy(pos, 0, prev, 0, pos.size)
        counter = (System.nanoTime() and 0xffffff).toInt()
    }

    /** One 60 Hz step of the springs (custom_render_plugin 0x4bed0, then 0x4bce0 for the roots). */
    private fun step() {
        System.arraycopy(pos, 0, prev, 0, pos.size)
        val ts = line.timestep
        clock += ts * 1e-4f
        if (clock > 10f) clock = 0f
        val l = line.length
        val k = line.tension
        for (r in 0 until R) for (c in 0 until R) {
            val i = r * R + c
            if (c > 0) spring(i, i - 1, l, k)
            if (c > 1) spring(i, i - 2, 2f * l, k * 10f)
            if (r > 0) spring(i, i - R, l, k)
            if (r > 1) spring(i, i - 2 * R, 2f * l, k * 10f)
            val p = line.perturbation
            vel[i * 3] += noise(++counter) * p
            vel[i * 3 + 1] += noise(++counter) * p
            vel[i * 3 + 2] += noise(++counter) * p
        }
        val dt = ts * 1e-4f
        val keep = 1f - line.damping * ts
        for (j in 0 until N * 3) {
            pos[j] += vel[j] * dt
            vel[j] *= keep
        }
        smooth += (clock - smooth) * 0.1f
        for (r in 0 until R) {
            vel[(r * R) * 3] += ts * 0.02f
            val ph = r / R.toFloat() + smooth
            val i = (r * R + R - 1) * 3
            pos[i] = 0f
            pos[i + 1] = (sin(ph * 11f) * 0.5f + 0.5f) * line.end[1]
            pos[i + 2] = (cos(ph * 15f) + 1f) * 0.5f * line.end[2]
            vel[i] = 0f; vel[i + 1] = 0f; vel[i + 2] = 0f
        }
    }

    private fun spring(a: Int, b: Int, rest: Float, k: Float) {
        val dx = pos[b * 3] - pos[a * 3]
        val dy = pos[b * 3 + 1] - pos[a * 3 + 1]
        val dz = pos[b * 3 + 2] - pos[a * 3 + 2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-12f) return
        val f = (len - rest) * k / len
        vel[a * 3] += dx * f; vel[a * 3 + 1] += dy * f; vel[a * 3 + 2] += dz * f
        vel[b * 3] -= dx * f; vel[b * 3 + 1] -= dy * f; vel[b * 3 + 2] -= dz * f
    }

    // ---- the lattice (ffd_shader1 at its 8x4x4 texels, edged out to 11x7x7) ----
    private val lattice = FloatArray(LZ * LY * LX * 2)

    private fun fillLattice(t: Float) {
        val c = (sin(0.1f * t) + 0.2f) / 1.2f
        val amp = (sin(2f * t) + 3f) * exp(-1e-4f * t)
        val bob = sin(0.25f * t)
        for (kk in 0 until LZ) for (jj in 0 until LY) for (ii in 0 until LX) {
            val u = (ii - 1).coerceIn(0, 7) / 8f
            val w = (kk - 1).coerceIn(0, 3) / 4f
            val dx = 0.2f * u + 1.3f * u * u - 0.15f
            val dy = 0.24f * (tanh((u - 0.5f) / 2f) + 1f) * amp * sin(7.85f * u - 2.5f * t - 1.25f) +
                bob + exp(-50f * (u - c) * (u - c)) + TWIST * sin(t - 2f * PI.toFloat() * w)
            val o = ((kk * LY + jj) * LX + ii) * 2
            lattice[o] = STRETCH * dx
            lattice[o + 1] = dy
        }
    }

    private val bx = FloatArray(4)
    private val by = FloatArray(4)
    private val bz = FloatArray(4)

    private fun weights(t: Float, out: FloatArray) {
        val t2 = t * t; val t3 = t2 * t; val m = 1f - t
        out[0] = m * m * m / 6f
        out[1] = (3f * t3 - 6f * t2 + 4f) / 6f
        out[2] = (-3f * t3 + 3f * t2 + 3f * t + 1f) / 6f
        out[3] = t3 / 6f
    }

    // ---- placing it: the line's world transform and the XMB camera ----
    private val rot = FloatArray(9).also { m ->
        var ax = line.axis[0]; var ay = line.axis[1]; var az = line.axis[2]
        val n = sqrt(ax * ax + ay * ay + az * az); ax /= n; ay /= n; az /= n
        val a = line.rotDeg * PI.toFloat() / 180f
        val c = cos(a); val s = sin(a); val ic = 1f - c
        m[0] = c + ax * ax * ic; m[1] = ax * ay * ic - az * s; m[2] = ax * az * ic + ay * s
        m[3] = ay * ax * ic + az * s; m[4] = c + ay * ay * ic; m[5] = ay * az * ic - ax * s
        m[6] = az * ax * ic - ay * s; m[7] = az * ay * ic + ax * s; m[8] = c + az * az * ic
    }

    /**
     * Advance by [dtSec] and fill [out] (19x19x4, row-major, rows = lines) with the control points
     * in clip space for a screen [aspect] wide. The console's camera keeps its 53 degree height and
     * shows more or less to the sides.
     */
    fun update(dtSec: Float, aspect: Float, out: FloatArray) {
        acc += dtSec.coerceIn(0f, 0.25f) * 60f
        var steps = 0
        while (acc > 1f && steps < 8) { step(); acc -= 1f; steps++ }
        if (acc > 1f) acc = 1f
        fillLattice(clock * 10f)
        val sy = SY
        val sx = SY / aspect
        for (i in 0 until N) {
            // The grid as the frame shows it, between two steps, then narrowed and widened along its
            // length (0x4f81c: cos, a fifth of a smoothstep, about PARAM1).
            val px = prev[i * 3] + (pos[i * 3] - prev[i * 3]) * acc
            var py = prev[i * 3 + 1] + (pos[i * 3 + 1] - prev[i * 3 + 1]) * acc
            var pz = prev[i * 3 + 2] + (pos[i * 3 + 2] - prev[i * 3 + 2]) * acc
            val d = px - line.param1
            val s = if (d < 0f) 0.3f else {
                val m = (d / 5f).coerceIn(0f, 1f)
                1.3f - cos(d * PI.toFloat() / 2f) * (1f - m) * (1f - m) * (1f + 2f * m)
            }
            py *= s; pz *= s
            // Into the lattice's box, through the lattice (spline.elf 0x3c68), and back out.
            val qx = (px - line.offset[0]) / line.scale1[0]
            val qy = (py - line.offset[1]) / line.scale1[1]
            val qz = (pz - line.offset[2]) / line.scale1[2]
            val sxl = qx.coerceIn(0f, 0.999f) * 8f
            val syl = qy.coerceIn(0f, 0.999f) * 4f
            val szl = qz.coerceIn(0f, 0.999f) * 4f
            val cx = floor(sxl).toInt(); val cy = floor(syl).toInt(); val cz = floor(szl).toInt()
            weights(sxl - cx, bx); weights(syl - cy, by); weights(szl - cz, bz)
            var ddx = 0f; var ddy = 0f
            for (c in 0 until 4) for (b in 0 until 4) {
                val wzy = bz[c] * by[b]
                val row = (((cz + c) * LY + cy + b) * LX + cx) * 2
                for (a in 0 until 4) {
                    val wgt = wzy * bx[a]
                    ddx += wgt * lattice[row + a * 2]
                    ddy += wgt * lattice[row + a * 2 + 1]
                }
            }
            val ox = (qx + ddx) * line.scale2[0]
            val oy = (qy + ddy) * line.scale2[1]
            val oz = qz * line.scale2[2]
            // The line object's place in the world, then the eye.
            val wx = rot[0] * ox + rot[1] * oy + rot[2] * oz + line.pos[0] - EYE_X
            val wy = rot[3] * ox + rot[4] * oy + rot[5] * oz + line.pos[1] - EYE_Y
            val wz = rot[6] * ox + rot[7] * oy + rot[8] * oz + line.pos[2] - EYE_Z
            val w = -wz
            out[i * 4] = sx * wx
            out[i * 4 + 1] = sy * wy
            out[i * 4 + 2] = 1.0002f * w - 0.2f
            out[i * 4 + 3] = w
        }
    }

    companion object {
        const val R = 19
        const val N = R * R
        private const val LX = 11
        private const val LY = 7
        private const val LZ = 7

        /** The XMB camera: 53 degrees tall, near 0.1, far 1000 (from the capture's particles). */
        private const val SY = 2.0057f
        private const val EYE_X = 0.39f
        private const val EYE_Y = 0.63f
        private const val EYE_Z = 1.87f

        /** The lattice's lengthwise stretch and the strength of its twist across the band. */
        private const val STRETCH = 6.4f
        private const val TWIST = 0.15f

        /** 0x4ab04: the console's integer noise, -1..1. */
        fun noise(n0: Int): Float {
            val n = (n0 shl 13) xor n0
            val t = (n * (n * n * 15731 + 789221) + 1376312589) and 0x7fffffff
            return 1f - t.toFloat() / 1073741824f
        }

        /** The strand lookup without firmware: the shape of freslut1, a spike at 0.057 with a long tail. */
        fun fallbackStrands(): FloatArray = FloatArray(512) { i ->
            val x = i.toFloat()
            if (x < 29f) 0.988f * exp(-((x - 29f) / 7.8f).let { it * it }) else 0.988f * exp(-(x - 29f) / 21.5f)
        }
    }
}

/**
 * The wave's settings and strand lookup from the player's firmware (dev_flash/vsh/resource/qgl/
 * lines.qrc), read once; the firmware's defaults while it is not there.
 */
object XmbWaveFirmware {
    @Volatile private var loaded: Pair<XmbWaveModel.Line, FloatArray>? = null

    val line: XmbWaveModel.Line get() = load().first
    val strands: FloatArray get() = load().second

    /** The line colour across the screen (BACKGROUND.mnu colours 3 and 4). */
    @Volatile var colour3 = floatArrayOf(0.847347f, 0.846155f, 0.846448f)
    @Volatile var colour4 = floatArrayOf(0.92487f, 0.922603f, 0.922933f)

    @Synchronized
    private fun load(): Pair<XmbWaveModel.Line, FloatArray> {
        loaded?.let { return it }
        val files = XmbFirmware.devFlash()?.let { XmbQrc.open(File(it, "vsh/resource/qgl/lines.qrc")) }
        val mnu = files?.get("LINE1.mnu")?.let(::parseMnu).orEmpty()
        val bg = files?.get("BACKGROUND.mnu")?.let(::parseMnu).orEmpty()
        fun f(key: String, d: Float) = mnu[key] ?: d
        val d = XmbWaveModel.Line()
        val line = XmbWaveModel.Line(
            damping = f("DAMPING", d.damping),
            length = f("LENGTH", d.length),
            tension = f("TENSION", d.tension),
            brightness = f("BRIGHTNESS", d.brightness),
            mipmapBias = f("MIPMAP BIAS", d.mipmapBias),
            fresnel = f("FRESNEL", d.fresnel),
            timestep = f("TIMESTEP", d.timestep),
            perturbation = f("PERTURBATION", d.perturbation),
            pos = floatArrayOf(f("POS X", d.pos[0]), f("POS Y", d.pos[1]), f("POS Z", d.pos[2])),
            axis = floatArrayOf(f("ANG X", d.axis[0]), f("ANG Y", d.axis[1]), f("ANG Z", d.axis[2])),
            rotDeg = f("ANG ROT", d.rotDeg),
            end = floatArrayOf(f("END X", d.end[0]), f("END Y", d.end[1]), f("END Z", d.end[2])),
            scale1 = floatArrayOf(f("FFD SCALE1 X", d.scale1[0]), f("FFD SCALE1 Y", d.scale1[1]), f("FFD SCALE1 Z", d.scale1[2])),
            scale2 = floatArrayOf(f("FFD SCALE2 X", d.scale2[0]), f("FFD SCALE2 Y", d.scale2[1]), f("FFD SCALE2 Z", d.scale2[2])),
            offset = floatArrayOf(f("FFD OFFSET X", d.offset[0]), f("FFD OFFSET Y", d.offset[1]), f("FFD OFFSET Z", d.offset[2])),
            param1 = f("FFD PARAM 1", d.param1),
        )
        if (bg.isNotEmpty()) {
            colour3 = floatArrayOf(bg["3 RED"] ?: colour3[0], bg["3 GREEN"] ?: colour3[1], bg["3 BLUE"] ?: colour3[2])
            colour4 = floatArrayOf(bg["4 RED"] ?: colour4[0], bg["4 GREEN"] ?: colour4[1], bg["4 BLUE"] ?: colour4[2])
        }
        // freslut1.tga: 512 texels, BGR; the strands are its red.
        val strands = files?.get("textures/TGA/freslut1.tga")?.let { tga ->
            runCatching {
                val idLen = tga[0].toInt() and 0xff
                val w = (tga[12].toInt() and 0xff) or ((tga[13].toInt() and 0xff) shl 8)
                val bpp = (tga[16].toInt() and 0xff) / 8
                FloatArray(512) { i -> (tga[18 + idLen + min(i, w - 1) * bpp + 2].toInt() and 0xff) / 255f }
            }.getOrNull()
        } ?: XmbWaveModel.fallbackStrands()
        val result = line to strands
        // Only kept once the firmware was there: until the data folder is known, ask again.
        if (files != null) loaded = result
        return result
    }

    private fun parseMnu(bytes: ByteArray): Map<String, Float> =
        String(bytes, Charsets.ISO_8859_1).lineSequence().mapNotNull { l ->
            val parts = l.trim().split(':')
            if (parts.size == 3 && (parts[1] == "float" || parts[1] == "int")) parts[2].toFloatOrNull()?.let { parts[0] to it } else null
        }.toMap()
}

/** A QRC resource container (the firmware's qgl folder): zlib-packed "QRCF", files by name. */
internal object XmbQrc {
    fun open(file: File): Map<String, ByteArray>? = runCatching {
        if (!file.isFile) return null
        val raw = file.readBytes()
        if (raw.size < 8 || String(raw, 0, 4, Charsets.ISO_8859_1) != "QRCC") return null
        val size = be(raw, 4)
        val u = ByteArray(size)
        val inflater = Inflater()
        try {
            inflater.setInput(raw, 8, raw.size - 8)
            var n = 0
            while (n < size && !inflater.finished()) {
                val k = inflater.inflate(u, n, size - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
        } finally {
            inflater.end()
        }
        if (String(u, 0, 4, Charsets.ISO_8859_1) != "QRCF") return null
        val treeOff = be(u, 8)
        val namesOff = be(u, 16)
        val namesSize = be(u, 20)
        val dataOff = be(u, 48)
        val dataSize = be(u, 52)
        val out = HashMap<String, ByteArray>()
        var pos = namesOff
        val end = min(namesOff + namesSize, u.size)
        while (pos + 4 < end) {
            val entry = treeOff + be(u, pos)
            var e = pos + 4
            while (e < end && u[e] != 0.toByte()) e++
            val name = String(u, pos + 4, e - pos - 4, Charsets.ISO_8859_1)
            pos = e + 1
            if (entry < 0 || entry + 60 > u.size) continue
            val off = be(u, entry + 36)
            val len = be(u, entry + 40)
            if (name.isEmpty() || len <= 0 || off < 0 || off + len > dataSize || dataOff + off + len > u.size) continue
            out[name] = u.copyOfRange(dataOff + off, dataOff + off + len)
        }
        out
    }.getOrNull()

    private fun be(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
}

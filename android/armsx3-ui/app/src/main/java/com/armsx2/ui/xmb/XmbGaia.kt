package com.armsx2.ui.xmb

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.TextureView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gaia, the console's Earth visualizer, from the player's own firmware (earth.qrc): the Earth's
 * own pictures (ground, clouds and the sea's mask, six faces of a cube), its presets, and the
 * camera flight each preset carries.
 *
 * The planet is drawn as the firmware's CubeEarthLayer draws it: the sea taken to its dark blue,
 * clouds lifted off the ground by their thickness and casting shadows away from the sun, the sun's
 * glint off the water, all under the sunlight that comes through the air. The air is the
 * firmware's in spirit: light scattered along each line of sight in the console's own colours
 * (its R, G and B wavelengths and sun intensity), a glow round the edge and a reddened sunrise.
 * Then the sun with its glow and spikes, the stars, and the console's glare and tone.
 *
 * Each preset's camera flies its path (keys every ten seconds) once, then another preset takes
 * over; the right stick or a swipe moves on to another one at once.
 */
internal object XmbGaiaFirmware {
    class Key(val t: Float, val eye: FloatArray, val center: FloatArray, val up: FloatArray, val fovy: Float)

    /** A preset: its settings menu by menu (the firmware's own with the preset's on top) and its flight. */
    class Preset(val name: String, val menus: Map<String, Map<String, Float>>, val path: List<Key>) {
        fun f(menu: String, key: String, d: Float) = menus[menu]?.get(key) ?: d
    }

    /** One of the Earth's layers as GL cube faces (+X, -X, +Y, -Y, +Z, -Z), [size] square. */
    class Cube(val size: Int, val faces: Array<ByteArray>, val channels: Int)

    class Pack(val presets: List<Preset>, val ground: Cube, val clouds: Cube, val specular: Cube, val disk: Triple<Int, Int, ByteArray>?)

    private val MENUS = listOf("ATM.mnu", "CLOUDS.mnu", "EARTH.mnu", "HDR.mnu", "INTERACT.mnu", "SUN.mnu", "SimpleAutoExp.mnu")

    // The faces are large (about 30 MB): kept for a quick return while memory allows.
    @Volatile private var loaded: java.lang.ref.SoftReference<Pack>? = null

    /** Whether the firmware has Gaia (cheap: the pack's file only). */
    fun available(): Boolean = XmbFirmware.devFlash()?.let { File(it, "vsh/resource/qgl/earth.qrc").isFile } == true

    fun load(): Pack? {
        loaded?.get()?.let { return it }
        val files = XmbFirmware.devFlash()?.let { XmbQrc.open(File(it, "vsh/resource/qgl/earth.qrc")) } ?: return null
        val names = files.keys.mapNotNull { k ->
            if (!k.startsWith("earth/presets/") || !k.endsWith("/earth/camera.path")) null
            else k.removePrefix("earth/presets/").substringBefore('/')
        }.sorted()
        val presets = names.mapNotNull { p ->
            val path = parsePath(files["earth/presets/$p/earth/camera.path"] ?: return@mapNotNull null)
            if (path.size < 2) return@mapNotNull null
            val menus = MENUS.associateWith { menu ->
                (files["earth/$menu"]?.let(::parseMnu).orEmpty()) + (files["earth/presets/$p/earth/$menu"]?.let(::parseMnu).orEmpty())
            }
            Preset(p, menus, path)
        }
        if (presets.isEmpty()) return null
        val ground = cube(files, "ground_cm", 3) ?: return null
        val clouds = cube(files, "clouds", 1) ?: return null
        val specular = cube(files, "specular", 1) ?: return null
        val disk = files["earth/flashrom/disk.dds"]?.let(::dxt1)
        return Pack(presets, ground, clouds, specular, disk).also { loaded = java.lang.ref.SoftReference(it) }
    }

    private fun parseMnu(bytes: ByteArray): Map<String, Float> =
        String(bytes, Charsets.ISO_8859_1).lineSequence().mapNotNull { l ->
            val parts = l.trim().split(':')
            if (parts.size == 3 && (parts[1] == "float" || parts[1] == "int")) parts[2].toFloatOrNull()?.let { parts[0] to it } else null
        }.toMap()

    /** camera.path: "time=T eye x y z center x y z up x y z fovy=F", a key a line. */
    private fun parsePath(bytes: ByteArray): List<Key> {
        val keys = ArrayList<Key>()
        var fovy = 30f
        for (line in String(bytes, Charsets.ISO_8859_1).lines()) {
            val w = line.trim().split(Regex("\\s+"))
            val t = w.firstOrNull { it.startsWith("time=") }?.removePrefix("time=")?.toFloatOrNull() ?: continue
            fun v(name: String): FloatArray? {
                val i = w.indexOf(name)
                if (i < 0 || i + 3 >= w.size) return null
                return floatArrayOf(w[i + 1].toFloatOrNull() ?: return null, w[i + 2].toFloatOrNull() ?: return null, w[i + 3].toFloatOrNull() ?: return null)
            }
            w.firstOrNull { it.startsWith("fovy=") }?.removePrefix("fovy=")?.toFloatOrNull()?.let { fovy = it }
            keys.add(Key(t, v("eye") ?: continue, v("center") ?: continue, v("up") ?: floatArrayOf(0f, 1f, 0f), fovy))
        }
        return keys.sortedBy { it.t }
    }

    /**
     * A layer's six faces, each four 512 pictures (<row>_<col>), turned into GL's cube faces. The
     * firmware's world has north up (+Y) and longitude lon at azimuth lon - 45 deg from +Z toward +X
     * (its sun is placed that way); the pictures' faces sit a quarter turn round from GL's.
     */
    private fun cube(files: Map<String, ByteArray>, layer: String, channels: Int): Cube? {
        val src = Array(6) { IntArray(SRC * SRC) }
        for (f in 0 until 6) for (r in 0 until 2) for (c in 0 until 2) {
            val bytes = files["earth/flashrom/$layer/$f/1/%03d_%03d.jpg".format(r, c)] ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val tile = IntArray(TILE * TILE)
            bmp.getPixels(tile, 0, TILE, 0, 0, min(TILE, bmp.width), min(TILE, bmp.height))
            bmp.recycle()
            for (y in 0 until TILE) System.arraycopy(tile, y * TILE, src[f], (r * TILE + y) * SRC + c * TILE, TILE)
        }
        val n = OUT
        val faces = Array(6) { ByteArray(n * n * channels) }
        for (g in 0 until 6) {
            val out = faces[g]
            for (j in 0 until n) {
                val tc = (j + 0.5f) / n * 2f - 1f
                for (i in 0 until n) {
                    val sc = (i + 0.5f) / n * 2f - 1f
                    // GL's direction for this texel of face g.
                    val xw: Float; val yw: Float; val zw: Float
                    when (g) {
                        0 -> { xw = 1f; yw = -tc; zw = -sc }
                        1 -> { xw = -1f; yw = -tc; zw = sc }
                        2 -> { xw = sc; yw = 1f; zw = tc }
                        3 -> { xw = sc; yw = -1f; zw = -tc }
                        4 -> { xw = sc; yw = -tc; zw = 1f }
                        else -> { xw = -sc; yw = -tc; zw = -1f }
                    }
                    val p = consoleTexel(src, zw, yw, -xw)
                    val o = (j * n + i) * channels
                    if (channels == 3) {
                        out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte()
                    } else {
                        out[o] = (p shr 8).toByte()
                    }
                }
            }
        }
        return Cube(n, faces, channels)
    }

    // The firmware's face for a direction, and the texel of it (u right, v down in its pictures).
    private fun consoleTexel(src: Array<IntArray>, x: Float, y: Float, z: Float): Int {
        val ax = abs(x); val ay = abs(y); val az = abs(z)
        val f: Int; val u: Float; val v: Float
        if (ax >= ay && ax >= az) {
            if (x > 0) { f = 2; u = (z / ax + 1) / 2; v = (y / ax + 1) / 2 } else { f = 0; u = (1 - z / ax) / 2; v = (y / ax + 1) / 2 }
        } else if (az >= ay) {
            if (z > 0) { f = 1; u = (1 - x / az) / 2; v = (y / az + 1) / 2 } else { f = 3; u = (x / az + 1) / 2; v = (y / az + 1) / 2 }
        } else {
            if (y > 0) { f = 5; u = (z / ay + 1) / 2; v = (1 - x / ay) / 2 } else { f = 4; u = (z / ay + 1) / 2; v = (x / ay + 1) / 2 }
        }
        val px = (u * SRC).toInt().coerceIn(0, SRC - 1)
        val py = (v * SRC).toInt().coerceIn(0, SRC - 1)
        return src[f][py * SRC + px]
    }

    /** A DXT1 picture's largest level, as RGBA. */
    private fun dxt1(b: ByteArray): Triple<Int, Int, ByteArray>? {
        if (b.size < 128 || String(b, 0, 4, Charsets.ISO_8859_1) != "DDS " || String(b, 84, 4, Charsets.ISO_8859_1) != "DXT1") return null
        fun le(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        val h = le(12); val w = le(16)
        if (w <= 0 || h <= 0 || 128 + max(1, w / 4) * max(1, h / 4) * 8 > b.size) return null
        val out = ByteArray(w * h * 4)
        var o = 128
        val col = IntArray(4)
        for (by in 0 until (h + 3) / 4) for (bx in 0 until (w + 3) / 4) {
            val c0 = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
            val c1 = (b[o + 2].toInt() and 0xff) or ((b[o + 3].toInt() and 0xff) shl 8)
            fun rgb(c: Int) = intArrayOf(((c shr 11) and 31) * 255 / 31, ((c shr 5) and 63) * 255 / 63, (c and 31) * 255 / 31)
            val a = rgb(c0); val bb = rgb(c1)
            val p = arrayOf(a, bb, IntArray(3), IntArray(3))
            if (c0 > c1) {
                for (k in 0 until 3) { p[2][k] = (2 * a[k] + bb[k]) / 3; p[3][k] = (a[k] + 2 * bb[k]) / 3 }
            } else {
                for (k in 0 until 3) { p[2][k] = (a[k] + bb[k]) / 2; p[3][k] = 0 }
            }
            for (k in 0 until 4) col[k] = if (c0 <= c1 && k == 3) 0 else 255
            val bits = le(o + 4)
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py
                if (x >= w || y >= h) continue
                val idx = (bits shr (2 * (py * 4 + px))) and 3
                val d = (y * w + x) * 4
                out[d] = p[idx][0].toByte(); out[d + 1] = p[idx][1].toByte(); out[d + 2] = p[idx][2].toByte(); out[d + 3] = col[idx].toByte()
            }
            o += 8
        }
        return Triple(w, h, out)
    }

    private const val TILE = 512
    private const val SRC = 1024
    private const val OUT = 1024
}

/** Gaia's preset changes, from the right stick or a swipe, picked up on its own thread. */
internal object XmbGaiaPresets {
    @Volatile var preset = -1
    val flicks = AtomicInteger(0)
    fun flick() { flicks.set(1) }
}

/** Gaia on screen: a TextureView with its own GL thread, as Valley has. */
internal class XmbGaiaView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = RenderThread(st, w, h).also {
            it.paused = windowVisibility != VISIBLE
            it.start()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        thread?.paused = visibility != VISIBLE
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.resize(w, h) }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private class Target(val w: Int, val h: Int, val fbo: Int, val tex: Int)

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("xmb-gaia") {
        @Volatile private var running = true
        @Volatile var paused = false
            set(value) { field = value; LockSupport.unpark(this) }

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var presets: List<XmbGaiaFirmware.Preset> = emptyList()
        private var preset: XmbGaiaFirmware.Preset? = null
        private var pathTime = 0f
        private var seed = 0
        private var fade = 0f

        private var spaceProg = 0
        private var sunProg = 0
        private var spikeProg = 0
        private var sourceProg = 0
        private var copyProg = 0
        private var blurProg = 0
        private var outProg = 0
        private var quadVao = 0
        private var groundTex = 0
        private var cloudTex = 0
        private var specTex = 0
        private var starTex = 0
        private var diskTex = 0
        private var odTex = 0
        private var floatTarget = false

        private var scene: Target? = null
        private val levels = arrayOfNulls<Target>(GLARE_LEVELS)
        private val blurred = arrayOfNulls<Target>(GLARE_LEVELS)
        private var glare: Target? = null
        // SimpleAutoExp: the scene's average light at 64x32 down to one texel, and the exposure kept
        // in a texel of its own, a pair to read the last one while writing the next.
        private var lumTex = 0
        private var lumFbo = 0
        private val expTarget = arrayOfNulls<Target>(2)
        private var expPing = 0
        private var expReset = true
        private var lumProg = 0
        private var expProg = 0
        private val blurWeights = FloatArray(24)
        private val levelWeights = FloatArray(GLARE_LEVELS)

        private val view = FloatArray(16)
        private val proj = FloatArray(16)
        private val viewProj = FloatArray(16)
        private val invViewProj = FloatArray(16)
        private val spikeAngles = FloatArray(MAX_SPIKES)
        private val spikeLengths = FloatArray(MAX_SPIKES)

        fun resize(w: Int, h: Int) { width = w; height = h }
        fun finish() { running = false; LockSupport.unpark(this); runCatching { join(500) } }

        override fun run() {
            if (!initEgl()) return
            val ok = runCatching { initGl() }.onFailure { Log.w(TAG, "GL init failed", it) }.isSuccess
            if (!ok) { teardown(); return }
            var last = System.nanoTime()
            while (running) {
                if (paused) {
                    LockSupport.park(this)
                    last = System.nanoTime()
                    continue
                }
                val start = System.nanoTime()
                val dt = ((start - last) / 1e9f).coerceIn(0f, 0.1f)
                last = start
                runCatching {
                    tick(dt)
                    draw()
                }.onFailure { Log.w(TAG, "frame failed", it) }
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) running = false
                val left = 16L - (System.nanoTime() - start) / 1_000_000L
                if (left > 1) runCatching { sleep(left) }
            }
            teardown()
        }

        private fun rand01(): Float {
            seed++
            val n = (seed shl 13) xor seed
            val v = (n * (n * n * 15731 + 789221) + 1376312589) and 0x7fffffff
            return v / 2147483648f
        }

        private fun tick(dt: Float) {
            if (presets.isEmpty()) return
            val p = preset
            if (p == null || XmbGaiaPresets.flicks.getAndSet(0) != 0 || pathTime > p.path.last().t) {
                nextPreset()
                return
            }
            pathTime += dt * p.f("EARTH.mnu", "ANIM SPEED", 1f).coerceIn(0.05f, 4f)
            fade = min(1f, fade + dt / 1.5f)
        }

        private fun nextPreset() {
            val n = presets.size
            var i = (rand01() * n).toInt().coerceIn(0, n - 1)
            if (n > 1 && i == XmbGaiaPresets.preset) i = (i + 1) % n
            XmbGaiaPresets.preset = i
            val p = presets[i]
            preset = p
            pathTime = p.path.first().t
            fade = 0f
            Log.i(TAG, "preset ${p.name}")
            // The sun's spikes: NUM of them, round the sun at jittered angles.
            val num = p.f("SUN.mnu", "NUM", 15f).toInt().coerceIn(0, MAX_SPIKES)
            val jit = p.f("SUN.mnu", "ANGLE JIT", 1.4f)
            for (k in 0 until MAX_SPIKES) {
                spikeAngles[k] = if (k < num) (k.toFloat() / num * 2f * Math.PI.toFloat() + (rand01() - 0.5f) * jit) else 0f
                spikeLengths[k] = if (k < num) 0.6f + rand01() * 0.8f else 0f
            }
            glareWeights(p)
            expReset = true
        }

        // ---- the camera along the preset's path, a Catmull-Rom curve through its keys ----
        private fun camera(p: XmbGaiaFirmware.Preset, t: Float, eye: FloatArray, center: FloatArray, up: FloatArray): Float {
            val k = p.path
            var i = 0
            while (i < k.size - 2 && k[i + 1].t <= t) i++
            val a = k[max(i - 1, 0)]; val b = k[i]; val c = k[min(i + 1, k.size - 1)]; val d = k[min(i + 2, k.size - 1)]
            val s = if (c.t > b.t) ((t - b.t) / (c.t - b.t)).coerceIn(0f, 1f) else 0f
            fun cr(p0: Float, p1: Float, p2: Float, p3: Float): Float {
                val s2 = s * s; val s3 = s2 * s
                return 0.5f * (2f * p1 + (-p0 + p2) * s + (2f * p0 - 5f * p1 + 4f * p2 - p3) * s2 + (-p0 + 3f * p1 - 3f * p2 + p3) * s3)
            }
            for (j in 0 until 3) {
                eye[j] = cr(a.eye[j], b.eye[j], c.eye[j], d.eye[j])
                center[j] = cr(a.center[j], b.center[j], c.center[j], d.center[j])
                up[j] = cr(a.up[j], b.up[j], c.up[j], d.up[j])
            }
            return b.fovy + (c.fovy - b.fovy) * s
        }

        private val eye = FloatArray(3)
        private val center = FloatArray(3)
        private val up = FloatArray(3)

        private fun draw() {
            val w = width
            val h = height
            val p = preset
            if (p == null) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, w, h)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            val fovy = camera(p, pathTime, eye, center, up)
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], center[0], center[1], center[2], up[0], up[1], up[2])
            Matrix.perspectiveM(proj, 0, fovy, w.toFloat() / h.coerceAtLeast(1), 0.01f, 1000f)
            Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
            Matrix.invertM(invViewProj, 0, viewProj, 0)

            // The sun: SUN ANG Y from the north pole, SUN ANG Z round from +Z toward +X.
            val ay = Math.toRadians(p.f("SUN.mnu", "SUN ANG Y", 90f).toDouble())
            val az = Math.toRadians(p.f("SUN.mnu", "SUN ANG Z", 0f).toDouble())
            val sun = floatArrayOf((sin(ay) * sin(az)).toFloat(), cos(ay).toFloat(), (sin(ay) * cos(az)).toFloat())

            val aspect = w.toFloat() / h.coerceAtLeast(1)
            val sc = target(scene, w, h).also { scene = it }
            // Gaia's glare is finer than the XMB's: 256 high and twice that across on a wide screen,
            // in eight sizes (its HDR takes TEX SIZE 9 and 8 levels).
            val gh = GLARE_BASE
            val gw = if (aspect > 1.5f) GLARE_BASE * 2 else GLARE_BASE
            for (n in 0 until GLARE_LEVELS) {
                levels[n] = target(levels[n], max(1, gw shr n), max(1, gh shr n))
                blurred[n] = target(blurred[n], max(1, gw shr n), max(1, gh shr n))
            }
            val sum = target(glare, gw, gh).also { glare = it }
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(quadVao)

            // Space: the stars, the Earth and its air, in light (HDR).
            bind(sc)
            GLES30.glUseProgram(spaceProg)
            GLES30.glUniformMatrix4fv(u(spaceProg, "uInvViewProj"), 1, false, invViewProj, 0)
            GLES30.glUniform3f(u(spaceProg, "uEye"), eye[0], eye[1], eye[2])
            GLES30.glUniform3f(u(spaceProg, "uSun"), sun[0], sun[1], sun[2])
            GLES30.glUniform4f(u(spaceProg, "uCloud"),
                p.f("CLOUDS.mnu", "PARALLAX", 0.15f), p.f("CLOUDS.mnu", "PARALLAX BIAS", 0f),
                p.f("CLOUDS.mnu", "BRIGHTNESS", 0.3f) * 3f, p.f("CLOUDS.mnu", "BLEND", 0.35f) * 2.5f)
            GLES30.glUniform2f(u(spaceProg, "uShadow"), p.f("CLOUDS.mnu", "SHADOW BIAS", 0.8f), p.f("CLOUDS.mnu", "SHADOW STREN", 1f))
            GLES30.glUniform4f(u(spaceProg, "uSea"), p.f("EARTH.mnu", "SEA ALBEDO", 0.06f), p.f("EARTH.mnu", "SEA POW", 10f), p.f("EARTH.mnu", "LAND ALBEDO", 0.036f), p.f("EARTH.mnu", "LAND POW", 20f))
            val wl = floatArrayOf(p.f("ATM.mnu", "R WAVELENGTH", 0.65f), p.f("ATM.mnu", "G WAVELENGTH", 0.57f), p.f("ATM.mnu", "B WAVELENGTH", 0.475f))
            GLES30.glUniform3f(u(spaceProg, "uInvWave4"), 1f / wl[0].pow(4), 1f / wl[1].pow(4), 1f / wl[2].pow(4))
            GLES30.glUniform3f(u(spaceProg, "uSunColour"), p.f("ATM.mnu", "SUN R", 1f), p.f("ATM.mnu", "SUN G", 1f), p.f("ATM.mnu", "SUN B", 1f))
            GLES30.glUniform1f(u(spaceProg, "uSunIntensity"), p.f("ATM.mnu", "SUN INTENSITY", 16f))
            GLES30.glUniform2f(u(spaceProg, "uAttn"),
                p.f("ATM.mnu", "ATTN EARTH", ATTN_EARTH) / ATTN_EARTH, p.f("ATM.mnu", "ATTN ATM.", ATTN_ATM) / ATTN_ATM)
            GLES30.glUniform3f(u(spaceProg, "uStars"), p.f("EARTH.mnu", "STARS FACTOR", 0.0002f), p.f("EARTH.mnu", "STARS POWSCALE", 2.15f), p.f("EARTH.mnu", "STARS WHITENESS", 0.22f))
            GLES30.glUniform1f(u(spaceProg, "uFade"), fade)
            cube(0, groundTex, spaceProg, "uGround")
            cube(1, cloudTex, spaceProg, "uClouds")
            cube(2, specTex, spaceProg, "uSpec")
            cube(3, starTex, spaceProg, "uStarTex")
            sample(4, odTex, spaceProg, "uOd")
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            // The sun's disc and spikes, added where the sun is. Each pixel of them is hidden where the
            // Earth is in the way, and the disc reddened through the air near the Earth's edge.
            val sp = floatArrayOf(eye[0] + sun[0] * 100f, eye[1] + sun[1] * 100f, eye[2] + sun[2] * 100f, 1f)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, viewProj, 0, sp, 0)
            if (clip[3] > 0f) {
                val sx = clip[0] / clip[3]; val sy = clip[1] / clip[3]
                if (abs(sx) < 3f && abs(sy) < 3f) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
                    val bright = p.f("SUN.mnu", "BRIGHTNESS", 3.4f) * fade
                    GLES30.glUseProgram(sunProg)
                    sample(0, diskTex, sunProg, "uDisk")
                    sample(1, odTex, sunProg, "uOd")
                    occlusion(sunProg, w, h)
                    GLES30.glUniform3f(u(sunProg, "uBeta"), beta(wl, 0), beta(wl, 1), beta(wl, 2))
                    // The console's disc: SOLID ANGLE times the sun's own 0.265 degrees, the quad
                    // twice that from its centre.
                    val radius = Math.toRadians(0.265 * 2.0 * p.f("SUN.mnu", "SOLID ANGLE", 1f)).toFloat()
                    val half = kotlin.math.tan(radius) / kotlin.math.tan(Math.toRadians(fovy / 2.0).toFloat())
                    GLES30.glUniform4f(u(sunProg, "uRect"), sx, sy, half / aspect, half)
                    GLES30.glUniform1f(u(sunProg, "uBright"), bright)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    // The spikes go as the disc goes behind the Earth.
                    val b = eye[0] * sun[0] + eye[1] * sun[1] + eye[2] * sun[2]
                    val e2 = eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]
                    val shown = if (b >= 0f) 1f else {
                        val d = sqrt(max(0f, e2 - b * b))
                        val edge = -b * kotlin.math.tan(radius / 2f)
                        ((d - 1f + edge) / (2f * edge)).coerceIn(0f, 1f)
                    }
                    val spikes = shown * bright * 0.12f
                    GLES30.glUseProgram(spikeProg)
                    occlusion(spikeProg, w, h)
                    GLES30.glUniform2f(u(spikeProg, "uFalloff"), p.f("SUN.mnu", "FALLOFF X", 9.4f), p.f("SUN.mnu", "FALLOFF Y", 10.1f))
                    GLES30.glUniform4f(u(spikeProg, "uColour"), p.f("SUN.mnu", "SPIKE R", 1f) * spikes, p.f("SUN.mnu", "SPIKE G", 1f) * spikes, p.f("SUN.mnu", "SPIKE B", 1f) * spikes, 1f)
                    val scaleX = p.f("SUN.mnu", "SCALE X", 3f) * 0.12f
                    val scaleY = p.f("SUN.mnu", "SCALE Y", 6f) * 0.006f
                    for (k in 0 until MAX_SPIKES) {
                        if (spikeLengths[k] <= 0f) continue
                        GLES30.glUniform4f(u(spikeProg, "uSpike"), sx, sy, spikeAngles[k], aspect)
                        GLES30.glUniform2f(u(spikeProg, "uSize"), scaleX * spikeLengths[k], scaleY)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    }
                    GLES30.glDisable(GLES30.GL_BLEND)
                }
            }

            // The exposure (SimpleAutoExp): KEY VALUE over the scene's average light, held between MIN
            // and MAX EXPOSURE and eased there at FILTER SPEED a frame; the preset's EXPOSURE to start.
            val minExp = p.f("SimpleAutoExp.mnu", "MIN EXPOSURE", 0.4f)
            val maxExp = max(minExp, p.f("SimpleAutoExp.mnu", "MAX EXPOSURE", 1.16f))
            val startExp = p.f("HDR.mnu", "EXPOSURE", 0.8f).coerceIn(minExp, maxExp)
            val white = p.f("HDR.mnu", "WHITE LEVEL", 1.41f)
            val expTex = autoExposure(sc, p, startExp, minExp, maxExp, white)
            val exposure = startExp

            // The console's glare: what is past GLARE THRESH in the toned picture, at the largest
            // size and seven smaller ones, each blurred by a gaussian per colour, added back up by
            // GLARE SUM POW.
            bind(levels[0]!!)
            GLES30.glUseProgram(sourceProg)
            sample(0, sc.tex, sourceProg, "uScene")
            GLES30.glUniform2f(u(sourceProg, "uFoot"), 1f / gw, 1f / gh)
            GLES30.glUniform1f(u(sourceProg, "uThreshold"), p.f("HDR.mnu", "GLARE THRESH", 0.55f))
            GLES30.glUniform1f(u(sourceProg, "uExposure"), exposure)
            sample(1, expTex, sourceProg, "uExpTex")
            GLES30.glUniform1f(u(sourceProg, "uAuto"), if (expTex != 0) 1f else 0f)
            GLES30.glUniform1f(u(sourceProg, "uWhiteSqrRcp"), 1f / (white * white))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glUseProgram(copyProg)
            GLES30.glUniform1f(u(copyProg, "uWeight"), 1f)
            for (n in 1 until GLARE_LEVELS) {
                bind(levels[n]!!)
                sample(0, levels[n - 1]!!.tex, copyProg, "uTex")
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glUseProgram(blurProg)
            GLES30.glUniform3fv(u(blurProg, "uW"), 8, blurWeights, 0)
            for (n in 0 until GLARE_LEVELS) {
                val l = levels[n]!!; val b = blurred[n]!!
                bind(b); sample(0, l.tex, blurProg, "uTex"); GLES30.glUniform2f(u(blurProg, "uStep"), 1f / l.w, 0f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                bind(l); sample(0, b.tex, blurProg, "uTex"); GLES30.glUniform2f(u(blurProg, "uStep"), 0f, 1f / l.h)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            bind(sum)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glUseProgram(copyProg)
            for (n in GLARE_LEVELS - 1 downTo 0) {
                sample(0, levels[n]!!.tex, copyProg, "uTex")
                GLES30.glUniform1f(u(copyProg, "uWeight"), levelWeights[n])
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glDisable(GLES30.GL_BLEND)

            // Toned, with the glare, the preset's GAMMA, and the console's dither.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(outProg)
            sample(0, sc.tex, outProg, "uScene")
            sample(1, sum.tex, outProg, "uGlare")
            GLES30.glUniform1f(u(outProg, "uExposure"), exposure)
            sample(2, expTex, outProg, "uExpTex")
            GLES30.glUniform1f(u(outProg, "uAuto"), if (expTex != 0) 1f else 0f)
            GLES30.glUniform1f(u(outProg, "uWhiteSqrRcp"), 1f / (white * white))
            GLES30.glUniform1f(u(outProg, "uGamma"), p.f("INTERACT.mnu", "GAMMA", 1f).coerceIn(0.5f, 3f))
            GLES30.glUniform1f(u(outProg, "uDither"), p.f("HDR.mnu", "DITHER", 0.004f))
            GLES30.glUniform1f(u(outProg, "uSeed"), rand01())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
        }

        /**
         * The scene's average light into one texel, then the exposure eased toward KEY VALUE over it;
         * the texture holding it, or 0 without float targets (the preset's EXPOSURE then). The light
         * is read as the console's toned picture holds it, no brighter than the white level.
         */
        private fun autoExposure(sc: Target, p: XmbGaiaFirmware.Preset, start: Float, minExp: Float, maxExp: Float, white: Float): Int {
            if (!floatTarget) return 0
            if (lumTex == 0) {
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); lumTex = t[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumTex)
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, LUM_LEVELS, GLES30.GL_RGBA16F, LUM_W, LUM_H)
                params2d(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); lumFbo = f[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, lumTex, 0)
            }
            for (k in 0 until 2) expTarget[k] = target(expTarget[k], 1, 1)
            if (expReset) {
                for (k in 0 until 2) {
                    bind(expTarget[k]!!)
                    GLES30.glClearColor(start, 0f, 0f, 0f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                }
                expReset = false
            }
            val prev = expTarget[expPing]!!
            val next = expTarget[1 - expPing]!!
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumFbo)
            GLES30.glViewport(0, 0, LUM_W, LUM_H)
            GLES30.glUseProgram(lumProg)
            sample(0, sc.tex, lumProg, "uScene")
            sample(1, prev.tex, lumProg, "uExpTex")
            GLES30.glUniform2f(u(lumProg, "uFoot"), 1f / LUM_W, 1f / LUM_H)
            GLES30.glUniform1f(u(lumProg, "uWhite"), white)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumTex)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            bind(next)
            GLES30.glUseProgram(expProg)
            sample(0, lumTex, expProg, "uLum")
            sample(1, prev.tex, expProg, "uPrev")
            GLES30.glUniform1f(u(expProg, "uTop"), (LUM_LEVELS - 1).toFloat())
            GLES30.glUniform1f(u(expProg, "uKey"), p.f("SimpleAutoExp.mnu", "KEY VALUE", 1f))
            GLES30.glUniform2f(u(expProg, "uRange"), minExp, maxExp)
            GLES30.glUniform1f(u(expProg, "uSpeed"), p.f("SimpleAutoExp.mnu", "FILTER SPEED", 0.01f).coerceIn(0.001f, 1f))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            expPing = 1 - expPing
            return next.tex
        }

        /** What the sun's shaders need to find, a pixel at a time, whether the Earth is in the way. */
        private fun occlusion(prog: Int, w: Int, h: Int) {
            GLES30.glUniformMatrix4fv(u(prog, "uInvViewProj"), 1, false, invViewProj, 0)
            GLES30.glUniform3f(u(prog, "uEye"), eye[0], eye[1], eye[2])
            GLES30.glUniform2f(u(prog, "uViewport"), w.toFloat(), h.toFloat())
        }

        /** The air's extinction for colour [c], as the space shader has it. */
        private fun beta(wl: FloatArray, c: Int): Float =
            (1f / wl[c].pow(4) * KR + KM) * 4f * Math.PI.toFloat()

        private fun glareWeights(p: XmbGaiaFirmware.Preset) {
            val radius = floatArrayOf(p.f("HDR.mnu", "GAUSSIAN RAD R", 1.4f), p.f("HDR.mnu", "GAUSSIAN RAD G", 1.45f), p.f("HDR.mnu", "GAUSSIAN RAD B", 1.42f))
            for (ch in 0 until 3) {
                val r = radius[ch].coerceAtLeast(0.01f)
                val side = (floor(3f * r).toInt() - 1).coerceIn(0, 7)
                val g = FloatArray(8) { k -> if (k <= side) exp(-(k * k) / (2f * r * r)) else 0f }
                val total = g[0] + 2f * (1..7).sumOf { g[it].toDouble() }.toFloat()
                for (k in 0 until 8) blurWeights[k * 3 + ch] = g[k] / total
            }
            val pow = p.f("HDR.mnu", "GLARE SUM POW", 2f).toDouble()
            val lvl = p.f("HDR.mnu", "GLARE LEVEL", 1.3f)
            val total = (0 until GLARE_LEVELS).sumOf { pow.pow(it.toDouble()) }
            for (n in 0 until GLARE_LEVELS) levelWeights[n] = (pow.pow(n.toDouble()) / total * lvl).toFloat()
        }

        private fun u(prog: Int, name: String) = GLES30.glGetUniformLocation(prog, name)

        private fun sample(unit: Int, tex: Int, prog: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(u(prog, name), unit)
        }

        private fun cube(unit: Int, tex: Int, prog: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, tex)
            GLES30.glUniform1i(u(prog, name), unit)
        }

        private fun bind(t: Target) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
            GLES30.glViewport(0, 0, t.w, t.h)
        }

        private fun target(old: Target?, w: Int, h: Int): Target {
            if (old != null && old.w == w && old.h == h) return old
            if (old != null) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(old.fbo), 0)
                GLES30.glDeleteTextures(1, intArrayOf(old.tex), 0)
            }
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            params2d(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_LINEAR)
            if (floatTarget) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
            return Target(w, h, f[0], t[0])
        }

        private fun params2d(wrap: Int, filter: Int) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (filter == GLES30.GL_LINEAR_MIPMAP_LINEAR) GLES30.GL_LINEAR else filter)
        }

        private fun uploadCube(tex: Int, cube: XmbGaiaFirmware.Cube) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, tex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            val fmt = if (cube.channels == 3) GLES30.GL_RGB else GLES30.GL_RED
            val internal = if (cube.channels == 3) GLES30.GL_RGB8 else GLES30.GL_R8
            for (f in 0 until 6) {
                val bb = ByteBuffer.allocateDirect(cube.faces[f].size).put(cube.faces[f])
                bb.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, 0, internal, cube.size, cube.size, 0, fmt, GLES30.GL_UNSIGNED_BYTE, bb)
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_CUBE_MAP)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        // The stars: the firmware's star picture is not in its pack, so a sky of them is made,
        // brightness spread as the stars' is (many faint, a few bright); TiledStars' curve lifts them.
        private fun makeStars() {
            val n = 512
            val rnd = java.util.Random(0x51A75L)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, starTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            for (f in 0 until 6) {
                val data = ByteArray(n * n * 3)
                repeat(1400) {
                    val x = rnd.nextInt(n); val y = rnd.nextInt(n)
                    val v = (0.35 + 0.65 * rnd.nextDouble().pow(6.0)).coerceAtMost(1.0)
                    val tint = rnd.nextFloat()
                    val r = v * (0.85 + 0.15 * tint); val g = v * 0.95; val b = v * (1.0 - 0.15 * tint)
                    val o = (y * n + x) * 3
                    data[o] = (r * 255).toInt().toByte(); data[o + 1] = (g * 255).toInt().toByte(); data[o + 2] = (b * 255).toInt().toByte()
                }
                val bb = ByteBuffer.allocateDirect(data.size).put(data)
                bb.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, 0, GLES30.GL_RGB8, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bb)
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }

        /**
         * The air's optical depth, worked out once: from a point at height r (the Earth's radius to the
         * air's edge, down the table) along a direction above that height's horizon (across, finer
         * toward the horizon), the density exp(-(h - 1) / H) summed to the edge of the air, in the
         * air's thickness. Below the horizon the Earth is in the way; the shader shades that itself.
         */
        private fun makeOpticalDepth() {
            val w = OD_W; val h = OD_H
            val data = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val thick = ATM - 1f
            val scaleH = 0.25f * thick
            for (j in 0 until h) {
                val r = 1f + thick * j / (h - 1)
                val muH = -sqrt(max(0f, 1f - 1f / (r * r)))
                for (i in 0 until w) {
                    val x = i / (w - 1f)
                    val mu = muH + (1f - muH) * x * x
                    val b = r * mu
                    val tOut = -b + sqrt(max(0f, b * b - (r * r - ATM * ATM)))
                    val steps = 64
                    val ds = tOut / steps
                    var sum = 0f
                    for (s in 0 until steps) {
                        val tt = (s + 0.5f) * ds
                        val px = sqrt(max(0f, 1f - mu * mu)) * tt
                        val py = r + mu * tt
                        val hh = max(1f, sqrt(px * px + py * py))
                        sum += exp(-(hh - 1f) / scaleH) * ds
                    }
                    data.put(sum / thick); data.put(0f); data.put(0f); data.put(0f)
                }
            }
            data.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, odTex)
            if (floatTarget) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                params2d(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_LINEAR)
            } else {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                params2d(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_NEAREST)
            }
        }

        private fun initGl() {
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            floatTarget = "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
            spaceProg = link(QUAD_VS, SPACE_FS)
            sunProg = link(SUN_VS, SUN_FS)
            spikeProg = link(SPIKE_VS, SPIKE_FS)
            sourceProg = link(QUAD_VS, SOURCE_FS)
            copyProg = link(QUAD_VS, COPY_FS)
            blurProg = link(QUAD_VS, BLUR_FS)
            outProg = link(QUAD_VS, OUT_FS)
            lumProg = link(QUAD_VS, LUM_FS)
            expProg = link(QUAD_VS, EXP_FS)
            val q = IntArray(1); GLES30.glGenVertexArrays(1, q, 0); quadVao = q[0]
            GLES30.glBindVertexArray(quadVao)
            val qb = IntArray(1); GLES30.glGenBuffers(1, qb, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, qb[0])
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val qf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            qf.put(quad).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qf, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glBindVertexArray(0)

            val t = IntArray(6); GLES30.glGenTextures(6, t, 0)
            groundTex = t[0]; cloudTex = t[1]; specTex = t[2]; starTex = t[3]; diskTex = t[4]; odTex = t[5]
            makeStars()
            makeOpticalDepth()
            val pk = XmbGaiaFirmware.load()
            presets = pk?.presets.orEmpty()
            if (pk != null) {
                uploadCube(groundTex, pk.ground)
                uploadCube(cloudTex, pk.clouds)
                uploadCube(specTex, pk.specular)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, diskTex)
            val disk = pk?.disk
            if (disk != null) {
                val bb = ByteBuffer.allocateDirect(disk.third.size).put(disk.third)
                bb.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, disk.first, disk.second, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            } else {
                // A soft round glow when the firmware's sun picture is not there.
                val n = 64
                val bb = ByteBuffer.allocateDirect(n * n * 4)
                for (y in 0 until n) for (x in 0 until n) {
                    val d = sqrt(((x - n / 2 + 0.5f) / (n / 2)).pow(2) + ((y - n / 2 + 0.5f) / (n / 2)).pow(2))
                    val v = (exp(-d * d * 6f) * 255).toInt().coerceIn(0, 255).toByte()
                    bb.put(v); bb.put(v); bb.put(v); bb.put(255.toByte())
                }
                bb.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            params2d(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            seed = (System.nanoTime() and 0xffff).toInt()
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader compile: " + GLES30.glGetShaderInfoLog(s))
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("program link: " + GLES30.glGetProgramInfoLog(p))
            return p
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val cfg = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, cfg, 0, 1, num, 0) || num[0] == 0) return false
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun teardown() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    companion object {
        private const val TAG = "XmbGaia"
        private const val MAX_SPIKES = 24
        private const val GLARE_BASE = 256
        private const val GLARE_LEVELS = 8
        private const val LUM_W = 64
        private const val LUM_H = 32
        private const val LUM_LEVELS = 7
        /** The air's outer edge, in the Earth's radius. */
        private const val ATM = 1.025f
        /** The air's Rayleigh and Mie scattering, as the space shader has them. */
        private const val KR = 0.0025f
        private const val KM = 0.0010f
        /** The firmware's own ATTN EARTH and ATTN ATM., which the look is made at; presets go from there. */
        private const val ATTN_EARTH = 0.038f
        private const val ATTN_ATM = 2.176f
        private const val OD_W = 256
        private const val OD_H = 64

        private const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        // The air: its edge, and its optical depth made once (makeOpticalDepth) from height r toward
        // the air's edge at angle acos(mu) from the vertical, in the air's thickness, over the
        // directions above the horizon (finer toward it).
        private const val AIR = """
uniform sampler2D uOd;
const float R_IN = 1.0;
const float R_OUT = 1.025;

// The cosine of the horizon's angle from the vertical at height r.
float horizon(float r) {
    return -sqrt(max(0.0, 1.0 - 1.0 / (r * r)));
}

float od(float r, float mu) {
    float mh = horizon(r);
    float x = sqrt(clamp((mu - mh) / (1.0 - mh), 0.0, 1.0));
    float y = clamp((r - R_IN) / (R_OUT - R_IN), 0.0, 1.0);
    return texture(uOd, vec2(x, y)).r;
}
"""

        // Space, a pixel at a time: the line of sight from the eye, the stars behind, the Earth it
        // meets (CubeEarthLayer: the sea to its blue, clouds lifted by their thickness and their
        // shadows, the glint), and the air along it, scattering the sunlight in the console's colours.
        private const val SPACE_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform mat4 uInvViewProj;
uniform vec3 uEye;
uniform vec3 uSun;
uniform vec4 uCloud;
uniform vec2 uShadow;
uniform vec4 uSea;
uniform vec3 uInvWave4;
uniform vec3 uSunColour;
uniform float uSunIntensity;
uniform vec2 uAttn;
uniform vec3 uStars;
uniform float uFade;
uniform samplerCube uGround;
uniform samplerCube uClouds;
uniform samplerCube uSpec;
uniform samplerCube uStarTex;
out vec4 oColor;
""" + AIR + """
const float SCALE_H = 0.25;
const float KR = 0.0025;
const float KM = 0.0010;
const float G_MIE = -0.95;
const float PI = 3.14159265;

vec2 sphere(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float h = b * b - c;
    if (h < 0.0) return vec2(1e9, -1e9);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

// Whether the sun is above the horizon at height r, the Earth's shadow edge softened a little.
float sunlit(float r, float mus) {
    float mh = horizon(r);
    return smoothstep(mh - 0.02, mh + 0.02, mus);
}

void main() {
    vec4 ndc = vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec4 far = uInvViewProj * ndc;
    vec3 dir = normalize(far.xyz / far.w - uEye);

    // The stars: TiledStars' curve, 2^(8.64 c POWSCALE) times the factor, toward white by WHITENESS.
    vec3 s = texture(uStarTex, dir).rgb;
    s = mix(s, vec3((s.r + s.g + s.b) / 3.0), uStars.z);
    vec3 col = (exp2(8.64386 * s * uStars.y) - 1.0) * uStars.x * 0.05;

    vec2 atm = sphere(uEye, dir, R_OUT);
    vec2 ground = sphere(uEye, dir, R_IN);
    vec3 sunLight = uSunColour * uSunIntensity;

    if (atm.y > 0.0) {
        float t0 = max(atm.x, 0.0);
        // In front of the eye; a line past the Earth has no ground (sphere() gives it 1e9, -1e9).
        bool hit = ground.x > 0.0 && ground.y > 0.0;
        float t1 = hit ? ground.x : atm.y;
        // Light lost along the way: Rayleigh by 1/wavelength^4 in the console's colours, and Mie.
        vec3 beta = uInvWave4 * KR * 4.0 * PI + KM * 4.0 * PI;
        float thick = R_OUT - R_IN;

        // Along the line, the sunlight scattered toward the eye, each sample's share dimmed by the
        // air between it and the sun and between it and the eye.
        const int N = 12;
        float seg = (t1 - t0) / float(N);
        vec3 sumR = vec3(0.0);
        vec3 sumM = vec3(0.0);
        for (int i = 0; i < N; i++) {
            vec3 sp = uEye + dir * (t0 + seg * (float(i) + 0.5));
            float h = length(sp);
            vec3 up = sp / h;
            float dens = exp(-(h - R_IN) / (SCALE_H * thick));
            float mus = dot(uSun, up);
            float tau = od(h, mus) + od(h, dot(-dir, up));
            vec3 tr = exp(-tau * beta) * sunlit(h, mus);
            vec3 add = tr * dens * (seg / thick);
            sumR += add;
            sumM += add;
        }
        float cosA = dot(uSun, -dir);
        float rayleigh = 0.75 * (1.0 + cosA * cosA);
        float g2 = G_MIE * G_MIE;
        float mie = 1.5 * ((1.0 - g2) / (2.0 + g2)) * (1.0 + cosA * cosA) / pow(1.0 + g2 - 2.0 * G_MIE * cosA, 1.5);
        // At the preset's ATTN ATM., as the console's air over the Earth and against space.
        vec3 inscatter = (sumR * uInvWave4 * KR * rayleigh + sumM * KM * mie) * sunLight * uAttn.y;
        // What lies behind, seen through all of the air along the line.
        vec3 through = hit ? exp(-od(R_IN, dot(-dir, normalize(uEye + dir * ground.x))) * beta)
                           : exp(-(od(length(uEye + dir * t0), dot(dir, normalize(uEye + dir * t0)))) * beta);

        if (hit) {
            vec3 n = normalize(uEye + dir * ground.x);
            vec3 e = -dir;
            // The ground: the sea taken to the firmware's blue, clouds lifted by their thickness
            // (PARALLAX) over it, their shadow cast away from the sun (SHADOW BIAS, STREN), and
            // the sun's glint off the sea (SEA ALBEDO, SEA POW) and the land (LAND ALBEDO, POW).
            // The land and sea at the preset's ATTN EARTH; the clouds and the glint are not.
            vec3 land = texture(uGround, n).rgb;
            float sea = texture(uSpec, n).r;
            land = mix(land, vec3(0.06, 0.15, 0.225), sea) * uAttn.x;
            vec3 tangentEye = e - n * dot(e, n);
            float thickC = texture(uClouds, n).r;
            float cloud = texture(uClouds, normalize(n + tangentEye * (thickC * uCloud.x + uCloud.y) * 0.02)).r;
            vec3 tangentSun = uSun - n * dot(uSun, n);
            float shadowCloud = texture(uClouds, normalize(n + tangentSun * uShadow.x * 0.01)).r;
            float shadow = 1.0 - clamp(shadowCloud - cloud * 0.8, 0.0, 1.0) * uShadow.y * 0.6;
            vec3 surface = mix(land, vec3(0.92), cloud);
            // The firmware's glint: the eye's slant across the ground, N (N.E) - E, against the
            // sun (strongest toward the edge of the Earth, none straight down), at SEA or LAND
            // POW and ALBEDO, beside the ground's own light (its ATTN EARTH, 0.038).
            float facing = dot(n, e);
            float slant = clamp(dot(n * facing - e, uSun), 0.0, 1.0);
            float albedo = mix(uSea.z, uSea.x, sea) * (1.0 - cloud);
            float glint = albedo * pow(slant, mix(uSea.w, uSea.y, sea)) / 0.038;
            vec3 glintColour = vec3(0.8 + 0.0708 * (facing + 0.5), 0.788 * (facing + 0.5), 0.706 * (facing + 0.5));
            // Sunlight down through the air to the ground, and the blue of the sky lighting it
            // too (none past the terminator).
            float mus = dot(uSun, n);
            vec3 sunGround = sunLight * exp(-od(R_IN, mus) * beta * 0.6) * 0.14;
            vec3 skyLight = sunLight * vec3(0.30, 0.45, 0.75) * 0.035 * smoothstep(-0.12, 0.35, mus);
            float lambert = max(mus, 0.0);
            vec3 lit = (surface * (lambert * sunGround + skyLight) + glintColour * glint * sunGround * lambert) * shadow;
            col = lit * through + inscatter;
        } else {
            col = col * through + inscatter;
        }
    }
    // Kept finite; the glare and the exposure read it as the console's toned picture holds it.
    oColor = vec4(min(col, vec3(24.0)) * uFade, 1.0);
}
"""

        // A pixel's line of sight, and how close it passes the Earth's centre: behind the Earth when
        // that is inside it, through the air when inside the air (Sun.fpo and SunSpike.fpo's test).
        private const val SIGHT = """
uniform mat4 uInvViewProj;
uniform vec3 uEye;
uniform vec2 uViewport;
vec3 sight() {
    vec4 far = uInvViewProj * vec4(gl_FragCoord.xy / uViewport * 2.0 - 1.0, 1.0, 1.0);
    return normalize(far.xyz / far.w - uEye);
}
// The closest approach to the Earth's centre, or 1e9 when that is behind the eye.
float pass(vec3 dir) {
    float b = dot(uEye, dir);
    return b < 0.0 ? length(uEye - dir * b) : 1e9;
}
"""

        // Sun.fpo: the firmware's sun picture where the sun is, at its BRIGHTNESS, hidden by the Earth
        // and seen through the air past its edge (the light lost along the whole of that line).
        private const val SUN_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uRect;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(uRect.xy + aPos * uRect.zw, 0.0, 1.0);
}
"""
        private const val SUN_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uDisk;
uniform float uBright;
uniform vec3 uBeta;
out vec4 oColor;
""" + AIR + SIGHT + """
void main() {
    float d = pass(sight());
    if (d < R_IN) discard;
    // Both halves of the line through the air, from its closest point out to the air's edge.
    vec3 through = d < R_OUT ? exp(-2.0 * od(d, 0.0) * uBeta) : vec3(1.0);
    vec3 c = texture(uDisk, vUv).rgb;
    oColor = vec4(c * c * uBright * 6.0 * through, 0.0);
}
"""

        // SunSpike: a long thin streak from the sun, falling off by FALLOFF X along and Y across.
        private const val SPIKE_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec4 uSpike;
uniform vec2 uSize;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    float c = cos(uSpike.z);
    float s = sin(uSpike.z);
    vec2 p = vec2(aPos.x * uSize.x, aPos.y * uSize.y);
    vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
    gl_Position = vec4(uSpike.xy + vec2(r.x / uSpike.w, r.y), 0.0, 1.0);
}
"""
        private const val SPIKE_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform vec2 uFalloff;
uniform vec4 uColour;
out vec4 oColor;
""" + SIGHT + """
void main() {
    if (pass(sight()) < 1.0) discard;
    vec2 q = abs(vUv - 0.5);
    float v = exp(-(q.x * uFalloff.x * 0.5 + q.y * uFalloff.y * 2.0));
    v *= 1.0 - smoothstep(0.35, 0.5, q.x);
    oColor = vec4(uColour.rgb * v, 0.0);
}
"""

        // GlareSourceNoTone: past GLARE THRESH in the toned picture, as the console's 8-bit one holds
        // it (no brighter than 1), averaged over the glare's texel.
        private const val SOURCE_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uFoot;
uniform float uThreshold;
uniform float uExposure;
uniform float uWhiteSqrRcp;
uniform sampler2D uExpTex;
uniform float uAuto;
out vec4 oColor;
void main() {
    float exposure = uAuto > 0.5 ? texture(uExpTex, vec2(0.5)).r : uExposure;
    vec3 s = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) - 1.5) * 0.25 * uFoot;
            vec3 y = texture(uScene, vUv + o).rgb * exposure;
            vec3 t = y * (1.0 + y * uWhiteSqrRcp) / (1.0 + y);
            s += max(min(t, vec3(1.0)) - uThreshold, 0.0);
        }
    }
    oColor = vec4(s / 16.0, 0.0);
}
"""

        // SimpleAutoExp's average: the scene's luminance (GlareSourcePre's weights) as the toned
        // picture keeps it, no brighter than the white level at the exposure in use, over each
        // 64x32 texel.
        private const val LUM_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uExpTex;
uniform vec2 uFoot;
uniform float uWhite;
out vec4 oColor;
void main() {
    float e = max(texture(uExpTex, vec2(0.5)).r, 1e-3);
    float s = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) - 1.5) * 0.25 * uFoot;
            vec3 c = min(texture(uScene, vUv + o).rgb * e, vec3(uWhite)) / e;
            s += dot(c, vec3(0.27, 0.67, 0.06));
        }
    }
    oColor = vec4(s / 16.0, 0.0, 0.0, 1.0);
}
"""

        // The exposure eased toward KEY VALUE over the scene's average, held in MIN..MAX EXPOSURE.
        private const val EXP_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uLum;
uniform sampler2D uPrev;
uniform float uTop;
uniform float uKey;
uniform vec2 uRange;
uniform float uSpeed;
out vec4 oColor;
void main() {
    float avg = textureLod(uLum, vec2(0.5), uTop).r;
    float want = clamp(uKey / max(avg, 1e-4), uRange.x, uRange.y);
    float prev = texture(uPrev, vec2(0.5)).r;
    oColor = vec4(mix(prev, want, uSpeed), 0.0, 0.0, 1.0);
}
"""

        private const val COPY_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uWeight;
out vec4 oColor;
void main() {
    oColor = vec4(texture(uTex, vUv).rgb * uWeight, 0.0);
}
"""

        private const val BLUR_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uStep;
uniform vec3 uW[8];
out vec4 oColor;
void main() {
    vec3 s = texture(uTex, vUv).rgb * uW[0];
    for (int k = 1; k < 8; k++) {
        vec2 o = uStep * float(k);
        s += (texture(uTex, vUv + o).rgb + texture(uTex, vUv - o).rgb) * uW[k];
    }
    oColor = vec4(s, 0.0);
}
"""

        // ToneApplyDisplay: the tone (EXPOSURE, WHITE LEVEL), the glare added, GAMMA, the dither.
        private const val OUT_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uGlare;
uniform float uExposure;
uniform float uWhiteSqrRcp;
uniform sampler2D uExpTex;
uniform float uAuto;
uniform float uGamma;
uniform float uDither;
uniform float uSeed;
out vec4 oColor;
void main() {
    float exposure = uAuto > 0.5 ? texture(uExpTex, vec2(0.5)).r : uExposure;
    vec3 y = texture(uScene, vUv).rgb * exposure;
    vec3 t = y * (1.0 + y * uWhiteSqrRcp) / (1.0 + y) + texture(uGlare, vUv).rgb;
    t = pow(max(t, 0.0), vec3(1.0 / uGamma));
    float n = fract(sin(dot(gl_FragCoord.xy + uSeed * 71.0, vec2(12.9898, 78.233))) * 43758.5453);
    oColor = vec4(t + (n - 0.5) * uDither, 1.0);
}
"""
    }
}

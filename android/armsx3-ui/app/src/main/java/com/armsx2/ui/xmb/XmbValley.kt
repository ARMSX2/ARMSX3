package com.armsx2.ui.xmb

import android.content.Context
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
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Valley, the console's canyon visualizer, read from the player's own firmware (canyon.qrc: its
 * settings, its presets and its normal map) and drawn the way the firmware draws it.
 *
 * A ring of 128 rows of 128 points runs away from the camera. Each new row comes from the music:
 * the console's spectrum ([XmbVisualizer.Levels.column], lows at the edges, highs in the middle),
 * weighted toward the middle (TERRAIN WIDTH), on the valley's own profile (VALLEY HEIGHT, CENTRE,
 * WIDTH, SHARP), eased in on a spring from the row before and smoothed across. The vertex shader
 * lifts it (TERRAIN HEIGHT) and rolls a wave along it at LINE AHEAD; new rows rise in from their
 * own random heights until TIME AHEAD catches them up. The colour comes from a lookup by how
 * squarely a point faces the camera (COLOUR R/G/B, SCALE, BIAS, FADE), lit toward the line's own
 * colour near the line, and fogs into the sky. Then the depth of field (FOCAL DEPTH and VARIANCE,
 * SKY BLUR) and the feedback that smears each frame into the next (FEEDBACK, X, Y, ROT).
 *
 * The presets come in groups, as on the console: one gives way to another of its group every
 * twenty seconds or so, and the right stick (or a swipe) changes them: left and right another of
 * the group, up and down another group.
 */
internal object XmbValleyFirmware {
    /** One preset's settings: the firmware's defaults with the preset's own on top. */
    class Params(private val m: Map<String, Float>) {
        private fun f(key: String, d: Float) = m[key] ?: d
        val lineAhead = f("LINE AHEAD", 127f)
        val lineWidth = f("LINE WIDTH", 5f)
        val lineHeight = f("LINE HEIGHT", 0.01f)
        val lineLength = f("LINE LENGTH", 5f)
        val lineBias = f("LINE BIAS", 0f)
        val terrainHeight = f("TERRAIN HEIGHT", 1f)
        val terrainWidth = f("TERRAIN WIDTH", 1f)
        val terrainSlide = f("TERRAIN SLIDE", 1f)
        val scaleX = f("SCALE X", 1f)
        val textureScale = f("TEXTURE SCALE", 10f)
        val bumpScale = f("BUMP SCALE", 0f)
        val valleyHeight = f("VALLEY HEIGHT", 0f)
        val valleyCentre = f("VALLEY CENTRE", 0f)
        val valleyWidth = f("VALLEY WIDTH", 0f)
        val valleySharp = f("VALLEY SHARP", 0f)
        val focalDepth = f("FOCAL DEPTH", 50f)
        val focalVariance = f("FOCAL VARIANCE", 50f)
        val lod = f("LOD", 0f)
        val feedback = f("FEEDBACK", 0.9f)
        val feedbackX = f("FEEDBACK X", 1f)
        val feedbackY = f("FEEDBACK Y", 1f)
        val feedbackRot = f("FEEDBACK ROT", 0f)
        val skyBlur = f("SKY BLUR", 0f)
        val posX = f("POS X", 0f)
        val posY = f("POS Y", 8f)
        val posZ = f("POS Z", 0f)
        val targetX = f("TARGET X", 0f)
        val targetY = f("TARGET Y", -3f)
        val targetZ = f("TARGET Z", 156f)
        val posSpeed = f("POS SPEED", 20f)
        val timeAhead = f("TIME AHEAD", 159f)
        val zoom = f("ZOOM", 65f)
        val fade = f("FADE COLOUR", 0f)
        val colour = floatArrayOf(f("COLOUR R", 10f), f("COLOUR G", 10f), f("COLOUR B", 10f))
        val colourScale = f("COLOUR SCALE", 0f)
        val colourBias = f("COLOUR BIAS", 0f)
        val line = floatArrayOf(f("LINE R", 0f), f("LINE G", 0f), f("LINE B", 0f))
        val fogMin = f("FOG MIN", 30f)
        val fogMax = f("FOG MAX", 70f)
        val fog = floatArrayOf(f("FOG R", 0f), f("FOG G", 0f), f("FOG B", 0f))
    }

    /** A group of presets, as the console's player keeps them, and how long each one stays. */
    class Group(val name: String, val presets: List<String>, val stay: Float)

    class Pack(
        val groups: List<Group>,
        private val files: Map<String, ByteArray>,
        /** The normal map's levels, largest first: width, height and RGBA bytes. */
        val normalMap: List<Triple<Int, Int, ByteArray>>,
    ) {
        private val cache = HashMap<String, Params>()

        fun params(preset: String): Params = synchronized(cache) {
            cache.getOrPut(preset) {
                val m = HashMap<String, Float>()
                for (menu in MENUS) {
                    files["canyon/$menu"]?.let { m.putAll(parseMnu(it)) }
                    files["override/$preset/canyon/$menu"]?.let { m.putAll(parseMnu(it)) }
                }
                Params(m)
            }
        }
    }

    private val MENUS = listOf("CANYON_LINE.mnu", "CANYON_TERRAIN.mnu", "CANYON_BLUR.mnu", "CANYON_CAMERA.mnu", "CANYON_COLOUR.mnu", "CANYON_FOG.mnu")

    // The player's groups, in its order, with the presets toured of each and how many seconds each
    // stays: those that draw the land. The player's own list also has Bir_A to C, Volvic, Voyage01
    // and Voyage04, whose TIME AHEAD sits short of the rows coming in, so the rise throws every new
    // row into spikes (Volvic's fan right across the screen; Voyage01's leave nothing in sight); and
    // the pack holds a Voyage11 the player never shows.
    private val GROUPS = listOf(
        Triple("Voyage", listOf(2, 3, 5, 6, 7, 8, 9, 10), 16f),
        Triple("Volcano", listOf(1, 2, 3, 4), 20f),
        Triple("Blue", listOf(1, 2, 3, 4), 20f),
    )

    /** The group the player starts on: Blue, as the console's does. */
    const val FIRST_GROUP = 2

    @Volatile private var loaded: Pack? = null

    fun load(): Pack? {
        loaded?.let { return it }
        val files = XmbFirmware.devFlash()?.let { XmbQrc.open(File(it, "vsh/resource/qgl/canyon.qrc")) } ?: return null
        val names = files.keys.mapNotNull { k ->
            k.removePrefix("override/").substringBefore('/', "").takeIf { k.startsWith("override/") && it.isNotEmpty() }
        }.toSortedSet()
        val groups = GROUPS.mapNotNull { (prefix, numbers, stay) ->
            val list = numbers.map { prefix + it.toString().padStart(2, '0') }.filter { it in names }
            if (list.isEmpty()) null else Group(prefix, list, stay)
        }
        if (groups.isEmpty()) return null
        val normal = files["canyon/textures/DDS/canyon_normalmap.dds"]?.let(::dds).orEmpty()
        return Pack(groups, files, normal).also { loaded = it }
    }

    private fun parseMnu(bytes: ByteArray): Map<String, Float> =
        String(bytes, Charsets.ISO_8859_1).lineSequence().mapNotNull { l ->
            val parts = l.trim().split(':')
            if (parts.size == 3 && (parts[1] == "float" || parts[1] == "int")) parts[2].toFloatOrNull()?.let { parts[0] to it } else null
        }.toMap()

    /** An uncompressed 32-bit DDS (A8R8G8B8, as the firmware's normal map is), every level, as RGBA. */
    private fun dds(b: ByteArray): List<Triple<Int, Int, ByteArray>> {
        if (b.size < 128 || String(b, 0, 4, Charsets.ISO_8859_1) != "DDS ") return emptyList()
        fun le(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        val h = le(12)
        val w = le(16)
        val mips = max(1, le(28))
        val bits = le(88)
        if (bits != 32 || le(92) != 0xff0000 || le(96) != 0xff00 || le(100) != 0xff) return emptyList()
        val out = ArrayList<Triple<Int, Int, ByteArray>>()
        var o = 128
        var lw = w
        var lh = h
        repeat(mips) {
            val n = lw * lh * 4
            if (o + n > b.size) return out
            val rgba = ByteArray(n)
            for (i in 0 until lw * lh) {
                rgba[i * 4] = b[o + i * 4 + 2]
                rgba[i * 4 + 1] = b[o + i * 4 + 1]
                rgba[i * 4 + 2] = b[o + i * 4]
                rgba[i * 4 + 3] = b[o + i * 4 + 3]
            }
            out.add(Triple(lw, lh, rgba))
            o += n
            lw = max(1, lw / 2)
            lh = max(1, lh / 2)
        }
        return out
    }
}

/**
 * Where Valley is in its presets, kept while the app runs so it comes back where it was. Changed by
 * [flick] from any thread; the scene picks the change up on its own thread.
 */
internal object XmbValleyPresets {
    @Volatile var group = XmbValleyFirmware.FIRST_GROUP
    @Volatile var preset = 0

    /** Right stick or swipe, one step: x -1/1 another preset of the group, y -1/1 another group. */
    val flicks = AtomicInteger(0)

    fun flick(dx: Int, dy: Int) {
        val code = when {
            dx < 0 -> 1
            dx > 0 -> 2
            dy < 0 -> 3
            dy > 0 -> 4
            else -> return
        }
        flicks.set(code)
    }
}

/**
 * The terrain, worked out on the CPU frame by frame as the firmware does it, sixty times a second.
 * [vertices] holds the ring (128 rows of 128 points: position, normal, texture coordinates); the
 * rows changed since the last [takeDirty] are drawn from there.
 */
internal class XmbValleyScene {
    var params: XmbValleyFirmware.Params? = null
        private set

    val vertices = FloatArray(ROWS * COLS * STRIDE)
    private val z = FloatArray(ROWS)
    private var slot = 0
    var rowZ = 0f
        private set
    var travelled = -10f
        private set
    private val prevY = FloatArray(COLS)
    private val vel = FloatArray(COLS)
    private val newY = FloatArray(COLS)
    private val target = FloatArray(COLS)
    private val spectrum = FloatArray(COLS)
    private var seed = 0
    private val dirty = BooleanArray(ROWS)
    private var allDirty = true

    /** The newest row's slot: the strip from it to the oldest is the ring's seam, not drawn. */
    val newest get() = (slot + ROWS - 1) % ROWS

    /** The engine's own random numbers, -1..1: its integer hash noise. */
    fun rand(): Float {
        seed++
        val n = (seed shl 13) xor seed
        val v = (n * (n * n * 15731 + 789221) + 1376312589) and 0x7fffffff
        return 1f - v / 1073741824f
    }

    /** A new preset: its settings, and the terrain started over flat, rows rising into place. */
    fun start(p: XmbValleyFirmware.Params) {
        params = p
        travelled = -10f
        rowZ = 0f
        slot = 0
        prevY.fill(0f)
        vel.fill(0f)
        for (r in 0 until ROWS) {
            z[r] = r.toFloat()
            for (c in 0 until COLS) {
                val i = (r * COLS + c) * STRIDE
                val e = 2f * abs(0.5f - c / 128f)
                vertices[i] = c - 64f
                vertices[i + 1] = 0f
                vertices[i + 2] = r.toFloat()
                vertices[i + 3] = 0f
                vertices[i + 4] = 0f
                vertices[i + 5] = 1f
                vertices[i + 6] = 0f
                vertices[i + 7] = c / 127f
                vertices[i + 8] = r / 127f
                vertices[i + 9] = r - 50f * e * e
                vertices[i + 10] = rand() * 500f
            }
        }
        allDirty = true
    }

    /**
     * One sixtieth of a second: the music's row of [input] ([XmbVisualizer.COLUMNS] points, 0..3)
     * weighted and scaled as the firmware does, the camera moved on, and a new row when it is due.
     */
    fun step(dt: Float, input: FloatArray) {
        val p = params ?: return
        var top = 1f
        for (c in 0 until COLS) {
            val v = input[c] * exp(p.terrainWidth * (1f - 2f * abs(0.5f - c / 128f)))
            spectrum[c] = v
            if (v > top) top = v
        }
        for (c in 0 until COLS) spectrum[c] /= top
        travelled += dt * p.posSpeed
        if (rowZ < travelled) {
            rowZ += 1f
            row(p)
        }
        if (travelled > 99f) rebase()
    }

    private fun row(p: XmbValleyFirmware.Params) {
        val x0 = -64f - 0.25f * p.terrainSlide
        for (c in 0 until COLS) {
            val u = c / 128f - 0.5f
            val s = ((abs(2f * u - p.valleyCentre) - p.valleyWidth) * p.valleySharp).coerceIn(0f, 1f)
            val t = s * s * (3f - 2f * s) * p.valleyHeight + spectrum[c] + (rand() * 0.5f + 0.5f) * 0.02f
            target[c] = t
            val d = 0.7f + 0.295f * abs(2f * u)
            val v = vel[c] + (t - prevY[c]) * (1f - d)
            newY[c] = prevY[c] + v
            vel[c] = v * (d * 0.5f)
        }
        // Five passes of smoothing across the row, in place, the edges against themselves.
        repeat(5) {
            for (c in 0 until COLS) {
                val l = newY[max(c - 1, 0)]
                val r = newY[min(c + 1, COLS - 1)]
                newY[c] += 0.25f * (l + r - 2f * newY[c])
            }
        }
        val s = slot
        val zz = rowZ + 127f
        z[s] = zz
        for (c in 0 until COLS) {
            // The normal: across the row, and from the row before (one unit back).
            val l = max(c - 1, 0)
            val r = min(c + 1, COLS - 1)
            val ax = (l - r).toFloat()
            val ay = newY[l] - newY[r]
            val by = newY[c] - prevY[c]
            // cross((ax, ay, 0), (0, by, 1))
            var nx = ay
            var ny = -ax
            var nz = ax * by
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
            nx /= len; ny /= len; nz /= len
            val e = 2f * abs(0.5f - c / 128f)
            val i = (s * COLS + c) * STRIDE
            vertices[i] = x0 + c
            vertices[i + 1] = newY[c]
            vertices[i + 2] = zz
            vertices[i + 3] = target[c]
            vertices[i + 4] = nx
            vertices[i + 5] = ny
            vertices[i + 6] = nz
            vertices[i + 9] = zz + 1f - 50f * e * e
            vertices[i + 10] = rand() * 500f
        }
        System.arraycopy(newY, 0, prevY, 0, COLS)
        dirty[s] = true
        slot = (slot + 1) % ROWS
    }

    // Brought back toward 0 now and then, as the firmware does, so the numbers stay small.
    private fun rebase() {
        val shift = travelled
        travelled -= shift
        rowZ -= shift
        for (r in 0 until ROWS) {
            z[r] -= shift
            for (c in 0 until COLS) {
                val i = (r * COLS + c) * STRIDE
                val e = 2f * abs(0.5f - c / 128f)
                vertices[i + 2] = z[r]
                vertices[i + 9] = z[r] + 1f - 50f * e * e
            }
        }
        allDirty = true
    }

    /** Calls [each] with every row changed since the last time, or with -1 for all of them. */
    fun takeDirty(each: (Int) -> Unit) {
        if (allDirty) {
            allDirty = false
            dirty.fill(false)
            each(-1)
            return
        }
        for (r in 0 until ROWS) if (dirty[r]) {
            dirty[r] = false
            each(r)
        }
    }

    companion object {
        const val ROWS = 128
        const val COLS = 128
        /** Floats a point: position (x, y, z, the row's own height), normal, texture coordinates. */
        const val STRIDE = 11
    }
}

/**
 * Valley on screen: a TextureView with its own GL thread, as XMB mode's wave has. [source] fills a
 * frame of the console's spectrum for now (zeros while nothing plays).
 */
internal class XmbValleyView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var source: ((FloatArray) -> Unit)? = null
    private var thread: RenderThread? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = RenderThread(st, w, h) { out -> source?.invoke(out) ?: out.fill(0f) }.also {
            it.paused = windowVisibility != VISIBLE
            it.start()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        thread?.paused = visibility != VISIBLE
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        thread?.resize(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val source: (FloatArray) -> Unit,
    ) : Thread("xmb-valley") {
        @Volatile private var running = true
        @Volatile private var sizeDirty = true
        @Volatile var paused = false
            set(value) { field = value; LockSupport.unpark(this) }

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private val scene = XmbValleyScene()
        private var pack: XmbValleyFirmware.Pack? = null
        private val input = FloatArray(XmbVisualizer.COLUMNS)
        private var presetLeft = 0f

        private var terrainProg = 0
        private var cocProg = 0
        private var layerProg = 0
        private var outProg = 0
        private var terrainVao = 0
        private var vbo = 0
        private var quadVao = 0
        private var normalTex = 0
        private var lutTex = 0
        private var noise1 = 0
        private var noise2 = 0
        private val vertBuf: FloatBuffer = ByteBuffer.allocateDirect(XmbValleyScene.ROWS * XmbValleyScene.COLS * XmbValleyScene.STRIDE * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        // Render targets: the scene (with mip levels, for the blur), how blurred each point is, the
        // feedback pair, and the depth they share.
        private val fbo = IntArray(4)
        private val tex = IntArray(4)
        private var depth = 0
        private var ping = 0
        private var targetsW = 0
        private var targetsH = 0
        private var clearFeedback = true

        private val view = FloatArray(16)
        private val proj = FloatArray(16)
        private val mvp = FloatArray(16)

        fun resize(w: Int, h: Int) { width = w; height = h; sizeDirty = true }
        fun finish() { running = false; LockSupport.unpark(this); runCatching { join(500) } }

        override fun run() {
            if (!initEgl()) { teardown(); return }
            val ok = runCatching { initGl() }.onFailure { Log.w(TAG, "GL init failed", it) }.isSuccess
            if (!ok) { teardown(); return }
            var last = System.nanoTime()
            var carry = 0f
            while (running) {
                if (paused) {
                    LockSupport.park(this)
                    last = System.nanoTime()
                    continue
                }
                val frameStart = System.nanoTime()
                var dt = ((frameStart - last) / 1e9f).coerceIn(0f, 0.25f)
                last = frameStart
                runCatching {
                    // The firmware moves on a frame at a time; so does this, sixty a second.
                    carry += dt
                    var steps = 0
                    while (carry >= STEP && steps < 4) {
                        tick()
                        carry -= STEP
                        steps++
                    }
                    if (steps == 4) carry = 0f
                    draw()
                }.onFailure { Log.w(TAG, "frame failed", it) }
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) running = false
                val leftMs = 16L - (System.nanoTime() - frameStart) / 1_000_000L
                if (leftMs > 1) runCatching { sleep(leftMs) }
            }
            teardown()
        }

        private fun tick() {
            val pk = pack ?: return
            when (XmbValleyPresets.flicks.getAndSet(0)) {
                1, 2 -> otherPreset(pk)
                3, 4 -> otherGroup(pk)
            }
            presetLeft -= STEP
            if (presetLeft <= 0f) otherPreset(pk)
            source(input)
            scene.step(STEP, input)
        }

        private fun otherPreset(pk: XmbValleyFirmware.Pack) {
            val g = pk.groups[XmbValleyPresets.group.coerceIn(0, pk.groups.size - 1)]
            if (g.presets.size > 1) {
                val n = g.presets.size
                var i = ((scene.rand() * 0.5f + 0.5f) * (n - 1)).toInt().coerceIn(0, n - 2)
                if (i >= XmbValleyPresets.preset) i++
                XmbValleyPresets.preset = i
            }
            startPreset(pk)
        }

        private fun otherGroup(pk: XmbValleyFirmware.Pack) {
            val n = pk.groups.size
            if (n > 1) {
                var g = ((scene.rand() * 0.5f + 0.5f) * (n - 1)).toInt().coerceIn(0, n - 2)
                if (g >= XmbValleyPresets.group) g++
                XmbValleyPresets.group = g
            }
            XmbValleyPresets.preset = 0
            startPreset(pk)
        }

        private fun startPreset(pk: XmbValleyFirmware.Pack) {
            val g = pk.groups[XmbValleyPresets.group.coerceIn(0, pk.groups.size - 1)]
            val name = g.presets[XmbValleyPresets.preset.coerceIn(0, g.presets.size - 1)]
            val p = pk.params(name)
            Log.i(TAG, "preset $name")
            scene.start(p)
            presetLeft = g.stay
            fresLut(p)
            clearFeedback = true
        }

        // ---- drawing ----
        private fun draw() {
            val p = scene.params
            val w = width
            val h = height
            if (sizeDirty || w != targetsW || h != targetsH) { targets(w, h); sizeDirty = false }
            if (p == null) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, w, h)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            upload()

            // The camera, POS and TARGET from where the terrain has got to.
            val t = scene.travelled
            Matrix.setLookAtM(view, 0, p.posX, p.posY, p.posZ + t, p.targetX, p.targetY, p.targetZ + t, 0f, 1f, 0f)
            // Held upright, as much across as the console's 16:9 shows, and more up and down.
            val aspect = w.toFloat() / h.coerceAtLeast(1)
            Matrix.perspectiveM(proj, 0, xmbFovy(p.zoom, aspect), aspect, 0.1f, 10000f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            val fade = p.fade * p.fade
            val newest = scene.newest

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_BLEND)

            // How blurred each point is: the sky at SKY BLUR, the ground by its distance from the focus.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[COC])
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(p.skyBlur.coerceIn(0f, 1f), 1f, 1f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glUseProgram(cocProg)
            terrainUniforms(cocProg, p, t)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(cocProg, "uFocalDepth"), p.focalDepth)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(cocProg, "uFocus"), 1f / p.focalVariance.takeIf { it != 0f }.let { it ?: 1e-6f })
            drawTerrain(newest)

            // The scene: the ground on its fog-coloured sky.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[FRONT])
            GLES30.glClearColor(p.fog[0] * fade, p.fog[1] * fade, p.fog[2] * fade, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glUseProgram(terrainProg)
            terrainUniforms(terrainProg, p, t)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(terrainProg, "uFogColour"), p.fog[0] * fade, p.fog[1] * fade, p.fog[2] * fade)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(terrainProg, "uLineColour"), p.line[0] * fade, p.line[1] * fade, p.line[2] * fade)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProg, "uBumpScale"), p.bumpScale)
            bind(0, normalTex, terrainProg, "uNormalMap")
            bind(1, lutTex, terrainProg, "uFresLut")
            drawTerrain(newest)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[FRONT])
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glBindVertexArray(quadVao)

            // Depth of field and feedback, into one of the pair from the other.
            val dst = FEEDBACK_A + ping
            val prev = FEEDBACK_A + (1 - ping)
            if (clearFeedback) {
                for (k in intArrayOf(FEEDBACK_A, FEEDBACK_B)) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[k])
                    GLES30.glClearColor(p.fog[0] * fade, p.fog[1] * fade, p.fog[2] * fade, 1f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                }
                clearFeedback = false
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[dst])
            GLES30.glUseProgram(layerProg)
            bind(0, tex[COC], layerProg, "uCoc")
            bind(1, tex[prev], layerProg, "uScreen")
            bind(2, noise1, layerProg, "uNoise1")
            bind(3, tex[FRONT], layerProg, "uFront")
            bind(4, noise2, layerProg, "uNoise2")
            GLES30.glUniform1f(GLES30.glGetUniformLocation(layerProg, "uLod"), p.lod)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(layerProg, "uFeedback"), p.feedback)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(layerProg, "uResRecip"), 1f / w, 1f / h)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(layerProg, "uNoiseUV"), scene.rand() * 0.5f + 0.5f, scene.rand() * 0.5f + 0.5f, 1f / 128f)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(layerProg, "uFbScale"), p.feedbackX, p.feedbackY)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(layerProg, "uFbRot"), sin(p.feedbackRot), cos(p.feedbackRot))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            // To the screen through the console's last soft blur.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(outProg)
            bind(0, tex[dst], outProg, "uTex")
            GLES30.glUniform2f(GLES30.glGetUniformLocation(outProg, "uStep"), 1f / w, 1f / h)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
            ping = 1 - ping
        }

        private fun terrainUniforms(prog: Int, p: XmbValleyFirmware.Params, t: Float) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uEye"), p.posX, p.posY, p.posZ + t)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uLine"), p.lineWidth, p.lineHeight, p.lineLength, p.lineBias)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uTime"), p.lineAhead + t, p.timeAhead + t, 126.9f + t)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uTexScale"), p.textureScale)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFogMin"), p.fogMin)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFogRange"), (p.fogMax - p.fogMin).takeIf { it != 0f } ?: 1e-6f)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uScale"), p.scaleX, 0.3f * p.terrainHeight, 1f)
        }

        // The ring, every strip but the seam from the newest row back round to the oldest.
        private fun drawTerrain(newest: Int) {
            GLES30.glBindVertexArray(terrainVao)
            val perStrip = (XmbValleyScene.COLS - 1) * 6
            val after = newest + 1
            if (after < XmbValleyScene.ROWS) {
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, (XmbValleyScene.ROWS - after) * perStrip, GLES30.GL_UNSIGNED_SHORT, after * perStrip * 2)
            }
            if (newest > 0) GLES30.glDrawElements(GLES30.GL_TRIANGLES, newest * perStrip, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glBindVertexArray(0)
        }

        private fun upload() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val rowFloats = XmbValleyScene.COLS * XmbValleyScene.STRIDE
            scene.takeDirty { r ->
                if (r < 0) {
                    vertBuf.clear(); vertBuf.put(scene.vertices); vertBuf.position(0)
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, scene.vertices.size * 4, vertBuf)
                } else {
                    vertBuf.clear(); vertBuf.put(scene.vertices, r * rowFloats, rowFloats); vertBuf.position(0)
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, r * rowFloats * 4, rowFloats * 4, vertBuf)
                }
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        private fun bind(unit: Int, texture: Int, prog: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit)
        }

        /** The colour by angle: 1 - i/511 to the power of COLOUR R/G/B, scaled and biased, faded. */
        private fun fresLut(p: XmbValleyFirmware.Params) {
            val fade = p.fade * p.fade
            val data = ByteBuffer.allocateDirect(512 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until 512) {
                val t = 1f - i / 511f
                for (k in 0 until 3) data.put(((t.toDouble().pow(p.colour[k].toDouble())).toFloat() * p.colourScale + p.colourBias) * fade)
                data.put(1f)
            }
            data.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 512, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
        }

        // ---- set up ----
        private fun initGl() {
            terrainProg = link(TERRAIN_VS, TERRAIN_FS)
            cocProg = link(TERRAIN_VS, COC_FS)
            layerProg = link(QUAD_VS, LAYER_FS)
            outProg = link(QUAD_VS, OUT_FS)

            // The ring's points, and the strips between rows: two triangles a cell.
            val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); terrainVao = vao[0]
            GLES30.glBindVertexArray(terrainVao)
            val b = IntArray(2); GLES30.glGenBuffers(2, b, 0)
            vbo = b[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, scene.vertices.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
            val stride = XmbValleyScene.STRIDE * 4
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 16)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 28)
            val rows = XmbValleyScene.ROWS
            val cols = XmbValleyScene.COLS
            val idx = ShortArray(rows * (cols - 1) * 6)
            var k = 0
            for (s in 0 until rows) {
                val r0 = s * cols
                val r1 = ((s + 1) % rows) * cols
                for (c in 0 until cols - 1) {
                    idx[k++] = (r0 + c).toShort(); idx[k++] = (r0 + c + 1).toShort(); idx[k++] = (r1 + c).toShort()
                    idx[k++] = (r0 + c + 1).toShort(); idx[k++] = (r1 + c + 1).toShort(); idx[k++] = (r1 + c).toShort()
                }
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b[1])
            val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(idx).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)

            // A screen-filling quad for the passes after.
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

            val t = IntArray(4); GLES30.glGenTextures(4, t, 0)
            normalTex = t[0]; lutTex = t[1]; noise1 = t[2]; noise2 = t[3]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            params(GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
            for ((tx, seed) in listOf(noise1 to 1, noise2 to 2)) {
                val rnd = java.util.Random(seed.toLong())
                val n = ByteBuffer.allocateDirect(64 * 64 * 4)
                repeat(64 * 64 * 4) { n.put(rnd.nextInt(256).toByte()) }
                n.position(0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tx)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 64, 64, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, n)
                params(GLES30.GL_REPEAT, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
            }

            val pk = XmbValleyFirmware.load()
            pack = pk
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, normalTex)
            val levels = pk?.normalMap.orEmpty()
            if (levels.isEmpty()) {
                // Flat, facing up: with no normal map the ground is lit by its shape alone.
                val flat = ByteBuffer.allocateDirect(4).put(byteArrayOf(127, 127, 255.toByte(), 255.toByte()))
                flat.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, flat)
                params(GLES30.GL_REPEAT, GLES30.GL_LINEAR, GLES30.GL_LINEAR)
            } else {
                levels.forEachIndexed { i, (lw, lh, rgba) ->
                    val bb = ByteBuffer.allocateDirect(rgba.size).put(rgba)
                    bb.position(0)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, i, GLES30.GL_RGBA, lw, lh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
                }
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels.size - 1)
                params(GLES30.GL_REPEAT, GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
            }
            if (pk != null) {
                XmbValleyPresets.group = XmbValleyPresets.group.coerceIn(0, pk.groups.size - 1)
                startPreset(pk)
            }
        }

        private fun params(wrap: Int, min: Int, mag: Int) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        }

        private fun targets(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            if (targetsW != 0) {
                GLES30.glDeleteFramebuffers(4, fbo, 0)
                GLES30.glDeleteTextures(4, tex, 0)
                GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
            }
            GLES30.glGenFramebuffers(4, fbo, 0)
            GLES30.glGenTextures(4, tex, 0)
            val rb = IntArray(1); GLES30.glGenRenderbuffers(1, rb, 0); depth = rb[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
            for (i in 0 until 4) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
                val levels = if (i == FRONT) 1 + (31 - Integer.numberOfLeadingZeros(max(w, h))) else 1
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
                params(GLES30.GL_CLAMP_TO_EDGE, if (i == FRONT) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR, GLES30.GL_LINEAR)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
                if (i == FRONT || i == COC) {
                    GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
                }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            targetsW = w
            targetsH = h
            clearFeedback = true
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
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // ES3
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
        private const val TAG = "XmbValley"
        private const val STEP = 1f / 60f
        private const val FRONT = 0
        private const val COC = 1
        private const val FEEDBACK_A = 2
        private const val FEEDBACK_B = 3

        // The firmware's canyon.vpo, line for line in effect: the height lifted by TERRAIN HEIGHT and
        // rolled by the line's wave, new rows risen from their own heights until TIME AHEAD passes
        // them, the normal taken again from the lifted ground, fog by distance.
        private const val TERRAIN_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec4 aTc;
uniform mat4 uMvp;
uniform vec3 uEye;
uniform vec4 uLine;
uniform vec3 uTime;
uniform float uTexScale;
uniform float uFogMin;
uniform float uFogRange;
uniform vec3 uScale;
out vec2 vTc0;
out vec2 vTc1;
out vec2 vParams;
out vec3 vU;
out vec3 vV;
out vec3 vN;
out vec3 vEye;
out float vClip;
out float vDist;
vec3 frame(vec3 n) {
    return n.z < 0.99 ? normalize(cross(n, vec3(0.0, 0.0, 1.0))) : normalize(cross(n, vec3(0.0, 1.0, 0.0)));
}
float falloff(float dz) {
    float d = clamp(abs(dz) / uLine.x, 0.0, 1.0);
    return 1.0 - d * d * (3.0 - 2.0 * d);
}
void main() {
    vTc0 = aTc.xy;
    vTc1 = aTc.xy * uTexScale;
    vClip = aPos.z - uTime.z;
    vec3 n = normalize(aNrm);
    vec3 t = frame(n);
    vec3 b = cross(n, t);
    float rise = clamp((aTc.z - uTime.y) * 0.01, 0.0, 9999.0);
    vec3 p = vec3(aPos.x * uScale.x, aPos.y + aTc.w * rise * rise, aPos.z);
    float fP = falloff(p.z - uTime.x);
    vec3 pt = p + t;
    vec3 pb = p + b;
    float cT = cos(abs(uTime.x - pt.z) * uLine.z);
    float cB = cos(abs(uTime.x - pb.z) * uLine.z);
    float yP = p.y * (uLine.y * cT * (uLine.w + fP) + uScale.y);
    float yT = pt.y * (uLine.y * cT * (uLine.w + falloff(uTime.x - pt.z)) + uScale.y);
    float yB = pb.y * (uLine.y * cB * (uLine.w + falloff(uTime.x - pb.z)) + uScale.y);
    float y = yP + 0.2 * fP * (aPos.w * uScale.y - yP);
    vec3 q = vec3(p.x, yP, p.z);
    vec3 dt = vec3(pt.x, yT, pt.z) - q;
    vec3 db = vec3(pb.x, yB, pb.z) - q;
    vec3 world = vec3(p.x, y, p.z);
    gl_Position = uMvp * vec4(world, 1.0);
    vec3 toEye = uEye - world;
    float dist = length(toEye);
    vDist = dist;
    vEye = toEye / dist;
    vec3 nn = normalize(cross(db, dt));
    vParams = vec2(1.0 - clamp((dist - uFogMin) / uFogRange, 0.0, 1.0), 0.2 * fP * abs(y));
    vN = nn;
    vU = frame(nn);
    vV = cross(nn, vU);
}
"""

        // canyon.fpo: the normal map tips the normal, the colour comes from how squarely the point
        // faces the camera, lit toward the line's colour near the line, fogged with distance and
        // toward the sides.
        private const val TERRAIN_FS = """#version 300 es
precision highp float;
in vec2 vTc0;
in vec2 vTc1;
in vec2 vParams;
in vec3 vU;
in vec3 vV;
in vec3 vN;
in vec3 vEye;
in float vClip;
in float vDist;
uniform sampler2D uNormalMap;
uniform sampler2D uFresLut;
uniform vec3 uFogColour;
uniform vec3 uLineColour;
uniform float uBumpScale;
out vec4 fragColor;
void main() {
    if (vClip > 0.0) discard;
    vec3 m = texture(uNormalMap, vTc1).xyz - 0.5;
    vec3 nb = vU * (m.x * uBumpScale) + vV * (m.y * uBumpScale) + vN * m.z;
    float facing = abs(dot(vEye, nb) * inversesqrt(max(dot(nb, nb), 1e-12)));
    vec3 c = texture(uFresLut, vec2(facing, 0.5)).rgb;
    c += vParams.y * (uLineColour - c);
    c = vParams.x * (c - uFogColour) + uFogColour;
    float side = abs(vTc0.x - 0.5);
    c += clamp(side * side * side, 0.0, 1.0) * (uFogColour - c);
    fragColor = vec4(c, 1.0);
}
"""

        // canyon_blur_ammount.fpo: how far from the focus, the distance, and which side of it.
        private const val COC_FS = """#version 300 es
precision highp float;
in float vClip;
in float vDist;
in vec2 vTc0;
in vec2 vTc1;
in vec2 vParams;
in vec3 vU;
in vec3 vV;
in vec3 vN;
in vec3 vEye;
uniform float uFocalDepth;
uniform float uFocus;
out vec4 fragColor;
void main() {
    if (vClip > 0.0) discard;
    fragColor = vec4(clamp(abs(vDist - uFocalDepth) * uFocus, 0.0, 1.0), vDist * 0.01, vDist > uFocalDepth ? 1.0 : 0.0, 0.0);
}
"""

        private const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vTc;
void main() {
    vTc = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        // canyon_layer_blur.fpo: the scene read from a coarser level and jittered as far as each
        // point is out of focus, laid over the last frame drawn back in, scaled and turned about
        // the middle (FEEDBACK, X, Y, ROT), with the firmware's dither.
        private const val LAYER_FS = """#version 300 es
precision highp float;
in vec2 vTc;
uniform sampler2D uCoc;
uniform sampler2D uScreen;
uniform sampler2D uNoise1;
uniform sampler2D uFront;
uniform sampler2D uNoise2;
uniform float uLod;
uniform float uFeedback;
uniform vec2 uResRecip;
uniform vec3 uNoiseUV;
uniform vec2 uFbScale;
uniform vec2 uFbRot;
out vec4 fragColor;
void main() {
    vec2 wpos = gl_FragCoord.xy;
    float blur = clamp(texture(uCoc, vTc).x * 0.75 + uLod, 0.0, 1.0);
    vec3 n1 = texture(uNoise1, wpos / 64.0).xyz - 0.5;
    float level = blur * 6.0;
    float w = clamp(level, 0.0, 1.0);
    vec2 uv = wpos * uResRecip + level * n1.xy * vec2(0.0004, 0.000225);
    vec3 front = textureLod(uFront, uv, clamp(level - 2.0, 0.0, 1.0) + 0.1).rgb;
    float wF = w * uFeedback;
    vec3 sum = (w - wF) * (n1 * (1.0 / 128.0) + front);
    float total = w + uFeedback - wF;
    vec2 c = (vTc - 0.5) * uFbScale;
    vec2 r = vec2(dot(c, uFbRot.yx), dot(vec2(-uFbRot.x, uFbRot.y), c)) + 0.5;
    vec3 prev = texture(uScreen, r).rgb;
    vec3 col = total > 0.0 ? (prev * uFeedback + sum) / total : front;
    vec4 n2 = texture(uNoise2, wpos * uNoiseUV.z + uNoiseUV.xy);
    fragColor = vec4(col, 1.0) + vec4(n2.rgb, 0.0) * (1.0 / 64.0);
}
"""

        // canyon_layer_blur_blur.fpo: four samples half a point either way, averaged.
        private const val OUT_FS = """#version 300 es
precision highp float;
in vec2 vTc;
uniform sampler2D uTex;
uniform vec2 uStep;
out vec4 fragColor;
void main() {
    vec2 h = uStep * 0.5;
    vec4 c = texture(uTex, vTc + vec2(h.x, -h.y)) + texture(uTex, vTc - h)
        + texture(uTex, vTc + h) + texture(uTex, vTc + vec2(-h.x, h.y));
    fragColor = vec4(c.rgb * 0.25, 1.0);
}
"""
    }
}

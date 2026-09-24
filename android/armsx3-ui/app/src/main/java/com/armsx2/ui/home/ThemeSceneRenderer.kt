package com.armsx2.ui.home

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/**
 * Draws a [ThemeScene] with GLES 3: each shown actor's mesh with its texture, in the scene's order,
 * the way the theme's material asks (see [RafScene.Material]). Create, use and [release] it on the
 * thread whose context is current.
 *
 * Textures are decoded to RGBA with premultiplied alpha, so their see-through edges filter and
 * mipmap without dark fringes, and only for actors that can be shown.
 */
internal class ThemeSceneRenderer(private val play: ThemeScene, private val raf: P3tTheme.Bytes) {

    private class Gpu(val vao: Int, val buffers: IntArray, val count: Int)

    private var program = 0
    private var uMvp = 0
    private var uUv = 0
    private var uColor = 0
    private var uOpaque = 0
    private val meshes = IdentityHashMap<RafScene.Mesh, Gpu>()
    private val textures = IdentityHashMap<P3tAnimation.Texture, Int>()
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Build what the scene draws. Throws when GL cannot. */
    fun init() {
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uUv = GLES30.glGetUniformLocation(program, "uUv")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uOpaque = GLES30.glGetUniformLocation(program, "uOpaque")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)

        val drawn = play.actors.filter { it.drawable }
        val limit = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val unique = drawn.mapNotNull { it.source.material?.texture }.distinct()
        // Two in three of these scenes' pixels are backdrop the size of the screen; past that,
        // bigger textures only cost memory. Halve them again for a scene that would still need
        // more than a budget.
        var side = minOf(MAX_SIDE, limit.coerceAtLeast(MIN_SIDE))
        while (side > MIN_SIDE && unique.sumOf { bytesAt(it, side) } > TEXTURE_BUDGET) side /= 2
        for (a in drawn) {
            val mesh = a.source.mesh ?: continue
            if (!meshes.containsKey(mesh)) meshes[mesh] = upload(mesh)
            val texture = a.source.material?.texture ?: continue
            if (!textures.containsKey(texture)) textures[texture] = upload(texture, side)
        }
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL error building the scene" }
    }

    fun draw(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // Alpha stays the 1 it was cleared to, so the view is opaque whatever is blended into it.
        GLES30.glColorMask(true, true, true, false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        val camera = play.camera
        val lens = play.scene.camera
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val viewProjection = ThemeScene.multiply(
            ThemeScene.projection(camera.yfov, aspect, lens.znear, lens.zfar),
            ThemeScene.viewMatrix(camera.position, camera.direction, camera.up),
        )
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (a in play.actors) {
            if (!a.shown) continue
            val material = a.source.material ?: continue
            val gpu = meshes[a.source.mesh] ?: continue
            val texture = textures[material.texture]?.takeIf { it != 0 } ?: continue
            when {
                material.opaque -> GLES30.glDisable(GLES30.GL_BLEND)
                material.additive -> {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
                }
                else -> {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                }
            }
            if (material.depthTest) GLES30.glEnable(GLES30.GL_DEPTH_TEST) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(material.depthTest && material.opaque)
            ThemeScene.multiply(viewProjection, ThemeScene.modelMatrix(a.position, a.rotation, a.scale, model), mvp)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniform4f(uUv, a.uvScale[0], a.uvScale[1], a.uvOffset[0], a.uvOffset[1])
            GLES30.glUniform4f(uColor, a.color[0], a.color[1], a.color[2], a.color[3])
            GLES30.glUniform1f(uOpaque, if (material.opaque) 1f else 0f)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glBindVertexArray(gpu.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gpu.count, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }

    fun release() {
        for (gpu in meshes.values) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(gpu.vao), 0)
            GLES30.glDeleteBuffers(gpu.buffers.size, gpu.buffers, 0)
        }
        meshes.clear()
        val names = textures.values.filter { it != 0 }.toIntArray()
        if (names.isNotEmpty()) GLES30.glDeleteTextures(names.size, names, 0)
        textures.clear()
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
    }

    /** Positions and texture coordinates interleaved, and the triangles. */
    private fun upload(mesh: RafScene.Mesh): Gpu {
        val n = mesh.vertexCount
        val vertices = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (v in 0 until n) {
            vertices.put(mesh.positions, v * 3, 3)
            vertices.put(mesh.uvs, v * 2, 2)
        }
        vertices.position(0)
        val indices = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in mesh.indices) indices.put(i.toShort()) // an Edge mesh counts its vertices in 16 bits
        indices.position(0)

        val vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        val buffers = IntArray(2).also { GLES30.glGenBuffers(2, it, 0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * STRIDE, vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        return Gpu(vao, buffers, mesh.indices.size)
    }

    /** The texture, no larger than [side] either way, mipmapped and repeating. 0 when it cannot be read. */
    private fun upload(texture: P3tAnimation.Texture, side: Int): Int {
        var pixels = P3tAnimation.decode(raf, texture) ?: return 0
        var w = texture.width
        var h = texture.height
        premultiply(pixels)
        while (w > side || h > side) {
            pixels = halve(pixels, w, h)
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
        }
        val rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            rgba.put((p ushr 16).toByte()).put((p ushr 8).toByte()).put(p.toByte()).put((p ushr 24).toByte())
        }
        rgba.position(0)
        val name = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return name
    }

    companion object {
        private const val TAG = "ThemeScene"
        private const val STRIDE = 20
        private const val MAX_SIDE = 2048
        private const val MIN_SIDE = 256
        private const val TEXTURE_BUDGET = 96L shl 20

        /** GPU bytes for [t] at no more than [side] a side, with its mipmaps. */
        private fun bytesAt(t: P3tAnimation.Texture, side: Int): Long {
            var w = t.width.toLong()
            var h = t.height.toLong()
            while (w > side || h > side) { w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1) }
            return w * h * 4 * 4 / 3
        }

        /** ARGB with the colour scaled by alpha, in place. */
        internal fun premultiply(argb: IntArray) {
            for (i in argb.indices) {
                val p = argb[i]
                val a = p ushr 24
                if (a == 255) continue
                val r = ((p shr 16) and 0xFF) * a / 255
                val g = ((p shr 8) and 0xFF) * a / 255
                val b = (p and 0xFF) * a / 255
                argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        /** Half the size each way, each pixel the average of the 2x2 it covers. */
        internal fun halve(argb: IntArray, w: Int, h: Int): IntArray {
            val w2 = (w / 2).coerceAtLeast(1)
            val h2 = (h / 2).coerceAtLeast(1)
            val out = IntArray(w2 * h2)
            for (y in 0 until h2) for (x in 0 until w2) {
                val x0 = minOf(2 * x, w - 1); val x1 = minOf(2 * x + 1, w - 1)
                val y0 = minOf(2 * y, h - 1); val y1 = minOf(2 * y + 1, h - 1)
                val a = argb[y0 * w + x0]; val b = argb[y0 * w + x1]
                val c = argb[y1 * w + x0]; val d = argb[y1 * w + x1]
                var pixel = 0
                var shift = 0
                while (shift < 32) {
                    val sum = ((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) +
                        ((c ushr shift) and 0xFF) + ((d ushr shift) and 0xFF)
                    pixel = pixel or (((sum + 2) / 4) shl shift)
                    shift += 8
                }
                out[y * w2 + x] = pixel
            }
            return out
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("shader: $log")
            }
            return shader
        }

        private fun link(vertex: String, fragment: String): Int {
            val program = GLES30.glCreateProgram()
            val v = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            GLES30.glAttachShader(program, v)
            GLES30.glAttachShader(program, f)
            GLES30.glBindAttribLocation(program, 0, "aPos")
            GLES30.glBindAttribLocation(program, 1, "aUv")
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("program: $log")
            }
            return program
        }

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
uniform vec4 uUv; // scale in xy, offset in zw
out vec2 vUv;
void main() {
    vUv = aUv * uUv.xy + uUv.zw;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

        // The texture is premultiplied; the actor's colour is not, so its alpha scales everything.
        private const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform float uOpaque;
out vec4 fragColor;
void main() {
    vec4 t = texture(uTex, vUv);
    fragColor = uOpaque > 0.5 ? vec4(t.rgb * uColor.rgb, 1.0) : t * vec4(uColor.rgb * uColor.a, uColor.a);
}
"""

        /**
         * The scene's opening frame, drawn off screen at [width] x [height]: the picture shown
         * before the live scene is up, and wherever the background is shown as a still. Null when GL
         * cannot draw it. Blocking; any thread.
         */
        fun still(raf: P3tTheme.Bytes, scene: RafScene.Scene, width: Int, height: Int): Bitmap? {
            val egl = SceneEgl()
            return try {
                if (!egl.create(null, width, height)) return null
                val play = ThemeScene(scene)
                // Long enough for the script to have placed everything once.
                repeat(STILL_TICKS) { play.advance(ThemeScene.TICK) }
                val renderer = ThemeSceneRenderer(play, raf)
                try {
                    renderer.init()
                    renderer.draw(width, height)
                    val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                    GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return null
                    // GL's rows run bottom up.
                    val argb = IntArray(width * height)
                    for (y in 0 until height) {
                        val row = (height - 1 - y) * width * 4
                        for (x in 0 until width) {
                            val at = row + x * 4
                            argb[y * width + x] = (0xFF shl 24) or ((pixels.get(at).toInt() and 0xFF) shl 16) or
                                ((pixels.get(at + 1).toInt() and 0xFF) shl 8) or (pixels.get(at + 2).toInt() and 0xFF)
                        }
                    }
                    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
                } finally {
                    renderer.release()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "no still for the scene", t)
                null
            } finally {
                egl.destroy()
            }
        }

        private const val STILL_TICKS = 6
    }
}

/** An EGL context for GLES 3 with a depth buffer, on a window or, with no window, off screen. */
internal class SceneEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** [window] is a SurfaceTexture or Surface; null for an off-screen [width] x [height] buffer. */
    fun create(window: Any?, width: Int, height: Int): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val config = choose(if (window == null) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT) ?: return false
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = if (window == null) {
            EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        } else {
            EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        }
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    /** A config with 8-bit colour and alpha and the deepest of a 24- or 16-bit depth buffer. */
    private fun choose(surfaceType: Int): EGLConfig? {
        for (depth in intArrayOf(24, 16)) {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, depth,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) return configs[0]
        }
        return null
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun destroy() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40 // EGL_KHR_create_context; not in EGL14
    }
}

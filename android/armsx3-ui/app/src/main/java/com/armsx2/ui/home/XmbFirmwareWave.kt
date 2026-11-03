package com.armsx2.ui.home

import android.content.Context
import android.opengl.GLES30
import com.armsx2.ui.xmb.XmbWaveFirmware
import com.armsx2.ui.xmb.XmbWaveModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/**
 * XMB mode's wave on the GPU: XmbWaveModel's 19x19 control points tessellated into the console's
 * 128x128 grid and lit as its lines shader lights them (spurs/moyou/spline.elf, lib/moyou/lines1),
 * the light added up off screen and then laid over the background in the line colour, as
 * LinesController does. Lives on XmbGlView's render thread, in its GL context.
 *
 * With a [mood] (lines.qrc override/<name>, the music player's "music_1") it draws the whole
 * picture the way that mood does: its own sky (lib/moyou/back_colours1 under BACKGROUND colours
 * 1 and 2), the lines in colours 3 and 4, the HDR.mnu exposure, and the console's glare: the
 * bright part taken down to 128x64 and five smaller sizes, each blurred by a gaussian per colour
 * and added back up by GLARE SUM POW (the passes and their weights as an RSX capture of the
 * console's XMB showed them, glutils GlareSource, Gaussian, AccGlare and ToneApplyDisplay).
 */
internal class XmbFirmwareWave(context: Context, private val mood: XmbWaveFirmware.Mood? = null) {
    private val model = XmbWaveModel(loadState(context), mood?.line ?: XmbWaveFirmware.line)
    private val ctrl = FloatArray(XmbWaveModel.N * 4)
    private val ctrlBuf: FloatBuffer = ByteBuffer.allocateDirect(ctrl.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var lineProg = 0
    private var compProg = 0
    private var gridVao = 0
    private var quadVao = 0
    private var indexCount = 0
    private var ctrlTex = 0
    private var strandTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var floatTarget = false
    private var lastSec = -1f

    // ---- a mood's picture: the scene, then the glare at 128x64 and five smaller sizes ----
    private class Target(val w: Int, val h: Int, val fbo: Int, val tex: Int)
    private var sceneProg = 0
    private var sourceProg = 0
    private var copyProg = 0
    private var blurProg = 0
    private var outProg = 0
    private var scene: Target? = null
    private val levels = arrayOfNulls<Target>(GLARE_LEVELS)
    private val blurred = arrayOfNulls<Target>(GLARE_LEVELS)
    private var glare: Target? = null
    private val blurWeights = FloatArray(8 * 3)
    private val levelWeights = FloatArray(GLARE_LEVELS)

    /** How hard the music pushes the wave (the Waves visualizer); 0 for the XMB's own. */
    var drive: Float
        get() = model.drive
        set(value) { model.drive = value }

    /** Whether [draw] covers the whole screen itself (a mood brings its own sky). */
    val paintsBackground: Boolean get() = mood != null

    fun init() {
        lineProg = link(LINE_VS, LINE_FS)
        compProg = link(COMP_VS, COMP_FS)
        makeGrid()
        makeQuad()
        if (mood != null) {
            sceneProg = link(COMP_VS, SCENE_FS)
            sourceProg = link(COMP_VS, SOURCE_FS)
            copyProg = link(COMP_VS, COPY_FS)
            blurProg = link(COMP_VS, BLUR_FS)
            outProg = link(COMP_VS, OUT_FS)
            gaussian(mood.hdr.radius)
            // glutils 0x7b19c: pow(GLARE SUM POW, n) over all of them, times GLARE LEVEL; n = 0 the largest.
            val sum = (0 until GLARE_LEVELS).sumOf { Math.pow(mood.hdr.glareSumPow.toDouble(), it.toDouble()) }
            for (n in 0 until GLARE_LEVELS) {
                levelWeights[n] = (Math.pow(mood.hdr.glareSumPow.toDouble(), n.toDouble()) / sum * mood.hdr.glareLevel).toFloat()
            }
        }
        ctrlTex = texture(GLES30.GL_NEAREST)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, XmbWaveModel.R, XmbWaveModel.R, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
        strandTex = texture(GLES30.GL_LINEAR)
        val strands = XmbWaveFirmware.strands
        val sb = ByteBuffer.allocateDirect(strands.size).order(ByteOrder.nativeOrder())
        strands.forEach { sb.put((it.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()) }
        sb.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, strands.size, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, sb)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        floatTarget = "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
    }

    /**
     * Draw over the background already in the current framebuffer, [width] x [height], at [level]
     * (0..1, the boot animation bringing it up). [timeSec] is the render loop's clock.
     */
    fun draw(timeSec: Float, width: Int, height: Int, level: Float) {
        val dt = if (lastSec < 0f) 0f else (timeSec - lastSec)
        lastSec = timeSec
        model.update(dt, width.toFloat() / height.coerceAtLeast(1), ctrl)
        ctrlBuf.clear(); ctrlBuf.put(ctrl); ctrlBuf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ctrlTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, XmbWaveModel.R, XmbWaveModel.R, GLES30.GL_RGBA, GLES30.GL_FLOAT, ctrlBuf)

        ensureTarget(width, height)
        // The lines' light, added up (the console packs it into two 8-bit channels; a float target
        // keeps it without that).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, fboW, fboH)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(lineProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ctrlTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(lineProg, "uCtrl"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strandTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(lineProg, "uStrands"), 1)
        val line = model.line
        GLES30.glUniform1f(GLES30.glGetUniformLocation(lineProg, "uFresnel"), line.fresnel)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(lineProg, "uBrightness"), line.brightness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(lineProg, "uMipBias"), line.mipmapBias)
        GLES30.glEnable(GLES30.GL_PRIMITIVE_RESTART_FIXED_INDEX)
        GLES30.glBindVertexArray(gridVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_PRIMITIVE_RESTART_FIXED_INDEX)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (mood != null) {
            drawMood(mood, width, height, level)
            return
        }

        // Over the background in the line colour, across the screen from colour 3 to colour 4.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
        GLES30.glUseProgram(compProg)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compProg, "uLines"), 0)
        val c3 = XmbWaveFirmware.colour3
        val c4 = XmbWaveFirmware.colour4
        GLES30.glUniform3f(GLES30.glGetUniformLocation(compProg, "uCol3"), c3[0], c3[1], c3[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(compProg, "uCol4"), c4[0], c4[1], c4[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(compProg, "uGain"), GAIN * level)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** A mood's picture over the lines' light in [fbo]: the scene, its glare, and both on the screen. */
    private fun drawMood(mood: XmbWaveFirmware.Mood, width: Int, height: Int, level: Float) {
        val sc = target(scene, width, height).also { scene = it }
        val sum = target(glare, GLARE_W, GLARE_H).also { glare = it }
        for (n in 0 until GLARE_LEVELS) {
            levels[n] = target(levels[n], max(1, GLARE_W shr n), max(1, GLARE_H shr n))
            blurred[n] = target(blurred[n], max(1, GLARE_W shr n), max(1, GLARE_H shr n))
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)

        // The sky (back_colours1) under colours 1 at the bottom to 2 at the top, the lines in
        // colours 3 at the left to 4 at the right, and the exposure (glutils ToneMap).
        bind(sc)
        GLES30.glUseProgram(sceneProg)
        sample(0, fboTex, sceneProg, "uLines")
        val c = mood.colours
        for (n in 0 until 4) GLES30.glUniform3f(GLES30.glGetUniformLocation(sceneProg, "uCol${n + 1}"), c[n][0], c[n][1], c[n][2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProg, "uSky"), if (mood.colourShader == 1) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProg, "uDayCos"), dayCos())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProg, "uGain"), LINE_LIGHT * level)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProg, "uExposure"), mood.hdr.exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProg, "uWhiteSqrRcp"), 1f / (mood.hdr.white * mood.hdr.white))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // What is brighter than GLARE THRESH, at 128x64 (glutils GlareSourceMRT), then five
        // smaller sizes, each half the last.
        bind(levels[0]!!)
        GLES30.glUseProgram(sourceProg)
        sample(0, sc.tex, sourceProg, "uScene")
        GLES30.glUniform2f(GLES30.glGetUniformLocation(sourceProg, "uFoot"), 1f / GLARE_W, 1f / GLARE_H)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sourceProg, "uThreshold"), mood.hdr.glareThreshold)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glUseProgram(copyProg)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(copyProg, "uWeight"), 1f)
        for (n in 1 until GLARE_LEVELS) {
            bind(levels[n]!!)
            sample(0, levels[n - 1]!!.tex, copyProg, "uTex")
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Each size blurred across and then down, a gaussian of GAUSSIAN RAD texels for each colour.
        GLES30.glUseProgram(blurProg)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(blurProg, "uW"), 8, blurWeights, 0)
        val step = GLES30.glGetUniformLocation(blurProg, "uStep")
        for (n in 0 until GLARE_LEVELS) {
            val l = levels[n]!!
            val b = blurred[n]!!
            bind(b)
            sample(0, l.tex, blurProg, "uTex")
            GLES30.glUniform2f(step, 1f / l.w, 0f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            bind(l)
            sample(0, b.tex, blurProg, "uTex")
            GLES30.glUniform2f(step, 0f, 1f / l.h)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Added up at 128x64, the largest the most (glutils AccGlare).
        bind(sum)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(copyProg)
        val weight = GLES30.glGetUniformLocation(copyProg, "uWeight")
        for (n in GLARE_LEVELS - 1 downTo 0) {
            sample(0, levels[n]!!.tex, copyProg, "uTex")
            GLES30.glUniform1f(weight, levelWeights[n])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        // The scene and its glare, over the whole screen (glutils ToneApplyDisplay).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(outProg)
        sample(0, sc.tex, outProg, "uScene")
        sample(1, sum.tex, outProg, "uGlare")
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** glutils 0x71260: a gaussian of [radius] texels for each colour, floor(3 radius) - 1 texels to a side. */
    private fun gaussian(radius: FloatArray) {
        for (ch in 0 until 3) {
            val r = radius[ch].coerceAtLeast(0.01f)
            val side = (floor(3f * r).toInt() - 1).coerceIn(0, 7)
            val g = FloatArray(8) { k -> if (k <= side) exp(-(k * k) / (2f * r * r)) else 0f }
            val total = g[0] + 2f * (1..7).sumOf { g[it].toDouble() }.toFloat()
            for (k in 0 until 8) blurWeights[k * 3 + ch] = g[k] / total
        }
    }

    /** back_colours1's _DayTime: the hour of the day, times 100. */
    private fun dayCos(): Float {
        val now = System.currentTimeMillis()
        val local = now + TimeZone.getDefault().getOffset(now)
        val hours = Math.floorMod(local, 86_400_000L) / 3_600_000.0
        return cos(hours * 100.0).toFloat()
    }

    private fun target(old: Target?, w: Int, h: Int): Target {
        if (old != null && old.w == w && old.h == h) return old
        if (old != null) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(old.fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(old.tex), 0)
        }
        val tex = texture(GLES30.GL_LINEAR)
        if (floatTarget) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        return Target(w, h, f[0], tex)
    }

    private fun bind(t: Target) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glViewport(0, 0, t.w, t.h)
    }

    private fun sample(unit: Int, tex: Int, prog: Int, name: String) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit)
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (fbo != 0 && w == fboW && h == fboH) return
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        fboW = w.coerceAtLeast(1); fboH = h.coerceAtLeast(1)
        fboTex = texture(GLES30.GL_NEAREST)
        if (floatTarget) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, fboW, fboH, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, fboW, fboH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE && floatTarget) {
            // A float target the driver will not render to after all: 8 bits, as the console has.
            floatTarget = false
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            fboW = 0
            ensureTarget(w, h)
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun texture(filter: Int): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        return t[0]
    }

    /** The 128x128 samples as (row, column), drawn as strips along each line with restarts between. */
    private fun makeGrid() {
        val verts = FloatArray(GRID * GRID * 2)
        for (r in 0 until GRID) for (c in 0 until GRID) {
            verts[(r * GRID + c) * 2] = r.toFloat()
            verts[(r * GRID + c) * 2 + 1] = c.toFloat()
        }
        val idx = ShortArray((GRID - 1) * (GRID * 2 + 1))
        var n = 0
        for (r in 0 until GRID - 1) {
            for (c in 0 until GRID) {
                idx[n++] = (r * GRID + c).toShort()
                idx[n++] = ((r + 1) * GRID + c).toShort()
            }
            idx[n++] = 0xFFFF.toShort()
        }
        indexCount = n
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
        val vb = IntArray(1); GLES30.glGenBuffers(1, vb, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0])
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vbuf.put(verts).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vbuf, GLES30.GL_STATIC_DRAW)
        val loc = GLES30.glGetAttribLocation(lineProg, "aRC")
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 8, 0)
        val ib = IntArray(1); GLES30.glGenBuffers(1, ib, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0])
        val ibuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ibuf.put(idx).position(0)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ibuf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        gridVao = vao[0]
    }

    private fun makeQuad() {
        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
        val buf = IntArray(1); GLES30.glGenBuffers(1, buf, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
        val fb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(verts).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES30.GL_STATIC_DRAW)
        val loc = GLES30.glGetAttribLocation(compProg, "aPos")
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        quadVao = vao[0]
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("xmb wave shader: " + GLES30.glGetShaderInfoLog(s))
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
        if (ok[0] == 0) throw RuntimeException("xmb wave program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val GRID = 128

        /** The line light's strength over the background (the console's exposure and glare). */
        private const val GAIN = 1.6f

        /**
         * The same light before a mood's own exposure: GAIN is the XMB's look after its exposure,
         * which (HDR.mnu 1.05, white 0.899) brightens the light's range by about a tenth.
         */
        private const val LINE_LIGHT = GAIN / 1.1f

        /** The console's glare: from 128x64 down to 4x2. */
        private const val GLARE_W = 128
        private const val GLARE_H = 64
        private const val GLARE_LEVELS = 6

        private fun loadState(context: Context): FloatArray? = runCatching {
            context.assets.open("xmb/wave_state.bin").use { s ->
                val b = s.readBytes()
                val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }.getOrNull()

        // spline.elf's tessellation (8 samples a patch at t = 16j/127, normals from the two tangents)
        // and lines1.vpo.
        private const val LINE_VS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 aRC;
uniform sampler2D uCtrl;
uniform float uFresnel;
uniform float uBrightness;
uniform float uMipBias;
out vec2 vTc0;
out vec4 vTc1;
void basis(float t, out vec4 b, out vec4 d) {
  float t2 = t * t;
  float t3 = t2 * t;
  float m = 1.0 - t;
  b = vec4(m * m * m, 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
  d = vec4(-3.0 * m * m, 9.0 * t2 - 12.0 * t, -9.0 * t2 + 6.0 * t + 3.0, 3.0 * t2) / 6.0;
}
void main() {
  int r = int(aRC.x + 0.5);
  int c = int(aRC.y + 0.5);
  int pr = r / 8;
  int pc = c / 8;
  vec4 br; vec4 dr; vec4 bc; vec4 dc;
  basis(float(r - pr * 8) * 16.0 / 127.0, br, dr);
  basis(float(c - pc * 8) * 16.0 / 127.0, bc, dc);
  vec4 P = vec4(0.0);
  vec4 Pr = vec4(0.0);
  vec4 Pc = vec4(0.0);
  for (int a = 0; a < 4; a++) {
    vec4 rowP = vec4(0.0);
    vec4 rowD = vec4(0.0);
    for (int b = 0; b < 4; b++) {
      vec4 C = texelFetch(uCtrl, ivec2(pc + b, pr + a), 0);
      rowP += bc[b] * C;
      rowD += dc[b] * C;
    }
    P += br[a] * rowP;
    Pr += dr[a] * rowP;
    Pc += br[a] * rowD;
  }
  vec3 N = cross(Pr.xyz, Pc.xyz);
  float lp = max(length(P.xyz), 1e-6);
  vec3 v = P.xyz / lp;
  float w = abs(dot(v, N)) / lp * uMipBias;
  float vn = abs(dot(v, N / max(length(N), 1e-20)));
  float fr = min(1.0, 10.0 * min(aRC.x, 127.0 - aRC.x) / 127.0);
  float fc = min(1.0, 10.0 * min(aRC.y, 127.0 - aRC.y) / 127.0);
  vTc0 = vec2(aRC.x / 127.0, w);
  vTc1 = vec4(uFresnel * w, uBrightness * w, vn * w, fr * fc);
  gl_Position = P;
}"""

        // lines1.fpo: the band's own light (its stripe texture's column at THINNESS 1, rows of
        // 127/5/0/0 by how small the band is on screen) and the strands, where the band turns edge on.
        private const val LINE_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vTc0;
in vec4 vTc1;
uniform sampler2D uStrands;
out vec4 oColor;
void main() {
  float y = clamp(vTc0.y * 4.0 - 0.5, 0.0, 3.0);
  float s = (y < 1.0 ? mix(127.0, 5.0, y) : (y < 2.0 ? mix(5.0, 0.0, y - 1.0) : 0.0)) / 255.0;
  float strand = texture(uStrands, vec2(vTc1.z, 0.5)).r;
  float light = (vTc1.y * s + strand * vTc1.x) * vTc1.w;
  oColor = vec4(clamp(light, 0.0, 1.0), 0.0, 0.0, 0.0);
}"""

        private const val COMP_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}"""

        // LinesController: the light (which the console's two 8-bit channels hold up to about 0.53)
        // in the line colour, from colour 3 at the left to colour 4 at the right.
        private const val COMP_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uLines;
uniform vec3 uCol3;
uniform vec3 uCol4;
uniform float uGain;
out vec4 oColor;
void main() {
  float light = min(texture(uLines, vUv).r, 0.53);
  oColor = vec4(mix(uCol3, uCol4, vUv.x) * light * uGain, 0.0);
}"""

        // A mood's scene: lib/moyou/back_colours1 (its sky, brighter toward the top, a glow low on
        // the left that swells with _DayTime), times BACKGROUND colours 1 to 2 from the bottom up;
        // the lines in colours 3 to 4 (LinesController); then glutils ToneMap's exposure.
        private const val SCENE_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uLines;
uniform vec3 uCol1;
uniform vec3 uCol2;
uniform vec3 uCol3;
uniform vec3 uCol4;
uniform float uSky;
uniform float uDayCos;
uniform float uGain;
uniform float uExposure;
uniform float uWhiteSqrRcp;
out vec4 oColor;
vec3 sky(vec2 uv) {
  float d1 = dot(uv - 1.5, uv - 1.5);
  vec2 q = uv - vec2(0.4, 0.1);
  float e2 = q.x * q.x / 6.0 + q.y * q.y;
  float r = 0.4 * exp(0.4 * (uv.y - 1.0)) + 0.6 * exp(-16.0 * (uDayCos + 1.0) * e2) + 0.375 * exp(-d1);
  float g = 0.3 * exp(0.2 * (uv.y - 1.0)) + 0.3 * exp(-d1) + exp(-12.0 * e2);
  float b = 0.45 * exp(-e2) + 0.6 * exp(-d1) + exp(0.1 * (uv.y - 1.0));
  float s = clamp(2.5 * (uv.y - 0.2 * uv.x - 0.4), -10.0, 10.0);
  return vec3(r, g, b) * (0.4 + 0.4 * tanh(s * 0.5));
}
void main() {
  vec3 back = uSky > 0.5 ? sky(vUv) : vec3(1.0);
  vec3 c = back * mix(uCol1, uCol2, vUv.y);
  c += min(texture(uLines, vUv).r, 0.53) * uGain * mix(uCol3, uCol4, vUv.x);
  vec3 y = c * uExposure;
  oColor = vec4(y * (1.0 + y * uWhiteSqrRcp) / (1.0 + y), 1.0);
}"""

        // glutils GlareSourceMRT: what is past the threshold, here averaged over the 128x64 texel.
        private const val SOURCE_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uFoot;
uniform float uThreshold;
out vec4 oColor;
void main() {
  vec3 s = vec3(0.0);
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      vec2 o = (vec2(float(i), float(j)) - 1.5) * 0.25 * uFoot;
      s += max(texture(uScene, vUv + o).rgb - uThreshold, 0.0);
    }
  }
  oColor = vec4(s / 16.0, 0.0);
}"""

        // glutils GlareSource and AccGlare: a copy, at a weight.
        private const val COPY_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uWeight;
out vec4 oColor;
void main() {
  oColor = vec4(texture(uTex, vUv).rgb * uWeight, 0.0);
}"""

        // glutils Gaussian: the middle and seven texels to each side, a weight for each colour.
        private const val BLUR_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
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
}"""

        // glutils ToneApplyDisplay: the glare added to the scene.
        private const val OUT_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uGlare;
out vec4 oColor;
void main() {
  oColor = vec4(texture(uScene, vUv).rgb + texture(uGlare, vUv).rgb, 1.0);
}"""
    }
}

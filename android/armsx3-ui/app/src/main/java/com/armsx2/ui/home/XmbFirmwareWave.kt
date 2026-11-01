package com.armsx2.ui.home

import android.content.Context
import android.opengl.GLES30
import com.armsx2.ui.xmb.XmbWaveFirmware
import com.armsx2.ui.xmb.XmbWaveModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * XMB mode's wave on the GPU: XmbWaveModel's 19x19 control points tessellated into the console's
 * 128x128 grid and lit as its lines shader lights them (spurs/moyou/spline.elf, lib/moyou/lines1),
 * the light added up off screen and then laid over the background in the line colour, as
 * LinesController does. Lives on XmbGlView's render thread, in its GL context.
 */
internal class XmbFirmwareWave(context: Context) {
    private val model = XmbWaveModel(loadState(context))
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

    fun init() {
        lineProg = link(LINE_VS, LINE_FS)
        compProg = link(COMP_VS, COMP_FS)
        makeGrid()
        makeQuad()
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
in vec2 aPos;
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
    }
}

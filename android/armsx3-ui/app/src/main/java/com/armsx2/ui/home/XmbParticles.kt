package com.armsx2.ui.home

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * XMB mode's sparkles as the console makes them, from its firmware (lines.qrc PARTICLES.mnu, and
 * the particle shaders lib/particles/particles_quads and particles_second) and from an RSX capture
 * of the real XMB, which drew 2,040 of them in two passes.
 *
 * Each sparkle is a tiny flake, a square in space turned by its own spinning quaternion. Flakes are
 * born on the wave's surface, drift off it slowly (friction, a little jitter and gravity) and live a
 * few seconds, so they keep to the wave the way the console's do: in the capture, three quarters
 * sat within the band. A flake is a soft disc lit by a spotlight off to the right: a dim white glow,
 * and a sharp highlight coloured off the console's iridescent swirl (particles/proc_iridescent.tga)
 * that flashes as the flake turns to catch the light. That flash is the glitter. A second pass adds
 * the flash's glare, a soft glow 5.44 times the flake's size. Near and far flakes grow and soften
 * with the console's depth of field; flakes seen edge on, at the end of their life or near the edge
 * of their box fade out.
 *
 * World space is the console's particle camera: the eye at (0, 0, 2) looking down -z, clip x and y
 * the world's times [sx] and [sy], clip w = 2 - z. The wave's control points are taken back into it
 * from their clip coordinates, so a flake born on them lies on the wave on screen.
 *
 * Lives on XmbGlView's render thread, in its GL context.
 */
internal class XmbParticles {
    private val px = FloatArray(MAX); private val py = FloatArray(MAX); private val pz = FloatArray(MAX)
    private val vx = FloatArray(MAX); private val vy = FloatArray(MAX); private val vz = FloatArray(MAX)
    private val qx = FloatArray(MAX); private val qy = FloatArray(MAX); private val qz = FloatArray(MAX); private val qw = FloatArray(MAX)
    private val ax = FloatArray(MAX); private val ay = FloatArray(MAX); private val az = FloatArray(MAX)
    private val age = FloatArray(MAX); private val ageRate = FloatArray(MAX)
    private var count = 0
    private var emitCarry = 0f
    private var stepCarry = 0f
    private var warmed = false
    private var seed = 0x2545F491L

    // The wave's 19x19 control points in world space, and scratch for the B-spline basis.
    private val net = FloatArray(NET * NET * 3)
    private var haveNet = false
    private val bu = FloatArray(4); private val dbu = FloatArray(4)
    private val bv = FloatArray(4); private val dbv = FloatArray(4)
    private val surface = FloatArray(6)

    private var prog = 0
    private var vao = 0
    private var instanceBuf = 0
    private val instances: FloatBuffer = ByteBuffer.allocateDirect(MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun init() {
        prog = XmbGl.link(VS, FS)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(2, ids, 0)
        val cornerBuf = ids[0]; instanceBuf = ids[1]
        GLES30.glBindVertexArray(vao)
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerBuf)
        val cb = ByteBuffer.allocateDirect(corners.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(corners).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, cb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX * 8 * 4, null, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 32, 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Take the wave's control points ([ctrl], 19x19 clip-space x, y, z, w as XmbWaveModel writes
     * them) back into world space.
     */
    fun setWave(ctrl: FloatArray, sx: Float, sy: Float) {
        for (i in 0 until NET * NET) {
            net[i * 3] = ctrl[i * 4] / sx
            net[i * 3 + 1] = ctrl[i * 4 + 1] / sy
            net[i * 3 + 2] = 2f - ctrl[i * 4 + 3]
        }
        haveNet = true
    }

    /**
     * Move every flake on by [dtSec], in the console's 60 steps a second. [windX] and [windY] (world
     * units a second) carry the whole field, as moving the controller or the XMB's cursor does;
     * [scatter] (0..1) jolts every flake, as a hard shake does.
     */
    fun step(dtSec: Float, windX: Float, windY: Float, scatter: Float) {
        if (!haveNet) return
        if (!warmed) {
            // Start full, as the console's field has long been running by the time it is seen.
            warmed = true
            repeat(WARM_STEPS) { stepOnce(0f, 0f, 0f) }
        }
        stepCarry = minOf(stepCarry + dtSec * STEPS_PER_SECOND, MAX_STEPS_PER_FRAME.toFloat())
        while (stepCarry >= 1f) {
            stepCarry -= 1f
            stepOnce(windX / STEPS_PER_SECOND, windY / STEPS_PER_SECOND, scatter)
        }
    }

    private fun stepOnce(windX: Float, windY: Float, scatter: Float) {
        // Births: PARTICLES.mnu emits 16.65 a frame with probability 0.479.
        emitCarry += EMIT_PER_FRAME * EMIT_PROB
        while (emitCarry >= 1f) {
            emitCarry -= 1f
            if (count < MAX) spawn(count++)
        }
        val keep = 1f - FRICTION
        val kick = BROWNIAN + scatter * SCATTER_KICK
        var i = 0
        while (i < count) {
            age[i] += ageRate[i]
            if (age[i] >= 1f) {
                count--
                copy(count, i)
                continue
            }
            vx[i] = (vx[i] + kick * (rand() - 0.5f)) * keep
            vy[i] = (vy[i] + kick * (rand() - 0.5f) + GRAVITY) * keep
            vz[i] = (vz[i] + kick * (rand() - 0.5f)) * keep
            px[i] += vx[i] * DELTA_TIME + windX
            py[i] += vy[i] * DELTA_TIME + windY
            pz[i] += vz[i] * DELTA_TIME
            spin(i)
            i++
        }
    }

    /** A flake on a random point of the wave, off it within the emission cone. */
    private fun spawn(i: Int) {
        val u = rand() * (NET - 3)
        val v = rand() * (NET - 3)
        evalSurface(u, v)
        px[i] = surface[0]; py[i] = surface[1]; pz[i] = surface[2]
        // Within the cone about the surface's normal, now and then the other way.
        var nx = surface[3]; var ny = surface[4]; var nz = surface[5]
        if (rand() < EMIT_NEG_PROB) { nx = -nx; ny = -ny; nz = -nz }
        val spread = sin(EMIT_CONE_HALF)
        nx += (rand() * 2f - 1f) * spread
        ny += (rand() * 2f - 1f) * spread
        nz += (rand() * 2f - 1f) * spread
        nz *= EMIT_VEL_ZSCALE
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        val speed = (EMIT_VEL_MIN + EMIT_VEL_MUL * rand()) * (1f + EMIT_VEL_VAR * (rand() * 2f - 1f))
        vx[i] = nx / len * speed; vy[i] = ny / len * speed; vz[i] = nz / len * speed
        // A random attitude, and a spin about a random axis.
        var a = rand() * 2f - 1f; var b = rand() * 2f - 1f; var c = rand() * 2f - 1f; var d = rand() * 2f - 1f
        var n = sqrt(a * a + b * b + c * c + d * d).coerceAtLeast(1e-6f)
        qx[i] = a / n; qy[i] = b / n; qz[i] = c / n; qw[i] = d / n
        a = rand() * 2f - 1f; b = rand() * 2f - 1f; c = rand() * 2f - 1f
        n = sqrt(a * a + b * b + c * c).coerceAtLeast(1e-6f)
        val rate = SPIN_TIME_SCALE * (0.3f + 0.7f * rand()) / STEPS_PER_SECOND
        ax[i] = a / n * rate; ay[i] = b / n * rate; az[i] = c / n * rate
        age[i] = 0f
        ageRate[i] = AGING_SPEED * (1f + AGING_VARIANCE * (rand() * 2f - 1f))
    }

    /** Turn flake [i] by its spin for one step (q += 0.5 * w * q, renormalised). */
    private fun spin(i: Int) {
        val x = qx[i]; val y = qy[i]; val z = qz[i]; val w = qw[i]
        val hx = ax[i] * 0.5f; val hy = ay[i] * 0.5f; val hz = az[i] * 0.5f
        val nx = x + (hx * w + hy * z - hz * y)
        val ny = y + (hy * w + hz * x - hx * z)
        val nz = z + (hz * w + hx * y - hy * x)
        val nw = w - (hx * x + hy * y + hz * z)
        val n = 1f / sqrt(nx * nx + ny * ny + nz * nz + nw * nw)
        qx[i] = nx * n; qy[i] = ny * n; qz[i] = nz * n; qw[i] = nw * n
    }

    private fun copy(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        qx[to] = qx[from]; qy[to] = qy[from]; qz[to] = qz[from]; qw[to] = qw[from]
        ax[to] = ax[from]; ay[to] = ay[from]; az[to] = az[from]
        age[to] = age[from]; ageRate[to] = ageRate[from]
    }

    /**
     * The wave's surface at (u, v), each 0..16 across its 16 cubic B-spline patches (as spline.elf
     * tessellates it): the point and its unit normal into [surface].
     */
    private fun evalSurface(u: Float, v: Float) {
        val iu = u.toInt().coerceIn(0, NET - 4); val iv = v.toInt().coerceIn(0, NET - 4)
        basis(u - iu, bu, dbu); basis(v - iv, bv, dbv)
        var x = 0f; var y = 0f; var z = 0f
        var ux = 0f; var uy = 0f; var uz = 0f
        var wx = 0f; var wy = 0f; var wz = 0f
        for (a in 0 until 4) for (b in 0 until 4) {
            val o = ((iu + a) * NET + (iv + b)) * 3
            val cx = net[o]; val cy = net[o + 1]; val cz = net[o + 2]
            val k = bu[a] * bv[b]; val ku = dbu[a] * bv[b]; val kv = bu[a] * dbv[b]
            x += k * cx; y += k * cy; z += k * cz
            ux += ku * cx; uy += ku * cy; uz += ku * cz
            wx += kv * cx; wy += kv * cy; wz += kv * cz
        }
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        if (n > 1e-9f) { nx /= n; ny /= n; nz /= n } else { nx = 0f; ny = 1f; nz = 0f }
        surface[0] = x; surface[1] = y; surface[2] = z
        surface[3] = nx; surface[4] = ny; surface[5] = nz
    }

    private fun basis(t: Float, b: FloatArray, d: FloatArray) {
        val t2 = t * t; val t3 = t2 * t; val m = 1f - t
        b[0] = m * m * m / 6f
        b[1] = (3f * t3 - 6f * t2 + 4f) / 6f
        b[2] = (-3f * t3 + 3f * t2 + 3f * t + 1f) / 6f
        b[3] = t3 / 6f
        d[0] = -m * m / 2f
        d[1] = (3f * t2 - 4f * t) / 2f
        d[2] = (-3f * t2 + 2f * t + 1f) / 2f
        d[3] = t2 / 2f
    }

    /** xorshift: fast, and plenty for sparkle jitter. 0..1. */
    private fun rand(): Float {
        var s = seed
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        seed = s
        return ((s ushr 40).toFloat()) / (1L shl 24).toFloat()
    }

    /**
     * Draw over what is already in the framebuffer, additively as the console does, at [level]
     * (0..1, the boot animation bringing the wave up). [irid] is the iridescent swirl (white until
     * the firmware's has been read), [tint] how much of it shows.
     */
    fun draw(sx: Float, sy: Float, level: Float, irid: Int, tint: Float) {
        if (count == 0 || level <= 0f) return
        instances.clear()
        for (i in 0 until count) {
            val a = age[i]
            // Up over the first 3% of a life and down over the last 12%.
            val life = minOf(1f, a / 0.03f) * minOf(1f, (1f - a) / 0.12f)
            instances.put(px[i]).put(py[i]).put(pz[i]).put(life)
            instances.put(qx[i]).put(qy[i]).put(qz[i]).put(qw[i])
        }
        instances.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX * 8 * 4, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 8 * 4, instances)

        GLES30.glEnable(GLES30.GL_BLEND)
        // Added on, as the console blends them (ONE, ONE); the surface keeps its alpha.
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ZERO, GLES30.GL_ONE)
        GLES30.glUseProgram(prog)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uScale"), sx, sy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLevel"), level)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uTint"), tint)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, irid)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uIrid"), 0)
        GLES30.glBindVertexArray(vao)
        val glare = GLES30.glGetUniformLocation(prog, "uGlare")
        // The flakes, then their glare (particles_quads, then particles_second).
        GLES30.glUniform1f(glare, 0f)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glUniform1f(glare, 1f)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        /** The console's field: 2,040 in one capture, 2,027 in another. */
        const val MAX = 2048
        private const val NET = 19

        // PARTICLES.mnu, per 60 Hz step.
        private const val STEPS_PER_SECOND = 60f
        private const val MAX_STEPS_PER_FRAME = 6
        private const val WARM_STEPS = 420
        private const val EMIT_PER_FRAME = 16.6539f
        private const val EMIT_PROB = 0.479115f
        private const val EMIT_VEL_MIN = 0.15064f
        private const val EMIT_VEL_MUL = 0.19f
        private const val EMIT_VEL_VAR = 0.282567f
        private const val EMIT_CONE_HALF = 51.8695f * 0.5f * (Math.PI.toFloat() / 180f)
        private const val EMIT_NEG_PROB = 0.173899f
        private const val EMIT_VEL_ZSCALE = 0f
        private const val AGING_SPEED = 0.00285223f
        private const val AGING_VARIANCE = 0.493003f
        private const val FRICTION = 0.030551f
        private const val DELTA_TIME = 0.0088883f
        // PARTICLES.mnu's gravity is -6.8e-5. Thirty times that in these steps gives the field the
        // console's lean below the wave: run on the console's own wave, this simulation matched the
        // RSX capture's spread (73% within half the band's height of its middle against 74%, the
        // middle 0.20 of a half-height below it against 0.26).
        private const val GRAVITY = -6.8e-5f * 30f
        private const val BROWNIAN = 0.225311f
        private const val SPIN_TIME_SCALE = 2.74f

        /** A hard shake's jolt on every flake, on top of the jitter (PARTICLES_UI.mnu "dshake brw imp" in spirit). */
        private const val SCATTER_KICK = 1.5f

        // particles_quads / particles_second, with the constants the RSX capture of the console's
        // XMB showed them drawn with (PARTICLES.mnu's own values, as the shaders take them).
        private const val VS = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec4 aPos;
layout(location = 2) in vec4 aRot;
uniform vec2 uScale;
uniform float uGlare;
out vec2 vUv;
out vec2 vBlur;
out float vAlpha;
out vec3 vNormal;
out vec3 vPos;
const vec3 CAM = vec3(0.0, 0.0, 2.0);
const vec3 LIFE_MIN = vec3(-10.0, -10.0, -12.0);
const vec3 LIFE_MAX = vec3(10.0, 10.0, 7.0);
const vec4 FOCUS = vec4(6.12435, 7.44365, 12.2008, 16.228149);
const vec3 FOCUS_CURVES = vec3(2.47206, 1.83324, 0.925025);
const vec3 SIZE = vec3(0.046477, 0.077203, 0.874062);
const vec3 NEAR = vec3(13.19, 3.79, 0.722);
const vec2 DARK = vec2(6.24028, 13.9);
const vec2 TRANSPARENCY = vec2(1.33319, 1.0);
const float GLARE_SIZE = 5.44386;
mat3 quatMat(vec4 q) {
  return mat3(
    1.0 - 2.0 * (q.y * q.y + q.z * q.z), 2.0 * (q.x * q.y + q.w * q.z), 2.0 * (q.x * q.z - q.w * q.y),
    2.0 * (q.x * q.y - q.w * q.z), 1.0 - 2.0 * (q.x * q.x + q.z * q.z), 2.0 * (q.y * q.z + q.w * q.x),
    2.0 * (q.x * q.z + q.w * q.y), 2.0 * (q.y * q.z - q.w * q.x), 1.0 - 2.0 * (q.x * q.x + q.y * q.y));
}
void main() {
  vec3 pos = aPos.xyz;
  // Fading out within 0.2 of the edge of its box.
  vec3 edge = min(pos - LIFE_MIN, LIFE_MAX - pos);
  float f = clamp(min(edge.x, min(edge.y, edge.z)) * 5.0, 0.0, 1.0);
  float boxFade = f * f * (3.0 - 2.0 * f);
  vec4 q = normalize(aRot);
  vec3 toCam = CAM - pos;
  float dist = length(toCam);
  vec3 V = toCam / dist;
  // Depth of field: nearer than 7.44 grows towards the near size, beyond 12.2 towards the far one.
  float nearT = (clamp(dist, FOCUS.x, FOCUS.y) - FOCUS.y) / (FOCUS.x - FOCUS.y);
  float farT = (clamp(dist, FOCUS.z, FOCUS.w) - FOCUS.z) / (FOCUS.w - FOCUS.z);
  float nearP = pow(nearT, FOCUS_CURVES.x);
  float farP = pow(farT, FOCUS_CURVES.y);
  float size = SIZE.x + nearP * (SIZE.y - SIZE.x);
  size += farP * (SIZE.z - size);
  float rampA = (clamp(dist, FOCUS.z - 0.2, FOCUS.z) - (FOCUS.z - 0.2)) * 5.0;
  float rampB = (clamp(dist, FOCUS.z - 0.6, FOCUS.z - 0.4) - (FOCUS.z - 0.6)) * 5.0;
  float rampC = (clamp(dist, FOCUS.z - 0.4, FOCUS.z - 0.2) - (FOCUS.z - 0.4)) * 5.0;
  float midFade = 1.0 + rampA - rampB;
  float ang = 2.0 * atan(size / dist) / FOCUS_CURVES.z;
  float farDark = clamp(farT * ang * DARK.y, 0.0, 1.0);
  float nearDark = clamp(nearT * ang * DARK.x, 0.0, 1.0);
  // Near and large flakes turn to face the camera; the glare always does, 5.44 times the size.
  float face = clamp(ang * NEAR.x + nearP * NEAR.z + rampC, 0.0, 1.0);
  vec4 qf = uGlare > 0.5 ? vec4(0.0, 0.0, 0.0, 1.0) : normalize(mix(q, vec4(0.0, 0.0, 0.0, 1.0), face));
  float s = uGlare > 0.5 ? size * GLARE_SIZE : size;
  mat3 R = quatMat(qf);
  vec2 c = aCorner - 0.5;
  vec3 world = pos + (R[0] * c.x + R[1] * c.y) * s;
  float w = 2.0 - world.z;
  gl_Position = vec4(uScale.x * world.x, uScale.y * world.y, 1.0002 * w - 0.2, w);
  // Seen edge on, a flake all but vanishes.
  float facing = 1.0 - pow(max(1.0 - abs(dot(V, R[2])), 1e-6), TRANSPARENCY.x);
  vAlpha = facing * TRANSPARENCY.y * aPos.w * midFade * (1.0 - farDark) * (1.0 - nearDark) * boxFade;
  vUv = aCorner;
  vBlur = vec2(nearP, farP);
  // Lit by its own turning, however much it faces the camera.
  vNormal = quatMat(q)[2];
  vPos = world;
}"""

        private const val FS = """#version 300 es
precision highp float;
in vec2 vUv;
in vec2 vBlur;
in float vAlpha;
in vec3 vNormal;
in vec3 vPos;
uniform sampler2D uIrid;
uniform float uTint;
uniform float uLevel;
uniform float uGlare;
out vec4 oColor;
const vec3 CAM = vec3(0.0, 0.0, 2.0);
const vec3 LIGHT = vec3(4.16, 2.63, -7.6);
const float LAMBERT = 8.0;
const float SPECULAR = 74.74;
const float SPECULAR_POWER = 35.2904;
const float EXPOSURE = 0.0390458;
const float NEAR_FUZZINESS = 3.79;
const vec2 GLARE = vec2(0.201367, 4.44);
void main() {
  vec3 N = normalize(vNormal);
  // The swirl looked up by the flake's normal as the camera sees it.
  vec3 irid = mix(vec3(1.0), texture(uIrid, N.xy * 0.5 + 0.5).rgb, uTint);
  vec3 V = normalize(CAM - vPos);
  vec3 L = normalize(LIGHT - vPos);
  vec3 H = normalize(V + L);
  float spec = pow(abs(dot(N, H)), SPECULAR_POWER);
  float r = length(vUv - 0.5);
  vec3 col;
  if (uGlare < 0.5) {
    // A disc, its edge softer the more out of focus it is.
    float blur = clamp(vBlur.x + vBlur.y, 0.0, 1.0);
    float e = blur * 0.6345 + 0.05;
    float t = clamp((r - (0.5 - e)) / (2.0 * e), 0.0, 1.0);
    float disc = 1.0 - t * t * (3.0 - 2.0 * t);
    float fuzz = vBlur.x * (NEAR_FUZZINESS - 0.85) + 0.85;
    float profile = disc + blur * (1.0 - disc - exp(-disc * fuzz));
    vec3 light = abs(dot(N, L)) * LAMBERT + spec * SPECULAR * irid;
    col = (1.0 - exp(-light * EXPOSURE)) * profile * vAlpha;
  } else {
    // The flash's glare: only the highlight, in a glow from the middle.
    float glow = exp(-pow(clamp(r, 0.0, 1.0), 0.99) * GLARE.y);
    col = (1.0 - exp(-spec * SPECULAR * irid * EXPOSURE)) * vAlpha * glow * GLARE.x;
  }
  oColor = vec4(col * uLevel, 0.0);
}"""
    }
}

/** Shader helpers for XMB mode's GL pieces. */
internal object XmbGl {
    fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("xmb program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("xmb shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
}

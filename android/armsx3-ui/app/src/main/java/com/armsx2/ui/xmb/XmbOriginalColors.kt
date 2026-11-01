package com.armsx2.ui.xmb

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The PS3's "Original" XMB background: a gradient that changes with the month and dims from day to
 * night, the way the console does it under Theme Settings > Color > Original.
 *
 * The twelve day and twelve night gradients are linkev's measurements of the console
 * (github.com/linkev/PlayStation-3-XMB, MIT, ps3xmbwave/background-gradients-day.js and
 * background-gradients-night.js), the same project the wave itself is ported from (see XmbGlView).
 * January is the silver one and December the red one. Each is a CSS-style linear gradient: an
 * angle (0 points up, 90 to the right) from [Gradient.start] to [Gradient.end].
 *
 * Day and night are blended by the local time: full day from 08:00 to 17:00, full night from 20:00
 * to 05:00, eased in between. The month changes on the 1st, as it does on the console.
 */
object XmbOriginalColors {
    /** Linear 0..1 RGB stops, the angle in degrees. */
    class Gradient(val angleDeg: Float, val start: FloatArray, val end: FloatArray)

    private fun g(angle: Float, s: Int, e: Int) = Gradient(angle, rgb(s), rgb(e))

    private fun rgb(c: Int) = floatArrayOf(
        ((c shr 16) and 0xFF) / 255f,
        ((c shr 8) and 0xFF) / 255f,
        (c and 0xFF) / 255f,
    )

    private val DAY = arrayOf(
        g(90.25f, 0xC5C5C5, 0xC9C9C9),
        g(67f, 0xCB9E0D, 0xDBD629),
        g(106f, 0x8EBE28, 0x68A816),
        g(136.75f, 0xD8B6B6, 0xE74275),
        g(1.5f, 0x136C13, 0x189C18),
        g(148.75f, 0xC678EE, 0x674DA1),
        g(26.5f, 0x00A792, 0x0AF0EF),
        g(62.5f, 0x00005F, 0x21D9FF),
        g(148.5f, 0x922C9B, 0xD962EC),
        g(128.5f, 0xE3970F, 0xE0BB02),
        g(90f, 0x734414, 0x9A762F),
        g(170.5f, 0xEC442D, 0xD63F2B),
    )

    private val NIGHT = arrayOf(
        g(89.75f, 0xB5B5B5, 0x000000),
        g(93.75f, 0xC6BC80, 0x000000),
        g(90.25f, 0x98AA71, 0x000000),
        g(90.25f, 0xD4AEB6, 0x0A0808),
        g(116f, 0x307630, 0x0B030B),
        g(91f, 0xD1A3E1, 0x000000),
        g(109.75f, 0x10817C, 0x110000),
        g(69.5f, 0x149FB0, 0x00001F),
        g(51f, 0x740099, 0x0C000B),
        g(89.75f, 0xD88E00, 0x000000),
        g(90f, 0x835620, 0x121411),
        g(118.25f, 0x9D3B2C, 0x000003),
    )

    /** How much of the day gradient shows at [hour] (0..24, fractional): 1 by day, 0 at night. */
    fun daylight(hour: Float): Float {
        fun ease(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        return when {
            hour < 5f -> 0f
            hour < 8f -> ease((hour - 5f) / 3f)
            hour < 17f -> 1f
            hour < 20f -> 1f - ease((hour - 17f) / 3f)
            else -> 0f
        }
    }

    /** The gradient for [month] (0 = January) at [hour]. */
    fun at(month: Int, hour: Float): Gradient {
        val day = DAY[month.coerceIn(0, 11)]
        val night = NIGHT[month.coerceIn(0, 11)]
        val d = daylight(hour)
        fun mix(a: FloatArray, b: FloatArray) = FloatArray(3) { a[it] + (b[it] - a[it]) * d }
        return Gradient(night.angleDeg + (day.angleDeg - night.angleDeg) * d, mix(night.start, day.start), mix(night.end, day.end))
    }

    // Read by the GL thread every frame, so the calendar is consulted at most once a minute.
    @Volatile private var cached: Gradient? = null
    @Volatile private var cachedAt = 0L

    /** The gradient for the current local month and time. */
    fun now(): Gradient {
        val nowMs = System.currentTimeMillis()
        cached?.let { if (nowMs - cachedAt < 60_000L) return it }
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        return at(cal.get(Calendar.MONTH), hour).also {
            cached = it
            cachedAt = nowMs
        }
    }

    /**
     * The gradient's direction for a [width] x [height] surface, as XmbGlView's background shader
     * takes it: t = dot(uv, dir) with uv in 0..1 and y pointing down, then u = (t - tMin) / 1.
     * A CSS gradient spans the projection of the whole box onto its direction, so the two corners
     * along it land on 0 and 1.
     */
    fun shaderDirection(angleDeg: Float, width: Int, height: Int): FloatArray {
        val a = Math.toRadians(angleDeg.toDouble())
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val dx = w * sin(a)
        val dy = -h * cos(a)
        val length = (abs(w * sin(a)) + abs(h * cos(a))).coerceAtLeast(1e-6)
        val ux = (dx / length).toFloat()
        val uy = (dy / length).toFloat()
        // dot((0.5, 0.5), dir) is the centre, which must land on 0.5.
        val tMin = 0.5f * (ux + uy) - 0.5f
        return floatArrayOf(ux, uy, tMin)
    }
}

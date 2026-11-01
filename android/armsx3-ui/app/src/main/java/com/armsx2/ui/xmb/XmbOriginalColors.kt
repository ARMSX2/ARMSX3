package com.armsx2.ui.xmb

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The PS3's "Original" XMB background: a gradient that changes with the month and dims in the
 * small hours, the way the console does it under Theme Settings > Color > Original.
 *
 * The twelve month gradients are linkev's measurements of the console's daytime colours
 * (github.com/linkev/PlayStation-3-XMB, MIT, ps3xmbwave/background-gradients-day.js), the same
 * project the wave itself is ported from (see XmbGlView). January is the silver one and December
 * the red one. Each is a CSS-style linear gradient: an angle (0 points up, 90 to the right) from
 * [Gradient.start] to [Gradient.end].
 *
 * The time of day darkens the same colour rather than swapping it for another: a recording of a
 * real console shows October still in full colour at 23:08, and the console in the small hours is
 * near black at the top and a dark brown at the foot. So full until 23:00, down to the night's
 * level by midnight (the top much darker than the foot, as the console's is), and back up from
 * 04:00 to full by 06:30. The month changes on the 1st, as it does on the console.
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
        // October from a capture of a real console's XMB (olive gold, measured at 23:08 and
        // brought up to its daytime level), where linkev's measurement was a much brighter,
        // orange yellow that left the console's see-through white icons hard to make out.
        g(160f, 0xADB327, 0xD0BF3C),
        g(90f, 0x734414, 0x9A762F),
        g(170.5f, 0xEC442D, 0xD63F2B),
    )

    /** [month]'s colour in full day, for a swatch: the middle of its gradient. */
    fun swatch(month: Int): FloatArray {
        val day = DAY[month.coerceIn(0, 11)]
        return FloatArray(3) { (day.start[it] + day.end[it]) / 2f }
    }

    /** How bright the gradient's start (the top) and its end (the foot) are in the small hours. */
    private const val NIGHT_START = 0.10f
    private const val NIGHT_END = 0.30f

    /** How far into the night it is at [hour] (0..24, fractional): 0 by day, 1 in the small hours. */
    fun night(hour: Float): Float {
        fun ease(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        return when {
            hour < 4f -> 1f
            hour < 6.5f -> 1f - ease((hour - 4f) / 2.5f)
            hour < 23f -> 0f
            else -> ease(hour - 23f)
        }
    }

    /** The gradient for [month] (0 = January) at [hour]. */
    fun at(month: Int, hour: Float): Gradient {
        val day = DAY[month.coerceIn(0, 11)]
        val n = night(hour)
        val s = 1f + (NIGHT_START - 1f) * n
        val e = 1f + (NIGHT_END - 1f) * n
        return Gradient(day.angleDeg, FloatArray(3) { day.start[it] * s }, FloatArray(3) { day.end[it] * e })
    }

    /**
     * The boot animation's hold on the background, read by the GL thread every frame: how much of
     * the colour shows (it rises from black) and how much of the wave (it comes first, on black).
     * 1 and 1 outside the boot.
     */
    @Volatile var bootColor = 1f
    @Volatile var bootWave = 1f

    // Read by the GL thread every frame, so the calendar is consulted at most once a minute.
    @Volatile private var cached: Gradient? = null
    @Volatile private var cachedAt = 0L

    /** Drop the cached gradient, for a change of Theme Settings > Color to show at once. */
    fun invalidate() {
        cachedAt = 0L
    }

    /**
     * The gradient for the current local month and time; in XMB mode, the month Theme Settings
     * keeps instead, when it keeps one.
     */
    fun now(): Gradient {
        val nowMs = System.currentTimeMillis()
        val pinned = XmbMode.pinnedMonth
        val cycling = pinned == XmbMode.CYCLE && com.armsx2.ui.theme.LibraryBackgroundColorPreferences.xmbModeGl
        if (cycling) return cycle(nowMs)
        cached?.let { if (nowMs - cachedAt < 60_000L) return it }
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val month = if (pinned in 0..11 && com.armsx2.ui.theme.LibraryBackgroundColorPreferences.xmbModeGl) pinned else cal.get(Calendar.MONTH)
        return at(month, hour).also {
            cached = it
            cachedAt = nowMs
        }
    }

    /** How long Color's Cycle stays on each month before easing into the next. */
    private const val CYCLE_MONTH_MS = 20_000L

    /** Theme Settings > Color > Cycle: each month's colour in turn, easing from one to the next. */
    private fun cycle(nowMs: Long): Gradient {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val t = (nowMs % (12 * CYCLE_MONTH_MS)).toFloat() / CYCLE_MONTH_MS
        val month = t.toInt() % 12
        // Most of each month held, the last quarter easing into the next.
        val x = ((t - month - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val f = x * x * (3f - 2f * x)
        val a = at(month, hour)
        val b = at((month + 1) % 12, hour)
        return Gradient(
            a.angleDeg + (b.angleDeg - a.angleDeg) * f,
            FloatArray(3) { a.start[it] + (b.start[it] - a.start[it]) * f },
            FloatArray(3) { a.end[it] + (b.end[it] - a.end[it]) * f },
        )
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

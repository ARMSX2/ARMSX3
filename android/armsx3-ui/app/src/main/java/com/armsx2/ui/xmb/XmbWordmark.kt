package com.armsx2.ui.xmb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The ARMSX3 boot logo, laid out as the console's: the word ARMSX3 where "PS3" stood. (The mark
 * that once stood before it, a 3, only said again what the word ends with.)
 *
 * The letters follow RPCS3's wordmark, itself drawn after the PS3 logo: one stroke weight throughout
 * (0.21 of the letter height), letters half again as wide as they are high and set well apart,
 * large rounded outer corners with the inner corners inside them drawn round to match, square
 * ends, and the PS3 logo's S, a single stroke with a bar out to the right at its head and to the
 * left at its foot. A, M and X are built on the same rules. The mark is the ARMSX3 logo's "3",
 * heavier, as the symbol is heavier than the word.
 *
 * Everything is in letter heights, drawn white over a light grey at the foot with a short hard
 * shadow down and to the right, as RPCS3's is.
 */
object XmbWordmark {
    /**
     * [unitsWide] x [unitsHigh] letter heights; the word's letters run from [wordTop] to
     * [wordTop] + 1 of them, and its middle is [wordCentreX] across.
     */
    class Layers(
        val logo: Bitmap,
        val unitsWide: Float,
        val unitsHigh: Float,
        val wordTop: Float,
        val wordCentreX: Float,
    )

    private const val T = 0.21f   // the stroke
    private const val RO = 0.34f  // outer corner
    private const val RC = RO - T // an inner corner inside an outer one, so the stroke keeps its weight round it
    private const val RI = 0.035f // any other inner corner
    private const val MID_TOP = 0.5f - T / 2f
    private const val MID_BOTTOM = 0.5f + T / 2f
    private const val GAP = 0.42f
    private const val PAD = 0.15f

    /** A glyph's outline at [x], one letter high, with its width. */
    private class Glyph(val width: Float, val evenOdd: Boolean = false, val build: Shape.() -> Unit)

    /** Outline building in letter units, y down, scaled to pixels as it goes. */
    private class Shape(val path: Path, val x0: Float, val y0: Float, val px: Float) {
        fun move(x: Float, y: Float) = path.moveTo((x0 + x) * px, (y0 + y) * px)
        fun line(x: Float, y: Float) = path.lineTo((x0 + x) * px, (y0 + y) * px)

        /** An arc around ([cx], [cy]) of radius [r] from [start] degrees through [sweep] (0 = right, 90 = down). */
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            path.arcTo(RectF((x0 + cx - r) * px, (y0 + cy - r) * px, (x0 + cx + r) * px, (y0 + cy + r) * px), start, sweep, false)
        }

        fun close() = path.close()

        /** A counter, with its corners' radii clockwise from the top left. */
        fun hole(l: Float, t: Float, r: Float, b: Float, tl: Float, tr: Float, br: Float, bl: Float) {
            val radii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl).map { it * px }.toFloatArray()
            path.addRoundRect(RectF((x0 + l) * px, (y0 + t) * px, (x0 + r) * px, (y0 + b) * px), radii, Path.Direction.CCW)
        }
    }

    /** The 3: three bars into an upright [t] thick with two rounded bowls, a notch where they meet. */
    private fun three(w: Float, t: Float, ro: Float, notch: Float) = Glyph(w) {
        // The counters' corners under the outer ones, no rounder than a counter leaves room for.
        val rc = minOf(ro - t, 0.5f - 1.5f * t - RI)
        move(0f, 0f)
        line(w - ro, 0f)
        arc(w - ro, ro, ro, 270f, 90f)
        line(w, 0.5f - notch)
        arc(w - notch, 0.5f - notch, notch, 0f, 90f)
        arc(w - notch, 0.5f + notch, notch, 270f, 90f)
        line(w, 1f - ro)
        arc(w - ro, 1f - ro, ro, 0f, 90f)
        line(0f, 1f)
        line(0f, 1f - t)
        line(w - t - rc, 1f - t)
        arc(w - t - rc, 1f - t - rc, rc, 90f, -90f)
        line(w - t, 0.5f + t / 2f + RI)
        arc(w - t - RI, 0.5f + t / 2f + RI, RI, 0f, -90f)
        line(0f, 0.5f + t / 2f)
        line(0f, 0.5f - t / 2f)
        line(w - t - RI, 0.5f - t / 2f)
        arc(w - t - RI, 0.5f - t / 2f - RI, RI, 90f, -90f)
        line(w - t, t + rc)
        arc(w - t - rc, t + rc, rc, 0f, -90f)
        line(0f, t)
        close()
    }

    private val glyphs: Map<Char, Glyph> = mapOf(
        // An arch with a bar across its middle.
        'A' to Glyph(1.50f, evenOdd = true) {
            val w = 1.50f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, 1f)
            line(w - T, 1f)
            line(w - T, MID_BOTTOM + RI)
            arc(w - T - RI, MID_BOTTOM + RI, RI, 0f, -90f)
            line(T + RI, MID_BOTTOM)
            arc(T + RI, MID_BOTTOM + RI, RI, 270f, -90f)
            line(T, 1f)
            close()
            hole(T, T, w - T, MID_TOP, RC, RC, RI, RI)
        },
        // A bowl over a full upright, and a leg out from under the bowl.
        'R' to Glyph(1.50f, evenOdd = true) {
            val w = 1.50f
            val rm = 0.26f
            val legW = 0.34f
            val legX = 0.62f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, MID_BOTTOM - rm)
            arc(w - rm, MID_BOTTOM - rm, rm, 0f, 90f)
            line(legX + legW, MID_BOTTOM)
            line(w, 1f)
            line(w - legW, 1f)
            line(legX, MID_BOTTOM)
            line(T + RI, MID_BOTTOM)
            arc(T + RI, MID_BOTTOM + RI, RI, 270f, -90f)
            line(T, 1f)
            close()
            hole(T, T, w - T, MID_TOP, RC, RC, maxOf(rm - T, RI), RI)
        },
        // Two arches side by side.
        'M' to Glyph(1.80f) {
            val w = 1.80f
            val cx = w / 2f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, 1f)
            line(w - T, 1f)
            line(w - T, T + RC)
            arc(w - T - RC, T + RC, RC, 0f, -90f)
            line(cx + T / 2f + RI, T)
            arc(cx + T / 2f + RI, T + RI, RI, 270f, -90f)
            line(cx + T / 2f, 1f)
            line(cx - T / 2f, 1f)
            line(cx - T / 2f, T + RI)
            arc(cx - T / 2f - RI, T + RI, RI, 0f, -90f)
            line(T + RC, T)
            arc(T + RC, T + RC, RC, 270f, -90f)
            line(T, 1f)
            close()
        },
        // The PS3 S: an upright down the middle with a bar out to the right at its head and to the
        // left at its foot.
        'S' to Glyph(1.55f) {
            val w = 1.55f
            val sx = (w - T) / 2f
            move(sx, RO)
            arc(sx + RO, RO, RO, 180f, 90f)
            line(w, 0f)
            line(w, T)
            line(sx + T + RC, T)
            arc(sx + T + RC, T + RC, RC, 270f, -90f)
            line(sx + T, 1f - RO)
            arc(sx + T - RO, 1f - RO, RO, 0f, 90f)
            line(0f, 1f)
            line(0f, 1f - T)
            line(sx - RC, 1f - T)
            arc(sx - RC, 1f - T - RC, RC, 90f, -90f)
            line(sx, RO)
            close()
        },
        // Two strokes crossing corner to corner, cut flat at the top and the foot.
        'X' to Glyph(1.50f) {
            val w = 1.50f
            // How wide a stroke T thick is, measured across, at this slant.
            var k = T
            repeat(20) { k = T * kotlin.math.sqrt((w - k) * (w - k) + 1f) }
            move(0f, 0f)
            line(k, 0f)
            line(w, 1f)
            line(w - k, 1f)
            close()
            move(w - k, 0f)
            line(w, 0f)
            line(k, 1f)
            line(0f, 1f)
            close()
        },
        '3' to three(1.50f, T, RO, 0.09f),
    )

    /** The mark: the ARMSX3 logo's 3, heavier. */
    private val mark = three(1.50f, 0.25f, 0.38f, 0.10f)

    /**
     * The ARMSX3 mark on its own, white, for an item in the XMB ([sizePx] square), as the console's
     * own item icons are white.
     */
    fun markIcon(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val h = sizePx * 0.56f
        val w = mark.width * h
        drawMark(canvas, (sizePx - w) / 2f, (sizePx - h) / 2f, h, android.graphics.Color.WHITE, android.graphics.Color.rgb(0xD8, 0xD8, 0xD8))
        return bitmap
    }

    /**
     * The ARMSX3 badge for the category where PlayStation Network was, made the way the console's
     * round PlayStation Network badge is: a glossy disc in the mark's purple with the mark on it.
     */
    fun markBadge(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = sizePx * 0.46f
        val c = sizePx / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                c, c - r * 0.35f, r * 1.25f,
                intArrayOf(android.graphics.Color.rgb(0xB2, 0x8C, 0xFF), android.graphics.Color.rgb(0x6A, 0x2E, 0xD6), android.graphics.Color.rgb(0x3A, 0x10, 0x86)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(c, c, r, disc)
        // The gloss across the top half.
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, c - r, 0f, c, android.graphics.Color.argb(0x70, 0xFF, 0xFF, 0xFF), android.graphics.Color.argb(0x00, 0xFF, 0xFF, 0xFF), Shader.TileMode.CLAMP)
        }
        canvas.drawOval(RectF(c - r * 0.82f, c - r * 0.94f, c + r * 0.82f, c + r * 0.1f), gloss)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.012f
            color = android.graphics.Color.argb(0x90, 0xFF, 0xFF, 0xFF)
        }
        canvas.drawCircle(c, c, r - rim.strokeWidth / 2f, rim)
        val h = r * 0.95f
        val w = mark.width * h
        drawMark(canvas, c - w / 2f, c - h / 2f, h, android.graphics.Color.WHITE, android.graphics.Color.rgb(0xE6, 0xE0, 0xF4))
        return bitmap
    }

    private fun drawMark(canvas: Canvas, x: Float, y: Float, h: Float, top: Int, bottom: Int) {
        val path = Path()
        mark.build(Shape(path, x / h, y / h, h))
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, y, 0f, y + h, top, bottom, Shader.TileMode.CLAMP)
        })
    }

    /** The logo with a letter [letterPx] pixels high. */
    fun render(letterPx: Float): Layers {
        val word = "ARMSX3"
        val wordW = word.sumOf { (glyphs[it]?.width ?: 1f).toDouble() }.toFloat() + GAP * (word.length - 1)
        val wordTop = PAD
        val wordLeft = PAD
        val unitsWide = wordLeft + wordW + PAD
        val unitsHigh = wordTop + 1f + PAD + 0.08f
        val w = (unitsWide * letterPx).toInt().coerceAtLeast(1)
        val h = (unitsHigh * letterPx).toInt().coerceAtLeast(1)

        val logo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(logo)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, wordTop * letterPx, 0f, (wordTop + 1f) * letterPx,
                android.graphics.Color.WHITE, android.graphics.Color.rgb(0xD2, 0xD2, 0xD2), Shader.TileMode.CLAMP,
            )
            setShadowLayer(letterPx * 0.012f, letterPx * 0.045f, letterPx * 0.06f, android.graphics.Color.argb(0x73, 0, 0, 0))
        }
        fun draw(glyph: Glyph, x: Float, top: Float, px: Float) {
            val path = Path()
            path.fillType = if (glyph.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            glyph.build(Shape(path, x, top, px))
            canvas.drawPath(path, fill)
        }
        var x = wordLeft
        for (ch in word) {
            val g = glyphs[ch] ?: continue
            draw(g, x, wordTop, letterPx)
            x += g.width + GAP
        }
        return Layers(logo, unitsWide, unitsHigh, wordTop, wordLeft + wordW / 2f)
    }
}

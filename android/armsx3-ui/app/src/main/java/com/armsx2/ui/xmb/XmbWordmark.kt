package com.armsx2.ui.xmb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The ARMSX3 boot logo, laid out as the console's: the ARMSX3 mark where the PlayStation symbol
 * stood, the word ARMSX3 where "PS3" stood, and a small ARMSX3 under the word where "PlayStation 3"
 * was.
 *
 * The letters follow the construction of the "PS3" wordmark rather than copying it, measured off
 * a capture of the console's boot (letters 87 px high at 1080p): uprights 0.22 of the letter height,
 * bars 0.105, large rounded outer corners, small rounded inner ones, square ends. Its P, S and 3
 * are the models for the R, S and 3 here; A, M and X are built on the same rules. The mark is the
 * ARMSX3 logo's "3", heavier, as the symbol is heavier than the word.
 *
 * Everything is in letter heights, drawn white over a light grey at the foot as the console's logo
 * is. [Layers.logo] holds the mark and the word, [Layers.caption] the line under it, both the same
 * size so they sit together.
 */
object XmbWordmark {
    /**
     * [unitsWide] x [unitsHigh] letter heights; the word's letters run from [wordTop] to
     * [wordTop] + 1 of them, and its middle is [wordCentreX] across.
     */
    class Layers(
        val logo: Bitmap,
        val caption: Bitmap,
        val unitsWide: Float,
        val unitsHigh: Float,
        val wordTop: Float,
        val wordCentreX: Float,
    )

    private const val V = 0.22f   // upright
    private const val B = 0.105f  // bar
    private const val RO = 0.30f  // outer corner
    private const val RM = 0.20f  // the smaller outer corners where a bowl meets a bar
    private const val RI = 0.05f  // inner corner
    private const val MID_TOP = 0.425f
    private const val MID_BOTTOM = 0.53f
    private const val GAP = 0.21f
    private const val MARK_SCALE = 1.12f
    private const val MARK_GAP = 1.0f
    private const val PAD = 0.1f

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

        fun hole(l: Float, t: Float, r: Float, b: Float, top: Float, bottom: Float) {
            val radii = floatArrayOf(top * px, top * px, top * px, top * px, bottom * px, bottom * px, bottom * px, bottom * px)
            path.addRoundRect(RectF((x0 + l) * px, (y0 + t) * px, (x0 + r) * px, (y0 + b) * px), radii, Path.Direction.CCW)
        }
    }

    /** The 3: three bars into an upright with two rounded bowls, a notch where they meet. */
    private fun three(w: Float, v: Float, b: Float, ro: Float, notch: Float) = Glyph(w) {
        move(0f, 0f)
        line(w - ro, 0f)
        arc(w - ro, ro, ro, 270f, 90f)
        line(w, 0.5f - notch)
        arc(w - notch, 0.5f - notch, notch, 0f, 90f)
        arc(w - notch, 0.5f + notch, notch, 270f, 90f)
        line(w, 1f - ro)
        arc(w - ro, 1f - ro, ro, 0f, 90f)
        line(0f, 1f)
        line(0f, 1f - b)
        line(w - v - RI, 1f - b)
        arc(w - v - RI, 1f - b - RI, RI, 90f, -90f)
        line(w - v, 0.5f + b / 2f + RI)
        arc(w - v - RI, 0.5f + b / 2f + RI, RI, 0f, -90f)
        line(0f, 0.5f + b / 2f)
        line(0f, 0.5f - b / 2f)
        line(w - v - RI, 0.5f - b / 2f)
        arc(w - v - RI, 0.5f - b / 2f - RI, RI, 90f, -90f)
        line(w - v, b + RI)
        arc(w - v - RI, b + RI, RI, 0f, -90f)
        line(0f, b)
        close()
    }

    private val glyphs: Map<Char, Glyph> = mapOf(
        // An arch with a bar across its middle.
        'A' to Glyph(1.40f, evenOdd = true) {
            val w = 1.40f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, 1f)
            line(w - V, 1f)
            line(w - V, 0.5f + B / 2f + RI)
            arc(w - V - RI, 0.5f + B / 2f + RI, RI, 0f, -90f)
            line(V + RI, 0.5f + B / 2f)
            arc(V + RI, 0.5f + B / 2f + RI, RI, 270f, -90f)
            line(V, 1f)
            close()
            hole(V, B, w - V, 0.5f - B / 2f, RO - B, RI)
        },
        // The PS3 P's bowl over a full upright, and a leg out from under the bowl.
        'R' to Glyph(1.40f, evenOdd = true) {
            val w = 1.40f
            val legW = 0.32f
            val legX = 0.56f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, MID_BOTTOM - RM)
            arc(w - RM, MID_BOTTOM - RM, RM, 0f, 90f)
            line(legX + legW, MID_BOTTOM)
            line(w, 1f)
            line(w - legW, 1f)
            line(legX, MID_BOTTOM)
            line(V + RI, MID_BOTTOM)
            arc(V + RI, MID_BOTTOM + RI, RI, 270f, -90f)
            line(V, 1f)
            close()
            hole(V, B, w - V, MID_TOP, RO - B, RI)
        },
        // Two arches side by side.
        'M' to Glyph(1.62f) {
            val w = 1.62f
            val cx = w / 2f
            move(0f, 1f)
            line(0f, RO)
            arc(RO, RO, RO, 180f, 90f)
            line(w - RO, 0f)
            arc(w - RO, RO, RO, 270f, 90f)
            line(w, 1f)
            line(w - V, 1f)
            line(w - V, B + RI)
            arc(w - V - RI, B + RI, RI, 0f, -90f)
            line(cx + V / 2f + RI, B)
            arc(cx + V / 2f + RI, B + RI, RI, 270f, -90f)
            line(cx + V / 2f, 1f)
            line(cx - V / 2f, 1f)
            line(cx - V / 2f, B + RI)
            arc(cx - V / 2f - RI, B + RI, RI, 0f, -90f)
            line(V + RI, B)
            arc(V + RI, B + RI, RI, 270f, -90f)
            line(V, 1f)
            close()
        },
        // The PS3 S: an upright with a bar out to the right at its head and to the left at its foot.
        'S' to Glyph(1.58f) {
            val w = 1.58f
            val sx = 0.69f
            move(sx, RO)
            arc(sx + RO, RO, RO, 180f, 90f)
            line(w, 0f)
            line(w, B)
            line(sx + V + RI, B)
            arc(sx + V + RI, B + RI, RI, 270f, -90f)
            line(sx + V, 1f - RO)
            arc(sx + V - RO, 1f - RO, RO, 0f, 90f)
            line(0f, 1f)
            line(0f, 1f - B)
            line(sx - RI, 1f - B)
            arc(sx - RI, 1f - B - RI, RI, 90f, -90f)
            line(sx, RO)
            close()
        },
        // Two strokes crossing, cut flat at the top and the foot.
        'X' to Glyph(1.40f) {
            val w = 1.40f
            val s = 0.32f
            move(0f, 0f)
            line(s, 0f)
            line(w, 1f)
            line(w - s, 1f)
            close()
            move(w - s, 0f)
            line(w, 0f)
            line(s, 1f)
            line(0f, 1f)
            close()
        },
        '3' to three(1.46f, V, B, RO, 0.10f),
    )

    /** The mark: the ARMSX3 logo's 3, heavier. */
    private val mark = three(1.46f, 0.28f, 0.20f, 0.32f, 0.12f)

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

    /** The layers with a letter [letterPx] pixels high; [font] for the caption, when there is one. */
    fun render(letterPx: Float, font: Typeface?): Layers {
        val word = "ARMSX3"
        val wordW = word.sumOf { (glyphs[it]?.width ?: 1f).toDouble() }.toFloat() + GAP * (word.length - 1)
        val markW = mark.width * MARK_SCALE
        val markTop = PAD
        val wordTop = markTop + (MARK_SCALE - 1f) / 2f
        val wordLeft = PAD + markW + MARK_GAP
        val unitsWide = wordLeft + wordW + PAD
        val captionSize = 0.42f
        val captionBaseline = wordTop + 1f + 0.36f + captionSize * 0.72f
        val unitsHigh = captionBaseline + 0.12f + PAD
        val w = (unitsWide * letterPx).toInt().coerceAtLeast(1)
        val h = (unitsHigh * letterPx).toInt().coerceAtLeast(1)

        val logo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(logo)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, markTop * letterPx, 0f, (markTop + MARK_SCALE) * letterPx,
                android.graphics.Color.WHITE, android.graphics.Color.rgb(0xD2, 0xD2, 0xD2), Shader.TileMode.CLAMP,
            )
        }
        fun draw(glyph: Glyph, x: Float, top: Float, px: Float) {
            val path = Path()
            path.fillType = if (glyph.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            glyph.build(Shape(path, x, top, px))
            canvas.drawPath(path, fill)
        }
        // The mark is drawn at its own scale: units of its own height.
        draw(mark, PAD / MARK_SCALE, markTop / MARK_SCALE, letterPx * MARK_SCALE)
        var x = wordLeft
        for (ch in word) {
            val g = glyphs[ch] ?: continue
            draw(g, x, wordTop, letterPx)
            x += g.width + GAP
        }

        val caption = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(caption).drawText(
            word,
            (wordLeft + wordW / 2f) * letterPx,
            captionBaseline * letterPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0xEC, 0xEC, 0xEC)
                textSize = captionSize * letterPx
                textAlign = Paint.Align.CENTER
                typeface = font ?: Typeface.create("sans-serif", Typeface.NORMAL)
            },
        )
        return Layers(logo, caption, unitsWide, unitsHigh, wordTop, wordLeft + wordW / 2f)
    }
}

package com.armsx2.ui.xmb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The ARMSX3 boot logo, drawn in the manner of the console's "PS3" wordmark rather than copied from
 * it: one stroke weight (an eighth of the letter height), wide letters, rounded outer corners and
 * square ends. The S and the 3 follow the PS3 logo's construction (an integral-like S, a flat-topped
 * 3); the other letters are built on the same rules.
 *
 * Three white masks, the same three layers the firmware's coldboot scene animates: the wordmark, a
 * small "ARMSX3" under it in place of the "PlayStation 3" line, and a soft glow of both. 2:1, like
 * the scene's 700x350 textures, so they sit on screen where the console's did.
 */
object XmbWordmark {
    class Layers(val logo: Bitmap, val footer: Bitmap, val glow: Bitmap)

    private const val T = 0.125f // stroke, in letter heights
    private const val R = 0.26f  // outer corner radius
    private const val GAP = 0.34f

    private class Glyph(val width: Float, val draw: Path.(x: Float, h: Float) -> Unit)

    // Centre-line coordinates: c is half a stroke, so a bar at c sits flush with the top edge.
    private val glyphs: Map<Char, Glyph> = run {
        val c = T / 2f
        val rc = R - c
        fun Path.corner(cx: Float, cy: Float, startDeg: Float, h: Float, x0: Float) {
            arcTo(RectF((x0 + (cx - rc)) * h, (cy - rc) * h, (x0 + (cx + rc)) * h, (cy + rc) * h), startDeg, 90f, false)
        }
        mapOf(
            'A' to Glyph(1.18f) { x, h ->
                val w = 1.18f
                moveTo((x + c) * h, 1f * h)
                lineTo((x + c) * h, (c + rc) * h)
                corner(c + rc, c + rc, 180f, h, x)
                lineTo((x + w - c - rc) * h, c * h)
                corner(w - c - rc, c + rc, 270f, h, x)
                lineTo((x + w - c) * h, 1f * h)
                moveTo((x + c) * h, 0.56f * h)
                lineTo((x + w - c) * h, 0.56f * h)
            },
            'R' to Glyph(1.18f) { x, h ->
                val w = 1.18f
                val mid = 0.53f
                moveTo((x + c) * h, 1f * h)
                lineTo((x + c) * h, (c + rc) * h)
                corner(c + rc, c + rc, 180f, h, x)
                lineTo((x + w - c - rc) * h, c * h)
                corner(w - c - rc, c + rc, 270f, h, x)
                lineTo((x + w - c) * h, (mid - rc) * h)
                corner(w - c - rc, mid - rc, 0f, h, x)
                lineTo((x + c) * h, mid * h)
                // The leg, the one diagonal: from under the bowl to the foot.
                moveTo((x + w * 0.5f) * h, mid * h)
                lineTo((x + w - c) * h, 1f * h)
            },
            'M' to Glyph(1.46f) { x, h ->
                val w = 1.46f
                moveTo((x + c) * h, 1f * h)
                lineTo((x + c) * h, (c + rc) * h)
                corner(c + rc, c + rc, 180f, h, x)
                lineTo((x + w - c - rc) * h, c * h)
                corner(w - c - rc, c + rc, 270f, h, x)
                lineTo((x + w - c) * h, 1f * h)
                moveTo((x + w / 2f) * h, c * h)
                lineTo((x + w / 2f) * h, 0.78f * h)
            },
            'S' to Glyph(1.24f) { x, h ->
                val w = 1.24f
                val r2 = 0.30f
                moveTo((x + w) * h, c * h)
                lineTo((x + w / 2f + r2) * h, c * h)
                arcTo(RectF((x + w / 2f) * h, c * h, (x + w / 2f + 2f * r2) * h, (c + 2f * r2) * h), 270f, -90f, false)
                lineTo((x + w / 2f) * h, (1f - c - r2) * h)
                arcTo(RectF((x + w / 2f - 2f * r2) * h, (1f - c - 2f * r2) * h, (x + w / 2f) * h, (1f - c) * h), 0f, 90f, false)
                lineTo(x * h, (1f - c) * h)
            },
            'X' to Glyph(1.22f) { x, h ->
                val w = 1.22f
                moveTo((x + c) * h, 0f)
                lineTo((x + w - c) * h, 1f * h)
                moveTo((x + w - c) * h, 0f)
                lineTo((x + c) * h, 1f * h)
            },
            '3' to Glyph(1.26f) { x, h ->
                val w = 1.26f
                moveTo(x * h, c * h)
                lineTo((x + w - c - rc) * h, c * h)
                corner(w - c - rc, c + rc, 270f, h, x)
                lineTo((x + w - c) * h, (1f - c - rc) * h)
                corner(w - c - rc, 1f - c - rc, 0f, h, x)
                lineTo(x * h, (1f - c) * h)
                moveTo((x + w * 0.28f) * h, 0.5f * h)
                lineTo((x + w - c) * h, 0.5f * h)
            },
        )
    }

    /** The three layers at [width] x width/2 pixels; [font] for the footer line, when there is one. */
    fun render(width: Int, font: Typeface?): Layers {
        val height = width / 2
        val word = "ARMSX3"
        val units = word.sumOf { (glyphs[it]?.width ?: 1f).toDouble() }.toFloat() + GAP * (word.length - 1)
        // As wide as the console's logo was in its texture (its ink spanned 91% of it).
        val letterH = (width * 0.86f) / units
        val logo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(logo).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = T * letterH
                strokeCap = Paint.Cap.BUTT
                strokeJoin = Paint.Join.MITER
            }
            val left = (width - units * letterH) / 2f
            val top = height * 0.49f - letterH / 2f
            save()
            translate(left, top)
            var x = 0f
            for (ch in word) {
                val g = glyphs[ch] ?: continue
                val path = Path()
                g.draw(path, x, letterH)
                drawPath(path, paint)
                x += g.width + GAP
            }
            restore()
        }
        val footer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(footer).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = height * 0.075f
                textAlign = Paint.Align.CENTER
                typeface = font ?: Typeface.create("sans-serif", Typeface.NORMAL)
                letterSpacing = 0.04f
            }
            drawText(word, width / 2f, height * 0.49f + letterH * 0.5f + height * 0.2f, paint)
        }
        val glow = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(glow).apply {
            drawBitmap(logo, 0f, 0f, null)
            drawBitmap(footer, 0f, 0f, null)
        }
        blurAlpha(glow, (width * 0.012f).toInt().coerceAtLeast(2))
        return Layers(logo, footer, glow)
    }

    /** Three box-blur passes over the alpha channel (close to a Gaussian), keeping it white. */
    private fun blurAlpha(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var a = IntArray(w * h) { px[it] ushr 24 }
        var tmp = IntArray(w * h)
        repeat(3) {
            boxPass(a, tmp, w, h, radius, horizontal = true)
            boxPass(tmp, a, w, h, radius, horizontal = false)
        }
        // A glow reads stronger than the shape it came from: scale it up, as the console's blur
        // layer is brighter than a plain blur of its logo.
        for (i in px.indices) {
            val v = (a[i] * 1.6f).toInt().coerceAtMost(255)
            px[i] = (v shl 24) or 0xFFFFFF
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        a = IntArray(0)
        tmp = IntArray(0)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val j = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + j else j * w + line
            }
            var sum = 0
            for (i in -r..r) sum += src[at(i)]
            for (i in 0 until len) {
                dst[at(i)] = sum / div
                sum += src[at(i + r + 1)] - src[at(i - r)]
            }
        }
    }
}

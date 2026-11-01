package com.armsx2.ui.xmb

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * The XMB Mode icon for the drawer: the ARMSX3 logo without its 3, the purple ground with the wave
 * across it and the sparkles along the wave.
 */
@Composable
fun XmbWaveIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.24f)
        val shape = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, corner))
        }
        clipPath(shape) {
            drawRect(Brush.verticalGradient(0f to Color(0xFF1C0B3E), 0.55f to Color(0xFF4A1FA8), 1f to Color(0xFF9150F2)))
            fun wave(y0: Float, y1: Float, lift: Float) = Path().apply {
                moveTo(-0.05f * w, y0 * h)
                cubicTo(0.3f * w, (y0 + lift) * h, 0.6f * w, (y1 - lift) * h, 1.05f * w, y1 * h)
            }
            // The lighter water under the wave.
            val under = wave(0.70f, 0.56f, 0.06f).apply {
                lineTo(1.05f * w, 1.05f * h)
                lineTo(-0.05f * w, 1.05f * h)
                close()
            }
            drawPath(under, Color.White.copy(alpha = 0.10f))
            drawPath(wave(0.84f, 0.70f, 0.05f), Color.White.copy(alpha = 0.30f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
            val crest = wave(0.70f, 0.56f, 0.06f)
            drawPath(crest, Color.White.copy(alpha = 0.22f), style = Stroke(w * 0.13f, cap = StrokeCap.Round))
            drawPath(crest, Color.White.copy(alpha = 0.95f), style = Stroke(w * 0.04f, cap = StrokeCap.Round))
            for ((x, y, r) in listOf(
                Triple(0.16f, 0.60f, 0.022f), Triple(0.30f, 0.66f, 0.016f), Triple(0.55f, 0.55f, 0.020f),
                Triple(0.72f, 0.50f, 0.026f), Triple(0.86f, 0.47f, 0.016f),
            )) drawCircle(Color.White.copy(alpha = 0.85f), r * w, Offset(x * w, y * h))
        }
    }
}

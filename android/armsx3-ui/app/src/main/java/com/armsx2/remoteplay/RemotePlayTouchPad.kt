package com.armsx2.remoteplay

import android.content.Context
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How an on-screen element of the viewer shows itself. Tap to reveal is the default for both. */
enum class RevealMode(val key: String) {
    TAP("tap"),
    ON("on"),
    OFF("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: TAP
    }
}

/** The viewer's own choices, set on the Remote play screen. */
object RemotePlayPrefs {
    private const val FILE = "remoteplay"
    private const val KEY_EXIT = "exitButton"
    private const val KEY_TOUCH = "touchControls"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun exitButton(context: Context) = RevealMode.of(prefs(context).getString(KEY_EXIT, null))
    fun touchControls(context: Context) = RevealMode.of(prefs(context).getString(KEY_TOUCH, null))

    fun setExitButton(context: Context, mode: RevealMode) = prefs(context).edit().putString(KEY_EXIT, mode.key).apply()
    fun setTouchControls(context: Context, mode: RevealMode) = prefs(context).edit().putString(KEY_TOUCH, mode.key).apply()
}

private enum class Kind { BUTTON, DPAD, LEFT_STICK, RIGHT_STICK }

private class Control(
    val kind: Kind,
    val center: Offset,
    val radius: Float,
    val bit: Int = 0,
    val label: String = "",
    val tint: Color = Color.White,
    /** Shoulder, Select and Start are pills; everything else is round. */
    val rect: Rect? = null,
)

/**
 * Player 2's on-screen pad, for a viewer without a controller. Laid out for landscape around the
 * screen's height, so it keeps its shape on any screen. Multi-touch: a finger that starts on a
 * stick keeps it until lifted; a finger on the buttons can slide from one to the next.
 */
@Composable
internal fun TouchPad(onChange: (RemotePlayProtocol.PadState) -> Unit, onTouch: () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val controls = remember(width, height) { layout(width, height) }
        var pressedBits by remember { mutableStateOf(0) }
        var leftKnob by remember { mutableStateOf(Offset.Zero) }
        var rightKnob by remember { mutableStateOf(Offset.Zero) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(controls) {
                    // Finger id -> the stick it holds (LEFT_STICK / RIGHT_STICK), or null for buttons.
                    val owners = HashMap<Long, Kind?>()
                    val positions = HashMap<Long, Offset>()
                    var last = RemotePlayProtocol.PadState()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    if (!change.previousPressed) {
                                        owners[id] = stickAt(controls, change.position)
                                    }
                                    positions[id] = change.position
                                } else {
                                    owners.remove(id)
                                    positions.remove(id)
                                }
                                change.consume()
                            }

                            var bits = 0
                            var left = Offset.Zero
                            var right = Offset.Zero
                            for ((id, position) in positions) {
                                when (owners[id]) {
                                    Kind.LEFT_STICK -> left = stickVector(controls, Kind.LEFT_STICK, position)
                                    Kind.RIGHT_STICK -> right = stickVector(controls, Kind.RIGHT_STICK, position)
                                    else -> bits = bits or buttonsAt(controls, position)
                                }
                            }

                            val newlyPressed = bits and pressedBits.inv()
                            if (newlyPressed != 0) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            pressedBits = bits
                            leftKnob = left
                            rightKnob = right

                            val state = RemotePlayProtocol.PadState(
                                buttons = bits and (L2_BIT or R2_BIT).inv(),
                                lx = RemotePlayProtocol.axisByte(left.x),
                                ly = RemotePlayProtocol.axisByte(left.y),
                                rx = RemotePlayProtocol.axisByte(right.x),
                                ry = RemotePlayProtocol.axisByte(right.y),
                                l2 = if (bits and L2_BIT != 0) 255 else 0,
                                r2 = if (bits and R2_BIT != 0) 255 else 0,
                            )
                            if (state != last) {
                                last = state
                                onChange(state)
                            }
                            onTouch()
                        }
                    }
                },
        ) {
            for (control in controls) drawControl(control, pressedBits, leftKnob, rightKnob)
        }
    }
}

// L2 and R2 travel as analog bytes in PadState; on the touch pad they are plain buttons, so they
// borrow two bits PadState does not use for the hit test and become 255 or 0.
private const val L2_BIT = 1 shl 20
private const val R2_BIT = 1 shl 21

private fun layout(width: Float, height: Float): List<Control> {
    val h = height
    val margin = 0.05f * h
    val faceCenter = Offset(width - margin - 0.17f * h, 0.42f * h)
    val dpadCenter = Offset(margin + 0.17f * h, 0.42f * h)
    val leftStick = Offset(margin + 0.38f * h, 0.78f * h)
    val rightStick = Offset(width - margin - 0.38f * h, 0.78f * h)
    val face = 0.058f * h
    val spread = 0.1f * h
    val pill = 0.075f * h
    val shoulderWidth = 0.22f * h

    fun pillControl(label: String, bit: Int, left: Float, top: Float, w: Float) =
        Control(Kind.BUTTON, Offset(left + w / 2, top + pill / 2), pill / 2, bit, label, rect = Rect(left, top, left + w, top + pill))

    return listOf(
        Control(Kind.DPAD, dpadCenter, 0.15f * h),
        Control(Kind.LEFT_STICK, leftStick, 0.12f * h),
        Control(Kind.RIGHT_STICK, rightStick, 0.12f * h),
        Control(Kind.BUTTON, faceCenter + Offset(0f, -spread), face, RemotePlayProtocol.PAD_TRIANGLE, "△", Color(0xFF3ECFA0)),
        Control(Kind.BUTTON, faceCenter + Offset(spread, 0f), face, RemotePlayProtocol.PAD_CIRCLE, "○", Color(0xFFFF6B6B)),
        Control(Kind.BUTTON, faceCenter + Offset(0f, spread), face, RemotePlayProtocol.PAD_CROSS, "✕", Color(0xFF7AA8FF)),
        Control(Kind.BUTTON, faceCenter + Offset(-spread, 0f), face, RemotePlayProtocol.PAD_SQUARE, "□", Color(0xFFE68DD8)),
        Control(Kind.BUTTON, leftStick + Offset(-0.2f * h, 0.06f * h), 0.045f * h, RemotePlayProtocol.PAD_L3, "L3"),
        Control(Kind.BUTTON, rightStick + Offset(0.2f * h, 0.06f * h), 0.045f * h, RemotePlayProtocol.PAD_R3, "R3"),
        pillControl("L2", L2_BIT, margin, 0.03f * h, shoulderWidth),
        pillControl("L1", RemotePlayProtocol.PAD_L1, margin, 0.13f * h, shoulderWidth),
        pillControl("R2", R2_BIT, width - margin - shoulderWidth, 0.03f * h, shoulderWidth),
        pillControl("R1", RemotePlayProtocol.PAD_R1, width - margin - shoulderWidth, 0.13f * h, shoulderWidth),
        pillControl("SELECT", RemotePlayProtocol.PAD_SELECT, width / 2 - 0.27f * h, 0.87f * h, 0.2f * h),
        pillControl("START", RemotePlayProtocol.PAD_START, width / 2 + 0.07f * h, 0.87f * h, 0.2f * h),
    )
}

/** The stick a finger lands on, with a little slack around it; null for anything else. */
private fun stickAt(controls: List<Control>, position: Offset): Kind? =
    controls.firstOrNull {
        (it.kind == Kind.LEFT_STICK || it.kind == Kind.RIGHT_STICK) && (position - it.center).getDistance() <= it.radius * 1.4f
    }?.kind

/** Where a stick is pushed, as x/y in -1..1 with a small deadzone, expanded to the square like a DualShock. */
private fun stickVector(controls: List<Control>, kind: Kind, position: Offset): Offset {
    val stick = controls.first { it.kind == kind }
    val raw = (position - stick.center) / stick.radius
    val magnitude = hypot(raw.x, raw.y)
    if (magnitude < 0.12f) return Offset.Zero
    val out = ((magnitude - 0.12f) / 0.88f).coerceAtMost(1f)
    val scale = out / max(abs(raw.x), abs(raw.y))
    return Offset((raw.x * scale).coerceIn(-1f, 1f), (raw.y * scale).coerceIn(-1f, 1f))
}

/** The buttons under a finger: the nearest round button in reach, a pill it is inside, or d-pad directions. */
private fun buttonsAt(controls: List<Control>, position: Offset): Int {
    controls.firstOrNull { control ->
        val rect = control.rect
        rect != null && rect.inflate(rect.height * 0.25f).contains(position)
    }?.let { return it.bit }

    val dpad = controls.first { it.kind == Kind.DPAD }
    val fromDpad = position - dpad.center
    if (fromDpad.getDistance() <= dpad.radius * 1.2f) {
        val threshold = dpad.radius * 0.35f
        return (if (fromDpad.y < -threshold) RemotePlayProtocol.PAD_UP else 0) or
            (if (fromDpad.y > threshold) RemotePlayProtocol.PAD_DOWN else 0) or
            (if (fromDpad.x < -threshold) RemotePlayProtocol.PAD_LEFT else 0) or
            (if (fromDpad.x > threshold) RemotePlayProtocol.PAD_RIGHT else 0)
    }

    return controls
        .filter { it.kind == Kind.BUTTON && it.rect == null && (position - it.center).getDistance() <= it.radius * 1.35f }
        .minByOrNull { (position - it.center).getDistance() }
        ?.bit ?: 0
}

private val fill = Color.White.copy(alpha = 0.12f)
private val fillPressed = Color.White.copy(alpha = 0.38f)
private val outline = Color.White.copy(alpha = 0.35f)
private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

private fun DrawScope.drawControl(control: Control, pressed: Int, leftKnob: Offset, rightKnob: Offset) {
    val stroke = Stroke(width = 1.5f * density)
    when (control.kind) {
        Kind.DPAD -> {
            val arm = control.radius * 0.42f
            val armLength = control.radius
            val directions = listOf(
                RemotePlayProtocol.PAD_UP to Rect(control.center.x - arm / 2, control.center.y - armLength, control.center.x + arm / 2, control.center.y - arm / 2),
                RemotePlayProtocol.PAD_DOWN to Rect(control.center.x - arm / 2, control.center.y + arm / 2, control.center.x + arm / 2, control.center.y + armLength),
                RemotePlayProtocol.PAD_LEFT to Rect(control.center.x - armLength, control.center.y - arm / 2, control.center.x - arm / 2, control.center.y + arm / 2),
                RemotePlayProtocol.PAD_RIGHT to Rect(control.center.x + arm / 2, control.center.y - arm / 2, control.center.x + armLength, control.center.y + arm / 2),
            )
            for ((bit, rect) in directions) {
                drawRect(if (pressed and bit != 0) fillPressed else fill, rect.topLeft, rect.size)
                drawRect(outline, rect.topLeft, rect.size, style = stroke)
            }
        }
        Kind.LEFT_STICK, Kind.RIGHT_STICK -> {
            val knob = if (control.kind == Kind.LEFT_STICK) leftKnob else rightKnob
            drawCircle(fill, control.radius, control.center)
            drawCircle(outline, control.radius, control.center, style = stroke)
            val moved = knob != Offset.Zero
            drawCircle(if (moved) fillPressed else outline.copy(alpha = 0.25f), control.radius * 0.45f, control.center + knob * control.radius * 0.6f)
        }
        Kind.BUTTON -> {
            val down = pressed and control.bit != 0
            val rect = control.rect
            if (rect != null) {
                val corner = androidx.compose.ui.geometry.CornerRadius(rect.height / 2)
                drawRoundRect(if (down) fillPressed else fill, rect.topLeft, rect.size, corner)
                drawRoundRect(outline, rect.topLeft, rect.size, corner, style = stroke)
            } else {
                drawCircle(if (down) fillPressed else fill, control.radius, control.center)
                drawCircle(outline, control.radius, control.center, style = stroke)
            }
            val size = if (rect != null) rect.height * 0.42f else control.radius * if (control.label.length > 1) 0.7f else 1.05f
            labelPaint.textSize = size
            labelPaint.color = control.tint.copy(alpha = if (down) 1f else 0.75f).toArgb()
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(control.label, control.center.x, control.center.y + size * 0.36f, labelPaint)
            }
        }
    }
}

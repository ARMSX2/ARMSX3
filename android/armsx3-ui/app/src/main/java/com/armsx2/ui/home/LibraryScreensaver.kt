package com.armsx2.ui.home

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The Library Screensaver: after a stretch with no input (10 minutes unless App Settings says
 * otherwise, 1 to 60), the library background fills the screen as View background shows it, until
 * the next touch, key or stick push. That input only wakes it: pressing A does not also start the
 * game under the cursor.
 *
 * The library keeps the screen on while it is open, so a library left alone used to show the same
 * picture for hours, which an OLED panel keeps. It never starts in a game, and only while the app's
 * own window has focus: a dialog or menu over it takes input the activity never sees, so time spent
 * in one cannot be told from time away.
 *
 * Input reaches it from MainActivityRuntime's dispatch methods, before anything else sees it.
 */
object LibraryScreensaver {
    private const val EnabledKey = "ui.library.screensaver"
    private const val MinutesKey = "ui.library.screensaverMinutes"
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60
    private const val DEFAULT_MINUTES = 10
    private const val CHECK_MS = 1000L

    /** Past this, a stick or trigger counts as pushed: resting sticks drift and some pads report it. */
    private const val DEAD_ZONE = 0.35f

    val enabled = mutableStateOf(true)
    val minutes = mutableStateOf(DEFAULT_MINUTES)

    /** Whether it is on screen now. */
    val showing = mutableStateOf(false)

    @Volatile private var lastInput = SystemClock.uptimeMillis()

    // What woke it, swallowed until it lets go: the key, a touch, or a stick or D-pad axis.
    private var swallowKey: Int? = null
    private var swallowTouch = false
    private var swallowMotion = false

    fun load() {
        enabled.value = MainActivityRuntime.prefs.getBoolean(EnabledKey, true)
        minutes.value = MainActivityRuntime.prefs.getInt(MinutesKey, DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        MainActivityRuntime.prefs.edit { putBoolean(EnabledKey, value) }
    }

    fun setMinutes(value: Int) {
        val v = value.coerceIn(MIN_MINUTES, MAX_MINUTES)
        minutes.value = v
        MainActivityRuntime.prefs.edit { putInt(MinutesKey, v) }
    }

    /** Count from now, as if there had just been input. */
    fun reset() {
        lastInput = SystemClock.uptimeMillis()
    }

    private fun wake() {
        showing.value = false
        reset()
    }

    /** A key event. True when it woke the screensaver, or is the rest of the key that did. The
     *  volume keys neither wake it nor stop at it: they turn the volume, of its music too. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return false
        reset()
        val held = swallowKey
        if (held != null && event.keyCode == held) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = null
            return true
        }
        if (!showing.value) return false
        if (event.action == KeyEvent.ACTION_DOWN) swallowKey = event.keyCode
        wake()
        return true
    }

    /** A touch event. True for the whole touch that woke the screensaver. */
    fun onTouch(event: MotionEvent): Boolean {
        reset()
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        if (!showing.value) return false
        swallowTouch = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
        wake()
        return true
    }

    /**
     * A joystick or D-pad motion event. Only a push past [DEAD_ZONE] counts as input; one that
     * wakes the screensaver is swallowed until every axis is back at rest.
     */
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            reset()
            return false
        }
        val pushed = AXES.any { abs(event.getAxisValue(it)) > DEAD_ZONE }
        if (swallowMotion) {
            if (!pushed) swallowMotion = false
            if (pushed) reset()
            return true
        }
        if (!pushed) return false
        reset()
        if (!showing.value) return false
        swallowMotion = true
        wake()
        return true
    }

    private val AXES = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
    )

    private fun due(): Boolean =
        enabled.value && !showing.value && SystemClock.uptimeMillis() - lastInput >= minutes.value * 60_000L

    /**
     * Shows the screensaver when it is due, over whatever is on screen. [active] is false in a
     * game and anywhere else it must not start; it also stops one that is up.
     */
    @Composable
    fun Host(active: Boolean) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(active, focused) {
            // Coming back from a game, a dialog or another app counts as input.
            reset()
            if (!active) {
                showing.value = false
                return@LaunchedEffect
            }
            if (!focused) return@LaunchedEffect
            while (true) {
                delay(CHECK_MS)
                if (due()) showing.value = true
            }
        }
        if (active && showing.value) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LibraryBackdrop()
            }
        }
    }
}

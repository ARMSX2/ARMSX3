package com.armsx2.input

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SIXAXIS motion on buttons, the way Dolphin maps Wiimote motion: bindable actions that move the
 * emulated DualShock 3's motion sensors while held (tilt, turn, shake) or play a short gesture when
 * pressed (flick). For motion a handheld cannot make well, or that a controller without sensors
 * cannot make at all, such as Folklore's flick up to catch a folk (asked for on an Odin 2).
 *
 * Writes the same registers as the sensor feed and takes them over while any action on that player
 * is active; [Sixaxis] checks [overriding] and stands aside until it ends, and the player is left at
 * rest. Samples go out every [TICK_MS] from a thread of their own, so a busy UI cannot stretch a
 * flick into something the game no longer reads as one.
 *
 * Values are in the pad frame [Sixaxis.send] uses: acceleration in g as the sensor measures it (Y
 * reads +1 lying face up, X to the right, Z toward the player) and yaw in rad/s, counter-clockwise
 * seen from above. A flick is a burst of upward (or downward) acceleration and the swing back that
 * stops it, the shape a real DualShock 3 reports; the strengths are a first guess, to be tuned against
 * the games that use them.
 */
object MotionButtons {
    const val FLICK_UP = 300
    const val FLICK_DOWN = 301
    const val SHAKE = 302
    const val TILT_LEFT = 303
    const val TILT_RIGHT = 304
    const val TILT_FORWARD = 305
    const val TILT_BACK = 306
    const val TURN_LEFT = 307
    const val TURN_RIGHT = 308

    /** Every motion target, so the input paths can tell one from a button. */
    val CODES = FLICK_UP..TURN_RIGHT

    private const val TICK_MS = 8L
    private const val FLICK_PUSH_MS = 70L
    private const val FLICK_TOTAL_MS = 140L
    private const val FLICK_PUSH_G = 2.5f
    private const val FLICK_STOP_G = 1.8f
    private const val SHAKE_HZ = 10f
    private const val SHAKE_G = 2.5f
    private val TILT_RAD = (50.0 * PI / 180.0).toFloat()
    private const val TURN_RAD_PER_SEC = 3f

    private class Player {
        val held = LinkedHashSet<Int>()
        var flickCode = 0
        var flickStart = 0L
        var shakeStart = 0L
        /** Whether the last tick sent this player a sample, so the one after it ends can park it. */
        var sending = false
        fun active(now: Long) = held.isNotEmpty() || (flickCode != 0 && now - flickStart < FLICK_TOTAL_MS)
    }

    // The players the core has motion for. Guarded by `this`: keys arrive on the main thread and
    // samples go out on [handler]'s.
    private val players = Array(7) { Player() }
    private var handler: Handler? = null
    private var ticking = false

    /** True while an action is moving [port]'s sensors, so the sensor feed does not fight it. */
    @Synchronized
    fun overriding(port: Int): Boolean =
        players.getOrNull(port)?.active(SystemClock.uptimeMillis()) == true

    /** A mapped motion action pressed or released on [port]. Key repeat is ignored. */
    @Synchronized
    fun onKey(port: Int, code: Int, down: Boolean) {
        val player = players.getOrNull(port) ?: return
        val now = SystemClock.uptimeMillis()
        if (down) {
            if (!player.held.add(code)) return
            when (code) {
                FLICK_UP, FLICK_DOWN -> {
                    player.flickCode = code
                    player.flickStart = now
                }
                SHAKE -> player.shakeStart = now
            }
        } else {
            player.held.remove(code)
        }
        startTicking()
    }

    private fun startTicking() {
        if (ticking) return
        val h = handler ?: Handler(HandlerThread("ARMSX3-MotionButtons").apply { start() }.looper)
            .also { handler = it }
        ticking = true
        h.post(::tick)
    }

    private fun tick() {
        val again = synchronized(this) {
            val now = SystemClock.uptimeMillis()
            var any = false
            for (port in players.indices) {
                val player = players[port]
                if (player.active(now)) {
                    any = true
                    player.sending = true
                    send(port, sample(player, now))
                } else if (player.sending) {
                    // Just ended: leave the player at rest, so the game does not read the last
                    // sample forever when there is no sensor feed to take over.
                    player.sending = false
                    player.flickCode = 0
                    player.shakeStart = 0L
                    send(port, Sample())
                }
            }
            ticking = any
            any
        }
        if (again) handler?.postDelayed(::tick, TICK_MS)
    }

    private data class Sample(val x: Float = 0f, val y: Float = 1f, val z: Float = 0f, val yaw: Float = 0f)

    private fun sample(player: Player, now: Long): Sample {
        var x = 0f
        var y = 1f
        var z = 0f
        var yaw = 0f

        // Tilt rotates gravity in the pad frame: rolling left raises the right side, so +X reads
        // part of it; tipping forward raises the side toward the player, so +Z does.
        val roll = (if (TILT_LEFT in player.held) TILT_RAD else 0f) - (if (TILT_RIGHT in player.held) TILT_RAD else 0f)
        val pitch = (if (TILT_FORWARD in player.held) TILT_RAD else 0f) - (if (TILT_BACK in player.held) TILT_RAD else 0f)
        if (roll != 0f || pitch != 0f) {
            x = sin(roll)
            z = sin(pitch)
            y = cos(roll) * cos(pitch)
        }

        if (TURN_LEFT in player.held) yaw += TURN_RAD_PER_SEC
        if (TURN_RIGHT in player.held) yaw -= TURN_RAD_PER_SEC

        if (SHAKE in player.held) {
            val t = (now - player.shakeStart) / 1000f
            x += SHAKE_G * sin(2f * PI.toFloat() * SHAKE_HZ * t)
        }

        // A flick plays out once per press: the push, then the stop.
        if (player.flickCode != 0) {
            val t = now - player.flickStart
            if (t < FLICK_TOTAL_MS) {
                val up = player.flickCode == FLICK_UP
                val push = if (t < FLICK_PUSH_MS) FLICK_PUSH_G else -FLICK_STOP_G
                y += if (up) push else -push
            }
        }
        return Sample(x, y, z, yaw)
    }

    private fun send(port: Int, s: Sample) =
        com.armsx3.Rpcs3Bridge.setPadMotion(port, -s.x, -s.y, -s.z, -s.yaw)
}

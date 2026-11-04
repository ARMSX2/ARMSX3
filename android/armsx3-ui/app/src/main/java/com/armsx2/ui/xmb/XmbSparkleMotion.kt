package com.armsx2.ui.xmb

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.InputDevice
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Moving the controller moves the XMB's sparkles, as on the console.
 *
 * The PS3 pushes its wave's particles with the SIXAXIS: lines.qrc's PARTICLES_UI.mnu tunes it with a
 * sideways coefficient and a gravity one ("dshake x coeff", "dshake g coeff"), a shake threshold
 * ("dshake thresh" 0.72 g) and a jitter kick for shakes past it ("dshake brw imp"). So swinging the
 * pad to the left, or tipping it down on the left, sends them to the left, and a hard shake scatters
 * them for a moment.
 *
 * Two kinds of source, each read the same way: this device's own sensors (a handheld's built-in
 * controls ARE the device, and its screen is what the player holds), and, on Android 12 and up, any
 * controller that reports its own motion (a DualShock 4 or DualSense). Each sample is split into
 * gravity and movement along the player's left-right axis; the source moving hardest wins, so a
 * handheld whose built-in pad also reports motion is not counted twice.
 *
 * [want] switches the sensors on and off from any thread; the wave's render thread calls it every
 * frame, so the sensors run only while XMB mode's wave is on screen. [push] and [shake] are read on
 * that thread too.
 */
class XmbSparkleMotion(context: Context, private val displayRotation: () -> Int) {

    /** Sideways push on the sparkles, in g, positive to the right. */
    @Volatile var push = 0f
        private set

    /** How far past the console's shake threshold the last movement went, 0 to 1. */
    @Volatile var shake = 0f
        private set

    private val appContext = context.applicationContext
    private val deviceSensors = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val wanted = AtomicBoolean(false)

    /** The sensors running now, on their own thread; replaced, never reused, at each start. */
    private var session: Session? = null

    /** Sensors on while [on]; cheap to call every frame, it only acts when the answer changes. */
    fun want(on: Boolean) {
        if (wanted.getAndSet(on) == on) return
        synchronized(this) {
            // The latest wish, in case another thread changed it while this one waited.
            val latest = wanted.get()
            if (latest == (session != null)) return
            session?.close()
            session = if (latest) Session().also { it.open() } else null
            if (!latest) {
                push = 0f
                shake = 0f
            }
        }
    }

    /**
     * One run of the sensors, on its own thread, so switching off and straight back on can never
     * have the old run's teardown close the new run's listeners.
     */
    private inner class Session {
        private val thread = HandlerThread("xmb-motion").also { it.start() }
        private val handler = Handler(thread.looper)
        @Volatile private var closed = false

        // The thread's own.
        private var deviceSource: Source? = null
        private val controllerSources = HashMap<Int, Source>()

        private val deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = rescanControllers()
            override fun onInputDeviceRemoved(deviceId: Int) = rescanControllers()
            override fun onInputDeviceChanged(deviceId: Int) = rescanControllers()
        }

        fun open() {
            handler.post {
                if (closed) return@post
                deviceSource = deviceSensors?.let { openDevice(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { inputManager?.registerInputDeviceListener(deviceListener, handler) }
                    rescanControllers()
                }
                val names = listOfNotNull(deviceSource?.name) + controllerSources.values.map { it.name }
                Log.i(TAG, if (names.isEmpty()) "sparkles: no motion sensors" else "sparkles follow motion from ${names.joinToString()}")
            }
        }

        fun close() {
            closed = true
            handler.post {
                runCatching { inputManager?.unregisterInputDeviceListener(deviceListener) }
                deviceSource?.close()
                deviceSource = null
                controllerSources.values.forEach { it.close() }
                controllerSources.clear()
                thread.quitSafely()
            }
        }

        /**
         * This device's own sensors. The fused gravity and linear acceleration sensors, when the
         * device has them, tell a tip from a swing far better than filtering the raw accelerometer
         * can (a slow swing and a slow tip read the same to it); the raw one is the fallback.
         */
        private fun openDevice(manager: SensorManager): Source? {
            val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val raw = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val source = Source(this, "this device", manager, screenAxes = true)
            val ok = if (gravity != null && linear != null) {
                manager.registerListener(source, gravity, SensorManager.SENSOR_DELAY_GAME, handler) and
                    manager.registerListener(source, linear, SensorManager.SENSOR_DELAY_GAME, handler)
            } else {
                raw != null && manager.registerListener(source, raw, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            if (!ok) {
                source.close()
                return null
            }
            return source
        }

        /** Match [controllerSources] to the connected controllers that report their own motion. */
        private fun rescanControllers() {
            if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val found = HashMap<Int, Pair<String, SensorManager>>()
            for (id in runCatching { InputDevice.getDeviceIds() }.getOrDefault(IntArray(0))) {
                val dev = runCatching { InputDevice.getDevice(id) }.getOrNull() ?: continue
                if (dev.isVirtual) continue
                val manager = runCatching { dev.sensorManager }.getOrNull() ?: continue
                val hasAccel = runCatching { manager.getSensorList(Sensor.TYPE_ACCELEROMETER).isNotEmpty() }.getOrDefault(false)
                if (hasAccel) found[id] = (dev.name ?: "controller") to manager
            }
            controllerSources.keys.filter { it !in found }.forEach { controllerSources.remove(it)?.close() }
            for ((id, entry) in found) {
                if (id in controllerSources) continue
                val (name, manager) = entry
                val sensor = runCatching { manager.getSensorList(Sensor.TYPE_ACCELEROMETER).firstOrNull() }.getOrNull() ?: continue
                val source = Source(this, name, manager, screenAxes = false)
                if (runCatching { manager.registerListener(source, sensor, SensorManager.SENSOR_DELAY_GAME, handler) }.getOrDefault(false)) {
                    controllerSources[id] = source
                }
            }
        }

        /** The source moving hardest now drives the sparkles. */
        fun publish() {
            if (closed) return
            var best: Source? = deviceSource
            for (s in controllerSources.values) {
                if (best == null || abs(s.push) > abs(best.push)) best = s
            }
            push = best?.push ?: 0f
            var hardest = deviceSource?.shake ?: 0f
            for (s in controllerSources.values) hardest = maxOf(hardest, s.shake)
            shake = hardest
        }
    }

    /**
     * One sensor stream. [screenAxes] for this device, whose axes belong to the display's natural
     * orientation and are turned into the player's left-right first (as in input/Sixaxis.kt). A
     * controller's axes are already its own: X to its right.
     */
    private inner class Source(
        private val session: Session,
        val name: String,
        private val manager: SensorManager,
        private val screenAxes: Boolean,
    ) : SensorEventListener {
        var push = 0f
            private set
        var shake = 0f
            private set

        // In g. Gravity is the reading at rest: +1 along whichever axis points up.
        private var gravityX = 0f
        private var linearX = 0f
        private var linearMagnitude = 0f
        private var lastNanos = 0L

        override fun onSensorChanged(event: SensorEvent) {
            val g = SensorManager.GRAVITY_EARTH
            val v = event.values
            val x = sideways(v[0], v[1]) / g
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> gravityX = x
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linearX = x
                    linearMagnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / g
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // The raw reading is gravity plus movement: a quarter-second low-pass keeps the
                    // gravity and leaves the movement.
                    val dt = if (lastNanos == 0L) 0f else ((event.timestamp - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
                    lastNanos = event.timestamp
                    val a = 1f - exp(-dt / GRAVITY_SECONDS)
                    gravityX += (x - gravityX) * if (dt == 0f) 1f else a
                    linearX = x - gravityX
                    // The whole-vector movement for the shake test: the reading's length away from 1 g
                    // misses a sideways swing, so the sideways part counts in full.
                    val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / g
                    linearMagnitude = maxOf(abs(mag - 1f), abs(linearX))
                }
                else -> return
            }

            // Swinging to the left reads as a push to the left at its start (the stop pushes back,
            // so the sparkles go where the pad went and settle). Tipping down on the left puts
            // gravity's pull to the left: the reading at rest points UP, so its sideways part is
            // positive when the left side is the low one, and the pull is the opposite way.
            push = deadZone(linearX, SWING_DEAD_ZONE) * SWING_GAIN - deadZone(gravityX, TIP_DEAD_ZONE) * TIP_GAIN
            shake = ((linearMagnitude - SHAKE_THRESHOLD) / SHAKE_RANGE).coerceIn(0f, 1f)
            session.publish()
        }

        /** The player's left-right component of a device-axis X, Y pair (in the source's frame). */
        private fun sideways(ax: Float, ay: Float): Float {
            if (!screenAxes) return ax
            // ROTATION_90: the device was turned counter-clockwise, so its +Y now points left.
            return when (displayRotation()) {
                Surface.ROTATION_90 -> -ay
                Surface.ROTATION_180 -> -ax
                Surface.ROTATION_270 -> ay
                else -> ax
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        fun close() {
            runCatching { manager.unregisterListener(this) }
        }
    }

    private companion object {
        const val TAG = "XmbSparkleMotion"

        /** Low-pass for gravity out of a raw accelerometer. */
        const val GRAVITY_SECONDS = 0.25f

        /** Hand tremor and sensor noise stay out. */
        const val SWING_DEAD_ZONE = 0.05f

        /** About 6 degrees: a handheld held roughly level stays still. */
        const val TIP_DEAD_ZONE = 0.1f

        const val SWING_GAIN = 1f
        const val TIP_GAIN = 0.5f

        /** The console's shake threshold (PARTICLES_UI.mnu "dshake thresh" 0.722145), in g. */
        const val SHAKE_THRESHOLD = 0.72f

        /** Past the threshold by this much is a full scatter. */
        const val SHAKE_RANGE = 0.8f

        fun deadZone(value: Float, zone: Float): Float = when {
            value > zone -> value - zone
            value < -zone -> value + zone
            else -> 0f
        }
    }
}

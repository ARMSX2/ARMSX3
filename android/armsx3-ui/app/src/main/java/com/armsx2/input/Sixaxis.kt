package com.armsx2.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.Surface
import android.view.WindowManager
import kotlin.math.sqrt

/**
 * Feeds real motion to the pads' SIXAXIS registers.
 *
 * Deliberately separate from [AndroidGyroscopeInput]. That one converts motion into STICK
 * movement (deadzone, smoothing, inversion, per-mode centring), which is exactly right for aim
 * and exactly wrong here: a game reading SIXAXIS wants the controller's actual attitude, not a
 * processed stick value. The two run side by side, which is also what a real DualShock 3 does:
 * it reports motion continuously whether or not you are also moving the sticks.
 *
 * Two sources, and a port takes exactly one of them:
 *
 *  - A controller with its own motion sensors (a DualShock 4 or DualSense, through
 *    InputDevice.getSensorManager on API 31 and up) feeds the player it is plugged in as. This
 *    used to be missing entirely: only the aim path had learned to read a pad's sensors, so
 *    shaking the controller in Ar Tonelico Qoga's battles did nothing while shaking the tablet
 *    worked.
 *  - Otherwise the device's own sensors feed player 1, which is what a handheld's built-in
 *    controls and the on-screen pad want. Those axes are fixed to the display's NATURAL
 *    orientation, so they are turned into screen axes first. The Odin 3's panel is natively
 *    portrait and runs rotated 90 degrees, so without that a vertical shake arrived as a roll
 *    and the game never saw a shake at all.
 *
 * Signs follow RPCS3's own DualShock 4 and DualSense handlers, which negate every axis of the
 * pad's frame (X right, Y out of the face, Z toward the player) to get the DS3's registers.
 */
class Sixaxis(context: Context) {

    companion object {
        /** DS3 rest: flat and still, one g down its Y axis (pad_types.h DEFAULT_MOTION_Y). */
        private const val REST_Y = -1f
    }

    private val appContext = context.applicationContext
    private val deviceSensors = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val main = Handler(Looper.getMainLooper())

    private val deviceFeed = DeviceFeed()
    private var deviceFeedRunning = false

    /**
     * Keyed by the InputDevice that owns the sensors. Everything below is touched on the main
     * thread only: sensor and input device callbacks already arrive there, and [start]/[stop] are
     * called from the VM thread, so they post their work rather than race the callbacks.
     */
    private val controllerFeeds = HashMap<Int, ControllerFeed>()

    /** What last fed each port, so the log says when it changes and not on every sample. */
    private val source = arrayOfNulls<String>(PadRouter.MAX_PADS)
    private var running = false

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = rescanControllers()
        override fun onInputDeviceRemoved(deviceId: Int) = rescanControllers()
        override fun onInputDeviceChanged(deviceId: Int) = rescanControllers()
    }

    /** False when nothing on this device could ever report motion. */
    fun start(): Boolean {
        val deviceHasMotion = deviceSensors?.let {
            it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                it.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        } == true
        if (!deviceHasMotion && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        main.post { startOnMain() }
        return true
    }

    fun stop() {
        main.post { stopOnMain() }
    }

    private fun startOnMain() {
        stopOnMain()
        running = true

        deviceSensors?.let { manager ->
            // TYPE_ACCELEROMETER rather than TYPE_GRAVITY: SIXAXIS is an accelerometer, so the
            // hand movement fused sensors remove is signal here, not noise. A shake is exactly that.
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                deviceFeedRunning = manager.registerListener(deviceFeed, it, SensorManager.SENSOR_DELAY_GAME) || deviceFeedRunning
            }
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                deviceFeedRunning = manager.registerListener(deviceFeed, it, SensorManager.SENSOR_DELAY_GAME) || deviceFeedRunning
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { inputManager?.registerInputDeviceListener(deviceListener, main) }
            rescanControllers()
        }
    }

    private fun stopOnMain() {
        if (!running) return
        running = false
        runCatching { inputManager?.unregisterInputDeviceListener(deviceListener) }
        if (deviceFeedRunning) {
            runCatching { deviceSensors?.unregisterListener(deviceFeed) }
            deviceFeedRunning = false
        }
        controllerFeeds.values.forEach { it.stop() }
        controllerFeeds.clear()

        // Park every port we fed at rest, so a game does not read the last motion forever.
        for (port in source.indices) {
            if (source[port] != null) {
                com.armsx3.Rpcs3Bridge.setPadMotion(port, 0f, REST_Y, 0f, 0f)
                source[port] = null
            }
        }
    }

    /** Match [controllerFeeds] to the controllers that are connected and carry motion sensors. */
    private fun rescanControllers() {
        if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val wanted = HashMap<Int, Pair<InputDevice, SensorManager>>()
        for (id in runCatching { InputDevice.getDeviceIds() }.getOrDefault(IntArray(0))) {
            val dev = runCatching { InputDevice.getDevice(id) }.getOrNull() ?: continue
            if (dev.isVirtual) continue
            val manager = runCatching { dev.sensorManager }.getOrNull() ?: continue
            val hasMotion = runCatching {
                manager.getSensorList(Sensor.TYPE_ACCELEROMETER).isNotEmpty() ||
                    manager.getSensorList(Sensor.TYPE_GYROSCOPE).isNotEmpty()
            }.getOrDefault(false)
            if (hasMotion) wanted[id] = dev to manager
        }

        val gone = controllerFeeds.keys.filter { it !in wanted }
        for (id in gone) controllerFeeds.remove(id)?.stop()

        for ((id, found) in wanted) {
            if (id in controllerFeeds) continue
            val (dev, manager) = found
            val feed = ControllerFeed(padNodeFor(dev), dev.name ?: "controller", manager)
            if (feed.start()) controllerFeeds[id] = feed
        }
    }

    /**
     * The node that claims a player slot for [dev]. One physical pad can enumerate as several
     * InputDevices (a DualSense adds touchpad and motion nodes) and PadRouter only ever claims
     * with the gamepad one, so the sensors are matched to it by descriptor.
     */
    private fun padNodeFor(dev: InputDevice): Int {
        if (dev.isPadNode()) return dev.id
        val descriptor = dev.descriptor ?: return dev.id
        for (id in runCatching { InputDevice.getDeviceIds() }.getOrDefault(IntArray(0))) {
            val other = runCatching { InputDevice.getDevice(id) }.getOrNull() ?: continue
            if (other.descriptor == descriptor && other.isPadNode()) return other.id
        }
        return dev.id
    }

    private fun InputDevice.isPadNode(): Boolean =
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /** True when a controller reporting its own motion currently holds [port]. */
    private fun controllerOwns(port: Int): Boolean =
        controllerFeeds.values.any { PadRouter.claimedPort(it.padNode) == port }

    /**
     * Send one sample in the pad frame: acceleration in g as the sensor measures it (Y reads +1
     * lying face up), and yaw in rad/s, counter-clockwise seen from above.
     */
    private fun send(port: Int, x: Float, y: Float, z: Float, yawLeft: Float, from: String) {
        if (port !in source.indices) return
        if (source[port] != from) {
            source[port] = from
            runCatching { net.rpcsx.RPCSX.instance.logAndroid("sixaxis: player ${port + 1} motion from $from") }
        }
        com.armsx3.Rpcs3Bridge.setPadMotion(port, -x, -y, -z, -yawLeft)
    }

    private inner class DeviceFeed : SensorEventListener {
        private var ax = 0f
        private var ay = 0f
        private var az = SensorManager.GRAVITY_EARTH

        // Measured up direction in device axes, normalised: the yaw projection below needs it.
        private var upX = 0f
        private var upY = 0f
        private var upZ = 1f
        private var yawLeft = 0f

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                    val len = sqrt(ax * ax + ay * ay + az * az)
                    if (len > 0.0001f) {
                        upX = ax / len; upY = ay / len; upZ = az / len
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    // The DS3 has ONE gyro axis and it is yaw. A fixed device axis would only be
                    // yaw for one way of holding the device; the rotation rate about the measured
                    // up direction is yaw however it is held, and needs no rotation handling.
                    yawLeft = event.values[0] * upX + event.values[1] * upY + event.values[2] * upZ
                }

                else -> return
            }

            val port = 0
            // A controller with its own sensors owns its player. Waving the screen must not fight it.
            if (controllerOwns(port)) return

            // Sensor axes belong to the display's natural orientation (X right, Y to the top
            // edge, Z out of the screen); turn them into what the player sees. ROTATION_90 means
            // the device was turned counter-clockwise, so its +X now points up and +Y left.
            val rotation = runCatching { @Suppress("DEPRECATION") windowManager?.defaultDisplay?.rotation }.getOrNull()
            val (right, up) = when (rotation) {
                Surface.ROTATION_90 -> -ay to ax
                Surface.ROTATION_180 -> -ax to -ay
                Surface.ROTATION_270 -> ay to -ax
                else -> ax to ay
            }

            // Held like a pad: screen right is its right, the screen faces out of its face, and
            // the top edge points away from the player.
            val g = SensorManager.GRAVITY_EARTH
            val degrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            send(port, right / g, az / g, -up / g, yawLeft, "this device's sensors (screen at $degrees)")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private inner class ControllerFeed(
        val padNode: Int,
        private val name: String,
        private val manager: SensorManager,
    ) : SensorEventListener {
        private var ax = 0f
        private var ay = SensorManager.GRAVITY_EARTH
        private var az = 0f
        private var yawLeft = 0f

        fun start(): Boolean {
            var ok = false
            runCatching {
                manager.getSensorList(Sensor.TYPE_ACCELEROMETER).firstOrNull()?.let {
                    ok = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || ok
                }
                manager.getSensorList(Sensor.TYPE_GYROSCOPE).firstOrNull()?.let {
                    ok = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || ok
                }
            }
            return ok
        }

        fun stop() {
            runCatching { manager.unregisterListener(this) }
        }

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                // Already in the pad's own frame: the kernel driver passes the controller's axes
                // through and Android keeps them.
                Sensor.TYPE_ACCELEROMETER -> {
                    ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                }
                // Y is the axis out of the pad's face, so its rate is the yaw a DS3's gyro reports.
                Sensor.TYPE_GYROSCOPE -> yawLeft = event.values[1]
                else -> return
            }

            // A pad that has not pressed anything yet is not a player yet, and claiming one from
            // a sensor stream would let a controller lying on the table take player 1.
            val port = PadRouter.claimedPort(padNode)
            if (port < 0) return

            val g = SensorManager.GRAVITY_EARTH
            send(port, ax / g, ay / g, az / g, yawLeft, "$name's own sensors")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}

package com.armsx2.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Feeds the phone's motion sensors to the pad's SIXAXIS registers.
 *
 * Deliberately separate from [AndroidGyroscopeInput]. That one converts motion into STICK
 * movement -- deadzone, smoothing, inversion, per-mode centring -- which is exactly right for aim
 * and exactly wrong here: a game reading SIXAXIS wants the controller's actual attitude, not a
 * processed stick value. The two run side by side, which is also what a real DualShock 3 does:
 * it reports motion continuously whether or not you are also moving the sticks.
 *
 * Why this exists at all: the native half was already written and connected to nothing.
 * `_rpcsx_setPadSensor` and `Rpcs3Bridge.setPadMotion` were complete, the pad advertised
 * CELL_PAD_CAPABILITY_SENSOR_MODE, and no caller ever drove them -- so m_sensors sat at its
 * DEFAULT_MOTION_* neutrals for the whole session. Titles that read the sticks appeared to have
 * working gyro; titles that read SIXAXIS saw a controller lying perfectly still. Killzone 3's
 * valve prompt and Ratchet & Clank ToD's flight sections are the visible cases.
 */
class Sixaxis(context: Context, private val port: Int = 0) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var accel: Sensor? = null
    private var gyro: Sensor? = null

    // Latest gravity direction, in device axes and normalised. Also doubles as the "which way is
    // up" reference the yaw projection below needs.
    private var upX = 0f
    private var upY = 0f
    private var upZ = 1f

    // DS3 axes, in g. Rest is (0, -1, 0): pad_types.h gives DEFAULT_MOTION_Y = 512 - 113, i.e.
    // minus one g on Y, with X and Z centred.
    private var dsX = 0f
    private var dsY = -1f
    private var dsZ = 0f

    private var yawRadPerSec = 0f
    private var running = false

    fun start(): Boolean {
        val manager = sensorManager ?: return false
        stop()

        // TYPE_ACCELEROMETER rather than TYPE_GRAVITY: SIXAXIS is an accelerometer, so the hand
        // movement fused sensors remove is signal here, not noise. A game shaking-detect wants it.
        accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accel == null && gyro == null) {
            return false
        }

        accel?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = true
        return true
    }

    fun stop() {
        val manager = sensorManager ?: return
        if (running) {
            manager.unregisterListener(this)
            running = false
            // Park the pad at rest so a game does not read the last motion forever.
            dsX = 0f; dsY = -1f; dsZ = 0f; yawRadPerSec = 0f
            push()
        }
        accel = null
        gyro = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0] / SensorManager.GRAVITY_EARTH
                val y = event.values[1] / SensorManager.GRAVITY_EARTH
                val z = event.values[2] / SensorManager.GRAVITY_EARTH

                // Device axes are X right, Y toward the top edge, Z out of the screen. Lying flat
                // and face up that reads (0, 0, +1). The DS3 lying flat reads (0, -1, 0), so the
                // screen normal maps to the pad's Y, negated, and the top edge maps to its Z.
                dsX = x
                dsY = -z
                dsZ = y

                val len = sqrt(x * x + y * y + z * z)
                if (len > 0.0001f) {
                    upX = x / len; upY = y / len; upZ = z / len
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                // The DS3 has ONE gyro axis and it is yaw -- PadHandler.cpp feeds m_sensors[3]
                // into gyro_y and pins gyro_x/gyro_z to zero.
                //
                // Taking a fixed device axis would only be yaw for one way of holding the device.
                // Projecting the rotation vector onto the measured up direction is yaw however it
                // is held, flat or upright, portrait or landscape, which is what a handheld needs.
                yawRadPerSec =
                    event.values[0] * upX + event.values[1] * upY + event.values[2] * upZ
            }

            else -> return
        }

        push()
    }

    private fun push() {
        com.armsx3.Rpcs3Bridge.setPadMotion(port, dsX, dsY, dsZ, yawRadPerSec)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

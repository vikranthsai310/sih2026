package org.itantra.app.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.abs

/**
 * Which way the handset is pointing, as degrees clockwise from true north.
 *
 * From the platform's rotation vector, which fuses the magnetometer, accelerometer and
 * gyroscope into something that does not swing about the way a raw compass does. The
 * reading is still smoothed here along the shortest arc, so an arrow drawn from it turns
 * rather than jumps, and a wobble of a few degrees does not make it shiver.
 *
 * Magnetic north is corrected to true north from the last position, when there is one:
 * the arrow is drawn against a bearing computed from positions, which are true-north
 * quantities, and in this part of the world the difference is a degree or two.
 */
class Heading(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile
    var degrees: Float? = null
        private set

    @Volatile
    private var declination = 0f

    /** Called on every reading, on the sensor thread, so an arrow can follow in real time. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var listening = false

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // No remapping: the azimuth is where the handset's top edge points, which
                // holds whether the phone is flat in a palm or tilted up to read. Remapped
                // for an upright phone, a flat one reported the direction of its back.
                SensorManager.getOrientation(rotation, orientation)
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat() + declination
                degrees = smooth(degrees, normalise(azimuth))
                onChanged?.invoke()
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    fun start(): Boolean {
        if (listening) return true
        val sm = manager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return false
        listening = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        return listening
    }

    fun stop() {
        if (!listening) return
        manager?.unregisterListener(listener)
        listening = false
        degrees = null
    }

    /** Corrects for the difference between magnetic and true north where the handset is. */
    fun calibrate(location: Location) {
        declination =
            GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination
    }

    companion object {
        /** How much of a new reading is taken each time: a turn settles in about a third of a second. */
        const val SMOOTHING = 0.25f

        fun normalise(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /** Along the shortest arc, so 359 → 1 is a two-degree step rather than a spin. */
        fun smooth(
            previous: Float?,
            next: Float,
            weight: Float = SMOOTHING,
        ): Float {
            if (previous == null) return next
            var delta = next - previous
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            // A large sudden change is a real turn, not noise: follow it faster.
            val w = if (abs(delta) > 45f) 0.6f else weight
            return normalise(previous + delta * w)
        }
    }
}

package org.itantra.app.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Which way the handset is pointing, as degrees clockwise from true north.
 *
 * ## Which way "pointing" is
 *
 * A phone has two candidates for "forward". Flat in a palm it is the top edge; held up
 * in front of the face it is the back of the phone, where the camera looks. The
 * platform's own `getOrientation` answers only the first: its azimuth is the top edge's
 * direction on the ground, and as the phone is raised towards vertical that projection
 * shrinks to nothing and the answer swings wildly — the reading that made the arrow
 * "not correct" when the handset was held up to be read. The remap Android offers for
 * an upright phone has the opposite flaw, reporting the direction of the back when the
 * phone is flat.
 *
 * So forward is computed here from the rotation matrix directly, as a blend of the two
 * axes weighted by how far the phone is raised: the top edge when it is flat, the back
 * when it is upright, and a proportionate mix of the two in between, which is continuous
 * because the two directions agree whenever the phone is simply tilted up. The arrow is
 * then right at every angle an operator actually holds a phone at, without a mode
 * switch and without a jump at the crossover.
 *
 * ## The sensors, and why there are two headings
 *
 * The rotation vector is the platform's fusion of magnetometer, accelerometer and
 * gyroscope, and it knows where north is. The *game* rotation vector is the same fusion
 * without the magnetometer: it knows nothing about north, but nothing magnetic can fool
 * it, and it follows a turn of the hand exactly. [HeadingFusion] takes the second as the
 * moment-to-moment reading and anchors it to the first only while the magnetic field
 * looks like the Earth's -- its strength and its dip against the geomagnetic model for
 * this position -- so a steel door frame swings the magnetic heading and not the arrow.
 * That is what the research on smartphone heading under magnetic anomalies does with a
 * Kalman filter, done with an offset and a gate. A handset without a game rotation
 * vector uses the magnetic heading alone, as before; one without a rotation vector at
 * all falls back to accelerometer and magnetometer combined here, which is the same
 * geometry with more jitter.
 *
 * The magnetometer's own accuracy is reported by the platform and surfaced as
 * [needsCalibration]: near iron, or after a change of magnetic surroundings, the sensor
 * knows it is unreliable and says so, and the fix is the operator moving the phone in a
 * figure of eight. An arrow that is silently thirty degrees out is worse than one that
 * asks for that.
 *
 * Magnetic north is corrected to true north from the last position when there is one.
 * The arrow is drawn against a bearing computed from positions, which are true-north
 * quantities, and in this part of the world the difference is a degree or two.
 */
class Heading(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** The magnetic rotation matrix, from the rotation vector or the fallback pair. */
    private val rotation = FloatArray(9)

    /** The gyroscope's rotation matrix, from the game rotation vector. */
    private val gyroRotation = FloatArray(9)

    private val fusion = HeadingFusion()

    @Volatile
    var degrees: Float? = null
        private set

    /**
     * The platform's estimate of how far the heading may be out, in degrees, when the
     * rotation vector offers one. Null when it does not.
     */
    @Volatile
    var errorDegrees: Float? = null
        private set

    /** The magnetometer has reported itself low or unreliable. A figure of eight fixes it. */
    @Volatile
    var needsCalibration: Boolean = false
        private set

    /**
     * The magnetic field here does not look like the Earth's, and the heading is being
     * carried by the gyroscope from the last place it did. Moving a few metres cures it.
     */
    @Volatile
    var magneticallyDisturbed: Boolean = false
        private set

    @Volatile
    private var declination = 0f

    @Volatile
    private var expectedStrengthMicroTesla: Float? = null

    @Volatile
    private var expectedDipDegrees: Float? = null

    /** Called on every reading, on the sensor thread, so an arrow can follow in real time. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var listening = false
    private var hasGyroHeading = false

    // The latest of each instrument, on the sensor thread.
    private var magneticDegrees: Float? = null
    private var gyroDegrees: Float? = null
    private var field: FloatArray? = null
    private var fieldTrusted = true

    // The fallback pair, when there is no rotation vector.
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, quaternion(event.values))
                        // values[4], when present and non-negative, is the platform's own
                        // estimate of the heading error, in radians.
                        errorDegrees =
                            event.values
                                .getOrNull(4)
                                ?.takeIf { it >= 0f }
                                ?.let { Math.toDegrees(it.toDouble()).toFloat() }
                        magneticDegrees = normalise(forwardAzimuth(rotation) + declination)
                        publish()
                    }
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(gyroRotation, quaternion(event.values))
                        gyroDegrees = smooth(gyroDegrees, forwardAzimuth(gyroRotation), GYRO_SMOOTHING)
                        publish()
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        field = event.values.copyOf(3)
                        judgeField()
                        if (!hasGyroHeading) {
                            geomagnetic = lowPass(event.values, geomagnetic)
                            fallback()
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravity = lowPass(event.values, gravity)
                        fallback()
                    }
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {
                // The rotation vector's accuracy is its magnetometer's: it is the one input
                // that can be wrong without knowing, and the one whose being wrong the
                // platform detects.
                val type = sensor?.type ?: return
                if (type != Sensor.TYPE_ROTATION_VECTOR && type != Sensor.TYPE_MAGNETIC_FIELD) return
                needsCalibration =
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                judgeField()
                onChanged?.invoke()
            }
        }

    /** Whether the latest raw field looks like the Earth's, against the model for this place. */
    private fun judgeField() {
        val b = field ?: return
        val strength = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        // The dip needs the handset's attitude, and the gyroscope's matrix gives one that
        // the field itself cannot have bent.
        val dip = if (hasGyroHeading && gyroDegrees != null) HeadingFusion.dipOf(gyroRotation, b) else null
        fieldTrusted =
            HeadingFusion.looksLikeEarth(
                strengthMicroTesla = strength,
                dipDegrees = dip,
                expectedStrengthMicroTesla = expectedStrengthMicroTesla,
                expectedDipDegrees = expectedDipDegrees,
                accuracyOk = !needsCalibration,
            )
    }

    private fun fallback() {
        if (hasGyroHeading) return
        val g = gravity ?: return
        val m = geomagnetic ?: return
        if (!SensorManager.getRotationMatrix(rotation, null, g, m)) return
        magneticDegrees = normalise(forwardAzimuth(rotation) + declination)
        publish()
    }

    private var readings = 0
    private var reportedAtMillis = 0L

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val fused = fusion.update(gyroDegrees, magneticDegrees, fieldTrusted, now)
        degrees =
            if (hasGyroHeading) {
                fused
            } else {
                // No gyroscope heading: the magnetic one, lightly smoothed as before.
                fused?.let { smooth(degrees, it) }
            }
        magneticallyDisturbed = hasGyroHeading && (!fieldTrusted || fusion.isHolding(now))
        readings++
        if (now - reportedAtMillis >= 1_000L) {
            android.util.Log.d(
                "Heading",
                "$readings readings/s, forward ${degrees?.toInt()}°, magnetic ${magneticDegrees?.toInt()}°, " +
                    "gyro ${gyroDegrees?.toInt()}° offset ${fusion.offsetDegrees?.toInt()}, " +
                    "field trusted $fieldTrusted, err ${errorDegrees?.toInt()}, cal ${!needsCalibration}",
            )
            readings = 0
            reportedAtMillis = now
        }
        onChanged?.invoke()
    }

    fun start(): Boolean {
        if (listening) return true
        val sm = manager ?: return false
        fusion.reset()
        magneticDegrees = null
        gyroDegrees = null
        field = null
        fieldTrusted = true
        val fused = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        listening =
            if (fused != null) {
                val ok = sm.registerListener(listener, fused, SensorManager.SENSOR_DELAY_GAME)
                hasGyroHeading = game != null && sm.registerListener(listener, game, SensorManager.SENSOR_DELAY_GAME)
                // The raw field, for the gate. Not a heading source when the vector is there.
                if (mag != null) sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
                ok
            } else {
                hasGyroHeading = false
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
                if (mag == null) return false
                sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME) &&
                    sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_GAME)
            }
        return listening
    }

    fun stop() {
        if (!listening) return
        manager?.unregisterListener(listener)
        listening = false
        hasGyroHeading = false
        degrees = null
        errorDegrees = null
        magneticallyDisturbed = false
        gravity = null
        geomagnetic = null
        magneticDegrees = null
        gyroDegrees = null
        field = null
    }

    /**
     * Corrects for the difference between magnetic and true north where the handset is,
     * and learns the field strength and dip expected there, for the gate.
     */
    fun calibrate(location: Location) {
        val model =
            GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            )
        declination = model.declination
        // Nanotesla from the model; microtesla from the sensor.
        expectedStrengthMicroTesla = model.fieldStrength / 1_000f
        expectedDipDegrees = model.inclination
    }

    companion object {
        /**
         * How much of a new reading is taken each time, on the fallback and no-gyroscope
         * paths. The rotation vector is already fused and steady, so this is light: a turn
         * is followed within a few readings, a tenth of a second, and a jitter of a degree
         * is halved.
         */
        const val SMOOTHING = 0.35f

        /** The gyroscope's heading is clean already; this only rounds off the last fraction of a degree. */
        const val GYRO_SMOOTHING = 0.6f

        private const val FALLBACK_LOW_PASS = 0.2f

        fun normalise(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /**
         * The rotation vector as the matrix routine wants it.
         *
         * Some handsets report five values -- the quaternion and a heading error -- and
         * some builds of the routine refuse more than four. Only ever the first four are
         * passed; three are left as three, because padding a fourth with zero would be
         * read as a real scalar part rather than one to be derived.
         */
        fun quaternion(values: FloatArray): FloatArray = if (values.size > 4) values.copyOf(4) else values

        /**
         * The direction the phone is pointing, from its rotation matrix, in degrees
         * clockwise from magnetic north.
         *
         * The matrix maps device axes to world axes (east, north, up), so its columns are
         * the device's own axes in world terms: column 1 is the top edge, column 2 the
         * screen normal. "Forward" is the top edge when the phone is flat and the back of
         * the phone when it is upright, blended by how far the top edge is raised, which
         * is the up-component of the top-edge axis. When the phone is tilted about its
         * short axis the two directions agree, so the blend costs nothing there; when it
         * is rolled or held at an odd angle the blend takes the better-conditioned one.
         */
        fun forwardAzimuth(r: FloatArray): Float {
            val topEast = r[1]
            val topNorth = r[4]
            val topUp = r[7]
            val backEast = -r[2]
            val backNorth = -r[5]
            // 0 flat, 1 upright, eased so the crossover is gentle.
            val raised = topUp.coerceIn(0f, 1f)
            val w = raised * raised * (3f - 2f * raised)
            var east = topEast * (1f - w) + backEast * w
            var north = topNorth * (1f - w) + backNorth * w
            // Face-down flat, or a matrix with nothing horizontal in it: fall back to
            // whichever axis has any horizontal projection at all rather than atan2(0, 0).
            if (hypot(east, north) < 1e-3f) {
                east = if (abs(topUp) < 0.99f) topEast else backEast
                north = if (abs(topUp) < 0.99f) topNorth else backNorth
            }
            return Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
        }

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
            val w = if (abs(delta) > 30f) 0.7f else weight
            return normalise(previous + delta * w)
        }

        private fun lowPass(
            input: FloatArray,
            previous: FloatArray?,
        ): FloatArray {
            if (previous == null) return input.copyOf()
            for (i in previous.indices) previous[i] += (input[i] - previous[i]) * FALLBACK_LOW_PASS
            return previous
        }
    }
}

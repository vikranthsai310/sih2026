package org.itantra.app.platform

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/**
 * A heading that turns with the gyroscope and is anchored by the magnetometer only when
 * the magnetometer can be believed.
 *
 * ## The two instruments
 *
 * The magnetometer knows where north is and is wrong whenever there is iron about -- a
 * car, a steel door frame, the reinforcing in a floor slab -- and it does not know it is
 * wrong. The gyroscope knows nothing about north and is never fooled by iron: it reports
 * every turn of the handset exactly, and drifts by a degree or so a minute. Each is what
 * the other lacks, and the fusion is the obvious one. The gyroscope's own heading (the
 * platform's *game* rotation vector, which is gyroscope and accelerometer without the
 * magnetometer) is taken as the moment-to-moment reading, and the difference between it
 * and the magnetic heading is tracked slowly, as an offset, **only while the magnetic
 * field looks like the Earth's**. Turn the handset and the arrow turns at once, from the
 * gyroscope; walk past a steel cabinet and the magnetic heading swings thirty degrees,
 * the field no longer looks like the Earth's, the offset is frozen, and the arrow stays
 * where it was.
 *
 * ## What "looks like the Earth's" means
 *
 * The Earth's field at a place has a known strength and a known dip -- the angle at
 * which it points into the ground -- and the platform's geomagnetic model gives both for
 * a position. Iron nearby changes one or the other or both. So a reading is trusted when
 * its strength is within a fifth of the expected strength and its dip within ten degrees
 * of the expected dip, and when the platform's own accuracy flag for the magnetometer is
 * not raised. Without a position there is no expected value, and the test falls back to
 * the range the Earth's field takes anywhere: 20 to 70 microtesla.
 *
 * Pure arithmetic, so it is tested on the JVM; [Heading] owns the sensors and calls it.
 */
class HeadingFusion(
    /** How long a trusted magnetic reading takes to pull the offset most of the way. */
    private val settleSeconds: Double = SETTLE_SECONDS,
) {
    /** Magnetic heading minus gyroscope heading, in degrees, or null before the first trusted reading. */
    var offsetDegrees: Float? = null
        private set

    private var lastTrustedAtMillis = 0L

    /** When the offset was last moved by a trusted reading, or 0. */
    val trustedAtMillis: Long get() = lastTrustedAtMillis

    /**
     * Folds one pair of readings in and returns the heading to show.
     *
     * @param gyroDegrees the gyroscope's heading, in degrees from an arbitrary origin, or
     *   null on a handset without one
     * @param magneticDegrees the magnetometer's heading, degrees from true north, or null
     * @param trusted whether the magnetic field looked like the Earth's for this reading
     * @return degrees from true north, or null when nothing can be said yet
     */
    fun update(
        gyroDegrees: Float?,
        magneticDegrees: Float?,
        trusted: Boolean,
        nowMillis: Long,
    ): Float? {
        if (gyroDegrees == null) return magneticDegrees
        if (magneticDegrees != null && trusted) {
            val sample = arc(magneticDegrees - gyroDegrees)
            val current = offsetDegrees
            offsetDegrees =
                if (current == null) {
                    sample
                } else {
                    val dt = (nowMillis - lastTrustedAtMillis).coerceAtLeast(0L) / 1000.0
                    val alpha = (1.0 - exp(-dt / settleSeconds)).toFloat()
                    normalise(current + arc(sample - current) * alpha)
                }
            lastTrustedAtMillis = nowMillis
        }
        val offset = offsetDegrees ?: return magneticDegrees
        return normalise(gyroDegrees + offset)
    }

    /** Whether the heading is being carried by the gyroscope alone right now. */
    fun isHolding(nowMillis: Long): Boolean =
        offsetDegrees != null && nowMillis - lastTrustedAtMillis > HOLD_NOTE_AFTER_MILLIS

    fun reset() {
        offsetDegrees = null
        lastTrustedAtMillis = 0L
    }

    companion object {
        const val SETTLE_SECONDS = 2.0

        /** After this long without a trusted reading the screen says the heading is held. */
        const val HOLD_NOTE_AFTER_MILLIS = 3_000L

        /** The Earth's field is at least this strong anywhere people live, in microtesla. */
        const val EARTH_MIN_MICROTESLA = 20f

        /** And at most this strong. */
        const val EARTH_MAX_MICROTESLA = 70f

        /** A reading further from the expected strength than this fraction of it is iron. */
        const val STRENGTH_TOLERANCE = 0.20f

        /** A reading whose dip is further from the expected dip than this is iron. */
        const val DIP_TOLERANCE_DEG = 10f

        /**
         * Whether a magnetic reading looks like the Earth's field.
         *
         * @param strengthMicroTesla the measured field's magnitude
         * @param dipDegrees the measured field's angle below the horizontal, positive
         *   downwards, or null when the handset's attitude is unknown
         * @param expectedStrengthMicroTesla the model's strength here, or null without a position
         * @param expectedDipDegrees the model's dip here, or null without a position
         * @param accuracyOk whether the platform's accuracy flag for the magnetometer is
         *   at least medium
         */
        fun looksLikeEarth(
            strengthMicroTesla: Float,
            dipDegrees: Float?,
            expectedStrengthMicroTesla: Float?,
            expectedDipDegrees: Float?,
            accuracyOk: Boolean,
        ): Boolean {
            if (!accuracyOk) return false
            if (expectedStrengthMicroTesla == null || expectedStrengthMicroTesla <= 0f) {
                return strengthMicroTesla in EARTH_MIN_MICROTESLA..EARTH_MAX_MICROTESLA
            }
            val strengthOff = abs(strengthMicroTesla - expectedStrengthMicroTesla)
            if (strengthOff > expectedStrengthMicroTesla * STRENGTH_TOLERANCE) return false
            if (dipDegrees != null && expectedDipDegrees != null) {
                if (abs(dipDegrees - expectedDipDegrees) > DIP_TOLERANCE_DEG) return false
            }
            return true
        }

        /**
         * The dip of a field measured in the handset's frame, given the handset's
         * rotation matrix: the field is rotated into the world frame and the angle
         * between it and the horizontal taken. Positive downwards, as the model reports it.
         */
        fun dipOf(
            rotation: FloatArray,
            field: FloatArray,
        ): Float {
            val east = rotation[0] * field[0] + rotation[1] * field[1] + rotation[2] * field[2]
            val north = rotation[3] * field[0] + rotation[4] * field[1] + rotation[5] * field[2]
            val up = rotation[6] * field[0] + rotation[7] * field[1] + rotation[8] * field[2]
            return Math.toDegrees(atan2(-up.toDouble(), hypot(east, north).toDouble())).toFloat()
        }

        fun normalise(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /** Degrees into −180..180. */
        fun arc(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}

package org.itantra.app.ui

/**
 * One unit on the channel, as this handset last heard it.
 *
 * @param name what the unit calls itself, or `node N` until it has said
 * @param heardMillisAgo since its last authenticated frame
 * @param rssi the last signal reading in dBm, or null when this handset has none
 * @param signalMillisAgo since that reading
 * @param hasPosition whether it has told this handset where it is
 */
data class UnitInfo(
    val src: Int,
    val name: String,
    val heardMillisAgo: Long,
    val rssi: Int? = null,
    val signalMillisAgo: Long? = null,
    val hasPosition: Boolean = false,
    val openLine: Boolean = false,
    val beaconing: Boolean = false,
) {
    /** Signal as five bars, from the last reading. Null when there is none. */
    val bars: Int?
        get() =
            rssi?.let {
                when {
                    it >= -55 -> 5
                    it >= -65 -> 4
                    it >= -75 -> 3
                    it >= -85 -> 2
                    else -> 1
                }
            }
}

/**
 * What the locate screen shows while walking towards one unit.
 *
 * @param proximity 0 (far, or unknown) to 1 (within arm's reach), from signal strength
 * @param estimatedMetres from signal strength; a rough figure, and said so on screen
 * @param estimatedCentimetres the same figure at the resolution the last few metres want
 * @param spreadCentimetres the nearest and farthest the last few readings put it, when
 *   there are enough of them to say: the honest width of the estimate
 * @param gpsMetres from both positions, when both are known
 * @param arrowDeg the angle the arrow is drawn at, clockwise from the top of the screen,
 *   or null when there is no compass. It turns with the handset whatever it points at.
 * @param arrowMode what the arrow means: the target, the last direction it had, or north
 * @param arrowSpreadDeg half the width of the arrow's own uncertainty, from the combined
 *   GPS error over the distance, when it points at the target
 * @param ownAccuracyMetres this handset's own fix error, averaged
 * @param headingCorrectionDeg what the walk has taught about the compass, when in force
 * @param sweptDeg how much of a full circle the signal has been sampled over, for the
 *   sweep; the screen shows it as progress while the operator turns
 * @param relativeBearingDeg where the target lies relative to the way this handset is
 *   pointing, clockwise, or null when either position or the compass is missing
 * @param bearingDeg the target's true bearing from here, or null without both positions
 * @param compassErrorDeg the platform's own estimate of the compass error, when it has one
 * @param compassNeedsCalibration the magnetometer has said it is unreliable
 * @param lost no signal for a while
 */
data class LocateState(
    val target: Int,
    val name: String,
    val proximity: Float,
    val rssi: Int?,
    val estimatedMetres: Int?,
    val estimatedCentimetres: Int? = null,
    val spreadCentimetres: IntRange? = null,
    val gpsMetres: Int?,
    val arrowDeg: Float? = null,
    val arrowMode: ArrowMode = ArrowMode.NONE,
    val arrowSpreadDeg: Float? = null,
    val targetAccuracyMetres: Int?,
    val ownAccuracyMetres: Int? = null,
    val headingCorrectionDeg: Float? = null,
    val sweptDeg: Int = 0,
    val relativeBearingDeg: Float?,
    val headingDeg: Float?,
    val bearingDeg: Float? = null,
    val compassErrorDeg: Float? = null,
    val compassNeedsCalibration: Boolean = false,
    /** Iron nearby: the heading is being carried by the gyroscope from the last clean place. */
    val compassDisturbed: Boolean = false,
    val lost: Boolean,
    /** Whether the target has answered the request and is beaconing. */
    val beaconing: Boolean,
    /** What is missing for the arrow: permission, our fix, their fix, the compass. */
    val arrowNote: String?,
    val sirenOn: Boolean,
    /** Whether the signal has risen or fallen over the last few seconds. Null when lost. */
    val trend: Trend? = null,
    /**
     * Whether the distance figure is on a model fitted to this pair of handsets while the
     * positions could vouch for the distance, rather than on the assumed one.
     */
    val distanceCalibrated: Boolean = false,
) {
    val arrowAtTarget: Boolean get() = arrowMode == ArrowMode.TARGET
}

/** The signal over the last few seconds, as a word: what the siren says to the ear. */
enum class Trend { CLOSING, STEADY, FURTHER }

/** What the arrow on the locate screen is pointing at. */
enum class ArrowMode {
    /** No compass reading yet: nothing can be drawn. */
    NONE,

    /** The target, from the two positions. */
    TARGET,

    /** The direction the signal was strongest in, from a turn on the spot. */
    SWEEP,

    /** The direction the signal has risen in as the operator walked. Coarse. */
    WALK,

    /** The direction the target was in when the fixes were last far enough apart to say. */
    LAST_KNOWN,

    /** North: no direction has ever been known on this walk. */
    NORTH,
}

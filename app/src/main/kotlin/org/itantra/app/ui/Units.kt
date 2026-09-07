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
 * @param gpsMetres from both positions, when both are known
 * @param relativeBearingDeg where the target lies relative to the way this handset is
 *   pointing, clockwise, or null when either position or the compass is missing
 * @param lost no signal for a while
 */
data class LocateState(
    val target: Int,
    val name: String,
    val proximity: Float,
    val rssi: Int?,
    val estimatedMetres: Int?,
    val gpsMetres: Int?,
    val targetAccuracyMetres: Int?,
    val relativeBearingDeg: Float?,
    val headingDeg: Float?,
    val lost: Boolean,
    /** Whether the target has answered the request and is beaconing. */
    val beaconing: Boolean,
    /** What is missing for the arrow: permission, our fix, their fix, the compass. */
    val arrowNote: String?,
    val sirenOn: Boolean,
)

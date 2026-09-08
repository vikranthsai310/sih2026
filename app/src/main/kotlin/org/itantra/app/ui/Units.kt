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
 * @param proximity 0 (far, or unknown) to 1 (within arm's reach), from the calibrated
 *   distance on a logarithmic scale: what both sounds follow
 * @param estimatedMetres from signal strength; a rough figure, and said so on screen
 * @param estimatedCentimetres the same figure at the resolution the last few metres want
 * @param spreadCentimetres the nearest and farthest the last few readings put it, when
 *   there are enough of them to say: the honest width of the estimate
 * @param gpsMetres from both positions, when both are known
 * @param gpsApart whether the two positions are further apart than their combined error,
 *   so that [gpsMetres] is a distance and not noise; the screen shows it large then
 * @param ownAccuracyMetres this handset's own fix error, averaged
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
    val gpsApart: Boolean = false,
    val targetAccuracyMetres: Int?,
    val ownAccuracyMetres: Int? = null,
    val lost: Boolean,
    /** Whether the target has answered the request and is beaconing. */
    val beaconing: Boolean,
    /** What is missing or what to do: the target's answer, a position, the GPS overlap. */
    val note: String?,
    /** Which handset makes the sound the searcher follows. */
    val sound: SoundFrom = SoundFrom.THIS_PHONE,
    /** Whether the target is being asked to chirp: [sound] is [SoundFrom.THEIR_PHONE]. */
    val theirSoundAsked: Boolean = false,
    /** Whether the target says, in its own presence, that it is chirping right now. */
    val theirChirping: Boolean = false,
    /** Whether the signal has risen or fallen over the last few seconds. Null when lost. */
    val trend: Trend? = null,
    /**
     * Whether the distance figure is on a model fitted to this pair of handsets while the
     * positions could vouch for the distance, rather than on the assumed one.
     */
    val distanceCalibrated: Boolean = false,
)

/** The signal over the last few seconds, as a word: what the siren says to the ear. */
enum class Trend { CLOSING, STEADY, FURTHER }

/**
 * Which handset sounds during a search.
 *
 * The searcher's own siren says *how close*, by rate. The target's chirp says *which
 * way*, because two ears place a sound to a few degrees where no radio on a handset can,
 * and it quickens too as the searcher closes in. Both are offered and the operator
 * picks: their own phone when the target must stay quiet, the target's when the last
 * metres matter more than the target's silence.
 */
enum class SoundFrom {
    /** The siren on this handset, faster as the signal rises. */
    THIS_PHONE,

    /** The target chirps, from the moment it hears the request. */
    THEIR_PHONE,

    /** Silence. The screen alone. */
    OFF,
}

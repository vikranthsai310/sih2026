package org.itantra.app.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A direction from walking: the way the signal has been rising as the operator moves.
 *
 * ## The information the phone has and was not using
 *
 * Inside the last ten or fifteen metres the two positions overlap and the arrow can only
 * repeat where the target *was*. The phone still knows two things every second: which
 * way it is being carried -- the receiver's course over the ground, the same reading the
 * compass is checked against -- and whether the signal is getting stronger or weaker.
 * Walk north and the signal rises: the target is northward. Walk east and it falls: not
 * east. That is the oldest search there is, warmer and colder, and a phone can do it
 * without being told to.
 *
 * ## How the samples are combined
 *
 * Each stretch of walking between two fixes gives a direction and a gain: decibels per
 * metre walked that way. The gains are summed as vectors, so a stretch that made the
 * signal rise pulls the estimate towards its heading and one that made it fall pushes
 * away. The direction of the sum is the estimate; how much of the total gain survived
 * the summing -- the resultant over the sum of magnitudes -- is how consistent the
 * stretches were, which is the estimate's own doubt. Four stretches that all agree give a
 * narrow answer; four that cancel give none.
 *
 * ## What it is not
 *
 * Coarse: a quadrant, from a walk of a few metres, and only while walking. It says
 * nothing standing still, and nothing until the signal has actually changed by more than
 * its own flicker. It is offered as the arrow's source only when nothing better exists,
 * and pulled into the better sources by their inverse spreads when they do. Samples age
 * out over half a minute, because the gradient at the last corner is not the gradient at
 * this one.
 */
class WalkGradient(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val capacity: Int = CAPACITY,
) {
    /** One stretch of walking: which way, and what it did to the signal, per metre. */
    private class Stretch(val courseDeg: Float, val gainDbPerMetre: Double, val atMillis: Long)

    private val stretches = ArrayDeque<Stretch>()

    private var lastRssi: Double? = null
    private var lastAtMillis = 0L

    val sampleCount: Int get() = stretches.size

    fun clear() {
        stretches.clear()
        lastRssi = null
        lastAtMillis = 0L
    }

    /**
     * Records the end of a stretch of walking.
     *
     * @param courseDeg the direction walked, clockwise from true north, as the receiver
     *   reported it for this fix
     * @param metres how far, since the previous stretch ended
     * @param rssi the smoothed signal now
     */
    fun walked(
        courseDeg: Float,
        metres: Double,
        rssi: Double,
        nowMillis: Long,
    ) {
        val previous = lastRssi
        lastRssi = rssi
        lastAtMillis = nowMillis
        if (previous == null) return
        if (metres < MIN_STRETCH_METRES) return
        stretches.addLast(Stretch(courseDeg, (rssi - previous) / metres, nowMillis))
        while (stretches.size > capacity) stretches.removeFirst()
        prune(nowMillis)
    }

    /** The operator stopped, or the signal was lost: the next stretch starts fresh. */
    fun interrupt() {
        lastRssi = null
        lastAtMillis = 0L
    }

    /** A direction and its spread, or null when the walk has not said enough yet. */
    fun estimate(nowMillis: Long): Locator.Companion.Sweep? {
        prune(nowMillis)
        if (stretches.size < MIN_STRETCHES) return null
        var x = 0.0
        var y = 0.0
        var total = 0.0
        for (s in stretches) {
            val r = Math.toRadians(s.courseDeg.toDouble())
            x += s.gainDbPerMetre * sin(r)
            y += s.gainDbPerMetre * cos(r)
            total += abs(s.gainDbPerMetre)
        }
        if (total < MIN_TOTAL_GAIN) return null
        val resultant = sqrt(x * x + y * y)
        val consistency = resultant / total
        if (consistency < MIN_CONSISTENCY) return null
        val bearing = Math.toDegrees(atan2(x, y)).toFloat()
        val spread = (MIN_SPREAD_DEG + (MAX_SPREAD_DEG - MIN_SPREAD_DEG) * (1.0 - consistency)).toFloat()
        return Locator.Companion.Sweep(normalise(bearing), spread)
    }

    private fun prune(nowMillis: Long) {
        while (stretches.isNotEmpty() && nowMillis - stretches.first().atMillis > windowMillis) stretches.removeFirst()
    }

    private fun normalise(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    companion object {
        /** Half a minute: the gradient at the last corner is not the gradient at this one. */
        const val WINDOW_MILLIS = 30_000L
        const val CAPACITY = 40

        /** A step is not a stretch: the fix has to have moved. */
        const val MIN_STRETCH_METRES = 0.7

        const val MIN_STRETCHES = 4

        /**
         * The signal has to have changed by more than its own flicker over the walk before
         * its gradient means anything. dB per metre, summed over the stretches.
         */
        const val MIN_TOTAL_GAIN = 0.8

        /** Below this the stretches contradict each other and there is no direction. */
        const val MIN_CONSISTENCY = 0.4

        const val MIN_SPREAD_DEG = 30.0
        const val MAX_SPREAD_DEG = 100.0
    }
}

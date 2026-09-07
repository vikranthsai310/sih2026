package org.itantra.app.engine

import kotlin.math.log10

/**
 * Learns how *this* pair of handsets loses signal over distance, while the distance is
 * still known, so the last few metres -- where it is not -- are reckoned on a model fitted
 * to them rather than on a guess.
 *
 * ## The problem this solves
 *
 * A signal figure turns into metres through two numbers: the strength expected at one
 * metre and the rate it falls off with distance. Both are assumed. The sender's own
 * transmit power narrows the first when the advertising header carries it, but the
 * antenna, the case, the hand around the phone and the ground under it are all still
 * guesses, and two phones of different make differ by ten decibels at the same range --
 * the difference between "one metre" and "three". In a search that ends with the operator
 * looking for someone under a collapsed roof, three metres is the wrong room.
 *
 * ## What is known, and when
 *
 * Far from the target the two positions are far enough apart for their distance to be
 * trusted -- that is exactly when the arrow points -- and every signal reading taken then
 * comes with a distance the receivers agree on. Those pairs are the calibration: a
 * straight line through (log distance, signal) has the one-metre strength as its intercept
 * and the loss rate as its slope. It is fitted on the way in, and by the time the
 * positions are too close to tell apart and the siren has taken over, the metres the
 * screen shows are the metres this phone measured against that phone, in this field.
 *
 * ## Why it is careful
 *
 * A fit through points that are all at nearly the same distance has a slope that is
 * mostly noise, so the slope is taken only when the samples span at least a doubling of
 * distance; short of that the slope stays assumed and only the intercept is learned,
 * which is the number that matters more. A fit that lands outside what physics allows --
 * a loss rate below free space or above a corridor of steel, a one-metre strength no
 * handset produces -- is rejected whole rather than clamped, because a clamped wrong fit
 * looks exactly like a right one. Samples age out, so a walk that crosses from a field
 * into a building is re-fitted to the building.
 */
class PathLossFit(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val capacity: Int = CAPACITY,
) {
    private class Sample(val logMetres: Double, val rssi: Double, val atMillis: Long)

    private val samples = ArrayDeque<Sample>()

    /** The fitted signal at one metre in dBm, or null before there is a fit. */
    var referenceDbm: Double? = null
        private set

    /** The fitted loss exponent, or null when only the reference could be learned. */
    var exponent: Double? = null
        private set

    val sampleCount: Int get() = samples.size

    val isFitted: Boolean get() = referenceDbm != null

    fun clear() {
        samples.clear()
        referenceDbm = null
        exponent = null
    }

    /**
     * Offers one reading taken at a distance the positions can vouch for.
     *
     * @param metres the distance between the two position estimates
     * @param errorMetres their combined error; the reading is refused unless [metres] is
     *   several times this, because a distance inside its own error is not a distance
     * @param rssi the smoothed signal at that moment
     */
    fun learn(
        metres: Double,
        errorMetres: Double,
        rssi: Double,
        nowMillis: Long,
    ) {
        if (metres < errorMetres * MIN_ERROR_RATIO) return
        if (metres < MIN_METRES || metres > MAX_METRES) return
        samples.addLast(Sample(log10(metres), rssi, nowMillis))
        while (samples.size > capacity) samples.removeFirst()
        prune(nowMillis)
        refit()
    }

    private fun prune(nowMillis: Long) {
        while (samples.isNotEmpty() && nowMillis - samples.first().atMillis > windowMillis) samples.removeFirst()
    }

    /**
     * Least squares of signal on log-distance: `rssi = reference − 10·n·log₁₀(d)`.
     *
     * The slope is believed only over a span of distances; otherwise the exponent is held
     * at the assumed value and the reference alone is fitted through the points.
     */
    private fun refit() {
        if (samples.size < MIN_SAMPLES) return
        val n = samples.size.toDouble()
        val meanX = samples.sumOf { it.logMetres } / n
        val meanY = samples.sumOf { it.rssi } / n
        val span = samples.maxOf { it.logMetres } - samples.minOf { it.logMetres }
        val sxx = samples.sumOf { (it.logMetres - meanX) * (it.logMetres - meanX) }

        var fittedExponent: Double? = null
        val reference: Double
        if (span >= MIN_LOG_SPAN && sxx > 0.0) {
            val sxy = samples.sumOf { (it.logMetres - meanX) * (it.rssi - meanY) }
            val slope = sxy / sxx
            val candidate = -slope / 10.0
            if (candidate !in MIN_EXPONENT..MAX_EXPONENT) return
            fittedExponent = candidate
            reference = meanY - slope * meanX
        } else {
            // Intercept only, with the assumed slope through the centroid.
            reference = meanY + 10.0 * Locator.PATH_LOSS_EXPONENT * meanX
        }
        if (reference !in MIN_REFERENCE..MAX_REFERENCE) return
        referenceDbm = reference
        exponent = fittedExponent
    }

    companion object {
        /** Three minutes: a walk in from a hundred metres, and no longer. */
        const val WINDOW_MILLIS = 180_000L
        const val CAPACITY = 60

        /** A distance is a distance only well outside the positions' own error. */
        const val MIN_ERROR_RATIO = 3.0

        /** Inside this GPS says nothing useful; beyond it the signal is near the noise floor. */
        const val MIN_METRES = 6.0
        const val MAX_METRES = 90.0

        const val MIN_SAMPLES = 6

        /** A doubling of distance, in log₁₀: below this a slope is mostly noise. */
        const val MIN_LOG_SPAN = 0.3

        /** Free space is 2; a steel corridor approaches 4. Outside is not a fit. */
        const val MIN_EXPONENT = 1.6
        const val MAX_EXPONENT = 4.2

        /** No handset is heard louder than −40 dBm at a metre, nor quieter than −78. */
        const val MIN_REFERENCE = -78.0
        const val MAX_REFERENCE = -40.0
    }
}

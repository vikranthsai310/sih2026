package org.itantra.proto

/**
 * Establishes a common time reference between two handsets, so end-to-end latency can
 * be measured rather than estimated.
 *
 * ## Why this is needed at all
 *
 * The headline claim is a delay measured **across two devices**: audio out on B minus
 * speech end on A. Two Android handsets do not share a clock — `System.nanoTime()` is
 * measured from an arbitrary origin on each — so subtracting one from the other yields
 * a meaningless number that can even be negative. Timing the demo with a stopwatch
 * instead is worse: human reaction time is roughly 250 ms against a target budget of
 * 800 ms, so the instrument would be a third of the measurement.
 *
 * ## The exchange
 *
 * Four `HEARTBEAT` round trips, each yielding four timestamps:
 *
 * ```
 *   A ──t1──────────────►  t2  B          offset = ((t2-t1) + (t3-t4)) / 2
 *                          │              delay  = (t4-t1) - (t3-t2)
 *   A  t4 ◄──────────────t3    B
 * ```
 *
 * The estimator assumes the outbound and return paths take equal time. They do not,
 * exactly, and the error in the offset is bounded by half the path asymmetry — a few
 * milliseconds over Bluetooth, well inside a 800–1200 ms budget.
 *
 * `docs/EVALUATION.md` section 4, "How end-to-end is measured". Task **W3.10**.
 */
class ClockSync(
    private val requiredSamples: Int = REQUIRED_SAMPLES,
    /**
     * How many round trips are kept. The exchange runs for as long as two units are on
     * the channel, and two `nanoTime` clocks drift apart by milliseconds an hour, so the
     * offset has to follow the recent samples rather than average over the whole day.
     */
    private val maxSamples: Int = MAX_SAMPLES,
) {
    init {
        require(maxSamples >= requiredSamples) { "window of $maxSamples cannot hold $requiredSamples samples" }
    }

    /**
     * One completed round trip. All four timestamps are in nanoseconds; [t1] and [t4]
     * come from the local clock, [t2] and [t3] from the remote one.
     */
    data class Sample(val t1: Long, val t2: Long, val t3: Long, val t4: Long) {
        /** How far the remote clock is ahead of the local one, in nanoseconds. */
        val offsetNanos: Long get() = ((t2 - t1) + (t3 - t4)) / 2

        /** Round-trip time excluding the time the remote spent turning the reply around. */
        val delayNanos: Long get() = (t4 - t1) - (t3 - t2)

        /**
         * A round trip that appears to take negative time, or in which the remote
         * replied before it received, cannot be used: one of the clocks stepped
         * mid-exchange. Discarding it is right — a bad sample poisons the offset and
         * therefore every latency figure derived from it.
         */
        val isUsable: Boolean get() = delayNanos >= 0 && t4 >= t1 && t3 >= t2
    }

    private val samples = ArrayList<Sample>()

    val sampleCount: Int get() = samples.size

    /** True once enough usable round trips have completed for [offsetNanos] to be valid. */
    val isSynchronised: Boolean get() = samples.size >= requiredSamples

    /** @return true if the sample was usable and recorded. */
    fun add(sample: Sample): Boolean {
        if (!sample.isUsable) return false
        if (samples.size >= maxSamples) samples.removeAt(0)
        samples += sample
        return true
    }

    fun reset() = samples.clear()

    /**
     * The median offset over the completed round trips.
     *
     * Median rather than mean because Bluetooth round trips are occasionally delayed by
     * tens of milliseconds while the radio is busy, and a single such outlier drags a
     * mean far more than it moves a median.
     *
     * @throws IllegalStateException if called before [isSynchronised]. Reporting a
     *   latency figure derived from an unsynchronised clock is exactly the error this
     *   class exists to prevent, so it fails loudly rather than returning zero.
     */
    fun offsetNanos(): Long {
        check(isSynchronised) {
            "clock not synchronised: ${samples.size} of $requiredSamples round trips"
        }
        return median(samples.map { it.offsetNanos })
    }

    fun offsetMillis(): Long = offsetNanos() / 1_000_000

    /** Median one-way delay — the transport's contribution, useful on its own. */
    fun oneWayDelayNanos(): Long {
        check(isSynchronised) { "clock not synchronised" }
        return median(samples.map { it.delayNanos }) / 2
    }

    /**
     * Converts a timestamp taken on the remote handset into this handset's clock.
     *
     * This is the whole point: with it, `tAudio` on the receiver and `tMic` on the
     * sender become comparable and their difference is the end-to-end figure.
     */
    fun toLocalNanos(remoteNanos: Long): Long = remoteNanos - offsetNanos()

    companion object {
        /** Four round trips, per `docs/EVALUATION.md` section 4. */
        const val REQUIRED_SAMPLES = 4

        /** A rolling window of the last eight round trips; a minute or so at the live cadence. */
        const val MAX_SAMPLES = 8

        /** Even counts take the mean of the two central values. */
        fun median(values: List<Long>): Long {
            require(values.isNotEmpty()) { "median of no values" }
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                // Averaged as a sum of halves to stay clear of overflow on large nanos.
                sorted[mid - 1] / 2 + sorted[mid] / 2 + (sorted[mid - 1] % 2 + sorted[mid] % 2) / 2
            }
        }
    }
}

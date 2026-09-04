package org.itantra.asr

/**
 * Adjusts the endpoint silence window to how the operator actually speaks. Task **W6.3**.
 *
 * ## Why a fixed window is wrong for somebody
 *
 * The silence window is the **largest single term in the latency budget** — 150 ms in
 * push-to-talk, 400 ms on the telephone path — and it is a pure trade. Too short and a
 * speaker who pauses to think is cut off mid-sentence. Too long and everyone pays for the
 * slowest speaker on every utterance.
 *
 * A fixed value has to be set for the slowest speaker, so everyone else pays. This
 * measures the pauses a particular operator actually leaves *inside* their sentences and
 * sets the window just above them.
 *
 * ## Deliberately timid
 *
 * The window only ever moves within [MIN_MILLIS]..[MAX_MILLIS], and it adapts **slowly**
 * — a single unusual pause should not retune the system. Being cut off mid-sentence is
 * far worse than 100 ms of extra latency, so the estimator is biased towards the safe
 * side: it rises quickly on evidence of long pauses and falls slowly.
 *
 * That asymmetry is the whole design. A symmetric estimator would shorten the window
 * after a run of brisk sentences and then clip the next thoughtful one.
 */
class AdaptiveEndpoint(
    private val baseMillis: Int,
    private val minMillis: Int = MIN_MILLIS,
    private val maxMillis: Int = MAX_MILLIS,
) {
    private var estimate = baseMillis.toDouble()
    private var samples = 0

    /** The window to use for the next utterance. */
    val currentMillis: Int get() = estimate.toInt().coerceIn(minMillis, maxMillis)

    val observations: Int get() = samples

    /**
     * Records the longest pause observed *within* a completed utterance — a pause the
     * speaker recovered from, so one the window should have tolerated.
     */
    fun observeInternalPause(pauseMillis: Int) {
        samples++
        val target = pauseMillis + HEADROOM_MILLIS
        estimate =
            if (target > estimate) {
                // Rise fast: this is evidence that the current window nearly cut someone
                // off, and being cut off is the expensive failure.
                estimate + (target - estimate) * RISE_RATE
            } else {
                // Fall slowly: a few brisk sentences are not evidence that this speaker
                // has stopped pausing.
                estimate + (target - estimate) * FALL_RATE
            }
    }

    /**
     * Records that an utterance was cut off and the operator had to repeat it.
     *
     * The strongest possible evidence that the window is too short, so it is treated as
     * such: an immediate jump rather than a nudge.
     */
    fun observeCutOff() {
        samples++
        estimate = (estimate * CUT_OFF_MULTIPLIER).coerceAtMost(maxMillis.toDouble())
    }

    /** Back to the mode's default. Called on a language or mode change. */
    fun reset() {
        estimate = baseMillis.toDouble()
        samples = 0
    }

    /**
     * Whether enough has been seen to depart from the default.
     *
     * Below this the default is used unchanged — a window tuned on two utterances is
     * tuned on noise.
     */
    val isConfident: Boolean get() = samples >= MIN_OBSERVATIONS

    /** The window actually to use: the default until there is evidence to move. */
    fun effectiveMillis(): Int = if (isConfident) currentMillis else baseMillis

    companion object {
        /** Never shorter than this, whatever the evidence: below it, word tails are cut. */
        const val MIN_MILLIS = 120

        /** Never longer: beyond this the latency claim stops being defensible. */
        const val MAX_MILLIS = 700

        /** Sits above the longest pause seen rather than exactly on it. */
        const val HEADROOM_MILLIS = 60

        const val RISE_RATE = 0.5
        const val FALL_RATE = 0.05
        const val CUT_OFF_MULTIPLIER = 1.4

        const val MIN_OBSERVATIONS = 5
    }
}

/**
 * Reduces inference threads when the device gets hot. Task **W6.17**, risk **T-03**.
 *
 * An entry-tier handset throttles after roughly ten minutes of continuous inference. Once
 * the platform starts reducing clocks, asking for more threads makes the situation worse
 * rather than better: the cores are already contended and the extra scheduling is pure
 * overhead on a device that is trying to shed heat.
 *
 * Dropping to fewer threads early keeps the real-time factor stable and — the point —
 * keeps it *predictable*, which is what a reported figure needs to be.
 */
object ThermalThreads {
    /**
     * @param thermalStatus the platform's `PowerManager` value, 0–6
     * @param normal the thread count used when cool
     */
    fun threadsFor(
        thermalStatus: Int,
        normal: Int = NORMAL_THREADS,
    ): Int =
        when {
            // 0 NONE, 1 LIGHT: full speed.
            thermalStatus <= 1 -> normal
            // 2 MODERATE: back off before the platform forces it.
            thermalStatus == 2 -> (normal / 2).coerceAtLeast(1)
            // 3 SEVERE and above: one thread, and the interface should be saying so.
            else -> 1
        }

    /** At and above this the operator is told, because latency will be visibly worse. */
    fun shouldWarn(thermalStatus: Int): Boolean = thermalStatus >= WARN_THRESHOLD

    const val NORMAL_THREADS = 4
    const val WARN_THRESHOLD = 3
}

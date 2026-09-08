package org.itantra.app.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One unit's signal strength, steadied enough to drive a sound.
 *
 * Readings arrive several times a second while a unit advertises, and each one is a
 * fresh measurement with its own flicker: a hand moving, a body turning, a reflection.
 * Two stages take that out. A **median** of the last [medianWindow] kills single-reading
 * spikes without lagging a real change by more than a couple of readings. An
 * **exponential average** then smooths what is left -- against time, not against the
 * count of readings, so the response is the same whether they come ten a second or one:
 * a step is two-thirds followed in [smoothingSeconds]. A jump of [bigStepDb] or more in
 * the median is followed at [bigStepAlpha] at least, because a change that size is a
 * wall, a body or a stride, and not noise.
 *
 * Used by the searcher for the target's signal and by the target for the searcher's, so
 * the two sounds of a search quicken on the same evidence.
 */
class SignalSmoother(
    private val medianWindow: Int = MEDIAN_WINDOW,
    private val smoothingSeconds: Double = SMOOTHING_SECONDS,
    private val bigStepDb: Double = BIG_STEP_DB,
    private val bigStepAlpha: Double = BIG_STEP_ALPHA,
) {
    private val window = ArrayDeque<Int>()
    private var smoothed: Double? = null
    private var lastAtMillis = 0L

    /** The smoothed signal in dBm, or null before the first reading. */
    val value: Double?
        @Synchronized get() = smoothed

    /** The raw readings still in the median window, oldest first. */
    val recent: List<Int>
        @Synchronized get() = window.toList()

    /** Folds one reading in and returns the smoothed signal. */
    @Synchronized
    fun offer(
        rssi: Int,
        atMillis: Long,
    ): Double {
        window.addLast(rssi)
        while (window.size > medianWindow) window.removeFirst()
        val median = window.sorted()[window.size / 2].toDouble()
        val previous = smoothed
        val next =
            if (previous == null || lastAtMillis == 0L) {
                median
            } else {
                val dt = (atMillis - lastAtMillis).coerceAtLeast(0L) / 1000.0
                var alpha = 1.0 - exp(-dt / smoothingSeconds)
                if (abs(median - previous) > bigStepDb) alpha = max(alpha, bigStepAlpha)
                previous + (median - previous) * alpha
            }
        smoothed = next
        lastAtMillis = atMillis
        return next
    }

    @Synchronized
    fun clear() {
        window.clear()
        smoothed = null
        lastAtMillis = 0L
    }

    companion object {
        const val MEDIAN_WINDOW = 5

        /** Time constant of the smoothing: a step is two-thirds followed in this long. */
        const val SMOOTHING_SECONDS = 0.6

        /** A jump this large in the median is a real change and is followed at [BIG_STEP_ALPHA] at least. */
        const val BIG_STEP_DB = 8.0
        const val BIG_STEP_ALPHA = 0.5
    }
}

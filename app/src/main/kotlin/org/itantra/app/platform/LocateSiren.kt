package org.itantra.app.platform

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The sound that leads an operator to another unit: beeps that come faster and higher
 * the closer they get, and slow right down when the signal is gone.
 *
 * A Geiger counter rather than a wail, because a rate is something a person can judge
 * with their ears while their eyes are on the ground, and because "faster" is
 * unambiguous where "louder" competes with everything else in a field.
 *
 * ## The scale
 *
 * [proximity] is 0 at [org.itantra.app.engine.Locator.FAR_METRES] and 1 at
 * [org.itantra.app.engine.Locator.NEAR_METRES], on a logarithmic scale of distance, so
 * it moves the same amount for "thirty metres to ten" as for "three to one". The rate and
 * the pitch follow it **geometrically**: the gap between beeps shrinks by the same factor
 * for every step of proximity, so the rate doubles every so many metres closer, all the
 * way in. A linear gap would spend most of its range on the last two metres and leave the
 * first twenty sounding the same; the ear judges rates by ratio, the way it judges pitch.
 *
 * Thread-safe: [proximity] and [lost] are set from wherever the readings arrive and read
 * by the one thread that writes audio.
 */
class LocateSiren(context: Context? = null) : ToneLoop.Voice {
    /** 0 far, 1 at arm's reach. */
    @Volatile
    var proximity: Float = 0f

    /** No signal lately: a slow, low beep that says "still looking". */
    @Volatile
    var lost: Boolean = true

    private val loop = ToneLoop("locate-siren", context, this)

    fun start() = loop.start()

    fun stop() = loop.stop()

    val isRunning: Boolean get() = loop.isRunning

    override fun sound(): ShortArray =
        if (lost) {
            ToneLoop.tone(LOST_HZ, LOST_BEEP_MILLIS, AMPLITUDE, FADE_MILLIS)
        } else {
            ToneLoop.tone(hzFor(proximity), BEEP_MILLIS, AMPLITUDE, FADE_MILLIS)
        }

    override fun gapMillis(): Long = if (lost) LOST_GAP_MILLIS else gapFor(proximity)

    companion object {
        const val AMPLITUDE = 0.6
        const val BEEP_MILLIS = 70
        const val LOST_BEEP_MILLIS = 160
        const val FADE_MILLIS = 5

        const val FAR_HZ = 620f
        const val NEAR_HZ = 1_480f
        const val LOST_HZ = 380f

        const val FAR_GAP_MILLIS = 1_400.0
        const val NEAR_GAP_MILLIS = 90.0
        const val LOST_GAP_MILLIS = 2_800L

        /** The silence after a beep: [FAR_GAP_MILLIS] at 0, [NEAR_GAP_MILLIS] at 1, geometric between. */
        fun gapFor(proximity: Float): Long {
            val p = proximity.coerceIn(0f, 1f).toDouble()
            return (FAR_GAP_MILLIS * (NEAR_GAP_MILLIS / FAR_GAP_MILLIS).pow(p)).roundToLong()
        }

        /** The beep's pitch: [FAR_HZ] at 0, [NEAR_HZ] at 1, by equal musical steps between. */
        fun hzFor(proximity: Float): Float {
            val p = proximity.coerceIn(0f, 1f).toDouble()
            return (FAR_HZ * (NEAR_HZ / FAR_HZ).toDouble().pow(p)).toFloat()
        }
    }
}

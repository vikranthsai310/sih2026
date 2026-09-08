package org.itantra.app.platform

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The sound a unit makes so the person looking for it can walk towards the noise.
 *
 * ## Why the target sounds, and not only the searcher
 *
 * No radio a handset has can say *which way* another lies: a signal is only a strength,
 * and two positions overlap inside their own error long before the searcher can see the
 * target. Two ears can, to a few degrees, in the dark, around a corner, under a roof. So
 * when the searcher asks for it ([org.itantra.proto.Locate.sound]), this unit chirps and
 * the searcher follows the chirp -- the way every "find my phone" ends, because it is the
 * way that works.
 *
 * ## The sound
 *
 * A rising pair: two short notes a fifth apart, the second higher. Distinct from the
 * searcher's own siren -- a single note at a rate -- and from the alert tone, so a unit
 * that hears both knows which is which. Rising, because a rising pair reads as a
 * question, "here?", and is easy to pick out of wind and water. Faded at each end of
 * each note, so there is no click to mislocate.
 *
 * ## It quickens as the searcher closes in
 *
 * This unit hears the searcher's own advertisements, and how strongly it hears them is
 * how far the searcher is. [proximity] -- set by the engine from that signal, on the same
 * scale as the searcher's siren -- shortens the pause between chirps, from one every
 * second and a half far out to one every half second at arm's length. A searcher
 * following the chirp then hears from the chirp itself that they are getting warmer,
 * without a glance at the screen, and a chirp that quickens is easier to keep a bearing
 * on than one that does not. When the searcher's signal is lost the pause goes back to
 * its longest; the chirp itself stops only when the requests do.
 *
 * Plays on the alarm stream at full volume through [ToneLoop]: a handset being searched
 * for in a pocket, on silent, is the case this exists for.
 */
class FoundBeacon(context: Context? = null) : ToneLoop.Voice {
    /** How near the searcher is, 0 far or unknown, 1 at arm's reach. */
    @Volatile
    var proximity: Float = 0f

    private val loop = ToneLoop("found-beacon", context, this)

    /** Two notes and the silence between them, as one buffer. Made once. */
    private val chirp: ShortArray by lazy {
        val note = ToneLoop.SAMPLE_RATE * NOTE_MILLIS / 1000
        val rest = ToneLoop.SAMPLE_RATE * REST_MILLIS / 1000
        ShortArray(note * 2 + rest).also {
            ToneLoop.tone(it, 0, note, LOW_HZ, AMPLITUDE, FADE_MILLIS)
            ToneLoop.tone(it, note + rest, note, HIGH_HZ, AMPLITUDE, FADE_MILLIS)
        }
    }

    fun start() = loop.start()

    fun stop() {
        loop.stop()
        proximity = 0f
    }

    val isRunning: Boolean get() = loop.isRunning

    override fun sound(): ShortArray = chirp

    override fun gapMillis(): Long = gapFor(proximity)

    companion object {
        const val AMPLITUDE = 0.85

        /** A fifth: 880 to 1320 Hz, well inside where a phone speaker is loud and an ear is sharp. */
        const val LOW_HZ = 880f
        const val HIGH_HZ = 1_320f

        const val NOTE_MILLIS = 140
        const val REST_MILLIS = 60
        const val FADE_MILLIS = 6

        /** Between chirps, far out: often enough to walk to, seldom enough to hear the searcher's own siren between. */
        const val FAR_GAP_MILLIS = 1_500.0

        /** Between chirps at arm's reach. */
        const val NEAR_GAP_MILLIS = 400.0

        /** The silence after a chirp: [FAR_GAP_MILLIS] at 0, [NEAR_GAP_MILLIS] at 1, geometric between. */
        fun gapFor(proximity: Float): Long {
            val p = proximity.coerceIn(0f, 1f).toDouble()
            return (FAR_GAP_MILLIS * (NEAR_GAP_MILLIS / FAR_GAP_MILLIS).pow(p)).roundToLong()
        }
    }
}

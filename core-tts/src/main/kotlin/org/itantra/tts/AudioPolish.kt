package org.itantra.tts

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The last few milliseconds of care between a synthesiser and a loudspeaker.
 *
 * Three things, each cheap, each audible when missing:
 *
 * - **Level.** A VITS voice comes out at whatever level it was trained at — the Piper
 *   voices peak around a third of full scale — and a phone speaker in the open air needs
 *   all of it. Each phrase is brought to a speech level, by its loudness rather than its
 *   peak, so a quiet clause and a shouted one land the same distance from the ear. The gain
 *   is capped, so silence is not amplified into hiss, and the peak is held below full scale
 *   so nothing clips.
 * - **Edges.** A waveform that starts or stops at anything but zero is a click. Six
 *   milliseconds of fade at each end of a phrase is below hearing as a fade and removes the
 *   click entirely. This is what the gap between two clauses used to be made of.
 * - **Silence.** The pause between phrases is written as audio, at the right length, rather
 *   than left to whatever the audio device does between two writes.
 *
 * Pure functions on sample arrays, so they are tested without a device.
 */
object AudioPolish {
    /** Loudness a phrase is brought to: -20 dBFS, ordinary for speech with headroom. */
    const val TARGET_RMS = 0.1f

    /** Never louder than this at the peak, whatever the loudness target asks for. */
    const val PEAK_CEILING = 0.89f

    /** Never more than this much gain: +14 dB. Beyond it, what is being amplified is not speech. */
    const val MAX_GAIN = 5.0f

    const val FADE_MILLIS = 6

    /**
     * Levels and de-clicks one phrase, in place, and returns it as 16-bit PCM.
     *
     * @param samples the synthesiser's output, nominally −1..1
     */
    fun polish(
        samples: FloatArray,
        sampleRate: Int,
        targetRms: Float = TARGET_RMS,
        fadeMillis: Int = FADE_MILLIS,
    ): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val gain = gainFor(samples, targetRms)
        val fade = min(samples.size / 2, sampleRate * fadeMillis / 1000)
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            var s = samples[i] * gain
            if (i < fade) s *= i.toFloat() / fade
            val fromEnd = samples.size - 1 - i
            if (fromEnd < fade) s *= fromEnd.toFloat() / fade
            out[i] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    /**
     * The gain that brings [samples] to [targetRms] without letting the peak past the
     * ceiling, and never more than [MAX_GAIN].
     */
    fun gainFor(
        samples: FloatArray,
        targetRms: Float = TARGET_RMS,
    ): Float {
        var sum = 0.0
        var peak = 0f
        for (s in samples) {
            sum += s.toDouble() * s
            val a = abs(s)
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / samples.size).toFloat()
        if (rms <= 0f || peak <= 0f) return 1f
        val byLoudness = targetRms / rms
        val byPeak = PEAK_CEILING / peak
        return min(min(byLoudness, byPeak), MAX_GAIN)
    }

    /** [millis] of digital silence at [sampleRate]. */
    fun silence(
        millis: Int,
        sampleRate: Int,
    ): ShortArray = ShortArray((sampleRate.toLong() * millis / 1000).toInt().coerceAtLeast(0))
}

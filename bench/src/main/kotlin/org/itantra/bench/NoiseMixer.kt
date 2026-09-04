package org.itantra.bench

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Mixes noise into clean speech at a stated signal-to-noise ratio. Task **W4.9**.
 *
 * The accuracy criterion is measured at +20, +10 and +5 dB SNR as well as clean, because
 * an error rate quoted only on studio audio says nothing about a flooded district with a
 * generator running. `docs/EVALUATION.md` section 3.
 *
 * ## The fixed seed is not a detail
 *
 * Every mix is deterministic given a seed. Without that, re-running the benchmark after
 * a model change measures the model *and* a different noise draw at the same time, and
 * the comparison means nothing. The seed is recorded in the scorecard alongside the
 * result, so any figure in the report can be reproduced exactly.
 */
class NoiseMixer(private val seed: Int = DEFAULT_SEED) {
    enum class Noise {
        /** Voices, a market, a relief camp. The hardest case for a recogniser. */
        CROWD,

        /** Broadband, the most forgiving. */
        WIND,

        /** Low-frequency and periodic — a truck, a pump, a generator. */
        ENGINE,

        /** Narrow-band and loud, and it is present exactly when the message matters. */
        SIREN,
    }

    /**
     * @param speech clean 16-bit PCM
     * @param noise the noise to mix in; tiled or truncated to match [speech]
     * @param snrDb the ratio to achieve, in decibels
     * @return a new array; [speech] is not modified
     */
    fun mix(
        speech: ShortArray,
        noise: ShortArray,
        snrDb: Double,
    ): ShortArray {
        require(speech.isNotEmpty()) { "nothing to mix into" }
        require(noise.isNotEmpty()) { "no noise to mix" }

        val speechRms = rms(speech)
        val noiseRms = rms(noise)
        // Silence has no signal power, so no scaling can produce a stated ratio.
        if (speechRms == 0.0 || noiseRms == 0.0) return speech.copyOf()

        // SNR = 20 log10(speechRms / scaledNoiseRms), so the scale that achieves it is:
        val targetNoiseRms = speechRms / Math.pow(10.0, snrDb / 20.0)
        val scale = targetNoiseRms / noiseRms

        val out = ShortArray(speech.size)
        var clipped = 0
        for (i in speech.indices) {
            val mixed = speech[i] + noise[i % noise.size] * scale
            val rounded = Math.round(mixed).toInt()
            if (rounded > Short.MAX_VALUE || rounded < Short.MIN_VALUE) clipped++
            out[i] = rounded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        lastClippedSamples = clipped
        return out
    }

    /**
     * How many samples clipped in the last mix.
     *
     * Clipping at a low SNR is real — it happens in the field too — but a mix that
     * clips heavily is measuring the mixer rather than the recogniser, so the number is
     * reported rather than hidden.
     */
    var lastClippedSamples: Int = 0
        private set

    /**
     * Synthetic noise, deterministic for a given [Noise] and [samples].
     *
     * Real recordings are better and are used where available; this exists so the
     * benchmark runs reproducibly on a machine that has not downloaded a noise corpus.
     */
    fun generate(
        kind: Noise,
        samples: Int,
    ): ShortArray {
        val random = Random(seed + kind.ordinal)
        val out = ShortArray(samples)
        when (kind) {
            Noise.WIND -> {
                // Broadband, lightly low-passed: a rolling average of white noise.
                var previous = 0.0
                for (i in 0 until samples) {
                    val white = random.nextDouble(-1.0, 1.0)
                    previous = 0.7 * previous + 0.3 * white
                    out[i] = (previous * AMPLITUDE).toInt().toShort()
                }
            }
            Noise.ENGINE -> {
                // A low fundamental plus a harmonic, with a little irregularity.
                for (i in 0 until samples) {
                    val t = i / 16_000.0
                    val tone =
                        0.6 * Math.sin(2 * Math.PI * 90 * t) +
                            0.3 * Math.sin(2 * Math.PI * 180 * t) +
                            0.1 * random.nextDouble(-1.0, 1.0)
                    out[i] = (tone * AMPLITUDE).toInt().toShort()
                }
            }
            Noise.SIREN -> {
                // A tone sweeping between 600 and 1200 Hz, twice a second.
                for (i in 0 until samples) {
                    val t = i / 16_000.0
                    val frequency = 900 + 300 * Math.sin(2 * Math.PI * 0.5 * t)
                    out[i] = (Math.sin(2 * Math.PI * frequency * t) * AMPLITUDE).toInt().toShort()
                }
            }
            Noise.CROWD -> {
                // Several detuned voices at speech frequencies, summed.
                val voices = 6
                val frequencies = DoubleArray(voices) { random.nextDouble(120.0, 260.0) }
                for (i in 0 until samples) {
                    val t = i / 16_000.0
                    var sum = 0.0
                    for (f in frequencies) sum += Math.sin(2 * Math.PI * f * t)
                    sum = sum / voices + 0.4 * random.nextDouble(-1.0, 1.0)
                    out[i] = (sum * AMPLITUDE).toInt().toShort()
                }
            }
        }
        return out
    }

    companion object {
        /** Recorded in every scorecard row, so any figure can be reproduced exactly. */
        const val DEFAULT_SEED = 26173

        /** The SNRs the accuracy criterion is reported at. */
        val SNR_LEVELS = listOf(20.0, 10.0, 5.0)

        private const val AMPLITUDE = 8_000.0

        fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s.toDouble()
            return sqrt(sum / samples.size)
        }

        /** Measured SNR of a mix against its clean original, for verifying the mixer. */
        fun measureSnrDb(
            clean: ShortArray,
            mixed: ShortArray,
        ): Double {
            require(clean.size == mixed.size) { "arrays differ in length" }
            val noise = ShortArray(clean.size) { (mixed[it] - clean[it]).toInt().toShort() }
            val noiseRms = rms(noise)
            if (noiseRms == 0.0) return Double.POSITIVE_INFINITY
            return 20 * Math.log10(rms(clean) / noiseRms)
        }
    }
}

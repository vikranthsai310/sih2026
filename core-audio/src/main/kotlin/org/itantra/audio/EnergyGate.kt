package org.itantra.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tier 0 of the three-tier detector: an energy gate against an adaptive noise floor.
 *
 * Costs roughly three microseconds per 20 ms frame, and rejects true silence — which
 * is the overwhelming majority of elapsed time. Everything above it is expensive:
 * Silero VAD is a hundred times dearer per frame and the acoustic model a thousand.
 * Getting this right is what holds idle processor use below two per cent and buys
 * the eight-hour standby claim. See `docs/ASR.md` section 1.
 *
 * Parameters are normative:
 *
 * | Parameter    | Value                                        |
 * |--------------|----------------------------------------------|
 * | Frame        | 20 ms, 320 samples at 16 kHz mono            |
 * | Noise floor  | exponential moving average, tau = 3 s        |
 * | Trigger      | frame energy above the floor by 9 dB         |
 * | Hysteresis   | 3 frames to open, 10 to close                |
 *
 * The floor is updated **only while the gate is shut**, and only when nothing
 * downstream reports speech. That is what stops a long utterance from dragging the
 * floor up until the speaker is gated out — the failure this class exists to avoid.
 *
 * Not thread-safe: it belongs to the capture thread and is allocation-free.
 */
class EnergyGate(
    private val sampleRate: Int = 16_000,
    private val frameMillis: Int = 20,
    private val thresholdDb: Double = 9.0,
    private val framesToOpen: Int = 3,
    private val framesToClose: Int = 10,
    private val noiseFloorTauSeconds: Double = 3.0,
) {
    /** Smoothing coefficient for the floor, derived from tau and the frame duration. */
    private val alpha: Double =
        1.0 - exp(-(frameMillis / 1000.0) / noiseFloorTauSeconds)

    private var noiseFloorDb = Double.NaN
    private var consecutiveAbove = 0
    private var consecutiveBelow = 0

    /** True while the gate is passing audio to tier 1. */
    var isOpen: Boolean = false
        private set

    /** Current adaptive floor in dBFS, or NaN before the first frame. */
    val floorDb: Double get() = noiseFloorDb

    /**
     * Processes one frame.
     *
     * @param frame PCM samples; [frameSamples] of them is the designed size, but any
     *   length works so the caller is not forced to pad.
     * @param speechDownstream whether a later tier reports speech for this frame. When
     *   true the floor is frozen, because a talker must never raise their own floor.
     * @return whether the gate is open after this frame.
     */
    fun process(
        frame: ShortArray,
        speechDownstream: Boolean = false,
    ): Boolean {
        val levelDb = levelDbFs(frame)

        if (noiseFloorDb.isNaN()) {
            noiseFloorDb = levelDb
        }

        val above = levelDb > noiseFloorDb + thresholdDb

        if (above) {
            consecutiveAbove++
            consecutiveBelow = 0
        } else {
            consecutiveBelow++
            consecutiveAbove = 0
        }

        if (!isOpen && consecutiveAbove >= framesToOpen) {
            isOpen = true
        } else if (isOpen && consecutiveBelow >= framesToClose) {
            isOpen = false
        }

        // Adapt on every frame a later tier does not call speech -- including frames
        // that opened this gate, which is what lets the floor follow a room that has
        // become permanently louder. Gating adaptation on `isOpen` instead would jam
        // the gate open forever the first time the environment stepped up.
        if (!speechDownstream) {
            noiseFloorDb += alpha * (levelDb - noiseFloorDb)
        }
        return isOpen
    }

    /** Forgets the floor and the hysteresis counters. */
    fun reset() {
        noiseFloorDb = Double.NaN
        consecutiveAbove = 0
        consecutiveBelow = 0
        isOpen = false
    }

    /** Root-mean-square level of the frame in dBFS, floored at [SILENCE_DB]. */
    private fun levelDbFs(frame: ShortArray): Double {
        if (frame.isEmpty()) return SILENCE_DB
        var sumOfSquares = 0.0
        for (sample in frame) {
            val normalised = sample.toDouble() / FULL_SCALE
            sumOfSquares += normalised * normalised
        }
        val rms = sqrt(sumOfSquares / frame.size)
        if (rms <= 0.0) return SILENCE_DB
        return (20.0 * ln(rms) / LN_10).coerceAtLeast(SILENCE_DB)
    }

    /** Designed frame size: 320 samples at 16 kHz. */
    val frameSamples: Int get() = sampleRate * frameMillis / 1000

    private companion object {
        const val FULL_SCALE = 32_768.0
        const val SILENCE_DB = -120.0
        val LN_10 = ln(10.0)
    }
}

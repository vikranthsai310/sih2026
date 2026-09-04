package org.itantra.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recognition tap, task **W7.6**.
 *
 * The suppressor here is a fake, because RNNoise's native library is not built (see
 * [RnNoiseSuppressor]) and because what needs testing is not RNNoise — it is the rate
 * reconciliation and the frame arithmetic around it, which is where an integration of this
 * shape actually goes wrong.
 */
class RecognitionTapTest {
    /** Records what it was handed and can be told to alter it, so the path is observable. */
    private class FakeSuppressor(
        override val sampleRate: Int = RnNoiseSuppressor.RATE,
        override val frameSamples: Int = RnNoiseSuppressor.FRAME_SAMPLES,
        val vad: Float? = null,
        val transform: ((ShortArray) -> Unit)? = null,
    ) : NoiseSuppressor {
        var calls = 0
        var allocationsObserved = 0
        private var lastIdentity: ShortArray? = null

        override fun process(frame: ShortArray): Float? {
            calls++
            assertEquals("the suppressor must be handed its own frame size", frameSamples, frame.size)
            // A tap that allocated per frame would hand a different array each time, which
            // on the capture thread is fifty allocations a second.
            if (lastIdentity != null && lastIdentity !== frame) allocationsObserved++
            lastIdentity = frame
            transform?.invoke(frame)
            return vad
        }
    }

    // ── the disabled path ────────────────────────────────────────────────────

    /**
     * "Off" must be genuinely free. A pass-through that resampled up and back would put a
     * measurable cost on the configuration chosen precisely to avoid one.
     */
    @Test
    fun `suppression disabled leaves the hop byte for byte unchanged`() {
        val tap = RecognitionTap(NoSuppression)
        val hop = speech(tap.hopSamples)
        val before = hop.copyOf()

        tap.suppress(hop)

        assertArrayEquals(before, hop)
        assertFalse(tap.isEnabled)
        assertNull(tap.lastVoiceActivity)
    }

    // ── the rate reconciliation ──────────────────────────────────────────────

    /**
     * A 20 ms hop at 16 kHz upsamples to exactly two RNNoise frames, with no remainder and
     * no carry buffer between hops. That is the arithmetic the class depends on.
     */
    @Test
    fun `one 20 ms hop is exactly two RNNoise frames`() {
        val suppressor = FakeSuppressor()
        val tap = RecognitionTap(suppressor)

        assertEquals(320, tap.hopSamples)
        tap.suppress(speech(tap.hopSamples))
        assertEquals(2, suppressor.calls)
    }

    @Test
    fun `a hop that does not divide into whole suppressor frames is refused`() {
        val thrown =
            runCatching { RecognitionTap(FakeSuppressor(), hopSamples = 250) }.exceptionOrNull()
        assertNotNull("240 samples upsampled is not a whole number of frames", thrown)
    }

    @Test
    fun `a suppressor at a rate that is not a multiple of the pipeline is refused`() {
        val thrown =
            runCatching {
                RecognitionTap(FakeSuppressor(sampleRate = 44_100, frameSamples = 441))
            }.exceptionOrNull()
        assertNotNull(thrown)
    }

    // ── the round trip ───────────────────────────────────────────────────────

    /**
     * With a suppressor that changes nothing, the hop must come back close to where it
     * started. Not identical — linear interpolation and a box average are lossy, and the
     * class says so — but close enough that the resampling is not itself the noise. A tone
     * is the honest test signal here: broadband noise would hide a resampling defect.
     */
    @Test
    fun `a transparent suppressor returns the hop close to unchanged`() {
        val tap = RecognitionTap(FakeSuppressor())
        val hop = speech(tap.hopSamples)
        val before = hop.copyOf()

        tap.suppress(hop)

        val error = before.indices.maxOf { kotlin.math.abs(before[it] - hop[it]) }
        assertTrue("resampling round trip moved a sample by $error", error < TOLERATED_ROUND_TRIP)
    }

    @Test
    fun `the length of the hop is unchanged`() {
        val tap = RecognitionTap(FakeSuppressor())
        val hop = speech(tap.hopSamples)
        tap.suppress(hop)
        assertEquals(320, hop.size)
    }

    /** What the suppressor writes must reach the recogniser, or the stage does nothing. */
    @Test
    fun `what the suppressor writes comes back in the hop`() {
        val tap = RecognitionTap(FakeSuppressor(transform = { frame -> frame.fill(0) }))
        val hop = speech(tap.hopSamples)

        tap.suppress(hop)

        assertTrue("a suppressor that silenced everything must leave silence", hop.all { it == 0.toShort() })
    }

    // ── voice activity ───────────────────────────────────────────────────────

    @Test
    fun `voice activity from the suppressor is surfaced`() {
        val tap = RecognitionTap(FakeSuppressor(vad = 0.9f))
        tap.suppress(speech(tap.hopSamples))
        assertEquals(0.9f, tap.lastVoiceActivity!!, 1e-6f)
    }

    @Test
    fun `a suppressor that reports no voice activity leaves it null`() {
        val tap = RecognitionTap(FakeSuppressor(vad = null))
        tap.suppress(speech(tap.hopSamples))
        assertNull(tap.lastVoiceActivity)
    }

    // ── the capture thread's contract ────────────────────────────────────────

    /** [AudioCapture] runs this on a thread that must not allocate. */
    @Test
    fun `repeated hops reuse the same buffers`() {
        val suppressor = FakeSuppressor()
        val tap = RecognitionTap(suppressor)
        val hop = speech(tap.hopSamples)

        repeat(50) { tap.suppress(hop) }

        assertEquals("the scratch frame must be allocated once", 0, suppressor.allocationsObserved)
        assertEquals(100, suppressor.calls)
    }

    // ── the rule in the task title ───────────────────────────────────────────

    /**
     * "Never audio the user hears." There is no assertion that can prove a negative about
     * a whole codebase, so the design makes the violation inexpressible instead: playback
     * takes no suppressor, and this test is the reminder of why. An alert tone through a
     * speech-tuned suppressor gets quieter the longer it plays, which is the worst failure
     * available to the one signal that must never be missed.
     */
    @Test
    fun `the playback path takes no suppressor`() {
        val playbackConstructors = AlertPlayback::class.java.constructors
        val takesSuppressor =
            playbackConstructors.any { constructor ->
                constructor.parameterTypes.any { NoiseSuppressor::class.java.isAssignableFrom(it) }
            }
        assertFalse("a suppressor must never reach the playback path", takesSuppressor)
    }

    private fun speech(samples: Int) =
        ShortArray(samples) { i ->
            // A 220 Hz tone with a second harmonic: enough structure that a resampling
            // defect shows up as an error rather than averaging away.
            val t = i / 16_000.0
            val value =
                6_000 * Math.sin(2 * Math.PI * 220 * t) + 2_000 * Math.sin(2 * Math.PI * 440 * t)
            value.toInt().toShort()
        }

    private companion object {
        /** Linear interpolation plus a box average, on a signal of amplitude 8000. */
        const val TOLERATED_ROUND_TRIP = 400
    }
}

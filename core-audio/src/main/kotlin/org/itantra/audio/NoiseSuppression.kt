package org.itantra.audio

/**
 * Noise suppression, on the recognition path and nowhere else. Task **W7.6**.
 *
 * ## The rule in the task title, and why it is the important half
 *
 * "Recognition path only, **never** audio the user hears." A suppressor exists to make a
 * recogniser's job easier, and it does that by removing energy it judges to be noise. On a
 * signal a person is listening to, the same operation is a liability twice over:
 *
 * - **Synthesised speech** is already clean. Running it through a suppressor trained on
 *   noisy microphone input can only remove things — and what it removes first is the
 *   low-energy consonant detail that distinguishes similar words.
 * - **The alert tone** is a designed signal, not speech. A speech-tuned suppressor is
 *   entitled to treat a steady tone as noise, and an alert that gets quieter the longer it
 *   plays is the worst possible failure of the one signal that must never be missed.
 *
 * So there is exactly one place a suppressor may be installed — [RecognitionTap] — and the
 * playback classes take no suppressor at all. The rule is enforced by there being no way to
 * express the violation, rather than by a comment asking people not to.
 *
 * ## Whether it is on is a measurement, not a preference
 *
 * Suppression is not free and not uniformly good; on some languages it costs accuracy. The
 * per-language decision comes from the accuracy matrix (`bench`, task **W7.7**), which is
 * why this interface has a working pass-through implementation: "off" is a supported
 * configuration, not a missing one.
 */
interface NoiseSuppressor : AutoCloseable {
    /** Sample rate this suppressor operates at. */
    val sampleRate: Int

    /** Samples per call to [process]. RNNoise is fixed at 480 at 48 kHz — 10 ms. */
    val frameSamples: Int

    /**
     * Suppresses noise in [frame] **in place**.
     *
     * In place, and never allocating, because this runs on the capture thread — see
     * [AudioCapture]. A suppressor that allocates per hop allocates fifty times a second
     * for audio that is usually silence.
     *
     * @return an estimate of voice activity in 0..1 where the implementation provides one,
     *   or null. RNNoise computes this anyway as part of its model, and it is worth having:
     *   a second opinion alongside [EnergyGate] costs nothing here.
     */
    fun process(frame: ShortArray): Float?

    override fun close() {}
}

/**
 * Suppression disabled. Not a stub — the measured answer for some languages is "off", and
 * that answer needs a working implementation rather than a null check at every call site.
 */
object NoSuppression : NoiseSuppressor {
    override val sampleRate: Int get() = RecognitionTap.PIPELINE_RATE
    override val frameSamples: Int get() = RecognitionTap.PIPELINE_RATE / 100

    override fun process(frame: ShortArray): Float? = null
}

/**
 * The one place a suppressor is installed: between capture and the recogniser.
 *
 * ## Why this class exists rather than a call in the capture loop
 *
 * Two rates have to be reconciled, and doing it inline would put the arithmetic somewhere
 * nobody tests. The pipeline runs at 16 kHz in 20 ms hops (320 samples); RNNoise is fixed
 * at 48 kHz in 10 ms frames (480 samples). The ratio works out exactly:
 *
 * ```
 *   320 samples @ 16 kHz  ──×3──▶  960 @ 48 kHz  ──▶  2 RNNoise frames  ──÷3──▶  320
 * ```
 *
 * No remainder and no buffering between hops, which is a happy accident of the 20 ms hop
 * size rather than a design — a 15 ms hop would need a carry buffer and would make this
 * class considerably worse.
 *
 * ## The resampling is deliberately crude, and that is a measured decision
 *
 * Upsampling is linear interpolation and downsampling is a box average. Neither is a proper
 * polyphase filter, and both leave imaging artefacts a good resampler would not. That is a
 * defensible starting point for one reason: the question is not whether the resampler is
 * ideal but whether the whole stage improves recognition, and W7.7 measures exactly that
 * with the resampler in the loop. If suppression turns out to pay for itself, improving the
 * resampler is the obvious next gain; if it does not, a better resampler would have been
 * work spent on a stage that gets switched off.
 *
 * ## Allocation
 *
 * Every buffer is allocated once, at construction. [suppress] runs on the capture thread.
 */
class RecognitionTap(
    private val suppressor: NoiseSuppressor,
    val hopSamples: Int = PIPELINE_RATE * AudioCapture.HOP_MILLIS / 1000,
) : AutoCloseable {
    private val ratio = suppressor.sampleRate / PIPELINE_RATE

    init {
        require(hopSamples > 0) { "hop must be positive: $hopSamples" }
        if (suppressor !== NoSuppression) {
            require(suppressor.sampleRate % PIPELINE_RATE == 0) {
                "suppressor runs at ${suppressor.sampleRate} Hz, which is not a whole " +
                    "multiple of the ${PIPELINE_RATE} Hz pipeline"
            }
            require((hopSamples * ratio) % suppressor.frameSamples == 0) {
                "a $hopSamples-sample hop upsamples to ${hopSamples * ratio}, which is not " +
                    "a whole number of ${suppressor.frameSamples}-sample suppressor frames"
            }
        }
    }

    private val upsampled = ShortArray(hopSamples * maxOf(ratio, 1))
    private val scratch = ShortArray(if (suppressor === NoSuppression) 0 else suppressor.frameSamples)

    /** Voice activity from the last [suppress], where the suppressor reported one. */
    var lastVoiceActivity: Float? = null
        private set

    val isEnabled: Boolean get() = suppressor !== NoSuppression

    /**
     * Suppresses noise in [hop] in place, over [length] samples.
     *
     * Disabled, this is a no-op and the samples are untouched — byte for byte, not
     * "approximately unchanged". A pass-through that quietly resamples twice would put a
     * measurable cost on the configuration that was supposed to be free.
     */
    fun suppress(
        hop: ShortArray,
        length: Int = hop.size,
    ) {
        if (!isEnabled) return
        require(length == hopSamples) { "expected a $hopSamples-sample hop, got $length" }

        upsample(hop, length)

        var activity: Float? = null
        var offset = 0
        while (offset + scratch.size <= length * ratio) {
            upsampled.copyInto(scratch, 0, offset, offset + scratch.size)
            val vad = suppressor.process(scratch)
            // The louder half of the hop is the one worth reporting: a 20 ms window that
            // is half speech is a window containing speech.
            if (vad != null) activity = maxOf(activity ?: 0f, vad)
            scratch.copyInto(upsampled, offset)
            offset += scratch.size
        }
        lastVoiceActivity = activity

        downsample(hop, length)
    }

    /** Linear interpolation to the suppressor's rate. See the class note. */
    private fun upsample(
        hop: ShortArray,
        length: Int,
    ) {
        for (i in 0 until length) {
            val current = hop[i].toInt()
            val next = if (i + 1 < length) hop[i + 1].toInt() else current
            for (step in 0 until ratio) {
                val interpolated = current + (next - current) * step / ratio
                upsampled[i * ratio + step] = interpolated.toShort()
            }
        }
    }

    /** Box average back to the pipeline rate. */
    private fun downsample(
        hop: ShortArray,
        length: Int,
    ) {
        for (i in 0 until length) {
            var sum = 0
            for (step in 0 until ratio) sum += upsampled[i * ratio + step].toInt()
            hop[i] = (sum / ratio).toShort()
        }
    }

    override fun close() = suppressor.close()

    companion object {
        /** Capture, recognition and the pre-trigger ring all run at this rate. */
        const val PIPELINE_RATE = AudioCapture.SAMPLE_RATE
    }
}

/**
 * The RNNoise binding. Task **W7.6**.
 *
 * ## Status
 *
 * The Kotlin side is complete; the native library is **not built**. RNNoise is a small C
 * library with no Android release artefact, so `librnnoise_jni.so` has to be produced from
 * source with the NDK for `arm64-v8a`, and this project has not done that yet. [create]
 * therefore returns null rather than throwing, and the caller falls back to [NoSuppression]
 * with the reason recorded — a handset that cannot suppress noise still recognises speech,
 * and refusing to start over it would be the wrong trade.
 *
 * Recording that plainly matters more than making it look finished: a binding that compiles
 * against an absent library and fails at first use in the field is worse than one that says
 * so at load.
 *
 * ## Licence
 *
 * RNNoise is BSD-3-Clause — compatible, unlike the GPL-3.0 espeak-ng data already
 * disclosed. Recorded in `LICENSES.md`.
 */
class RnNoiseSuppressor private constructor(
    private val handle: Long,
) : NoiseSuppressor {
    override val sampleRate: Int get() = RATE

    /** Fixed by the model. Not a tunable. */
    override val frameSamples: Int get() = FRAME_SAMPLES

    override fun process(frame: ShortArray): Float {
        require(frame.size == FRAME_SAMPLES) {
            "RNNoise takes exactly $FRAME_SAMPLES samples, got ${frame.size}"
        }
        return nativeProcess(handle, frame)
    }

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
    }

    private external fun nativeProcess(
        handle: Long,
        frame: ShortArray,
    ): Float

    private external fun nativeDestroy(handle: Long)

    companion object {
        /** RNNoise is trained at 48 kHz and does not run at any other rate. */
        const val RATE = 48_000

        /** 10 ms at 48 kHz. Fixed by the model. */
        const val FRAME_SAMPLES = 480

        private var available: Boolean? = null

        /**
         * @return a suppressor, or null when the native library is absent — see the class
         *   note on status. Never throws: the caller falls back to [NoSuppression].
         */
        fun create(): RnNoiseSuppressor? {
            if (available == false) return null
            return runCatching {
                System.loadLibrary("rnnoise_jni")
                available = true
                val handle = nativeCreate()
                if (handle == 0L) null else RnNoiseSuppressor(handle)
            }.getOrElse {
                available = false
                null
            }
        }

        @JvmStatic
        private external fun nativeCreate(): Long
    }
}

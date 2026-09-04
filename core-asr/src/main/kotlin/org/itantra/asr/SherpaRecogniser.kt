package org.itantra.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.itantra.proto.Language

/**
 * The IndicConformer binding. Task **W1.27**.
 *
 * ## What this is not
 *
 * It is **not** a streaming recogniser, because no streaming acoustic model exists for
 * these ten languages — verified, and the reason the latency target was revised from
 * 500–800 ms to 800–1200 ms. `OfflineRecognizer` decodes a complete buffer in one call
 * and yields no partial hypotheses.
 *
 * The latency that would otherwise cost is recovered by [SlidingWindowDecoder], which
 * calls [decode] on overlapping windows *while the speaker is still talking*, leaving
 * only a short tail for after the endpoint. This class is deliberately the thin,
 * synchronous piece that windowing sits on top of; see [windowedDecoder].
 *
 * ## Threads
 *
 * Four while decoding a window and two at idle, per `docs/ASR.md` section 3.5: cores are
 * free during speech, but the thermal budget is not. The recogniser is created once per
 * language and released on a language switch — one model resident at a time.
 */
class SherpaRecogniser(
    assets: AssetManager,
    modelDir: String,
    tokensPath: String,
    val language: Language,
    hotwordsFile: String = "",
    hotwordsScore: Float = 1.5f,
    numThreads: Int = DECODE_THREADS,
) : AutoCloseable {
    private val recogniser =
        OfflineRecognizer(
            assets,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig =
                    OfflineModelConfig(
                        // IndicConformer is published as a NeMo CTC graph, not a
                        // transducer -- the wrong config here loads and then produces
                        // silence rather than failing.
                        nemo = OfflineNemoEncDecCtcModelConfig(model = "$modelDir/model.int8.onnx"),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        modelType = "nemo_ctc",
                    ),
                decodingMethod = "greedy_search",
                // Contextual biasing: the highest-yield accuracy work available, and it
                // needs no retraining. docs/ASR.md section 3.4, tasks W4.13-W4.15.
                hotwordsFile = hotwordsFile,
                hotwordsScore = hotwordsScore,
            ),
        )

    /**
     * Decodes a complete buffer. Blocking, and called on the inference thread.
     *
     * @param pcm 16 kHz mono samples, normalised to −1..1
     */
    fun decode(pcm: FloatArray): String {
        val stream = recogniser.createStream()
        return try {
            stream.acceptWaveform(pcm, SAMPLE_RATE)
            recogniser.decode(stream)
            recogniser.getResult(stream).text.trim()
        } finally {
            // The stream holds a native pointer. Leaking one per utterance is a slow
            // native leak that would surface only during a long soak.
            stream.release()
        }
    }

    /** Convenience for 16-bit PCM, which is what `AudioRecord` produces. */
    fun decode(pcm: ShortArray): String = decode(pcm.toFloatArray())

    /**
     * Wraps this recogniser in the windowing from task W3.13.
     *
     * This is how the engine should use it: feed [SlidingWindowDecoder.onSpeech] as
     * capture arrives and call `onEndpoint()` when the endpointer fires, so only the
     * final partial window is decoded after the speaker stops.
     */
    fun windowedDecoder(): SlidingWindowDecoder = SlidingWindowDecoder(sampleRate = SAMPLE_RATE, decode = ::decode)

    override fun close() = recogniser.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80

        /** Cores are free while someone is speaking; the thermal budget is not. */
        const val DECODE_THREADS = 4
        const val IDLE_THREADS = 2

        fun ShortArray.toFloatArray(): FloatArray = FloatArray(size) { this[it] / 32768.0f }
    }
}

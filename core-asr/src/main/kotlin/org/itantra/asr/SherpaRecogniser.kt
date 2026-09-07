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
 * The latency that would otherwise cost is recovered by [UtteranceDecoder], which calls
 * [transcribe] on each clause *as the speaker pauses*, leaving only the last clause for
 * after the endpoint. This class is deliberately the thin, synchronous piece that
 * segmentation sits on top of.
 *
 * ## Timestamps
 *
 * sherpa-onnx reports the frame at which its CTC search emitted each token, at the
 * model's 40 ms resolution (10 ms features, subsampling factor 4 in the model's own
 * metadata). [transcribe] keeps them: they are how [UtteranceDecoder] decides which of two
 * overlapping decodes owns a word on a forced cut, rather than guessing from the text.
 *
 * ## What the decoder can be told, and what it ignores
 *
 * Read from the library's source (`offline-recognizer-ctc-impl.h`,
 * `offline-ctc-greedy-search-decoder.cc`) rather than its configuration class, which
 * accepts everything and applies some of it: for an offline CTC model sherpa-onnx runs
 * **greedy search only**. `hotwordsFile`, `hotwordsScore` and `blankPenalty` are read by
 * the transducer path and never reach the CTC decoder; `modified_beam_search` is refused
 * at construction. The one other decoder the CTC path has is an FST (HLG) decoder, which
 * needs a graph compiled with k2 from a lexicon and a language model for each language's
 * token set, and no such graph exists for IndicConformer. So accuracy is decided by the
 * model and by the audio it is given, which is why the work is in [UtteranceDecoder]:
 * where the clause is cut, what quiet is kept round it, what is silenced before it, and
 * the high-pass on the way in.
 *
 * ## Threads
 *
 * Four while decoding a window and two at idle, per `docs/ASR.md` section 3.5: cores are
 * free during speech, but the thermal budget is not. The recogniser is created once per
 * language and released on a language switch — one model resident at a time.
 */
class SherpaRecogniser(
    modelPath: String,
    tokensPath: String,
    val language: Language,
    /**
     * Null for a model on the filesystem, which is the shipping case.
     *
     * A pack is ~189 MB and constraint N2 caps the installer at 30 MB, so the model cannot
     * be an asset: it is fetched once during setup and read from app storage. The asset
     * path is kept because it is the only way to run a model from an instrumented test.
     */
    assets: AssetManager? = null,
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
                        nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                        tokens = tokensPath,
                        numThreads = numThreads,
                        modelType = "nemo_ctc",
                    ),
                decodingMethod = "greedy_search",
                // Kept for the day a transducer export of these models exists; the CTC
                // path in the library does not read them. See the class comment.
                hotwordsFile = hotwordsFile,
                hotwordsScore = hotwordsScore,
            ),
        )

    /**
     * Decodes a complete buffer into timed words. Blocking, and called on the inference
     * thread.
     *
     * @param pcm 16 kHz mono samples, normalised to −1..1
     */
    fun transcribe(pcm: FloatArray): Transcript {
        val stream = recogniser.createStream()
        return try {
            stream.acceptWaveform(pcm, SAMPLE_RATE)
            recogniser.decode(stream)
            val result = recogniser.getResult(stream)
            val timed = Transcript.fromTokens(result.tokens, result.timestamps)
            // A build of the library that reports text but no tokens would otherwise
            // read as silence. Untimed words are still words.
            if (timed.isEmpty && result.text.isNotBlank()) Transcript.fromText(result.text) else timed
        } finally {
            // The stream holds a native pointer. Leaking one per utterance is a slow
            // native leak that would surface only during a long soak.
            stream.release()
        }
    }

    /** Convenience for 16-bit PCM, which is what `AudioRecord` produces. */
    fun transcribe(pcm: ShortArray): Transcript = transcribe(pcm.toFloatArray())

    /** The text alone, for a caller that does not need the timing. */
    fun decode(pcm: FloatArray): String = transcribe(pcm).text

    fun decode(pcm: ShortArray): String = transcribe(pcm).text

    /**
     * Wraps this recogniser in the segmentation from task W3.13.
     *
     * This is how the engine should use it: feed [UtteranceDecoder.onAudio] as capture
     * arrives and call `onEndpoint()` when the speaker lets go, so only the last clause
     * is decoded after they stop.
     */
    fun utteranceDecoder(): UtteranceDecoder =
        UtteranceDecoder(sampleRate = SAMPLE_RATE, decode = ::transcribe, highPassHz = UtteranceDecoder.HIGH_PASS_HZ)

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

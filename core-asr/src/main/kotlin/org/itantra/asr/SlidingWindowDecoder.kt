package org.itantra.asr

/**
 * Decodes an utterance in overlapping windows **while the speaker is still talking**,
 * so that only a short tail remains to decode after the endpoint.
 *
 * ## Why this exists
 *
 * There is no streaming acoustic model published for the ten Indian languages. Every
 * available Indic model — IndicConformer NeMo-CTC and the Dolphin family — is offline
 * and decodes a complete utterance in one call. Taken naively that puts the whole
 * decode after the endpoint:
 *
 * ```
 *  speak 3 s ─────────────────►│ endpoint │◄──── decode 3 s ────►│ transmit
 *                                150 ms        900 ms at RTF 0.30
 * ```
 *
 * which is 1 050 ms before a byte is sent. Decoding windows as they accumulate leaves
 * only the final partial window:
 *
 * ```
 *  speech  |--- w1 ---|--- w2 ---|-- w3 --|
 *          decode w1   decode w2  decode w3    <- while the speaker is still talking
 *                                     endpoint
 *                                     |- tail -|   250-450 ms, not 900 ms
 * ```
 *
 * It costs roughly 1.6× the total compute, which the efficiency budget absorbs because
 * it runs only while someone is speaking — perhaps five per cent of elapsed time.
 *
 * Risk **T-16**, `docs/ASR.md` section 3.5.
 *
 * @param decode called with a window of PCM; returns its text. Injected so the
 *   windowing can be tested without a model.
 */
class SlidingWindowDecoder(
    private val sampleRate: Int = 16_000,
    private val windowMillis: Int = 1_500,
    private val overlapMillis: Int = 400,
    private val decode: (ShortArray) -> String,
) {
    init {
        require(overlapMillis < windowMillis) { "overlap must be shorter than the window" }
    }

    private val windowSamples = sampleRate * windowMillis / 1000
    private val overlapSamples = sampleRate * overlapMillis / 1000
    private val strideSamples = windowSamples - overlapSamples

    private var buffer = ShortArray(0)
    private val decoded = ArrayList<String>()

    /** Windows decoded so far during this utterance. */
    val windowsDecoded: Int get() = decoded.size

    /** Samples not yet covered by a completed window — the tail the endpoint pays for. */
    val pendingSamples: Int get() = buffer.size

    /** Milliseconds of audio that would still need decoding if the endpoint fired now. */
    val pendingMillis: Int get() = buffer.size * 1000 / sampleRate

    /**
     * Feeds captured speech and decodes every window it completes.
     *
     * Called on the inference thread while the speaker continues, so its cost does not
     * appear in the latency budget at all.
     *
     * It **loops** rather than decoding a single window per call. Capture normally
     * arrives in 20 ms hops, so one call rarely completes even one window — but a
     * caller that hands over a large block in one go must not leave several windows'
     * worth of audio sitting in the buffer, because that is exactly the tail the
     * endpoint would then have to pay for.
     *
     * @return the text of each window completed by this call, in order; empty if none.
     */
    fun onSpeech(samples: ShortArray): List<String> {
        buffer += samples
        if (buffer.size < windowSamples) return emptyList()

        val texts = ArrayList<String>()
        while (buffer.size >= windowSamples) {
            val window = buffer.copyOfRange(0, windowSamples)
            // Keep the overlap so a word straddling the boundary is not lost.
            buffer = buffer.copyOfRange(strideSamples, buffer.size)

            val text = decode(window)
            decoded += text
            texts += text
        }
        return texts
    }

    /**
     * The endpoint has fired. Decodes whatever remains and returns the stitched
     * utterance.
     *
     * This is the only decode that costs latency, and it covers at most one window.
     */
    fun onEndpoint(): String {
        if (buffer.isNotEmpty()) {
            decoded += decode(buffer)
            buffer = ShortArray(0)
        }
        val text = stitch(decoded)
        decoded.clear()
        return text
    }

    fun reset() {
        buffer = ShortArray(0)
        decoded.clear()
    }

    companion object {
        /**
         * Joins overlapping window texts, removing the words each shares with the last.
         *
         * Because windows overlap, consecutive decodes repeat the words in the overlap.
         * The join looks for the longest suffix of what is built so far that is also a
         * prefix of the next window, and drops the duplicate. Where nothing matches —
         * the recogniser produced different words for the same audio — the pieces are
         * simply concatenated, which is the safe failure: a repeated word is
         * recoverable by a listener, a silently dropped one is not.
         */
        fun stitch(parts: List<String>): String {
            val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) return ""

            var result = cleaned.first().split(WHITESPACE).toMutableList()

            for (next in cleaned.drop(1)) {
                val words = next.split(WHITESPACE).filter { it.isNotBlank() }
                if (words.isEmpty()) continue

                val maxOverlap = minOf(result.size, words.size)
                var matched = 0
                for (n in maxOverlap downTo 1) {
                    val tail = result.subList(result.size - n, result.size)
                    if (tail == words.subList(0, n)) {
                        matched = n
                        break
                    }
                }
                result.addAll(words.drop(matched))
            }
            return result.joinToString(" ").trim()
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

package org.itantra.asr

/**
 * Confidence in a recognised utterance, quantised to the two `CONFIDENCE` bits the
 * frame header carries. See `docs/ASR.md` section 4 and `docs/PROTOCOL.md` section 7.
 *
 * The wire values are normative. Thresholds are calibrated per language during
 * evaluation and live in the pack manifest rather than in code, because a threshold
 * that is right for Hindi is not right for Odia.
 */
enum class Confidence(val wireValue: Int) {
    /** Sender is warned; alert-class messages are blocked pending confirmation. */
    LOW(0),

    /** Transmitted, displayed with a caution marker at the receiver. */
    MEDIUM(1),

    /** Normal path. */
    HIGH(2),

    /** Fuzzy match at or above 0.85 **and** high confidence. Sent as a template code. */
    TEMPLATE_MATCHED(3),
    ;

    companion object {
        /**
         * @param meanLogProb mean token log-probability from the decoder
         * @param low, high per-language thresholds from the pack manifest
         */
        fun from(
            meanLogProb: Double,
            low: Double,
            high: Double,
        ): Confidence =
            when {
                meanLogProb >= high -> HIGH
                meanLogProb >= low -> MEDIUM
                else -> LOW
            }

        fun fromWire(value: Int): Confidence = entries.firstOrNull { it.wireValue == value } ?: LOW
    }
}

/**
 * The result of recognising an utterance.
 *
 * Note [isFinal]. The production acoustic model is offline and yields no partial
 * hypotheses, so until sliding-window decoding lands (task W3.13) every hypothesis
 * is final and the interface must not be built around live partial text. See
 * `docs/ASR.md` section 3.5.
 *
 * Timestamps are carried end to end so `latency.csv` can be written without a
 * separate tracing mechanism — `docs/ASR.md` section 9.
 */
data class Hypothesis(
    val text: String,
    val language: org.itantra.proto.Language,
    val confidence: Confidence,
    val isFinal: Boolean,
    /** Capture timestamp of the first sample of this utterance, in nanoseconds. */
    val micNanos: Long,
    /** When the endpointer declared the utterance finished. */
    val endpointNanos: Long,
    /** When the decoder produced this text. */
    val finalNanos: Long,
) {
    /**
     * The "final decode after endpoint" term of the latency budget. With an offline
     * recogniser this is the cost of the tail window, not of the whole utterance.
     */
    val decodeAfterEndpointMillis: Long get() = (finalNanos - endpointNanos) / 1_000_000

    /** Everything from the first sample to usable text. */
    val captureToTextMillis: Long get() = (finalNanos - micNanos) / 1_000_000

    val isEmpty: Boolean get() = text.isBlank()
}

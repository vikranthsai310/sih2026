package org.itantra.bench

/**
 * Records the stage timestamps for one utterance as it happens. Task **W1.33**.
 *
 * ## Why the live path carries this
 *
 * [UtteranceTrace] is the shape a latency row has to be in. Nothing produced one. The
 * timestamps it wants — `tMic`, `tVad`, `tFirstPartial`, `tEndpoint`, `tFinal` — are taken
 * at points scattered across the audio thread, the decoder and the link, and the only
 * moment they can be taken is as they pass.
 *
 * That is why this is in the shipped path rather than in a benchmark harness. A figure
 * reconstructed afterwards from logs is a figure with a different provenance from the run
 * it describes, and `docs/EVALUATION.md` section 4 asks for a hundred of them.
 *
 * ## Monotonic, not wall clock
 *
 * Every mark is `System.nanoTime`, which does not step when the clock is corrected. A wall
 * clock that jumps backwards mid-utterance produces a negative latency, and a negative
 * latency in a hundred-row median is worse than a missing one because it silently improves
 * the result.
 *
 * ## Marks are recorded once
 *
 * A stage that fires twice — an endpointer that retriggers, a decoder that emits a second
 * final — keeps the **first** mark. The alternative is a trace whose stages move as the
 * utterance goes on, and the resulting row would be internally inconsistent in a way no
 * reader could detect.
 */
class UtteranceClock(
    val utteranceId: String,
    val language: String,
    val mode: String,
    val transport: String,
    private val now: () -> Long = System::nanoTime,
) {
    private val marks = LinkedHashMap<Stage, Long>()

    /** The points a trace is assembled from, in the order they occur. */
    enum class Stage {
        MIC,
        VAD,
        FIRST_PARTIAL,
        ENDPOINT,
        FINAL,
        TX,
        RX,
        NORM,
        CHUNK1,
        AUDIO,
        DONE,
    }

    /** Started when the microphone delivers the first hop of the utterance. */
    fun start(): UtteranceClock = mark(Stage.MIC)

    /**
     * Records [stage] at this instant, if it has not already been recorded.
     *
     * @return this, so a caller on a hot path can chain without a local
     */
    fun mark(stage: Stage): UtteranceClock {
        marks.putIfAbsent(stage, now())
        return this
    }

    /** Records [stage] at a time the caller already has, for a mark taken elsewhere. */
    fun markAt(
        stage: Stage,
        nanos: Long,
    ): UtteranceClock {
        marks.putIfAbsent(stage, nanos)
        return this
    }

    operator fun get(stage: Stage): Long? = marks[stage]

    val isStarted: Boolean get() = Stage.MIC in marks

    /** Milliseconds since the microphone, for a live display that cannot wait for the end. */
    fun elapsedMillis(stage: Stage): Long? {
        val mic = marks[Stage.MIC] ?: return null
        val at = marks[stage] ?: return null
        return (at - mic) / 1_000_000
    }

    /**
     * Assembles the row.
     *
     * @param clockOffsetNanos the offset to the receiving handset's clock, from the
     *   clock-sync exchange. Zero when both ends are this device.
     * @throws IllegalStateException if the utterance never started. A trace with no origin
     *   cannot have its stages measured from anything, and returning a row of nulls would
     *   put a meaningless line in `latency.csv`.
     */
    fun toTrace(
        clockOffsetNanos: Long = 0,
        payloadBytes: Int? = null,
        frameBytes: Int? = null,
        compressionRatio: Double? = null,
        confidence: String? = null,
        templateId: Int? = null,
    ): UtteranceTrace {
        val mic = marks[Stage.MIC] ?: error("$utteranceId was never started")
        return UtteranceTrace(
            utteranceId = utteranceId,
            language = language,
            mode = mode,
            transport = transport,
            tMic = mic,
            tVad = marks[Stage.VAD],
            tFirstPartial = marks[Stage.FIRST_PARTIAL],
            tEndpoint = marks[Stage.ENDPOINT],
            tFinal = marks[Stage.FINAL],
            tTx = marks[Stage.TX],
            tRx = marks[Stage.RX],
            tNorm = marks[Stage.NORM],
            tChunk1 = marks[Stage.CHUNK1],
            tAudio = marks[Stage.AUDIO],
            tDone = marks[Stage.DONE],
            payloadBytes = payloadBytes,
            frameBytes = frameBytes,
            compressionRatio = compressionRatio,
            confidence = confidence,
            templateId = templateId,
            clockOffsetNanos = clockOffsetNanos,
        )
    }

    /**
     * Stages recorded out of order, which means a mark was taken at the wrong place.
     *
     * Not an exception. A trace with a stage out of order is still evidence — of a defect
     * in the instrumentation — and throwing it away at the moment of collection is how a
     * measurement bug survives to the next run.
     */
    fun outOfOrder(): List<Stage> {
        val recorded = Stage.entries.filter { it in marks }
        val wrong = ArrayList<Stage>()
        for (i in 1 until recorded.size) {
            if (marks.getValue(recorded[i]) < marks.getValue(recorded[i - 1])) wrong += recorded[i]
        }
        return wrong
    }
}

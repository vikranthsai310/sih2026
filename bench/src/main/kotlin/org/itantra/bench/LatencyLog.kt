package org.itantra.bench

import org.itantra.proto.ClockSync

/**
 * Records one row per utterance in the `latency.csv` schema fixed by
 * `docs/EVALUATION.md` section 6.
 *
 * The evaluation rules require the **median and p95 over at least 100 utterances**,
 * because a single best-case number is not a measurement. That is only possible if
 * every utterance is logged automatically during ordinary use, so this writes on the
 * live path rather than in a separate benchmark harness. Task **W3.11**.
 *
 * Stage timestamps are nanoseconds on the clock of the handset that took them.
 * Receiver-side stages are converted to the sender's clock through [ClockSync] before
 * any difference is taken — see [UtteranceTrace.endToEndMillis].
 */
class LatencyLog(
    private val sink: Appendable,
    /** False when the caller has already written the column line, as [ReportBundle] has. */
    writeHeader: Boolean = true,
) {
    private var wroteHeader = !writeHeader
    private var rows = 0

    val rowCount: Int get() = rows

    fun write(trace: UtteranceTrace) {
        if (!wroteHeader) {
            sink.append(COLUMNS.joinToString(",")).append('\n')
            wroteHeader = true
        }
        val fields = trace.toFields()
        // A row of the wrong arity silently shifts every later column, which is the
        // classic way a results file becomes quietly wrong. Refuse instead.
        check(fields.size == COLUMNS.size) {
            "row has ${fields.size} fields, schema has ${COLUMNS.size}"
        }
        // Same failure, different cause: a comma or newline inside a field splits it.
        // None of the current fields can contain one, but a later column that carried
        // recognised text would, and the corruption would be silent.
        val offender = fields.indexOfFirst { it.any(::breaksTheRow) }
        check(offender < 0) {
            "field ${COLUMNS[offender]} contains a separator: '${fields[offender]}'"
        }
        sink.append(fields.joinToString(",")).append('\n')
        rows++
    }

    companion object {
        /** Characters that would break the row apart if written into a field. */
        private fun breaksTheRow(c: Char) = c == ',' || c == '\n' || c == '\r'

        /**
         * The normative column order, `docs/EVALUATION.md` section 6. Held in one place
         * so the header and the rows cannot drift apart.
         */
        val COLUMNS =
            listOf(
                "utterance_id", "lang", "mode", "transport",
                "t_mic", "t_vad", "t_first_partial", "t_endpoint", "t_final",
                "t_tx", "t_rx", "t_norm", "t_chunk1", "t_audio", "t_done",
                "payload_bytes", "frame_bytes", "compression_ratio",
                "confidence", "template_id", "end_to_end_ms", "pipeline_ms",
            )
    }
}

/**
 * One utterance's journey, from the microphone on the sender to audio leaving the
 * speaker on the receiver.
 *
 * Every stage is nullable because a stage may legitimately not occur: an offline
 * recogniser produces no partial hypothesis, and a message sent as a template code has
 * no template-free payload. A stage that did not happen is written as an empty field,
 * never as a zero — a zero would be averaged into the results as if it were a
 * measurement.
 *
 * @param senderNanos stages timed on the sending handset
 * @param receiverNanos stages timed on the receiving handset, in *its* clock
 * @param clock the offset between the two, or null when both ends are the same device
 */
data class UtteranceTrace(
    val utteranceId: String,
    val language: String,
    val mode: String,
    val transport: String,
    val tMic: Long,
    val tVad: Long? = null,
    val tFirstPartial: Long? = null,
    val tEndpoint: Long? = null,
    val tFinal: Long? = null,
    val tTx: Long? = null,
    val tRx: Long? = null,
    val tNorm: Long? = null,
    val tChunk1: Long? = null,
    val tAudio: Long? = null,
    val tDone: Long? = null,
    val payloadBytes: Int? = null,
    val frameBytes: Int? = null,
    val compressionRatio: Double? = null,
    val confidence: String? = null,
    val templateId: Int? = null,
    val clockOffsetNanos: Long = 0,
) {
    /**
     * Stage timestamps as milliseconds since the microphone, which is what a reader
     * wants. A `receiver` stage was timed on the other handset, so the clock offset
     * comes off before the subtraction.
     */
    private fun relative(
        stage: Long?,
        receiver: Boolean = false,
    ): String {
        if (stage == null) return ""
        val local = if (receiver) stage - clockOffsetNanos else stage
        return ((local - tMic) / 1_000_000).toString()
    }

    /**
     * The figure the jury times: audio beginning on the receiver, minus the microphone
     * on the sender, with the two clocks reconciled.
     *
     * Null when the utterance never produced audio — which is a result worth keeping,
     * not a row to drop.
     */
    val endToEndMillis: Long?
        get() {
            val audio = tAudio ?: return null
            return (audio - clockOffsetNanos - tMic) / 1_000_000
        }

    /**
     * Release to sound: the operator letting go of transmit, to the first audio leaving
     * the receiver's speaker.
     *
     * ## Why this exists beside [endToEndMillis]
     *
     * [endToEndMillis] runs from the microphone opening, so it carries however long the
     * operator held the control. On push-to-talk that is the operator's speech, not the
     * system's delay: a three-second sentence produces a three-and-a-half-second
     * "latency", and quoting it against the 800-1200 ms budget in `docs/EVALUATION.md`
     * section 4 compares two different quantities.
     *
     * That budget is a sum of stages -- decode, transmit, normalise, synthesise, output --
     * every one of which happens **after** the endpoint. This is that sum, measured rather
     * than added up, and it is the figure the metrics screen puts in its headline.
     *
     * Both are kept and both are exported. A reader who wants the literal
     * microphone-to-speaker figure still has it; a reader comparing against the budget
     * gets the one that is comparable.
     *
     * Null when the utterance never reached an endpoint or never produced audio.
     */
    val pipelineMillis: Long?
        get() {
            val audio = tAudio ?: return null
            val endpoint = tEndpoint ?: return null
            return (audio - clockOffsetNanos - endpoint) / 1_000_000
        }

    fun toFields(): List<String> =
        listOf(
            utteranceId, language, mode, transport,
            // t_mic is the origin every other stage is measured from, so it is zero.
            "0",
            relative(tVad),
            relative(tFirstPartial),
            relative(tEndpoint),
            relative(tFinal),
            relative(tTx),
            relative(tRx, receiver = true),
            relative(tNorm, receiver = true),
            relative(tChunk1, receiver = true),
            relative(tAudio, receiver = true),
            relative(tDone, receiver = true),
            payloadBytes?.toString() ?: "",
            frameBytes?.toString() ?: "",
            compressionRatio?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
            confidence ?: "",
            templateId?.toString() ?: "",
            endToEndMillis?.toString() ?: "",
            pipelineMillis?.toString() ?: "",
        )
}

/**
 * Which figure a latency summary is over.
 *
 * The metrics screen quotes [UtteranceTrace.pipelineMillis] because that is what the
 * `docs/EVALUATION.md` section 4 budget is a sum of; `latency.csv` and anything auditing
 * the literal microphone-to-speaker claim want [UtteranceTrace.endToEndMillis]. Selecting
 * the figure rather than keeping two near-identical summarisers means the median, the
 * percentile rule and the refusal threshold cannot drift apart between the two.
 */
typealias LatencyFigure = (UtteranceTrace) -> Long?

/**
 * Summarises a run of utterances the way the reporting rules demand.
 *
 * Median and p95, never a mean and never a best case. The p95 is the number that
 * decides whether the system is usable under pressure: an operator remembers the
 * slowest exchange, not the average one.
 */
object LatencySummary {
    data class Stats(
        val n: Int,
        val medianMillis: Long,
        val p95Millis: Long,
        val worstMillis: Long,
        /** Rows set aside as physically impossible. See [of]. */
        val discarded: Int = 0,
    )

    /** The literal claim: microphone on the sender to speaker on the receiver. */
    val END_TO_END: LatencyFigure = UtteranceTrace::endToEndMillis

    /** The budgeted claim: the operator's release to speaker on the receiver. */
    val PIPELINE: LatencyFigure = UtteranceTrace::pipelineMillis

    /**
     * ## Negative figures are set aside rather than averaged
     *
     * A negative latency says audio reached the receiver's speaker before the operator let
     * go of the control, which did not happen. It means the clock offset was wrong — the
     * one failure [ClockSync] exists to prevent and the one it cannot always detect, since
     * a round trip queued behind a broadcast rotation looks like an ordinary slow one.
     *
     * Such a row must not be averaged in, and not because it is untidy: it drags the
     * median *down*. An error that flatters the result is the one kind nobody goes looking
     * for, and a run that quietly reports a better figure than the system achieved is
     * exactly what this whole module exists to make impossible. The count is carried out
     * in [Stats.discarded] so the screen can say how many, and the rows stay in
     * `latency.csv` untouched — the raw record is evidence of the defect.
     *
     * @return null when no utterance produced a usable figure, rather than a fabricated
     *   zero.
     */
    fun of(
        traces: List<UtteranceTrace>,
        figure: LatencyFigure = END_TO_END,
    ): Stats? {
        val measured = traces.mapNotNull(figure)
        val values = measured.filter { it >= 0 }.sorted()
        if (values.isEmpty()) return null
        return Stats(
            n = values.size,
            medianMillis = ClockSync.median(values),
            p95Millis = values[percentileIndex(values.size, 95)],
            worstMillis = values.last(),
            discarded = measured.size - values.size,
        )
    }

    /**
     * Nearest-rank percentile. With fewer than twenty samples the p95 is simply the
     * worst observation, which is honest: twenty samples cannot resolve a 95th
     * percentile, and the reporting rules ask for at least a hundred anyway.
     */
    internal fun percentileIndex(
        size: Int,
        percentile: Int,
    ): Int {
        val rank = Math.ceil(percentile / 100.0 * size).toInt()
        return (rank - 1).coerceIn(0, size - 1)
    }

    /** True once a run is large enough to report, per `docs/EVALUATION.md` section 4. */
    fun isReportable(
        traces: List<UtteranceTrace>,
        figure: LatencyFigure = END_TO_END,
    ): Boolean = traces.count { figure(it) != null } >= MINIMUM_UTTERANCES

    const val MINIMUM_UTTERANCES = 100
}

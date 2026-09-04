package org.itantra.bench

import org.itantra.proto.ClockSync

/**
 * The end-to-end figure broken into the stages that produced it. Tasks **W8.1**, **W8.3**.
 *
 * ## Why a single number is not enough on a metrics screen
 *
 * "1 040 ms" tells an operator nothing they can act on and tells an engineer nothing they
 * can fix. The budget in `docs/EVALUATION.md` section 4 is per stage, and a run that
 * misses it misses it *somewhere* — the difference between a slow decode and a slow link
 * is the difference between a thermal problem and a radio problem, and they have opposite
 * remedies.
 *
 * ## The stages sum to the whole, by construction
 *
 * Every stage is the gap between two consecutive timestamps, so the seven of them add up
 * to the end-to-end figure exactly. That is asserted rather than assumed: a stage table
 * that does not reconcile with the headline number is worse than no stage table, because
 * it invites a reader to trust a decomposition that has lost time somewhere unnamed.
 *
 * ## Crossing the clock boundary
 *
 * [TRANSMIT] is the one stage measured on two different handsets. `tFinal` is the
 * sender's clock and `tRx` is the receiver's, so the offset established by the clock-sync
 * exchange comes off before the subtraction — the same correction
 * [UtteranceTrace.endToEndMillis] applies. Every later stage is entirely on the receiver
 * and needs no correction, which is why the offset appears exactly once.
 */
enum class Stage(
    val label: String,
    /** The budget from `docs/EVALUATION.md` section 4, in milliseconds. */
    val budgetMillis: IntRange,
) {
    CAPTURE("Capture and buffering", 20..40),
    ENDPOINT("Endpoint silence window", 150..400),
    DECODE("Final decode after endpoint", 250..450),
    TRANSMIT("Framing, encryption, transmit", 20..60),
    NORMALISE("Normalisation", 0..10),
    SYNTHESIS("First synthesis chunk", 150..250),
    OUTPUT("Output pipeline", 30..80),
    ;

    /**
     * This stage's duration, or null when the utterance did not reach both ends of it.
     *
     * Null rather than zero. A stage that never happened and a stage that took no time are
     * different facts, and averaging the first as the second flatters every figure it
     * touches.
     */
    fun millisOf(trace: UtteranceTrace): Long? {
        val offset = trace.clockOffsetNanos
        return when (this) {
            CAPTURE -> gap(trace.tMic, trace.tVad)
            ENDPOINT -> gap(trace.tVad, trace.tEndpoint)
            DECODE -> gap(trace.tEndpoint, trace.tFinal)
            // The only stage spanning two handsets. The receiver's clock is corrected on
            // to the sender's before the subtraction.
            TRANSMIT -> gap(trace.tFinal, trace.tRx?.minus(offset))
            NORMALISE -> gap(trace.tRx, trace.tNorm)
            SYNTHESIS -> gap(trace.tNorm, trace.tChunk1)
            OUTPUT -> gap(trace.tChunk1, trace.tAudio)
        }
    }

    private fun gap(
        from: Long?,
        to: Long?,
    ): Long? {
        if (from == null || to == null) return null
        return (to - from) / 1_000_000
    }
}

/**
 * Per-stage and distribution statistics for a run. Tasks **W8.1**, **W8.3**.
 *
 * Median and p95 throughout, never a mean and never a best case — [LatencySummary] states
 * why. This object adds the two things a metrics screen needs on top of the headline
 * figure: where the time went, and what the spread looks like.
 */
object StageSummary {
    data class StageStats(
        val stage: Stage,
        val n: Int,
        val medianMillis: Long,
        val p95Millis: Long,
    ) {
        /** True when the **median** sits inside the budget. A p95 outside it is expected. */
        val withinBudget: Boolean get() = medianMillis <= stage.budgetMillis.last

        /** How far past the budget ceiling the median is, or zero. */
        val overBudgetMillis: Long get() = maxOf(0, medianMillis - stage.budgetMillis.last)
    }

    /**
     * @return one entry per stage that any utterance reached, in pipeline order. A stage
     *   nothing reached is absent rather than reported as zero.
     */
    fun byStage(traces: List<UtteranceTrace>): List<StageStats> =
        Stage.entries.mapNotNull { stage ->
            val values = traces.mapNotNull { stage.millisOf(it) }.sorted()
            if (values.isEmpty()) {
                null
            } else {
                StageStats(
                    stage = stage,
                    n = values.size,
                    medianMillis = ClockSync.median(values),
                    p95Millis = values[LatencySummary.percentileIndex(values.size, 95)],
                )
            }
        }

    /**
     * The stage medians will not generally sum to the end-to-end median — a median is not
     * additive — so this reconciles a **single trace** instead, which is where a missing
     * stage would actually hide.
     *
     * @return the milliseconds unaccounted for, or null if the trace is incomplete
     */
    fun unaccountedMillis(trace: UtteranceTrace): Long? {
        val total = trace.endToEndMillis ?: return null
        val stages = Stage.entries.map { it.millisOf(trace) ?: return null }
        return total - stages.sum()
    }

    /**
     * The end-to-end distribution, bucketed.
     *
     * A histogram rather than a mean because the shape is the finding. Two runs with the
     * same median look identical in a table and completely different here: one tight
     * around 900 ms, one bimodal with a second cluster at 1 800 ms where the decoder was
     * throttling. The second is a defect; the table would not have shown it.
     *
     * @param bucketMillis width of each bucket
     * @return buckets from zero to the slowest observation, including empty ones — a gap
     *   in the middle of a distribution is information, and omitting empty buckets would
     *   draw a bimodal run as a smooth one
     */
    fun histogram(
        traces: List<UtteranceTrace>,
        bucketMillis: Int = DEFAULT_BUCKET_MILLIS,
    ): List<Bucket> {
        require(bucketMillis > 0) { "bucket width must be positive: $bucketMillis" }
        val values = traces.mapNotNull { it.endToEndMillis }
        if (values.isEmpty()) return emptyList()

        val highest = values.max().toInt() / bucketMillis
        return (0..highest).map { index ->
            val from = index * bucketMillis
            Bucket(
                fromMillis = from,
                toMillis = from + bucketMillis,
                count = values.count { it >= from && it < from + bucketMillis },
            )
        }
    }

    data class Bucket(val fromMillis: Int, val toMillis: Int, val count: Int)

    /** 100 ms buckets: fine enough to show a second cluster, coarse enough to read. */
    const val DEFAULT_BUCKET_MILLIS = 100
}

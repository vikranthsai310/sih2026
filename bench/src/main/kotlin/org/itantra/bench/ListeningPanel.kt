package org.itantra.bench

import kotlin.math.sqrt

/**
 * Scoring for the two human panels. Tasks **W7.10** (mean opinion score) and **W7.11**
 * (intelligibility). `docs/EVALUATION.md` section 3.
 *
 * ## Why the softest number in the project gets the strictest code
 *
 * MOS is a number people make up. Not dishonestly — a five-point scale is genuinely how
 * synthesis quality is judged — but there is no ground truth, the scale is not linear, and
 * two panels of the same size can differ by half a point. That is exactly why the
 * arithmetic around it has to be beyond argument: the measurement is soft, so the
 * reporting must not be.
 *
 * Three rules follow, and all three are enforced here rather than remembered:
 *
 * 1. **A MOS without its panel size is not a measurement.** [MosPanel.isReportable] is
 *    false below fifteen listeners, and [MosPanel.report] always carries the count.
 * 2. **The listener is the unit, not the rating.** Ten ratings from one listener are not
 *    ten independent observations. Ratings are averaged per listener first and the
 *    interval computed across listener means, which is a wider and more honest interval
 *    than pooling every rating would give.
 * 3. **A flat rater is reported, not dropped.** A listener who gave every sample the same
 *    score contributes no information about relative quality, and silently removing them
 *    is a judgement about the data taken after seeing it. They are counted and named.
 */
object ListeningPanel {
    /** `docs/EVALUATION.md` section 3: fifteen native speakers per language. */
    const val MINIMUM_PANEL = 15

    /** The MOS target, and the intelligibility target as word accuracy. */
    const val MOS_TARGET = 3.8
    const val INTELLIGIBILITY_TARGET = 0.95
}

/** One listener's opinion of one sample, on the five-point scale. */
data class Rating(
    val listener: String,
    val sampleId: String,
    val score: Int,
) {
    init {
        require(score in 1..5) { "$listener rated $sampleId $score; the scale is 1 to 5" }
        require(listener.isNotBlank()) { "a rating with no listener cannot be aggregated" }
    }
}

/**
 * A language's MOS panel. Task **W7.10**.
 *
 * @param ratings every rating collected; blind, randomised and interleaved with a
 *   reference recording as the protocol requires — this class scores what the protocol
 *   produced and cannot verify that it was followed
 */
class MosPanel(
    val lang: String,
    val ratings: List<Rating>,
) {
    /** Mean score per listener. The unit of analysis — see the note on [ListeningPanel]. */
    val byListener: Map<String, Double> =
        ratings.groupBy { it.listener }.mapValues { (_, rs) -> rs.map { it.score }.average() }

    val panelSize: Int get() = byListener.size

    val sampleCount: Int get() = ratings.map { it.sampleId }.distinct().size

    /** The mean of the listener means, not of the pooled ratings. */
    val mos: Double? get() = if (byListener.isEmpty()) null else byListener.values.average()

    /** Standard deviation across listeners. Null below two listeners, where it is undefined. */
    val standardDeviation: Double?
        get() {
            if (panelSize < 2) return null
            val mean = byListener.values.average()
            val variance = byListener.values.sumOf { (it - mean) * (it - mean) } / (panelSize - 1)
            return sqrt(variance)
        }

    /**
     * Ninety-five per cent interval on the mean, normal approximation.
     *
     * The approximation is stated rather than hidden: at fifteen listeners a t-interval is
     * about fourteen per cent wider, so this is the optimistic one of the two. It is used
     * because it is the one a reader can check with a calculator, and the difference is far
     * smaller than the between-panel variation that makes MOS soft in the first place.
     */
    val confidence95: ClosedFloatingPointRange<Double>?
        get() {
            val mean = mos ?: return null
            val sd = standardDeviation ?: return null
            val margin = 1.96 * sd / sqrt(panelSize.toDouble())
            return (mean - margin)..(mean + margin)
        }

    /** Listeners who gave every sample the same score. Counted and named, never removed. */
    val flatRaters: List<String>
        get() =
            ratings.groupBy { it.listener }
                .filterValues { rs -> rs.size > 1 && rs.map { it.score }.distinct().size == 1 }
                .keys.sorted()

    /**
     * False until the panel is large enough for the figure to mean anything. A MOS from
     * four listeners is an anecdote with a decimal point.
     */
    val isReportable: Boolean get() = panelSize >= ListeningPanel.MINIMUM_PANEL

    val meetsTarget: Boolean get() = isReportable && (mos ?: 0.0) >= ListeningPanel.MOS_TARGET

    /** Never a bare number: the panel size travels with it, always. */
    fun report(): String {
        val mean = mos ?: return "$lang: no ratings collected"
        val interval =
            confidence95?.let {
                " (95%% CI %.2f–%.2f)".format(java.util.Locale.ROOT, it.start, it.endInclusive)
            } ?: ""
        val caveat = if (isReportable) "" else " — BELOW the ${ListeningPanel.MINIMUM_PANEL}-listener minimum"
        return "%s: MOS %.2f, n=%d listeners, %d samples%s%s".format(
            java.util.Locale.ROOT,
            lang,
            mean,
            panelSize,
            sampleCount,
            interval,
            caveat,
        )
    }
}

/** What one listener wrote down for one synthesised sample. */
data class Transcription(
    val listener: String,
    val sampleId: String,
    val heard: String,
)

/**
 * The intelligibility test. Task **W7.11**.
 *
 * Native listeners transcribe synthesised output, and the score is word **accuracy** — one
 * minus the word error rate — against the text that was synthesised. `EVALUATION.md`
 * section 3 puts the target at 95 %.
 *
 * ## Why this is the test that clears a numeral table
 *
 * Every language's `normalise.<lang>.json` carries `reviewed: false` until a native speaker
 * has checked its numerals. This is that check, and it is a better one than reading the
 * table: a wrong numeral word in a table is a spelling somebody has to notice, whereas a
 * wrong numeral word in a synthesised sentence is a listener writing down a different
 * number. Sentences carrying numerals, times and callsigns should be over-represented in
 * the sample set for that reason.
 */
class IntelligibilityPanel(
    val lang: String,
    private val scorer: WerScorer = WerScorer(),
) {
    data class Result(
        val lang: String,
        val panelSize: Int,
        val sampleCount: Int,
        val accuracy: Double?,
        val byListener: Map<String, Double>,
        val worstSamples: List<Pair<String, Double>>,
    ) {
        val isReportable: Boolean get() = panelSize >= ListeningPanel.MINIMUM_PANEL

        val meetsTarget: Boolean
            get() = isReportable && (accuracy ?: 0.0) >= ListeningPanel.INTELLIGIBILITY_TARGET

        fun report(): String {
            val value = accuracy ?: return "$lang: no transcriptions collected"
            val caveat =
                if (isReportable) "" else " — BELOW the ${ListeningPanel.MINIMUM_PANEL}-listener minimum"
            return "%s: intelligibility %.1f%%, n=%d listeners, %d samples%s".format(
                java.util.Locale.ROOT,
                lang,
                value * 100,
                panelSize,
                sampleCount,
                caveat,
            )
        }
    }

    /**
     * @param sources sample identifier to the text that was synthesised
     * @throws IllegalArgumentException if a transcription names a sample that was never
     *   played. Scoring it against nothing would silently drop a listener's answer, and a
     *   mismatched identifier usually means the randomisation and the answer sheet came
     *   apart — which invalidates the session rather than one row of it.
     */
    fun score(
        sources: Map<String, String>,
        transcriptions: List<Transcription>,
    ): Result {
        for (t in transcriptions) {
            require(t.sampleId in sources) {
                "$lang: '${t.sampleId}' was transcribed but never played"
            }
        }

        val perListener =
            transcriptions.groupBy { it.listener }.mapValues { (_, entries) ->
                entries.map { sources.getValue(it.sampleId) to it.heard }
                    .let { pairs -> 1.0 - scorer.corpusWer(pairs).wer }
            }

        val perSample =
            transcriptions.groupBy { it.sampleId }.mapValues { (id, entries) ->
                entries.map { sources.getValue(id) to it.heard }
                    .let { pairs -> 1.0 - scorer.corpusWer(pairs).wer }
            }

        return Result(
            lang = lang,
            panelSize = perListener.size,
            sampleCount = transcriptions.map { it.sampleId }.distinct().size,
            // Averaged across listeners, for the same reason MOS is: one listener's ten
            // transcriptions are not ten independent observations.
            accuracy = if (perListener.isEmpty()) null else perListener.values.average(),
            byListener = perListener,
            // The sentences listeners got wrong are the actionable output. A numeral table
            // that needs fixing shows up here as one sample everybody mis-heard.
            worstSamples = perSample.entries.sortedBy { it.value }.take(WORST_SAMPLES).map { it.key to it.value },
        )
    }

    private companion object {
        const val WORST_SAMPLES = 5
    }
}

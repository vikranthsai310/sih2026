package org.itantra.bench

/**
 * The full accuracy run: ten languages, four noise conditions, and the two engineering
 * switches. Tasks **W7.7**, **W7.8** and **W7.9**.
 *
 * ## Why one runner rather than three
 *
 * The three tasks ask for three tables — WER across ten languages and four SNRs (W7.8),
 * CTER with and without biasing (W7.9), and a per-language decision on noise suppression
 * taken from measurement (W7.7). They are three views of one experiment, and running them
 * separately would guarantee the three tables disagree: a different corpus order, a
 * different noise seed, a different build, and the numbers no longer compose. One matrix,
 * one seed, one pass.
 *
 * ```
 *   10 languages × 4 conditions × suppression{on,off} × biasing{on,off} = 160 cells
 * ```
 *
 * ## What is measured and what is decided
 *
 * This class **measures**. [SuppressionPolicy] **decides**, from the numbers this class
 * produced, and is separate for exactly that reason — W7.7 says the per-language
 * suppression decision comes from measurement rather than preference, and a decision made
 * inside the measurement loop is not auditable.
 *
 * ## Status
 *
 * The runner, the scoring and the report format are complete and tested against a stub
 * recogniser. The **numbers are not in**: they need the acoustic model, the evaluation
 * corpus and the target handset, and a matrix full of figures produced on a laptop against
 * a stub would be worse than an empty one. [Report.isMeasured] is what a caller asks
 * before quoting anything.
 */
class AccuracyMatrix(
    private val mixer: NoiseMixer = NoiseMixer(),
    private val scorer: WerScorer = WerScorer(),
) {
    /** One utterance of evaluation audio and what was actually said. */
    class Utterance(
        val audio: ShortArray,
        val reference: String,
    )

    /**
     * One cell of the matrix.
     *
     * @param snrDb null for clean speech, which is the reference figure rather than a
     *   fifth noise level
     */
    data class Condition(
        val lang: String,
        val snrDb: Double?,
        val noise: NoiseMixer.Noise,
        val suppression: Boolean,
        val biasing: Boolean,
    ) {
        val conditionName: String get() = snrDb?.let { "snr${it.toInt()}" } ?: "clean"

        fun label(): String =
            "$lang $conditionName ${if (suppression) "ns-on" else "ns-off"} " +
                if (biasing) "biased" else "unbiased"
    }

    /**
     * A recogniser under one set of switches. An interface rather than a concrete class so
     * the matrix can be exercised in CI without a model — see the class note on status.
     */
    fun interface Recogniser {
        fun recognise(
            audio: ShortArray,
            condition: Condition,
        ): String
    }

    data class Cell(
        val condition: Condition,
        val wer: Double,
        val cter: Double,
        val criticalOccurrences: Int,
        val utterances: Int,
    )

    /**
     * Runs every condition over the corpus.
     *
     * @param corpus evaluation utterances per language code
     * @param criticalTerms the critical vocabulary per language, normally the shipped
     *   biasing lexicon
     */
    fun run(
        corpus: Map<String, List<Utterance>>,
        criticalTerms: Map<String, Collection<String>>,
        conditions: List<Condition>,
        recogniser: Recogniser,
    ): Report {
        val cells = ArrayList<Cell>(conditions.size)

        for (condition in conditions) {
            val utterances = corpus[condition.lang].orEmpty()
            require(utterances.isNotEmpty()) {
                "no evaluation corpus for ${condition.lang}; an empty cell would be scored as perfect"
            }
            val critical = CriticalTermScorer(criticalTerms[condition.lang].orEmpty(), scorer)

            val pairs =
                utterances.map { utterance ->
                    // Mixed here rather than inside the recogniser, so every cell at a
                    // given SNR sees byte-identical audio whatever the switches are. The
                    // suppression and biasing deltas are then attributable to the
                    // switches and to nothing else.
                    val audio =
                        condition.snrDb?.let { snr ->
                            val noise = mixer.generate(condition.noise, utterance.audio.size)
                            mixer.mix(utterance.audio, noise, snr)
                        } ?: utterance.audio
                    utterance.reference to recogniser.recognise(audio, condition)
                }

            val wer = scorer.corpusWer(pairs)
            val cter = critical.corpus(pairs)
            cells +=
                Cell(
                    condition = condition,
                    wer = wer.wer,
                    cter = cter.cter,
                    criticalOccurrences = cter.occurrences,
                    utterances = utterances.size,
                )
        }
        return Report(cells, mixer.seedUsed)
    }

    /** The matrix as measured, plus what can be read off it. */
    data class Report(val cells: List<Cell>, val noiseSeed: Int) {
        /**
         * False until a real run has happened. A report of zero cells is not a report of
         * perfect accuracy, and the two must never be confused in a results file — the
         * same rule [ScorecardRow] applies to an absent measurement.
         */
        val isMeasured: Boolean get() = cells.isNotEmpty()

        val languages: List<String> get() = cells.map { it.condition.lang }.distinct()

        fun cell(
            lang: String,
            snrDb: Double?,
            suppression: Boolean,
            biasing: Boolean,
        ): Cell? =
            cells.firstOrNull {
                it.condition.lang == lang &&
                    it.condition.snrDb == snrDb &&
                    it.condition.suppression == suppression &&
                    it.condition.biasing == biasing
            }

        /**
         * The W7.9 table: CTER with and without biasing, side by side, so the delta is
         * visible rather than reconstructed from two documents. The delta is directly
         * attributable to an engineering decision, which is the point of reporting it.
         */
        fun biasingDelta(
            lang: String,
            snrDb: Double?,
            suppression: Boolean,
        ): Double? {
            val biased = cell(lang, snrDb, suppression, biasing = true) ?: return null
            val unbiased = cell(lang, snrDb, suppression, biasing = false) ?: return null
            return unbiased.cter - biased.cter
        }

        /** `accuracy-matrix.csv`. One row per cell; no figure is ever computed by hand. */
        fun toCsv(): String =
            buildString {
                append(CSV_COLUMNS.joinToString(",")).append('\n')
                for (cell in cells) {
                    append(cell.condition.lang).append(',')
                        .append(cell.condition.conditionName).append(',')
                        .append(cell.condition.noise.name.lowercase()).append(',')
                        .append(cell.condition.suppression).append(',')
                        .append(cell.condition.biasing).append(',')
                        .append(pct(cell.wer)).append(',')
                        .append(pct(cell.cter)).append(',')
                        .append(cell.criticalOccurrences).append(',')
                        .append(cell.utterances).append(',')
                        .append(noiseSeed).append('\n')
                }
            }

        private companion object {
            fun pct(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value * 100.0)
        }
    }

    companion object {
        val CSV_COLUMNS =
            listOf(
                "lang", "condition", "noise", "suppression", "biasing",
                "wer_pct", "cter_pct", "critical_occurrences", "utterances", "noise_seed",
            )

        /** Clean plus the three SNRs the accuracy criterion is reported at. */
        val CONDITIONS: List<Double?> = listOf(null) + NoiseMixer.SNR_LEVELS

        /**
         * Every cell for one language, in a stable order so two runs produce diffable
         * files.
         */
        fun conditionsFor(
            lang: String,
            noise: NoiseMixer.Noise = NoiseMixer.Noise.CROWD,
        ): List<Condition> =
            CONDITIONS.flatMap { snr ->
                listOf(false, true).flatMap { suppression ->
                    listOf(false, true).map { biasing ->
                        Condition(lang, snr, noise, suppression, biasing)
                    }
                }
            }
    }
}

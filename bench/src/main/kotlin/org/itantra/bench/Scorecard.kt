package org.itantra.bench

import java.util.Locale

/**
 * `scorecard.csv` — one row per language per run. Task **W4.11**.
 *
 * The schema is fixed by `docs/EVALUATION.md` section 6. Two of its columns are not
 * results but provenance, and they are what make the rest admissible:
 *
 * - **`device`** — every figure in this project is a figure *on a named handset*. A
 *   scorecard that does not name its hardware is not evidence, and a jury that has seen
 *   a hundred demos knows it.
 * - **`soak_minutes`** — how long the device had been working when the row was taken.
 *   An entry-tier phone throttles after about ten minutes of continuous inference and
 *   its real-time factor can double, so a cold number is not the number anyone will
 *   see in the field (requirement N9).
 */
class ScorecardWriter(private val sink: Appendable) {
    private var wroteHeader = false
    private var rows = 0

    val rowCount: Int get() = rows

    fun write(row: ScorecardRow) {
        if (!wroteHeader) {
            sink.append(COLUMNS.joinToString(",")).append('\n')
            wroteHeader = true
        }
        val fields = row.toFields()
        check(fields.size == COLUMNS.size) {
            "row has ${fields.size} fields, schema has ${COLUMNS.size}"
        }
        val offender = fields.indexOfFirst { f -> f.any { it == ',' || it == '\n' || it == '\r' } }
        check(offender < 0) {
            "field ${COLUMNS[offender]} contains a separator: '${fields[offender]}'"
        }
        sink.append(fields.joinToString(",")).append('\n')
        rows++
    }

    companion object {
        val COLUMNS =
            listOf(
                "lang", "model", "model_bytes", "voice_bytes",
                "wer_clean", "wer_snr20", "wer_snr10", "wer_snr5",
                "cter_biased", "cter_unbiased",
                "mos", "mos_panel_n",
                "rtf_asr", "rtf_tts",
                "device", "build", "soak_minutes", "date", "noise_seed",
            )
    }
}

/**
 * One language's results.
 *
 * Every measurement is nullable, and an absent one is written as an empty field rather
 * than a zero. A zero WER means perfect recognition; a WER that was never measured means
 * nothing at all, and the two must never be confused in a results file.
 */
data class ScorecardRow(
    val lang: String,
    val model: String,
    val modelBytes: Long,
    val voiceBytes: Long?,
    val werClean: Double? = null,
    val werSnr20: Double? = null,
    val werSnr10: Double? = null,
    val werSnr5: Double? = null,
    val cterBiased: Double? = null,
    val cterUnbiased: Double? = null,
    /**
     * Mean opinion score, with [mosPanelN] beside it. A MOS quoted without a panel size
     * is not a measurement — `docs/EVALUATION.md` section 3.
     */
    val mos: Double? = null,
    val mosPanelN: Int? = null,
    val rtfAsr: Double? = null,
    val rtfTts: Double? = null,
    val device: String,
    val build: String,
    val soakMinutes: Int,
    val date: String,
    val noiseSeed: Int = NoiseMixer.DEFAULT_SEED,
) {
    init {
        require(device.isNotBlank()) { "$lang: a scorecard row must name its device" }
        require(build.isNotBlank()) { "$lang: a scorecard row must name its build" }
        require(mos == null || mosPanelN != null) {
            "$lang: a mean opinion score requires its panel size"
        }
    }

    /** True once every figure the accuracy criterion needs is present. */
    val isComplete: Boolean
        get() =
            listOf(werClean, werSnr20, werSnr10, werSnr5).all { it != null } &&
                rtfAsr != null

    fun toFields(): List<String> =
        listOf(
            lang,
            model,
            modelBytes.toString(),
            voiceBytes?.toString() ?: "",
            pct(werClean), pct(werSnr20), pct(werSnr10), pct(werSnr5),
            pct(cterBiased), pct(cterUnbiased),
            mos?.let { fmt(it, 2) } ?: "",
            mosPanelN?.toString() ?: "",
            rtfAsr?.let { fmt(it, 3) } ?: "",
            rtfTts?.let { fmt(it, 3) } ?: "",
            device,
            build,
            soakMinutes.toString(),
            date,
            noiseSeed.toString(),
        )

    private companion object {
        /** Error rates are written as percentages to one decimal, the reporting convention. */
        fun pct(value: Double?): String = value?.let { fmt(it * 100.0, 1) } ?: ""

        /** Always a decimal point: a decimal comma would split the field. */
        fun fmt(
            value: Double,
            places: Int,
        ): String = String.format(Locale.ROOT, "%.${places}f", value)
    }
}

package org.itantra.bench

/**
 * Whether noise suppression is enabled, decided per language **from measurement**. Task
 * **W7.7**.
 *
 * ## Why this is not a setting somebody chose
 *
 * Noise suppression is not free and it is not uniformly good. It removes energy the
 * recogniser might have used, and whether that trade pays depends on the acoustic model,
 * the language and the noise — a suppressor tuned on English telephony can measurably hurt
 * a Dravidian language with long vowels and a different spectral balance. "Noise
 * suppression is on because noise is bad" is an assumption, and this file exists so that
 * it is a measurement instead.
 *
 * The decision is taken from an [AccuracyMatrix.Report] by a rule fixed **in advance**, so
 * that it cannot be adjusted after the numbers arrive to justify what was already built.
 * That is the whole discipline: the rule is arguable, the application of it is not.
 *
 * ## The rule
 *
 * Suppression is enabled for a language only if **both** hold:
 *
 * 1. it improves critical-term error at the two adverse SNRs (+10 and +5 dB) by at least
 *    [REQUIRED_MARGIN] — the conditions suppression exists for, judged on the metric that
 *    matters operationally rather than on overall WER; and
 * 2. it does not make clean speech worse by more than [TOLERATED_CLEAN_LOSS].
 *
 * The second condition is the one that catches the plausible mistake. A suppressor can pay
 * for itself at +5 dB and quietly cost accuracy in the quiet room where most messages are
 * actually spoken, and a decision taken only on the adverse cases would ship that.
 *
 * The margin is not zero because a difference smaller than it is noise in the measurement
 * rather than a difference: at a few hundred utterances per cell, a fraction of a per cent
 * is not evidence of anything, and turning a stage on for it is how a system accumulates
 * complexity nobody can justify.
 */
object SuppressionPolicy {
    /**
     * A CTER improvement below this is not evidence at the corpus sizes involved. One
     * percentage point.
     */
    const val REQUIRED_MARGIN = 0.01

    /** Half a point of clean-speech CTER is the most a noisy-case gain may cost. */
    const val TOLERATED_CLEAN_LOSS = 0.005

    /** The conditions suppression exists for. */
    val ADVERSE_SNRS = listOf(10.0, 5.0)

    data class Decision(
        val lang: String,
        val enabled: Boolean,
        val adverseGain: Double?,
        val cleanLoss: Double?,
        val reason: String,
    ) {
        /** True when the matrix did not carry the cells this decision needs. */
        val isUndecided: Boolean get() = adverseGain == null
    }

    /**
     * @param report a completed [AccuracyMatrix.Report]; biasing is held at its shipped
     *   setting (on) so the two switches are not varied at once
     */
    fun decide(
        report: AccuracyMatrix.Report,
        lang: String,
        biasing: Boolean = true,
    ): Decision {
        val adverse =
            ADVERSE_SNRS.mapNotNull { snr ->
                val off = report.cell(lang, snr, suppression = false, biasing = biasing)
                val on = report.cell(lang, snr, suppression = true, biasing = biasing)
                if (off == null || on == null) null else off.cter - on.cter
            }

        if (adverse.size != ADVERSE_SNRS.size) {
            // Not measured is not the same as measured and found unhelpful. Defaulting to
            // "off" here would be a decision dressed up as a result.
            return Decision(
                lang = lang,
                enabled = false,
                adverseGain = null,
                cleanLoss = null,
                reason = "not measured at ${ADVERSE_SNRS.joinToString(" and ") { "+${it.toInt()} dB" }}",
            )
        }

        // The worse of the two adverse cases, not the average: a stage that helps at +5 dB
        // and hurts at +10 dB has not earned being on.
        val gain = adverse.min()

        val cleanOff = report.cell(lang, null, suppression = false, biasing = biasing)
        val cleanOn = report.cell(lang, null, suppression = true, biasing = biasing)
        val cleanLoss =
            if (cleanOff == null || cleanOn == null) null else cleanOn.cter - cleanOff.cter

        return when {
            gain < REQUIRED_MARGIN ->
                Decision(
                    lang,
                    false,
                    gain,
                    cleanLoss,
                    "gain of ${pct(gain)} at the worse adverse SNR is below the " +
                        "${pct(REQUIRED_MARGIN)} margin",
                )

            cleanLoss == null ->
                Decision(lang, false, gain, null, "clean-speech cells missing; cannot rule out a regression")

            cleanLoss > TOLERATED_CLEAN_LOSS ->
                Decision(
                    lang,
                    false,
                    gain,
                    cleanLoss,
                    "helps by ${pct(gain)} in noise but costs ${pct(cleanLoss)} on clean speech",
                )

            else ->
                Decision(
                    lang,
                    true,
                    gain,
                    cleanLoss,
                    "improves critical-term error by ${pct(gain)} in noise at no clean-speech cost",
                )
        }
    }

    fun decideAll(
        report: AccuracyMatrix.Report,
        biasing: Boolean = true,
    ): List<Decision> = report.languages.map { decide(report, it, biasing) }

    /** The W7.7 report: one line per language, with the number that decided it. */
    fun toCsv(decisions: List<Decision>): String =
        buildString {
            append("lang,suppression,adverse_gain_pct,clean_loss_pct,reason\n")
            for (d in decisions) {
                append(d.lang).append(',')
                    .append(if (d.isUndecided) "undecided" else d.enabled.toString()).append(',')
                    .append(d.adverseGain?.let { pct(it) } ?: "").append(',')
                    .append(d.cleanLoss?.let { pct(it) } ?: "").append(',')
                    .append('"').append(d.reason.replace("\"", "\"\"")).append('"')
                    .append('\n')
            }
        }

    private fun pct(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f pp", value * 100.0)
}

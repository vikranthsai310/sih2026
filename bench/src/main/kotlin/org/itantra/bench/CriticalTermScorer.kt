package org.itantra.bench

/**
 * Critical-term error rate. `docs/EVALUATION.md` section 2.
 *
 * ## Why this is the figure that matters
 *
 * Overall word error rate is a poor proxy for usefulness. A recogniser that drops "the"
 * and "is" from every sentence scores badly and loses nothing; one that hears
 * *"सेक्टर सत्रह"* as *"सेक्टर सात"* scores well and sends a team to the wrong place. CTER
 * measures only the words whose loss changes what happens.
 *
 * The definition is the one in `EVALUATION.md`: over a fixed list of critical terms per
 * language, the proportion of **reference occurrences** not present in the hypothesis,
 * counted at the term level after NFC normalisation.
 *
 * ## Occurrences, not sentences
 *
 * A term is counted once per appearance, so a sentence naming two sectors and losing one
 * scores 50 % rather than 0 % or 100 %. Counting is by multiset: three occurrences in the
 * reference against two in the hypothesis is one miss, however they are arranged. That is
 * deliberately insensitive to word order — a recogniser that hears all the right critical
 * words in the wrong order has made a different error, and it is the WER's job to catch
 * it, not this metric's.
 *
 * ## What it deliberately does not do
 *
 * It does not credit near misses. आग and आठ differ by one character and mean *fire* and
 * *eight*; a metric that scored them as nearly right would be measuring the wrong thing.
 * The term is present or it is not.
 *
 * @param terms the critical vocabulary, usually the language's shipped biasing lexicon
 */
class CriticalTermScorer(
    terms: Collection<String>,
    private val scorer: WerScorer = WerScorer(),
) {
    /** Tokenised once at construction: this runs over a whole corpus, per condition. */
    private val tokenised: List<List<String>> =
        terms.map { scorer.tokenise(it) }.filter { it.isNotEmpty() }.distinct()

    val termCount: Int get() = tokenised.size

    data class Score(val occurrences: Int, val missed: Int) {
        /** Zero when the reference contained no critical vocabulary at all. */
        val cter: Double get() = if (occurrences == 0) 0.0 else missed.toDouble() / occurrences

        val percent: Double get() = cter * 100.0

        operator fun plus(other: Score) = Score(occurrences + other.occurrences, missed + other.missed)
    }

    fun score(
        reference: String,
        hypothesis: String,
    ): Score {
        val ref = scorer.tokenise(reference)
        val hyp = scorer.tokenise(hypothesis)

        var occurrences = 0
        var missed = 0
        for (term in tokenised) {
            val inReference = countOccurrences(ref, term)
            if (inReference == 0) continue
            val inHypothesis = countOccurrences(hyp, term)
            occurrences += inReference
            missed += maxOf(0, inReference - inHypothesis)
        }
        return Score(occurrences, missed)
    }

    fun corpus(pairs: List<Pair<String, String>>): Score =
        pairs.fold(Score(0, 0)) { total, (reference, hypothesis) ->
            total + score(reference, hypothesis)
        }

    /**
     * Non-overlapping occurrences of [term] in [tokens].
     *
     * Non-overlapping matters for a repeated single word: "मदद मदद" is two occurrences,
     * and a hypothesis with one of them has missed one rather than none.
     */
    private fun countOccurrences(
        tokens: List<String>,
        term: List<String>,
    ): Int {
        if (term.isEmpty() || term.size > tokens.size) return 0
        var count = 0
        var i = 0
        while (i <= tokens.size - term.size) {
            var matches = true
            for (j in term.indices) {
                if (tokens[i + j] != term[j]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                count++
                i += term.size
            } else {
                i++
            }
        }
        return count
    }
}

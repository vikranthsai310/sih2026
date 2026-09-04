package org.itantra.bench

import java.text.Normalizer

/**
 * Word and character error rates. Task **W4.8**.
 *
 * > **The single source of truth. No figure is ever computed by hand.**
 *
 * That rule is the reason this class exists rather than a spreadsheet. Every accuracy
 * number that appears in the report, the scorecard or the presentation comes from here,
 * so the comparison rules are written down once and applied identically to every
 * language, every noise condition and every run.
 *
 * ## Comparison rules, `docs/EVALUATION.md`
 *
 * | Step | Why |
 * | --- | --- |
 * | Unicode NFC | Devanagari and Malayalam have several byte sequences per visible glyph; without normalising, identical text scores as an error |
 * | Punctuation stripped | The recogniser emits none, so counting it would penalise a correct transcript |
 * | Case folded | Only affects Latin script, but silently inflates English error otherwise |
 * | Whitespace collapsed | Tokenisation must not depend on how many spaces a transcriber typed |
 * | Digits read as words | The recogniser writes "सत्रह" where the reference says "17"; these are the same answer |
 *
 * The last one is the rule most often skipped, and skipping it flatters the result in
 * one direction and penalises it in the other depending on which side holds the digits.
 * It is applied to **both** sides so it cannot do either.
 */
class WerScorer(
    /** Converts a run of digits into its spoken form for this language. */
    private val spokenNumerals: (String) -> String = { it },
) {
    /**
     * @param substitutions a word recognised as a different word
     * @param deletions a word in the reference the recogniser did not produce
     * @param insertions a word the recogniser produced that is not in the reference
     * @param referenceWords the denominator: N, the reference length
     */
    data class Score(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWords: Int,
    ) {
        val errors: Int get() = substitutions + deletions + insertions

        /**
         * Word error rate as a fraction.
         *
         * It can exceed 1.0 — a recogniser that hallucinates a long sentence from a
         * short one genuinely does score above 100 %, and clamping it would hide the
         * worst failure mode there is.
         */
        val wer: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords

        val percent: Double get() = wer * 100.0

        val isPerfect: Boolean get() = errors == 0
    }

    fun score(
        reference: String,
        hypothesis: String,
    ): Score {
        val ref = tokenise(reference)
        val hyp = tokenise(hypothesis)
        return align(ref, hyp)
    }

    /** Character error rate, over the same normalised text. Used for Indic scripts. */
    fun characterErrorRate(
        reference: String,
        hypothesis: String,
    ): Double {
        val ref = tokenise(reference).joinToString("").toList()
        val hyp = tokenise(hypothesis).joinToString("").toList()
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0
        val score = align(ref, hyp)
        return score.errors.toDouble() / ref.size
    }

    /** Mean WER over a corpus, weighted by reference length — not a mean of means. */
    fun corpusWer(pairs: List<Pair<String, String>>): Score {
        var s = 0
        var d = 0
        var i = 0
        var n = 0
        for ((reference, hypothesis) in pairs) {
            val one = score(reference, hypothesis)
            s += one.substitutions
            d += one.deletions
            i += one.insertions
            n += one.referenceWords
        }
        return Score(s, d, i, n)
    }

    /**
     * The normalisation every comparison passes through. Exposed because a scorer whose
     * preprocessing cannot be inspected is not evidence.
     */
    fun tokenise(text: String): List<String> {
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFC)
        val stripped = normalised.filterNot { it.isPunctuation() }
        return stripped
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .map { if (it.all(Char::isDigit)) spokenNumerals(it) else it }
            .flatMap { it.split(WHITESPACE) }
            .filter { it.isNotBlank() }
    }

    /** Levenshtein alignment, counting each edit type separately. */
    private fun <T> align(
        ref: List<T>,
        hyp: List<T>,
    ): Score {
        val n = ref.size
        val m = hyp.size
        if (n == 0) return Score(0, 0, m, 0)
        if (m == 0) return Score(0, n, 0, n)

        // distance[i][j] plus the edit counts that produced it. Two rows are enough for
        // the distance, but the counts need the same rolling treatment.
        var previous = Array(m + 1) { j -> Cell(j, 0, 0, j) }
        previous[0] = Cell(0, 0, 0, 0)

        for (i in 1..n) {
            val current = arrayOfNulls<Cell>(m + 1)
            current[0] = Cell(i, 0, i, 0)
            for (j in 1..m) {
                val match = ref[i - 1] == hyp[j - 1]
                val substitute =
                    previous[j - 1].let {
                        Cell(
                            it.cost + if (match) 0 else 1,
                            it.subs + if (match) 0 else 1,
                            it.dels,
                            it.ins,
                        )
                    }
                val delete = previous[j].let { Cell(it.cost + 1, it.subs, it.dels + 1, it.ins) }
                val insert = current[j - 1]!!.let { Cell(it.cost + 1, it.subs, it.dels, it.ins + 1) }

                // Ties prefer substitution, then deletion: the conventional choice, and
                // it keeps the counts stable across runs.
                current[j] = minOf(substitute, delete, insert, compareBy { it.cost })
            }
            previous = current.requireNoNulls()
        }

        val end = previous[m]
        return Score(end.subs, end.dels, end.ins, n)
    }

    private data class Cell(val cost: Int, val subs: Int, val dels: Int, val ins: Int)

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /**
         * Punctuation the recogniser never emits. The Devanagari danda is included
         * because a reference transcript written by a person will contain it and a
         * decoder's output will not.
         */
        fun Char.isPunctuation(): Boolean = this in ",.;:!?\"'()[]{}-–—…«»‘’“”" || this == '।' || this == '॥'
    }
}

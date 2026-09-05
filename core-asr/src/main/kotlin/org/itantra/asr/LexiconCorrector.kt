package org.itantra.asr

/**
 * Repairs near-misses of critical vocabulary in a transcription. Task **W4.13**.
 *
 * ## Why this is not contextual biasing, and why it had to be built anyway
 *
 * `docs/ASR.md` section 3.4 specifies contextual biasing: hand the decoder a phrase list
 * and it scores those phrases more favourably. [BiasingLexicon] builds exactly that list,
 * and [SherpaRecogniser] accepts a `hotwordsFile`. It cannot be used.
 *
 * sherpa-onnx applies hotwords only under `modified_beam_search`, which is a beam search
 * over a **transducer**'s joiner. IndicConformer as published is a NeMo **CTC** graph. The
 * constraint is in the shipped library's own diagnostic:
 *
 * > `modified_beam_search if you provide --hotwords-file. Given --decoding-method`
 *
 * So the lexicon cannot reach the decoder. What it can do is repair the decoder's output
 * afterwards, which is a weaker mechanism honestly described: biasing changes what is heard,
 * this changes what was written down. It recovers the common case — a critical word decoded
 * one character off — and nothing else.
 *
 * ## Why negation is never a correction target
 *
 * This is the one place this class deliberately contradicts the weighting in
 * [BiasingLexicon], where negation carries 4.0 against the domain's 1.5. That weight is
 * right for *biasing*: the decoder still has the audio, and boosting a negation only makes
 * it likelier to be heard where it was actually spoken.
 *
 * Editing text has no audio to check against. Turning "अब निकलो" into "मत निकलो" because
 * they are one character apart would manufacture an instruction that means the opposite of
 * what was said, with no evidence whatsoever — which is risk **S-03** committed
 * deliberately rather than avoided. So a negation is never written *in*, and a recognised
 * negation is never edited *away*: it is the one class of word this leaves exactly as
 * heard, in both directions.
 *
 * ## Why an unambiguous match is required
 *
 * A word equidistant from two critical terms is a word this cannot repair. Picking either
 * is a coin toss on the most important vocabulary in the message, and a coin toss that
 * looks like a correction is worse than leaving a visibly wrong word an operator would
 * question.
 */
class LexiconCorrector(
    lexicon: BiasingLexicon,
    private val maxShortDistance: Int = 1,
    private val maxLongDistance: Int = 2,
) {
    /** Single words only. A multi-word phrase is not repairable one word at a time. */
    private val targets: List<String> =
        lexicon
            .phrases()
            .filterValues { it < BiasingLexicon.NEGATION_WEIGHT }
            .keys
            .filter { it.isNotBlank() && !it.contains(' ') }

    /** Everything known, including negation and phrases, so a real word is never "repaired". */
    private val known: Set<String> =
        lexicon.phrases().keys.flatMapTo(HashSet()) { it.split(' ') }.filterTo(HashSet()) { it.isNotBlank() }

    val targetCount: Int get() = targets.size

    /** What a pass changed, so the interface can say so rather than silently rewriting. */
    data class Correction(
        val text: String,
        /** Each repair as it was heard and as it was written. Empty when nothing changed. */
        val repairs: List<Pair<String, String>> = emptyList(),
    ) {
        val changed: Boolean get() = repairs.isNotEmpty()
    }

    fun correct(text: String): Correction {
        if (text.isBlank() || targets.isEmpty()) return Correction(text)

        val repairs = ArrayList<Pair<String, String>>()
        val out =
            text.split(' ').joinToString(" ") { word ->
                val bare = word.trim()
                // A word the lexicon already knows is right by definition, and a negation is
                // left exactly as heard.
                if (bare.isEmpty() || bare in known) {
                    word
                } else {
                    repairFor(bare)?.also { repairs += bare to it } ?: word
                }
            }
        return Correction(out, repairs)
    }

    private fun repairFor(word: String): String? {
        val limit = if (word.length <= SHORT_WORD) maxShortDistance else maxLongDistance
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var ties = 0

        for (target in targets) {
            // A length gap wider than the limit cannot be closed, and skipping it here keeps
            // the whole pass linear in the lexicon rather than quadratic in its longest word.
            if (kotlin.math.abs(target.length - word.length) > limit) continue
            val distance = distance(word, target, limit)
            if (distance > limit) continue
            when {
                distance < bestDistance -> {
                    bestDistance = distance
                    best = target
                    ties = 1
                }

                distance == bestDistance -> ties++
            }
        }
        // Ambiguous is not repairable. See the class comment.
        return if (ties == 1) best else null
    }

    private companion object {
        /** Below this a two-character edit is most of the word, not a near miss. */
        const val SHORT_WORD = 5

        /**
         * Levenshtein distance, abandoned once it cannot come in under [limit].
         *
         * Two rows rather than a full matrix: this runs once per word against every term in
         * the lexicon, on the utterance's critical path.
         */
        fun distance(
            a: String,
            b: String,
            limit: Int,
        ): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowBest = current[0]
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                    if (current[j] < rowBest) rowBest = current[j]
                }
                if (rowBest > limit) return limit + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}

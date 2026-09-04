package org.itantra.bench

import org.itantra.tts.HindiNumerals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scorer, task W4.8.
 *
 * Every accuracy figure in the project comes from this class, so its rules are tested
 * rather than assumed. A scorer that is wrong makes every other number wrong too, and
 * does it silently.
 */
class WerScorerTest {
    private val scorer = WerScorer()
    private val hindiScorer = WerScorer(spokenNumerals = HindiNumerals::digitwise)

    // ── the edit types ───────────────────────────────────────────────────────

    @Test
    fun `an exact match scores zero`() {
        val score = scorer.score("हमें तुरंत मदद चाहिए", "हमें तुरंत मदद चाहिए")
        assertEquals(0, score.errors)
        assertEquals(0.0, score.wer, 1e-9)
        assertTrue(score.isPerfect)
    }

    @Test
    fun `a substitution is counted once`() {
        val score = scorer.score("भेजो नाव जल्दी", "भेजो नाव धीरे")
        assertEquals(1, score.substitutions)
        assertEquals(0, score.deletions)
        assertEquals(0, score.insertions)
        assertEquals(1.0 / 3, score.wer, 1e-9)
    }

    @Test
    fun `a deletion is counted once`() {
        val score = scorer.score("भेजो नाव जल्दी", "भेजो जल्दी")
        assertEquals(1, score.deletions)
        assertEquals(0, score.substitutions)
        assertEquals(0, score.insertions)
    }

    @Test
    fun `an insertion is counted once`() {
        val score = scorer.score("भेजो नाव", "भेजो बड़ी नाव")
        assertEquals(1, score.insertions)
        assertEquals(0, score.substitutions)
        assertEquals(0, score.deletions)
    }

    @Test
    fun `the denominator is the reference length, not the hypothesis`() {
        val score = scorer.score("एक दो तीन चार", "एक")
        assertEquals(4, score.referenceWords)
        assertEquals(3, score.deletions)
        assertEquals(0.75, score.wer, 1e-9)
    }

    /**
     * A recogniser that hallucinates a long sentence from a short one genuinely does
     * score above 100 %. Clamping would hide the worst failure mode there is.
     */
    @Test
    fun `a hallucinated transcript may exceed one hundred percent`() {
        val score = scorer.score("आग", "आग लगी है तुरंत सब लोग बाहर निकलो")
        assertTrue("expected WER above 1.0, got ${score.wer}", score.wer > 1.0)
    }

    @Test
    fun `an empty hypothesis deletes everything`() {
        val score = scorer.score("एक दो", "")
        assertEquals(2, score.deletions)
        assertEquals(1.0, score.wer, 1e-9)
    }

    @Test
    fun `an empty reference with an empty hypothesis is not an error`() {
        assertEquals(0.0, scorer.score("", "").wer, 1e-9)
    }

    // ── the normalisation rules ──────────────────────────────────────────────

    /**
     * Devanagari and Malayalam have several byte sequences per visible glyph. Without
     * NFC, text that looks identical scores as an error — the single most misleading
     * failure a scorer can have, because the transcript looks right on screen.
     */
    @Test
    fun `text differing only in Unicode composition is identical`() {
        // U+0958 DEVANAGARI LETTER QA as one code point, against the two-code-point
        // sequence KA + NUKTA that renders identically. Written as escapes because a
        // literal in this file may already be stored decomposed, which would make the
        // fixture assert nothing.
        val single = "\u0958"
        val pair = "\u0915\u093C"
        assertNotEquals("the fixture must actually differ", single, pair)
        assertEquals(
            "text that renders identically must not score as an error",
            0,
            scorer.score(single, pair).errors,
        )
    }

    @Test
    fun `the same holds for a Latin accented character`() {
        val single = "\u00E9"
        val pair = "e\u0301"
        assertNotEquals(single, pair)
        assertEquals(0, scorer.score(single, pair).errors)
    }

    @Test
    fun `punctuation is not counted, including the danda`() {
        assertEquals(0, scorer.score("आग लगी है।", "आग लगी है").errors)
        assertEquals(0, scorer.score("मदद, जल्दी!", "मदद जल्दी").errors)
    }

    @Test
    fun `case is folded`() {
        assertEquals(0, scorer.score("Send The Boat", "send the boat").errors)
    }

    @Test
    fun `whitespace is collapsed`() {
        assertEquals(0, scorer.score("मदद    चाहिए", "मदद चाहिए").errors)
        assertEquals(0, scorer.score("  मदद चाहिए  ", "मदद चाहिए").errors)
    }

    /**
     * The rule most often skipped. The recogniser writes words where the reference
     * writes digits; these are the same answer and must not score as an error.
     */
    @Test
    fun `digits and their spoken form are the same answer`() {
        assertEquals(
            0,
            hindiScorer.score("112 पर कॉल करो", "एक एक दो पर कॉल करो").errors,
        )
    }

    /** Applied to both sides, so it cannot flatter the result in either direction. */
    @Test
    fun `the numeral rule applies whichever side holds the digits`() {
        assertEquals(0, hindiScorer.score("एक एक दो", "112").errors)
        assertEquals(0, hindiScorer.score("112", "एक एक दो").errors)
    }

    @Test
    fun `a genuinely wrong number is still an error`() {
        val score = hindiScorer.score("112", "113")
        assertTrue("a misheard digit must be counted", score.errors > 0)
    }

    // ── corpus scoring ───────────────────────────────────────────────────────

    /**
     * A mean of per-sentence rates over-weights short sentences. The corpus rate pools
     * the edits and the reference length, which is the convention every published WER
     * uses.
     */
    @Test
    fun `the corpus rate pools edits rather than averaging rates`() {
        // One error over one word, then no errors over ten.
        val pairs =
            listOf(
                "एक" to "दो",
                "एक दो तीन चार पाँच छह सात आठ नौ दस" to
                    "एक दो तीन चार पाँच छह सात आठ नौ दस",
            )
        val corpus = scorer.corpusWer(pairs)

        assertEquals(11, corpus.referenceWords)
        assertEquals(1, corpus.errors)
        assertEquals(1.0 / 11, corpus.wer, 1e-9)
        // A mean of the two rates would have been 0.5 — five times too high.
        assertTrue(corpus.wer < 0.1)
    }

    @Test
    fun `an empty corpus scores zero rather than dividing by zero`() {
        assertEquals(0.0, scorer.corpusWer(emptyList()).wer, 1e-9)
    }

    // ── character error rate ─────────────────────────────────────────────────

    @Test
    fun `character error rate counts characters`() {
        assertEquals(0.0, scorer.characterErrorRate("मदद", "मदद"), 1e-9)
        assertTrue(scorer.characterErrorRate("मदद", "मदत") > 0.0)
    }

    @Test
    fun `character error rate is lower than word error rate for a one-letter slip`() {
        val reference = "सेक्टर सत्रह भेजो"
        val hypothesis = "सेक्टर सत्रह भजो"
        val wer = scorer.score(reference, hypothesis).wer
        val cer = scorer.characterErrorRate(reference, hypothesis)
        assertTrue("one wrong letter should cost less per character ($cer) than per word ($wer)", cer < wer)
    }

    // ── the preprocessing is inspectable ─────────────────────────────────────

    @Test
    fun `tokenise exposes exactly what is compared`() {
        assertEquals(listOf("मदद", "चाहिए"), scorer.tokenise("मदद, चाहिए।"))
        assertEquals(listOf("एक", "एक", "दो"), hindiScorer.tokenise("112"))
    }
}

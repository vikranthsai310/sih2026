package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety properties matter more than the repairs.
 *
 * A corrector that fixes nine words and inverts the tenth is worse than one that fixes
 * nothing, because the tenth is the one that gets acted on.
 */
class LexiconCorrectorTest {
    private fun corrector(
        domain: String,
        negation: String = "",
    ) = LexiconCorrector(BiasingLexicon.of(domain, negation))

    // ── what it is for ───────────────────────────────────────────────────────

    /** The real miss observed on a handset: खाना (food) decoded as काना. */
    @Test
    fun `a critical word decoded one character off is repaired`() {
        val fixed = corrector("खाना\nमदद\nघायल").correct("काना दो")
        assertEquals("खाना दो", fixed.text)
        assertTrue(fixed.changed)
        assertEquals(listOf("काना" to "खाना"), fixed.repairs)
    }

    /**
     * The hazard, asserted rather than hoped away.
     *
     * खाता ("eats") is an ordinary word one edit from खाना ("food"), and this class has no
     * vocabulary of Hindi with which to tell them apart — so it rewrites it. Nothing here
     * fixes that, because nothing here can: a lexicon of eighty distress terms cannot say
     * what else is a word.
     *
     * It is the entire reason the engine uses a correction **only when it turns a sentence
     * that matched no template into one that does**. Free speech keeps the transcription
     * exactly as heard; a near-miss of a known sentence becomes that sentence, which is
     * bounded and checkable. See `MessageEngine.bestReading`.
     */
    @Test
    fun `an ordinary word near a lexicon term is damaged, which is why the engine gates this`() {
        val fixed = corrector("खाना").correct("मैं खाता हूँ")
        assertEquals("this is the failure the gate exists for", "मैं खाना हूँ", fixed.text)
    }

    @Test
    fun `a longer word tolerates two characters`() {
        val fixed = corrector("evacuate\nstretcher").correct("send a strecher now")
        assertEquals("send a stretcher now", fixed.text)
    }

    @Test
    fun `what was already right is left alone`() {
        val text = "मदद चाहिए घायल है"
        val fixed = corrector("मदद\nचाहिए\nघायल").correct(text)
        assertEquals(text, fixed.text)
        assertFalse(fixed.changed)
    }

    // ── the negation rules, which are the point ──────────────────────────────

    /**
     * Risk **S-03**. "अब निकलो" (now evacuate) and "मत निकलो" (do not evacuate) are one
     * character apart and mean opposite things. Editing one into the other has no audio to
     * justify it, so it must never happen however tempting the distance looks.
     */
    @Test
    fun `a negation is never written in`() {
        val fixed = corrector(domain = "निकलो", negation = "मत").correct("अब निकलो")
        assertEquals("अब निकलो", fixed.text)
        assertFalse("a negation manufactured from nothing", fixed.changed)
    }

    @Test
    fun `a recognised negation is never edited away`() {
        // "मत" is one edit from the domain word "मैं", and must survive anyway.
        val fixed = corrector(domain = "मैं\nनिकलो", negation = "मत").correct("मत निकलो")
        assertEquals("मत निकलो", fixed.text)
        assertFalse(fixed.changed)
    }

    @Test
    fun `English negation is protected the same way`() {
        val fixed = corrector(domain = "now\nevacuate", negation = "not").correct("do not evacuate")
        assertEquals("do not evacuate", fixed.text)
    }

    // ── when it must decline ─────────────────────────────────────────────────

    /** A coin toss that looks like a correction is worse than a visibly wrong word. */
    @Test
    fun `an ambiguous word is left as heard`() {
        // "bat" is exactly one edit from both "bag" and "bad".
        val fixed = corrector("bag\nbad").correct("bat here")
        assertEquals("bat here", fixed.text)
        assertFalse(fixed.changed)
    }

    @Test
    fun `an unrelated word is not dragged into the lexicon`() {
        val fixed = corrector("evacuate\nstretcher\ncasualty").correct("the weather is fine")
        assertEquals("the weather is fine", fixed.text)
        assertFalse(fixed.changed)
    }

    @Test
    fun `a short word is not rewritten on a two character edit`() {
        // "एक" to "आग" is two edits on a two-character word: most of it, not a near miss.
        val fixed = corrector("आग").correct("एक बजे")
        assertEquals("एक बजे", fixed.text)
    }

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `multi-word phrases are not correction targets`() {
        // "मदद चाहिए" cannot repair a single word, and must not be spliced in as one.
        val c = LexiconCorrector(BiasingLexicon.parse("मदद चाहिए\nमदद"))
        assertEquals(1, c.targetCount)
        assertTrue(c.correct("मदद चाहिए").text == "मदद चाहिए")
    }

    @Test
    fun `an empty lexicon changes nothing`() {
        val fixed = LexiconCorrector(BiasingLexicon.empty()).correct("मैं काना खाता हूँ")
        assertEquals("मैं काना खाता हूँ", fixed.text)
        assertFalse(fixed.changed)
    }

    @Test
    fun `blank input is returned unchanged`() {
        assertEquals("", LexiconCorrector(BiasingLexicon.parse("मदद")).correct("").text)
        assertEquals("   ", LexiconCorrector(BiasingLexicon.parse("मदद")).correct("   ").text)
    }

    @Test
    fun `spacing survives a repair`() {
        val fixed = corrector("stretcher").correct("send strecher")
        assertEquals("send stretcher", fixed.text)
    }

    /** Every real lexicon in the repository must load and correct without throwing. */
    @Test
    fun `the shipped Hindi lexicon repairs the observed miss`() {
        val domain =
            """
            # comment
            खाना
            मदद	1.5
            घायल
            """.trimIndent()
        val fixed = LexiconCorrector(BiasingLexicon.of(domain, "मत\nनहीं")).correct("काना दो")
        assertEquals("खाना दो", fixed.text)
    }
}

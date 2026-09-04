package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Critical-term error rate, the figure `docs/EVALUATION.md` calls the one that actually
 * matters operationally.
 */
class CriticalTermScorerTest {
    private val scorer =
        CriticalTermScorer(listOf("मदद", "आग", "घायल", "सेक्टर", "मदद चाहिए", "नाव"))

    @Test
    fun `a hypothesis carrying every critical term scores zero`() {
        val score = scorer.score("सेक्टर सत्रह में मदद चाहिए", "सेक्टर सत्रह में मदद चाहिए")
        assertEquals(0.0, score.cter, 1e-9)
        assertTrue("the terms must actually have been counted", score.occurrences > 0)
    }

    @Test
    fun `a dropped critical term is counted as missed`() {
        val score = scorer.score("यहाँ आग लगी है", "यहाँ आठ लगी है")
        assertEquals(1, score.occurrences)
        assertEquals(1, score.missed)
        assertEquals(1.0, score.cter, 1e-9)
    }

    /**
     * आग and आठ differ by one character and mean *fire* and *eight*. A metric that scored
     * that as nearly right would be measuring the wrong thing.
     */
    @Test
    fun `a near miss earns no credit`() {
        assertEquals(1.0, scorer.score("आग", "आठ").cter, 1e-9)
    }

    /** A sentence naming two casualties and losing one is half wrong, not all or nothing. */
    @Test
    fun `terms are counted per occurrence rather than per sentence`() {
        val score = scorer.score("घायल घायल", "घायल")
        assertEquals(2, score.occurrences)
        assertEquals(1, score.missed)
        assertEquals(0.5, score.cter, 1e-9)
    }

    @Test
    fun `word order is not this metric's job`() {
        val score = scorer.score("मदद आग", "आग मदद")
        assertEquals("both terms are present, however arranged", 0, score.missed)
    }

    @Test
    fun `a multi-word term is matched as a phrase`() {
        // "मदद" alone is present; "मदद चाहिए" is not, so one of the two occurrences is missed.
        val score = scorer.score("मदद चाहिए", "मदद")
        assertEquals(2, score.occurrences)
        assertEquals(1, score.missed)
    }

    /**
     * A reference with no critical vocabulary scores zero, which is correct — there was
     * nothing critical to lose. It must not be confused with perfect recognition, and the
     * occurrence count beside it is what keeps the two apart.
     */
    @Test
    fun `a reference with no critical terms reports zero occurrences`() {
        val score = scorer.score("आगे बढ़ रहे हैं", "कुछ और")
        assertEquals(0, score.occurrences)
        assertEquals(0.0, score.cter, 1e-9)
    }

    @Test
    fun `a corpus score sums occurrences rather than averaging rates`() {
        val corpus =
            scorer.corpus(
                listOf(
                    // Three occurrences, one missed.
                    "मदद आग घायल" to "मदद आग",
                    // One occurrence, none missed.
                    "नाव" to "नाव",
                ),
            )
        assertEquals(4, corpus.occurrences)
        assertEquals(1, corpus.missed)
        // Averaging the two sentence rates would give (0.33 + 0.0) / 2 = 0.17, which
        // weights a one-word sentence as heavily as a three-word one.
        assertEquals(0.25, corpus.cter, 1e-9)
    }

    @Test
    fun `punctuation and spacing do not create misses`() {
        assertEquals(0, scorer.score("मदद, आग!", "मदद   आग").missed)
    }

    @Test
    fun `an empty term list scores nothing rather than everything`() {
        val empty = CriticalTermScorer(emptyList())
        assertEquals(0, empty.termCount)
        assertEquals(0, empty.score("मदद", "कुछ").occurrences)
    }
}

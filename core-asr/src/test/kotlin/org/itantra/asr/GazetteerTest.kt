package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deployment gazetteer, task W7.5. */
class GazetteerTest {
    private val sample =
        """
        # Cuttack district, 4 September
        deployment: cuttack-2026-09
        sectors: 1-40
        place: Jagatpur
        place: Nayabazar
        callsign: Alpha
        callsign: Bravo
        """.trimIndent()

    @Test
    fun `a gazetteer parses places, call signs and sectors`() {
        val g = Gazetteer.parse(sample)
        assertEquals("cuttack-2026-09", g.deployment)
        assertEquals(listOf("Jagatpur", "Nayabazar"), g.places)
        assertEquals(listOf("Alpha", "Bravo"), g.callSigns)
        assertEquals(1..40, g.sectorRange)
    }

    /** Forty sectors should not need forty lines a coordinator has to type. */
    @Test
    fun `sectors are generated rather than listed`() {
        val terms = Gazetteer.parse(sample).terms()

        for (sector in 1..40) {
            assertTrue("sector $sector must be generated", "sector $sector" in terms)
        }
        assertTrue("and not one beyond the range", "sector 41" !in terms)

        // 2 places + 2 call signs + 40 sectors in two forms each.
        assertEquals(2 + 2 + 40 * 2, terms.size)
    }

    /** The recogniser may produce either form, so both are biased. */
    @Test
    fun `both the spoken and bare forms of a sector are biased`() {
        val terms =
            Gazetteer(
                deployment = "d",
                places = emptyList(),
                callSigns = emptyList(),
                sectorRange = 17..17,
            ).terms { HindiSpoken[it] ?: it.toString() }

        assertTrue("sector सत्रह" in terms)
        assertTrue("सत्रह" in terms)
    }

    /**
     * A place name is the one word in the sentence that says where to go, and it is a
     * proper noun no acoustic model has seen.
     */
    @Test
    fun `place names outrank the domain lexicon`() {
        val terms = Gazetteer.parse(sample).terms()
        assertTrue(terms["Jagatpur"]!! > BiasingLexicon.DOMAIN_WEIGHT)
    }

    @Test
    fun `a gazetteer folds into an existing lexicon keeping the higher weight`() {
        val base = BiasingLexicon.of(domain = "मदद\nJagatpur", negation = "मत")
        val merged = Gazetteer.parse(sample).applyTo(base)

        assertEquals(
            "the gazetteer must raise a place already in the lexicon",
            Gazetteer.PLACE_WEIGHT,
            merged.weightOf("Jagatpur")!!,
            1e-9,
        )
        assertEquals(
            "but negation still outranks everything",
            BiasingLexicon.NEGATION_WEIGHT,
            merged.weightOf("मत")!!,
            1e-9,
        )
        assertTrue("the domain lexicon survives", "मदद" in merged)
    }

    // ── a file written under time pressure ───────────────────────────────────

    /** A typo should cost one line, not the whole gazetteer. */
    @Test
    fun `an unknown key is ignored rather than fatal`() {
        val g = Gazetteer.parse("deployment: d\nplcae: typo\nplace: Real")
        assertEquals(listOf("Real"), g.places)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val g = Gazetteer.parse("# heading\n\nplace: A   # trailing\n\n")
        assertEquals(listOf("A"), g.places)
    }

    @Test
    fun `an empty gazetteer is valid`() {
        val g = Gazetteer.parse("")
        assertEquals(0, g.size)
        assertTrue(g.terms().isEmpty())
    }

    /** Biasing on ten thousand phrases would slow every decode. */
    @Test
    fun `an implausible sector range is refused`() {
        assertNull(Gazetteer.parse("sectors: 1-99999").sectorRange)
        assertNull(Gazetteer.parse("sectors: nonsense").sectorRange)
        assertNull(Gazetteer.parse("sectors: 40-1").sectorRange)
    }

    @Test
    fun `a single sector number is accepted`() {
        assertEquals(7..7, Gazetteer.parse("sectors: 7").sectorRange)
    }

    private companion object {
        val HindiSpoken = mapOf(17 to "सत्रह")
    }
}

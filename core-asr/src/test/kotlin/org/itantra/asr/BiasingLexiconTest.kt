package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contextual biasing, tasks W4.13–W4.15.
 *
 * The weight ordering is the part worth testing. If negation ever stops outranking the
 * domain lexicon, W4.14 is silently undone and nothing else in the system notices.
 */
class BiasingLexiconTest {
    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `one phrase per line at the default weight`() {
        val lexicon = BiasingLexicon.parse("मदद\nआग\nबाढ़")
        assertEquals(3, lexicon.size)
        assertEquals(BiasingLexicon.DOMAIN_WEIGHT, lexicon.weightOf("मदद")!!, 1e-9)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val lexicon =
            BiasingLexicon.parse(
                """
                # a heading
                मदद

                आग   # trailing comment

                """.trimIndent(),
            )
        assertEquals(2, lexicon.size)
        assertTrue("मदद" in lexicon)
        assertTrue("आग" in lexicon)
    }

    @Test
    fun `an explicit weight overrides the default`() {
        val lexicon = BiasingLexicon.parse("मदद\t9.0\nआग")
        assertEquals(9.0, lexicon.weightOf("मदद")!!, 1e-9)
        assertEquals(BiasingLexicon.DOMAIN_WEIGHT, lexicon.weightOf("आग")!!, 1e-9)
    }

    /** A lexicon is edited by hand under time pressure; a duplicate must not break it. */
    @Test
    fun `a duplicate keeps the higher weight rather than failing`() {
        val lexicon = BiasingLexicon.parse("मदद\t2.0\nमदद\t5.0")
        assertEquals(1, lexicon.size)
        assertEquals(5.0, lexicon.weightOf("मदद")!!, 1e-9)
    }

    @Test
    fun `an unknown phrase has no weight`() {
        assertNull(BiasingLexicon.parse("मदद").weightOf("हेलीकॉप्टर"))
    }

    @Test
    fun `an empty lexicon is usable`() {
        assertEquals(0, BiasingLexicon.empty().size)
        assertFalse("मदद" in BiasingLexicon.empty())
    }

    // ── W4.14: negation outranks everything ──────────────────────────────────

    /**
     * Risk S-03. "मत निकलो" heard as "अब निकलो" is a fluent instruction meaning the
     * opposite of what was said, and it will be acted on.
     */
    @Test
    fun `negation is weighted far above the domain lexicon`() {
        val lexicon = BiasingLexicon.of(domain = "निकलो\nआग", negation = "मत\nनहीं")

        assertEquals(BiasingLexicon.NEGATION_WEIGHT, lexicon.weightOf("मत")!!, 1e-9)
        assertEquals(BiasingLexicon.DOMAIN_WEIGHT, lexicon.weightOf("निकलो")!!, 1e-9)
        assertTrue(
            "negation must outrank the domain lexicon",
            lexicon.weightOf("मत")!! > lexicon.weightOf("निकलो")!!,
        )
    }

    /** Precedence the other way would silently undo W4.14. */
    @Test
    fun `a term in both lists keeps the negation weight`() {
        val lexicon = BiasingLexicon.of(domain = "रुको", negation = "रुको")
        assertEquals(BiasingLexicon.NEGATION_WEIGHT, lexicon.weightOf("रुको")!!, 1e-9)
    }

    // ── W4.15: roster names injected at runtime ──────────────────────────────

    @Test
    fun `roster names are added at the roster weight`() {
        val lexicon = BiasingLexicon.parse("मदद").withRoster(listOf("अल्फ़ा", "ब्रावो"))
        assertEquals(3, lexicon.size)
        assertEquals(BiasingLexicon.ROSTER_WEIGHT, lexicon.weightOf("अल्फ़ा")!!, 1e-9)
    }

    @Test
    fun `a roster name never lowers a weight already assigned`() {
        val lexicon =
            BiasingLexicon.of(domain = "", negation = "मत").withRoster(listOf("मत"))
        assertEquals(
            "negation must survive a colliding roster name",
            BiasingLexicon.NEGATION_WEIGHT,
            lexicon.weightOf("मत")!!,
            1e-9,
        )
    }

    @Test
    fun `an empty roster changes nothing`() {
        val base = BiasingLexicon.parse("मदद")
        assertEquals(base.size, base.withRoster(emptyList()).size)
        assertEquals(base.size, base.withRoster(listOf("", "  ")).size)
    }

    @Test
    fun `the roster does not mutate the lexicon it came from`() {
        val base = BiasingLexicon.parse("मदद")
        base.withRoster(listOf("अल्फ़ा"))
        assertEquals("the original must be unchanged", 1, base.size)
    }

    @Test
    fun `the weight ordering is domain below roster below negation`() {
        assertTrue(BiasingLexicon.DOMAIN_WEIGHT < BiasingLexicon.ROSTER_WEIGHT)
        assertTrue(BiasingLexicon.ROSTER_WEIGHT < BiasingLexicon.NEGATION_WEIGHT)
    }
}

/** The shipped Hindi lists are held to the same rules as any other input. */
class ShippedLexiconTest {
    private fun read(name: String): String {
        val candidates = listOf(File("../models/lexicon/$name"), File("models/lexicon/$name"))
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("models/lexicon/$name not found", file != null)
        return file!!.readText()
    }

    private fun hindi() = BiasingLexicon.of(read("alert-lexicon.hi.txt"), read("negation.hi.txt"))

    @Test
    fun `the Hindi lexicon loads and is substantial`() {
        val lexicon = hindi()
        assertTrue("expected a real lexicon, got ${lexicon.size} terms", lexicon.size >= 100)
    }

    @Test
    fun `the vocabulary a distress message actually uses is present`() {
        val lexicon = hindi()
        for (term in listOf("मदद", "घायल", "आग", "निकासी", "सेक्टर", "नाव", "बाढ़")) {
            assertTrue("'$term' must be biased", term in lexicon)
        }
    }

    @Test
    fun `every negation term outranks every domain term`() {
        val domain = BiasingLexicon.parse(read("alert-lexicon.hi.txt"))
        val merged = hindi()
        val negations = BiasingLexicon.parse(read("negation.hi.txt"), BiasingLexicon.NEGATION_WEIGHT)

        val worstNegation = negations.phrases().keys.minOf { merged.weightOf(it)!! }
        val bestDomain = domain.phrases().keys.maxOf { merged.weightOf(it)!! }
        assertTrue(
            "negation floor $worstNegation must exceed domain ceiling $bestDomain",
            worstNegation > bestDomain,
        )
    }

    @Test
    fun `the negation list covers the forms an operator actually says`() {
        val negations = BiasingLexicon.parse(read("negation.hi.txt"))
        for (term in listOf("नहीं", "मत", "ना", "रुको")) {
            assertTrue("'$term' must be in the negation list", term in negations)
        }
    }
}

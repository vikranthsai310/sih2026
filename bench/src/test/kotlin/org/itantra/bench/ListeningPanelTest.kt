package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel arithmetic, tasks **W7.10** and **W7.11**.
 *
 * The panels themselves have not been run — they need fifteen native speakers per language
 * and synthesised audio, and neither exists yet. What is asserted here is that when the
 * ratings do arrive, the number that comes out of them is computed correctly and cannot be
 * quoted without its panel size.
 */
class ListeningPanelTest {
    // ── mean opinion score ───────────────────────────────────────────────────

    @Test
    fun `a MOS below the minimum panel size is not reportable`() {
        val panel = MosPanel("hi", ratings(listeners = 4, samples = 5) { _, _ -> 5 })
        assertFalse("four listeners is an anecdote with a decimal point", panel.isReportable)
        assertFalse("and it cannot meet the target however high it is", panel.meetsTarget)
        assertTrue(panel.report().contains("BELOW"))
    }

    @Test
    fun `a full panel is reportable and carries its size`() {
        val panel = MosPanel("hi", ratings(listeners = 15, samples = 8) { _, _ -> 4 })
        assertTrue(panel.isReportable)
        assertEquals(15, panel.panelSize)
        assertEquals(8, panel.sampleCount)
        assertTrue("the panel size travels with the figure", panel.report().contains("n=15"))
    }

    /**
     * The rule that matters most in the arithmetic. Ten ratings from one listener are not
     * ten independent observations, so a panel of two enthusiasts and one sceptic must not
     * be diluted into looking like thirty balanced opinions.
     */
    @Test
    fun `the listener is the unit of analysis, not the rating`() {
        // One listener rates twenty samples 5; one listener rates one sample 1.
        val ratings =
            (1..20).map { Rating("generous", "s$it", 5) } + Rating("sceptic", "s1", 1)
        val panel = MosPanel("hi", ratings)

        // Pooling every rating would give (20*5 + 1)/21 = 4.81. Averaging per listener
        // first gives 3.0, which is the honest summary of two opinions.
        assertEquals(3.0, panel.mos!!, 1e-9)
    }

    @Test
    fun `a flat rater is named rather than quietly dropped`() {
        val ratings =
            (1..5).map { Rating("flat", "s$it", 3) } +
                (1..5).map { Rating("varied", "s$it", it) }
        val panel = MosPanel("hi", ratings)

        assertEquals(listOf("flat"), panel.flatRaters)
        assertEquals("and still counted in the panel", 2, panel.panelSize)
    }

    @Test
    fun `the interval widens as listeners disagree`() {
        val agreeing = MosPanel("hi", ratings(listeners = 15, samples = 4) { _, _ -> 4 })
        val disagreeing =
            MosPanel("hi", ratings(listeners = 15, samples = 4) { listener, _ -> 1 + listener % 5 })

        val narrow = agreeing.confidence95!!
        val wide = disagreeing.confidence95!!
        assertTrue(
            "unanimous panel interval ${width(narrow)} should be narrower than ${width(wide)}",
            width(narrow) < width(wide),
        )
    }

    @Test
    fun `a single listener has a mean but no interval`() {
        val panel = MosPanel("hi", listOf(Rating("only", "s1", 4)))
        assertEquals(4.0, panel.mos!!, 1e-9)
        assertNull("a standard deviation over one listener is undefined", panel.standardDeviation)
        assertNull(panel.confidence95)
    }

    @Test
    fun `a score outside the five-point scale is refused`() {
        assertNotNull(runCatching { Rating("l", "s", 0) }.exceptionOrNull())
        assertNotNull(runCatching { Rating("l", "s", 6) }.exceptionOrNull())
    }

    @Test
    fun `an empty panel reports nothing rather than zero`() {
        val panel = MosPanel("or", emptyList())
        assertNull("a MOS of zero and no panel at all are different states", panel.mos)
        assertTrue(panel.report().contains("no ratings"))
    }

    // ── intelligibility ──────────────────────────────────────────────────────

    private val sources =
        mapOf(
            "s1" to "सेक्टर सत्रह में तीन घायल",
            "s2" to "दोपहर दो बजकर तीस मिनट",
            "s3" to "पाँच किलोमीटर उत्तर",
        )

    @Test
    fun `perfect transcription scores full accuracy`() {
        val heard = sources.keys.flatMap { id -> (1..15).map { Transcription("l$it", id, sources.getValue(id)) } }
        val result = IntelligibilityPanel("hi").score(sources, heard)

        assertEquals(1.0, result.accuracy!!, 1e-9)
        assertTrue(result.isReportable)
        assertTrue(result.meetsTarget)
    }

    /**
     * The failure this test exists to catch is a numeral table nobody checked: one sample
     * that every listener writes down differently. It must surface as a named sample, not
     * be averaged into an acceptable-looking total.
     */
    @Test
    fun `a sample everybody mishears is named`() {
        val heard =
            sources.keys.flatMap { id ->
                (1..15).map { listener ->
                    val text =
                        if (id == "s2") "दोपहर दो बजकर तीन मिनट" else sources.getValue(id)
                    Transcription("l$listener", id, text)
                }
            }
        val result = IntelligibilityPanel("hi").score(sources, heard)

        assertEquals("s2", result.worstSamples.first().first)
        assertTrue("and it scores below the others", result.worstSamples.first().second < 1.0)
    }

    @Test
    fun `intelligibility below the minimum panel is not reportable`() {
        val heard = sources.keys.map { Transcription("only", it, sources.getValue(it)) }
        val result = IntelligibilityPanel("hi").score(sources, heard)

        assertEquals(1.0, result.accuracy!!, 1e-9)
        assertFalse("one listener is not a panel", result.isReportable)
        assertFalse(result.meetsTarget)
        assertTrue(result.report().contains("BELOW"))
    }

    /**
     * A transcription of a sample nobody played means the randomisation and the answer
     * sheet came apart, which invalidates the session rather than one row of it.
     */
    @Test
    fun `a transcription of an unplayed sample invalidates the run`() {
        val thrown =
            runCatching {
                IntelligibilityPanel("hi").score(sources, listOf(Transcription("l1", "s9", "कुछ")))
            }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("never played"))
    }

    @Test
    fun `accuracy is averaged across listeners, not across transcriptions`() {
        val heard =
            listOf(
                // One listener transcribes three samples perfectly.
                Transcription("careful", "s1", sources.getValue("s1")),
                Transcription("careful", "s2", sources.getValue("s2")),
                Transcription("careful", "s3", sources.getValue("s3")),
                // One listener transcribes one sample and gets nothing right.
                Transcription("distracted", "s1", "कुछ और शब्द बिलकुल अलग"),
            )
        val result = IntelligibilityPanel("hi").score(sources, heard)

        assertEquals(2, result.panelSize)
        assertTrue(
            "the second listener must count as half the panel, not a quarter of the rows",
            result.accuracy!! < 0.75,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ratings(
        listeners: Int,
        samples: Int,
        score: (listener: Int, sample: Int) -> Int,
    ): List<Rating> =
        (1..listeners).flatMap { listener ->
            (1..samples).map { sample -> Rating("l$listener", "s$sample", score(listener, sample)) }
        }

    private fun width(range: ClosedFloatingPointRange<Double>) = range.endInclusive - range.start
}

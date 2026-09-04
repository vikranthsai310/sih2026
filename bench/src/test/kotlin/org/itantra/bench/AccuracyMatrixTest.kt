package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy matrix and the suppression decision. Tasks **W7.7**, **W7.8**, **W7.9**.
 *
 * The recogniser here is a stub whose error is a **function of the condition**, so the
 * matrix can be checked for the thing that actually goes wrong in a measurement harness:
 * a cell reading the wrong row. A stub that returned the reference verbatim would give a
 * table of zeroes that passes every assertion and proves nothing.
 */
class AccuracyMatrixTest {
    private val references =
        listOf(
            "सेक्टर सत्रह में तीन घायल",
            "मदद चाहिए नाव भेजें",
            "आग लगी है तुरंत खाली करें",
            "पुल टूट गया है रास्ता बंद",
        )

    private val corpus =
        mapOf(
            "hi" to references.map { AccuracyMatrix.Utterance(tone(1_600), it) },
        )

    private val critical = mapOf("hi" to listOf("मदद", "आग", "घायल", "नाव", "सेक्टर", "सत्रह"))

    // ── the matrix ───────────────────────────────────────────────────────────

    @Test
    fun `a full language sweep produces every cell exactly once`() {
        val conditions = AccuracyMatrix.conditionsFor("hi")
        assertEquals("four conditions, two switches", 16, conditions.size)
        assertEquals("no cell is repeated", conditions.size, conditions.distinct().size)

        val report = AccuracyMatrix().run(corpus, critical, conditions, perfect())
        assertEquals(16, report.cells.size)
        assertTrue(report.isMeasured)
    }

    @Test
    fun `an empty report is not a report of perfect accuracy`() {
        val empty = AccuracyMatrix.Report(emptyList(), noiseSeed = 1)
        assertFalse(empty.isMeasured)
    }

    /** An empty corpus scored as perfect is the classic silent measurement failure. */
    @Test
    fun `a language with no corpus is refused rather than scored`() {
        val thrown =
            runCatching {
                AccuracyMatrix().run(
                    corpus = mapOf("hi" to emptyList()),
                    criticalTerms = critical,
                    conditions = AccuracyMatrix.conditionsFor("hi"),
                    recogniser = perfect(),
                )
            }.exceptionOrNull()
        assertNotNull("an empty cell must not be scored", thrown)
    }

    /**
     * Every cell at a given SNR must see byte-identical audio whatever the switches say,
     * or the suppression and biasing deltas are attributable to the mixer rather than to
     * the switches.
     */
    @Test
    fun `the audio at one SNR does not depend on the switch settings`() {
        val seen = HashMap<String, List<Int>>()
        val recorder =
            AccuracyMatrix.Recogniser { audio, condition ->
                val digest = audio.take(64).map { it.toInt() }
                val previous = seen[condition.conditionName]
                if (previous != null) {
                    assertEquals(
                        "${condition.label()} saw different audio from another cell at the same SNR",
                        previous,
                        digest,
                    )
                } else {
                    seen[condition.conditionName] = digest
                }
                references.first()
            }
        AccuracyMatrix().run(corpus, critical, AccuracyMatrix.conditionsFor("hi"), recorder)
        assertEquals("clean plus three SNRs", 4, seen.size)
    }

    @Test
    fun `the CSV carries one row per cell and names its seed`() {
        val report = AccuracyMatrix().run(corpus, critical, AccuracyMatrix.conditionsFor("hi"), perfect())
        val lines = report.toCsv().trim().lines()

        assertEquals(AccuracyMatrix.CSV_COLUMNS.joinToString(","), lines.first())
        assertEquals(16 + 1, lines.size)
        assertTrue("every row records the noise seed", lines.drop(1).all { it.endsWith(",${report.noiseSeed}") })
    }

    // ── W7.9, the biasing delta ──────────────────────────────────────────────

    @Test
    fun `the biasing delta is the unbiased error minus the biased error`() {
        val report =
            AccuracyMatrix().run(
                corpus,
                critical,
                AccuracyMatrix.conditionsFor("hi"),
                // Unbiased drops the critical word; biased keeps it. That is the whole
                // claim W7.9 makes, reduced to a stub.
                AccuracyMatrix.Recogniser { _, condition ->
                    val reference = references.first()
                    if (condition.biasing) reference else reference.replace("घायल", "गया")
                },
            )

        val delta = report.biasingDelta("hi", snrDb = null, suppression = false)
        assertNotNull(delta)
        assertTrue("biasing must reduce critical-term error, delta was $delta", delta!! > 0.0)
    }

    // ── W7.7, the decision ───────────────────────────────────────────────────

    @Test
    fun `suppression is enabled when it helps in noise at no clean-speech cost`() {
        val decision = SuppressionPolicy.decide(reportWith(adverseGain = 0.05, cleanLoss = 0.0), "hi")
        assertTrue(decision.reason, decision.enabled)
    }

    /** A gain below the margin is measurement noise, not a reason to add a stage. */
    @Test
    fun `a gain smaller than the margin does not enable suppression`() {
        val decision =
            SuppressionPolicy.decide(
                reportWith(adverseGain = SuppressionPolicy.REQUIRED_MARGIN / 2, cleanLoss = 0.0),
                "hi",
            )
        assertFalse(decision.reason, decision.enabled)
    }

    /**
     * The mistake this rule exists to catch: a suppressor that pays for itself at +5 dB
     * and quietly costs accuracy in the quiet room where most messages are spoken.
     */
    @Test
    fun `a noisy-case gain does not buy a clean-speech regression`() {
        val decision = SuppressionPolicy.decide(reportWith(adverseGain = 0.05, cleanLoss = 0.02), "hi")
        assertFalse(decision.reason, decision.enabled)
        assertTrue(decision.reason.contains("clean speech"))
    }

    /** A stage that helps at one adverse SNR and hurts at the other has not earned it. */
    @Test
    fun `the worse of the two adverse conditions decides, not the average`() {
        val report = reportWith(adverseGain = 0.10, cleanLoss = 0.0, gainAtTenDb = -0.06)
        val decision = SuppressionPolicy.decide(report, "hi")
        assertFalse(decision.reason, decision.enabled)
    }

    /**
     * Not measured and measured-unhelpful are different states. Reporting the first as the
     * second is a decision dressed up as a result.
     */
    @Test
    fun `an unmeasured language is undecided rather than disabled`() {
        val decision = SuppressionPolicy.decide(AccuracyMatrix.Report(emptyList(), 1), "or")
        assertTrue(decision.isUndecided)
        assertTrue(decision.reason, decision.reason.contains("not measured"))
        assertTrue(SuppressionPolicy.toCsv(listOf(decision)).contains("undecided"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun perfect() = AccuracyMatrix.Recogniser { _, _ -> references.first() }

    /** A report whose CTER cells are set directly, so the policy is tested and not the mixer. */
    private fun reportWith(
        adverseGain: Double,
        cleanLoss: Double,
        gainAtTenDb: Double = adverseGain,
    ): AccuracyMatrix.Report {
        val base = 0.20

        fun cell(
            snr: Double?,
            suppression: Boolean,
            cter: Double,
        ) = AccuracyMatrix.Cell(
            AccuracyMatrix.Condition("hi", snr, NoiseMixer.Noise.CROWD, suppression, biasing = true),
            wer = cter,
            cter = cter,
            criticalOccurrences = 100,
            utterances = 10,
        )

        return AccuracyMatrix.Report(
            listOf(
                cell(null, false, base),
                cell(null, true, base + cleanLoss),
                cell(10.0, false, base),
                cell(10.0, true, base - gainAtTenDb),
                cell(5.0, false, base),
                cell(5.0, true, base - adverseGain),
            ),
            noiseSeed = NoiseMixer.DEFAULT_SEED,
        )
    }

    /** A short tone: real enough to have non-zero energy, which is all the mixer needs. */
    private fun tone(samples: Int) =
        ShortArray(samples) { i -> (4_000 * Math.sin(2 * Math.PI * 220 * i / 16_000.0)).toInt().toShort() }
}

package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage decomposition behind the metrics screen. Tasks **W8.1**, **W8.3**.
 */
class LatencyStagesTest {
    /** A trace whose stages are round numbers, so an arithmetic slip is visible. */
    private fun trace(
        id: String = "u1",
        capture: Long = 30,
        endpoint: Long = 200,
        decode: Long = 330,
        transmit: Long = 40,
        normalise: Long = 5,
        synthesis: Long = 195,
        output: Long = 50,
        offsetMillis: Long = 0,
    ): UtteranceTrace {
        val tVad = capture
        val tEndpoint = tVad + endpoint
        val tFinal = tEndpoint + decode
        val tRxSender = tFinal + transmit
        val tNorm = tRxSender + normalise
        val tChunk1 = tNorm + synthesis
        val tAudio = tChunk1 + output

        // Receiver stages are recorded in the receiver's clock, which is offset.
        fun receiver(millis: Long) = (millis + offsetMillis) * 1_000_000
        return UtteranceTrace(
            utteranceId = id,
            language = "hi",
            mode = "ptt",
            transport = "ble",
            tMic = 0,
            tVad = tVad * 1_000_000,
            tEndpoint = tEndpoint * 1_000_000,
            tFinal = tFinal * 1_000_000,
            tTx = tFinal * 1_000_000,
            tRx = receiver(tRxSender),
            tNorm = receiver(tNorm),
            tChunk1 = receiver(tChunk1),
            tAudio = receiver(tAudio),
            clockOffsetNanos = offsetMillis * 1_000_000,
        )
    }

    // ── the decomposition reconciles ─────────────────────────────────────────

    /**
     * The property that makes a stage table worth showing. A decomposition that loses time
     * somewhere unnamed is worse than no decomposition, because it invites a reader to
     * trust it.
     */
    @Test
    fun `the seven stages sum to the end-to-end figure`() {
        val t = trace()
        assertEquals(850L, t.endToEndMillis)
        assertEquals(0L, StageSummary.unaccountedMillis(t))
    }

    /** And they still reconcile when the two handsets' clocks are far apart. */
    @Test
    fun `the decomposition survives a large clock offset`() {
        val t = trace(offsetMillis = 45_000)
        assertEquals("the offset must not leak into the total", 850L, t.endToEndMillis)
        assertEquals(0L, StageSummary.unaccountedMillis(t))
    }

    /**
     * The offset is applied exactly once, at the one stage that spans two handsets.
     * Applying it twice would move time out of TRANSMIT and into NORMALISE, where nobody
     * would think to look for it.
     */
    @Test
    fun `only the transmit stage crosses the clock boundary`() {
        val synced = trace()
        val skewed = trace(offsetMillis = 45_000)
        for (stage in Stage.entries) {
            assertEquals(
                "$stage changed when the clocks did",
                stage.millisOf(synced),
                stage.millisOf(skewed),
            )
        }
    }

    @Test
    fun `each stage measures the gap it is named for`() {
        val t = trace()
        assertEquals(30L, Stage.CAPTURE.millisOf(t))
        assertEquals(200L, Stage.ENDPOINT.millisOf(t))
        assertEquals(330L, Stage.DECODE.millisOf(t))
        assertEquals(40L, Stage.TRANSMIT.millisOf(t))
        assertEquals(5L, Stage.NORMALISE.millisOf(t))
        assertEquals(195L, Stage.SYNTHESIS.millisOf(t))
        assertEquals(50L, Stage.OUTPUT.millisOf(t))
    }

    /**
     * A stage that never happened and a stage that took no time are different facts.
     * Averaging the first as the second flatters every figure it touches.
     */
    @Test
    fun `a stage that did not happen is null rather than zero`() {
        val incomplete = trace().copy(tChunk1 = null, tAudio = null)
        assertNull(Stage.SYNTHESIS.millisOf(incomplete))
        assertNull(Stage.OUTPUT.millisOf(incomplete))
        assertNull("and the whole trace cannot be reconciled", StageSummary.unaccountedMillis(incomplete))
        assertEquals("but the stages that did happen still report", 30L, Stage.CAPTURE.millisOf(incomplete))
    }

    // ── the summary ──────────────────────────────────────────────────────────

    @Test
    fun `every stage reached by an utterance appears in the summary`() {
        val stats = StageSummary.byStage(List(10) { trace("u$it") })
        assertEquals(Stage.entries.size, stats.size)
        assertEquals("in pipeline order", Stage.entries.toList(), stats.map { it.stage })
        assertTrue(stats.all { it.n == 10 })
    }

    @Test
    fun `a stage nothing reached is absent rather than reported as zero`() {
        val stats = StageSummary.byStage(List(5) { trace("u$it").copy(tAudio = null) })
        assertTrue("OUTPUT must not appear", stats.none { it.stage == Stage.OUTPUT })
        assertTrue(stats.any { it.stage == Stage.SYNTHESIS })
    }

    @Test
    fun `a stage over its budget is flagged with the amount`() {
        val slow = StageSummary.byStage(List(10) { trace("u$it", decode = 700) })
        val decode = slow.first { it.stage == Stage.DECODE }
        assertTrue("450 ms budget, 700 ms median", !decode.withinBudget)
        assertEquals(250L, decode.overBudgetMillis)

        val capture = slow.first { it.stage == Stage.CAPTURE }
        assertTrue(capture.withinBudget)
        assertEquals(0L, capture.overBudgetMillis)
    }

    // ── the histogram ────────────────────────────────────────────────────────

    /**
     * The shape is the finding. Two runs with the same median look identical in a table
     * and completely different here.
     */
    @Test
    fun `a bimodal run shows two clusters rather than one median`() {
        val fast = List(50) { trace("f$it") } // 850 ms
        val throttled = List(50) { trace("t$it", decode = 1_280) } // 1 800 ms
        val buckets = StageSummary.histogram(fast + throttled)

        val occupied = buckets.filter { it.count > 0 }
        assertEquals("two clusters", 2, occupied.size)
        assertEquals(800, occupied.first().fromMillis)
        assertEquals(1_800, occupied.last().fromMillis)
    }

    /**
     * Empty buckets are kept. Omitting them would draw the run above as a smooth
     * distribution, which is exactly the defect the histogram exists to reveal.
     */
    @Test
    fun `empty buckets between clusters are kept`() {
        val buckets = StageSummary.histogram(List(5) { trace("f$it") } + List(5) { trace("t$it", decode = 1_280) })
        assertTrue("the gap must be drawn", buckets.any { it.count == 0 })
        assertEquals("and the buckets are contiguous from zero", 0, buckets.first().fromMillis)
    }

    @Test
    fun `an empty run produces no buckets rather than one containing nothing`() {
        assertTrue(StageSummary.histogram(emptyList()).isEmpty())
    }

    @Test
    fun `a bucket width of zero is refused`() {
        assertNotNull(
            runCatching { StageSummary.histogram(List(3) { trace() }, bucketMillis = 0) }.exceptionOrNull(),
        )
    }

    @Test
    fun `every observation lands in exactly one bucket`() {
        val traces = List(40) { n -> trace("u$n", decode = 300L + n * 17) }
        val buckets = StageSummary.histogram(traces)
        assertEquals(traces.size, buckets.sumOf { it.count })
    }
}

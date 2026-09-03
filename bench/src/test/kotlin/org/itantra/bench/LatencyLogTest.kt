package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The results file, task W3.11.
 *
 * These tests guard the two ways a results file goes quietly wrong: columns that drift
 * out of step with their header, and absent measurements recorded as zeros. Both
 * produce a file that looks correct and reports the wrong answer.
 */
class LatencyLogTest {
    private val ms = 1_000_000L
    private val tMic = 5_000_000_000L

    private fun trace(
        id: String = "u1",
        tAudio: Long? = tMic + 900 * ms,
        offset: Long = 0,
    ) = UtteranceTrace(
        utteranceId = id,
        language = "hi",
        mode = "ptt",
        transport = "rfcomm",
        tMic = tMic,
        tVad = tMic + 30 * ms,
        tEndpoint = tMic + 180 * ms,
        tFinal = tMic + 480 * ms,
        tTx = tMic + 520 * ms,
        tRx = tMic + 560 * ms + offset,
        tNorm = tMic + 565 * ms + offset,
        tChunk1 = tMic + 800 * ms + offset,
        tAudio = tAudio,
        tDone = tMic + 1_400 * ms + offset,
        payloadBytes = 22,
        frameBytes = 44,
        compressionRatio = 2182.0,
        confidence = "HIGH",
        templateId = 7,
        clockOffsetNanos = offset,
    )

    private fun rowsOf(vararg traces: UtteranceTrace): List<String> {
        val out = StringBuilder()
        val log = LatencyLog(out)
        traces.forEach { log.write(it) }
        return out.toString().trim().lines()
    }

    // ── the schema ───────────────────────────────────────────────────────────

    @Test
    fun `the header matches the schema in EVALUATION section 6`() {
        val expected =
            "utterance_id,lang,mode,transport,t_mic,t_vad,t_first_partial,t_endpoint," +
                "t_final,t_tx,t_rx,t_norm,t_chunk1,t_audio,t_done,payload_bytes," +
                "frame_bytes,compression_ratio,confidence,template_id,end_to_end_ms"
        assertEquals(expected, rowsOf(trace()).first())
    }

    @Test
    fun `every row has exactly as many fields as the header`() {
        val lines = rowsOf(trace("u1"), trace("u2"), trace("u3"))
        val width = lines.first().split(",").size
        assertEquals(21, width)
        for (line in lines.drop(1)) {
            assertEquals("row '$line' is the wrong width", width, line.split(",").size)
        }
    }

    @Test
    fun `the header is written once, however many rows follow`() {
        val lines = rowsOf(trace("u1"), trace("u2"), trace("u3"))
        assertEquals(4, lines.size)
        assertEquals(1, lines.count { it.startsWith("utterance_id") })
    }

    @Test
    fun `an empty log writes nothing at all, not a bare header`() {
        val out = StringBuilder()
        val log = LatencyLog(out)
        assertEquals("", out.toString())
        assertEquals(0, log.rowCount)
    }

    /**
     * A comma inside a field splits it and shifts every column after it — the same
     * silent corruption the arity check guards, arriving by a different route. No
     * current field can contain one, but a later column carrying recognised text
     * could, so the log refuses rather than writing a file that looks fine.
     */
    @Test
    fun `a separator inside a field is refused, not written`() {
        val out = StringBuilder()
        val log = LatencyLog(out)
        try {
            log.write(trace().copy(utteranceId = "u1,extra"))
            throw AssertionError("expected a refusal, the row was written")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("utterance_id"))
        }
        assertEquals("no row may have been written", 0, log.rowCount)
    }

    @Test
    fun `a newline inside a field is refused too`() {
        val log = LatencyLog(StringBuilder())
        try {
            log.write(trace().copy(confidence = "HIGH\nfake,row"))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("confidence"))
        }
    }

    // ── stages ───────────────────────────────────────────────────────────────

    @Test
    fun `stages are written relative to the microphone`() {
        val fields = rowsOf(trace())[1].split(",")
        assertEquals("t_mic is the origin", "0", fields[4])
        assertEquals("t_vad", "30", fields[5])
        assertEquals("t_endpoint", "180", fields[7])
        assertEquals("t_final", "480", fields[8])
    }

    /**
     * The offline recogniser produces no partial hypothesis. That field must be empty,
     * because a zero would be read as "a partial arrived at the microphone" and would
     * be averaged into the results as a measurement.
     */
    @Test
    fun `a stage that did not happen is empty, never zero`() {
        val fields = rowsOf(trace())[1].split(",")
        assertEquals("t_first_partial must be empty when there was no partial", "", fields[6])
    }

    @Test
    fun `an utterance that produced no audio still writes a row`() {
        val fields = rowsOf(trace(tAudio = null))[1].split(",")
        assertEquals("t_audio", "", fields[13])
        assertEquals("end_to_end_ms", "", fields[20])
    }

    @Test
    fun `metadata is written verbatim`() {
        val fields = rowsOf(trace())[1].split(",")
        assertEquals("u1", fields[0])
        assertEquals("hi", fields[1])
        assertEquals("ptt", fields[2])
        assertEquals("rfcomm", fields[3])
        assertEquals("22", fields[15])
        assertEquals("44", fields[16])
        assertEquals("2182.0", fields[17])
        assertEquals("HIGH", fields[18])
        assertEquals("7", fields[19])
    }

    /** A decimal comma would split the field and shift every column after it. */
    @Test
    fun `the compression ratio uses a decimal point regardless of the default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val fields = rowsOf(trace())[1].split(",")
            assertEquals(21, fields.size)
            assertEquals("2182.0", fields[17])
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // ── the cross-device figure ──────────────────────────────────────────────

    @Test
    fun `end to end is the receiver audio minus the sender microphone`() {
        assertEquals(900L, trace().endToEndMillis)
        assertEquals("900", rowsOf(trace())[1].split(",").last())
    }

    /**
     * The whole reason [UtteranceTrace] carries a clock offset. With B's clock 47 s
     * ahead, the raw difference is nonsense and the corrected one is the truth.
     */
    @Test
    fun `a clock difference between the handsets is removed`() {
        val offset = 47_000 * ms
        val t = trace(tAudio = tMic + 900 * ms + offset, offset = offset)
        assertEquals(900L, t.endToEndMillis)
    }

    @Test
    fun `receiver stages are corrected too, not only the final figure`() {
        val offset = 47_000 * ms
        val fields =
            rowsOf(trace(tAudio = tMic + 900 * ms + offset, offset = offset))[1]
                .split(",")
        assertEquals("t_rx", "560", fields[10])
        assertEquals("t_chunk1", "800", fields[12])
        assertEquals("t_done", "1400", fields[14])
    }

    @Test
    fun `sender stages are left alone by the offset`() {
        val offset = 47_000 * ms
        val fields =
            rowsOf(trace(tAudio = tMic + 900 * ms + offset, offset = offset))[1]
                .split(",")
        assertEquals("t_endpoint must not be shifted", "180", fields[7])
    }
}

class LatencySummaryTest {
    private val ms = 1_000_000L

    private fun run(vararg endToEndMillis: Long) =
        endToEndMillis.mapIndexed { i, delay ->
            UtteranceTrace(
                utteranceId = "u$i",
                language = "hi",
                mode = "ptt",
                transport = "rfcomm",
                tMic = 0,
                tAudio = delay * ms,
            )
        }

    @Test
    fun `median and p95 over a run`() {
        val stats = LatencySummary.of(run(800, 850, 900, 950, 1_000))!!
        assertEquals(5, stats.n)
        assertEquals(900L, stats.medianMillis)
        assertEquals(1_000L, stats.worstMillis)
    }

    /** A hundred utterances at the design target, with a realistic tail. */
    @Test
    fun `a hundred utterances summarise within the budget`() {
        val values = LongArray(100) { 780L + it * 3 }.toList() // 780..1077
        val stats = LatencySummary.of(run(*values.toLongArray()))!!
        assertEquals(100, stats.n)
        assertTrue(
            "median ${stats.medianMillis} must sit in the 800-1200 band",
            stats.medianMillis in 800..1_200,
        )
        assertTrue(
            "p95 ${stats.p95Millis} must sit in the 800-1200 band",
            stats.p95Millis in 800..1_200,
        )
        assertTrue("p95 must not be below the median", stats.p95Millis >= stats.medianMillis)
    }

    @Test
    fun `the p95 is a rank, not a mean, so one outlier does not hide`() {
        val values = List(99) { 900L } + 5_000L
        val stats = LatencySummary.of(run(*values.toLongArray()))!!
        assertEquals(900L, stats.medianMillis)
        assertEquals("the worst case must be reported, not averaged away", 5_000L, stats.worstMillis)
    }

    @Test
    fun `utterances that produced no audio are excluded from the statistics`() {
        val traces =
            run(800, 900, 1_000) +
                UtteranceTrace("x", "hi", "ptt", "rfcomm", tMic = 0, tAudio = null)
        val stats = LatencySummary.of(traces)!!
        assertEquals("the incomplete utterance must not count", 3, stats.n)
    }

    @Test
    fun `a run with nothing measurable summarises to null, not to zero`() {
        assertNull(LatencySummary.of(emptyList()))
        assertNull(
            LatencySummary.of(
                listOf(UtteranceTrace("x", "hi", "ptt", "rfcomm", tMic = 0, tAudio = null)),
            ),
        )
    }

    /** The reporting rule: fewer than a hundred utterances is not a result. */
    @Test
    fun `a run is reportable only at a hundred completed utterances`() {
        assertFalse(LatencySummary.isReportable(run(*LongArray(99) { 900L })))
        assertTrue(LatencySummary.isReportable(run(*LongArray(100) { 900L })))
    }

    @Test
    fun `nearest-rank percentile indices`() {
        assertEquals(94, LatencySummary.percentileIndex(100, 95))
        assertEquals(49, LatencySummary.percentileIndex(100, 50))
        // Too few samples to resolve a 95th percentile: the worst observation is honest.
        assertEquals(4, LatencySummary.percentileIndex(5, 95))
        assertEquals(0, LatencySummary.percentileIndex(1, 95))
    }
}

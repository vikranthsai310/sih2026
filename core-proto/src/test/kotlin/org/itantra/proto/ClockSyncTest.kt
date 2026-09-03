package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clock synchronisation, task W3.10.
 *
 * The tests are built around a simulated pair of handsets whose clocks differ by a
 * known amount, so the estimator can be checked against ground truth — which is the
 * only way to know the measuring instrument is not itself the error.
 */
class ClockSyncTest {
    /**
     * Builds a round trip for a remote clock that is [offsetMillis] ahead, where the
     * message takes [oneWayMillis] each way and the remote takes [turnaroundMillis] to
     * reply.
     */
    private fun sample(
        offsetMillis: Long,
        oneWayMillis: Long,
        turnaroundMillis: Long = 1,
        outboundExtraMillis: Long = 0,
        t1: Long = 1_000_000_000L,
    ): ClockSync.Sample {
        val ms = 1_000_000L
        val t2 = t1 + (oneWayMillis + outboundExtraMillis) * ms + offsetMillis * ms
        val t3 = t2 + turnaroundMillis * ms
        val t4 = t3 - offsetMillis * ms + oneWayMillis * ms
        return ClockSync.Sample(t1, t2, t3, t4)
    }

    private fun synced(vararg samples: ClockSync.Sample) = ClockSync().apply { samples.forEach { add(it) } }

    // ── the estimator ────────────────────────────────────────────────────────

    @Test
    fun `a symmetric path recovers the offset exactly`() {
        val sync =
            synced(
                sample(offsetMillis = 5_000, oneWayMillis = 20),
                sample(offsetMillis = 5_000, oneWayMillis = 20),
                sample(offsetMillis = 5_000, oneWayMillis = 20),
                sample(offsetMillis = 5_000, oneWayMillis = 20),
            )
        assertEquals(5_000L, sync.offsetMillis())
    }

    @Test
    fun `a negative offset -- the remote clock behind -- is recovered too`() {
        val sync =
            synced(
                sample(offsetMillis = -3_000, oneWayMillis = 15),
                sample(offsetMillis = -3_000, oneWayMillis = 15),
                sample(offsetMillis = -3_000, oneWayMillis = 15),
                sample(offsetMillis = -3_000, oneWayMillis = 15),
            )
        assertEquals(-3_000L, sync.offsetMillis())
    }

    @Test
    fun `the one-way delay is recovered independently of the offset`() {
        val sync =
            synced(
                sample(offsetMillis = 9_999, oneWayMillis = 20),
                sample(offsetMillis = 9_999, oneWayMillis = 20),
                sample(offsetMillis = 9_999, oneWayMillis = 20),
                sample(offsetMillis = 9_999, oneWayMillis = 20),
            )
        assertEquals(20L, sync.oneWayDelayNanos() / 1_000_000)
    }

    /**
     * The reason the specification says median rather than mean. One round trip is
     * delayed 200 ms by a busy radio; the offset must barely move.
     */
    @Test
    fun `a single delayed round trip does not move the offset`() {
        val sync =
            synced(
                sample(offsetMillis = 5_000, oneWayMillis = 20),
                sample(offsetMillis = 5_000, oneWayMillis = 20),
                sample(offsetMillis = 5_000, oneWayMillis = 20, outboundExtraMillis = 200),
                sample(offsetMillis = 5_000, oneWayMillis = 20),
            )
        val error = Math.abs(sync.offsetMillis() - 5_000L)
        assertTrue("an outlier moved the offset by $error ms", error <= 1)
    }

    /**
     * The estimator's known weakness, stated as a test so nobody is surprised by it:
     * asymmetry between the two directions shows up as half of itself in the offset.
     * At Bluetooth scale that is a few milliseconds against an 800 ms budget.
     */
    @Test
    fun `path asymmetry biases the offset by half the asymmetry`() {
        val sync =
            synced(
                sample(offsetMillis = 0, oneWayMillis = 20, outboundExtraMillis = 10),
                sample(offsetMillis = 0, oneWayMillis = 20, outboundExtraMillis = 10),
                sample(offsetMillis = 0, oneWayMillis = 20, outboundExtraMillis = 10),
                sample(offsetMillis = 0, oneWayMillis = 20, outboundExtraMillis = 10),
            )
        assertEquals("10 ms of asymmetry must appear as 5 ms of offset", 5L, sync.offsetMillis())
    }

    // ── refusing to produce a number it cannot support ───────────────────────

    @Test
    fun `it is not synchronised until four round trips have completed`() {
        val sync = ClockSync()
        repeat(3) {
            sync.add(sample(offsetMillis = 100, oneWayMillis = 20))
            assertFalse(sync.isSynchronised)
        }
        sync.add(sample(offsetMillis = 100, oneWayMillis = 20))
        assertTrue(sync.isSynchronised)
    }

    /**
     * Silently returning zero would make every latency figure derived from it wrong in
     * a way nobody would notice until the results were challenged.
     */
    @Test
    fun `asking for the offset before synchronising fails loudly`() {
        val sync = ClockSync()
        sync.add(sample(offsetMillis = 100, oneWayMillis = 20))
        try {
            sync.offsetNanos()
            throw AssertionError("expected a refusal, got a number")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("1 of 4"))
        }
    }

    @Test
    fun `a round trip that violates causality is rejected`() {
        val sync = ClockSync()
        // The reply arrives before it was sent: a clock stepped mid-exchange.
        assertFalse(sync.add(ClockSync.Sample(t1 = 1_000, t2 = 2_000, t3 = 3_000, t4 = 500)))
        assertEquals(0, sync.sampleCount)
    }

    @Test
    fun `a round trip whose remote replied before receiving is rejected`() {
        val sync = ClockSync()
        assertFalse(sync.add(ClockSync.Sample(t1 = 1_000, t2 = 5_000, t3 = 4_000, t4 = 9_000)))
        assertEquals(0, sync.sampleCount)
    }

    @Test
    fun `reset clears the exchange`() {
        val sync =
            synced(
                sample(offsetMillis = 1, oneWayMillis = 5),
                sample(offsetMillis = 1, oneWayMillis = 5),
                sample(offsetMillis = 1, oneWayMillis = 5),
                sample(offsetMillis = 1, oneWayMillis = 5),
            )
        assertTrue(sync.isSynchronised)
        sync.reset()
        assertFalse(sync.isSynchronised)
        assertEquals(0, sync.sampleCount)
    }

    // ── the thing it exists for ──────────────────────────────────────────────

    /**
     * The end-to-end measurement, end to end. Handset B's clock is 47 seconds ahead of
     * A's. A speaks; 900 ms later B plays audio. Subtracting the raw timestamps would
     * report 47 900 ms; the offset must recover the true 900 ms.
     */
    @Test
    fun `a cross-device latency is recovered despite a large clock difference`() {
        val ms = 1_000_000L
        val offsetMillis = 47_000L
        val sync =
            synced(
                sample(offsetMillis = offsetMillis, oneWayMillis = 20),
                sample(offsetMillis = offsetMillis, oneWayMillis = 20),
                sample(offsetMillis = offsetMillis, oneWayMillis = 20),
                sample(offsetMillis = offsetMillis, oneWayMillis = 20),
            )

        val tMicOnA = 8_000_000_000L
        val tAudioOnB = tMicOnA + 900 * ms + offsetMillis * ms

        val naive = (tAudioOnB - tMicOnA) / ms
        assertEquals("the uncorrected subtraction is nonsense", 47_900L, naive)

        val corrected = (sync.toLocalNanos(tAudioOnB) - tMicOnA) / ms
        assertEquals(900L, corrected)
    }

    // ── the median helper, shared with the latency summary ───────────────────

    @Test
    fun `median of an odd count is the middle value`() {
        assertEquals(30L, ClockSync.median(listOf(50L, 10L, 30L)))
    }

    @Test
    fun `median of an even count averages the two middle values`() {
        assertEquals(25L, ClockSync.median(listOf(10L, 20L, 30L, 40L)))
        assertEquals(15L, ClockSync.median(listOf(10L, 20L)))
    }

    @Test
    fun `median does not overflow on nanosecond-scale values`() {
        val big = Long.MAX_VALUE - 4
        assertEquals(big - 1, ClockSync.median(listOf(big - 2, big - 1, big - 1, big)))
    }

    @Test
    fun `median of nothing is refused`() {
        try {
            ClockSync.median(emptyList())
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // A median of no observations is not zero; it does not exist.
        }
    }
}

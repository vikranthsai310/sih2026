package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rolling window on the live path: the offset follows the clocks as they drift. */
class ClockSyncWindowTest {
    private val ms = 1_000_000L

    private fun sample(
        offsetMillis: Long,
        t1: Long,
    ): ClockSync.Sample {
        val t2 = t1 + 20 * ms + offsetMillis * ms
        val t3 = t2 + 1 * ms
        val t4 = t1 + 41 * ms
        return ClockSync.Sample(t1, t2, t3, t4)
    }

    @Test
    fun `only the most recent samples are kept`() {
        val sync = ClockSync(maxSamples = 8)
        for (i in 0 until 20) sync.add(sample(offsetMillis = 100L + i, t1 = i * 5_000L * ms))
        assertEquals(8, sync.sampleCount)
        // Samples 12..19 remain: offsets 112..119, median 115.5 -> averaged as 115.
        assertEquals(115, sync.offsetMillis())
    }

    @Test
    fun `a drifting remote clock is followed rather than averaged over the whole run`() {
        val sync = ClockSync()
        for (i in 0 until 8) sync.add(sample(offsetMillis = 0, t1 = i * 5_000L * ms))
        assertEquals(0, sync.offsetMillis())
        for (i in 8 until 16) sync.add(sample(offsetMillis = 500, t1 = i * 5_000L * ms))
        assertEquals(500, sync.offsetMillis())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window smaller than the required sample count is refused`() {
        ClockSync(requiredSamples = 4, maxSamples = 2)
    }
}

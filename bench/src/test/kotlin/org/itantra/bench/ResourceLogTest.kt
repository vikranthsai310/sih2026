package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resource log and its soak summary — tasks W6.18 and W6.16, risk T-03. */
class ResourceLogTest {
    private fun sample(
        t: Long,
        cpu: Double = 22.0,
        rss: Long = 300_000_000,
        battery: Int = 80,
        thermal: Int = 0,
        state: String = "LISTENING",
    ) = ResourceSample(
        timestampMillis = t,
        cpuPercent = cpu,
        rssBytes = rss,
        batteryPercent = battery,
        thermalStatus = thermal,
        state = state,
        lang = "hi",
        transport = "rfcomm",
    )

    private fun rowsOf(vararg samples: ResourceSample): List<String> {
        val out = StringBuilder()
        val writer = ResourceLogWriter(out)
        samples.forEach { writer.write(it) }
        return out.toString().trim().lines()
    }

    // ── the file ─────────────────────────────────────────────────────────────

    @Test
    fun `the header matches the schema`() {
        assertEquals(
            "timestamp,cpu_pct,rss_bytes,battery_pct,thermal_status,state,lang,transport",
            rowsOf(sample(0)).first(),
        )
    }

    @Test
    fun `every row has the schema width`() {
        val lines = rowsOf(sample(0), sample(1_000), sample(2_000))
        val width = lines.first().split(",").size
        for (line in lines.drop(1)) assertEquals(width, line.split(",").size)
    }

    @Test
    fun `processor use keeps one decimal in a neutral locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val fields = rowsOf(sample(0, cpu = 22.45))[1].split(",")
            assertEquals(8, fields.size)
            assertEquals("22.5", fields[1])
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `the engine state is recorded so a spike can be attributed`() {
        val fields = rowsOf(sample(0, state = "RECOGNISING"))[1].split(",")
        assertEquals("RECOGNISING", fields[5])
    }

    @Test
    fun `implausible values are refused rather than logged`() {
        for (build in listOf<() -> Unit>(
            { sample(0, cpu = -1.0) },
            { sample(0, battery = 101) },
            { sample(0, battery = -1) },
            { sample(0, thermal = 7) },
        )) {
            try {
                build()
                throw AssertionError("expected a refusal")
            } catch (expected: IllegalArgumentException) {
                // A sample that cannot be true would be averaged in as if it were.
            }
        }
    }

    // ── throttling is the figure that matters ────────────────────────────────

    @Test
    fun `throttling is detected at the platform's light threshold`() {
        assertFalse(sample(0, thermal = 0).isThrottling)
        assertTrue(sample(0, thermal = 1).isThrottling)
        assertTrue(sample(0, thermal = 4).isThrottling)
    }

    /**
     * The single most useful thing the file records. An entry-tier handset throttles
     * after roughly ten minutes of continuous inference, and finding that in week 8
     * would be finding it too late.
     */
    @Test
    fun `the summary reports how long the device ran before throttling`() {
        val minute = 60_000L
        val samples =
            (0..30).map { m ->
                sample(m * minute, thermal = if (m >= 11) 2 else 0)
            }
        val summary = ResourceRun.summarise(samples)!!

        assertEquals(11 * minute, summary.throttlingBeganAtMillis)
        assertEquals(11.0, summary.minutesBeforeThrottling!!, 1e-9)
        assertEquals(2, summary.maxThermalStatus)
    }

    @Test
    fun `a device that never throttles reports null rather than zero`() {
        val summary = ResourceRun.summarise((0..10).map { sample(it * 1_000L) })!!
        assertNull(summary.throttlingBeganAtMillis)
        assertNull(summary.minutesBeforeThrottling)
    }

    @Test
    fun `peaks and drain are summarised`() {
        val samples =
            listOf(
                sample(0, cpu = 10.0, rss = 200_000_000, battery = 90),
                sample(1_000, cpu = 48.0, rss = 340_000_000, battery = 86),
                sample(2_000, cpu = 30.0, rss = 310_000_000, battery = 83),
            )
        val summary = ResourceRun.summarise(samples)!!

        assertEquals(48.0, summary.peakCpuPercent, 1e-9)
        assertEquals(340_000_000L, summary.peakRssBytes)
        assertEquals(7, summary.batteryDrainPercent)
        assertEquals(3, summary.samples)
    }

    @Test
    fun `samples out of order are still summarised correctly`() {
        val samples = listOf(sample(2_000, battery = 83), sample(0, battery = 90))
        val summary = ResourceRun.summarise(samples)!!

        assertEquals(2_000L, summary.durationMillis)
        assertEquals(7, summary.batteryDrainPercent)
    }

    @Test
    fun `an empty run summarises to null, not to zeroes`() {
        assertNull(ResourceRun.summarise(emptyList()))
    }

    @Test
    fun `a run is only a soak once it reaches thirty minutes`() {
        val minute = 60_000L
        val short = ResourceRun.summarise((0..29).map { sample(it * minute) })!!
        val full = ResourceRun.summarise((0..30).map { sample(it * minute) })!!

        assertFalse(short.isSoak)
        assertTrue(full.isSoak)
    }

    /** A run taken on charge is not an endurance measurement, and this is how you know. */
    @Test
    fun `a battery that rises reveals the handset was on charge`() {
        val onCharge = listOf(sample(0, battery = 80), sample(1_000, battery = 82))
        val onBattery = listOf(sample(0, battery = 80), sample(1_000, battery = 78))

        assertTrue(ResourceRun.wasOnCharge(onCharge))
        assertFalse(ResourceRun.wasOnCharge(onBattery))
    }

    @Test
    fun `battery drain is never reported as negative`() {
        val summary = ResourceRun.summarise(listOf(sample(0, battery = 80), sample(1_000, battery = 85)))!!
        assertEquals(0, summary.batteryDrainPercent)
    }
}

package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two holes found in the export path on 2026-09-12: an export with no utterances at all
 * passed every check, and a file with rows carried its column line twice.
 */
class ReportBundleGateTest {
    private val good =
        RunConditions(
            device = "Redmi 10A",
            build = "1.0.0-rc1",
            soakMinutes = 32,
            batteryPercent = 61,
            onCharge = false,
            date = "2026-09-12",
            isReleaseBuild = true,
        )

    private fun trace(i: Int): UtteranceTrace {
        val mic = 1_000_000_000L + i * 3_000_000_000L
        return UtteranceTrace(
            utteranceId = "u$i",
            language = "hi",
            mode = "PTT",
            transport = "bluetooth",
            tMic = mic,
            tEndpoint = mic + 400_000_000L,
            tFinal = mic + 700_000_000L,
            tTx = mic + 720_000_000L,
            tRx = mic + 760_000_000L + 250_000_000L,
            tAudio = mic + 950_000_000L + 250_000_000L,
            frameBytes = 44,
            clockOffsetNanos = 250_000_000L,
        )
    }

    @Test
    fun `an export with no utterances at all is refused, not written empty`() {
        val unmet = good.unmetRequirements(emptyList())
        assertTrue(unmet.toString(), unmet.any { "0 utterances completed" in it })
        val threw = runCatching { ReportBundle(good).write(emptyList(), emptyList(), emptyList()) }.isFailure
        assertTrue("three files of headers were written", threw)
    }

    @Test
    fun `a resource-only check still passes without a latency run`() {
        assertTrue(good.unmetRequirements().isEmpty())
        assertTrue(good.isReportable)
    }

    @Test
    fun `the column line appears exactly once in every file`() {
        val files = ReportBundle(good).write((0 until 100).map(::trace), emptyList(), emptyList())
        val latency = files.getValue("latency.csv").lines()
        assertEquals(1, latency.count { it == LatencyLog.COLUMNS.joinToString(",") })
        assertEquals(100, latency.count { Regex("^u\\d+,").containsMatchIn(it) })
        for (name in listOf("resource.csv", "scorecard.csv")) {
            val lines = files.getValue(name).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertEquals("$name should be a header and nothing else", 1, lines.size)
        }
    }

    @Test
    fun `a receiver timestamp is read on the sender's clock`() {
        val fields = trace(0).toFields()
        val byName = LatencyLog.COLUMNS.zip(fields).toMap()
        assertEquals("950", byName["end_to_end_ms"])
        assertEquals("760", byName["t_rx"])
        assertEquals("950", byName["t_audio"])
    }
}

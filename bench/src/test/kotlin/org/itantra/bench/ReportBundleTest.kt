package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three result files and the conditions that gate them. Tasks **W8.8**, **W8.2**,
 * **W8.3**.
 */
class ReportBundleTest {
    private val good =
        RunConditions(
            device = "Redmi 10A",
            build = "1.0.0-rc1",
            soakMinutes = 32,
            batteryPercent = 61,
            onCharge = false,
            date = "2026-09-04",
            isReleaseBuild = true,
        )

    // ── the gate ─────────────────────────────────────────────────────────────

    @Test
    fun `a run meeting every condition is reportable`() {
        assertTrue(good.unmetRequirements().toString(), good.isReportable)
    }

    @Test
    fun `a debug build is not reportable`() {
        val unmet = good.copy(isReleaseBuild = false).unmetRequirements()
        assertTrue(unmet.toString(), unmet.any { "release build" in it })
    }

    @Test
    fun `an emulator is not reportable`() {
        val unmet = good.copy(device = "Android Emulator API 34").unmetRequirements()
        assertTrue(unmet.toString(), unmet.any { "emulator" in it })
    }

    /** Cold figures are the ones that flatter, which is why they are refused. */
    @Test
    fun `a run short of the thirty-minute soak is not reportable`() {
        val unmet = good.copy(soakMinutes = 5).unmetRequirements()
        assertTrue(unmet.toString(), unmet.any { "soaked 5 minutes" in it })
    }

    @Test
    fun `a run on charge or on a low battery is not reportable`() {
        assertTrue(good.copy(onCharge = true).unmetRequirements().any { "on charge" in it })
        assertTrue(good.copy(batteryPercent = 12).unmetRequirements().any { "battery at 12" in it })
    }

    /**
     * All of them, not the first. Being told the build was wrong, fixing it, and then
     * being told the battery was too is two wasted half-hours.
     */
    @Test
    fun `every unmet condition is reported at once`() {
        val unmet =
            good.copy(
                isReleaseBuild = false,
                soakMinutes = 0,
                batteryPercent = 5,
                onCharge = true,
            ).unmetRequirements()
        assertEquals(unmet.toString(), 4, unmet.size)
    }

    /** W8.3: a median over fewer than a hundred utterances is not a median. */
    @Test
    fun `fewer than a hundred utterances is not reportable`() {
        val unmet = good.unmetRequirements(traces(50))
        assertTrue(unmet.toString(), unmet.any { "50 utterances completed" in it })
        assertTrue(good.unmetRequirements(traces(100)).isEmpty())
    }

    // ── the files ────────────────────────────────────────────────────────────

    @Test
    fun `a reportable run writes all three files`() {
        val files = ReportBundle(good).write(traces(100), samples(), scorecard())
        assertEquals(setOf("latency.csv", "resource.csv", "scorecard.csv"), files.keys)
    }

    /**
     * The point of W8.8. A file that does not say where it came from acquires a
     * provenance in whatever slide quotes it.
     */
    @Test
    fun `every file names its device and its soak duration`() {
        val files = ReportBundle(good).write(traces(100), samples(), scorecard())
        for ((name, content) in files) {
            assertTrue("$name does not name the device", "# device: Redmi 10A" in content)
            assertTrue("$name does not name the soak", "# soak_minutes: 32" in content)
            assertTrue("$name does not name the build", "# build: 1.0.0-rc1" in content)
            assertTrue("$name does not name the date", "# date: 2026-09-04" in content)
        }
    }

    @Test
    fun `the preamble is commented so a spreadsheet skips it and the header follows`() {
        val latency = ReportBundle(good).write(traces(100), samples(), scorecard()).getValue("latency.csv")
        val lines = latency.lines()
        assertTrue(lines.takeWhile { it.startsWith("#") }.size >= 5)
        assertEquals(
            LatencyLog.COLUMNS.joinToString(","),
            lines.first { !it.startsWith("#") },
        )
    }

    /**
     * No files rather than files needing a caveat nobody will attach. This is the whole
     * behaviour: a bad run leaves nothing behind to be quoted later.
     */
    @Test
    fun `an unreportable run writes nothing at all`() {
        val thrown =
            runCatching {
                ReportBundle(good.copy(isReleaseBuild = false)).write(traces(100), samples(), scorecard())
            }.exceptionOrNull()
        assertNotNull("a debug run must produce no files", thrown)
        assertTrue(thrown!!.message!!.contains("EVALUATION.md section 1"))
    }

    @Test
    fun `a debug build is marked in the preamble as well as refused`() {
        val debug = good.copy(isReleaseBuild = false)
        assertTrue(debug.preamble().contains("(DEBUG)"))
        assertFalse(debug.isReportable)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun traces(count: Int) =
        (1..count).map { n ->
            UtteranceTrace(
                utteranceId = "u$n",
                language = "hi",
                mode = "ptt",
                transport = "ble",
                tMic = 0,
                tVad = 30_000_000,
                tEndpoint = 230_000_000,
                tFinal = 560_000_000,
                tTx = 580_000_000,
                tRx = 600_000_000,
                tNorm = 605_000_000,
                tChunk1 = 800_000_000,
                tAudio = 850_000_000,
            )
        }

    private fun samples() =
        (1..10).map { n ->
            ResourceSample(
                timestampMillis = n * 1_000L,
                cpuPercent = 22.0,
                rssBytes = 180_000_000,
                batteryPercent = 61,
                thermalStatus = 1,
                state = "LISTENING",
                lang = "hi",
                transport = "ble",
            )
        }

    private fun scorecard() =
        listOf(
            ScorecardRow(
                lang = "hi",
                model = "IndicConformer int8",
                modelBytes = 125_829_120,
                voiceBytes = 63_963_136,
                device = "Redmi 10A",
                build = "1.0.0-rc1",
                soakMinutes = 32,
                date = "2026-09-04",
            ),
        )
}

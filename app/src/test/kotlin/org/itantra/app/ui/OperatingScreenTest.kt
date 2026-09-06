package org.itantra.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Band F's numbers and its spoken form. Tasks **W1.34**, **W7.23**.
 *
 * ## What a unit test can hold here and what it cannot
 *
 * The layout — the band order, the 33 % transmit control, the behaviour at 200 % text —
 * needs a device or a Compose UI test and is not asserted here. What is asserted is the
 * part that is pure data and gets quietly wrong: whether an unmeasured figure is drawn as a
 * zero, and whether the strip a jury photographs can be read aloud.
 */
class OperatingScreenTest {
    // ── nothing unmeasured is reported as zero ───────────────────────────────

    /**
     * A zero-millisecond stage and a stage that was never measured are different facts, and
     * this is the strip a jury photographs. Reporting the second as the first is a claim
     * the project did not make.
     */
    @Test
    fun `an unmeasured metric has no value rather than a zero`() {
        val empty = BandFMetrics()
        assertNull(empty.totalMillis)
        assertNull("no frame means no ratio, not an infinite one", empty.compressionRatio)
    }

    @Test
    fun `a frame size yields the ratio against three seconds of audio`() {
        assertEquals(3_310, BandFMetrics(lastFrameBytes = 29).compressionRatio)
        assertEquals(7_385, BandFMetrics(lastFrameBytes = 13).compressionRatio)
        assertEquals(2_182, BandFMetrics(lastFrameBytes = 44).compressionRatio)
    }

    /** A zero-byte frame is not a frame, and dividing by it would print infinity. */
    @Test
    fun `a zero-byte frame yields no ratio`() {
        assertNull(BandFMetrics(lastFrameBytes = 0).compressionRatio)
    }

    // ── the strip read aloud ─────────────────────────────────────────────────

    /**
     * A monospace strip of `STT 210 · LINK 40 · TTS 180 ms` is unreadable glyph by glyph.
     * TalkBack gets a sentence.
     */
    @Test
    fun `band F is announced as a sentence rather than as symbols`() {
        val spoken =
            spokenMetrics(
                BandFMetrics(
                    sttMillis = 210,
                    linkMillis = 40,
                    ttsMillis = 180,
                    totalMillis = 780,
                    lastFrameBytes = 29,
                ),
            )
        assertTrue(spoken, "780 milliseconds" in spoken)
        assertTrue(spoken, "29 bytes" in spoken)
        assertTrue(spoken, "3310 times smaller" in spoken)
        for (symbol in listOf("·", "×", "—", "%")) {
            assertFalse("'$symbol' reached the announcement", symbol in spoken)
        }
    }

    /** Silence is stated. An empty announcement leaves a listener unsure whether it read. */
    @Test
    fun `an empty band F says so rather than saying nothing`() {
        assertEquals("Nothing measured yet.", spokenMetrics(BandFMetrics()))
    }

    @Test
    fun `a partial measurement announces only what it has`() {
        val spoken = spokenMetrics(BandFMetrics(sttMillis = 210))
        assertTrue(spoken, "Recognition 210" in spoken)
        assertFalse("nothing else may be invented", "Link" in spoken)
        assertFalse("Total" in spoken)
    }

    // ── processor use is stated in cores, not as a percentage ────────────────

    /**
     * The strip used to print `CPU 107.0 %`, which reads as 107 % of the handset and so
     * reads as broken. The measurement was right — the decoder is multi-threaded and one
     * core plus a sliver of a second is exactly what it used — but the unit invited the
     * wrong denominator. Cores carry their own scale.
     */
    @Test
    fun `more than one core busy is stated as cores rather than as over 100 percent`() {
        val label = cpuLabel(1.07, 8)
        assertEquals("1.07/8 cores", label)
        assertFalse("a percentage sign invites the wrong denominator", "%" in label)
    }

    /** Multi-threaded decoding genuinely uses several cores; the figure is not clamped. */
    @Test
    fun `several busy cores are reported in full`() {
        assertEquals("2.60/8 cores", cpuLabel(2.6, 8))
    }

    /** Without a core count the figure still has a unit, just no scale to sit against. */
    @Test
    fun `an unknown core count still names the unit`() {
        assertEquals("1.07 cores", cpuLabel(1.07, null))
        assertEquals("1.07 cores", cpuLabel(1.07, 0))
    }

    /** Unmeasured is a dash, like every other figure on the strip — never a zero. */
    @Test
    fun `unmeasured processor use is a dash`() {
        assertEquals("—", cpuLabel(null, 8))
        assertEquals("—", cpuLabel(null, null))
    }

    /**
     * The strip shows processor use and real-time factor; a listener who cannot see it was
     * being told strictly less than a sighted operator standing beside them.
     */
    @Test
    fun `processor use and real time factor are announced too`() {
        val spoken =
            spokenMetrics(
                BandFMetrics(totalMillis = 780, realTimeFactor = 0.41, cpuCores = 1.07, cpuCoreCount = 8),
            )
        assertTrue(spoken, "Processor 1.07 of 8 cores" in spoken)
        assertTrue(spoken, "Real time factor 0.41" in spoken)
        assertFalse("'%' reached the announcement", "%" in spoken)
    }

    // ── the state the screen is given ────────────────────────────────────────

    @Test
    fun `a state with no link and queued frames carries both facts`() {
        val state =
            OperatingState(
                unitName = "BASE",
                nodeId = 1,
                peerCount = 0,
                linkUp = false,
                transportName = "bluetooth",
                mode = "PTT",
                audience = "ALL UNITS",
                language = "हिन्दी",
                queued = 3,
            )
        assertFalse(state.linkUp)
        assertEquals(3, state.queued)
        assertTrue("the language is shown in its own script", state.language.any { it.code > 0x7F })
    }

    @Test
    fun `the default state shows nothing measured and nothing degraded`() {
        val state =
            OperatingState(
                unitName = "BASE",
                nodeId = 1,
                peerCount = 0,
                linkUp = false,
                transportName = "bluetooth",
                mode = "PTT",
                audience = "ALL UNITS",
                language = "हिन्दी",
            )
        assertNull(state.degraded)
        assertNull(state.partial)
        assertEquals(BandFMetrics(), state.metrics)
    }
}

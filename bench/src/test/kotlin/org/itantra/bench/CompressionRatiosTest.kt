package org.itantra.bench

import org.itantra.proto.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four published compression figures. Task **W8.9**.
 *
 * ## What these assertions are for
 *
 * They pin the numbers that appear in `docs/`, the submission and the demo script to the
 * frame codec that produces them. If someone widens the header by a byte, these fail and
 * name the documents that need changing — rather than the documents quietly becoming
 * wrong and the drift being found by a jury.
 */
class CompressionRatiosTest {
    // ── the four published figures ───────────────────────────────────────────

    @Test
    fun `the headline is 2182 times`() {
        val ratio = CompressionRatios.unauthenticated
        assertEquals("96 000 B of PCM", 96_000, ratio.fromBytes)
        assertEquals("44 B on the wire", 44, ratio.toBytes)
        assertEquals(2_182, ratio.rounded)
    }

    @Test
    fun `the authenticated figure is 1600 times at 60 bytes`() {
        val ratio = CompressionRatios.authenticated
        assertEquals("12 B frame plus 32 B payload plus a 16-byte tag", 60, ratio.toBytes)
        assertEquals(1_600, ratio.rounded)
    }

    @Test
    fun `against Opus it is 43 times`() {
        val ratio = CompressionRatios.versusOpus
        assertEquals("Opus at 6 kbps for three seconds", 2_250, ratio.fromBytes)
        assertEquals("12 B frame plus 32 B payload plus an 8-byte tag", 52, ratio.toBytes)
        assertEquals(43, ratio.rounded)
    }

    @Test
    fun `the template best case is 7385 times at 13 bytes`() {
        val ratio = CompressionRatios.template
        assertEquals("the 13 B frame PROTOCOL.md section 1 describes", 13, ratio.toBytes)
        assertEquals(7_385, ratio.rounded)
    }

    // ── the pairing of ratio to frame variant ────────────────────────────────

    /**
     * The subtlety that makes these four drift. The deployment figure uses the full tag
     * because Bluetooth has bytes to spare; the Opus comparison uses the truncated tag
     * because it is a comparison against a low-rate radio link. Swapping them would
     * overstate the deployment case.
     */
    @Test
    fun `the authenticated and Opus figures deliberately use different tags`() {
        val deployed = CompressionRatios.authenticated.toBytes
        val lowRate = CompressionRatios.versusOpus.toBytes
        assertTrue("the deployment frame must be the larger of the two", deployed > lowRate)
        assertEquals("and the difference is exactly the eight bytes of tag", 8, deployed - lowRate)
    }

    /**
     * Quoting the 8-byte frame as the authenticated ratio would claim 1 846× where the
     * defensible number is 1 600×. Fifteen per cent, and it is the number a jury checks.
     */
    @Test
    fun `the truncated-tag frame would overstate the deployment ratio`() {
        val honest = CompressionRatios.authenticated.rounded
        val overstated =
            CompressionRatios.Ratio(
                "x",
                CompressionRatios.rawAudioBytes,
                CompressionRatios.versusOpus.toBytes,
                "",
            ).rounded
        assertEquals(1_846, overstated)
        assertTrue("$overstated must not be the figure published as authenticated", overstated > honest)
    }

    // ── derived, not transcribed ─────────────────────────────────────────────

    /**
     * The property that makes this file worth having. Change the header and every
     * published ratio changes with it, loudly.
     */
    @Test
    fun `every size is derived from the frame codec`() {
        val overhead = Frame.HEADER_SIZE + Frame.CRC_SIZE
        assertEquals("the 12 B frame overhead PROTOCOL.md documents", 12, overhead)
        assertEquals(
            overhead + CompressionRatios.PACKED_SENTENCE_PAYLOAD,
            CompressionRatios.unauthenticated.toBytes,
        )
        assertEquals(overhead + CompressionRatios.TEMPLATE_PAYLOAD, CompressionRatios.template.toBytes)
    }

    /** A real frame of the shape being quoted must actually be that size. */
    @Test
    fun `a frame built to these dimensions really is 44 bytes on the wire`() {
        val frame =
            Frame(
                type = org.itantra.proto.MessageType.TEXT,
                language = org.itantra.proto.Language.HINDI,
                seq = 1,
                flags = 0,
                src = 1,
                keyId = 1,
                ttl = 3,
                payload = ByteArray(CompressionRatios.PACKED_SENTENCE_PAYLOAD),
            )
        assertEquals(44, frame.wireSize)
        assertEquals(frame.wireSize, frame.encode().size)
    }

    @Test
    fun `a template frame really is 13 bytes on the wire`() {
        val frame =
            Frame(
                type = org.itantra.proto.MessageType.TEMPLATE,
                language = org.itantra.proto.Language.HINDI,
                seq = 1,
                flags = 0,
                src = 1,
                keyId = 1,
                ttl = 3,
                payload = byteArrayOf(0x04),
            )
        assertEquals(13, frame.wireSize)
    }

    // ── how they are reported ────────────────────────────────────────────────

    /** The best case must never appear without being called one. */
    @Test
    fun `every ratio carries a caveat`() {
        for (ratio in CompressionRatios.all) {
            assertTrue("${ratio.name} has no caveat", ratio.caveat.isNotBlank())
        }
        assertTrue(CompressionRatios.template.caveat.contains("best case"))
    }

    @Test
    fun `the statement carries the arithmetic that produced it`() {
        val statement = CompressionRatios.unauthenticated.statement()
        assertTrue(statement, "96000" in statement && "44" in statement && "2182" in statement)
    }

    @Test
    fun `the CSV has one row per figure and names its caveat`() {
        val lines = CompressionRatios.toCsv().trim().lines()
        assertEquals("measure,from_bytes,to_bytes,ratio,caveat", lines.first())
        assertEquals(4 + 1, lines.size)
        assertTrue(lines.drop(1).all { it.contains('"') })
    }

    @Test
    fun `a ratio against zero bytes is refused rather than reported as infinite`() {
        assertNotNull(
            runCatching { CompressionRatios.Ratio("x", 96_000, 0, "") }.exceptionOrNull(),
        )
    }
}

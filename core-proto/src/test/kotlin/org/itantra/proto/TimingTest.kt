package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock-sync and audio-receipt payloads, task W3.10. */
class TimingTest {
    @Test
    fun `a ping is ten bytes and round-trips its timestamp`() {
        val ping = Timing.Ping(t1 = 1_234_567_890_123L)
        val bytes = ping.encode()
        assertEquals(10, bytes.size)
        assertEquals(ping, Timing.decode(bytes))
    }

    @Test
    fun `a pong carries all three timestamps, negative ones included`() {
        // nanoTime has an arbitrary origin and may be negative on some platforms.
        val pong = Timing.Pong(t1 = -5L, t2 = Long.MAX_VALUE, t3 = Long.MIN_VALUE)
        val bytes = pong.encode()
        assertEquals(26, bytes.size)
        assertEquals(pong, Timing.decode(bytes))
    }

    /**
     * The answer is broadcast, so a third unit must be able to tell it is not the one being
     * answered — otherwise it reads the pinger's `t1` as its own and derives its offset
     * from two unrelated clock origins.
     */
    @Test
    fun `an addressed pong says whose ping it answers`() {
        val pong = Timing.Pong(t1 = 1L, t2 = 2L, t3 = 3L, to = 0xC4)
        val bytes = pong.encode()
        assertEquals(27, bytes.size)
        assertEquals(pong, Timing.decode(bytes))
        assertTrue(pong.answers(0xC4))
        assertFalse(pong.answers(0x11))
    }

    /** An unaddressed pong is read exactly as before, and answers anyone. */
    @Test
    fun `a pong from a build that predates addressing still answers`() {
        val pong = Timing.Pong(t1 = 1L, t2 = 2L, t3 = 3L)
        assertEquals(26, pong.encode().size)
        assertTrue(pong.answers(0x11))
        assertTrue((Timing.decode(pong.encode()) as Timing.Pong).answers(0x99))
    }

    @Test
    fun `an audio report names the sender and the sequence it answers`() {
        val report = Timing.AudioReport(sender = 0xFD, seq = 0xBEEF, rxNanos = 7L, audioNanos = 900_000_007L)
        val bytes = report.encode()
        assertEquals(21, bytes.size)
        assertEquals(report, Timing.decode(bytes))
    }

    /**
     * The staged form carries the two receiver-side boundaries between `tRx` and `tAudio`.
     * Without them three of the seven rows in the stage table can never have a figure.
     */
    @Test
    fun `an audio report can carry the receiver's normalise and synthesis marks`() {
        val report =
            Timing.AudioReport(
                sender = 3,
                seq = 9,
                rxNanos = 100L,
                audioNanos = 900L,
                normNanos = 150L,
                chunk1Nanos = 700L,
            )
        val bytes = report.encode()
        assertEquals(37, bytes.size)
        assertEquals(report, Timing.decode(bytes))
    }

    /**
     * A unit on an older build sends the short form. It has to keep working, or the whole
     * end-to-end exchange fails over a length check during a mixed-build demonstration.
     */
    @Test
    fun `the short audio report still decodes, with the stage marks absent`() {
        val short = Timing.AudioReport(sender = 3, seq = 9, rxNanos = 100L, audioNanos = 900L)
        val decoded = Timing.decode(short.encode()) as Timing.AudioReport
        assertEquals(21, short.encode().size)
        assertNull(decoded.normNanos)
        assertNull(decoded.chunk1Nanos)
    }

    /** Half a decomposition is not one: one mark without the other sends the short form. */
    @Test
    fun `an audio report with only one stage mark falls back to the short form`() {
        val half = Timing.AudioReport(sender = 3, seq = 9, rxNanos = 1L, audioNanos = 2L, normNanos = 5L)
        assertEquals(21, half.encode().size)
        assertNull((Timing.decode(half.encode()) as Timing.AudioReport).normNanos)
    }

    @Test
    fun `a presence is not mistaken for a timing payload, and vice versa`() {
        val presence = Presence(name = "ALPHA").encode()
        assertNull(Timing.decode(presence))
        assertNull(Presence.decode(Timing.Ping(1L).encode()))
    }

    @Test
    fun `a truncated or unknown payload decodes to nothing rather than a guess`() {
        assertNull(Timing.decode(byteArrayOf()))
        assertNull(Timing.decode(byteArrayOf(2)))
        assertNull(Timing.decode(byteArrayOf(2, 9, 0, 0)))
        assertNull(Timing.decode(Timing.Pong(1, 2, 3).encode().copyOf(20)))
        assertNull(Timing.decode(Timing.Ping(1).encode() + byteArrayOf(0)))
    }
}

package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `an audio report names the sender and the sequence it answers`() {
        val report = Timing.AudioReport(sender = 0xFD, seq = 0xBEEF, rxNanos = 7L, audioNanos = 900_000_007L)
        val bytes = report.encode()
        assertEquals(21, bytes.size)
        assertEquals(report, Timing.decode(bytes))
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

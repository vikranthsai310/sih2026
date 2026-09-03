package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private val digest = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    private fun sample(
        epoch: Long = 0x01020304,
        state: Heartbeat.State = Heartbeat.State.READY,
        battery: Int = 88,
    ) = Heartbeat(
        epoch = epoch,
        profileDigest = digest,
        batteryPercent = battery,
        linkQuality = 200,
        state = state,
        language = Language.HINDI,
    )

    @Test
    fun `the payload is twelve bytes and the frame twenty-four`() {
        assertEquals(12, Heartbeat.SIZE)
        assertEquals(12, sample().encode().size)
        assertEquals(24, Heartbeat.FRAME_SIZE)
    }

    @Test
    fun `decode of encode returns the original`() {
        for (state in Heartbeat.State.entries) {
            for (language in Language.entries) {
                val h = sample(state = state).copy(language = language)
                assertEquals(h, Heartbeat.decode(h.encode()))
            }
        }
    }

    @Test
    fun `epoch survives the full 32-bit range`() {
        for (epoch in listOf(0L, 1L, 0xFFFFL, 0x7FFFFFFFL, 0xFFFFFFFFL)) {
            assertEquals(epoch, Heartbeat.decode(sample(epoch = epoch).encode())!!.epoch)
        }
    }

    /**
     * The two values that make other safety properties work: the epoch keeps the
     * derived nonce unique across restarts (S-07), and the digest catches two handsets
     * meaning different sentences by the same template byte (S-06).
     */
    @Test
    fun `the payload carries the epoch and the template digest`() {
        val decoded = Heartbeat.decode(sample().encode())!!
        assertEquals(0x01020304L, decoded.epoch)
        assertTrue(decoded.profileDigest.contentEquals(digest))
    }

    @Test
    fun `an unknown battery level is representable`() {
        val h = sample(battery = Heartbeat.BATTERY_UNKNOWN)
        assertEquals(Heartbeat.BATTERY_UNKNOWN, Heartbeat.decode(h.encode())!!.batteryPercent)
    }

    @Test
    fun `a wrong-sized payload decodes to null rather than throwing`() {
        assertNull(Heartbeat.decode(ByteArray(11)))
        assertNull(Heartbeat.decode(ByteArray(13)))
        assertNull(Heartbeat.decode(ByteArray(0)))
    }

    @Test
    fun `a reserved state or language decodes to null`() {
        val wire = sample().encode()
        wire[10] = 7 // no such state
        assertNull(Heartbeat.decode(wire))

        val wire2 = sample().encode()
        wire2[11] = 12 // no such language
        assertNull(Heartbeat.decode(wire2))
    }

    @Test
    fun `an impossible battery value decodes to null`() {
        val wire = sample().encode()
        wire[8] = 150.toByte()
        assertNull(Heartbeat.decode(wire))
    }

    @Test
    fun `an out-of-range battery is rejected at construction`() {
        try {
            sample(battery = 101)
            throw AssertionError("101 % battery should have been rejected")
        } catch (expected: IllegalArgumentException) {
            // as specified
        }
    }

    @Test
    fun `a wrong-sized digest is rejected at construction`() {
        try {
            Heartbeat(1, ByteArray(3), 50, 100, Heartbeat.State.READY, Language.HINDI)
            throw AssertionError("a three-byte digest should have been rejected")
        } catch (expected: IllegalArgumentException) {
            // as specified
        }
    }

    @Test
    fun `it round trips inside a real frame`() {
        val h = sample()
        val frame =
            Frame(
                type = MessageType.HEARTBEAT,
                language = Language.HINDI,
                seq = 42,
                flags = Flags.FINAL,
                src = 2,
                keyId = 7,
                ttl = 0,
                payload = h.encode(),
            )
        assertEquals(Heartbeat.FRAME_SIZE, frame.wireSize)

        val decoded = Frame.decode(frame.encode()).orNull()
        assertNotNull(decoded)
        assertEquals(h, Heartbeat.decode(decoded!!.payload))
    }

    @Test
    fun `heartbeats are never relayed`() {
        assertTrue(!MessageType.HEARTBEAT.relayable)
    }

    @Test
    fun `equality and hashing account for the digest contents`() {
        val a = sample()
        val b = sample().copy(profileDigest = digest.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val different = sample().copy(profileDigest = byteArrayOf(1, 2, 3, 4))
        assertTrue(a != different)
    }
}

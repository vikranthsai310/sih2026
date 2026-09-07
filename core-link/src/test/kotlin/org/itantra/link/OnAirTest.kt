package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The air policy behind [BleBroadcastLink], proved without a radio.
 *
 * Every test is a story about chances: a frame that leaves the air before a scanner has
 * had enough of them is a message lost, and this is where that is made impossible by
 * construction rather than by hoping the controller is quick.
 */
class OnAirTest {
    private fun frame(
        type: MessageType,
        seq: Int,
        payloadBytes: Int,
        encrypted: Boolean = true,
        src: Int = 7,
    ): ByteArray =
        Frame(
            type = type,
            language = Language.HINDI,
            seq = seq,
            flags = Flags.FINAL or (if (encrypted) Flags.ENCRYPTED else 0),
            src = src,
            keyId = 0x42,
            ttl = 3,
            payload = ByteArray(payloadBytes) { (it + seq).toByte() },
        ).encode()

    private fun hello(epoch: Int = 1): ByteArray =
        frame(MessageType.HEARTBEAT, 0, 4, encrypted = false).also { it[10] = epoch.toByte() }

    private fun presence(seq: Int) = frame(MessageType.HEARTBEAT, seq, 30)

    private fun message(
        seq: Int,
        bytes: Int = 48,
        src: Int = 7,
    ) = frame(MessageType.TEXT, seq, bytes, src = src)

    private fun alert(seq: Int) = frame(MessageType.ALERT, seq, 1)

    private fun air(
        soft: Int = 200,
        hard: Int = 1_600,
        min: Long = 5_000,
        max: Long = 30_000,
        waiting: Int = 64,
    ) = OnAir(soft, hard, min, max, waiting)

    @Test
    fun `a message stays on the air for its minimum time even when newer ones are waiting`() {
        val air = air(soft = 100)
        assertTrue(air.offer(message(1, 60), 0))
        assertEquals(listOf(1), seqs(air.contents(0)))

        // Two more, neither of which fits beside the first.
        air.offer(message(2, 60), 100)
        air.offer(message(3, 60), 200)
        assertEquals("the first has not had its turn", listOf(1), seqs(air.contents(1_000)))
        assertEquals(listOf(1), seqs(air.contents(4_999)))

        assertEquals("at five seconds it yields to the next", listOf(2), seqs(air.contents(5_000)))
        assertEquals(listOf(2), seqs(air.contents(9_999)))
        assertEquals(listOf(3), seqs(air.contents(10_000)))
    }

    @Test
    fun `a message stays up to the maximum when nothing needs the room`() {
        val air = air()
        air.offer(message(1), 0)
        assertEquals("admitted, and timed from then", listOf(1), seqs(air.contents(0)))
        assertEquals(listOf(1), seqs(air.contents(29_999)))
        assertEquals(emptyList<Int>(), seqs(air.contents(30_000)))
    }

    @Test
    fun `frames that fit together travel together, oldest first`() {
        val air = air(soft = 200)
        air.offer(message(1, 40), 0)
        air.offer(message(2, 40), 10)
        air.offer(message(3, 40), 20)
        assertEquals(listOf(1, 2, 3), seqs(air.contents(20)))
    }

    @Test
    fun `hello and presence are pinned at the head and only the latest of each is kept`() {
        val air = air()
        air.offer(message(5), 0)
        air.offer(hello(1), 100)
        air.offer(presence(1), 200)
        air.offer(hello(2), 300)
        air.offer(presence(2), 400)

        val onAir = air.contents(400)
        assertEquals(3, onAir.size)
        assertArrayEquals("the newest hello leads", hello(2), onAir[0])
        assertArrayEquals(presence(2), onAir[1])
        assertEquals(5, seqOf(onAir[2]))
    }

    @Test
    fun `pins count against the budget but never block a message for ever`() {
        val air = air(soft = 100)
        air.offer(hello(), 0) // 16 B
        air.offer(presence(1), 0) // 42 B
        air.offer(message(1, 60), 0) // 72 B: does not fit beside 58 B of pins
        val onAir = air.contents(0)
        assertEquals("alone with the pins, over the soft budget, rather than never", 3, onAir.size)
        assertEquals(1, seqOf(onAir[2]))
    }

    @Test
    fun `a frame no advertisement can hold is refused and counted`() {
        val air = air(soft = 100, hard = 120)
        assertFalse(air.offer(message(1, 200), 0))
        assertEquals(1, air.droppedOversize)
        assertEquals(emptyList<Int>(), seqs(air.contents(0)))
    }

    @Test
    fun `an alert jumps the queue`() {
        val air = air(soft = 100)
        air.offer(message(1, 80), 0) // 92 B: fills the packet
        assertEquals(listOf(1), seqs(air.contents(0)))
        air.offer(message(2, 60), 0) // waits
        air.offer(alert(3), 0) // 13 B: waits, at the front
        assertEquals(listOf(1), seqs(air.contents(1_000)))
        assertEquals("the alert goes before message 2", listOf(3, 2), seqs(air.contents(5_000)))
    }

    @Test
    fun `the waiting queue is bounded and the oldest message is the one dropped`() {
        val air = air(soft = 100, waiting = 2)
        air.offer(message(1, 60), 0)
        air.contents(0)
        assertTrue(air.offer(message(2, 60), 0))
        assertTrue(air.offer(message(3, 60), 0))
        assertFalse(air.offer(message(4, 60), 0))
        assertEquals(1L, air.droppedWaiting)
        assertEquals(2, air.waitingCount)
        assertEquals("2 was dropped, 3 and 4 wait", listOf(3), seqs(air.contents(5_000)))
        assertEquals(listOf(4), seqs(air.contents(10_000)))
    }

    @Test
    fun `a full waiting queue never drops an alert to make room`() {
        val air = air(soft = 100, waiting = 2)
        air.offer(message(1, 80), 0) // 92 B: fills the packet
        air.contents(0)
        air.offer(alert(9), 0)
        air.offer(message(2, 60), 0)
        assertFalse(air.offer(message(3, 60), 0))
        assertEquals("the alert survives; message 2 went", listOf(9, 3), seqs(air.contents(5_000)))
    }

    @Test
    fun `the same instant asked twice answers the same, so the radio is left alone`() {
        val air = air()
        air.offer(hello(), 0)
        air.offer(message(1), 0)
        val first = air.contents(100)
        val second = air.contents(100)
        assertEquals(first.size, second.size)
        first.indices.forEach { assertArrayEquals(first[it], second[it]) }
    }

    @Test
    fun `next change is the earliest retirement, or the oldest frame's minimum while something waits`() {
        val air = air(soft = 100)
        assertNull(air.nextChangeMillis(0))
        air.offer(message(1, 60), 1_000)
        air.contents(1_000)
        assertEquals("nothing waiting: the maximum stay", 31_000L, air.nextChangeMillis(1_000))
        air.offer(message(2, 60), 2_000)
        assertEquals("something waiting: the minimum stay", 6_000L, air.nextChangeMillis(2_000))
        assertEquals("never in the past", 7_000L, air.nextChangeMillis(7_000))
    }

    @Test
    fun `a blob round-trips several frames and names the advertiser`() {
        val frames = listOf(hello(), presence(3), message(4, 70))
        val blob = AirBlob.encode(src = 9, keyId = 0x42, frames = frames)
        assertEquals(AirBlob.HEADER_BYTES + frames.sumOf { it.size }, blob.size)

        val parsed = AirBlob.decode(blob)
        assertEquals(9, parsed.src)
        assertEquals(0x42, parsed.keyId)
        assertEquals(3, parsed.frames.size)
        frames.indices.forEach { assertArrayEquals(frames[it], parsed.frames[it]) }
    }

    @Test
    fun `a bare frame from the previous version still reads, with its own sender`() {
        val bare = message(2, src = 11)
        val parsed = AirBlob.decode(bare)
        assertEquals(11, parsed.src)
        assertEquals(0x42, parsed.keyId)
        assertEquals(1, parsed.frames.size)
        assertArrayEquals(bare, parsed.frames[0])
    }

    @Test
    fun `a truncated tail is dropped and everything before it is kept`() {
        val frames = listOf(message(1, 30), message(2, 30))
        val blob = AirBlob.encode(src = 1, keyId = 2, frames = frames)
        val cut = blob.copyOf(blob.size - 5)
        val parsed = AirBlob.decode(cut)
        assertEquals(1, parsed.frames.size)
        assertArrayEquals(frames[0], parsed.frames[0])
    }

    @Test
    fun `garbage after the header yields no frames and does not throw`() {
        val junk = byteArrayOf(AirBlob.MAGIC.toByte(), 1, 2) + ByteArray(12) { 0x55 }
        assertTrue(AirBlob.decode(junk).frames.isEmpty())
        assertTrue(AirBlob.decode(ByteArray(0)).frames.isEmpty())
        assertTrue(AirBlob.decode(byteArrayOf(AirBlob.MAGIC.toByte())).frames.isEmpty())
    }

    @Test
    fun `an unknown advertiser yields no source`() {
        val blob = AirBlob.encode(src = AirBlob.UNKNOWN, keyId = AirBlob.UNKNOWN, frames = listOf(message(1)))
        val parsed = AirBlob.decode(blob)
        assertNull(parsed.src)
        assertNull(parsed.keyId)
        assertEquals(1, parsed.frames.size)
    }

    private fun seqOf(frame: ByteArray): Int = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    private fun seqs(frames: List<ByteArray>): List<Int> = frames.map(::seqOf)
}

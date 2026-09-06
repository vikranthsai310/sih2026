package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock seed. A reinstall wipes the persisted counter, and a counter back at zero
 * both reuses nonces and is refused by every peer as a replay. Seeded from the clock, an
 * epoch never goes backwards across a reinstall.
 */
class EpochSeedTest {
    private class Store(var value: Long? = null) : EpochCounter.Store {
        override fun read(): Long? = value

        override fun write(epoch: Long) {
            value = epoch
        }
    }

    private val tenDays = EpochCounter.ORIGIN_MILLIS + 10L * 24 * 60 * 60 * 1000
    private val tenDaysInMinutes = 10L * 24 * 60

    @Test
    fun `a fresh install starts at the clock's minute, not at zero`() {
        val counter = EpochCounter(Store(null), clock = { tenDays })
        assertEquals(tenDaysInMinutes, counter.start())
    }

    @Test
    fun `a restart in the same minute advances the counter past the seed`() {
        val store = Store(null)
        assertEquals(tenDaysInMinutes, EpochCounter(store, clock = { tenDays }).start())
        assertEquals(tenDaysInMinutes + 1, EpochCounter(store, clock = { tenDays }).start())
        assertEquals(tenDaysInMinutes + 2, EpochCounter(store, clock = { tenDays }).start())
    }

    @Test
    fun `a reinstall later never reuses an epoch from before it`() {
        val before = EpochCounter(Store(null), clock = { tenDays }).start()
        val after = EpochCounter(Store(null), clock = { tenDays + 90_000 }).start()
        assertTrue("$after must be past $before", after > before)
    }

    @Test
    fun `a persisted counter ahead of the clock wins`() {
        val counter = EpochCounter(Store(tenDaysInMinutes + 500), clock = { tenDays })
        assertEquals(tenDaysInMinutes + 501, counter.start())
    }

    @Test
    fun `a clock before the origin or absurdly late is ignored`() {
        assertEquals(EpochCounter.INITIAL, EpochCounter(Store(null), clock = { 0L }).start())
        assertEquals(EpochCounter.INITIAL, EpochCounter(Store(null), clock = { Long.MAX_VALUE / 2 }).start())
        assertEquals(4L, EpochCounter(Store(3), clock = { 0L }).start())
    }

    @Test
    fun `without a clock the counter is exactly what it was`() {
        assertEquals(EpochCounter.INITIAL, EpochCounter(Store(null)).start())
        assertEquals(8L, EpochCounter(Store(7)).start())
    }
}

class StreamFramerOverflowTest {
    private fun frame(seq: Int) =
        Frame(
            type = MessageType.TEXT,
            language = Language.HINDI,
            seq = seq,
            flags = Flags.FINAL,
            src = 1,
            keyId = 9,
            ttl = 3,
            payload = ByteArray(20) { it.toByte() },
        )

    @Test
    fun `a flood of garbage larger than the buffer does not stop the next frame being read`() {
        val framer = StreamFramer(maxBuffered = 256)
        val garbage = ByteArray(1_000) { 0x5A }
        assertTrue(framer.offer(garbage).isEmpty())
        assertEquals(listOf(frame(1)), framer.offer(frame(1).encode()))
    }

    @Test
    fun `a frame split across an overflowing chunk boundary still arrives`() {
        val framer = StreamFramer(maxBuffered = 64)
        val wire = frame(2).encode()
        // 40 bytes of noise, then the frame in two halves: the buffer must keep the
        // newest bytes, not the oldest.
        framer.offer(ByteArray(40) { 0x11 })
        framer.offer(wire.copyOfRange(0, 10))
        val out = framer.offer(wire.copyOfRange(10, wire.size))
        assertEquals(listOf(frame(2)), out)
    }
}

package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The epoch counter, task W6.10, risk S-07.
 *
 * Nonce uniqueness rests entirely on this. Reuse under a fixed key does not weaken
 * AES-GCM, it destroys it — two messages under one nonce leak their XOR and the
 * authentication key becomes recoverable. So the tests here are about the ordering of
 * persistence and use, including the case where the process dies between them.
 */
class EpochCounterTest {
    /** Records the order of reads and writes, and can be made to die mid-write. */
    private class FakeStore(var persisted: Long? = null) : EpochCounter.Store {
        val writes = ArrayList<Long>()
        var failNextWrite = false

        override fun read(): Long? = persisted

        override fun write(epoch: Long) {
            if (failNextWrite) throw java.io.IOException("storage full")
            writes += epoch
            persisted = epoch
        }
    }

    // ── advancing ────────────────────────────────────────────────────────────

    @Test
    fun `a device that has never run starts at the initial epoch`() {
        val store = FakeStore(persisted = null)
        assertEquals(EpochCounter.INITIAL, EpochCounter(store).start())
    }

    @Test
    fun `every start advances past the last persisted epoch`() {
        val store = FakeStore(persisted = 41)
        assertEquals(42L, EpochCounter(store).start())
    }

    /**
     * A process that restarts and resumes from `SEQ = 0` under the same epoch reuses
     * every nonce it has already used. This is the whole point of the class.
     */
    @Test
    fun `restarting never reuses an epoch`() {
        val store = FakeStore()
        val seen = mutableSetOf<Long>()

        repeat(50) {
            val epoch = EpochCounter(store).start()
            assertTrue("epoch $epoch was reused after a restart", seen.add(epoch))
        }
        assertEquals(50, seen.size)
    }

    @Test
    fun `a sequence wrap advances the epoch`() {
        val counter = EpochCounter(FakeStore(persisted = 10))
        assertEquals(11L, counter.start())
        assertEquals(12L, counter.onSequenceWrap())
        assertEquals(12L, counter.epoch)
    }

    @Test
    fun `the wrap threshold is the sixteen-bit sequence limit`() {
        val counter = EpochCounter(FakeStore())
        counter.start()
        assertFalse(counter.willWrap(0xFFFF))
        assertTrue(counter.willWrap(0x10000))
    }

    // ── persist before use ───────────────────────────────────────────────────

    /**
     * Writing after use leaves a window in which the process dies having transmitted
     * under an epoch that was never recorded — and the next start reuses it. Being one
     * epoch ahead after a crash costs nothing; being one behind loses confidentiality.
     */
    @Test
    fun `the new epoch is persisted before it is handed out`() {
        val store = FakeStore(persisted = 7)
        val counter = EpochCounter(store)
        val issued = counter.start()

        assertEquals("the write must have happened", listOf(8L), store.writes)
        assertEquals(8L, issued)
        assertEquals(8L, store.persisted)
    }

    @Test
    fun `a failed write means no epoch is issued at all`() {
        val store = FakeStore(persisted = 7)
        store.failNextWrite = true
        val counter = EpochCounter(store)

        try {
            counter.start()
            throw AssertionError("expected the failure to propagate")
        } catch (expected: java.io.IOException) {
            // Right: transmitting under an unrecorded epoch is worse than not starting.
        }
        assertEquals("nothing may have been persisted", 7L, store.persisted)
    }

    /**
     * The crash this ordering defends against: the process dies immediately after the
     * epoch is persisted but before a single frame is sent. The next start must still
     * move forward, wasting an epoch rather than reusing one.
     */
    @Test
    fun `a crash straight after persisting wastes an epoch rather than reusing it`() {
        val store = FakeStore(persisted = 4)

        val first = EpochCounter(store).start() // then the process dies
        val second = EpochCounter(store).start()

        assertEquals(5L, first)
        assertEquals("the epoch must not be handed out twice", 6L, second)
    }

    @Test
    fun `the epoch is unavailable before start is called`() {
        val counter = EpochCounter(FakeStore())
        try {
            counter.epoch
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("start()"))
        }
    }

    @Test
    fun `a wrap before start is refused`() {
        try {
            EpochCounter(FakeStore()).onSequenceWrap()
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalStateException) {
            // Nothing to advance from.
        }
    }

    // ── the end of the epoch space ───────────────────────────────────────────

    /**
     * At 2^32 epochs the counter cannot advance without reuse. It refuses rather than
     * wrapping, because wrapping here is silent nonce reuse.
     */
    @Test
    fun `exhausting the epoch space refuses rather than wrapping`() {
        val store = FakeStore(persisted = EpochCounter.MAX_EPOCH)
        try {
            EpochCounter(store).start()
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("rotated"))
        }
    }

    @Test
    fun `a wrap that would exhaust the epoch space is refused`() {
        val store = FakeStore(persisted = EpochCounter.MAX_EPOCH - 1)
        val counter = EpochCounter(store)
        counter.start()
        try {
            counter.onSequenceWrap()
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("rotated"))
        }
    }

    @Test
    fun `the epoch fits the thirty-two bit wire field`() {
        assertEquals(0xFFFFFFFFL, EpochCounter.MAX_EPOCH)
    }

    // ── the nonces it produces really are distinct ───────────────────────────

    /**
     * The property the whole class exists for, checked against the nonce derivation
     * itself: across a restart and a sequence wrap, no nonce repeats.
     */
    @Test
    fun `no nonce repeats across restarts and wraps`() {
        val store = FakeStore()
        val src = 3
        val nonces = HashSet<String>()

        repeat(4) {
            val counter = EpochCounter(store)
            counter.start()
            // A handful of sequence numbers per run, then a wrap.
            for (seq in 0..3) {
                val nonce = Aead.nonce(counter.epoch, src, seq).joinToString(",")
                assertTrue("nonce repeated: $nonce", nonces.add(nonce))
            }
            counter.onSequenceWrap()
            for (seq in 0..3) {
                val nonce = Aead.nonce(counter.epoch, src, seq).joinToString(",")
                assertTrue("nonce repeated after a wrap: $nonce", nonces.add(nonce))
            }
        }
        assertEquals(32, nonces.size)
    }
}

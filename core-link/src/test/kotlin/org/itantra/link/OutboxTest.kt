package org.itantra.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store-and-forward, task **W2.30**.
 *
 * The interesting behaviour is entirely in the two caps and in which end is dropped when
 * one is reached, so that is what these test.
 */
class OutboxTest {
    private fun frame(n: Int) = ByteArray(4) { n.toByte() }

    @Test
    fun `frames are held and drained in the order they were queued`() {
        val outbox = Outbox()
        for (n in 1..5) outbox.offer(frame(n), nowMillis = n * 1_000L)

        assertEquals(5, outbox.size)
        val drained = outbox.drain(nowMillis = 6_000)
        assertEquals(listOf(1, 2, 3, 4, 5), drained.map { it.wire.first().toInt() })
        assertTrue("draining empties the queue", outbox.isEmpty)
    }

    /**
     * A relief message arriving before the one it answers reads as a different
     * conversation, so order is part of the contract rather than an implementation detail.
     */
    @Test
    fun `draining twice does not repeat anything`() {
        val outbox = Outbox()
        outbox.offer(frame(1), 0)
        assertEquals(1, outbox.drain(1_000).size)
        assertEquals(0, outbox.drain(2_000).size)
    }

    // ── the caps ─────────────────────────────────────────────────────────────

    @Test
    fun `the queue is capped at five hundred frames`() {
        val outbox = Outbox()
        for (n in 0 until 600) outbox.offer(frame(n), nowMillis = n.toLong())
        assertEquals(Outbox.MAX_FRAMES, outbox.size)
    }

    /**
     * The **oldest** is dropped, which is the opposite of the reassembler's choice and for
     * the opposite reason: this queue is entirely our own, and the newest message is the
     * one most likely to still be true.
     */
    @Test
    fun `the oldest frame is dropped when the queue is full`() {
        val outbox = Outbox(capacity = 3)
        for (n in 1..5) outbox.offer(frame(n), nowMillis = n * 1_000L)

        assertEquals(listOf(3, 4, 5), outbox.peek().map { it.wire.first().toInt() })
        assertEquals("and the loss is counted, not hidden", 2, outbox.droppedOldest)
    }

    @Test
    fun `a caller is told when a frame had to be dropped to make room`() {
        val outbox = Outbox(capacity = 2)
        assertTrue(outbox.offer(frame(1), 0))
        assertTrue(outbox.offer(frame(2), 0))
        assertFalse("the third displaces the first", outbox.offer(frame(3), 0))
    }

    /**
     * A returning link must not deliver a flood of messages that stopped being true hours
     * ago — "water is rising here", delivered at nightfall about a morning.
     */
    @Test
    fun `frames older than a day are discarded rather than delivered`() {
        val outbox = Outbox()
        outbox.offer(frame(1), nowMillis = 0)
        outbox.offer(frame(2), nowMillis = Outbox.MAX_AGE_MILLIS / 2)

        val drained = outbox.drain(nowMillis = Outbox.MAX_AGE_MILLIS + 1)
        assertEquals("only the newer one survives", listOf(2), drained.map { it.wire.first().toInt() })
        assertEquals(1, outbox.expired)
    }

    @Test
    fun `expiry is checked on write as well as on drain`() {
        val outbox = Outbox()
        outbox.offer(frame(1), nowMillis = 0)
        outbox.offer(frame(2), nowMillis = Outbox.MAX_AGE_MILLIS + 1)
        assertEquals("the stale one goes as the new one arrives", 1, outbox.size)
    }

    @Test
    fun `the twenty-four hour boundary is exactly a day`() {
        val outbox = Outbox()
        outbox.offer(frame(1), nowMillis = 0)
        assertEquals("one millisecond short is kept", 1, outbox.drain(Outbox.MAX_AGE_MILLIS - 1).size)

        outbox.offer(frame(2), nowMillis = 0)
        assertEquals("exactly a day is dropped", 0, outbox.drain(Outbox.MAX_AGE_MILLIS).size)
    }

    // ── the frames themselves ────────────────────────────────────────────────

    /** A caller reusing its buffer must not silently rewrite what is already queued. */
    @Test
    fun `a queued frame is copied rather than referenced`() {
        val outbox = Outbox()
        val buffer = ByteArray(4) { 1 }
        outbox.offer(buffer, 0)
        buffer.fill(9)

        assertEquals(1, outbox.peek().single().wire.first().toInt())
    }

    // ── persistence ──────────────────────────────────────────────────────────

    private class MemoryStore(var saved: List<Outbox.Entry> = emptyList()) : Outbox.Store {
        override fun load(): List<Outbox.Entry> = saved

        override fun persist(entries: List<Outbox.Entry>) {
            saved = entries
        }
    }

    @Test
    fun `a queue survives being rebuilt from its store`() {
        val store = MemoryStore()
        val first = Outbox(store = store)
        first.offer(frame(1), 1_000)
        first.offer(frame(2), 2_000)

        val restored = Outbox(store = store)
        assertEquals(listOf(1, 2), restored.peek().map { it.wire.first().toInt() })
    }

    @Test
    fun `a store holding more than the cap is trimmed on load`() {
        val store = MemoryStore((1..10).map { Outbox.Entry(frame(it), it * 1_000L) })
        val outbox = Outbox(capacity = 4, store = store)

        assertEquals(4, outbox.size)
        assertEquals("the newest four survive", listOf(7, 8, 9, 10), outbox.peek().map { it.wire.first().toInt() })
    }

    @Test
    fun `draining clears the store as well as the queue`() {
        val store = MemoryStore()
        val outbox = Outbox(store = store)
        outbox.offer(frame(1), 0)
        outbox.drain(1_000)

        assertTrue("a drained frame must not come back after a restart", store.saved.isEmpty())
        assertTrue(Outbox(store = store).isEmpty)
    }
}

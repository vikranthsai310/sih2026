package org.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFrameQueueTest {
    @Test
    fun `the capture queue holds two seconds at a twenty millisecond hop`() {
        assertEquals(100, BoundedFrameQueue.CAPTURE_TO_INFERENCE)
        assertEquals(2_000, BoundedFrameQueue.CAPTURE_TO_INFERENCE * 20)
    }

    @Test
    fun `frames come out oldest first`() {
        val q = BoundedFrameQueue<Int>(4)
        listOf(1, 2, 3).forEach { q.offer(it) }
        assertEquals(listOf(1, 2, 3), listOf(q.poll(), q.poll(), q.poll()))
        assertNull(q.poll())
    }

    /**
     * The capture thread must never block. When the recogniser falls behind the
     * oldest frame is discarded and counted, rather than the producer waiting.
     */
    @Test
    fun `offer never blocks and drops the oldest when full`() {
        val q = BoundedFrameQueue<Int>(3)
        assertTrue(q.offer(1))
        assertTrue(q.offer(2))
        assertTrue(q.offer(3))
        assertFalse("a full queue must report the drop", q.offer(4))

        assertEquals(listOf(2, 3, 4), q.drain())
        assertEquals(1L, q.droppedCount)
    }

    @Test
    fun `the dropped counter accumulates and can be reset`() {
        val q = BoundedFrameQueue<Int>(2)
        repeat(10) { q.offer(it) }
        assertEquals(8L, q.droppedCount)
        q.resetDroppedCount()
        assertEquals(0L, q.droppedCount)
    }

    @Test
    fun `a full queue keeps exactly the newest frames`() {
        val q = BoundedFrameQueue<Int>(5)
        repeat(1_000) { q.offer(it) }
        assertEquals(listOf(995, 996, 997, 998, 999), q.drain())
    }

    @Test
    fun `concurrent producers and consumers do not corrupt the queue`() {
        val q = BoundedFrameQueue<Int>(64)
        val producer =
            Thread {
                repeat(20_000) { q.offer(it) }
            }
        val consumed = java.util.concurrent.atomic.AtomicInteger()
        val consumer =
            Thread {
                repeat(20_000) { if (q.poll() != null) consumed.incrementAndGet() }
            }
        producer.start()
        consumer.start()
        producer.join()
        consumer.join()
        // Nothing to assert about exact counts under a race; what matters is that
        // neither thread threw and the invariant held.
        assertTrue(q.size <= 64)
    }
}

class EngineStateTest {
    @Test
    fun `transmit is disabled until models are loaded`() {
        assertFalse(
            "the cold-start model load must never be paid on a key press (T-11)",
            EngineState.Initialising.canTransmit,
        )
        assertTrue(EngineState.Ready.canTransmit)
        assertTrue(EngineState.Listening.canTransmit)
    }

    @Test
    fun `transmit is disabled while degraded`() {
        val degraded = EngineState.Degraded(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
        assertFalse(degraded.canTransmit)
        assertTrue(degraded.isDegraded)
    }

    @Test
    fun `every degraded reason carries a message for the operator`() {
        for (reason in EngineState.Degraded.Reason.entries) {
            assertTrue(
                "${reason.name} has no message; a silent failure is worse than a stated one",
                reason.message.isNotBlank(),
            )
        }
    }

    @Test
    fun `the normal transmit path is legal end to end`() {
        val path =
            listOf(
                EngineState.Initialising,
                EngineState.Ready,
                EngineState.Listening,
                EngineState.Recognising,
                EngineState.Transmitting,
                EngineState.Ready,
            )
        for (i in 0 until path.size - 1) {
            assertTrue(
                "${path[i]} -> ${path[i + 1]} should be legal",
                EngineTransitions.isLegal(path[i], path[i + 1]),
            )
        }
    }

    @Test
    fun `the receive path is legal end to end`() {
        assertTrue(EngineTransitions.isLegal(EngineState.Ready, EngineState.Receiving))
        assertTrue(EngineTransitions.isLegal(EngineState.Receiving, EngineState.Speaking))
        assertTrue(EngineTransitions.isLegal(EngineState.Speaking, EngineState.Ready))
    }

    @Test
    fun `any state may degrade, and degraded recovers only to ready`() {
        val degraded = EngineState.Degraded(EngineState.Degraded.Reason.LINK_DOWN)
        for (state in listOf(
            EngineState.Initialising,
            EngineState.Ready,
            EngineState.Listening,
            EngineState.Recognising,
            EngineState.Transmitting,
            EngineState.Receiving,
            EngineState.Speaking,
        )) {
            assertTrue("$state should be able to degrade", EngineTransitions.isLegal(state, degraded))
        }
        assertTrue(EngineTransitions.isLegal(degraded, EngineState.Ready))
        assertFalse(
            "recovery must go through Ready, never straight back into work",
            EngineTransitions.isLegal(degraded, EngineState.Transmitting),
        )
    }

    @Test
    fun `illegal shortcuts are rejected`() {
        assertFalse(EngineTransitions.isLegal(EngineState.Initialising, EngineState.Transmitting))
        assertFalse(EngineTransitions.isLegal(EngineState.Ready, EngineState.Speaking))
        assertFalse(EngineTransitions.isLegal(EngineState.Transmitting, EngineState.Speaking))
    }
}

package org.itantra.link

import org.itantra.proto.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alert pre-emption, acknowledgement and retry — tasks W5.15 and W5.16. */
class AlertDeliveryTest {
    private fun delivery() = AlertDelivery()

    private fun text(seq: Int) = AlertDelivery.Pending(seq, MessageType.TEXT, ByteArray(44), queuedAtMillis = 0)

    private fun alert(seq: Int) = AlertDelivery.Pending(seq, MessageType.ALERT, ByteArray(21), queuedAtMillis = 0)

    // ── W5.15: an alert overtakes everything ─────────────────────────────────

    @Test
    fun `an alert jumps ahead of queued text`() {
        val d = delivery()
        d.enqueue(text(1))
        d.enqueue(text(2))
        d.enqueue(alert(3))

        assertEquals("the alert must go first", 3, d.dequeue(0, peerCount = 6)!!.seq)
        assertEquals(1, d.dequeue(0, 6)!!.seq)
        assertEquals(2, d.dequeue(0, 6)!!.seq)
    }

    /** Two alerts stay in the order they were spoken; only text is overtaken. */
    @Test
    fun `an alert does not overtake another alert`() {
        val d = delivery()
        d.enqueue(alert(1))
        d.enqueue(text(2))
        d.enqueue(alert(3))

        assertEquals(1, d.dequeue(0, 6)!!.seq)
        assertEquals(3, d.dequeue(0, 6)!!.seq)
        assertEquals(2, d.dequeue(0, 6)!!.seq)
    }

    @Test
    fun `text keeps its order among itself`() {
        val d = delivery()
        listOf(1, 2, 3).forEach { d.enqueue(text(it)) }
        assertEquals(listOf(1, 2, 3), (1..3).map { d.dequeue(0, 6)!!.seq })
    }

    @Test
    fun `an empty queue yields nothing`() {
        assertNull(delivery().dequeue(0, 6))
    }

    // ── W5.16: delivered on the first ack, but the count keeps rising ────────

    /**
     * One unit hearing an evacuation order is the difference between the message
     * working and not working. Retrying after that would put a duplicate alert on a
     * channel now carrying the reply to the first.
     */
    @Test
    fun `the alert is delivered on the first acknowledgement`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        val progress = d.onAck(seq = 3, src = 11, nowMillis = 100)!!
        assertTrue(progress.isDelivered)
        assertEquals("1 of 6 units", progress.display())
        assertTrue("retrying must stop on the first ack", d.dueForRetry(10_000).isEmpty())
    }

    @Test
    fun `the count keeps rising after delivery, for the display`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        d.onAck(3, src = 11, nowMillis = 100)
        d.onAck(3, src = 12, nowMillis = 150)
        val progress = d.onAck(3, src = 13, nowMillis = 200)!!

        assertEquals("3 of 6 units", progress.display())
        assertTrue(progress.isDelivered)
        assertFalse("not everyone has answered yet", progress.isComplete)
    }

    @Test
    fun `the same unit acknowledging twice is counted once`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        d.onAck(3, src = 11, nowMillis = 100)
        val progress = d.onAck(3, src = 11, nowMillis = 400)!!
        assertEquals("1 of 6 units", progress.display())
    }

    @Test
    fun `every peer answering completes the alert`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 3)

        d.onAck(3, 11, 10)
        d.onAck(3, 12, 20)
        val progress = d.onAck(3, 13, 30)!!
        assertEquals("3 of 3 units", progress.display())
        assertTrue(progress.isComplete)
    }

    @Test
    fun `an acknowledgement for an unknown alert is ignored`() {
        val d = delivery()
        assertNull("a late or duplicate ack must not be counted", d.onAck(99, 11, 0))
    }

    @Test
    fun `text is not tracked for acknowledgement`() {
        val d = delivery()
        d.enqueue(text(1))
        d.dequeue(0, 6)
        assertNull(d.progressOf(1))
    }

    // ── retry: three attempts at 300 ms ──────────────────────────────────────

    @Test
    fun `an unacknowledged alert is retried at the stated interval`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        assertTrue("too early", d.dueForRetry(299).isEmpty())
        assertEquals(listOf(3), d.dueForRetry(300))
        assertEquals("attempt counted", 2, d.progressOf(3)!!.attempts)
    }

    @Test
    fun `it gives up after three attempts and says so`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        assertEquals(listOf(3), d.dueForRetry(300))
        assertEquals(listOf(3), d.dueForRetry(600))
        assertEquals("a fourth attempt must not happen", emptyList<Int>(), d.dueForRetry(900))

        val progress = d.progressOf(3)!!
        assertEquals(AlertDelivery.MAX_ATTEMPTS, progress.attempts)
        assertTrue(progress.givenUp)
        assertFalse(progress.isDelivered)
    }

    /**
     * An alert nobody heard must reach the operator as a visible failure. A silent one
     * is the worst outcome this interface can produce — the sender believes the warning
     * went out.
     */
    @Test
    fun `an alert nobody answered is reported as undelivered`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)
        repeat(3) { d.dueForRetry(300L * (it + 1)) }

        val failed = d.undelivered()
        assertEquals(1, failed.size)
        assertEquals(3, failed.first().seq)
    }

    @Test
    fun `an alert that was answered is never reported as undelivered`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)
        d.onAck(3, 11, 100)
        repeat(3) { d.dueForRetry(300L * (it + 1)) }

        assertTrue(d.undelivered().isEmpty())
    }

    @Test
    fun `asking twice at the same instant does not double-count an attempt`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, peerCount = 6)

        d.dueForRetry(300)
        assertTrue("the retry is already scheduled forward", d.dueForRetry(300).isEmpty())
        assertEquals(2, d.progressOf(3)!!.attempts)
    }

    @Test
    fun `the retry settings are the ones the task names`() {
        assertEquals(300L, AlertDelivery.RETRY_INTERVAL_MILLIS)
        assertEquals(3, AlertDelivery.MAX_ATTEMPTS)
    }

    @Test
    fun `forgetting an alert stops it being tracked`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.dequeue(0, 6)
        d.forget(3)
        assertNull(d.progressOf(3))
        assertTrue(d.dueForRetry(10_000).isEmpty())
    }

    @Test
    fun `clear empties the queue and the tracking table`() {
        val d = delivery()
        d.enqueue(alert(3))
        d.enqueue(text(1))
        d.dequeue(0, 6)
        d.clear()

        assertEquals(0, d.queueDepth)
        assertNull(d.progressOf(3))
    }
}

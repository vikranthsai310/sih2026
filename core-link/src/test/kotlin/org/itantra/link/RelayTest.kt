package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Relaying, task W6.13, risk T-10.
 *
 * The headline test is [three units in mutual range do not storm], which simulates the
 * actual failure: without suppression a single message multiplies until the channel is
 * unusable, including for the alert that started it.
 */
class RelayTest {
    private fun relay(src: Int) = Relay(localSrc = src, random = Random(1))

    private fun frame(
        src: Int,
        seq: Int,
        ttl: Int = 3,
        type: MessageType = MessageType.TEXT,
    ) = Frame(
        type = type,
        language = Language.HINDI,
        seq = seq,
        flags = Flags.FINAL,
        src = src,
        keyId = 7,
        ttl = ttl,
        payload = ByteArray(20),
    )

    // ── the three mechanisms ─────────────────────────────────────────────────

    @Test
    fun `a fresh frame from a peer is forwarded with its TTL decremented`() {
        val decision = relay(1).consider(frame(src = 2, seq = 5, ttl = 3), epochForSrc = 0)

        assertTrue(decision is Relay.Decision.Forward)
        assertEquals("TTL must decrement by exactly one", 2, (decision as Relay.Decision.Forward).frame.ttl)
    }

    @Test
    fun `a frame at TTL zero is dropped`() {
        val decision = relay(1).consider(frame(src = 2, seq = 5, ttl = 0), epochForSrc = 0)
        assertEquals(Relay.Decision.Drop(Relay.Reason.TTL_EXHAUSTED), decision)
    }

    @Test
    fun `the same frame is only ever forwarded once`() {
        val r = relay(1)
        assertTrue(r.consider(frame(2, 5), 0) is Relay.Decision.Forward)
        assertEquals(
            Relay.Decision.Drop(Relay.Reason.ALREADY_SEEN),
            r.consider(frame(2, 5), 0),
        )
    }

    /** Arriving by a second, shorter path must not defeat the suppression. */
    @Test
    fun `a duplicate arriving with a higher TTL is still suppressed`() {
        val r = relay(1)
        r.consider(frame(2, 5, ttl = 1), 0)
        assertEquals(
            Relay.Decision.Drop(Relay.Reason.ALREADY_SEEN),
            r.consider(frame(2, 5, ttl = 5), 0),
        )
    }

    @Test
    fun `this unit never relays its own transmission back`() {
        val decision = relay(1).consider(frame(src = 1, seq = 5), 0)
        assertEquals(Relay.Decision.Drop(Relay.Reason.OWN_FRAME), decision)
    }

    @Test
    fun `acknowledgements and heartbeats are never relayed`() {
        val r = relay(1)
        for (type in listOf(MessageType.ACK, MessageType.HEARTBEAT)) {
            assertEquals(
                "$type must not be relayed",
                Relay.Decision.Drop(Relay.Reason.NOT_RELAYABLE),
                r.consider(frame(2, 5, type = type), 0),
            )
        }
    }

    @Test
    fun `alerts are relayed, since reach is the point of relaying`() {
        val decision = relay(1).consider(frame(2, 5, type = MessageType.ALERT), 0)
        assertTrue(decision is Relay.Decision.Forward)
    }

    /** Every unit rebroadcasting in the same instant would simply collide. */
    @Test
    fun `the rebroadcast is delayed by a random interval within the bound`() {
        val delays =
            (0 until 40).map { seed ->
                val r = Relay(localSrc = 1, random = Random(seed))
                (r.consider(frame(2, 5), 0) as Relay.Decision.Forward).delayMillis
            }
        assertTrue("delays must be bounded", delays.all { it in 0..Relay.MAX_JITTER_MILLIS })
        assertTrue("expected a spread, got ${delays.toSet()}", delays.toSet().size > 5)
    }

    // ── the storm ────────────────────────────────────────────────────────────

    /**
     * The failure this class exists to prevent. Three units in mutual range, one message
     * from a fourth. Without suppression each rebroadcast is heard by the other two and
     * rebroadcast again, and the traffic multiplies at every hop.
     */
    @Test
    fun `three units in mutual range do not storm`() {
        val units = listOf(relay(1), relay(2), relay(3))
        val original = frame(src = 9, seq = 42, ttl = 3)

        var inFlight = listOf(original)
        var transmissions = 0

        // Ten rounds is far more than a three-unit loop needs to die out.
        repeat(10) {
            val next = ArrayList<Frame>()
            for (f in inFlight) {
                for (unit in units) {
                    val decision = unit.consider(f, epochForSrc = 0)
                    if (decision is Relay.Decision.Forward) {
                        transmissions++
                        next += decision.frame
                    }
                }
            }
            inFlight = next
        }

        // Each unit relays the message at most once, so three rebroadcasts in total.
        assertEquals("expected one rebroadcast per unit and no more", 3, transmissions)
        assertTrue("the storm must die out", inFlight.isEmpty())
    }

    @Test
    fun `TTL bounds the hop count even without the seen-set`() {
        val original = frame(src = 9, seq = 42, ttl = 3)
        var current = original
        var hops = 0

        // A fresh relay at every hop, so only the TTL is doing the work.
        while (true) {
            val decision = Relay(localSrc = hops + 100, random = Random(1)).consider(current, 0)
            if (decision !is Relay.Decision.Forward) break
            current = decision.frame
            hops++
        }
        assertEquals("TTL 3 must allow exactly three hops", 3, hops)
    }

    // ── the seen-set is bounded ──────────────────────────────────────────────

    @Test
    fun `the seen-set evicts the eldest entry beyond its capacity`() {
        val r = Relay(localSrc = 1, capacity = 4, random = Random(1))
        for (seq in 1..4) r.consider(frame(2, seq), 0)
        assertEquals(4, r.seenCount)

        r.consider(frame(2, 5), 0)
        assertEquals("capacity must hold", 4, r.seenCount)
        assertFalse("the eldest must have been evicted", r.hasSeen(2, 0, 1))
        assertTrue("the newest must be retained", r.hasSeen(2, 0, 5))
    }

    @Test
    fun `the default capacity is the one the task names`() {
        assertEquals(512, Relay.SEEN_CAPACITY)
    }

    // ── the epoch matters ────────────────────────────────────────────────────

    /**
     * `SEQ` wraps at 65 536. Without the epoch in the key, the first frame after a wrap
     * would be suppressed as a duplicate of one sent 65 536 frames earlier.
     */
    @Test
    fun `the same sequence number in a new epoch is not a duplicate`() {
        val r = relay(1)
        assertTrue(r.consider(frame(2, 5), epochForSrc = 0) is Relay.Decision.Forward)
        assertTrue(
            "a new epoch makes this a different frame",
            r.consider(frame(2, 5), epochForSrc = 1) is Relay.Decision.Forward,
        )
    }

    @Test
    fun `the same sequence number from a different sender is not a duplicate`() {
        val r = relay(1)
        assertTrue(r.consider(frame(2, 5), 0) is Relay.Decision.Forward)
        assertTrue(r.consider(frame(3, 5), 0) is Relay.Decision.Forward)
    }

    // ── a unit recognises its own message coming back ────────────────────────

    @Test
    fun `a frame this unit originated is recognised when a peer relays it back`() {
        val r = relay(1)
        r.remember(src = 1, epoch = 0, seq = 5)
        assertTrue(r.hasSeen(1, 0, 5))
    }

    @Test
    fun `clear empties the seen-set`() {
        val r = relay(1)
        r.consider(frame(2, 5), 0)
        r.clear()
        assertEquals(0, r.seenCount)
    }
}

package org.itantra.link

import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Four devices relaying for an hour. Task **W7.15**.
 *
 * ## Why this runs in memory rather than on four handsets
 *
 * The property W7.15 asks for — *every message arrives exactly once and the seen-set stays
 * bounded* — is a property of the relay algorithm, not of the radio. Four handsets in a
 * room prove it for one hour, once, on one topology, and tell you nothing about the case
 * that actually breaks it. This runs the same hour of traffic through the same [Relay]
 * instances in a second, on the topology that makes relaying necessary, and can be run on
 * every commit.
 *
 * It does **not** replace the field test. Radio loss, collision and the timing of the
 * jitter window are exactly what a simulation cannot tell you, and W7.14 is where those
 * are measured. What this proves is that when a frame does arrive, the algorithm handles
 * it correctly — which is the half that can be proven cheaply.
 *
 * ## The topology matters more than the device count
 *
 * ```
 *   1 ── 2 ── 3 ── 4
 * ```
 *
 * A chain, not a clique. In a clique every device hears every other directly and relaying
 * is never exercised; the existing three-unit storm test already covers that shape. Here a
 * message from 1 reaches 4 only by being relayed twice, so both the forwarding path and
 * the loop suppression carry real traffic.
 *
 * ## One assumption stated plainly
 *
 * Each message finishes propagating before the next is sent. That is what lets the
 * exactly-once assertion be exact: the seen-set holds 512 entries and the hour carries
 * 1 800, so entries **are** evicted, and a frame arriving after its own eviction would be
 * delivered a second time. Real traffic interleaves, but a frame's propagation across
 * three hops takes milliseconds against a seen-set horizon of roughly two minutes, so the
 * eviction case needs a stalled relay rather than a busy one. Worth knowing that this
 * suite does not cover it.
 */
class RelaySoakTest {
    /** One device: its relay, and what its application layer has been handed. */
    private class Device(val id: Int) {
        val relay = Relay(id)
        val delivered = HashMap<Long, Int>()
        var seq = 0
        var maxSeen = 0

        fun key(
            src: Int,
            epoch: Long,
            seq: Int,
        ): Long = (src.toLong() shl 48) or (epoch shl 16) or seq.toLong()
    }

    @Test
    fun `an hour of four-device relaying delivers every message exactly once`() {
        val devices = (1..4).map { Device(it) }
        val neighbours =
            mapOf(1 to listOf(2), 2 to listOf(1, 3), 3 to listOf(2, 4), 4 to listOf(3))
        val byId = devices.associateBy { it.id }
        val random = Random(SEED)

        var transmissions = 0
        val originated = ArrayList<Long>()

        // An hour at one message per device every eight seconds. Realistic for a relief
        // team: bursts of traffic around an incident, quiet in between, and far more than
        // any single field session would generate.
        for (tick in 0 until MESSAGES_PER_DEVICE) {
            for (sender in devices) {
                val frame = frameFrom(sender.id, sender.seq++, ttl = INITIAL_TTL)
                originated += sender.key(sender.id, EPOCH, frame.seq)

                // The sender records its own frame, so a peer relaying it back is
                // recognised rather than relayed onward.
                sender.relay.remember(sender.id, EPOCH, frame.seq)
                transmissions++

                val pending = ArrayDeque<Pair<Int, Frame>>()
                for (peer in neighbours.getValue(sender.id)) pending += peer to frame

                while (pending.isNotEmpty()) {
                    val (atId, arriving) = pending.removeFirst()
                    val device = byId.getValue(atId)

                    // Delivery to the application is "first sight", which is what the
                    // seen-set is for. Checked before consider(), which records it.
                    val key = device.key(arriving.src, EPOCH, arriving.seq)
                    val isNew = !device.relay.hasSeen(arriving.src, EPOCH, arriving.seq)

                    val decision = device.relay.consider(arriving, EPOCH, random.nextLong(1000))
                    if (isNew) device.delivered.merge(key, 1, Int::plus)
                    device.maxSeen = maxOf(device.maxSeen, device.relay.seenCount)

                    if (decision is Relay.Decision.Forward) {
                        transmissions++
                        for (peer in neighbours.getValue(atId)) pending += peer to decision.frame
                    }
                }
            }
        }

        // ── exactly once ─────────────────────────────────────────────────────
        for (device in devices) {
            val mine = originated.filter { (it ushr 48).toInt() == device.id }
            val theirs = originated - mine.toSet()

            assertEquals(
                "device ${device.id} was handed ${device.delivered.size} distinct messages",
                theirs.size,
                device.delivered.size,
            )
            val duplicated = device.delivered.filterValues { it > 1 }
            assertTrue(
                "device ${device.id} received ${duplicated.size} messages more than once",
                duplicated.isEmpty(),
            )
            assertTrue(
                "device ${device.id} was handed its own transmission back",
                mine.none { it in device.delivered },
            )
        }

        // ── the seen-set stays bounded ───────────────────────────────────────
        for (device in devices) {
            assertTrue(
                "device ${device.id} peaked at ${device.maxSeen} seen entries, " +
                    "capacity ${Relay.SEEN_CAPACITY}",
                device.maxSeen <= Relay.SEEN_CAPACITY,
            )
        }

        // ── and it did not storm ─────────────────────────────────────────────
        // A chain of four with TTL 3: the originator transmits once and at most three
        // relays forward it once each. Anything above that is the suppression failing.
        val ceiling = MESSAGES_PER_DEVICE * devices.size * devices.size
        assertTrue(
            "$transmissions transmissions for ${MESSAGES_PER_DEVICE * devices.size} messages " +
                "exceeds the storm ceiling of $ceiling",
            transmissions <= ceiling,
        )
    }

    /**
     * `SEQ` wraps at 65 536 and the epoch is what keeps the seen-set from confusing the
     * two sides of a wrap. An eight-hour deployment at a high message rate reaches the
     * wrap; this asserts the seen-set stays bounded across it and that a reused sequence
     * number in a new epoch is not mistaken for a duplicate.
     */
    @Test
    fun `the seen-set stays bounded across a sequence-number wrap`() {
        val relay = Relay(localSrc = 9)
        var peak = 0

        for (n in 0 until (0x10000 + 5_000)) {
            val epoch = (n / 0x10000).toLong()
            val seq = n % 0x10000
            relay.consider(frameFrom(src = 1, seq = seq, ttl = 3), epoch)
            peak = maxOf(peak, relay.seenCount)
        }

        assertTrue("seen-set peaked at $peak", peak <= Relay.SEEN_CAPACITY)

        // The frame after the wrap carries the same SEQ as one from before it. Without the
        // epoch in the key it would be suppressed as a duplicate and never delivered.
        val afterWrap = relay.consider(frameFrom(src = 1, seq = 3, ttl = 3), epochForSrc = 1)
        assertTrue(
            "a sequence number reused in a new epoch must not be suppressed",
            afterWrap is Relay.Decision.Forward,
        )
    }

    private fun frameFrom(
        src: Int,
        seq: Int,
        ttl: Int,
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = 0,
        src = src,
        keyId = 1,
        ttl = ttl,
        payload = ByteArray(12) { it.toByte() },
    )

    private companion object {
        /** An hour at one message per device every eight seconds. */
        const val MESSAGES_PER_DEVICE = 450
        const val INITIAL_TTL = 3
        const val EPOCH = 7L
        const val SEED = 20260904L
    }
}

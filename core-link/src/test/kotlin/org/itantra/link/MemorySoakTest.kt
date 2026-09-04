package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.ReplayWindow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An hour of traffic through every structure that outlives a message. Task **W7.17**.
 *
 * ## What "no leak" means here, and what it does not
 *
 * W7.17 asks for resident memory flat over an hour. Resident memory is measured on the
 * handset, in week 8, with `dumpsys` — a JVM heap figure taken here would be a measurement
 * of the garbage collector, not of this code, and quoting it would be worse than quoting
 * nothing.
 *
 * What *can* be established here is the property underneath it: every structure that
 * survives a message has a ceiling, and an hour of traffic reaches it and stops. A leak in
 * this layer is not a slow drift — it is a map with no eviction, and a map with no eviction
 * is visible in a second of simulated traffic if you look for it. This suite looks.
 *
 * ## What it found
 *
 * [AlertDelivery.inFlight] had no ceiling. Its documentation said the caller must call
 * `forget` once an alert is finished with, which is true and which the engine does — but
 * "the table is bounded provided every caller remembers" is not a bound, it is a hope. An
 * eight-hour deployment is exactly where a single missed path shows up, and the failure is
 * silent until the process is killed. It now evicts completed alerts itself.
 */
class MemorySoakTest {
    // ── the relay's seen-set ─────────────────────────────────────────────────

    @Test
    fun `the relay's seen-set is bounded by an hour of six-unit traffic`() {
        val relay = Relay(localSrc = 1)
        var peak = 0

        for (message in 0 until MESSAGES_PER_HOUR) {
            for (src in 2..7) {
                relay.consider(frame(src = src, seq = message % 0x10000), epochForSrc = 0)
                peak = maxOf(peak, relay.seenCount)
            }
        }
        assertTrue("seen-set peaked at $peak", peak <= Relay.SEEN_CAPACITY)
    }

    // ── reassembly ───────────────────────────────────────────────────────────

    /**
     * The hostile case, not the ordinary one: a peer that starts messages and never
     * finishes them. Ordinary traffic completes and clears itself, so a leak here only
     * shows under abandonment — which is also the case an attacker would choose.
     */
    @Test
    fun `abandoned reassemblies do not accumulate over an hour`() {
        val reassembler = Reassembler()
        var peak = 0
        var now = 0L

        for (message in 0 until MESSAGES_PER_HOUR) {
            now += MILLIS_BETWEEN_MESSAGES
            // One fragment of a two-fragment message, then never the second.
            reassembler.offer(
                fragmentOf(seq = message % 0x10000, index = 0, count = 2),
                nowMillis = now,
            )
            reassembler.expire(now)
            peak = maxOf(peak, reassembler.pendingCount)
        }

        assertTrue(
            "reassembler held $peak partial messages, ceiling ${Reassembler.MAX_PARTIAL_MESSAGES}",
            peak <= Reassembler.MAX_PARTIAL_MESSAGES,
        )
    }

    // ── alert delivery ───────────────────────────────────────────────────────

    /**
     * The one that was not bounded. An hour of alerts, every one acknowledged, and no
     * caller ever calling `forget` — the shape of a caller that returns early on a path
     * nobody tested.
     */
    @Test
    fun `delivered alerts do not accumulate when the caller never forgets them`() {
        val delivery = AlertDelivery()
        var now = 0L

        for (n in 0 until ALERTS_PER_HOUR) {
            now += MILLIS_BETWEEN_ALERTS
            val seq = n % 0x10000
            delivery.enqueue(
                AlertDelivery.Pending(
                    seq = seq,
                    type = MessageType.ALERT,
                    wire = ByteArray(29),
                    queuedAtMillis = now,
                ),
            )
            delivery.dequeue(now, peerCount = 3)
            delivery.onAck(seq, src = 2, nowMillis = now)
            // Deliberately no forget(seq).
        }

        assertTrue(
            "alert table holds ${delivery.trackedCount} entries after $ALERTS_PER_HOUR alerts",
            delivery.trackedCount <= AlertDelivery.MAX_TRACKED,
        )
    }

    /** An alert still awaiting acknowledgement must not be evicted by newer ones. */
    @Test
    fun `eviction never drops an alert that is still in flight`() {
        val delivery = AlertDelivery()
        val watched = 1

        delivery.enqueue(AlertDelivery.Pending(watched, MessageType.ALERT, ByteArray(29), 0))
        delivery.dequeue(0, peerCount = 3)

        for (n in 2..(AlertDelivery.MAX_TRACKED * 2)) {
            delivery.enqueue(AlertDelivery.Pending(n, MessageType.ALERT, ByteArray(29), n.toLong()))
            delivery.dequeue(n.toLong(), peerCount = 3)
            delivery.onAck(n, src = 2, nowMillis = n.toLong())
        }

        assertTrue(
            "an unacknowledged alert was evicted; the operator would never be told it failed",
            delivery.progressOf(watched) != null,
        )
    }

    // ── the replay window ────────────────────────────────────────────────────

    /**
     * The replay window keeps state per peer, not per frame, so its ceiling is the number
     * of senders. That is bounded by the 8-bit `SRC` field rather than by traffic — worth
     * asserting, because it is the reason this structure does not need eviction at all.
     */
    @Test
    fun `the replay window grows with peers, not with traffic`() {
        val window = ReplayWindow()

        for (message in 0 until MESSAGES_PER_HOUR) {
            for (src in 2..7) {
                window.admit(src = src, epoch = 0, seq = message % 0x10000)
            }
        }
        assertTrue(
            "replay window tracks ${window.trackedSenders.size} senders",
            window.trackedSenders.size <= 6,
        )
    }

    private fun frame(
        src: Int,
        seq: Int,
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = 0,
        src = src,
        keyId = 1,
        ttl = 3,
        payload = ByteArray(12),
    )

    private fun fragmentOf(
        seq: Int,
        index: Int,
        count: Int,
    ): Frame {
        val body = ByteArray(10)
        return Frame(
            type = MessageType.TEXT,
            language = Language.HINDI,
            seq = seq,
            flags = Flags.FRAGMENT,
            src = 2,
            keyId = 1,
            ttl = 3,
            payload = byteArrayOf(index.toByte(), count.toByte()) + body,
        )
    }

    private companion object {
        /** One message every two seconds for an hour. */
        const val MESSAGES_PER_HOUR = 1_800
        const val MILLIS_BETWEEN_MESSAGES = 2_000L

        /** One alert every ten seconds is far above any real rate, which is the point. */
        const val ALERTS_PER_HOUR = 360
        const val MILLIS_BETWEEN_ALERTS = 10_000L
    }
}

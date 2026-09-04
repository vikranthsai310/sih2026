package org.itantra.link

import org.itantra.proto.Frame
import kotlin.random.Random

/**
 * Decides whether a received frame should be rebroadcast, so a unit out of direct range
 * is still reached. Task **W6.13**, risk **T-10**.
 *
 * ## The failure this exists to prevent
 *
 * Broadcast relaying without suppression is a storm. Three units in mutual range each
 * rebroadcast what the others send, those rebroadcasts are themselves received and
 * rebroadcast, and the channel saturates within a second or two from a single message.
 * On a shared half-duplex radio that does not degrade gracefully — it stops the channel
 * working at all, including for the alert that triggered it.
 *
 * Three independent mechanisms hold it down, and all three are necessary:
 *
 * | Mechanism | Stops |
 * | --- | --- |
 * | `TTL` decrement, drop at zero | Unbounded hop count |
 * | Seen-set on `(SRC, EPOCH, SEQ)` | The same frame going round a loop forever |
 * | 0–50 ms random delay | Every unit rebroadcasting in the same instant and colliding |
 *
 * The seen-set alone is not enough, because a frame can reach a unit by two paths before
 * either rebroadcast completes. The TTL alone is not enough, because a three-unit loop
 * multiplies traffic at every hop until the TTL runs out. The delay alone prevents
 * neither.
 *
 * ## `EPOCH` is not on the wire
 *
 * The header carries `SRC` and `SEQ` but not `EPOCH`, so the caller supplies the epoch it
 * currently believes that sender to be in — learned from their `HEARTBEAT`. This matters
 * because `SEQ` wraps at 65 536: without the epoch, a frame from after a wrap would be
 * suppressed as a duplicate of one from before it.
 */
class Relay(
    private val localSrc: Int,
    private val capacity: Int = SEEN_CAPACITY,
    private val random: Random = Random.Default,
) {
    sealed interface Decision {
        /**
         * Rebroadcast [frame] — already decremented — after waiting [delayMillis].
         */
        data class Forward(val frame: Frame, val delayMillis: Long) : Decision

        data class Drop(val reason: Reason) : Decision
    }

    enum class Reason {
        /** `ACK` and `HEARTBEAT` are never relayed: both are strictly point-to-point. */
        NOT_RELAYABLE,

        /** The frame has travelled as far as its sender allowed. */
        TTL_EXHAUSTED,

        /** Seen before — this is the loop suppressor. */
        ALREADY_SEEN,

        /** Our own transmission, heard back. Relaying it would be a self-sustaining loop. */
        OWN_FRAME,
    }

    /**
     * Insertion-ordered so the eldest entry is the first key; `LinkedHashMap` in
     * access order would keep frequently-seen frames instead, which is the wrong
     * eviction policy here — recency of *arrival* is what matters.
     */
    private val seen = LinkedHashSet<Long>()

    val seenCount: Int get() = seen.size

    /**
     * @param epochForSrc the epoch this unit believes [frame]'s sender is in, from their
     *   most recent `HEARTBEAT`
     */
    fun consider(
        frame: Frame,
        epochForSrc: Long,
        nowMillis: Long = 0,
    ): Decision {
        if (frame.src == localSrc) return Decision.Drop(Reason.OWN_FRAME)
        if (!frame.type.relayable) return Decision.Drop(Reason.NOT_RELAYABLE)

        // Recorded before the TTL check, so a frame that arrives again by a shorter path
        // is still suppressed rather than forwarded on its second appearance.
        val key = key(frame.src, epochForSrc, frame.seq)
        if (!remember(key)) return Decision.Drop(Reason.ALREADY_SEEN)

        if (frame.ttl <= 0) return Decision.Drop(Reason.TTL_EXHAUSTED)

        return Decision.Forward(
            frame = frame.copy(ttl = frame.ttl - 1),
            delayMillis = random.nextLong(MAX_JITTER_MILLIS + 1),
        )
    }

    /**
     * Records a frame this unit originated, so its own message coming back from a
     * relaying peer is recognised rather than relayed onward.
     */
    fun remember(
        src: Int,
        epoch: Long,
        seq: Int,
    ): Boolean = remember(key(src, epoch, seq))

    /** @return true if this is the first time; false if it was already known. */
    private fun remember(key: Long): Boolean {
        if (!seen.add(key)) return false
        if (seen.size > capacity) {
            val eldest = seen.first()
            seen.remove(eldest)
        }
        return true
    }

    fun hasSeen(
        src: Int,
        epoch: Long,
        seq: Int,
    ): Boolean = key(src, epoch, seq) in seen

    fun clear() = seen.clear()

    companion object {
        /**
         * 512 entries is roughly two minutes of traffic from six units at a realistic
         * rate — comfortably longer than any loop takes to die out, and a few kilobytes
         * of memory.
         */
        const val SEEN_CAPACITY = 512

        /** Long enough to decorrelate rebroadcasts, short enough not to add real delay. */
        const val MAX_JITTER_MILLIS = 50L

        /** `SRC` (8 bits) ‖ `EPOCH` (32) ‖ `SEQ` (16) packed into one `Long`. */
        private fun key(
            src: Int,
            epoch: Long,
            seq: Int,
        ): Long =
            ((src.toLong() and 0xFF) shl 48) or
                ((epoch and 0xFFFFFFFFL) shl 16) or
                (seq.toLong() and 0xFFFF)
    }
}

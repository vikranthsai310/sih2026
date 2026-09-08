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
 * | Seen-set on `(SRC, EPOCH, SEQ, fragment)` | The same frame going round a loop forever |
 * | 0–50 ms random delay | Every unit rebroadcasting in the same instant and colliding |
 *
 * The seen-set alone is not enough, because a frame can reach a unit by two paths before
 * either rebroadcast completes. The TTL alone is not enough, because a three-unit loop
 * multiplies traffic at every hop until the TTL runs out. The delay alone prevents
 * neither.
 *
 * ## What is relayed is what arrived
 *
 * The frame handed here is the **sealed** one, exactly as it came off the wire, and the
 * frame handed back is that frame with its `TTL` one lower. The first version relayed the
 * *opened* frame — plaintext, `ENCRYPTED` flag cleared — and every unit that received the
 * rebroadcast refused it as unauthenticated and counted it as an attack. Multi-hop never
 * worked, and every message was re-broadcast in the clear. The TTL can be decremented
 * without breaking the tag because [Session] leaves the TTL byte out of the associated
 * data, precisely so that relays can do this.
 *
 * ## `EPOCH` is not on the wire
 *
 * The header carries `SRC` and `SEQ` but not `EPOCH`, so the caller supplies the epoch it
 * has verified that sender to be in. This matters because `SEQ` wraps at 65 536: without
 * the epoch, a frame from after a wrap would be suppressed as a duplicate of one from
 * before it.
 *
 * ## Fragments
 *
 * The fragments of one message share `SRC` and `SEQ`. Keyed on those alone, the second
 * fragment was "already seen" the moment the first had been, and a fragmented message
 * could never cross a relay. The fragment index is part of the key.
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
     * @param epochForSrc the epoch this unit has verified [frame]'s sender to be in
     * @param fragment the fragment index for a fragment, or [WHOLE] for a complete frame
     */
    fun consider(
        frame: Frame,
        epochForSrc: Long,
        nowMillis: Long = 0,
        fragment: Int = WHOLE,
    ): Decision {
        if (!frame.type.relayable) return Decision.Drop(Reason.NOT_RELAYABLE)
        return decide(frame, key(frame.src, epochForSrc, frame.seq, fragment))
    }

    /**
     * A hello or a presence, which the type table says is never relayed and which
     * [Session] relays anyway when it is *news* -- see there for the rule and the reason.
     *
     * The type check is the only thing skipped. The key lives in its own [slot], so a
     * hello -- always `SEQ` 0 -- can never be mistaken for the first message of an epoch,
     * and the loop suppression is the same seen-set as for everything else: one relay per
     * unit per `(SRC, EPOCH, SEQ)`, however many roads bring it back.
     */
    fun considerControl(
        frame: Frame,
        epochForSrc: Long,
        nowMillis: Long = 0,
        slot: Int,
    ): Decision = decide(frame, key(frame.src, epochForSrc, frame.seq, slot))

    private fun decide(
        frame: Frame,
        key: Long,
    ): Decision {
        if (frame.src == localSrc) return Decision.Drop(Reason.OWN_FRAME)

        // Recorded before the TTL check, so a frame that arrives again by a shorter path
        // is still suppressed rather than forwarded on its second appearance.
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
        fragment: Int = WHOLE,
    ): Boolean = remember(key(src, epoch, seq, fragment))

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
        fragment: Int = WHOLE,
    ): Boolean = key(src, epoch, seq, fragment) in seen

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

        /** The fragment index of a frame that is not a fragment. */
        const val WHOLE = 0xFF

        /** The key slot of a relayed hello. Fragments count from zero, so neither collides. */
        const val HELLO_SLOT = 0xFE

        /** The key slot of a relayed presence. */
        const val PRESENCE_SLOT = 0xFD

        /** fragment (8 bits) ‖ `SRC` (8) ‖ `EPOCH` (32) ‖ `SEQ` (16) packed into one `Long`. */
        private fun key(
            src: Int,
            epoch: Long,
            seq: Int,
            fragment: Int,
        ): Long =
            ((fragment.toLong() and 0xFF) shl 56) or
                ((src.toLong() and 0xFF) shl 48) or
                ((epoch and 0xFFFFFFFFL) shl 16) or
                (seq.toLong() and 0xFFFF)
    }
}

package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame

/**
 * Splits a frame too large for the link, and puts it back together. Task **W6.5**.
 *
 * BLE negotiates an MTU of 247, of which about 244 is usable, and a serial link may be
 * narrower still. A frame carrying a long sentence plus its 16-byte AEAD tag can exceed
 * that, so it travels as several fragments.
 *
 * ## The ordering rule that matters
 *
 * > **The AEAD tag is verified after reassembly, never per fragment.**
 *
 * A fragment is a slice of ciphertext; it has no tag of its own and there is nothing in
 * it to authenticate. Attempting to decrypt fragment by fragment would mean processing
 * attacker-chosen bytes before anything has been authenticated at all — exactly the
 * position AEAD exists to avoid. So reassembly is a purely mechanical operation on
 * opaque bytes: it validates only sizes and indices, and hands the result to the AEAD
 * layer, which is the first thing entitled to an opinion about whether the content is
 * genuine.
 *
 * The consequence is that a hostile peer can make this class buffer up to
 * [MAX_FRAGMENTS] × the fragment size per `(SRC, SEQ)` before anything is rejected.
 * That is bounded deliberately, and the reassembly timeout bounds it in time as well.
 *
 * ## Fragment payload layout
 *
 * ```
 *  0      1        2 ..
 *  index  count    ciphertext slice
 * ```
 *
 * Two bytes, not a full sub-header: the enclosing frame already carries `SRC` and `SEQ`,
 * which together identify the message a fragment belongs to.
 */
class Fragmenter(private val mtu: Int) {
    init {
        require(mtu > Frame.HEADER_SIZE + Frame.CRC_SIZE + FRAGMENT_HEADER + 1) {
            "an MTU of $mtu leaves no room for payload"
        }
    }

    /** Bytes of message payload each fragment can carry. */
    val usablePayload: Int
        get() = mtu - Frame.HEADER_SIZE - Frame.CRC_SIZE - FRAGMENT_HEADER

    fun needsFragmenting(frame: Frame): Boolean = frame.wireSize > mtu

    /**
     * @return the fragments in order, or a single-element list containing [frame]
     *   unchanged when it already fits
     * @throws IllegalArgumentException if the payload would need more than
     *   [MAX_FRAGMENTS]
     */
    fun fragment(frame: Frame): List<Frame> {
        if (!needsFragmenting(frame)) return listOf(frame)

        val count = (frame.payload.size + usablePayload - 1) / usablePayload
        require(count <= MAX_FRAGMENTS) {
            "payload of ${frame.payload.size} needs $count fragments, " +
                "more than the $MAX_FRAGMENTS limit"
        }

        return (0 until count).map { index ->
            val from = index * usablePayload
            val to = minOf(from + usablePayload, frame.payload.size)
            val payload = ByteArray(FRAGMENT_HEADER + (to - from))
            payload[0] = index.toByte()
            payload[1] = count.toByte()
            frame.payload.copyInto(payload, FRAGMENT_HEADER, from, to)

            // FINAL is cleared on every fragment but the last, so a receiver that
            // ignores fragmentation entirely still cannot mistake a slice for a
            // complete message.
            val isLast = index == count - 1
            val flags =
                if (isLast) {
                    frame.flags or Flags.FRAGMENT or Flags.FINAL
                } else {
                    (frame.flags or Flags.FRAGMENT) and Flags.FINAL.inv()
                }

            frame.copy(flags = flags, payload = payload)
        }
    }

    companion object {
        /** One byte of index, one of count. */
        const val FRAGMENT_HEADER = 2

        /**
         * 255 is what a one-byte count allows, but the real bound is
         * [Frame.MAX_PAYLOAD]; 64 fragments of ~230 bytes already exceeds it.
         */
        const val MAX_FRAGMENTS = 64
    }
}

/**
 * Collects fragments until a message is whole.
 *
 * Everything here operates on opaque bytes. Nothing is decrypted, and no content is
 * inspected — see the ordering rule on [Fragmenter].
 */
class Reassembler(private val timeoutMillis: Long = REASSEMBLY_TIMEOUT_MILLIS) {
    private class Partial(val count: Int, val firstSeenMillis: Long) {
        val slices = arrayOfNulls<ByteArray>(count)
        var received = 0

        val isComplete: Boolean get() = received == count
    }

    private val partials = HashMap<Long, Partial>()

    val pendingCount: Int get() = partials.size

    sealed interface Result {
        /** The message is whole. */
        data class Complete(val frame: Frame) : Result

        /** More fragments needed. */
        data class Incomplete(val have: Int, val of: Int) : Result

        /** Malformed; the whole partial message is discarded. */
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param frame a frame carrying the `FRAGMENT` flag
     */
    fun offer(
        frame: Frame,
        nowMillis: Long,
    ): Result {
        expire(nowMillis)

        val payload = frame.payload
        if (payload.size < Fragmenter.FRAGMENT_HEADER + 1) {
            return Result.Rejected("fragment shorter than its own header")
        }
        val index = payload[0].toInt() and 0xFF
        val count = payload[1].toInt() and 0xFF

        if (count == 0 || count > Fragmenter.MAX_FRAGMENTS) {
            return Result.Rejected("implausible fragment count $count")
        }
        if (index >= count) {
            return Result.Rejected("fragment $index of $count is out of range")
        }

        val key = key(frame.src, frame.seq)
        val partial = partials.getOrPut(key) { Partial(count, nowMillis) }

        // A peer that changes its mind about the message length mid-transfer is either
        // broken or hostile; either way the partial is not going to reassemble.
        if (partial.count != count) {
            partials.remove(key)
            return Result.Rejected("fragment count changed from ${partial.count} to $count")
        }

        // A repeated fragment is ignored rather than counted twice, so a retransmission
        // cannot make a partial message look complete.
        if (partial.slices[index] == null) {
            partial.slices[index] = payload.copyOfRange(Fragmenter.FRAGMENT_HEADER, payload.size)
            partial.received++
        }

        if (!partial.isComplete) return Result.Incomplete(partial.received, count)

        partials.remove(key)
        val whole = partial.slices.requireNoNulls().reduce { a, b -> a + b }
        if (whole.size > Frame.MAX_PAYLOAD) {
            return Result.Rejected("reassembled payload of ${whole.size} exceeds the limit")
        }

        return Result.Complete(
            frame.copy(
                flags = frame.flags and Flags.FRAGMENT.inv() or Flags.FINAL,
                payload = whole,
            ),
        )
    }

    /**
     * Discards partial messages older than the timeout.
     *
     * Without this a sender that dies mid-message leaks its fragments for the lifetime
     * of the process, and a hostile peer could hold memory open indefinitely by sending
     * one fragment of many thousands of messages.
     *
     * @return how many partial messages were abandoned
     */
    fun expire(nowMillis: Long): Int {
        val stale = partials.filterValues { nowMillis - it.firstSeenMillis >= timeoutMillis }
        stale.keys.forEach { partials.remove(it) }
        return stale.size
    }

    fun clear() = partials.clear()

    companion object {
        /** `docs/TODO.md` W6.5. Long enough for a slow link, short enough to bound memory. */
        const val REASSEMBLY_TIMEOUT_MILLIS = 2_000L

        private fun key(
            src: Int,
            seq: Int,
        ): Long = ((src.toLong() and 0xFF) shl 16) or (seq.toLong() and 0xFFFF)
    }
}

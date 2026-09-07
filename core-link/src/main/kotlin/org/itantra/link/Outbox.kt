package org.itantra.link

/**
 * Store-and-forward for frames written while the link was down. Task **W2.30**.
 *
 * ## What an outbox is for, and what it must not become
 *
 * An operator who speaks into a unit whose peer has walked out of range should not lose
 * the message, and should not have to know that they nearly did. The frames are held and
 * flushed in order when the link returns.
 *
 * The failure mode is the opposite one. A queue with no bound turns a long outage into an
 * out-of-memory kill, and a queue with no age limit turns a returning link into a flood of
 * messages that stopped being true hours ago — "water is rising here", delivered at
 * nightfall about a morning. Both caps are therefore part of the definition rather than a
 * tuning parameter: **500 frames, 24 hours**.
 *
 * ## Which end is dropped when it is full
 *
 * The **oldest**. That is the opposite of the [Reassembler]'s choice, and for the opposite
 * reason: there, evicting the newest would let an attacker lock out live traffic; here,
 * the queue is entirely our own and the newest message is the one most likely to still be
 * true. A drop is counted rather than hidden, because an operator whose message was
 * discarded is entitled to know the outage cost them something.
 *
 * ## Persistence
 *
 * The bound and the ordering live here, in a pure class that can be tested in
 * milliseconds. Durability across a process death is a separate concern behind [Store] —
 * the same split as [org.itantra.proto.EpochCounter] and its store, and for the same
 * reason: the policy is what has bugs, and it should not need a database to prove.
 */
class Outbox(
    private val capacity: Int = MAX_FRAMES,
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val store: Store? = null,
) {
    /** Durability, if a caller wants it. Restored at construction. */
    interface Store {
        fun load(): List<Entry>

        fun persist(entries: List<Entry>)
    }

    /**
     * @param urgent whether the frame was an alert when it was queued. An alert held
     *   through an outage must still go down every road when the outage ends, and the
     *   wire bytes alone cannot say so — see [Link.send].
     */
    data class Entry(
        val wire: ByteArray,
        val queuedAtMillis: Long,
        val urgent: Boolean = false,
    ) {
        // A ByteArray in a data class compares by identity, which would make two entries
        // holding the same frame unequal and any test of the contents meaningless.
        override fun equals(other: Any?): Boolean =
            other is Entry &&
                queuedAtMillis == other.queuedAtMillis &&
                urgent == other.urgent &&
                wire.contentEquals(other.wire)

        override fun hashCode(): Int {
            var result = wire.contentHashCode()
            result = 31 * result + queuedAtMillis.hashCode()
            return 31 * result + urgent.hashCode()
        }
    }

    private val queue = ArrayDeque<Entry>()

    init {
        store?.load()?.forEach { queue.addLast(it) }
        while (queue.size > capacity) queue.removeFirst()
    }

    val size: Int get() = queue.size

    val isEmpty: Boolean get() = queue.isEmpty()

    /** Frames dropped because the queue was full. Above zero means an outage cost a message. */
    var droppedOldest: Long = 0L
        private set

    /** Frames discarded for being too old to be worth delivering. */
    var expired: Long = 0L
        private set

    /**
     * Holds a frame.
     *
     * @return false if an older frame had to be dropped to make room, so a caller can tell
     *   the operator rather than discovering it in a counter later
     */
    fun offer(
        wire: ByteArray,
        nowMillis: Long,
        urgent: Boolean = false,
    ): Boolean {
        purgeExpired(nowMillis)
        var droppedAny = false
        while (queue.size >= capacity) {
            queue.removeFirst()
            droppedOldest++
            droppedAny = true
        }
        queue.addLast(Entry(wire.copyOf(), nowMillis, urgent))
        store?.persist(queue.toList())
        return !droppedAny
    }

    /**
     * Everything still worth sending, oldest first, and empties the queue.
     *
     * In order, because a relief message that arrives before the one it answers reads as a
     * different conversation. Expired frames are dropped here as well as on [offer], since
     * an outage long enough to matter ends with a flush rather than with another write.
     */
    fun drain(nowMillis: Long): List<Entry> {
        purgeExpired(nowMillis)
        val out = queue.toList()
        queue.clear()
        store?.persist(emptyList())
        return out
    }

    /** The frames without draining them, for an interface that shows what is waiting. */
    fun peek(): List<Entry> = queue.toList()

    fun clear() {
        queue.clear()
        store?.persist(emptyList())
    }

    private fun purgeExpired(nowMillis: Long) {
        while (queue.isNotEmpty() && nowMillis - queue.first().queuedAtMillis >= maxAgeMillis) {
            queue.removeFirst()
            expired++
        }
    }

    companion object {
        /** `docs/TODO.md` W2.30. About twenty minutes of continuous traffic. */
        const val MAX_FRAMES = 500

        /**
         * A day. Long enough that an overnight outage still delivers; short enough that
         * nothing arrives describing a situation that has since been resolved.
         */
        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }
}

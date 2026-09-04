package org.itantra.link

import org.itantra.proto.MessageType

/**
 * The transmit queue, in which an `ALERT` overtakes everything else, and the
 * acknowledgement tracking behind the delivery indicator. Tasks **W5.15** and **W5.16**.
 *
 * ## Two different questions
 *
 * The interface asks *"did this get through?"* and *"to how many?"* — and they have
 * different answers, which is the point of this class:
 *
 * - **Delivered** is true on the **first** acknowledgement. One unit hearing an
 *   evacuation order is the difference between the message working and not working, and
 *   retrying after that would put a duplicate alert on a channel that is now carrying
 *   the response to it.
 * - **The count keeps rising** after that, so the display can read `3 of 6 units`. An
 *   operator deciding whether to send a runner needs the count, not the boolean.
 *
 * Collapsing the two — stopping the count at the first ack, or retrying until all six
 * answer — gets one of the two questions wrong.
 */
class AlertDelivery(
    private val retryIntervalMillis: Long = RETRY_INTERVAL_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    /** A message waiting to go out. */
    data class Pending(
        val seq: Int,
        val type: MessageType,
        val wire: ByteArray,
        val queuedAtMillis: Long,
    ) {
        /** `ALERT` pre-empts everything; nothing pre-empts `ALERT`. */
        val isAlert: Boolean get() = type == MessageType.ALERT

        override fun equals(other: Any?): Boolean = other is Pending && seq == other.seq && type == other.type

        override fun hashCode(): Int = 31 * seq + type.hashCode()
    }

    /** What is known about one alert in flight. */
    data class Progress(
        val seq: Int,
        val attempts: Int,
        val ackedBy: Set<Int>,
        val peerCount: Int,
        val givenUp: Boolean = false,
    ) {
        /** True once anyone at all has answered. */
        val isDelivered: Boolean get() = ackedBy.isNotEmpty()

        /** What the interface shows: `3 of 6 units`. */
        fun display(): String = "${ackedBy.size} of $peerCount units"

        val isComplete: Boolean get() = ackedBy.size >= peerCount
    }

    private val queue = ArrayDeque<Pending>()
    private val inFlight = LinkedHashMap<Int, Progress>()
    private val nextRetryAt = HashMap<Int, Long>()

    val queueDepth: Int get() = queue.size

    /** Alerts still tracked for acknowledgement. Bounded by [MAX_TRACKED]. */
    val trackedCount: Int get() = inFlight.size

    /**
     * Enqueues a frame. An `ALERT` goes to the front, ahead of any ordinary text already
     * waiting — but behind any alert already queued, so two alerts stay in the order
     * they were spoken.
     */
    fun enqueue(pending: Pending) {
        if (!pending.isAlert) {
            queue.addLast(pending)
            return
        }
        val insertAt = queue.indexOfFirst { !it.isAlert }
        if (insertAt < 0) queue.addLast(pending) else queue.add(insertAt, pending)
    }

    /** Takes the next frame to transmit, registering an alert for acknowledgement. */
    fun dequeue(
        nowMillis: Long,
        peerCount: Int,
    ): Pending? {
        val next = queue.removeFirstOrNull() ?: return null
        if (next.isAlert) {
            inFlight[next.seq] = Progress(next.seq, attempts = 1, ackedBy = emptySet(), peerCount = peerCount)
            nextRetryAt[next.seq] = nowMillis + retryIntervalMillis
            evictFinished()
        }
        return next
    }

    /**
     * Keeps the tracking table bounded. Task **W7.17**.
     *
     * [forget] is the intended way an alert leaves this table, and the engine calls it.
     * But "bounded provided every caller remembers" is not a bound, it is a hope, and the
     * path that forgets to call it is silent until the process is killed — which on an
     * eight-hour deployment means it is silent for the whole deployment. So the table
     * evicts for itself.
     *
     * **Only finished alerts are evicted**, eldest first. An alert still awaiting
     * acknowledgement must never disappear: [undelivered] is how the operator is told
     * that a message they believe went out did not, and dropping one to save a few
     * hundred bytes would trade the worst failure this interface can have against
     * nothing. An unfinished alert is bounded anyway — it becomes given-up after
     * [maxAttempts] retries.
     */
    private fun evictFinished() {
        if (inFlight.size <= MAX_TRACKED) return
        val eldestFinished =
            inFlight.entries.firstOrNull { (_, progress) ->
                progress.isDelivered || progress.givenUp
            } ?: return
        forget(eldestFinished.key)
    }

    /**
     * Records an acknowledgement of [seq] from [src].
     *
     * @return the updated progress, or null if this alert is not in flight — a duplicate
     *   or very late ack, which is ignored rather than counted twice.
     */
    fun onAck(
        seq: Int,
        src: Int,
        nowMillis: Long,
    ): Progress? {
        val current = inFlight[seq] ?: return null
        val updated = current.copy(ackedBy = current.ackedBy + src)
        inFlight[seq] = updated

        // Delivered. Stop retrying: a duplicate alert would land on a channel now
        // carrying the reply to the first one.
        if (updated.isDelivered) nextRetryAt.remove(seq)
        return updated
    }

    /**
     * Alerts whose retry is due. Each returned alert has its attempt counted, so calling
     * this twice for the same instant does not double-count.
     *
     * @return the sequence numbers to retransmit
     */
    fun dueForRetry(nowMillis: Long): List<Int> {
        val due = nextRetryAt.filterValues { it <= nowMillis }.keys.toList()
        val out = ArrayList<Int>(due.size)

        for (seq in due) {
            val progress = inFlight[seq] ?: continue
            if (progress.attempts >= maxAttempts) {
                // Out of attempts and nobody answered. The operator must be told; a
                // silent failure here is the worst outcome the interface can produce.
                inFlight[seq] = progress.copy(givenUp = true)
                nextRetryAt.remove(seq)
                continue
            }
            inFlight[seq] = progress.copy(attempts = progress.attempts + 1)
            nextRetryAt[seq] = nowMillis + retryIntervalMillis
            out += seq
        }
        return out
    }

    fun progressOf(seq: Int): Progress? = inFlight[seq]

    /** Alerts that exhausted every attempt with no acknowledgement at all. */
    fun undelivered(): List<Progress> = inFlight.values.filter { it.givenUp && !it.isDelivered }

    /** Called once an alert is finished with, so the table does not grow without bound. */
    fun forget(seq: Int) {
        inFlight.remove(seq)
        nextRetryAt.remove(seq)
    }

    fun clear() {
        queue.clear()
        inFlight.clear()
        nextRetryAt.clear()
    }

    companion object {
        /** Three attempts at 300 ms — `docs/TODO.md` W5.15. */
        const val RETRY_INTERVAL_MILLIS = 300L
        const val MAX_ATTEMPTS = 3

        /**
         * Finished alerts kept before the eldest is dropped. Task **W7.17**.
         *
         * Sixty-four is far above any real number of alerts outstanding at once — an
         * operator sending one every ten seconds for an hour never has more than a
         * handful unresolved — and it is a few kilobytes. It exists to make the table
         * bounded by construction, not to be reached.
         */
        const val MAX_TRACKED = 64
    }
}

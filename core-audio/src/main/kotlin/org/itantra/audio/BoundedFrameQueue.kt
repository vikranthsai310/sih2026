package org.itantra.audio

/**
 * A fixed-capacity queue between the capture thread and the inference thread.
 *
 * The capture thread must never block: a blocked capture thread drops audio at the
 * hardware, and a dropped block is a lost utterance. So when the recogniser falls
 * behind, this discards the **oldest** frame and counts it rather than making the
 * producer wait.
 *
 * Dropping stale speech is correct behaviour. Retransmitting or queueing old
 * conversation is worse than losing it — the exception is `ALERT`, which is
 * acknowledged and retried at the protocol layer instead.
 *
 * See `docs/ARCHITECTURE.md` section 3, queue policy: capture to inference, depth
 * 100 frames, which is two seconds at a 20 ms hop.
 */
class BoundedFrameQueue<T>(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val items = ArrayDeque<T>(capacity)
    private val lock = Any()

    /** Frames discarded because the consumer fell behind. Surfaced as `DEGRADED` in the UI. */
    var droppedCount: Long = 0L
        private set

    val size: Int get() = synchronized(lock) { items.size }

    val isEmpty: Boolean get() = size == 0

    /**
     * Appends a frame, never blocking.
     *
     * @return true if the queue had room, false if the oldest frame was discarded to
     *   make space. A false return is the signal to surface a degraded state.
     */
    fun offer(item: T): Boolean =
        synchronized(lock) {
            if (items.size >= capacity) {
                items.removeFirst()
                droppedCount++
                items.addLast(item)
                false
            } else {
                items.addLast(item)
                true
            }
        }

    /** @return the oldest frame, or null if empty. Never blocks. */
    fun poll(): T? = synchronized(lock) { items.removeFirstOrNull() }

    /** Drains everything currently queued, oldest first. */
    fun drain(): List<T> =
        synchronized(lock) {
            val out = items.toList()
            items.clear()
            out
        }

    fun clear() =
        synchronized(lock) {
            items.clear()
        }

    /** Zeroes the dropped counter, for instance when the interface leaves `DEGRADED`. */
    fun resetDroppedCount() =
        synchronized(lock) {
            droppedCount = 0L
        }

    companion object {
        /** 100 frames of 20 ms is two seconds of slack before anything is lost. */
        const val CAPTURE_TO_INFERENCE = 100

        /** Outbound frames; `ALERT` pre-empts rather than queueing. */
        const val INFERENCE_TO_LINK = 32

        /** Inbound utterances waiting to be spoken. */
        const val LINK_TO_SYNTHESIS = 16
    }
}

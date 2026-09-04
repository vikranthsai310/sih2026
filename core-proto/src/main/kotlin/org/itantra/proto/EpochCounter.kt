package org.itantra.proto

/**
 * Keeps `EPOCH` strictly increasing across restarts, reboots and crashes. Task
 * **W6.10**, risk **S-07**.
 *
 * ## Why this is the most safety-critical counter in the project
 *
 * The AEAD nonce is derived rather than transmitted: `EPOCH ‖ SRC ‖ SEQ ‖ padding`. That
 * saves eight bytes on every frame, which on a 44-byte frame is a fifth of it. The price
 * is that **nonce uniqueness rests entirely on this counter**, and nonce reuse under a
 * fixed key does not weaken AES-GCM, it destroys it: two messages encrypted with the same
 * nonce leak their XOR, and the authentication key itself becomes recoverable.
 *
 * `SEQ` is 16 bits and wraps every 65 536 frames. `EPOCH` must advance on that wrap, and
 * on every process start, because a process that restarts and resumes from `SEQ = 0`
 * under the same epoch would reuse every nonce it had already used.
 *
 * ## The persistence rule
 *
 * > **Persist before use, not after.**
 *
 * The counter is written to storage *before* the new epoch is handed out. Writing
 * afterwards leaves a window in which the process dies having transmitted under an epoch
 * that was never recorded — and the next start would reuse it. Being one epoch ahead
 * after a crash costs nothing; being one behind is a total loss of confidentiality.
 */
class EpochCounter(private val store: Store) {
    /**
     * Durable storage for the counter. On device this is backed by DataStore; the
     * interface exists so the ordering above can be tested, including the crash.
     */
    interface Store {
        /** @return the last persisted epoch, or null on a first-ever start. */
        fun read(): Long?

        /** Must not return until the value is durable. */
        fun write(epoch: Long)
    }

    private var current: Long = UNSTARTED

    /** The epoch in use. Valid only after [start]. */
    val epoch: Long
        get() {
            check(current != UNSTARTED) { "start() has not been called" }
            return current
        }

    /**
     * Advances to a fresh epoch for this run, persisting it first.
     *
     * Called once at service start, before any frame is sent.
     *
     * @return the epoch now in use
     */
    fun start(): Long {
        val previous = store.read() ?: INITIAL - 1
        val next = previous + 1
        require(next <= MAX_EPOCH) {
            "epoch space exhausted at $previous; the key must be rotated"
        }
        // Persisted before it is used, and before it is even visible on this object.
        store.write(next)
        current = next
        return next
    }

    /**
     * Called when `SEQ` wraps past 65 535. Advances the epoch so the nonce for the next
     * `SEQ = 0` differs from the one used 65 536 frames ago.
     *
     * @return the new epoch
     */
    fun onSequenceWrap(): Long {
        check(current != UNSTARTED) { "start() has not been called" }
        val next = current + 1
        require(next <= MAX_EPOCH) {
            "epoch space exhausted at $current; the key must be rotated"
        }
        store.write(next)
        current = next
        return next
    }

    /**
     * Whether a sequence number is about to wrap, so the caller can advance the epoch
     * before rather than after the frame that would collide.
     */
    fun willWrap(nextSeq: Int): Boolean = nextSeq > MAX_SEQ

    companion object {
        private const val UNSTARTED = -1L

        /** First epoch on a device that has never run. */
        const val INITIAL = 0L

        /** `EPOCH` is a 32-bit field. */
        const val MAX_EPOCH = 0xFFFFFFFFL

        /** `SEQ` is 16 bits. */
        const val MAX_SEQ = 0xFFFF
    }
}

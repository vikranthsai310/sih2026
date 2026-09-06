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
class EpochCounter(
    private val store: Store,
    /**
     * Wall-clock milliseconds, or null for a pure counter.
     *
     * With a clock, a start never issues an epoch below the number of minutes since
     * [ORIGIN_MILLIS]. That is what keeps the epoch moving forward across a
     * **reinstall**, which wipes the persisted counter: a receiver holds the highest
     * epoch it has verified for each sender and refuses anything lower as a replay, so a
     * sender whose counter went back to zero was refused until the receiver restarted --
     * and, worse, a counter back at zero reuses nonces it has already used under this
     * key. Minutes, because a reinstall never completes inside one, and restarts inside
     * one minute still get distinct epochs from the counter. A clock reading before the
     * origin, or absurdly far after it, is ignored and the counter alone applies.
     */
    private val clock: (() -> Long)? = null,
) {
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
        val next = maxOf(previous + 1, timeSeed())
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

    /** Minutes since [ORIGIN_MILLIS], or [INITIAL] when there is no clock or it reads nonsense. */
    private fun timeSeed(): Long {
        val millis = clock?.invoke() ?: return INITIAL
        val minutes = (millis - ORIGIN_MILLIS) / MINUTE_MILLIS
        return if (minutes in 1 until SANE_MINUTES) minutes else INITIAL
    }

    companion object {
        private const val UNSTARTED = -1L

        /** First epoch on a device that has never run. */
        const val INITIAL = 0L

        /** `EPOCH` is a 32-bit field. */
        const val MAX_EPOCH = 0xFFFFFFFFL

        /** `SEQ` is 16 bits. */
        const val MAX_SEQ = 0xFFFF

        /** 2026-01-01T00:00:00Z. Epochs seeded from the clock count minutes from here. */
        const val ORIGIN_MILLIS = 1_767_225_600_000L

        const val MINUTE_MILLIS = 60_000L

        /** A century. A clock past this is a clock that is wrong, not a date. */
        const val SANE_MINUTES = 100L * 365 * 24 * 60
    }
}

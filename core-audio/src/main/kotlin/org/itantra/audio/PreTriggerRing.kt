package org.itantra.audio

/**
 * A fixed-capacity ring of PCM samples retained ahead of the trigger, so that the
 * first phoneme of an utterance is never clipped.
 *
 * The endpointer opens on speech, but speech has already begun by the time detection
 * fires. This holds the immediately preceding audio so it can be prepended to the
 * utterance. See `docs/ASR.md` section 2 — leading pad, 250 ms.
 *
 * Allocated once at service start and never resized: the capture thread must not
 * allocate. Writing is O(n) in the frame size and allocation-free.
 */
class PreTriggerRing(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val buffer = ShortArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    /** Number of samples currently retained, at most [capacity]. */
    val size: Int get() = filled

    /**
     * Appends a frame, overwriting the oldest samples once full.
     *
     * Allocation-free by contract — this runs on the capture thread.
     */
    fun write(
        frame: ShortArray,
        offset: Int = 0,
        length: Int = frame.size - offset,
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= frame.size) {
            "range $offset..${offset + length} exceeds frame of ${frame.size}"
        }

        // A frame longer than the ring can only leave its own tail behind.
        val start = if (length > capacity) offset + length - capacity else offset
        val count = if (length > capacity) capacity else length

        for (i in 0 until count) {
            buffer[writeIndex] = frame[start + i]
            writeIndex = (writeIndex + 1) % capacity
        }
        filled = minOf(capacity, filled + count)
    }

    /**
     * @return the retained samples in chronological order, oldest first.
     *   Allocates, so it is called once when an utterance opens — never per frame.
     */
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        if (filled == 0) return out
        val start = (writeIndex - filled + capacity) % capacity
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % capacity]
        }
        return out
    }

    /** Discards the retained audio; the allocation is kept. */
    fun clear() {
        writeIndex = 0
        filled = 0
    }

    companion object {
        /**
         * 250 ms of leading pad at 16 kHz mono is 4 000 samples, which is 8 000 bytes.
         * Permanently allocated at service start.
         */
        fun forLeadingPad(
            sampleRate: Int = 16_000,
            padMillis: Int = 250,
        ): PreTriggerRing = PreTriggerRing(sampleRate * padMillis / 1000)
    }
}

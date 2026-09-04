package org.itantra.asr

/**
 * Decides which words of a part-decoded utterance are safe to send early. Task **W6.2**.
 *
 * ## Where a partial comes from here
 *
 * Not from the model. The acoustic model is offline and yields no partial hypotheses —
 * that is the finding that revised the whole latency budget. A partial here is the
 * **stitched text of the decode windows completed so far** (task W3.13), which is a
 * different thing and behaves differently.
 *
 * ## Why the tail must be withheld
 *
 * The last words of a window are the least reliable: the window ends mid-word, and the
 * next window's overlap usually revises them. Sending the raw stitched text early would
 * mean the receiver speaks a word the sender never said, and then a correction — which is
 * worse than waiting, because a listener acts on the first version.
 *
 * So a word is only released once it has survived [STABLE_REPEATS] consecutive partials
 * unchanged. In practice that means everything except the last few words of the newest
 * window, which is exactly the unreliable part.
 *
 * ## The `PARTIAL` flag
 *
 * Released text is transmitted with `PARTIAL` set and `FINAL` clear, so the receiver may
 * begin synthesising it while the sender is still speaking — the same trick as
 * sliding-window decoding, applied at the other end. The receiver must not treat a
 * `PARTIAL` frame as a complete message: it is a prefix, and the `FINAL` frame that
 * follows carries the whole utterance.
 */
class StabilisedPartials(private val stableRepeats: Int = STABLE_REPEATS) {
    private var released = emptyList<String>()
    private val history = ArrayList<List<String>>()

    /** Words released so far, in order. */
    fun releasedText(): String = released.joinToString(" ")

    /**
     * Offers the newest stitched partial.
     *
     * @return the words newly safe to send, or empty when nothing has stabilised. The
     *   caller transmits these with `PARTIAL` set.
     */
    fun offer(partial: String): List<String> {
        val words = partial.trim().split(WHITESPACE).filter { it.isNotBlank() }
        history.add(words)
        if (history.size > stableRepeats) history.removeAt(0)
        if (history.size < stableRepeats) return emptyList()

        // A prefix is stable when every recent partial agrees on it.
        val stable = commonPrefix(history)

        // Never un-release: a word already spoken by the receiver cannot be recalled, so
        // if a later partial disagrees the correction waits for the FINAL frame.
        if (stable.size <= released.size) return emptyList()

        val fresh = stable.subList(released.size, stable.size).toList()
        released = stable
        return fresh
    }

    /**
     * The utterance is complete.
     *
     * @return the full text, which the caller sends with `FINAL` set. It is sent whole
     *   rather than as a remainder, so a receiver that missed a partial still gets a
     *   complete message — the frames are small and the duplication is cheap insurance.
     */
    fun onFinal(finalText: String): String {
        reset()
        return finalText.trim()
    }

    fun reset() {
        released = emptyList()
        history.clear()
    }

    companion object {
        /**
         * Three consecutive agreeing partials. Two is too eager — the overlap between
         * two adjacent windows revises words routinely — and four costs more latency
         * than the early start buys back.
         */
        const val STABLE_REPEATS = 3

        private val WHITESPACE = Regex("\\s+")

        /** The longest prefix every list shares. */
        fun commonPrefix(lists: List<List<String>>): MutableList<String> {
            if (lists.isEmpty()) return mutableListOf()
            val shortest = lists.minOf { it.size }
            val out = ArrayList<String>(shortest)
            for (i in 0 until shortest) {
                val word = lists[0][i]
                if (lists.any { it[i] != word }) break
                out.add(word)
            }
            return out
        }
    }
}

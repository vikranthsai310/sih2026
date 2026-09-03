package org.itantra.proto

/**
 * A sliding window of recently accepted sequence numbers, per sender.
 *
 * Without this, a recorded alert can be replayed indefinitely — an attacker who
 * captures one valid maximum-volume evacuation order can retransmit it for as long
 * as the key lives. This is risk **S-02**, and the control is normative in
 * `docs/PROTOCOL.md` section 6.4.
 *
 * Sixty-four entries per sender, held as a bitmask in a single [Long]: bit 0 is the
 * highest sequence number seen, bit *n* is *n* below it. That makes acceptance a
 * shift and a mask, which matters because it runs on every frame.
 *
 * Keyed on `(EPOCH, SEQ)` rather than `SEQ` alone. `SEQ` is only sixteen bits and
 * wraps every 65 536 frames; `EPOCH` increments on each wrap and on every service
 * start, which is what makes the pair unique for the life of a key — and what makes
 * the derived AEAD nonce safe. See [Aead].
 */
class ReplayWindow(private val windowSize: Int = 64) {
    init {
        require(windowSize in 1..64) { "window must fit a Long: $windowSize" }
    }

    private data class Peer(
        var epoch: Long,
        var highestSeq: Int,
        var mask: Long,
    )

    private val peers = HashMap<Int, Peer>()

    /**
     * Tests and records a frame in one step.
     *
     * @return true if the frame is fresh and should be processed; false if it is a
     *   replay, arrives from an older epoch, or has fallen out of the window.
     */
    fun admit(
        src: Int,
        epoch: Long,
        seq: Int,
    ): Boolean {
        require(src in 0..0xFF) { "src must fit 8 bits: $src" }
        require(seq in 0..0xFFFF) { "seq must fit 16 bits: $seq" }

        val peer = peers[src]

        // First frame from this sender.
        if (peer == null) {
            peers[src] = Peer(epoch, seq, 1L)
            return true
        }

        // A restart or a SEQ wrap. The window is meaningless across epochs, so reset it.
        if (epoch > peer.epoch) {
            peer.epoch = epoch
            peer.highestSeq = seq
            peer.mask = 1L
            return true
        }

        // An older epoch cannot be fresh: either a replay of pre-restart traffic, or
        // a sender whose persisted epoch went backwards, which is itself a defect.
        if (epoch < peer.epoch) return false

        val delta = seq - peer.highestSeq

        return when {
            // Newer than anything seen: slide the window forward.
            delta > 0 -> {
                peer.mask = if (delta >= windowSize) 1L else (peer.mask shl delta) or 1L
                peer.highestSeq = seq
                true
            }

            // The current highest, already recorded.
            delta == 0 -> false

            // Older than the window can remember. Refusing is the safe answer: it may
            // cost a genuinely delayed frame, and it is what stops an old capture.
            -delta >= windowSize -> false

            // Within the window: accept once, then never again.
            else -> {
                val bit = 1L shl (-delta)
                if (peer.mask and bit != 0L) {
                    false
                } else {
                    peer.mask = peer.mask or bit
                    true
                }
            }
        }
    }

    /** Highest epoch accepted from a sender, or null if none. Advertised in `HEARTBEAT`. */
    fun epochOf(src: Int): Long? = peers[src]?.epoch

    /** Forgets a sender, for instance after re-keying. */
    fun forget(src: Int) {
        peers.remove(src)
    }

    fun clear() = peers.clear()

    /** Senders currently tracked. */
    val trackedSenders: Set<Int> get() = peers.keys.toSet()
}

package org.itantra.proto

/**
 * The clock-sync exchange and the audio receipt: the three small control payloads that
 * turn `latency.csv` from a sender-side log into an end-to-end measurement.
 *
 * ## Why these exist
 *
 * The figure the jury times is audio beginning on the receiver minus the microphone on
 * the sender. Those two instants are taken on two handsets whose `nanoTime` clocks have
 * unrelated origins, so the receiver's timestamp is meaningless to the sender until the
 * offset between the two clocks is known. [ClockSync] computes that offset from round
 * trips; [Ping] and [Pong] are the round trip, and [AudioReport] is the receiver telling
 * the sender when its message was heard, in the receiver's own clock, for the sender to
 * convert. `docs/EVALUATION.md` section 4, task **W3.10**.
 *
 * ## How they share a frame type with presence
 *
 * All three travel in a **sealed** `HEARTBEAT` frame, exactly as [Presence] does, and are
 * told apart from it by the first byte: [Presence.VERSION] is 1, [VERSION] here is 2. A
 * unit that predates this file reads the byte, finds it is not a presence it understands,
 * and drops the frame — which is the right thing for it to do. Nothing here is relayed,
 * because a `HEARTBEAT` never is: the offset is measured to the unit that answered, over
 * the road it answered on.
 *
 * ## Layout
 *
 * ```
 *  PING          0=ver(2)  1=kind(1)  2..9  t1
 *  PONG          0=ver(2)  1=kind(2)  2..9  t1   10..17 t2   18..25 t3   [26 to]
 *  AUDIO_REPORT  0=ver(2)  1=kind(3)  2 sender   3..4 seq   5..12 tRx   13..20 tAudio
 *                [21..28 tNorm   29..36 tChunk1]
 * ```
 *
 * The bracketed tail is optional and a report without it is still read. It carries the two
 * receiver-side stage boundaries between `tRx` and `tAudio` — normalisation finishing and
 * the first synthesised chunk — without which three of the seven rows in the stage table
 * can never have a figure. A unit running a build that predates the tail sends the short
 * form, and the stage table simply shows those three rows as unmeasured rather than the
 * whole exchange failing over a length check.
 *
 * Every timestamp is a signed 64-bit `nanoTime` on the clock of the unit that took it,
 * big-endian. `t1` is echoed back untouched so the pinging unit needs no state between
 * ping and pong, which matters because a ping is broadcast and every unit in range
 * answers it.
 */
sealed interface Timing {
    fun encode(): ByteArray

    /** "What time is it there?" — [t1] is the sender's clock as the ping left. */
    data class Ping(val t1: Long) : Timing {
        override fun encode(): ByteArray = header(KIND_PING, 8).also { putLong(it, 2, t1) }
    }

    /**
     * The answer: [t1] echoed, [t2] the answering unit's clock when the ping arrived,
     * [t3] its clock as the pong left. The pinging unit adds its own arrival time as `t4`.
     *
     * ## Why a pong names who it answers
     *
     * A ping is broadcast and so is the pong, and [t1] is a `nanoTime` from the clock of
     * whoever pinged. With two handsets that is harmless: the only unit that hears B's
     * answer is the A that asked. With three it is not. C hears B answering A, reads A's
     * `t1` as if it were its own, and folds `t4 - t1` — a difference between two unrelated
     * clock origins — into its offset for B. The sample is not obviously malformed, so
     * [ClockSync.Sample.isUsable] may well accept it, and from then on every latency
     * figure C reports about B is wrong by an arbitrary amount.
     *
     * [to] is the node id of the unit whose ping this answers, so everyone else can drop
     * it. Null means a unit on a build that predates this field, which is read exactly as
     * before rather than discarded.
     */
    data class Pong(val t1: Long, val t2: Long, val t3: Long, val to: Int? = null) : Timing {
        init {
            require(to == null || to in 0..0xFF) { "$to is not a node id" }
        }

        /** True when this answer is for [src], or does not say. */
        fun answers(src: Int): Boolean = to == null || to == src

        override fun encode(): ByteArray =
            header(KIND_PONG, if (to == null) PONG_BODY else PONG_BODY_ADDRESSED).also {
                putLong(it, 2, t1)
                putLong(it, 10, t2)
                putLong(it, 18, t3)
                if (to != null) it[26] = to.toByte()
            }
    }

    /**
     * "Your message [seq] was heard": [rxNanos] when the frame became text on the
     * receiver, [audioNanos] when the first sound left its speaker — both on the
     * receiver's clock. [sender] names whose message it was, since the report is broadcast.
     */
    data class AudioReport(
        val sender: Int,
        val seq: Int,
        val rxNanos: Long,
        val audioNanos: Long,
        /** Normalisation finished, on the receiver's clock. Null on the short form. */
        val normNanos: Long? = null,
        /** The first synthesised chunk reached the audio device. Null on the short form. */
        val chunk1Nanos: Long? = null,
    ) : Timing {
        init {
            require(sender in 0..0xFF) { "sender $sender is not a node id" }
            require(seq in 0..0xFFFF) { "seq $seq is not a sequence number" }
        }

        /** Both stage marks or neither: half a decomposition is not one. */
        private val stages: Pair<Long, Long>? =
            if (normNanos != null && chunk1Nanos != null) normNanos to chunk1Nanos else null

        override fun encode(): ByteArray =
            header(KIND_AUDIO_REPORT, if (stages == null) AUDIO_BODY else AUDIO_BODY_STAGED).also {
                it[2] = sender.toByte()
                it[3] = (seq shr 8).toByte()
                it[4] = seq.toByte()
                putLong(it, 5, rxNanos)
                putLong(it, 13, audioNanos)
                stages?.let { (norm, chunk1) ->
                    putLong(it, 21, norm)
                    putLong(it, 29, chunk1)
                }
            }
    }

    companion object {
        /** Distinct from [Presence.VERSION], which is how the two share a frame type. */
        const val VERSION = 2

        private const val KIND_PING = 1
        private const val KIND_PONG = 2
        private const val KIND_AUDIO_REPORT = 3
        private const val HEADER = 2

        /** t1, t2, t3. The form every build since W3.10 can read. */
        private const val PONG_BODY = 24

        /** The same, plus the node id the answer is for. */
        private const val PONG_BODY_ADDRESSED = PONG_BODY + 1

        /** sender, seq, tRx, tAudio. The form every build since W3.10 can read. */
        private const val AUDIO_BODY = 19

        /** The same, plus tNorm and tChunk1. */
        private const val AUDIO_BODY_STAGED = AUDIO_BODY + 16

        /** @return null for anything this version cannot read, rather than a guess. */
        fun decode(payload: ByteArray): Timing? {
            if (payload.size < HEADER) return null
            if (payload[0].toInt() and 0xFF != VERSION) return null
            return when (payload[1].toInt() and 0xFF) {
                KIND_PING -> if (payload.size == HEADER + 8) Ping(getLong(payload, 2)) else null
                KIND_PONG ->
                    if (payload.size == HEADER + PONG_BODY || payload.size == HEADER + PONG_BODY_ADDRESSED) {
                        Pong(
                            t1 = getLong(payload, 2),
                            t2 = getLong(payload, 10),
                            t3 = getLong(payload, 18),
                            to =
                                if (payload.size == HEADER + PONG_BODY_ADDRESSED) {
                                    payload[26].toInt() and 0xFF
                                } else {
                                    null
                                },
                        )
                    } else {
                        null
                    }
                KIND_AUDIO_REPORT ->
                    if (payload.size == HEADER + AUDIO_BODY || payload.size == HEADER + AUDIO_BODY_STAGED) {
                        val staged = payload.size == HEADER + AUDIO_BODY_STAGED
                        AudioReport(
                            sender = payload[2].toInt() and 0xFF,
                            seq = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF),
                            rxNanos = getLong(payload, 5),
                            audioNanos = getLong(payload, 13),
                            normNanos = if (staged) getLong(payload, 21) else null,
                            chunk1Nanos = if (staged) getLong(payload, 29) else null,
                        )
                    } else {
                        null
                    }
                else -> null
            }
        }

        private fun header(
            kind: Int,
            bodyBytes: Int,
        ): ByteArray =
            ByteArray(HEADER + bodyBytes).also {
                it[0] = VERSION.toByte()
                it[1] = kind.toByte()
            }

        private fun putLong(
            out: ByteArray,
            at: Int,
            value: Long,
        ) {
            for (i in 0 until 8) out[at + i] = (value shr (56 - 8 * i)).toByte()
        }

        private fun getLong(
            bytes: ByteArray,
            at: Int,
        ): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
            return v
        }
    }
}

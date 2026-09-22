package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.MessageType

/**
 * What one unit has on the air, and for how long.
 *
 * ## Why a buffer and not a queue
 *
 * A BLE advertisement is not a packet. It is a chance, repeated every advertising interval,
 * and the scanner on the other handset takes only some of those chances -- measured on two
 * SM-S947B handsets, roughly one in three of a short burst. The first version put each
 * frame on the air by itself for two and a half seconds and then took it off for good, so a
 * receiver that happened to miss twenty-five chances in a row never heard that message at
 * all, and nothing told either operator. That is what "one message arrived and the next
 * one did not" looks like from the outside.
 *
 * So the advertisement now carries **everything this unit has said recently**, and stays on
 * the air all the time. A message is kept for at least [minAirMillis] -- fifty chances at
 * a hundred-millisecond interval -- and for up to [maxAirMillis] when nothing newer needs the
 * room. A receiver that hears *any* advertisement inside that window gets the whole buffer,
 * and every frame in it is delivered once: the receiver drops repeats by content, and the
 * replay window above it drops anything heard by two roads.
 *
 * ## What is pinned and what queues
 *
 * The **hello** (an unencrypted `HEARTBEAT`) and the **presence** (an encrypted one) are
 * *pinned*: only the latest of each is kept, at the head of the buffer, because an old
 * presence is not worth air and the hello is what lets a unit that has just met this one
 * open its frames in a single check. Everything else queues in order, alerts first, because
 * `docs/PROTOCOL.md` section 12 says an alert pre-empts the transmit queue.
 *
 * ## Two budgets
 *
 * [softBudget] is what fits in one radio packet. Extended advertising can carry far more,
 * but only by chaining packets, and a scanner that has to catch every packet of a chain to
 * hear any of it hears a chain far less often than it hears a single packet. The buffer
 * is filled to the soft budget and no further -- except that a single frame too large for
 * it goes on the air alone, chained, up to [hardBudget], rather than never.
 *
 * Pure JVM, no clock of its own: every call takes the time, so the policy is tested in
 * milliseconds and the transmit loop supplies real time.
 */
class OnAir(
    softBudget: Int,
    private val hardBudget: Int,
    private val minAirMillis: Long = MIN_AIR_MILLIS,
    private val maxAirMillis: Long = MAX_AIR_MILLIS,
    private val waitingCapacity: Int = MAX_WAITING,
    /**
     * This unit's node id, so that only its *own* hello and presence are pinned. A hello
     * or presence relayed for a unit two hops away queues like a message: pinned, it would
     * replace this unit's own announcement on the air, and this unit would vanish from
     * every roster in range while the far one appeared. Null pins any, for a test.
     */
    private val localSrc: Int? = null,
) {
    init {
        require(softBudget > 0) { "soft budget must be positive: $softBudget" }
        require(hardBudget >= softBudget) { "hard budget $hardBudget below soft $softBudget" }
        require(minAirMillis <= maxAirMillis) { "min air $minAirMillis beyond max $maxAirMillis" }
    }

    /**
     * What fits in one radio packet, narrowed to what the controller turns out to take.
     *
     * The figure starts at what the specification and the controller's own stated maximum
     * allow, and [narrowTo] lowers it when the radio proves otherwise. Measured on an
     * SM-E066B: any advertising data of 166 bytes or more was refused an in-place update
     * and could only go up by tearing the advertising set down and starting it again --
     * which takes the unit off the air for as long as that takes, every time the buffer
     * changes, which during a search is every second. An SM-S947B took 213 bytes without
     * complaint. So the budget is a measurement, not a constant, and it is measured per
     * handset at the only moment the answer is available.
     */
    @Volatile
    var softBudget: Int = softBudget
        private set

    /**
     * Lowers the budget to [bytes], never below [MIN_SOFT_BUDGET] and never upwards.
     *
     * @return whether the budget moved, so a caller can log it once rather than every time
     */
    @Synchronized
    fun narrowTo(bytes: Int): Boolean {
        val next = bytes.coerceAtLeast(MIN_SOFT_BUDGET)
        if (next >= softBudget) return false
        softBudget = next
        return true
    }

    /**
     * One frame on the air, with the stay it was admitted under.
     *
     * The stay is per frame rather than per buffer because the kinds want opposite things.
     * A message wants every chance it can get — a receiver that missed fifty of them still
     * hears it. A clock-sync pong wants to be heard **soon** and is worthless afterwards:
     * an answer that reaches the pinger twenty seconds late is a round trip that says
     * nothing about the path, and it spends those twenty seconds occupying room a message
     * needed. So control traffic goes on the air, is heard, and leaves.
     */
    private class Aired(
        val frame: ByteArray,
        val sinceMillis: Long,
        val minStayMillis: Long,
        val maxStayMillis: Long,
    )

    /** A frame behind the air, with the kind decided when it was offered. */
    private class Pending(val frame: ByteArray, val kind: Kind)

    private var hello: Aired? = null
    private var presence: Aired? = null
    private val aired = ArrayDeque<Aired>()
    private val waiting = ArrayDeque<Pending>()

    /** Frames refused because the waiting queue was full: an outage that cost a message. */
    var droppedWaiting: Long = 0L
        private set

    /** Frames refused because no advertisement could ever hold them. */
    var droppedOversize: Long = 0L
        private set

    /** Frames queued behind the air, for an interface that shows what is pending. */
    val waitingCount: Int get() = waiting.size

    /** Frames on the air now, pins included. */
    val airedCount: Int get() = aired.size + (if (hello != null) 1 else 0) + (if (presence != null) 1 else 0)

    /**
     * Hands a frame to the air.
     *
     * @return false if the frame was refused, or an older waiting frame was dropped to
     *   make room for it, so the caller can count the loss rather than discover it later
     */
    @Synchronized
    fun offer(
        frame: ByteArray,
        nowMillis: Long,
        announcement: Boolean = false,
    ): Boolean {
        if (frame.size > hardBudget) {
            droppedOversize++
            return false
        }
        val copy = frame.copyOf()
        val kind = kindOf(copy, announcement)
        when (kind) {
            Kind.HELLO -> {
                hello = Aired(copy, nowMillis, minAirMillis, maxAirMillis)
                return true
            }
            Kind.PRESENCE -> {
                presence = Aired(copy, nowMillis, minAirMillis, maxAirMillis)
                return true
            }
            Kind.ALERT -> waiting.addFirst(Pending(copy, kind))
            Kind.CONTROL, Kind.MESSAGE -> waiting.addLast(Pending(copy, kind))
        }
        // The oldest goes, as in the outbox and for the same reason: the newest message is
        // the one most likely to still be true. An alert is never the one dropped.
        var droppedAny = false
        while (waiting.size > waitingCapacity) {
            val victim = waiting.indexOfFirst { it.kind != Kind.ALERT }.takeIf { it >= 0 } ?: 0
            waiting.removeAt(victim)
            droppedWaiting++
            droppedAny = true
        }
        return !droppedAny
    }

    /**
     * The frames that should be on the air at [nowMillis], in the order they travel.
     *
     * Retires what has been up long enough, admits what fits, and returns the result. The
     * same call made twice at the same instant returns the same frames, so a caller can
     * compare the two and leave the radio alone when nothing changed.
     */
    @Synchronized
    fun contents(nowMillis: Long): List<ByteArray> {
        expire(nowMillis)
        makeRoom(nowMillis)
        admit(nowMillis)
        val out = ArrayList<ByteArray>(airedCount)
        hello?.let { out += it.frame }
        presence?.let { out += it.frame }
        aired.forEach { out += it.frame }
        return out
    }

    /**
     * When the contents will next change by themselves, or null if they will not.
     *
     * A retirement at the end of a frame's maximum stay, or -- while something is waiting
     * -- at the end of the oldest frame's minimum stay. A caller sleeps until then unless a
     * new frame arrives first.
     */
    @Synchronized
    fun nextChangeMillis(nowMillis: Long): Long? {
        var next: Long? = null

        fun consider(at: Long) {
            next = next?.let { minOf(it, at) } ?: at
        }
        hello?.let { consider(it.sinceMillis + it.maxStayMillis) }
        presence?.let { consider(it.sinceMillis + it.maxStayMillis) }
        aired.forEach { consider(it.sinceMillis + it.maxStayMillis) }
        if (waiting.isNotEmpty()) aired.firstOrNull()?.let { consider(it.sinceMillis + it.minStayMillis) }
        return next?.coerceAtLeast(nowMillis)
    }

    @Synchronized
    fun clear() {
        hello = null
        presence = null
        aired.clear()
        waiting.clear()
    }

    private fun expire(nowMillis: Long) {
        fun stale(entry: Aired?): Boolean = entry != null && nowMillis - entry.sinceMillis >= entry.maxStayMillis
        if (stale(hello)) hello = null
        if (stale(presence)) presence = null
        // The whole buffer, not only its head: control frames stay a fraction as long as
        // messages, so the frame that is ready to go is often not the oldest one.
        aired.removeAll { stale(it) }
    }

    /**
     * Retires the oldest frames that have had their minimum stay, but only as many as the
     * next waiting frame needs. A frame that has not had its minimum stay is never pushed
     * off by a newer one: the newer one waits, which is the whole guarantee.
     */
    private fun makeRoom(nowMillis: Long) {
        val next = waiting.firstOrNull() ?: return
        while (aired.isNotEmpty() && pinnedBytes() + airedBytes() + next.frame.size > softBudget) {
            val oldest = aired.first()
            if (nowMillis - oldest.sinceMillis < oldest.minStayMillis) return
            aired.removeFirst()
        }
    }

    private fun admit(nowMillis: Long) {
        while (waiting.isNotEmpty()) {
            val next = waiting.first()
            val fits = pinnedBytes() + airedBytes() + next.frame.size <= softBudget
            // Alone on the air with the pins, chained, rather than never: the alternative
            // is a message that waits for ever behind a budget it can never meet.
            val aloneAndOversize = aired.isEmpty() && pinnedBytes() + next.frame.size <= hardBudget
            if (!fits && !aloneAndOversize) return
            val taken = waiting.removeFirst()
            val control = taken.kind == Kind.CONTROL
            aired.addLast(
                Aired(
                    frame = taken.frame,
                    sinceMillis = nowMillis,
                    minStayMillis = if (control) CONTROL_MIN_AIR_MILLIS else minAirMillis,
                    maxStayMillis = if (control) CONTROL_AIR_MILLIS else maxAirMillis,
                ),
            )
            if (!fits) return
        }
    }

    private fun pinnedBytes(): Int = (hello?.frame?.size ?: 0) + (presence?.frame?.size ?: 0)

    private fun airedBytes(): Int = aired.sumOf { it.frame.size }

    private enum class Kind { HELLO, PRESENCE, ALERT, CONTROL, MESSAGE }

    /**
     * Which of the five kinds a frame is, from two header bytes and the sender's word.
     *
     * The type nibble of byte 1 and the `ENCRYPTED` bit of byte 4, per `docs/PROTOCOL.md`
     * section 1. The payload is not read; a link sees bytes.
     *
     * ## Why the header is not enough
     *
     * A presence and a clock-sync ping are the same frame to anything that cannot read the
     * payload: both are `HEARTBEAT`, both have `ENCRYPTED` set, both carry this unit's
     * `SRC`. Told apart by the header alone they collapse into one kind, and since that
     * kind is *pinned* — one slot, newest wins — every ping, pong and audio receipt evicted
     * the presence that belonged there and then evicted each other.
     *
     * The damage was not subtle. This unit stopped announcing itself, so every roster in
     * range emptied and the operating screen read "0 units" with the other handset on the
     * same table. A pong put in that slot was overwritten by the next control frame six
     * milliseconds later and never reached the air at all, so the four round trips the
     * clock exchange needs never completed, so no audio receipt could be converted, so no
     * end-to-end latency was ever measured. One indistinguishable pair of frames, three
     * failures that looked unrelated.
     *
     * [announcement] is the sender saying which it is. Only a frame this unit originated
     * *as its own announcement* is pinned; a relayed hello or presence belongs to another
     * unit and queues, or it would replace this unit's announcement on the air and this
     * unit would vanish from every roster while the far one appeared.
     */
    private fun kindOf(
        frame: ByteArray,
        announcement: Boolean,
    ): Kind {
        if (frame.size < Frame.HEADER_SIZE) return Kind.MESSAGE
        val type = (frame[TYPE_OFFSET].toInt() shr 4) and 0xF
        val encrypted = frame[FLAGS_OFFSET].toInt() and Flags.ENCRYPTED != 0
        val own = localSrc == null || (frame[SRC_OFFSET].toInt() and 0xFF) == localSrc
        return when {
            type != MessageType.HEARTBEAT.code ->
                if (type == MessageType.ALERT.code) Kind.ALERT else Kind.MESSAGE
            // Somebody else's announcement, relayed. Short-lived: it is news for a unit out
            // of direct range, and holding it for half a minute spends this unit's air on
            // another unit's introduction.
            !own -> Kind.CONTROL
            announcement && !encrypted -> Kind.HELLO
            announcement -> Kind.PRESENCE
            // A sealed heartbeat this unit sent that is not its announcement: a ping, a
            // pong, or an audio receipt. Wanted promptly and worthless late.
            else -> Kind.CONTROL
        }
    }

    companion object {
        /** Fifty chances at a hundred milliseconds: a scanner taking one in three misses all fifty about never. */
        const val MIN_AIR_MILLIS = 5_000L

        /** Long enough that a scanner restarting or busy elsewhere still catches up. */
        const val MAX_AIR_MILLIS = 30_000L

        /**
         * A control frame's stay: eighty chances at a hundred-millisecond interval.
         *
         * Far shorter than a message's, and deliberately. A ping, a pong and an audio
         * receipt are all answers to a question asked a moment ago; one that arrives half
         * a minute late tells the asker nothing, and the clock-sync estimator now discards
         * it anyway as a queued round trip. What it does do, held that long, is occupy
         * room in a 198-byte advertisement that a message needed — and during a two-way
         * conversation there is one receipt for every message either unit speaks.
         */
        const val CONTROL_AIR_MILLIS = 8_000L

        /** Its minimum, likewise: heard several times over, then out of the way. */
        const val CONTROL_MIN_AIR_MILLIS = 2_000L

        /** Behind the air. A flush of the outbox is the only thing that fills this. */
        const val MAX_WAITING = 64

        /**
         * However little a controller will take in place, the air holds at least this much:
         * a hello and a presence together, so a unit never stops announcing itself.
         */
        const val MIN_SOFT_BUDGET = 96

        private const val TYPE_OFFSET = 1
        private const val FLAGS_OFFSET = 4
        private const val SRC_OFFSET = 7
    }
}

/**
 * Several frames in one advertisement.
 *
 * ```
 *  byte  0       1      2       3 …
 *      +-------+------+-------+----------------------------------+
 *      | 0xB1  | SRC  | KEYID | frame, frame, … each self-sized  |
 *      +-------+------+-------+----------------------------------+
 * ```
 *
 * The first byte is not a frame's sentinel (`0xA1`), so a receiver can tell this from a
 * bare frame and still read one: every advertisement the previous version sent was a single
 * frame with nothing around it, and a unit running that version stays readable. `SRC` and
 * `KEYID` are the advertiser's own, not those of the frames -- a relayed frame carries
 * another unit's `SRC`, and the strength of an advertisement is a reading of the distance to
 * whoever is advertising it, not to whoever first said it.
 *
 * Frames are split by their own `LEN` field. A frame that does not fit the bytes that remain
 * ends the parse: what came before it is delivered, and a truncated tail is never handed on
 * as a frame.
 */
object AirBlob {
    /** Not `0xA`: a frame's sentinel occupies that nibble. */
    const val MAGIC = 0xB1

    const val HEADER_BYTES = 3

    /** Says the advertiser is unknown; a receiver emits no signal reading for it. */
    const val UNKNOWN = 0xFF

    class Parsed(val src: Int?, val keyId: Int?, val frames: List<ByteArray>)

    fun encode(
        src: Int,
        keyId: Int,
        frames: List<ByteArray>,
    ): ByteArray {
        require(src in 0..0xFF) { "src must fit 8 bits: $src" }
        require(keyId in 0..0xFF) { "keyId must fit 8 bits: $keyId" }
        val out = ByteArray(HEADER_BYTES + frames.sumOf { it.size })
        out[0] = MAGIC.toByte()
        out[1] = src.toByte()
        out[2] = keyId.toByte()
        var at = HEADER_BYTES
        for (frame in frames) {
            frame.copyInto(out, at)
            at += frame.size
        }
        return out
    }

    fun decode(bytes: ByteArray): Parsed {
        if (bytes.isEmpty()) return Parsed(null, null, emptyList())
        if ((bytes[0].toInt() and 0xFF) != MAGIC) {
            // A bare frame, as the previous version advertised. Its own header says who
            // sent it, which for a bare frame is also who advertised it.
            val frames = split(bytes, 0)
            val src = if (bytes.size >= Frame.HEADER_SIZE) bytes[SRC_OFFSET].toInt() and 0xFF else null
            val keyId = if (bytes.size >= Frame.HEADER_SIZE) bytes[KEYID_OFFSET].toInt() and 0xFF else null
            return Parsed(src, keyId, frames)
        }
        if (bytes.size < HEADER_BYTES) return Parsed(null, null, emptyList())
        val src = (bytes[1].toInt() and 0xFF).takeIf { it != UNKNOWN }
        val keyId = (bytes[2].toInt() and 0xFF).takeIf { it != UNKNOWN }
        return Parsed(src, keyId, split(bytes, HEADER_BYTES))
    }

    private fun split(
        bytes: ByteArray,
        from: Int,
    ): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var at = from
        while (at + Frame.HEADER_SIZE + Frame.CRC_SIZE <= bytes.size) {
            if (((bytes[at].toInt() shr 4) and 0xF) != Frame.MAGIC) break
            val len = ((bytes[at + LEN_OFFSET].toInt() and 0xFF) shl 8) or (bytes[at + LEN_OFFSET + 1].toInt() and 0xFF)
            if (len > Frame.MAX_PAYLOAD) break
            val size = Frame.HEADER_SIZE + len + Frame.CRC_SIZE
            if (at + size > bytes.size) break
            frames += bytes.copyOfRange(at, at + size)
            at += size
        }
        return frames
    }

    /** Header offsets, per `docs/PROTOCOL.md` section 1: `LEN` follows the flags byte. */
    private const val LEN_OFFSET = 5
    private const val SRC_OFFSET = 7
    private const val KEYID_OFFSET = 8
}

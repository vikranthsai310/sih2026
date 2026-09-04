package org.itantra.link

import org.itantra.proto.Aead
import org.itantra.proto.AuthFailureLimiter
import org.itantra.proto.DecodeResult
import org.itantra.proto.EpochCounter
import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.RejectReason
import org.itantra.proto.ReplayWindow
import org.itantra.proto.ScriptPacker
import org.itantra.proto.TemplateTable
import org.itantra.proto.TransportClass

/**
 * The path a message actually takes. Tasks **W3.9**, **W2.16**, **W2.18**.
 *
 * ## Why this class had to be written
 *
 * Every piece below this line was built and tested weeks ago, and none of them were joined
 * to anything. `EpochCounter.start()` had no caller. `TransportClass.tagBytesFor` had no
 * caller. There was no code path anywhere that took a sentence and produced bytes, or took
 * bytes and produced a sentence — the components were correct and the system did not
 * exist.
 *
 * That is a specific kind of project failure and it is worth naming: a suite of green
 * tests over parts that never meet. The integration defects — an epoch that never
 * advances, a tag length nobody selects, a replay window nothing consults — are invisible
 * to unit tests of the parts, and they are exactly the defects that matter, because each
 * one silently removes a security property the documents claim.
 *
 * ## The send path
 *
 * ```
 *   text ─► template match ─► or script pack ─► frame ─► seal ─► fragment ─► link
 * ```
 *
 * ## The receive path — `docs/TODO.md` W3.9, in order
 *
 * ```
 *   link ─► decode ─► CRC ─► KEYID filter ─► AEAD open ─► replay check
 *        ─► reassemble ─► unpack or render template ─► out
 * ```
 *
 * The order is not arbitrary and each step is cheaper than the one after it. CRC before
 * AEAD, because verifying a tag on a frame the CRC already rejected is wasted work on a
 * noisy link. `KEYID` before AEAD, because a frame from an unpaired transmitter should be
 * dropped without running the cipher at all. Replay **after** AEAD, because admitting an
 * unauthenticated frame into the window would let anyone poison it.
 *
 * ## Two things this class does not do
 *
 * It does not touch the microphone or the speaker: it takes text and produces text, which
 * is what makes the whole path testable without a model or a handset. And it does not own
 * the link — [Session] is given one, so the same session runs over Bluetooth, Wi-Fi or a
 * loopback in a test with nothing changed but the tag length the transport class implies.
 */
class Session(
    private val link: Link,
    private val key: ByteArray,
    private val localSrc: Int,
    private val epochs: EpochCounter,
    private val templates: TemplateTable,
    val transport: TransportClass = TransportClass.of(link.name),
    private val language: Language = Language.HINDI,
    private val relay: Relay = Relay(localSrc),
    private val replay: ReplayWindow = ReplayWindow(),
    private val limiter: AuthFailureLimiter = AuthFailureLimiter(),
    private val fragmenter: Fragmenter = Fragmenter(link.mtu),
    private val reassembler: Reassembler = Reassembler(),
    private val outbox: Outbox = Outbox(),
) {
    init {
        require(key.size == Aead.KEY_BYTES) { "key must be ${Aead.KEY_BYTES} bytes" }
        require(localSrc in 0..0xFF) { "src must fit 8 bits: $localSrc" }
    }

    /**
     * The tag length this session uses, chosen by the transport rather than by a caller.
     * Task **W2.18**.
     */
    val tagBytes: Int = TransportClass.tagBytesFor(transport, rateLimited = true)

    private val keyId = Aead.keyId(key)

    /**
     * Advanced on every service start and every `SEQ` wrap. Task **W2.16**.
     *
     * Read once here rather than per frame: [EpochCounter.start] persists before it
     * returns, and calling it twice would waste an epoch on every message.
     */
    private var epoch: Long = epochs.start()

    private var seq: Int = 0

    /** What the last send produced, for the byte counter the demonstration points at. */
    var lastWireBytes: Int = 0
        private set

    var lastWasTemplate: Boolean = false
        private set

    /** Frames held because the link was down. */
    val queuedCount: Int get() = outbox.size

    // ── send ─────────────────────────────────────────────────────────────────

    /**
     * Sends [text], choosing the smallest representation that survives.
     *
     * @param confident the recogniser's own confidence. A template code is only accepted
     *   when the match is good **and** the recogniser was confident — both, because a
     *   confident recognition of the wrong sentence and a hesitant recognition of the
     *   right one are equally unsafe.
     * @return what was actually put on the wire, so a caller can log it without
     *   re-deriving it
     */
    suspend fun send(
        text: String,
        confident: Boolean = true,
        type: MessageType = MessageType.TEXT,
        nowMillis: Long = 0,
    ): Sent {
        val templateId = templates.match(text, language, confident)
        val payload =
            when {
                templateId != null -> byteArrayOf(templateId.toByte())
                ScriptPacker.isWorthPacking(text, language) -> ScriptPacker.pack(text, language)
                else -> text.toByteArray(Charsets.UTF_8)
            }

        val flags =
            Flags.FINAL or
                Flags.ENCRYPTED or
                when {
                    templateId != null -> Flags.TEMPLATE
                    ScriptPacker.isWorthPacking(text, language) -> Flags.PACKED
                    else -> 0
                }

        val frame = nextFrame(type, flags, payload)
        val sealedFrame = seal(frame)
        val pieces =
            if (fragmenter.needsFragmenting(sealedFrame)) {
                fragmenter.fragment(sealedFrame)
            } else {
                listOf(sealedFrame)
            }

        var bytes = 0
        for (piece in pieces) {
            val wire = piece.encode()
            bytes += wire.size
            // Held rather than dropped when the link is down. The operator is told by the
            // queue depth; a message that vanishes silently is the failure this avoids.
            if (link.state.value == LinkState.CONNECTED) {
                link.send(wire)
            } else {
                outbox.offer(wire, nowMillis)
            }
        }

        lastWireBytes = bytes
        lastWasTemplate = templateId != null
        return Sent(
            seq = frame.seq,
            wireBytes = bytes,
            templateId = templateId,
            packed = frame.isPacked,
            fragments = pieces.size,
            queued = link.state.value != LinkState.CONNECTED,
        )
    }

    data class Sent(
        val seq: Int,
        val wireBytes: Int,
        val templateId: Int?,
        val packed: Boolean,
        val fragments: Int,
        val queued: Boolean,
    ) {
        /** The figure band F shows, against three seconds of raw audio. */
        val compressionRatio: Double get() = RAW_AUDIO_BYTES / wireBytes.toDouble()

        private companion object {
            const val RAW_AUDIO_BYTES = 96_000.0
        }
    }

    /** Flushes anything held while the link was down, in the order it was queued. */
    suspend fun flushOutbox(nowMillis: Long = 0): Int {
        if (link.state.value != LinkState.CONNECTED) return 0
        var sent = 0
        for (entry in outbox.drain(nowMillis)) {
            link.send(entry.wire)
            sent++
        }
        return sent
    }

    /**
     * Builds the next frame, advancing the epoch **before** a `SEQ` wrap rather than
     * after. Task **W2.16**, risk **S-07**.
     *
     * After would be too late: the wrapping frame would be sent under the old epoch with a
     * sequence number already used, and that is nonce reuse — which does not degrade GCM,
     * it destroys it.
     */
    private fun nextFrame(
        type: MessageType,
        flags: Int,
        payload: ByteArray,
    ): Frame {
        if (epochs.willWrap(seq)) {
            epoch = epochs.onSequenceWrap()
            seq = 0
        }
        return Frame(
            type = type,
            language = language,
            seq = seq++,
            flags = flags,
            src = localSrc,
            keyId = keyId,
            ttl = DEFAULT_TTL,
            payload = payload,
        )
    }

    /**
     * Seals the payload against the frame's own header.
     *
     * The header is built with the **sealed** length, because the tag is part of what goes
     * on the wire and `LEN` has to describe what a receiver will actually read. Building
     * the associated data from the pre-seal header would produce a frame that never
     * verifies, and it would fail identically to a wrong key — which is a very expensive
     * afternoon.
     */
    private fun seal(frame: Frame): Frame {
        val withFinalLength = frame.copy(payload = ByteArray(frame.payload.size + tagBytes))
        val header = withFinalLength.encode().copyOf(Frame.HEADER_SIZE)
        val sealed =
            Aead.seal(
                key = key,
                header = header,
                plaintext = frame.payload,
                epoch = epoch,
                src = localSrc,
                seq = frame.seq,
                tagBytes = tagBytes,
            )
        return frame.copy(payload = sealed)
    }

    // ── receive ──────────────────────────────────────────────────────────────

    /** What came off the wire, or why it did not. */
    sealed interface Received {
        data class Message(
            val from: Int,
            val text: String,
            val frame: Frame,
            val wasTemplate: Boolean,
            val wireBytes: Int,
        ) : Received

        /** A fragment landed and the message is not complete yet. */
        data class Partial(val have: Int, val of: Int) : Received

        data class Dropped(val reason: Reason, val detail: String = "") : Received

        /** Rebroadcast for a unit out of direct range, after [delayMillis]. */
        data class Relayed(val frame: Frame, val delayMillis: Long) : Received
    }

    enum class Reason {
        MALFORMED,
        WRONG_KEY,
        NOT_AUTHENTIC,
        REPLAYED,
        UNDER_ATTACK,
        NOT_FOR_US,
        UNREADABLE,
    }

    /**
     * Runs one received frame through the whole receive path.
     *
     * @param nowMillis a clock for the reassembly timeout and the failure limiter
     */
    fun receive(
        wire: ByteArray,
        nowMillis: Long = 0,
    ): Received {
        // 1. decode and CRC — both inside Frame.decode, and both before anything costly.
        val decoded = Frame.decode(wire)
        val frame =
            when (decoded) {
                is DecodeResult.Ok -> decoded.frame
                is DecodeResult.Rejected -> return Received.Dropped(malformed(decoded.reason), decoded.reason.name)
            }

        // 2. KEYID: a cheap reject for a transmitter we are not paired with. Not a
        //    security control -- a collision costs one wasted verification -- but it keeps
        //    the cipher off frames that were never ours.
        if (frame.keyId != keyId) return Received.Dropped(Reason.WRONG_KEY, "keyId ${frame.keyId}")

        // 3. our own frame heard back from a relaying peer.
        if (frame.src == localSrc) return Received.Dropped(Reason.NOT_FOR_US, "own transmission")

        // 4. AEAD. The epoch is the one this sender advertised in its HEARTBEAT; without
        //    it a frame from after a SEQ wrap would be opened against the wrong nonce.
        val senderEpoch = replay.epochOf(frame.src) ?: frame.let { peerEpoch(it) }
        val opened = openFrame(frame, senderEpoch)
        if (opened == null) {
            val underAttack = limiter.recordFailure(frame.src, nowMillis)
            return Received.Dropped(
                if (underAttack) Reason.UNDER_ATTACK else Reason.NOT_AUTHENTIC,
                "src ${frame.src}",
            )
        }

        // 5. replay, only now that the frame is known to be authentic. Admitting an
        //    unauthenticated frame into the window would let anyone poison it by
        //    replaying a sequence number the real sender has not reached.
        if (!replay.admit(frame.src, senderEpoch, frame.seq)) {
            return Received.Dropped(Reason.REPLAYED, "src ${frame.src} seq ${frame.seq}")
        }

        // 6. relay, before reassembly: a unit out of range needs the fragment, not the
        //    message, and holding it until the whole thing arrives would add a hop's
        //    delay to every fragment after the first.
        val decision = relay.consider(opened, senderEpoch, nowMillis)
        if (decision is Relay.Decision.Forward) {
            // Not returned as the result: this unit both relays the frame and reads it.
            pendingRelay = Received.Relayed(decision.frame, decision.delayMillis)
        }

        // 7. reassembly.
        val whole =
            if (opened.isFragment) {
                when (val result = reassembler.offer(opened, nowMillis)) {
                    is Reassembler.Result.Complete -> result.frame
                    is Reassembler.Result.Incomplete -> return Received.Partial(result.have, result.of)
                    is Reassembler.Result.Rejected -> return Received.Dropped(Reason.MALFORMED, result.reason)
                }
            } else {
                opened
            }

        // 8. template or script packing, back to text.
        val text =
            when {
                whole.isTemplate -> {
                    val id = whole.payload.firstOrNull()?.toInt()?.and(0xFF)
                    // Rendered in *this* unit's language, which is the cross-language
                    // property: the sender chose the byte, the receiver chooses the words.
                    id?.let { templates.render(it, language) }
                        ?: return Received.Dropped(Reason.UNREADABLE, "template $id absent")
                }
                whole.isPacked -> ScriptPacker.unpack(whole.payload, whole.language)
                else -> String(whole.payload, Charsets.UTF_8)
            }

        return Received.Message(
            from = whole.src,
            text = text,
            frame = whole,
            wasTemplate = whole.isTemplate,
            wireBytes = wire.size,
        )
    }

    /**
     * A relay decision produced by the last [receive], or null.
     *
     * Kept aside rather than returned because a frame is both read and relayed, and a
     * single return value would force the caller to choose. Read once and cleared, so a
     * caller that forgets to check does not rebroadcast a stale frame later.
     */
    private var pendingRelay: Received.Relayed? = null

    fun takeRelay(): Received.Relayed? = pendingRelay.also { pendingRelay = null }

    private fun openFrame(
        frame: Frame,
        senderEpoch: Long,
    ): Frame? {
        if (!frame.isEncrypted) {
            // An unauthenticated frame is not opened and not accepted. The UNSECURED
            // banner exists for a session that was paired without a key at all, which is
            // a different state from a frame arriving without the flag on a keyed link.
            return null
        }
        val header = frame.encode().copyOf(Frame.HEADER_SIZE)
        val plain =
            Aead.open(
                key = key,
                header = header,
                sealed = frame.payload,
                epoch = senderEpoch,
                src = frame.src,
                seq = frame.seq,
                tagBytes = tagBytes,
            ) ?: return null
        return frame.copy(
            flags = frame.flags and Flags.ENCRYPTED.inv(),
            payload = plain,
        )
    }

    /** Until a `HEARTBEAT` has been seen, assume the sender is in the epoch we are. */
    private fun peerEpoch(frame: Frame): Long = epoch

    private fun malformed(reason: RejectReason): Reason =
        when (reason) {
            RejectReason.BAD_CRC -> Reason.MALFORMED
            else -> Reason.MALFORMED
        }

    private companion object {
        /** `docs/PROTOCOL.md` section 1: three hops. */
        const val DEFAULT_TTL = 3
    }
}

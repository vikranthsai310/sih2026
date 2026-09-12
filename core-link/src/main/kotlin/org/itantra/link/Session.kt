package org.itantra.link

import org.itantra.proto.Aead
import org.itantra.proto.AuthFailureLimiter
import org.itantra.proto.DecodeResult
import org.itantra.proto.EpochCounter
import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.Locate
import org.itantra.proto.MessageType
import org.itantra.proto.Presence
import org.itantra.proto.Timing
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
 *   link ─► decode ─► CRC ─► KEYID filter ─► reassemble ─► AEAD open ─► replay check
 *        ─► relay ─► unpack or render template ─► out
 * ```
 *
 * The order is not arbitrary and each step is cheaper than the one after it. CRC before
 * AEAD, because verifying a tag on a frame the CRC already rejected is wasted work on a
 * noisy link. `KEYID` before AEAD, because a frame from an unpaired transmitter should be
 * dropped without running the cipher at all. **Reassembly before AEAD**, because a
 * fragment is a slice of ciphertext with no tag of its own; the first version opened each
 * fragment and refused every one, so a message long enough to fragment could never
 * arrive. Replay **after** AEAD, because admitting an unauthenticated frame into the window
 * would let anyone poison it. Relay after replay, so only frames proven genuine and fresh
 * are ever put back on the air.
 *
 * ## Three things a receiver has to work out for itself
 *
 * **The sender's epoch** is nowhere on the wire (see [resolveEpoch]). **The TTL** is the one
 * header byte a relay changes, so it is left out of the associated data — a relayed frame
 * still verifies, and a relay cannot alter anything else. **The link's MTU** changes as
 * peers come and go, so the fragmenter is rebuilt when it does rather than sized once at
 * construction for a mesh with no peers in it yet.
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
    /**
     * The language this unit speaks and renders in. Set by the operator, so it is a
     * property rather than a constructor constant: pinned at construction, a Tamil
     * sentence was matched against the Hindi template table, missed, and went as raw
     * UTF-8 in a frame labelled Hindi.
     */
    var language: Language = Language.HINDI,
    private val relay: Relay = Relay(localSrc),
    private val replay: ReplayWindow = ReplayWindow(),
    private val limiter: AuthFailureLimiter = AuthFailureLimiter(),
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

    /** Sized to the link as it is now. See [fragmenter]. */
    private var fragmenter: Fragmenter? = null

    /**
     * How many relay hops a frame this unit originates may take. `docs/PROTOCOL.md`
     * section 1: decremented by every relay, dropped at zero.
     *
     * Set by the operator, within [MIN_TTL]..[MAX_TTL]. Zero means a message reaches only
     * units in direct range and is never rebroadcast, which is a legitimate thing to want
     * on a crowded channel. It bounds what **this** unit sends; a frame passing through is
     * decremented from whatever its sender chose, and this value has no say in that.
     */
    var ttl: Int = DEFAULT_TTL
        set(value) {
            field = value.coerceIn(MIN_TTL, MAX_TTL)
        }

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
        val language = language
        val templateId = templates.match(text, language, confident)
        val packed = templateId == null && ScriptPacker.isWorthPacking(text, language)
        val payload =
            when {
                templateId != null -> byteArrayOf(templateId.toByte())
                packed -> ScriptPacker.pack(text, language)
                else -> text.toByteArray(Charsets.UTF_8)
            }

        val flags =
            Flags.FINAL or
                Flags.ENCRYPTED or
                when {
                    templateId != null -> Flags.TEMPLATE
                    packed -> Flags.PACKED
                    else -> 0
                }

        val frame = nextFrame(type, flags, payload, language)
        val sealedFrame = seal(frame)
        val fragmenter = fragmenter()
        val pieces =
            if (fragmenter.needsFragmenting(sealedFrame)) {
                fragmenter.fragment(sealedFrame)
            } else {
                listOf(sealedFrame)
            }

        // Our own frame, remembered so that a relaying peer handing it back is not relayed
        // onward again -- and so that a frame dropped as "own transmission" can be told
        // from a frame from another unit that happens to share this node id.
        relay.remember(localSrc, epoch, frame.seq)

        // An alert goes down every road the mesh has up, whatever the operator switched
        // off for ordinary traffic. See Link.send.
        val urgent = type == MessageType.ALERT
        var bytes = 0
        for (piece in pieces) {
            val wire = piece.encode()
            bytes += wire.size
            // Held rather than dropped when the link is down. The operator is told by the
            // queue depth; a message that vanishes silently is the failure this avoids.
            if (link.state.value == LinkState.CONNECTED) {
                link.send(wire, urgent)
            } else {
                outbox.offer(wire, nowMillis, urgent)
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

    /**
     * Says who this unit is, and where, to everyone in range. Not queued: a presence held
     * until the link comes back would be stale when it arrived, and the next one is seconds
     * away.
     *
     * @return whether a frame left
     */
    suspend fun sendPresence(presence: Presence): Boolean =
        sendControl(MessageType.HEARTBEAT, presence.encode(), queue = false)

    /**
     * A clock-sync ping or pong, or the receipt that says when a message was heard. Never
     * queued: a timestamp held until the link comes back measures the outage, not the path.
     * `docs/EVALUATION.md` section 4, task **W3.10**.
     *
     * @return whether a frame left
     */
    suspend fun sendTiming(timing: Timing): Boolean =
        sendControl(MessageType.HEARTBEAT, timing.encode(), queue = false)

    /** Asks [Locate.target] to beacon for this unit, or to stop. Queued if the link is down. */
    suspend fun sendLocate(
        locate: Locate,
        nowMillis: Long = 0,
    ): Boolean = sendControl(MessageType.POSITION, locate.encode(), queue = true, nowMillis = nowMillis)

    private suspend fun sendControl(
        type: MessageType,
        payload: ByteArray,
        queue: Boolean,
        nowMillis: Long = 0,
    ): Boolean {
        val frame = nextFrame(type, Flags.FINAL or Flags.ENCRYPTED, payload, language)
        val wire = seal(frame).encode()
        relay.remember(localSrc, epoch, frame.seq)
        return if (link.state.value == LinkState.CONNECTED) {
            link.send(wire)
            true
        } else {
            if (queue) outbox.offer(wire, nowMillis)
            false
        }
    }

    /** Flushes anything held while the link was down, in the order it was queued. */
    suspend fun flushOutbox(nowMillis: Long = 0): Int {
        if (link.state.value != LinkState.CONNECTED) return 0
        var sent = 0
        for (entry in outbox.drain(nowMillis)) {
            link.send(entry.wire, entry.urgent)
            sent++
        }
        return sent
    }

    /**
     * The fragmenter for the link's MTU **as it is now**.
     *
     * A mesh has no peers when the session is built and reports a default MTU; the real
     * one is whatever the narrowest peer reports once it joins. Sized once at
     * construction, a frame that fitted the default was refused by a narrower link and
     * silently never sent.
     */
    private fun fragmenter(): Fragmenter {
        val mtu = link.mtu.coerceAtLeast(MIN_FRAGMENT_MTU)
        val current = fragmenter
        if (current != null && current.mtu == mtu) return current
        return Fragmenter(mtu).also { fragmenter = it }
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
        language: Language,
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
            ttl = ttl,
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
        val sealed =
            Aead.seal(
                key = key,
                header = associatedData(withFinalLength),
                plaintext = frame.payload,
                epoch = epoch,
                src = localSrc,
                seq = frame.seq,
                tagBytes = tagBytes,
            )
        return frame.copy(payload = sealed)
    }

    /**
     * The header as the tag binds it: every byte but the TTL.
     *
     * The TTL is the one field a relay legitimately changes, and a relay does not hold
     * the key — it forwards what it heard. Binding the TTL made every relayed frame fail
     * verification at the next hop. Everything else in the header — `SRC`, `TYPE`, `SEQ`,
     * `FLAGS`, `LEN`, `KEYID`, language — stays bound, so nothing a relay could alter
     * changes what the frame means. `docs/PROTOCOL.md` section 6.1.
     */
    private fun associatedData(frame: Frame): ByteArray =
        frame.encode().copyOf(Frame.HEADER_SIZE).also { it[TTL_OFFSET] = 0 }

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

        /** A unit announcing the epoch it is on. Unauthenticated, and used only as a hint. */
        data class Hello(val from: Int, val epoch: Long) : Received

        /** A unit saying who it is and, while it is being looked for, where. Authenticated. */
        data class Presence(val from: Int, val presence: org.itantra.proto.Presence) : Received

        /** A unit asking [Locate.target] to beacon, or to stop. Authenticated, and relayed. */
        data class Locate(val from: Int, val locate: org.itantra.proto.Locate) : Received

        /** A clock-sync ping or pong, or an audio receipt. Authenticated, never relayed. */
        data class Timing(val from: Int, val timing: org.itantra.proto.Timing) : Received
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

        // 3. our own frame heard back from a relaying peer -- or, if this unit never sent
        //    it, another unit that drew the same node id, which is worth saying.
        if (frame.src == localSrc) {
            // A hello or a presence is not remembered by the relay -- neither is relayed --
            // and a Wi-Fi router echoes every broadcast back to its sender, so this unit's
            // own hello arrives here every five seconds. Only a message this unit never
            // sent is evidence of another unit on the same node id.
            val ours = frame.type == MessageType.HEARTBEAT || relay.hasSeen(localSrc, epoch, frame.seq)
            return Received.Dropped(
                Reason.NOT_FOR_US,
                if (ours) "own transmission" else "another unit is using this node id",
            )
        }

        // 3a. a hello: the epoch a unit says it is on, in the clear. Not believed -- every
        //     frame still has to open under that epoch -- but tried first, which turns
        //     the search after a restart or a reinstall from thousands of tag checks
        //     into one.
        if (frame.type == MessageType.HEARTBEAT && !frame.isEncrypted) {
            val hinted = helloEpoch(frame.payload) ?: return Received.Dropped(Reason.MALFORMED, "hello")
            hints[frame.src] = hinted
            relayHello(frame, hinted, nowMillis)
            return Received.Hello(frame.src, hinted)
        }

        // 4. reassembly, on the sealed bytes. A fragment carries a slice of ciphertext and
        //    no tag; there is nothing in it to authenticate until the whole is back.
        val sealed: Frame
        val fragments: List<Frame>
        if (frame.isFragment) {
            val held = holdFragment(frame)
            when (val result = reassembler.offer(frame, nowMillis)) {
                is Reassembler.Result.Complete -> {
                    sealed = result.frame
                    fragments = held
                    heldFragments.remove(fragmentKey(frame))
                }
                is Reassembler.Result.Incomplete -> return Received.Partial(result.have, result.of)
                is Reassembler.Result.Rejected -> {
                    heldFragments.remove(fragmentKey(frame))
                    return Received.Dropped(Reason.MALFORMED, result.reason)
                }
            }
        } else {
            sealed = frame
            fragments = emptyList()
        }

        // 5. AEAD. The nonce is EPOCH-SRC-SEQ and the epoch is the **sender's**, which is
        //    nowhere on the wire. See resolveEpoch.
        val resolved = resolveEpoch(sealed, nowMillis)
        if (resolved == null) {
            val underAttack = limiter.recordFailure(frame.src, nowMillis)
            return Received.Dropped(
                if (underAttack) Reason.UNDER_ATTACK else Reason.NOT_AUTHENTIC,
                "src ${frame.src}",
            )
        }
        val (senderEpoch, opened) = resolved

        // 6. replay, only now that the frame is known to be authentic. Admitting an
        //    unauthenticated frame into the window would let anyone poison it by
        //    replaying a sequence number the real sender has not reached.
        if (!replay.admit(frame.src, senderEpoch, frame.seq)) {
            return Received.Dropped(Reason.REPLAYED, "src ${frame.src} seq ${frame.seq}")
        }

        // 6a. presence and locate requests. Authenticated and fresh by now, and neither is
        //     text: they leave the path here. A presence is relayed when it is news (see
        //     relayPresence); a locate request always is, so it reaches a unit three hops
        //     away.
        when (opened.type) {
            MessageType.HEARTBEAT -> {
                // A sealed heartbeat is a presence or a timing payload; the first byte says
                // which (Presence.VERSION is 1, Timing.VERSION is 2). Neither is relayed as
                // a message, and only a presence is rebroadcast at all.
                Presence.decode(opened.payload)?.let { presence ->
                    relayPresence(sealed, opened.payload, senderEpoch, nowMillis)
                    return Received.Presence(opened.src, presence)
                }
                val timing = Timing.decode(opened.payload) ?: return Received.Dropped(Reason.MALFORMED, "presence")
                return Received.Timing(opened.src, timing)
            }
            MessageType.POSITION -> {
                forward(relay.consider(sealed, senderEpoch, nowMillis))
                return Locate.decode(opened.payload)?.let { Received.Locate(opened.src, it) }
                    ?: Received.Dropped(Reason.MALFORMED, "locate")
            }
            else -> Unit
        }

        // 7. relay: the frame **as it arrived**, sealed, so the next hop can verify it. A
        //    fragmented message is relayed as its fragments, each under its own key, once
        //    the whole has proved genuine.
        if (fragments.isEmpty()) {
            forward(relay.consider(sealed, senderEpoch, nowMillis))
        } else {
            for (fragment in fragments) {
                val index = fragment.payload.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                forward(relay.consider(fragment, senderEpoch, nowMillis, fragment = index))
            }
        }

        // 8. template or script packing, back to text.
        val text =
            when {
                opened.isTemplate -> {
                    val id = opened.payload.firstOrNull()?.toInt()?.and(0xFF)
                    // Rendered in *this* unit's language, which is the cross-language
                    // property: the sender chose the byte, the receiver chooses the words.
                    id?.let { templates.render(it, language) }
                        ?: return Received.Dropped(Reason.UNREADABLE, "template $id absent")
                }
                opened.isPacked ->
                    runCatching { ScriptPacker.unpack(opened.payload, opened.language) }
                        .getOrElse { return Received.Dropped(Reason.UNREADABLE, "packing: ${it.message}") }
                else -> String(opened.payload, Charsets.UTF_8)
            }

        return Received.Message(
            from = opened.src,
            text = text,
            frame = opened,
            wasTemplate = opened.isTemplate,
            wireBytes = wire.size,
        )
    }

    /**
     * Relay decisions produced by the last [receive].
     *
     * Kept aside rather than returned because a frame is both read and relayed, and a
     * single return value would force the caller to choose. Read once and cleared, so a
     * caller that forgets to check does not rebroadcast a stale frame later.
     */
    private val pendingRelays = ArrayList<Received.Relayed>()

    fun takeRelays(): List<Received.Relayed> = pendingRelays.toList().also { pendingRelays.clear() }

    /** The first pending relay, for a caller that only expects one. */
    fun takeRelay(): Received.Relayed? = takeRelays().firstOrNull()

    private fun forward(decision: Relay.Decision) {
        if (decision is Relay.Decision.Forward) {
            pendingRelays += Received.Relayed(decision.frame, decision.delayMillis)
        }
    }

    /** Epochs units have announced for themselves. Hints, never verdicts. */
    private val hints = HashMap<Int, Long>()

    /** When a hello from each unit was last relayed, so a flood of them is not amplified. */
    private val helloRelayedAt = HashMap<Int, Long>()

    /** The last presence relayed for each unit: a hash of its payload, and when. */
    private val presenceRelayed = HashMap<Int, Pair<Int, Long>>()

    /**
     * Relays a hello that is news: the first time this unit hears a given epoch from a
     * given sender.
     *
     * ## Why a hello has to travel
     *
     * A unit two hops away never hears the sender directly, so it never hears the
     * sender's hello, and the hello is the one thing that tells it which epoch the
     * sender's frames are sealed under. Without it the far unit falls back on the search
     * in [resolveEpoch], which reaches about three days either side of its own epoch and
     * no further: two handsets first set up in different weeks, out of each other's range,
     * could never read each other however many units stood between them. The relayed
     * frames arrived, verified under nothing, and were counted as an attack.
     *
     * ## Why once, and rate-limited
     *
     * The seen-set keys the hello on `(SRC, EPOCH)` -- its `SEQ` is always zero -- so each
     * unit relays each epoch it hears of once, however many times the sender repeats it
     * and however many roads bring it back. A hello is not authenticated, so a forger
     * could announce a new epoch with every frame and have every unit relay every one;
     * [HELLO_RELAY_MIN_MILLIS] caps that at the hello's own cadence, per sender, and the
     * TTL bounds how far any of it goes.
     */
    private fun relayHello(
        frame: Frame,
        hinted: Long,
        nowMillis: Long,
    ) {
        if (frame.ttl <= 0) return
        val last = helloRelayedAt[frame.src]
        if (last != null && nowMillis - last < HELLO_RELAY_MIN_MILLIS) return
        val decision = relay.considerControl(frame, hinted, nowMillis, slot = Relay.HELLO_SLOT)
        if (decision is Relay.Decision.Forward) helloRelayedAt[frame.src] = nowMillis
        forward(decision)
    }

    /**
     * Relays a presence that is news: a name or position this unit has not relayed for
     * that sender before, or the same one again after [PRESENCE_RELAY_REFRESH_MILLIS].
     *
     * A unit reached only through a relay would otherwise be a node number on the far
     * unit's screen and absent from its roster: the name travels in the presence, and the
     * roster counts a unit present for thirty-five seconds after its last authenticated
     * frame. The refresh keeps a quiet far unit present; the change rule keeps a unit
     * beaconing its position for a locator moving on that locator's screen. Never more
     * often than [PRESENCE_RELAY_MIN_MILLIS] per sender, whatever changes: air is shared.
     *
     * The signal reading a receiver takes from a presence comes from the radio, not the
     * frame, so a relayed presence carries no false distance.
     */
    private fun relayPresence(
        sealed: Frame,
        payload: ByteArray,
        senderEpoch: Long,
        nowMillis: Long,
    ) {
        if (sealed.ttl <= 0) return
        val digest = payload.contentHashCode()
        val last = presenceRelayed[sealed.src]
        if (last != null) {
            val (lastDigest, at) = last
            if (nowMillis - at < PRESENCE_RELAY_MIN_MILLIS) return
            if (lastDigest == digest && nowMillis - at < PRESENCE_RELAY_REFRESH_MILLIS) return
        }
        val decision = relay.considerControl(sealed, senderEpoch, nowMillis, slot = Relay.PRESENCE_SLOT)
        if (decision is Relay.Decision.Forward) presenceRelayed[sealed.src] = digest to nowMillis
        forward(decision)
    }

    /**
     * This unit's own announcement: its node id and current epoch, in the clear.
     *
     * `docs/PROTOCOL.md` section 9 wanted the heartbeat sealed like everything else, and
     * sealed with the sender's epoch it cannot tell a receiver what that epoch is -- which
     * is the one thing a receiver cannot otherwise know. So the announcement is not
     * sealed, carries nothing but the epoch, and is trusted for nothing: a receiver uses
     * it as the first candidate in a search whose every step is an AEAD check. A forged
     * hello costs the receiver one wasted tag check.
     *
     * It carries the operator's hop count, like every other frame this unit originates,
     * so that a unit two hops away learns the epoch too; see [relayHello].
     */
    fun hello(): ByteArray =
        Frame(
            type = MessageType.HEARTBEAT,
            language = language,
            seq = 0,
            flags = Flags.FINAL,
            src = localSrc,
            keyId = keyId,
            ttl = ttl,
            payload =
                byteArrayOf(
                    (epoch shr 24).toByte(),
                    (epoch shr 16).toByte(),
                    (epoch shr 8).toByte(),
                    epoch.toByte(),
                ),
        ).encode()

    private fun helloEpoch(payload: ByteArray): Long? {
        if (payload.size < 4) return null
        return ((payload[0].toLong() and 0xFF) shl 24) or
            ((payload[1].toLong() and 0xFF) shl 16) or
            ((payload[2].toLong() and 0xFF) shl 8) or
            (payload[3].toLong() and 0xFF)
    }

    /** Fragments of messages still reassembling, kept sealed so they can be relayed. */
    private val heldFragments = LinkedHashMap<Long, MutableList<Frame>>()

    private fun holdFragment(fragment: Frame): MutableList<Frame> {
        val key = fragmentKey(fragment)
        if (key !in heldFragments && heldFragments.size >= Reassembler.MAX_PARTIAL_MESSAGES) {
            heldFragments.remove(heldFragments.keys.first())
        }
        val list = heldFragments.getOrPut(key) { ArrayList() }
        list += fragment
        return list
    }

    private fun fragmentKey(frame: Frame): Long =
        ((frame.src.toLong() and 0xFF) shl 16) or (frame.seq.toLong() and 0xFFFF)

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
        val plain =
            Aead.open(
                key = key,
                header = associatedData(frame),
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

    /**
     * Finds the epoch a frame was sealed with, and opens it.
     *
     * The epoch this receiver last verified the sender under is tried first, and it is
     * almost always right. When it is not — the sender has restarted, so its epoch has
     * moved on — the search runs again. The first version cached the epoch and never
     * looked again, so a handset that restarted was refused by every other unit, for
     * ever, as `NOT_AUTHENTIC`; the banner said "under attack" and the cure was to restart
     * everything at once.
     *
     * Only the right epoch produces a tag that checks out under the shared key, so a
     * success is proof rather than a guess — this is discovery, not a bypass. An attacker
     * without the key gains nothing from it, because every candidate still has to pass the
     * AEAD.
     *
     * @return the epoch and the opened frame, or null if no candidate opened it
     */
    private fun resolveEpoch(
        frame: Frame,
        nowMillis: Long,
    ): Pair<Long, Frame>? {
        val cached = replay.epochOf(frame.src)
        if (cached != null) openFrame(frame, cached)?.let { return cached to it }
        hints[frame.src]?.let { hinted ->
            if (hinted != cached && (cached == null || hinted > cached)) {
                openFrame(frame, hinted)?.let { return hinted to it }
            }
        }
        if (!searchAllowed(frame.src, nowMillis)) return null
        for (candidate in candidates(cached)) {
            val opened = openFrame(frame, candidate) ?: continue
            searches.remove(frame.src)
            return candidate to opened
        }
        searches.getOrPut(frame.src) { Search(nowMillis) }.failures++
        return null
    }

    /**
     * Epochs to try, likeliest first, each once, none the replay window would refuse.
     *
     * 0. (Before this) the epoch the sender announced in its last hello, if any.
     * 1. Our own: two handsets set up together are usually in step, and with a clock-seeded
     *    counter they are within minutes of each other.
     * 2. Just past the cached one: a sender that has restarted a few times since we last
     *    heard from it.
     * 3. Outward from our own, both ways: a sender set up on another day.
     * 4. From zero: a handset whose clock is wrong and whose counter never left the ground.
     */
    private fun candidates(cached: Long?): Sequence<Long> =
        sequence {
            val tried = HashSet<Long>()

            suspend fun SequenceScope<Long>.offer(value: Long) {
                if (value < 0 || value > EpochCounter.MAX_EPOCH) return
                if (cached != null && value <= cached) return
                if (tried.add(value)) yield(value)
            }
            offer(epoch)
            if (cached != null) for (d in 1..RESTART_SEARCH) offer(cached + d)
            for (d in 1..NEIGHBOUR_SEARCH) {
                offer(epoch + d)
                offer(epoch - d)
            }
            for (value in 0..LEGACY_SEARCH) offer(value.toLong())
        }

    /** A search that keeps failing for one sender is a stranger, not a peer. */
    private class Search(val sinceMillis: Long) {
        var failures = 0
    }

    private val searches = HashMap<Int, Search>()

    /**
     * A full search costs some thousands of tag checks. A unit without the key could ask
     * for one with every frame it sends, so after a few failures the sender gets only the
     * cheap check for a while. A genuine peer whose epoch is out of reach is refused
     * either way; a restart of either handset puts them back in reach.
     */
    private fun searchAllowed(
        src: Int,
        nowMillis: Long,
    ): Boolean {
        val search = searches[src] ?: return true
        if (nowMillis - search.sinceMillis > SEARCH_BACKOFF_MILLIS) {
            searches.remove(src)
            return true
        }
        return search.failures < SEARCH_FAILURE_LIMIT
    }

    private fun malformed(reason: RejectReason): Reason =
        when (reason) {
            RejectReason.BAD_CRC -> Reason.MALFORMED
            else -> Reason.MALFORMED
        }

    companion object {
        /** `docs/PROTOCOL.md` section 1: three hops. */
        const val DEFAULT_TTL = 3

        /** Direct range only; never rebroadcast. */
        const val MIN_TTL = 0

        /**
         * Seven hops is more than any deployment this is built for has units, and the
         * byte on the wire would allow 255 — which on a channel with a loop in it is a
         * storm bounded only by the seen-set. The ceiling is the operator's protection
         * against a slip of the finger, not a protocol limit.
         */
        const val MAX_TTL = 7

        /** Byte 9 of the header, per `docs/PROTOCOL.md` section 1. */
        const val TTL_OFFSET = 9

        /** Below this a fragment carries nothing; the link cannot carry frames at all. */
        const val MIN_FRAGMENT_MTU = 24

        /** A hello is sent every five seconds; a unit relays at most that many per sender. */
        const val HELLO_RELAY_MIN_MILLIS = 5_000L

        /** A changed presence is relayed at most this often per sender. */
        const val PRESENCE_RELAY_MIN_MILLIS = 2_000L

        /**
         * An unchanged presence is relayed again after this long, so a far unit stays on
         * the roster: a unit is present for thirty-five seconds after its last frame.
         */
        const val PRESENCE_RELAY_REFRESH_MILLIS = 25_000L

        /** Restarts since we last heard a sender that a search will cover. */
        const val RESTART_SEARCH = 512

        /**
         * How far either side of our own epoch a first contact will look.
         *
         * Epochs are seeded from the clock in minutes, so this is nearly three days of
         * difference in start times, at a cost of at most twice that many tag checks --
         * some tens of milliseconds -- once per sender. A sender further off than that is
         * found from its hello instead, which every unit sends every few seconds.
         */
        const val NEIGHBOUR_SEARCH = 4_096

        /** A pure counter, for a handset whose clock is wrong: years of restarts. */
        const val LEGACY_SEARCH = 512

        const val SEARCH_FAILURE_LIMIT = 3
        const val SEARCH_BACKOFF_MILLIS = 60_000L
    }
}

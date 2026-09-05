package org.itantra.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One [Link] over many peers. Every frame goes to every unit.
 *
 * ## Why this exists
 *
 * `docs/PROTOCOL.md` section 8: *there is no destination field — every frame is broadcast
 * to every unit holding the key, exactly as a walkie-talkie is.* The frame format has said
 * that since week 2, and the transport did not implement it: [RfcommLink] carries **one
 * peer at a time**, by its own comment, because an RFCOMM server socket accepts a single
 * connection.
 *
 * So a project whose entire premise is a radio net had a transport that could only manage a
 * telephone call. This is the fan-out that was missing.
 *
 * ## Duplicates are expected, and they are already handled
 *
 * With several peers a frame can arrive twice — directly and again relayed by a unit in the
 * middle. This class does **not** deduplicate, deliberately. Two mechanisms above it
 * already do, and both are per-sender rather than per-link:
 *
 * - the replay window rejects a second `(EPOCH, SEQ)` from the same sender;
 * - the relay seen-set stops a frame going round a loop.
 *
 * A third check here would be a third place to get it wrong, and it would be the only one
 * that could not tell a duplicate from a retransmission.
 *
 * ## What "connected" means for a net
 *
 * [state] is `CONNECTED` when **any** peer is reachable, because a walkie-talkie with one
 * other unit on the channel is working. It is `DEGRADED` when peers are configured and none
 * is currently up — recoverable, and the children are retrying — and `IDLE` when there are
 * no peers at all. There is deliberately no state for "some peers down": on a radio net that
 * is the normal condition, not a fault.
 */
class MeshLink(
    private val scope: CoroutineScope,
    override val name: String = "mesh",
) : Link {
    /** A peer, and the two collectors reading it. Both are cancelled together. */
    private class Peer(
        val link: Link,
        val pump: Job,
        val watch: Job,
    ) {
        fun stop() {
            pump.cancel()
            watch.cancel()
        }
    }

    private val peers = LinkedHashMap<String, Peer>()

    private val _incoming =
        MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 256)
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    /**
     * The smallest MTU across the peers, or a conservative default with none.
     *
     * The **smallest**, because one frame goes to all of them: sizing to the largest would
     * produce a frame the narrowest peer cannot carry, and it would fail only for that
     * unit, only sometimes, and only for long messages.
     */
    override val mtu: Int
        get() = peers.values.minOfOrNull { it.link.mtu } ?: DEFAULT_MTU

    /** Peers currently reachable — the "6 units" band A shows. */
    val connectedCount: Int
        get() = peers.values.count { it.link.state.value == LinkState.CONNECTED }

    val peerCount: Int get() = peers.size

    val peerNames: List<String> get() = peers.keys.toList()

    /**
     * Adds a peer and starts reading from it.
     *
     * @param id a stable name for this peer, so re-adding the same one replaces rather
     *   than duplicates it. A duplicated peer would deliver every frame twice and count
     *   as two units on screen.
     */
    fun addPeer(
        id: String,
        link: Link,
    ) {
        removePeer(id)
        // UNDISPATCHED, so both collectors are subscribed by the time this method
        // returns. Started normally they subscribe on the next dispatch, and a frame or a
        // state change arriving in that window is simply lost — which on a real link is
        // the first frame after a peer connects, the one most likely to matter.
        val pump =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.incoming.collect { frame ->
                    _metrics.value =
                        _metrics.value.copy(
                            framesReceived = _metrics.value.framesReceived + 1,
                            bytesReceived = _metrics.value.bytesReceived + frame.size,
                        )
                    _incoming.emit(frame)
                }
            }
        val watch =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.state.collect { recomputeState() }
            }
        peers[id] = Peer(link, pump, watch)
        recomputeState()
    }

    fun removePeer(id: String) {
        peers.remove(id)?.let { peer ->
            peer.stop()
            scope.launch { peer.link.disconnect() }
        }
        recomputeState()
    }

    override suspend fun connect() {
        for (peer in peers.values) peer.link.connect()
        recomputeState()
    }

    override suspend fun disconnect() {
        for (peer in peers.values) peer.link.disconnect()
        recomputeState()
    }

    /**
     * Sends to every peer that is up.
     *
     * A peer that throws does not stop the others. On a radio net one unit walking out of
     * range must not silence the channel for everyone else, and that is exactly what an
     * exception propagating out of this loop would do.
     */
    override suspend fun send(frame: ByteArray) {
        var delivered = 0
        for (peer in peers.values) {
            if (peer.link.state.value != LinkState.CONNECTED) continue
            runCatching { peer.link.send(frame) }.onSuccess { delivered++ }
        }
        _metrics.value =
            _metrics.value.copy(
                framesSent = _metrics.value.framesSent + delivered,
                bytesSent = _metrics.value.bytesSent + frame.size.toLong() * delivered,
            )
    }

    private fun recomputeState() {
        _state.value =
            when {
                peers.isEmpty() -> LinkState.IDLE
                peers.values.any { it.link.state.value == LinkState.CONNECTED } -> LinkState.CONNECTED
                // Configured peers, none up. The children are retrying, so this is
                // recoverable by definition.
                else -> LinkState.DEGRADED
            }
    }

    private companion object {
        /** Conservative: the smallest of the three transports, before any peer joins. */
        const val DEFAULT_MTU = 244
    }
}

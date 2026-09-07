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
 *
 * ## Roads, and switching one off
 *
 * Every peer belongs to a **road** — the BLE broadcast, the Wi-Fi broadcast, or RFCOMM,
 * which is one peer per bonded handset and so one road with many peers. An operator can
 * switch a road off for routine traffic through [roads]. A road that is off is still
 * **connected and still received on**: switching it off changes only what this unit
 * sends down it, never what it hears. Going deaf on a radio is not a preference anyone
 * means to express, and the alert path below depends on the road staying up.
 *
 * An **urgent** send ignores the switch and goes down every road that is up. The frame
 * format has no way for this class to know what is urgent — it sees bytes — so the sender
 * says so through [send]'s second parameter, which [Session] sets for an alert and the
 * engine sets for a relayed one.
 *
 * If the roads that are on and the peers that exist have nothing in common, the switch is
 * ignored and the frame goes everywhere. A configuration that would send nothing is a
 * configuration error, and the right failure for a radio is to transmit anyway.
 *
 * ## Threads
 *
 * Peers are added and removed from the Bluetooth I/O threads while frames are sent from
 * the engine's. The roster is guarded, and every loop over it walks a **snapshot**: a send
 * suspends inside the loop at each peer's write, and a peer arriving during that suspension
 * used to throw `ConcurrentModificationException` out of the send — the message was lost,
 * and when it happened inside the first `connect()` the outbox watcher never started.
 */
class MeshLink(
    private val scope: CoroutineScope,
    override val name: String = "mesh",
) : Link, SignalSource {
    /** A peer, and the collectors reading it. All are cancelled together. */
    private class Peer(
        val link: Link,
        val road: String,
        val pump: Job,
        val watch: Job,
        val listen: Job?,
    ) {
        fun stop() {
            pump.cancel()
            watch.cancel()
            listen?.cancel()
        }
    }

    private val _signals =
        MutableSharedFlow<Signal>(replay = 0, extraBufferCapacity = 64)

    /** Signal readings from every radio on the mesh. See [Signal]. */
    override val signals: Flow<Signal> get() = _signals.asSharedFlow()

    private val peers = LinkedHashMap<String, Peer>()

    private fun snapshot(): List<Peer> = synchronized(peers) { peers.values.toList() }

    private val _incoming =
        MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 256)
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    /**
     * The smallest MTU across the peers that are up, or a conservative default with none.
     *
     * The **smallest**, because one frame goes to all of them: sizing to the largest would
     * produce a frame the narrowest peer cannot carry, and it would fail only for that
     * unit, only sometimes, and only for long messages. Only peers that are up count: a
     * road that is closed does not get to decide how wide the open ones are.
     */
    override val mtu: Int
        get() {
            val all = snapshot()
            val up = all.filter { it.link.state.value == LinkState.CONNECTED }
            return (up.ifEmpty { all }).minOfOrNull { it.link.mtu } ?: DEFAULT_MTU
        }

    /** Peers currently reachable — the "6 units" band A shows. */
    val connectedCount: Int
        get() = snapshot().count { it.link.state.value == LinkState.CONNECTED }

    val peerCount: Int get() = synchronized(peers) { peers.size }

    val peerNames: List<String> get() = synchronized(peers) { peers.keys.toList() }

    /**
     * Every peer's own state, by the id it was added under.
     *
     * [state] collapses the mesh to one value on purpose — an operator does not want to
     * read a table mid-incident. The settings screen does want the table, because "which
     * road is actually carrying this" is the question it exists to answer, and because a
     * channel listed with no state is the kind of control that looks alive and is not.
     */
    val peerStates: Map<String, LinkState>
        get() = synchronized(peers) { peers.mapValues { (_, peer) -> peer.link.state.value } }

    /**
     * The roads routine traffic may use, or null for all of them.
     *
     * Null rather than "every road that exists", because roads come and go — a bonded
     * handset appears after the operator set this — and "all" has to keep meaning all.
     * Written from the interface thread, read from the engine's; a volatile reference to
     * an immutable set is the whole synchronisation needed.
     */
    @Volatile
    var roads: Set<String>? = null

    /** The road each peer was added under, by id. Feeds the settings screen. */
    val peerRoads: Map<String, String>
        get() = synchronized(peers) { peers.mapValues { (_, peer) -> peer.road } }

    /**
     * Adds a peer and starts reading from it.
     *
     * @param id a stable name for this peer, so re-adding the same one replaces rather
     *   than duplicates it. A duplicated peer would deliver every frame twice and count
     *   as two units on screen.
     * @param road the road this peer is one lane of, for [roads]. Defaults to the id,
     *   which is right for a broadcast peer and wrong for RFCOMM, where every bonded
     *   handset is a peer and the road is all of them.
     */
    fun addPeer(
        id: String,
        link: Link,
        road: String = id,
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
        val listen =
            (link as? SignalSource)?.let { source ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    source.signals.collect { _signals.emit(it) }
                }
            }
        synchronized(peers) { peers[id] = Peer(link, road, pump, watch, listen) }
        recomputeState()
    }

    fun removePeer(id: String) {
        val removed = synchronized(peers) { peers.remove(id) }
        removed?.let { peer ->
            peer.stop()
            scope.launch { peer.link.disconnect() }
        }
        recomputeState()
    }

    override suspend fun connect() {
        for (peer in snapshot()) peer.link.connect()
        recomputeState()
    }

    override suspend fun disconnect() {
        for (peer in snapshot()) peer.link.disconnect()
        recomputeState()
    }

    /**
     * Asks every peer that is not up to try again.
     *
     * A broadcast link has nobody to notice it has died: a radio switched off and on
     * leaves the scanner stopped and the socket dead, with nothing to redial. This is the
     * periodic nudge that brings such a road back, and it is cheap for a road that is
     * already open, because those are skipped.
     */
    suspend fun reconnectDown(): Int {
        var attempted = 0
        for (peer in snapshot()) {
            if (peer.link.state.value == LinkState.CONNECTED) continue
            runCatching { peer.link.connect() }
            attempted++
        }
        recomputeState()
        return attempted
    }

    /** Routine traffic: every peer that is up, on a road that is on. */
    override suspend fun send(frame: ByteArray) = send(frame, urgent = false)

    /**
     * Sends to every peer that is up — on every road for an alert, on the roads that are
     * on for anything else. See the class note on roads.
     *
     * A peer that throws does not stop the others. On a radio net one unit walking out of
     * range must not silence the channel for everyone else, and that is exactly what an
     * exception propagating out of this loop would do.
     */
    override suspend fun send(
        frame: ByteArray,
        urgent: Boolean,
    ) {
        var delivered = 0
        for (peer in eligible(urgent)) {
            runCatching { peer.link.send(frame) }.onSuccess { delivered++ }
        }
        _metrics.value =
            _metrics.value.copy(
                framesSent = _metrics.value.framesSent + delivered,
                bytesSent = _metrics.value.bytesSent + frame.size.toLong() * delivered,
            )
    }

    /**
     * The peers a send goes to.
     *
     * Decided against the peers that are **up**, not against every peer configured: a road
     * that is on but down contributes nothing, and a selection of only such roads would
     * otherwise be honoured into silence. When the selection leaves no peer standing the
     * switch is set aside and the frame goes down every road that is up.
     */
    private fun eligible(urgent: Boolean): List<Peer> {
        val up = snapshot().filter { it.link.state.value == LinkState.CONNECTED }
        val selected = roads
        if (urgent || selected == null) return up
        val chosen = up.filter { it.road in selected }
        return chosen.ifEmpty { up }
    }

    private fun recomputeState() {
        val all = snapshot()
        _state.value =
            when {
                all.isEmpty() -> LinkState.IDLE
                all.any { it.link.state.value == LinkState.CONNECTED } -> LinkState.CONNECTED
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

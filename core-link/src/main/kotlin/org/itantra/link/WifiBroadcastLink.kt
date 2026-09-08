package org.itantra.link

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The same radio channel, over Wi-Fi: one datagram, every unit on the network.
 *
 * ## Why a hotspot is the answer, and needs no data plan
 *
 * One handset turns on its mobile hotspot and the others join it. That is a local network
 * with no route to anywhere — mobile data can be off, the SIM can be absent, the host can be
 * in aeroplane mode with Wi-Fi on — and every phone on it receives a broadcast datagram. No
 * pairing, no bonding, no discovery, and no internet.
 *
 * It also works on any shared Wi-Fi, so a relief camp with an existing access point needs
 * nothing arranged either.
 *
 * ## Why it exists alongside BLE
 *
 * They fail differently, which is the point of having both. BLE advertising needs nothing
 * set up at all and carries a few frames a second at ten to fifty metres. Wi-Fi needs
 * somebody to switch a hotspot on and then carries orders of magnitude more, further. The
 * mesh runs both at once and the replay window discards whichever copy arrives second, so a
 * frame that makes it by either road is delivered exactly once.
 *
 * ## What this cost, stated plainly
 *
 * `android.permission.INTERNET`. Android requires it to open **any** socket, including one
 * that only ever addresses a broadcast address on the local subnet. Constraint **C2** had
 * been verified by that permission's absence — but ISRO's own description says the data is
 * streamed "through wifi/Bluetooth", so the strongest form of C2 made a stated requirement
 * impossible to build. The permission is now present and the claim is verified differently:
 * every datagram this class sends goes to a broadcast address, and nothing in the
 * application resolves a hostname or opens an outbound network connection. See `docs/TRANSPORT.md`.
 */
class WifiBroadcastLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val port: Int = FRAME_PORT,
    override val name: String = "wifi-broadcast",
) : Link {
    private val _incoming =
        MutableSharedFlow<ByteArray>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    /** A datagram this size crosses any Wi-Fi without fragmenting at the IP layer. */
    override val mtu: Int = 1_400

    private var socket: DatagramSocket? = null
    private var reader: Job? = null
    private var lock: WifiManager.MulticastLock? = null

    /** Frames this unit put on the air, so its own broadcast is not read back as traffic. */
    private val sent = LinkedHashMap<Int, Long>()

    override suspend fun connect() {
        if (reader?.isActive == true) return
        runCatching {
            // Without this, Wi-Fi power save drops broadcast and multicast frames before
            // they reach the socket, and the failure is a channel that transmits perfectly
            // and never receives.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock =
                wifi?.createMulticastLock("itantra")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        Log.i(TAG, "opening on port $port")
        // Held in a local because inside `apply` the receiver is the socket, and
        // DatagramSocket has a `port` of its own -- the remote port, -1 while unconnected.
        // Kotlin resolved `port` to that one, and the bind failed with "port out of
        // range: -1" while the line above printed 38173.
        val wanted = port
        val attempt =
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(wanted))
                    broadcast = true
                }
            }
        val bound = attempt.getOrNull()

        if (bound == null) {
            // Said out loud, with the reason. A channel that fails to open and reports
            // nothing is indistinguishable from one that opened and heard nothing.
            Log.w(TAG, "could not bind port $port", attempt.exceptionOrNull())
            _state.value = LinkState.DEGRADED
            return
        }
        socket = bound
        reader = scope.launch(Dispatchers.IO) { receiveLoop(bound) }
        keeper?.cancel()
        keeper = scope.launch(Dispatchers.IO) { watchNetwork() }
        judgeNetwork()
    }

    /**
     * Says whether there is a network to broadcast on, and keeps saying it.
     *
     * The socket binds whether or not the handset is on any Wi-Fi, so a bound socket is
     * not a road: the first version reported "connected" from the moment of the bind, and
     * a unit with Bluetooth off and no Wi-Fi at all showed a net that was up and carried
     * nothing. The road is up when some interface has a subnet broadcast address -- a
     * hotspot's, a Wi-Fi Direct group's `p2p` interface, an access point's -- and down
     * otherwise. Checked every few seconds, because a group formed in the system settings
     * after the application started, or a client still waiting for its address, appears
     * with no callback to this class.
     */
    private suspend fun watchNetwork() {
        while (currentCoroutineContext().isActive) {
            delay(NETWORK_CHECK_MILLIS)
            if (socket == null) return
            judgeNetwork()
        }
    }

    private fun judgeNetwork() {
        val subnets = subnetBroadcastTargets()
        val up = subnets.isNotEmpty()
        val was = _state.value
        _state.value = if (up) LinkState.CONNECTED else LinkState.DEGRADED
        if (_state.value != was || subnets != lastTargets) {
            val word = if (up) "channel open on port $port, targets $subnets" else "no network to broadcast on; waiting"
            Log.i(TAG, word)
        }
        lastTargets = subnets
    }

    override suspend fun disconnect() {
        keeper?.cancel()
        keeper = null
        reader?.cancel()
        reader = null
        runCatching { socket?.close() }
        socket = null
        lastTargets = emptyList()
        runCatching { lock?.release() }
        lock = null
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        val open = socket ?: return
        if (frame.size > mtu) return
        remember(frame)

        // Every subnet broadcast, and the limited broadcast besides. Only the subnets
        // count as delivery: the limited broadcast can always be sent, to nowhere.
        var delivered = 0
        val subnets = subnetBroadcastTargets()
        for (target in subnets) {
            runCatching {
                open.send(DatagramPacket(frame, frame.size, target, port))
                delivered++
            }
        }
        runCatching { open.send(DatagramPacket(frame, frame.size, limitedBroadcast(), port)) }
        if (delivered == 0) {
            // No Wi-Fi, or no interface with a broadcast address. Recoverable: a hotspot
            // may be switched on at any moment, and the mesh has other roads meanwhile.
            _metrics.update { it.copy(framesLost = it.framesLost + 1) }
            _state.value = LinkState.DEGRADED
        } else {
            _state.value = LinkState.CONNECTED
            _metrics.update {
                it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
            }
        }
    }

    private suspend fun receiveLoop(open: DatagramSocket) {
        val buffer = ByteArray(mtu)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received =
                runCatching {
                    open.receive(packet)
                    true
                }
                    .getOrElse { failure ->
                        if (failure is IOException && open.isClosed) return
                        delay(RETRY_MILLIS)
                        false
                    }
            if (!received || packet.length <= 0) continue

            val frame = packet.data.copyOf(packet.length)
            // A broadcast comes back to its sender. The replay window would drop it as this
            // unit's own transmission anyway; dropping it here saves the AEAD the trouble.
            if (isOurs(frame)) continue

            _metrics.update {
                it.copy(
                    framesReceived = it.framesReceived + 1,
                    bytesReceived = it.bytesReceived + frame.size,
                )
            }
            Log.i(TAG, "heard ${frame.size} B from ${packet.address?.hostAddress}")
            _incoming.emit(frame)
        }
    }

    /** Watches for a network appearing or going. See [watchNetwork]. */
    private var keeper: Job? = null

    /** What the last check found, so the log says something only when it changes. */
    private var lastTargets: List<InetAddress> = emptyList()

    private fun remember(frame: ByteArray) {
        val now = System.currentTimeMillis()
        synchronized(sent) {
            sent.entries.removeAll { now - it.value > ECHO_WINDOW_MILLIS }
            if (sent.size >= MAX_TRACKED) sent.remove(sent.keys.first())
            sent[frame.contentHashCode()] = now
        }
    }

    private fun isOurs(frame: ByteArray): Boolean = synchronized(sent) { sent.containsKey(frame.contentHashCode()) }

    companion object {
        private const val TAG = "itantra-wifi"

        /**
         * Every address this handset may send a frame to: each up, non-loopback
         * interface's own broadcast address, plus the limited broadcast 255.255.255.255.
         *
         * Enumerated rather than assumed: a phone hosting a hotspot is usually
         * 192.168.43.1 and a phone joined to one is not, and some Android builds drop
         * 255.255.255.255 while delivering the subnet's own broadcast perfectly.
         *
         * This list *is* constraint **C2** for the Wi-Fi transport, which is why it is
         * `internal` rather than private: nothing else decides where a datagram goes, so
         * asserting that every entry here is a broadcast address is the whole claim.
         * The limited broadcast is built from its four bytes rather than resolved from
         * the string "255.255.255.255" — same address, but no name lookup, so the claim
         * "nothing resolves a hostname" survives a grep for the resolver API, comments
         * included. That is also why this note spells the method name nowhere.
         */
        internal fun broadcastTargets(): List<InetAddress> {
            val out = ArrayList(subnetBroadcastTargets())
            runCatching { out += limitedBroadcast() }
            return out
        }

        /**
         * The subnet broadcast address of every interface that is up: a hotspot's own
         * network, a Wi-Fi Direct group's `p2p` interface, an access point's. Empty
         * means there is no network to broadcast on, which is what makes the road's
         * state honest.
         */
        internal fun subnetBroadcastTargets(): List<InetAddress> {
            val out = ArrayList<InetAddress>()
            runCatching {
                for (nic in NetworkInterface.getNetworkInterfaces()) {
                    if (!nic.isUp || nic.isLoopback) continue
                    for (address in nic.interfaceAddresses) {
                        address.broadcast?.let(out::add)
                    }
                }
            }
            return out
        }

        private fun limitedBroadcast(): InetAddress = InetAddress.getByAddress(LIMITED_BROADCAST)

        /** 255.255.255.255, as bytes, so no resolver is ever consulted. */
        private val LIMITED_BROADCAST =
            byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())

        /** `docs/TRANSPORT.md` section 4 names this port for frames. */
        const val FRAME_PORT = 38_173

        private const val RETRY_MILLIS = 500L

        /** How often the interfaces are looked at for a network coming or going. */
        private const val NETWORK_CHECK_MILLIS = 3_000L

        /** A broadcast returns to its sender within milliseconds; this is generous. */
        private const val ECHO_WINDOW_MILLIS = 5_000L
        private const val MAX_TRACKED = 256
    }
}

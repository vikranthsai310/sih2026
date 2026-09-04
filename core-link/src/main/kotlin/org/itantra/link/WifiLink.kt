package org.itantra.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.itantra.proto.StreamFramer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Wi-Fi transport over a hosted network. Task **W6.6**.
 *
 * ## Why a hosted network rather than Wi-Fi Direct
 *
 * `WifiP2pManager` discovery is unreliable across manufacturers — risk **T-09**. One
 * handset enabling a hotspot and the others joining it is boring, works everywhere, and
 * needs no negotiation protocol. Wi-Fi Direct stays optional.
 *
 * There is **no internet involved**: this is a local network between handsets, which is
 * why the application can carry Wi-Fi without an `INTERNET` permission. Sockets bound to
 * a local address do not need one — only `java.net.URL` and friends do. Constraint C2
 * survives intact, and that is checked against the built APK.
 *
 * ## Ports
 *
 * | Port | Protocol | Purpose |
 * | --- | --- | --- |
 * | 38173 | TCP | Frames |
 * | 38174 | UDP broadcast | Discovery, so a joining unit finds the host without typing an address |
 *
 * ## Why TCP and not UDP for frames
 *
 * TCP gives ordering and retransmission for free over a link that is already lossy at
 * the radio layer, and the framing work is done regardless — [StreamFramer] exists for
 * Bluetooth. UDP would mean reimplementing both badly. The cost is head-of-line blocking,
 * which on a single-sentence-at-a-time channel does not arise.
 */
class WifiLink(
    private val role: Role,
    private val scope: CoroutineScope,
    private val hostAddress: String? = null,
    private val port: Int = FRAME_PORT,
) : Link {
    enum class Role {
        /** Runs the hotspot and accepts connections. */
        HOST,

        /** Joins the hotspot and dials the host. */
        CLIENT,
    }

    override val name: String get() = "wifi-${role.name.lowercase()}"

    /**
     * Deliberately below the typical 1500-byte path MTU: a frame that fits one TCP
     * segment cannot be split across two, which keeps latency predictable.
     */
    override val mtu: Int = 1_200

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var readJob: Job? = null
    private var beaconJob: Job? = null

    private val writeLock = Mutex()
    private val backoff = Backoff()
    private val framer = StreamFramer()

    override suspend fun connect() {
        disconnect()
        _state.value = LinkState.DISCOVERING
        when (role) {
            Role.HOST -> listen()
            Role.CLIENT -> dial()
        }
    }

    private suspend fun listen() =
        withContext(Dispatchers.IO) {
            runCatching {
                val listener = ServerSocket()
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(port))
                server = listener

                // Announce while waiting, so a joining unit never has to be told an
                // address by a person reading it off a screen.
                beaconJob = scope.launch(Dispatchers.IO) { beacon() }

                val accepted = listener.accept()
                accepted.tcpNoDelay = true
                onConnected(accepted)
            }.onFailure { fail(it) }
        }

    private suspend fun dial() =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = hostAddress ?: discoverHost() ?: error("no host found")
                val client = Socket()
                client.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                client.tcpNoDelay = true
                onConnected(client)
            }.onFailure { fail(it) }
        }

    private fun onConnected(connected: Socket) {
        socket = connected
        _state.value = LinkState.CONNECTED
        backoff.reset()
        readJob = scope.launch(Dispatchers.IO) { readLoop(connected) }
    }

    /**
     * `TCP_NODELAY` is set on both ends. Without it Nagle's algorithm holds a 44-byte
     * frame back waiting for more data to coalesce, adding up to 40 ms to a budget of
     * 800 — a latency bug that would look like a slow model.
     */
    private suspend fun readLoop(connected: Socket) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            val input = connected.getInputStream()
            while (scope.isActive && !connected.isClosed) {
                val read = input.read(buffer)
                if (read < 0) break
                _metrics.value =
                    _metrics.value.copy(bytesReceived = _metrics.value.bytesReceived + read)
                // De-framing is the transport's job, never the consumer's.
                for (frame in framer.offer(buffer, 0, read)) {
                    _metrics.value =
                        _metrics.value.copy(framesReceived = _metrics.value.framesReceived + 1)
                    _incoming.emit(frame.encode())
                }
            }
            // A clean end of stream is the peer leaving, which is recoverable.
            _state.value = LinkState.DEGRADED
        } catch (e: Exception) {
            fail(e)
        }
    }

    override suspend fun send(frame: ByteArray) {
        val connected = socket ?: return
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    connected.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                    _metrics.value =
                        _metrics.value.copy(
                            framesSent = _metrics.value.framesSent + 1,
                            bytesSent = _metrics.value.bytesSent + frame.size,
                        )
                }.onFailure { fail(it) }
            }
        }
    }

    override suspend fun disconnect() {
        beaconJob?.cancel()
        readJob?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            runCatching { server?.close() }
        }
        socket = null
        server = null
        framer.reset()
        _state.value = LinkState.IDLE
    }

    // ── discovery ────────────────────────────────────────────────────────────

    /** Broadcasts the host's presence once a second while it waits for a peer. */
    private fun beacon() {
        runCatching {
            DatagramSocket().use { datagram ->
                datagram.broadcast = true
                val payload = BEACON_MAGIC.toByteArray()
                val target = InetAddress.getByName("255.255.255.255")
                while (scope.isActive && _state.value != LinkState.CONNECTED) {
                    datagram.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                    Thread.sleep(BEACON_INTERVAL_MILLIS)
                }
            }
        }
    }

    /**
     * Listens for a beacon and returns the host's address.
     *
     * @return null if nothing announced itself within the timeout, which is a normal
     *   outcome rather than an error — the host may simply not be up yet.
     */
    private fun discoverHost(): String? =
        runCatching {
            DatagramSocket(DISCOVERY_PORT).use { datagram ->
                datagram.soTimeout = DISCOVERY_TIMEOUT_MILLIS
                val packet = DatagramPacket(ByteArray(64), 64)
                val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MILLIS
                while (System.currentTimeMillis() < deadline) {
                    try {
                        datagram.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        return@use null
                    }
                    val text = String(packet.data, 0, packet.length)
                    // Only our own beacon counts; a shared network carries other traffic.
                    if (text == BEACON_MAGIC) return@use packet.address.hostAddress
                }
                null
            }
        }.getOrNull()

    private fun fail(cause: Throwable) {
        // DEGRADED, not ERROR: the contract says anything the implementation can recover
        // from by itself is degraded, and reconnection is exactly that.
        _state.value = LinkState.DEGRADED
        _metrics.value = _metrics.value.copy(framesLost = _metrics.value.framesLost + 1)
    }

    /** Delay before the next reconnection attempt, with jitter. */
    fun nextRetryMillis(): Long = backoff.nextDelayMillis()

    companion object {
        const val FRAME_PORT = 38_173
        const val DISCOVERY_PORT = 38_174

        /** Identifies our beacon on a network carrying other broadcast traffic. */
        const val BEACON_MAGIC = "iTantra/1"

        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DISCOVERY_TIMEOUT_MILLIS = 8_000
        private const val BEACON_INTERVAL_MILLIS = 1_000L
        private const val READ_BUFFER = 4_096
    }
}

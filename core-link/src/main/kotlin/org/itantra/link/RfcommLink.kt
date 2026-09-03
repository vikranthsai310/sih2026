package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.itantra.proto.StreamFramer
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic over RFCOMM — the default transport.
 *
 * RFCOMM is a virtual serial cable. It is primary because it is a reliable, ordered
 * byte stream needing no additional protocol work, and because it is the same profile
 * a radio modem presents: the code that talks to another phone is the code that would
 * talk to a radio.
 *
 * **Two mistakes this class exists to avoid.**
 *
 * `cancelDiscovery()` before `connect()` is not optional — leaving discovery running
 * cuts throughput by roughly an order of magnitude, and is a common cause of "it
 * worked on the bench and failed on stage".
 *
 * A stream preserves byte order but **not** message boundaries, so reads are fed
 * through [StreamFramer] rather than assumed to be whole frames. Omitting that is
 * risk **T-08**.
 *
 * See `docs/TRANSPORT.md` section 2.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the app before use
class RfcommLink(
    private val adapter: BluetoothAdapter,
    private val role: Role,
    /** For [Role.CLIENT]: the already-bonded device to dial. */
    private val target: BluetoothDevice? = null,
    private val scope: CoroutineScope,
) : Link {
    enum class Role { HOST, CLIENT }

    override val name: String get() = "bluetooth-${role.name.lowercase()}"

    /** RFCOMM has no MTU as such; this bounds one write. */
    override val mtu: Int = 1024

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

    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private var readerJob: Job? = null
    private val writeLock = Mutex()
    private val backoff = Backoff()

    override suspend fun connect() {
        readerJob?.cancel()
        readerJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        _state.value = LinkState.DISCOVERING
                        val s = openSocket()
                        socket = s
                        _state.value = LinkState.CONNECTED
                        backoff.reset()
                        pump(s)
                    } catch (e: IOException) {
                        // Recoverable by definition: the service keeps trying, so this
                        // is DEGRADED rather than ERROR.
                        _state.value = LinkState.DEGRADED
                    } finally {
                        closeQuietly()
                    }
                    if (isActive) delay(backoff.nextDelayMillis())
                }
            }
    }

    override suspend fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        closeQuietly()
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        val s = socket ?: return
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    s.outputStream.write(frame)
                    s.outputStream.flush()
                    _metrics.update {
                        it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
                    }
                } catch (e: IOException) {
                    _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                    _state.value = LinkState.DEGRADED
                }
            }
        }
    }

    private fun openSocket(): BluetoothSocket =
        when (role) {
            Role.HOST -> {
                val srv = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = srv
                val accepted = srv.accept()
                // One peer at a time; release the listener so the port is not held.
                runCatching { srv.close() }
                server = null
                accepted
            }

            Role.CLIENT -> {
                val device = requireNotNull(target) { "CLIENT role needs a target device" }
                // Leaving discovery running cripples throughput. Not optional.
                adapter.cancelDiscovery()
                device.createRfcommSocketToServiceRecord(SERVICE_UUID).apply { connect() }
            }
        }

    /**
     * Reads until the socket closes, feeding every read through the framer.
     *
     * A short read is normal and expected: writing 44 bytes then 38 may be read as 20
     * then 62.
     */
    private suspend fun pump(s: BluetoothSocket) {
        val framer = StreamFramer()
        val buffer = ByteArray(READ_BUFFER)
        val input = s.inputStream

        while (scope.isActive) {
            val read = input.read(buffer)
            if (read < 0) throw IOException("peer closed the connection")
            if (read == 0) continue

            for (frame in framer.offer(buffer, 0, read)) {
                _metrics.update {
                    it.copy(
                        framesReceived = it.framesReceived + 1,
                        bytesReceived = it.bytesReceived + frame.wireSize,
                    )
                }
                _incoming.emit(frame.encode())
            }
        }
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
    }

    companion object {
        /** Stable service identifier; both ends must agree. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "iTantra"
        private const val READ_BUFFER = 2048
    }
}

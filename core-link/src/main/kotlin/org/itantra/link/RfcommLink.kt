package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
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

/**
 * One connected Bluetooth Classic peer, over RFCOMM — the default transport.
 *
 * RFCOMM is a virtual serial cable. It is primary because it is a reliable, ordered byte
 * stream needing no additional protocol work, and because it is the same profile a radio
 * modem presents: the code that talks to another phone is the code that would talk to a
 * radio.
 *
 * ## One socket, already open
 *
 * This class deliberately does **not** dial, accept, or reconnect. It is handed a socket
 * that is already connected and pumps it until it dies. Everything about *finding* peers —
 * who dials, who listens, retrying, and the roster of units on the net — belongs to
 * [BluetoothNet], because those decisions involve every peer at once and cannot be made
 * correctly one connection at a time.
 *
 * That split is not cosmetic. The previous shape of this class took a `Role` and a target
 * device, and a host-role instance called `accept()` — which accepts *whoever* connects,
 * not the device it was constructed for. On a net of three units the link labelled "peer B"
 * would routinely be the socket to peer C, and one accept per bonded device meant several
 * server sockets on the same service UUID at once.
 *
 * ## The mistake this class still exists to avoid
 *
 * A stream preserves byte order but **not** message boundaries, so reads are fed through
 * [StreamFramer] rather than assumed to be whole frames. Writing 44 bytes then 38 may be
 * read as 20 then 62. Omitting that is risk **T-08**.
 *
 * See `docs/TRANSPORT.md` section 2.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the app before use
class RfcommLink(
    private val socket: BluetoothSocket,
    private val scope: CoroutineScope,
    override val name: String = "bluetooth",
) : Link {
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

    private var readerJob: Job? = null
    private val writeLock = Mutex()

    /**
     * Starts reading. The socket is already open, so this cannot fail to connect — it can
     * only later fail to stay connected, which is [LinkState.DEGRADED] and the net's cue to
     * drop this peer and dial again.
     */
    override suspend fun connect() {
        if (readerJob?.isActive == true) return
        _state.value = LinkState.CONNECTED
        readerJob =
            scope.launch(Dispatchers.IO) {
                try {
                    pump()
                } catch (e: IOException) {
                    // The peer walked out of range or closed. Recoverable by definition,
                    // so DEGRADED rather than ERROR — but recovered by BluetoothNet
                    // redialling, not by this object retrying inside itself.
                } finally {
                    _state.value = LinkState.DEGRADED
                    runCatching { socket.close() }
                }
            }
    }

    override suspend fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        // Closing is what unblocks a read parked in the kernel; cancelling the coroutine
        // alone leaves the thread sitting in InputStream.read forever.
        runCatching { socket.close() }
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        if (_state.value != LinkState.CONNECTED) return
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    socket.outputStream.write(frame)
                    socket.outputStream.flush()
                    _metrics.update {
                        it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
                    }
                } catch (e: IOException) {
                    _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                    _state.value = LinkState.DEGRADED
                    runCatching { socket.close() }
                }
            }
        }
    }

    /**
     * Reads until the socket closes, feeding every read through the framer.
     *
     * A short read is normal and expected.
     */
    private suspend fun pump() {
        val framer = StreamFramer()
        val buffer = ByteArray(READ_BUFFER)
        val input = socket.inputStream

        while (currentCoroutineContext().isActive) {
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

    companion object {
        private const val READ_BUFFER = 2048
    }
}

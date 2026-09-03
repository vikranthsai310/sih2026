package org.itantra.link

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.itantra.proto.StreamFramer

/**
 * An in-process [Link] with no radio behind it.
 *
 * Two of these can be [pair]ed so that what one sends the other receives, which lets
 * the whole path — recognise, compress, frame, seal, unseal, unpack, synthesise — be
 * exercised in a unit test with no handset, no Bluetooth and no second device.
 *
 * It is deliberately unkind by default: [chunkSize] delivers each frame in pieces so
 * that any consumer which assumes a write arrives as one read is caught here rather
 * than in the field. That assumption is risk **T-08**, and it is the most common
 * defect in this class of project.
 */
class LoopbackLink(
    override val name: String = "loopback",
    override val mtu: Int = 1024,
    /** Bytes delivered per emission. 1 is the cruellest and the most useful. */
    private val chunkSize: Int = 7,
) : Link {
    private var peer: LoopbackLink? = null

    private val _incoming =
        MutableSharedFlow<ByteArray>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())
    private val framer = StreamFramer()

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    /** Set true to model a link that has dropped without being disconnected. */
    var dropEverything: Boolean = false

    override suspend fun connect() {
        _state.value = if (peer == null) LinkState.DEGRADED else LinkState.CONNECTED
    }

    override suspend fun disconnect() {
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        _metrics.update {
            it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
        }
        if (dropEverything) {
            _metrics.update { it.copy(framesLost = it.framesLost + 1) }
            return
        }
        peer?.deliver(frame)
    }

    /** Feeds bytes to this side as a stream, in [chunkSize] pieces. */
    private suspend fun deliver(wire: ByteArray) {
        var offset = 0
        while (offset < wire.size) {
            val n = minOf(chunkSize, wire.size - offset)
            for (complete in framer.offer(wire, offset, n)) {
                _metrics.update {
                    it.copy(
                        framesReceived = it.framesReceived + 1,
                        bytesReceived = it.bytesReceived + complete.wireSize,
                    )
                }
                _incoming.emit(complete.encode())
            }
            offset += n
        }
    }

    companion object {
        /** Joins two links so each receives what the other sends. */
        fun pair(
            a: LoopbackLink,
            b: LoopbackLink,
        ) {
            a.peer = b
            b.peer = a
        }
    }
}

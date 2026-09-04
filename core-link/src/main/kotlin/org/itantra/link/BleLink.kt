package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Bluetooth Low Energy transport. Task **W6.4**.
 *
 * ## Why BLE at all, when RFCOMM already works
 *
 * Standby endurance. Requirement N3 asks for more than eight hours of listening on one
 * charge, and a classic RFCOMM link held open costs far more than a BLE connection with
 * a long interval. BLE is the transport for *waiting*; RFCOMM is the transport for
 * *talking*. A unit that spends 95 % of its day waiting should spend it on BLE.
 *
 * ## The MTU is the whole design constraint
 *
 * BLE's default ATT MTU is 23 bytes, of which 20 are usable — less than half a frame
 * header. The connection negotiates up to 247, leaving ~244 usable, and even that does
 * not fit a long sentence plus its AEAD tag. Everything above [MAX_PAYLOAD] therefore
 * goes through [Fragmenter], and this class exposes [mtu] so the fragmenter is sized from
 * what was actually negotiated rather than what was hoped for.
 *
 * **The negotiated value is not guaranteed.** Some handsets refuse and stay at 23. That
 * is why [mtu] is a variable read after connection rather than a constant, and why the
 * fragmenter is constructed per connection.
 *
 * ## Write type
 *
 * `WRITE_TYPE_DEFAULT` — acknowledged. `WRITE_TYPE_NO_RESPONSE` is faster and is the
 * usual choice for streaming, but it drops silently under congestion, and a frame that
 * vanishes without anyone knowing is precisely the failure this project cannot have.
 * The CRC would catch corruption; nothing catches a frame that never arrived.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val role: Role,
    private val target: BluetoothDevice? = null,
    private val scope: CoroutineScope,
) : Link {
    enum class Role {
        /** Advertises and accepts a connection. */
        PERIPHERAL,

        /** Scans and connects. */
        CENTRAL,
    }

    override val name: String get() = "ble-${role.name.lowercase()}"

    /** Usable payload after the ATT header, updated once the MTU is negotiated. */
    override var mtu: Int = DEFAULT_USABLE_MTU
        private set

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _state = MutableStateFlow(LinkState.IDLE)
    private val _metrics = MutableStateFlow(LinkMetrics())

    override val incoming: Flow<ByteArray> get() = _incoming.asSharedFlow()
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()
    override val metrics: StateFlow<LinkMetrics> get() = _metrics.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var frameCharacteristic: BluetoothGattCharacteristic? = null
    private val writeLock = Mutex()
    private val backoff = Backoff()

    /**
     * Sized from the negotiated MTU, so it is created after connection rather than
     * with a guess.
     */
    var fragmenter: Fragmenter? = null
        private set

    private val reassembler = Reassembler()

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Ask for the larger MTU immediately; service discovery waits
                        // until the answer arrives, because the MTU changes how much
                        // each characteristic write can carry.
                        g.requestMtu(REQUESTED_MTU)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _state.value = LinkState.DEGRADED
                        fragmenter = null
                        reassembler.clear()
                    }
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                negotiated: Int,
                status: Int,
            ) {
                // Whatever the peer agreed to, not what was asked for. A handset that
                // refuses stays at 23 and everything still works, just in more pieces.
                mtu = (negotiated - ATT_OVERHEAD).coerceAtLeast(MIN_USABLE_MTU)
                fragmenter = Fragmenter(mtu)
                g.discoverServices()
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _state.value = LinkState.DEGRADED
                    return
                }
                val service = g.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(FRAME_UUID)
                if (characteristic == null) {
                    // The peer is a BLE device but not one of ours.
                    _state.value = LinkState.ERROR
                    return
                }
                frameCharacteristic = characteristic
                enableNotifications(g, characteristic)
                _state.value = LinkState.CONNECTED
                backoff.reset()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onFragment(value)
            }

            @Deprecated("Superseded on API 33; kept for older handsets.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value?.let { onFragment(it) }
                }
            }
        }

    /**
     * Reassembles, then emits. Reassembly is mechanical and does not decrypt — the AEAD
     * tag is verified above this layer, after the whole frame exists. See [Fragmenter].
     */
    private fun onFragment(value: ByteArray) {
        _metrics.value =
            _metrics.value.copy(bytesReceived = _metrics.value.bytesReceived + value.size)
        scope.launch {
            val frame = org.itantra.proto.Frame.decode(value).orNull()
            if (frame == null) {
                _metrics.value = _metrics.value.copy(framesLost = _metrics.value.framesLost + 1)
                return@launch
            }
            if (frame.flags and org.itantra.proto.Flags.FRAGMENT == 0) {
                emit(value)
                return@launch
            }
            when (val result = reassembler.offer(frame, System.currentTimeMillis())) {
                is Reassembler.Result.Complete -> emit(result.frame.encode())
                is Reassembler.Result.Rejected ->
                    _metrics.value =
                        _metrics.value.copy(framesLost = _metrics.value.framesLost + 1)
                is Reassembler.Result.Incomplete -> Unit
            }
        }
    }

    private suspend fun emit(frame: ByteArray) {
        _metrics.value =
            _metrics.value.copy(framesReceived = _metrics.value.framesReceived + 1)
        _incoming.emit(frame)
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        g.setCharacteristicNotification(characteristic, true)
        // Setting the local flag is not enough: the peer only starts sending once the
        // Client Characteristic Configuration descriptor is written. Forgetting this is
        // the classic BLE bug where everything looks connected and nothing arrives.
        val descriptor = characteristic.getDescriptor(CCC_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    override suspend fun connect() {
        disconnect()
        _state.value = LinkState.DISCOVERING
        val device = target ?: return run { _state.value = LinkState.ERROR }
        gatt =
            device.connectGatt(
                context,
                // No auto-connect: it is slow to establish and this link is created in
                // response to a deliberate action. Reconnection is driven by Backoff.
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
    }

    override suspend fun send(frame: ByteArray) {
        val g = gatt ?: return
        val characteristic = frameCharacteristic ?: return
        val pieces = fragmentsFor(frame)

        writeLock.withLock {
            for (piece in pieces) {
                val ok = write(g, characteristic, piece)
                if (!ok) {
                    _metrics.value =
                        _metrics.value.copy(framesLost = _metrics.value.framesLost + 1)
                    _state.value = LinkState.DEGRADED
                    return
                }
                _metrics.value =
                    _metrics.value.copy(bytesSent = _metrics.value.bytesSent + piece.size)
            }
            _metrics.value = _metrics.value.copy(framesSent = _metrics.value.framesSent + 1)
        }
    }

    /** Splits only when the negotiated MTU actually requires it. */
    private fun fragmentsFor(frame: ByteArray): List<ByteArray> {
        val f = fragmenter ?: return listOf(frame)
        val decoded = org.itantra.proto.Frame.decode(frame).orNull() ?: return listOf(frame)
        if (!f.needsFragmenting(decoded)) return listOf(frame)
        return f.fragment(decoded).map { it.encode() }
    }

    @Suppress("DEPRECATION")
    private fun write(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The API 33 overload returns a BluetoothStatusCodes value, not a GATT
            // status. Both happen to be 0 for success, so comparing against
            // GATT_SUCCESS works by accident -- Android Lint catches it, correctly.
            g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(characteristic)
        }

    override suspend fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        frameCharacteristic = null
        fragmenter = null
        reassembler.clear()
        _state.value = LinkState.IDLE
    }

    fun nextRetryMillis(): Long = backoff.nextDelayMillis()

    companion object {
        /** Shares the RFCOMM service identity, so a peer is one unit however it connects. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        /** Frames travel on one characteristic, notified in and written out. */
        val FRAME_UUID: UUID = UUID.fromString("8ce255c1-200a-11e0-ac64-0800200c9a66")

        /** Client Characteristic Configuration — the standard descriptor. */
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val REQUESTED_MTU = 247

        /** Three bytes of ATT opcode and handle. */
        const val ATT_OVERHEAD = 3

        /** What the default 23-byte MTU leaves. */
        const val MIN_USABLE_MTU = 20
        const val DEFAULT_USABLE_MTU = MIN_USABLE_MTU

        /** What a 247-byte MTU leaves — the figure the fragmenter is normally sized on. */
        const val MAX_PAYLOAD = REQUESTED_MTU - ATT_OVERHEAD

        /** The GATT service a peripheral publishes. */
        fun frameService(): BluetoothGattService {
            val service =
                BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic =
                BluetoothGattCharacteristic(
                    FRAME_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    CCC_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(characteristic)
            return service
        }
    }
}

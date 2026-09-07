package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withTimeoutOrNull
import org.itantra.proto.Frame
import java.util.UUID

/**
 * A radio channel: every frame goes out to whoever is listening, and nothing is paired.
 *
 * ## Why this exists
 *
 * `docs/PROTOCOL.md` section 8 has said since week 2 that every frame is broadcast to every
 * unit and that there is **no destination field**. RFCOMM is the opposite shape — a virtual
 * serial cable between two bonded devices — so making a broadcast protocol run over it
 * needed a bond per pair, a dial-or-listen decision, and a roster. `docs/TRANSPORT.md`
 * section 8 records what that costs in bold: *"Pairing is the most common cause of
 * demonstration failure."*
 *
 * BLE advertising is the shape the protocol already had. A unit advertises a frame; every
 * unit scanning receives it. No bond, no pairing dialog, no discovery window, no connection,
 * no roster. Two handsets with the application open are on the same channel.
 *
 * ## Why the frames fit
 *
 * Measured on a handset: a template alert is 13 B, an ordinary sentence 29–47 B, the longest
 * seen 85 B. Legacy advertising carries about 24 B of payload once the service-data header
 * is paid for, which is enough for a template and not for a sentence. **Extended**
 * advertising — Bluetooth 5, and every handset this targets — carries hundreds. The MTU is
 * read from the controller at runtime rather than assumed, and [Session] fragments anything
 * that does not fit.
 *
 * ## What is given up, honestly
 *
 * Rate. An advertisement goes out a few times a second, against RFCOMM's ~200 kbps. That is
 * ample for a 47 B frame and useless for audio, which is why RFCOMM stays the transport for
 * paired units and this is the one that needs no arranging.
 *
 * Delivery guarantees. Nothing acknowledges an advertisement. The protocol was built for
 * that: alerts already carry ACK-with-retry, the replay window discards the duplicates a
 * repeating advertisement produces, and the relay seen-set stops a rebroadcast looping.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE and BLUETOOTH_SCAN are requested first
class BleBroadcastLink(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    override val name: String = "ble-broadcast",
) : Link, SignalSource {
    /**
     * Every advertisement heard, with its strength -- repeats included, because each one
     * is a measurement. See [Signal].
     */
    private val _signals =
        MutableSharedFlow<Signal>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val signals: Flow<Signal> get() = _signals.asSharedFlow()

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

    /**
     * How much of a frame fits in one advertisement, asked of the controller.
     *
     * The service-data header costs the 16-byte UUID plus its own length and type bytes, and
     * the advertising flags cost three more. Assuming a number here rather than asking is
     * how a frame silently fails to advertise on one handset and works on another.
     */
    override val mtu: Int
        get() {
            val extended =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)
            val budget =
                if (extended) {
                    runCatching { adapter.leMaximumAdvertisingDataLength }.getOrDefault(LEGACY_BUDGET)
                } else {
                    LEGACY_BUDGET
                }
            val overhead = if (extended) SERVICE_DATA_OVERHEAD else LEGACY_OVERHEAD
            return (budget - overhead).coerceAtLeast(MIN_MTU)
        }

    /** Frames waiting for the air. Dropping the oldest is right: stale speech is not worth sending. */
    private val outgoing = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var pump: Job? = null

    /**
     * The one advertising set this link ever holds, or null while there is none.
     *
     * One set, its data replaced per frame, rather than a set per frame. The first version
     * started a set for every frame and stopped it through one shared callback object.
     * Android keys its callback registry on that object, and the *previous* set's
     * asynchronous "stopped" notification unregisters whatever the callback is mapped to
     * by the time it lands -- which is the new set. That set could then never be stopped:
     * it advertised its frame for ever, the peer heard it again every four seconds, and
     * after sixteen of them the controller answered every start with "too many
     * advertisers" and this unit went mute. Seen on an SM-S947B: sixteen ongoing sets in
     * `dumpsys bluetooth_manager`, every hello refused.
     */
    @Volatile
    private var set: AdvertisingSet? = null

    /** Settled by the callback for the set being started: the set, or null on refusal. */
    @Volatile
    private var starting: CompletableDeferred<AdvertisingSet?>? = null

    private val heard = LinkedHashMap<Int, Long>()

    private val advertiseCallback =
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                set: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS || set == null) {
                    // Ignoring this status was a mistake worth not repeating: the
                    // controller refuses an advertisement for perfectly ordinary reasons --
                    // data too large for the chosen mode, too many sets already registered --
                    // and a silent refusal looks exactly like a working radio with nobody
                    // listening.
                    Log.w(TAG, "advertisement refused, status $status (${describe(status)})")
                    _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                    this@BleBroadcastLink.set = null
                    starting?.complete(null)
                    return
                }
                this@BleBroadcastLink.set = set
                starting?.complete(set)
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                this@BleBroadcastLink.set = null
            }

            override fun onAdvertisingDataSet(
                set: AdvertisingSet?,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS) {
                    Log.w(TAG, "advertising data refused, status $status (${describe(status)})")
                    _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                }
            }

            override fun onAdvertisingEnabled(
                set: AdvertisingSet?,
                enable: Boolean,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS && enable) {
                    Log.w(TAG, "could not put the set on the air, status $status (${describe(status)})")
                }
            }
        }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val payload =
                    result?.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                if (payload.isEmpty()) return
                // Before the repeat check: a frame is delivered once, but every hearing of
                // it is a reading of the sender's distance.
                if (payload.size >= Frame.HEADER_SIZE) {
                    _signals.tryEmit(
                        Signal(
                            src = payload[SRC_OFFSET].toInt() and 0xFF,
                            keyId = payload[KEYID_OFFSET].toInt() and 0xFF,
                            rssi = result.rssi,
                            atMillis = android.os.SystemClock.elapsedRealtime(),
                        ),
                    )
                }
                if (!isNew(payload)) return
                Log.i(TAG, "heard ${payload.size} B, rssi ${result.rssi}")

                _metrics.update {
                    it.copy(
                        framesReceived = it.framesReceived + 1,
                        bytesReceived = it.bytesReceived + payload.size,
                        rssi = result.rssi,
                    )
                }
                scope.launch { _incoming.emit(payload) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Recoverable: the radio may come back, and the operator is told by the
                // banner rather than by a scan error code.
                _state.value = LinkState.DEGRADED
            }
        }

    override suspend fun connect() {
        if (_state.value == LinkState.CONNECTED && pump?.isActive == true) return
        // Re-armed from scratch. A radio switched off and on leaves the scanner stopped
        // and the advertising set gone, with nothing to notice: the first version returned
        // here as long as the transmit loop was alive, which it always was, so the channel
        // stayed "connected" and carried nothing until the application was restarted.
        pump?.cancel()
        pump = null
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        stopAdvertising()
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            _state.value = LinkState.DEGRADED
            return
        }
        if (!extendedSupported()) {
            // A legacy advertisement holds 31 bytes, of which 21 are flags and the
            // service-data header. The smallest frame is 29. Nothing can ever be carried,
            // and saying "connected" while dropping every frame is how a handset looks
            // like it is on the channel and is not. The other roads still run.
            Log.w(
                TAG,
                "no extended advertising on this handset: the BLE channel holds $mtu B " +
                    "and the smallest frame is $MIN_FRAME_BYTES B",
            )
            _state.value = LinkState.DEGRADED
            return
        }
        val scanner = adapter.bluetoothLeScanner
        val advertiser = adapter.bluetoothLeAdvertiser
        if (scanner == null || advertiser == null) {
            _state.value = LinkState.DEGRADED
            return
        }

        // Filtered on the service UUID so the callback is not woken by every beacon, till
        // and pair of earbuds in range. setLegacy(false) is what admits extended
        // advertisements; without it the scanner reports only the 31-byte kind.
        // Two filters, because a scan matches if any of them does, and the two AD fields
        // involved are not the same field. A frame travels in **service data** (AD type
        // 0x21); a ScanFilter.setServiceUuid matches the **service UUID** list (0x06/0x07).
        // Filtering on the UUID alone while advertising only the data matched nothing ever,
        // which is exactly what "the other phone received nothing" looks like.
        val id = ParcelUuid(SERVICE_UUID)
        val filters =
            listOf(
                ScanFilter.Builder().setServiceData(id, ByteArray(0), ByteArray(0)).build(),
                ScanFilter.Builder().setServiceUuid(id).build(),
            )
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setLegacy(false)
                        setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    }
                }
                .build()

        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure {
                _state.value = LinkState.DEGRADED
                return
            }

        // Listening is the whole of being on a radio channel. There is no peer to connect
        // to and nothing to wait for, so the link is up the moment the scanner is running.
        _state.value = LinkState.CONNECTED
        Log.i(TAG, "channel open: mtu $mtu, extended advertising ${extendedSupported()}")
        pump = scope.launch { transmitLoop() }
    }

    override suspend fun disconnect() {
        pump?.cancel()
        pump = null
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        stopAdvertising()
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        if (_state.value != LinkState.CONNECTED) return
        outgoing.trySend(frame)
    }

    /**
     * Puts one frame on the air at a time.
     *
     * The set is created on the first frame and kept. Every later frame replaces the
     * set's data and switches it on for [AIR_TIME_MILLIS] -- long enough for a scanning
     * unit to catch it across several advertising intervals -- after which the controller
     * switches it off by itself. A frame left on the air is heard again by every peer every
     * few seconds and dropped as a replay each time, and it occupies the radio meanwhile.
     */
    private suspend fun transmitLoop() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val parameters =
            AdvertisingSetParameters.Builder()
                // Not connectable and not scannable: nothing is meant to dial this unit, and
                // a non-connectable set is also the one allowed the largest payload.
                .setConnectable(false)
                .setScannable(false)
                .setLegacyMode(!extendedSupported())
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()

        while (scope.isActive) {
            val frame = outgoing.receive()
            if (frame.size > mtu) {
                // Session fragments to the link's MTU, so this should not happen -- and if
                // it does, dropping it silently is how a channel looks alive and carries
                // nothing.
                Log.w(TAG, "frame of ${frame.size} B exceeds the $mtu B advertisement; dropped")
                _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                continue
            }

            val data =
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    // Both: the data carries the frame, and the UUID makes the
                    // advertisement match a service-UUID filter as well as a service-data
                    // one. Sixteen bytes against an extended budget of over sixteen
                    // hundred, and it removes a whole class of "why is nothing arriving".
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .addServiceData(ParcelUuid(SERVICE_UUID), frame)
                    .build()

            val existing = set
            val onAir =
                if (existing != null) {
                    runCatching {
                        existing.setAdvertisingData(data)
                        existing.enableAdvertising(true, airUnits(frame), 0)
                    }.onFailure {
                        // The set is gone under us -- the radio was cycled. Forget it and
                        // start afresh on the next frame; this one is lost.
                        Log.w(TAG, "the advertising set failed: $it")
                        stopAdvertising()
                    }.isSuccess
                } else {
                    start(advertiser, parameters, data, airUnits(frame)) != null
                }

            if (onAir) {
                Log.i(TAG, "advertising ${frame.size} B (mtu $mtu, extended ${extendedSupported()})")
                _metrics.update {
                    it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
                }
                delay(airMillis(frame) + SETTLE_MILLIS)
            } else {
                _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                // A controller with no set to give is not going to have one in ten
                // milliseconds. Wait before asking again, so the queue does not spin.
                delay(START_RETRY_MILLIS)
            }
        }
    }

    /**
     * Starts the set with its first frame, and waits for the controller's answer.
     *
     * On the air for [AIR_TIME_MILLIS] from the start, like every frame after it. The
     * callback is the one registered for this link, and it is registered exactly once per
     * set, which is the whole of the fix described on [set].
     */
    private suspend fun start(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        parameters: AdvertisingSetParameters,
        data: AdvertiseData,
        airUnits: Int,
    ): AdvertisingSet? {
        val answer = CompletableDeferred<AdvertisingSet?>()
        starting = answer
        val began =
            runCatching {
                advertiser.startAdvertisingSet(parameters, data, null, null, null, airUnits, 0, advertiseCallback)
            }
        if (began.isFailure) {
            Log.w(TAG, "startAdvertisingSet threw: ${began.exceptionOrNull()}")
            starting = null
            // The callback may be left registered against a set that never started;
            // clearing it is what lets the next attempt register again.
            runCatching { advertiser.stopAdvertisingSet(advertiseCallback) }
            return null
        }
        val result = withTimeoutOrNull(START_TIMEOUT_MILLIS) { answer.await() }
        starting = null
        if (result == null) runCatching { advertiser.stopAdvertisingSet(advertiseCallback) }
        return result
    }

    /**
     * How long a frame stays on the air.
     *
     * Measured on two SM-S947B handsets scanning at low latency: of hellos put on the air
     * for 400 ms, roughly one in three was heard. An advertisement is not a packet; it is
     * a chance, repeated every advertising interval, and a scanner's own duty cycle
     * decides how many of those chances it takes. A message gets two and a half seconds
     * -- some twenty-five chances -- because a message missed is a message lost. A hello
     * gets less: there is another one in five seconds, and the channel is shared.
     */
    private fun airMillis(frame: ByteArray): Long = if (isHello(frame)) HELLO_AIR_TIME_MILLIS else AIR_TIME_MILLIS

    private fun airUnits(frame: ByteArray): Int = (airMillis(frame) / 10).toInt()

    /** The type nibble of the frame header, without decoding the frame. */
    private fun isHello(frame: ByteArray): Boolean =
        frame.size > 1 && ((frame[1].toInt() shr 4) and 0xF) == HEARTBEAT_TYPE

    private fun stopAdvertising() {
        // Only when one is registered, or being registered. Stopping a set that was never
        // started logs "callback does not belong to any advertising set" on every call,
        // which buries the failures that matter.
        if (set == null && starting == null) return
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseCallback) }
        set = null
        starting?.complete(null)
        starting = null
    }

    private fun extendedSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)

    /** @return false when this exact frame was already surfaced within [REPEAT_WINDOW_MILLIS]. */
    private fun isNew(payload: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        val key = payload.contentHashCode()
        synchronized(heard) {
            heard.entries.removeAll { now - it.value > REPEAT_WINDOW_MILLIS }
            if (heard.containsKey(key)) return false
            if (heard.size >= MAX_TRACKED) {
                heard.remove(heard.keys.first())
            }
            heard[key] = now
        }
        return true
    }

    companion object {
        private const val TAG = "itantra-ble"

        /** The controller's own words for a refusal, so a log line names the cause. */
        private fun describe(status: Int): String =
            when (status) {
                AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "unknown"
            }

        /** The same service identifier RFCOMM advertises, so one net has one name. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        /** A legacy advertisement is 31 bytes in total, three of them the flags. */
        const val LEGACY_BUDGET = 31

        /**
         * Flags, the 128-bit service UUID entry, and the service-data entry's own header.
         *
         * Both entries are present now: 2 + 16 for the UUID list and 2 + 16 before the
         * payload, plus 3 for the flags.
         */
        const val SERVICE_DATA_OVERHEAD = 39

        /** A legacy advertisement carries only the flags and the service-data entry. */
        const val LEGACY_OVERHEAD = 21

        /** Header, one template byte, the full tag, and the CRC. */
        const val MIN_FRAME_BYTES = Frame.HEADER_SIZE + 1 + 16 + Frame.CRC_SIZE

        /** Below this nothing useful fits and fragmentation would never terminate. */
        const val MIN_MTU = 8

        /** A message: long enough that a scanner taking one chance in three still hears it. */
        const val AIR_TIME_MILLIS = 2_500L

        /** A hello: another follows in five seconds, and the channel is shared. */
        const val HELLO_AIR_TIME_MILLIS = 600L

        /** `MessageType.HEARTBEAT`, as it sits in the high nibble of header byte 1. */
        const val HEARTBEAT_TYPE = 0x5

        /** Header bytes 7 and 8, per `docs/PROTOCOL.md` section 1. */
        const val SRC_OFFSET = 7
        const val KEYID_OFFSET = 8

        /** After the air time, before the next frame's data is set: the controller's own turnaround. */
        const val SETTLE_MILLIS = 50L

        /** How long to wait for the controller to answer a start. */
        const val START_TIMEOUT_MILLIS = 3_000L

        /** After a refused start, how long before the next frame tries again. */
        const val START_RETRY_MILLIS = 5_000L

        /** A repeated advertisement inside this window is the same transmission. Longer than any air time. */
        const val REPEAT_WINDOW_MILLIS = 4_000L

        /** Bounded: a busy channel must not grow this map without limit. */
        const val MAX_TRACKED = 256
    }
}

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
            return (budget - SERVICE_DATA_OVERHEAD).coerceAtLeast(MIN_MTU)
        }

    /** Frames waiting for the air. Dropping the oldest is right: stale speech is not worth sending. */
    private val outgoing = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var pump: Job? = null
    private var advertising: AdvertisingSet? = null

    /**
     * Frames heard recently, so one advertisement repeated on the air is surfaced once.
     *
     * The replay window would discard the duplicates anyway; catching them here keeps them
     * out of the AEAD, which is the expensive part of receiving.
     */
    private val heard = LinkedHashMap<Int, Long>()

    private val advertiseCallback =
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                set: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                advertising = set
                active = status == ADVERTISE_SUCCESS
                if (status != ADVERTISE_SUCCESS) {
                    // Ignoring this status was a mistake worth not repeating: the
                    // controller refuses an advertisement for perfectly ordinary reasons --
                    // data too large for the chosen mode, too many sets already registered --
                    // and a silent refusal looks exactly like a working radio with nobody
                    // listening.
                    Log.w(TAG, "advertisement refused, status $status (${describe(status)})")
                    _metrics.update { it.copy(framesLost = it.framesLost + 1) }
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                advertising = null
                active = false
            }
        }

    /** Whether a set is registered, so a stop is not attempted before any start. */
    @Volatile
    private var active = false

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val payload =
                    result?.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                if (payload.isEmpty()) return
                if (!isNew(payload)) return

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
        if (pump?.isActive == true) return
        val scanner = adapter.bluetoothLeScanner
        val advertiser = adapter.bluetoothLeAdvertiser
        if (scanner == null || advertiser == null) {
            _state.value = LinkState.DEGRADED
            return
        }

        // Filtered on the service UUID so the callback is not woken by every beacon, till
        // and pair of earbuds in range. setLegacy(false) is what admits extended
        // advertisements; without it the scanner reports only the 31-byte kind.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
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
     * A controller advertises one payload per set until told otherwise, so frames are queued
     * rather than overlapped. Each is held for [AIR_TIME_MILLIS] — long enough for a
     * scanning unit to catch it across several advertising intervals, short enough that a
     * queue of them still drains at conversational speed.
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
                    .addServiceData(ParcelUuid(SERVICE_UUID), frame)
                    .build()

            stopAdvertising()
            runCatching {
                advertiser.startAdvertisingSet(parameters, data, null, null, null, advertiseCallback)
            }.onSuccess {
                Log.i(TAG, "advertising ${frame.size} B (mtu $mtu, extended ${extendedSupported()})")
                _metrics.update {
                    it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + frame.size)
                }
            }.onFailure { failure ->
                Log.w(TAG, "startAdvertisingSet threw: $failure")
                _metrics.update { it.copy(framesLost = it.framesLost + 1) }
            }
            delay(AIR_TIME_MILLIS)
        }
    }

    private fun stopAdvertising() {
        // Only when one is registered. Stopping a set that was never started logs
        // "Fail to get GATT" on every frame, which buries the failures that matter.
        if (!active) return
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseCallback) }
        advertising = null
        active = false
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

        /** 16-byte UUID, its length and type bytes, and the advertising flags. */
        const val SERVICE_DATA_OVERHEAD = 21

        /** Below this nothing useful fits and fragmentation would never terminate. */
        const val MIN_MTU = 8

        /** Long enough to be caught across several advertising intervals. */
        const val AIR_TIME_MILLIS = 400L

        /** A repeated advertisement inside this window is the same transmission. */
        const val REPEAT_WINDOW_MILLIS = 4_000L

        /** Bounded: a busy channel must not grow this map without limit. */
        const val MAX_TRACKED = 256
    }
}

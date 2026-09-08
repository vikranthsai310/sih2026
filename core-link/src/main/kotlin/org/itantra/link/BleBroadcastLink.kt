package org.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
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
 * BLE advertising is the shape the protocol already had. A unit advertises its frames; every
 * unit scanning receives them. No bond, no pairing dialog, no discovery window, no connection,
 * no roster. Two handsets with the application open are on the same channel.
 *
 * ## The air is a buffer, not a queue
 *
 * An advertisement is a chance repeated every hundred milliseconds, and a scanner takes only
 * some of the chances. The version before this one put each frame on the air for two and a
 * half seconds and then took it off for good; a receiver that missed all twenty-five chances
 * -- its scanner between duty cycles, or restarting, or busy with the hello that went out
 * just before -- never heard that message, and nothing said so. That is "one message arrived
 * and the next did not".
 *
 * Now the set advertises **all the time**, and what it advertises is [OnAir]: this unit's
 * latest hello and presence, then everything it has said recently, each message kept for
 * at least five seconds and up to thirty. A receiver that hears any one advertisement in
 * that window gets every frame in it. Each frame is delivered once -- repeats are dropped
 * here by content and above by the replay window -- so the cost of a frame on the air for
 * thirty seconds is nothing but air. See [AirBlob] for how several frames share one
 * advertisement.
 *
 * ## Why the frames fit
 *
 * Measured on a handset: a template alert is 13 B, an ordinary sentence 29–47 B, the longest
 * seen 85 B. Legacy advertising carries about 24 B of payload once the service-data header
 * is paid for, which is enough for a template and not for a sentence. **Extended**
 * advertising — Bluetooth 5, and every handset this targets — carries hundreds. The buffer is
 * kept to what one radio packet holds, about two hundred bytes of frames, because a chain of
 * packets is heard far less often than one; a single frame larger than that goes alone,
 * chained, up to what the controller reports it can carry, and [Session] fragments anything
 * beyond even that.
 *
 * ## What is given up, honestly
 *
 * Rate. A message holds its place for five seconds when others are waiting, against
 * RFCOMM's ~200 kbps. That is the cadence of a walkie-talkie and useless for audio, which is
 * why RFCOMM stays the transport for paired units and this is the one that needs no
 * arranging.
 *
 * Delivery guarantees. Nothing acknowledges an advertisement. The protocol was built for
 * that: alerts already carry ACK-with-retry, the replay window discards the duplicates a
 * repeating advertisement produces, and the relay seen-set stops a rebroadcast looping.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE and BLUETOOTH_SCAN are requested first
class BleBroadcastLink(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    /** This unit's node id, stamped on every advertisement so a hearing is a reading of *this* unit's distance. */
    private val localSrc: Int = AirBlob.UNKNOWN,
    /** The key id the frames carry, stamped beside it so a foreign net's readings are ignored. */
    private val keyId: Int = AirBlob.UNKNOWN,
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
     * The largest frame this link will carry: what the controller can advertise at all,
     * less the service-data header and the blob's own.
     *
     * Asked of the controller rather than assumed, because assuming a number here is how a
     * frame silently fails to advertise on one handset and works on another. A frame this
     * size travels alone and chained; see [OnAir].
     */
    override val mtu: Int get() = hardBudget()

    private fun hardBudget(): Int {
        val extended = extendedSupported()
        val budget =
            if (extended) {
                runCatching { adapter.leMaximumAdvertisingDataLength }.getOrDefault(LEGACY_BUDGET)
            } else {
                LEGACY_BUDGET
            }
        val overhead = if (extended) SERVICE_DATA_OVERHEAD else LEGACY_OVERHEAD
        return (budget - overhead - AirBlob.HEADER_BYTES).coerceAtLeast(MIN_MTU)
    }

    /** What fits in one packet, or everything the controller has if that is less. */
    private fun softBudget(): Int =
        minOf(SINGLE_PACKET_AD_BYTES - SERVICE_DATA_OVERHEAD - AirBlob.HEADER_BYTES, hardBudget())

    /** What this unit has on the air. Built when the channel opens, sized to the controller. */
    @Volatile
    private var onAir: OnAir? = null

    /** Wakes the transmit loop when the buffer changes. Conflated: one wake is as good as ten. */
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    private var pump: Job? = null

    /**
     * The one advertising set this link ever holds, or null while there is none.
     *
     * One set, its data replaced as the buffer changes, rather than a set per frame. The
     * first version started a set for every frame and stopped it through one shared
     * callback object. Android keys its callback registry on that object, and the
     * *previous* set's asynchronous "stopped" notification unregisters whatever the
     * callback is mapped to by the time it lands -- which is the new set. That set could
     * then never be stopped: it advertised its frame for ever, the peer heard it again
     * every four seconds, and after sixteen of them the controller answered every start
     * with "too many advertisers" and this unit went mute. Seen on an SM-S947B: sixteen
     * ongoing sets in `dumpsys bluetooth_manager`, every hello refused.
     */
    @Volatile
    private var set: AdvertisingSet? = null

    /** Whether the set is enabled, as far as this link knows. */
    @Volatile
    private var airing = false

    /** The blob the controller last accepted, so an unchanged buffer leaves the radio alone. */
    @Volatile
    private var onTheAir: ByteArray? = null

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
                    this@BleBroadcastLink.set = null
                    airing = false
                    starting?.complete(null)
                    return
                }
                this@BleBroadcastLink.set = set
                airing = true
                starting?.complete(set)
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                this@BleBroadcastLink.set = null
                airing = false
                onTheAir = null
            }

            override fun onAdvertisingDataSet(
                set: AdvertisingSet?,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS) {
                    Log.w(TAG, "advertising data refused, status $status (${describe(status)})")
                }
                dataSet?.complete(status)
            }

            override fun onAdvertisingEnabled(
                set: AdvertisingSet?,
                enable: Boolean,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS && enable) {
                    Log.w(TAG, "could not put the set on the air, status $status (${describe(status)})")
                }
                if (enable) {
                    enabled?.complete(status)
                    return
                }
                val asked = disabled
                if (asked != null) {
                    asked.complete(status)
                    return
                }
                // Unasked: the controller took the set off the air by itself. Nothing was
                // given a duration, so this is a stack that has lost the set. The buffer is
                // intact; the loop puts it back.
                Log.w(TAG, "the set went off the air unasked (status $status); re-arming")
                airing = false
                onTheAir = null
                nudges.trySend(Unit)
            }
        }

    /** The step of the transmit loop waiting on the controller, if any. See [putOnAir]. */
    @Volatile
    private var dataSet: CompletableDeferred<Int>? = null

    @Volatile
    private var enabled: CompletableDeferred<Int>? = null

    @Volatile
    private var disabled: CompletableDeferred<Int>? = null

    /** The scan's filters and settings, kept so the scan can be restarted as it was. */
    private var scanFilters: List<ScanFilter> = emptyList()
    private var scanSettings: ScanSettings? = null

    /** Restarts the scan on a cycle and watches the radio. See [keepScanning]. */
    private var keeper: Job? = null

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val payload =
                    result?.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                if (payload.isEmpty()) return
                val parsed = AirBlob.decode(payload)
                // Before the repeat check: a frame is delivered once, but every hearing of
                // the advertisement is a reading of the advertiser's distance.
                val src = parsed.src
                val advertisersKey = parsed.keyId
                if (src != null && advertisersKey != null) {
                    _signals.tryEmit(
                        Signal(
                            src = src,
                            keyId = advertisersKey,
                            rssi = result.rssi,
                            atMillis = SystemClock.elapsedRealtime(),
                            txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
                        ),
                    )
                }
                val fresh = parsed.frames.filter { isNew(it) }
                if (fresh.isEmpty()) return
                Log.i(
                    TAG,
                    "heard ${payload.size} B from node $src: ${parsed.frames.size} frames, " +
                        "${fresh.size} new, rssi ${result.rssi}, tx ${result.txPower}",
                )
                _metrics.update {
                    it.copy(
                        framesReceived = it.framesReceived + fresh.size,
                        bytesReceived = it.bytesReceived + fresh.sumOf { f -> f.size },
                        rssi = result.rssi,
                    )
                }
                // One coroutine for the lot, so the frames of one advertisement arrive in
                // the order they were sent -- fragments depend on it.
                scope.launch { fresh.forEach { _incoming.emit(it) } }
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
        keeper?.cancel()
        keeper = null
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

        scanFilters = filters
        scanSettings = settings
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure {
                _state.value = LinkState.DEGRADED
                return
            }

        // The buffer survives a re-arm: what was on the air before the radio cycled is
        // still worth hearing. It is sized once the controller can be asked.
        if (onAir == null) {
            onAir =
                OnAir(
                    softBudget = softBudget(),
                    hardBudget = hardBudget(),
                    // So a hello or presence relayed for a far unit queues rather than
                    // taking this unit's own pin on the air.
                    localSrc = localSrc.takeIf { it != AirBlob.UNKNOWN },
                )
        }
        onTheAir = null
        airing = false

        // Listening is the whole of being on a radio channel. There is no peer to connect
        // to and nothing to wait for, so the link is up the moment the scanner is running.
        _state.value = LinkState.CONNECTED
        Log.i(
            TAG,
            "channel open: mtu $mtu, one packet ${softBudget()} B, extended advertising ${extendedSupported()}",
        )
        pump = scope.launch { transmitLoop() }
        keeper?.cancel()
        keeper = scope.launch { keepScanning() }
        nudges.trySend(Unit)
    }

    /**
     * Watches the radio every few seconds, and restarts the scan every [SCAN_CYCLE_MILLIS].
     *
     * The platform puts a time limit on every scan: thirty minutes after it starts, the
     * scan is quietly downgraded to *opportunistic*, which means this application is no
     * longer scanning at all and is only handed results when some other application on
     * the handset happens to scan for the same thing. There is no callback. On a phone
     * with a few Bluetooth accessories that is now and then; on a phone with none it is
     * never. It looked, from the other unit, like messages that stopped arriving half an
     * hour into a session and then arrived again at random. A scan restarted well inside
     * the limit is a scan that never reaches it. The gap between stop and start is a few
     * milliseconds against a buffer that stays on the air for seconds.
     *
     * The radio itself is watched on the same loop. Bluetooth switched off leaves the
     * scanner and the set gone with no callback for either, and a link that goes on saying
     * "connected" is never asked to reconnect. Saying "degraded" is what gets it re-armed
     * on the engine's next recovery tick once the radio is back.
     */
    private suspend fun keepScanning() {
        var lastRestart = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            delay(HEALTH_MILLIS)
            if (_state.value != LinkState.CONNECTED) continue
            if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
                Log.w(TAG, "the radio is off; the channel is closed until it returns")
                degrade()
                continue
            }
            if (SystemClock.elapsedRealtime() - lastRestart < SCAN_CYCLE_MILLIS) continue
            lastRestart = SystemClock.elapsedRealtime()
            val scanner = adapter.bluetoothLeScanner ?: continue
            val settings = scanSettings ?: continue
            runCatching { scanner.stopScan(scanCallback) }
            val restarted = runCatching { scanner.startScan(scanFilters, settings, scanCallback) }
            if (restarted.isFailure) {
                Log.w(TAG, "scan restart failed: ${restarted.exceptionOrNull()}")
                _state.value = LinkState.DEGRADED
            } else {
                Log.i(TAG, "scan restarted")
            }
        }
    }

    /** Off the air and off the scanner, keeping the buffer, until [connect] is asked again. */
    private fun degrade() {
        pump?.cancel()
        pump = null
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        stopAdvertising()
        _state.value = LinkState.DEGRADED
    }

    override suspend fun disconnect() {
        keeper?.cancel()
        keeper = null
        pump?.cancel()
        pump = null
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        stopAdvertising()
        onAir?.clear()
        _state.value = LinkState.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        if (_state.value != LinkState.CONNECTED) return
        val air = onAir ?: return
        if (!air.offer(frame, SystemClock.elapsedRealtime())) {
            // Either this frame or an older waiting one is gone. Counted, because an
            // operator whose message was discarded is entitled to know.
            Log.w(TAG, "a ${frame.size} B frame could not be held for the air (waiting ${air.waitingCount})")
            _metrics.update { it.copy(framesLost = it.framesLost + 1) }
        }
        _metrics.update { it.copy(queueDepth = air.waitingCount) }
        nudges.trySend(Unit)
    }

    /**
     * Keeps the advertisement equal to the buffer.
     *
     * Woken by every [send] and by the buffer's own timetable, it asks [OnAir] what should
     * be on the air now, and programs the set only when the answer has changed. A refusal
     * from the controller costs nothing but a retry: the frames stay in the buffer, the set
     * is torn down and started afresh, and if that fails too the loop waits and asks again.
     * Only a frame the controller has accepted is counted as sent.
     */
    private suspend fun transmitLoop() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val air = onAir ?: return
        val parameters =
            AdvertisingSetParameters.Builder()
                // Not connectable and not scannable: nothing is meant to dial this unit, and
                // a non-connectable set is also the one allowed the largest payload.
                .setConnectable(false)
                .setScannable(false)
                .setLegacyMode(!extendedSupported())
                // Ten advertisements a second, not one. Every one a scanner hears is a
                // chance at the buffer and a reading of how far away the sender is; the
                // locate screen is made of those readings, and at one a second a walk
                // towards a unit showed twenty seconds late. At ten a second it shows
                // within one.
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                // The extended header carries the transmit power the controller actually
                // uses, so a receiver can reckon distance against it. It costs nothing in
                // the payload; legacy advertising has no such header.
                .setIncludeTxPower(extendedSupported())
                .build()

        // What was on the air last time, by content, so a frame new to the air is one not
        // in this set. By content rather than identity: the engine re-sends its hello
        // every five seconds, and a hello identical to the one already pinned is not a
        // frame sent again.
        var previous: Set<Int> = emptySet()

        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val frames = air.contents(now)
            val blob = if (frames.isEmpty()) null else AirBlob.encode(localSrc, keyId, frames)
            val current = onTheAir
            val changed =
                when {
                    blob == null -> current != null
                    current == null -> true
                    else -> !blob.contentEquals(current)
                }

            var retryMillis: Long? = null
            if (changed) {
                if (blob == null) {
                    quiet()
                    onTheAir = null
                    previous = emptySet()
                } else {
                    // Once with the set as it is; if the controller refuses, once more with
                    // a fresh set. A refusal is nearly always the set having died under us
                    // -- the radio cycled, the stack restarted -- and a fresh set is the cure.
                    var accepted = putOnAir(existingSet = true, advertiser, parameters, blob)
                    if (!accepted) {
                        stopAdvertising()
                        accepted = putOnAir(existingSet = false, advertiser, parameters, blob)
                    }
                    if (accepted) {
                        onTheAir = blob
                        val fresh = frames.filter { it.contentHashCode() !in previous }
                        if (fresh.isNotEmpty()) {
                            Log.i(TAG, "advertising ${blob.size} B: ${frames.size} frames, ${fresh.size} new")
                            _metrics.update {
                                it.copy(
                                    framesSent = it.framesSent + fresh.size,
                                    bytesSent = it.bytesSent + fresh.sumOf { f -> f.size },
                                )
                            }
                        }
                        previous = frames.mapTo(HashSet()) { it.contentHashCode() }
                    } else {
                        // A controller with no set to give is not going to have one in ten
                        // milliseconds. The buffer keeps the frames; ask again in a while.
                        onTheAir = null
                        retryMillis = START_RETRY_MILLIS
                    }
                }
            }
            _metrics.update { it.copy(queueDepth = air.waitingCount) }

            // Until the buffer changes by itself, a frame arrives, or the retry is due.
            val due = air.nextChangeMillis(now)?.let { it - now }
            val wait = listOfNotNull(due, retryMillis).minOrNull()?.coerceAtLeast(MIN_TICK_MILLIS)
            if (wait == null) nudges.receive() else withTimeoutOrNull(wait) { nudges.receive() }
        }
    }

    /**
     * Puts one blob on the air and reports honestly whether it is there.
     *
     * Three commands go to the controller -- disable, set data, enable -- and each is
     * answered asynchronously, with a status. The first version issued the last two back
     * to back and took "no exception" for success. Two things go wrong that way. Data set
     * while the set is still enabled must fit one command, 251 bytes; anything longer is
     * refused and the *previous* data goes out again, so a long message vanished without a
     * line in the log. And a controller that has quietly lost the set answers the enable
     * with an error, which was logged and otherwise ignored. So each step waits for its
     * answer, and a refusal at any step is a false return.
     *
     * A blob that fits one command is written **without** the disable, while the set is on
     * the air: the controller swaps the data between two advertisements and there is no
     * gap at all. Only a larger blob pays for the three steps.
     */
    private suspend fun putOnAir(
        existingSet: Boolean,
        advertiser: BluetoothLeAdvertiser,
        parameters: AdvertisingSetParameters,
        blob: ByteArray,
    ): Boolean {
        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                // Both: the data carries the frames, and the UUID makes the
                // advertisement match a service-UUID filter as well as a service-data
                // one. Sixteen bytes, and it removes a whole class of "why is nothing
                // arriving".
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), blob)
                .build()
        val current = set
        if (!existingSet || current == null) {
            return start(advertiser, parameters, data) != null
        }

        if (airing && blob.size + SERVICE_DATA_OVERHEAD <= LIVE_UPDATE_AD_BYTES) {
            if (setData(current, data) == AdvertisingSetCallback.ADVERTISE_SUCCESS) return true
            // Refused live; the long way round below is the second chance.
        }

        val off = CompletableDeferred<Int>()
        disabled = off
        if (runCatching { current.enableAdvertising(false, 0, 0) }.isFailure) return false
        withTimeoutOrNull(STEP_TIMEOUT_MILLIS) { off.await() }
        disabled = null
        airing = false

        if (setData(current, data) != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            Log.w(TAG, "data of ${blob.size} B was not accepted")
            return false
        }

        val on = CompletableDeferred<Int>()
        enabled = on
        if (runCatching { current.enableAdvertising(true, 0, 0) }.isFailure) return false
        val onStatus = withTimeoutOrNull(STEP_TIMEOUT_MILLIS) { on.await() }
        enabled = null
        if (onStatus != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            Log.w(TAG, "the set would not go on the air (status $onStatus)")
            return false
        }
        airing = true
        return true
    }

    /** Writes the data and waits for the controller's answer; a timeout is a refusal. */
    private suspend fun setData(
        current: AdvertisingSet,
        data: AdvertiseData,
    ): Int? {
        val written = CompletableDeferred<Int>()
        dataSet = written
        if (runCatching { current.setAdvertisingData(data) }.isFailure) {
            dataSet = null
            return null
        }
        val status = withTimeoutOrNull(STEP_TIMEOUT_MILLIS) { written.await() }
        dataSet = null
        return status
    }

    /** Nothing to say: the set is kept and switched off, so the next frame needs no start. */
    private suspend fun quiet() {
        val current = set ?: return
        if (!airing) return
        val off = CompletableDeferred<Int>()
        disabled = off
        if (runCatching { current.enableAdvertising(false, 0, 0) }.isSuccess) {
            withTimeoutOrNull(STEP_TIMEOUT_MILLIS) { off.await() }
        }
        disabled = null
        airing = false
    }

    /**
     * Starts the set with its first blob, and waits for the controller's answer.
     *
     * No duration: the set stays on the air until the buffer is empty or the link closes.
     * The callback is the one registered for this link, and it is registered exactly once
     * per set, which is the whole of the fix described on [set].
     */
    private suspend fun start(
        advertiser: BluetoothLeAdvertiser,
        parameters: AdvertisingSetParameters,
        data: AdvertiseData,
    ): AdvertisingSet? {
        val answer = CompletableDeferred<AdvertisingSet?>()
        starting = answer
        val began =
            runCatching {
                advertiser.startAdvertisingSet(parameters, data, null, null, null, 0, 0, advertiseCallback)
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

    private fun stopAdvertising() {
        // Only when one is registered, or being registered. Stopping a set that was never
        // started logs "callback does not belong to any advertising set" on every call,
        // which buries the failures that matter.
        if (set == null && starting == null) return
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseCallback) }
        dataSet?.complete(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        enabled?.complete(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        disabled?.complete(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        set = null
        airing = false
        onTheAir = null
        starting?.complete(null)
        starting = null
    }

    private fun extendedSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)

    /**
     * @return false when this exact frame was already surfaced within [REPEAT_WINDOW_MILLIS].
     *
     * A frame stays on a sender's air for up to thirty seconds and is heard ten times a
     * second while it does; the window is longer than the stay, so each frame reaches the
     * session once. A frame heard again after the window is refused there by the replay
     * window, which is the check that cannot be fooled.
     */
    private fun isNew(frame: ByteArray): Boolean {
        val now = SystemClock.elapsedRealtime()
        val key = frame.contentHashCode()
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
         * Both entries are present: 2 + 16 for the UUID list and 2 + 16 before the
         * payload, plus 3 for the flags.
         */
        const val SERVICE_DATA_OVERHEAD = 39

        /** A legacy advertisement carries only the flags and the service-data entry. */
        const val LEGACY_OVERHEAD = 21

        /**
         * Advertising data that fits one extended advertising packet, without a chain.
         *
         * A secondary-channel packet carries 255 bytes less its extended header -- address,
         * set identifier, transmit power and the header's own bookkeeping, eleven in all --
         * so 244 of data. Four kept back, because the figure is the specification's and the
         * controller's is what counts.
         */
        const val SINGLE_PACKET_AD_BYTES = 240

        /**
         * Advertising data that can replace a set's data while it is on the air.
         *
         * One controller command carries 251 bytes; more than that has to go in several,
         * which the controller allows only for a set that is off.
         */
        const val LIVE_UPDATE_AD_BYTES = 251

        /** Header, one template byte, the full tag, and the CRC. */
        const val MIN_FRAME_BYTES = Frame.HEADER_SIZE + 1 + 16 + Frame.CRC_SIZE

        /** Below this nothing useful fits and fragmentation would never terminate. */
        const val MIN_MTU = 8

        /** How long to wait for the controller to answer a start. */
        const val START_TIMEOUT_MILLIS = 3_000L

        /** After a refused start, how long before the buffer is offered again. */
        const val START_RETRY_MILLIS = 5_000L

        /** How long one controller command may take to be answered before the attempt is given up. */
        const val STEP_TIMEOUT_MILLIS = 1_500L

        /** The loop never spins faster than this, whatever the buffer's timetable says. */
        const val MIN_TICK_MILLIS = 20L

        /** How often the radio is checked. */
        const val HEALTH_MILLIS = 5_000L

        /**
         * The scan is restarted this often: well inside the platform's thirty-minute
         * limit, after which a scan is downgraded to hearing only what other applications
         * scan for, and far outside its limit of five starts in thirty seconds.
         */
        const val SCAN_CYCLE_MILLIS = 10 * 60_000L

        /** A frame heard again inside this window is the same transmission. Longer than any stay on the air. */
        const val REPEAT_WINDOW_MILLIS = OnAir.MAX_AIR_MILLIS + 10_000L

        /** Bounded: a busy channel must not grow this map without limit. */
        const val MAX_TRACKED = 512
    }
}

package org.itantra.app.engine

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.itantra.app.platform.Heading
import org.itantra.app.platform.LocateSiren
import org.itantra.app.platform.PositionSource
import org.itantra.app.ui.LocateState
import org.itantra.link.Signal
import org.itantra.proto.Presence
import kotlin.math.pow

/**
 * Leads an operator to one unit: a siren from signal strength, an arrow from positions.
 *
 * ## What a phone can and cannot measure
 *
 * Bluetooth on a handset reports how *strongly* it hears a sender and nothing about
 * *where from*: direction finding needs an antenna array the phone does not have. So the
 * two questions are answered by two instruments. **How close** comes from signal
 * strength, which rises steeply in the last few metres and is what the siren follows.
 * **Which way** comes from the two positions -- the target's, sent in its beacon, and our
 * own -- against the compass, and is what the arrow follows. Beyond fifteen metres or so
 * the arrow is trustworthy and the siren is vague; inside that the siren is precise and
 * the positions are within their own error of each other, so the screen says "follow the
 * sound".
 *
 * ## Signal to distance
 *
 * The log-distance path-loss model: `d = 10^((P₁ − RSSI) / (10 n))`, with `P₁` the
 * strength expected at one metre and `n` the environment's loss exponent. Both are
 * guesses on a phone -- transmit power, antenna, the hand around it, the wall in between --
 * so the number is shown as an estimate, and the siren is driven by the *smoothed*
 * signal rather than the figure. Readings arrive several times a second while the target
 * beacons; each is passed through a median of the last five, which kills single-reading
 * spikes, and then an exponential average, which keeps the rate from stuttering.
 */
class Locator(
    private val positions: PositionSource?,
    private val heading: Heading?,
    private val siren: LocateSiren = LocateSiren(),
) {
    private val _state = MutableStateFlow<LocateState?>(null)
    val state: StateFlow<LocateState?> = _state.asStateFlow()

    val target: Int? get() = _state.value?.target

    private var name = ""
    private val recent = ArrayDeque<Int>()
    private var smoothedRssi: Double? = null
    private var lastSignalAtMillis = 0L
    private var targetPosition: Presence.Position? = null
    private var targetPositionAtMillis = 0L
    private var targetBeaconing = false
    private var sirenWanted = true

    /** The target's bearing from here, smoothed: positions arrive once a second and jump. */
    private var bearing: Float? = null

    fun start(
        src: Int,
        name: String,
    ) {
        stop()
        this.name = name
        recent.clear()
        smoothedRssi = null
        lastSignalAtMillis = 0L
        targetPosition = null
        targetBeaconing = false
        bearing = null
        positions?.start()
        heading?.onChanged = { tick() }
        heading?.start()
        siren.lost = true
        siren.proximity = 0f
        if (sirenWanted) siren.start()
        _state.value = compute(src, SystemClock.elapsedRealtime())
    }

    fun stop() {
        siren.stop()
        heading?.onChanged = null
        heading?.stop()
        _state.value = null
    }

    val isActive: Boolean get() = _state.value != null

    fun setSiren(on: Boolean) {
        sirenWanted = on
        if (!isActive) return
        if (on) siren.start() else siren.stop()
        tick()
    }

    /** A signal reading from any unit; only the target's matter here. */
    @Synchronized
    fun onSignal(signal: Signal) {
        val t = target ?: return
        if (signal.src != t) return
        recent.addLast(signal.rssi)
        while (recent.size > MEDIAN_WINDOW) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2].toDouble()
        smoothedRssi = smoothedRssi?.let { it + (median - it) * SMOOTHING } ?: median
        lastSignalAtMillis = signal.atMillis
        tick()
    }

    /** The target's presence: its position, and whether it has answered the request. */
    @Synchronized
    fun onTargetPresence(
        src: Int,
        presence: Presence,
        nowMillis: Long,
    ) {
        if (src != target) return
        if (presence.name.isNotBlank()) name = presence.name
        targetBeaconing = presence.beaconing
        presence.position?.let {
            targetPosition = it
            targetPositionAtMillis = nowMillis - it.ageSeconds * 1000L
        }
        tick()
    }

    /** Called often -- every compass reading, every second at least -- to keep the screen live. */
    @Synchronized
    fun tick() {
        val t = target ?: return
        val now = SystemClock.elapsedRealtime()
        val next = compute(t, now)
        siren.lost = next.lost
        siren.proximity = next.proximity
        _state.value = next
    }

    private fun compute(
        src: Int,
        now: Long,
    ): LocateState {
        val lost = lastSignalAtMillis == 0L || now - lastSignalAtMillis > LOST_AFTER_MILLIS
        val rssi = smoothedRssi
        val estimated = rssi?.let { metresFor(it) }
        val proximity = if (lost || rssi == null) 0f else proximityFor(rssi)

        val ours: Location? = positions?.latest
        ours?.let { heading?.calibrate(it) }
        val theirs = targetPosition
        var gps: Int? = null
        var relativeBearing: Float? = null
        var note: String? = null
        val compass = heading?.degrees
        val needsCalibration = heading?.needsCalibration == true
        when {
            positions == null || !positions.isPermitted() -> note = "Location permission is off, so there is no arrow."
            theirs == null ->
                note = if (targetBeaconing) "Waiting for $name's position…" else "Waiting for $name to answer…"
            now - targetPositionAtMillis > POSITION_STALE_MILLIS -> note = "$name's last position is old."
            ours == null -> note = "Waiting for this handset's own position…"
            compass == null -> note = "Waiting for the compass…"
            else -> {
                val results = FloatArray(2)
                Location.distanceBetween(ours.latitude, ours.longitude, theirs.latitude, theirs.longitude, results)
                gps = results[0].toInt()
                // The bearing moves only when a position does, once a second and by a
                // jump, so it is the one thing smoothed here. The compass is not smoothed
                // again: it is already steady, and a second stage on top of it is what
                // made the arrow trail the handset by a second when it was turned.
                bearing = Heading.smooth(bearing, Heading.normalise(results[1]), BEARING_SMOOTHING)
                relativeBearing = Heading.normalise(bearing!! - compass)
                val error = ours.accuracy.toInt() + theirs.accuracyMetres
                note =
                    when {
                        gps < error -> "Within $error m — the positions are too close to point. Follow the sound."
                        needsCalibration -> "Compass unsure: move the handset in a figure of eight."
                        else -> null
                    }
            }
        }

        return LocateState(
            target = src,
            name = name,
            proximity = proximity,
            rssi = rssi?.toInt(),
            estimatedMetres = if (lost) null else estimated,
            gpsMetres = gps,
            targetAccuracyMetres = theirs?.accuracyMetres,
            relativeBearingDeg = relativeBearing,
            headingDeg = compass,
            bearingDeg = bearing,
            compassErrorDeg = heading?.errorDegrees,
            compassNeedsCalibration = needsCalibration,
            lost = lost,
            beaconing = targetBeaconing,
            arrowNote = note,
            sirenOn = sirenWanted,
        )
    }

    companion object {
        /** Signal expected one metre from a handset advertising at high power, dBm. */
        const val RSSI_AT_ONE_METRE = -59.0

        /** Path-loss exponent: 2 is free space, 3 to 4 indoors. In between for a field. */
        const val PATH_LOSS_EXPONENT = 2.6

        /** Where the siren is at its fastest: this close, the eyes take over. */
        const val NEAR_RSSI = -50.0

        /** Where the siren is at its slowest: fainter than this and it is barely there. */
        const val FAR_RSSI = -95.0

        const val MEDIAN_WINDOW = 5
        const val SMOOTHING = 0.35

        /** Half of each new bearing: two position fixes to settle, and a bad one is halved. */
        const val BEARING_SMOOTHING = 0.5f

        /** Beacons come every second; five missed is a unit that has moved out of range. */
        const val LOST_AFTER_MILLIS = 6_000L

        const val POSITION_STALE_MILLIS = 30_000L

        fun metresFor(rssi: Double): Int =
            10.0.pow(
                (RSSI_AT_ONE_METRE - rssi) / (10 * PATH_LOSS_EXPONENT),
            ).toInt().coerceIn(0, 999)

        /** 0 at [FAR_RSSI], 1 at [NEAR_RSSI], on the signal's own logarithmic scale. */
        fun proximityFor(rssi: Double): Float = ((rssi - FAR_RSSI) / (NEAR_RSSI - FAR_RSSI)).toFloat().coerceIn(0f, 1f)
    }
}

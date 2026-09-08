package org.itantra.app.engine

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.itantra.app.platform.LocateSiren
import org.itantra.app.platform.PositionSource
import org.itantra.app.ui.LocateState
import org.itantra.app.ui.SoundFrom
import org.itantra.app.ui.Trend
import org.itantra.link.Signal
import org.itantra.proto.Presence
import kotlin.math.ln
import kotlin.math.pow

/**
 * Leads an operator to one unit by sound: how close, from signal strength, and which
 * way, from the target's own chirp.
 *
 * ## What a phone can and cannot measure
 *
 * Bluetooth on a handset reports how *strongly* it hears a sender and nothing about
 * *where from*: direction finding needs an antenna array the phone does not have. An
 * arrow from the two GPS positions was tried and taken off the screen: the fixes wander
 * by more than the distance between the units for the whole of the part of the search
 * where an arrow would matter. So **how close** is the instrument, and it is given to
 * the ear as well as the eye -- a siren on the searcher's handset that quickens as the
 * signal rises, or a chirp from the target that two ears can place to a few degrees,
 * which is the one thing about direction a handset can offer.
 *
 * ## Signal to distance
 *
 * The log-distance path-loss model: `d = 10^((P₁ − RSSI) / (10 n))`, with `P₁` the
 * strength expected at one metre and `n` the environment's loss exponent. Both are
 * guesses on a phone -- transmit power, antenna, the hand around it, the wall in between --
 * so the number is shown as an estimate, and the siren is driven by the *smoothed*
 * signal ([SignalSmoother]) rather than the figure.
 *
 * Under three metres the figure is given in centimetres, because that is the resolution
 * the last steps want, and beside it the spread of the recent readings, because a signal
 * figure without its width is a false precision: "60 cm" from a reading that has ranged
 * over 40 to 110 is "40 to 110 cm".
 *
 * ## The distance is calibrated on the way in
 *
 * Far out, the distance between the two GPS positions is known -- each side's fixes are
 * averaged, weighted by their stated accuracy -- and every signal reading taken there is
 * a calibration point: a straight line through (log distance, signal) has this pair's
 * one-metre strength as its intercept and its loss rate as its slope. [PathLossFit] fits
 * that line as the operator walks in, so by the time the positions overlap and the
 * siren has taken over, the metres on the screen are the metres this phone measured
 * against that phone, in this field, and not a figure assumed for every handset ever
 * made. While the positions are further apart than their combined error, the GPS
 * distance is the better figure and is the one shown large.
 *
 * ## The sound follows the distance
 *
 * The siren's [LocateSiren.proximity] is the calibrated distance on a logarithmic scale
 * ([proximityForMetres]), not the raw signal: the sound and the figure on the screen say
 * the same thing, and the same handset that reads "about 2 m" at a given rate on one
 * phone reads it at that rate on another. The signal's rise or fall over the last few
 * seconds is given as [Trend], because the siren says it to the ear and a word says it
 * to the eye.
 */
class Locator(
    private val positions: PositionSource?,
    private val siren: LocateSiren = LocateSiren(),
) {
    private val _state = MutableStateFlow<LocateState?>(null)
    val state: StateFlow<LocateState?> = _state.asStateFlow()

    val target: Int? get() = _state.value?.target

    private var name = ""
    private val signal = SignalSmoother()
    private var lastSignalAtMillis = 0L

    /** The target's transmit power, from its advertising header, when it says. */
    private var targetTxPower: Int? = null
    private var targetPosition: Presence.Position? = null
    private var targetPositionAtMillis = 0L
    private var targetBeaconing = false
    private var targetChirping = false
    private var targetPresenceAtMillis = 0L
    private var sound = SoundFrom.THIS_PHONE

    /** A position averaged over its recent fixes. */
    private class Estimate(
        var latitude: Double,
        var longitude: Double,
        var accuracy: Float,
        /** The clock of the last fix folded in, so one fix is folded in once. */
        var fixAtMillis: Long,
    )

    private var ours: Estimate? = null
    private var theirs: Estimate? = null

    /** Whether the fixes are far enough apart for their distance to mean anything, with hysteresis. */
    private var gpsApart = false

    /** The signal-to-distance model, fitted to this pair while the distance is known. */
    private val pathLoss = PathLossFit()

    /** Which fixes the model has already learned from, so one pair teaches it once. */
    private var learnedOursAt = 0L
    private var learnedTheirsAt = 0L

    /** The smoothed signal over the last few seconds, for closing / further. */
    private class Trail(val atMillis: Long, val rssi: Double)

    private val trail = ArrayDeque<Trail>()

    fun start(
        src: Int,
        name: String,
    ) {
        stop()
        this.name = name
        signal.clear()
        targetTxPower = null
        lastSignalAtMillis = 0L
        targetPosition = null
        targetPositionAtMillis = 0L
        targetBeaconing = false
        targetChirping = false
        targetPresenceAtMillis = 0L
        ours = null
        theirs = null
        gpsApart = false
        pathLoss.clear()
        learnedOursAt = 0L
        learnedTheirsAt = 0L
        trail.clear()
        positions?.start()
        siren.lost = true
        siren.proximity = 0f
        if (sound == SoundFrom.THIS_PHONE) siren.start()
        _state.value = compute(src, SystemClock.elapsedRealtime())
    }

    fun stop() {
        siren.stop()
        _state.value = null
    }

    val isActive: Boolean get() = _state.value != null

    /** Which handset sounds. Kept across searches: it is a preference, not a state. */
    fun setSound(from: SoundFrom) {
        sound = from
        if (!isActive) return
        if (from == SoundFrom.THIS_PHONE) siren.start() else siren.stop()
        tick()
    }

    /**
     * Whether the target should be chirping now: asked for, and a search is on.
     *
     * Not gated on distance. The first version asked only once its own estimate of the
     * distance was inside twenty-five metres, which made the chirp depend on this handset
     * hearing the target well before the target was allowed to make itself heard -- and
     * Bluetooth reception is not symmetric. A request that reaches the target is proof
     * the two are in radio range, which is where a chirp is worth having.
     */
    val wantsTheirSound: Boolean get() = isActive && sound == SoundFrom.THEIR_PHONE

    /** A signal reading from any unit; only the target's matter here. */
    @Synchronized
    fun onSignal(reading: Signal) {
        val t = target ?: return
        if (reading.src != t) return
        reading.txPower?.let { targetTxPower = it }
        val smoothed = signal.offer(reading.rssi, reading.atMillis)
        lastSignalAtMillis = reading.atMillis
        trail.addLast(Trail(reading.atMillis, smoothed))
        while (trail.isNotEmpty() && reading.atMillis - trail.first().atMillis > TREND_WINDOW_MILLIS + 1_000L) {
            trail.removeFirst()
        }
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
        targetChirping = presence.chirping
        targetPresenceAtMillis = nowMillis
        presence.position?.let {
            targetPosition = it
            targetPositionAtMillis = nowMillis - it.ageSeconds * 1000L
            theirs = fold(theirs, it.latitude, it.longitude, it.accuracyMetres.toFloat(), targetPositionAtMillis)
        }
        tick()
    }

    /** Called on every reading and every second at least, to keep the screen and the siren live. */
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
        val rssi = signal.value
        // The fitted model first, the sender's stated power second, the assumption last.
        val reference = pathLoss.referenceDbm ?: referenceFor(targetTxPower)
        val exponent = pathLoss.exponent ?: PATH_LOSS_EXPONENT
        val fitted = pathLoss.exponent != null
        val metres = rssi?.let { distanceFor(it, reference, exponent, fitted) }
        val centimetres = metres?.let { centimetresOf(it) }
        val recent = signal.recent
        val spread =
            if (recent.size >= 3) {
                val nearest = centimetresFor(recent.max().toDouble(), reference, exponent, fitted)
                val furthest = centimetresFor(recent.min().toDouble(), reference, exponent, fitted)
                nearest..furthest
            } else {
                null
            }
        val proximity = if (lost || metres == null) 0f else proximityForMetres(metres)
        val trend = if (lost) null else trendOf(now)
        // "Chirping" is what the target last said of itself, and only while it is still
        // saying it: its presence comes every second while it beacons.
        val chirping = targetChirping && now - targetPresenceAtMillis <= PRESENCE_FRESH_MILLIS

        positions?.latest?.let {
            ours = fold(ours, it.latitude, it.longitude, it.accuracy, it.elapsedRealtimeNanos / 1_000_000)
        }
        val theirPosition = targetPosition
        val here = ours
        val there = theirs

        var gps: Int? = null
        var note: String? = null
        when {
            positions == null || !positions.isPermitted() ->
                note = "Location permission is off: the distance is from signal alone."
            theirPosition == null || there == null ->
                note = if (targetBeaconing) "Waiting for $name's position…" else "Waiting for $name to answer…"
            now - targetPositionAtMillis > POSITION_STALE_MILLIS -> note = "$name's last position is old."
            here == null -> note = "Waiting for this handset's own position…"
            else -> {
                val apart = Geodesy.distanceMetres(here.latitude, here.longitude, there.latitude, there.longitude)
                gps = apart.toInt()
                val error = combinedError(here.accuracy, there.accuracy)
                // Hysteresis: apart at one error, together at well under it.
                gpsApart = if (gpsApart) apart >= error * STOP_APART_FRACTION else apart >= error
                // One lesson per new pair of fixes, while the signal is live. The fit
                // refuses a distance inside its own error itself.
                val newPair = here.fixAtMillis != learnedOursAt || there.fixAtMillis != learnedTheirsAt
                if (rssi != null && !lost && newPair) {
                    learnedOursAt = here.fixAtMillis
                    learnedTheirsAt = there.fixAtMillis
                    pathLoss.learn(apart, error.toDouble(), rssi, now)
                }
                if (!gpsApart) {
                    note = "Within ${error.toInt()} m of each other: closer than GPS can tell apart. Follow the sound."
                }
            }
        }

        return LocateState(
            target = src,
            name = name,
            proximity = proximity,
            rssi = rssi?.toInt(),
            estimatedMetres = if (lost) null else metres?.toInt()?.coerceIn(0, 999),
            estimatedCentimetres = if (lost) null else centimetres,
            spreadCentimetres = if (lost) null else spread,
            gpsMetres = gps,
            gpsApart = gps != null && gpsApart,
            targetAccuracyMetres = there?.accuracy?.toInt() ?: theirPosition?.accuracyMetres,
            ownAccuracyMetres = here?.accuracy?.toInt(),
            trend = trend,
            distanceCalibrated = pathLoss.isFitted,
            lost = lost,
            beaconing = targetBeaconing,
            note = note,
            sound = sound,
            theirSoundAsked = sound == SoundFrom.THEIR_PHONE,
            theirChirping = chirping,
        )
    }

    /**
     * Closing, further, or neither, from the smoothed signal now against a few seconds ago.
     *
     * The threshold is above the signal's own flicker standing still, so "steady" is what
     * an operator standing still sees, and "closing" means they have moved towards it.
     */
    private fun trendOf(now: Long): Trend? {
        val latest = trail.lastOrNull() ?: return null
        val earlier = trail.firstOrNull { now - it.atMillis <= TREND_WINDOW_MILLIS } ?: return null
        if (latest.atMillis - earlier.atMillis < TREND_WINDOW_MILLIS / 2) return null
        val delta = latest.rssi - earlier.rssi
        return when {
            delta >= TREND_DB -> Trend.CLOSING
            delta <= -TREND_DB -> Trend.FURTHER
            else -> Trend.STEADY
        }
    }

    companion object {
        /** Signal expected one metre from a handset advertising at high power, dBm. */
        const val RSSI_AT_ONE_METRE = -59.0

        /** Path-loss exponent at range: 2 is free space, 3 to 4 deep indoors. In between for a field. */
        const val PATH_LOSS_EXPONENT = 2.8

        /**
         * Where the sounds are at their slowest and fastest, in metres. Thirty metres is
         * about where a chirp at full phone volume stops carrying outdoors; half a metre
         * is arm's reach, where the eyes take over.
         */
        const val FAR_METRES = 30.0
        const val NEAR_METRES = 0.5

        /**
         * What a phone loses between its own antenna and another's at one metre, in dB,
         * over and above the transmit power: free-space loss at 2.44 GHz (41 dB) plus the
         * two handset antennas and their mismatch. The contact-tracing measurement studies
         * that calibrated phone-to-phone Bluetooth put the one-metre attenuation at 50 to
         * 60 dB; this is their middle.
         */
        const val ATTENUATION_AT_ONE_METRE_DB = 57.0

        /** Beacons come every second; five missed is a unit that has moved out of range. */
        const val LOST_AFTER_MILLIS = 6_000L

        /** A presence older than this no longer says what the target is doing now. */
        const val PRESENCE_FRESH_MILLIS = 6_000L

        const val POSITION_STALE_MILLIS = 30_000L

        /** How much of a new fix of the same quality as the estimate moves the estimate. */
        const val POSITION_SMOOTHING = 0.45f

        /** Once apart, the fixes count as apart until they are this fraction of their error apart. */
        const val STOP_APART_FRACTION = 0.6

        /** Over this long, a change of this much: closing or further. */
        const val TREND_WINDOW_MILLIS = 3_000L
        const val TREND_DB = 2.5

        /**
         * The two fixes' errors combined, in metres.
         *
         * In quadrature, not added: each accuracy is the radius the fix is inside with
         * two chances in three, the two errors are independent, and the error of the
         * line between them is the root of the sum of their squares.
         */
        fun combinedError(
            ownAccuracy: Float,
            targetAccuracy: Float,
        ): Double {
            val squares = ownAccuracy.toDouble().pow(2) + targetAccuracy.toDouble().pow(2)
            return kotlin.math.sqrt(squares).coerceAtLeast(1.0)
        }

        /** The signal expected at one metre: from the sender's own power when it says, else assumed. */
        fun referenceFor(txPower: Int?): Double = txPower?.let { it - ATTENUATION_AT_ONE_METRE_DB } ?: RSSI_AT_ONE_METRE

        /**
         * Distance in metres from a signal, against the one-metre reference.
         *
         * The path-loss exponent is not one number. Within a few metres and in sight of
         * each other the two antennas are in free space, exponent 2; further off, with
         * walls and floors and bodies in the way, it climbs towards 3. So the exponent
         * rises with the loss itself: 2 at the reference, [PATH_LOSS_EXPONENT] thirty
         * decibels below it, continuously between, which keeps the last metre honest
         * without pretending a corridor is free space.
         */
        fun distanceFor(
            rssi: Double,
            reference: Double = RSSI_AT_ONE_METRE,
            /** The exponent the loss climbs to when assumed, or the exponent throughout when fitted. */
            exponent: Double = PATH_LOSS_EXPONENT,
            /**
             * Whether [exponent] was measured for this pair by [PathLossFit]. The ramp from
             * free space is a prior about the last few metres; a measurement replaces a
             * prior, so a fitted exponent is used flat.
             */
            fitted: Boolean = false,
        ): Double {
            val loss = reference - rssi
            val n =
                if (fitted) {
                    exponent
                } else {
                    2.0 + (exponent.coerceAtLeast(2.0) - 2.0) * (loss / 30.0).coerceIn(0.0, 1.0)
                }
            return 10.0.pow(loss / (10 * n))
        }

        fun metresFor(
            rssi: Double,
            reference: Double = RSSI_AT_ONE_METRE,
            exponent: Double = PATH_LOSS_EXPONENT,
            fitted: Boolean = false,
        ): Int = distanceFor(rssi, reference, exponent, fitted).toInt().coerceIn(0, 999)

        /** The same model at the resolution the last few metres want. Capped at 999 m. */
        fun centimetresFor(
            rssi: Double,
            reference: Double = RSSI_AT_ONE_METRE,
            exponent: Double = PATH_LOSS_EXPONENT,
            fitted: Boolean = false,
        ): Int = centimetresOf(distanceFor(rssi, reference, exponent, fitted))

        private fun centimetresOf(metres: Double): Int = (100.0 * metres).toInt().coerceIn(0, 99_900)

        /**
         * How near, 0 at [FAR_METRES] and beyond, 1 at [NEAR_METRES] and closer, on the
         * logarithm of the distance between: the scale a signal has, and the scale the
         * ear judges a rate on. Halving the distance is the same step anywhere in the
         * range. Both sounds of a search are driven by this.
         */
        fun proximityForMetres(metres: Double): Float {
            val d = metres.coerceIn(NEAR_METRES, FAR_METRES)
            return (1.0 - ln(d / NEAR_METRES) / ln(FAR_METRES / NEAR_METRES)).toFloat().coerceIn(0f, 1f)
        }

        /**
         * Folds one fix into a running position estimate.
         *
         * The weight is [POSITION_SMOOTHING] scaled by how the fix's accuracy compares with
         * the estimate's: a fix twice as good takes nearly all of the estimate, one twice as
         * bad a fraction. A fix that lands further from the estimate than both their errors
         * put together is a move, not noise, and replaces the estimate outright. The same
         * fix is never folded twice.
         */
        private fun fold(
            estimate: Estimate?,
            latitude: Double,
            longitude: Double,
            accuracy: Float,
            fixAtMillis: Long,
        ): Estimate {
            val acc = accuracy.coerceAtLeast(1f)
            if (estimate == null) return Estimate(latitude, longitude, acc, fixAtMillis)
            if (fixAtMillis == estimate.fixAtMillis) return estimate
            val moved =
                Geodesy.distanceMetres(estimate.latitude, estimate.longitude, latitude, longitude) >
                    estimate.accuracy + acc
            val w =
                if (moved) 1f else (POSITION_SMOOTHING * (estimate.accuracy / acc)).coerceIn(0.1f, 1f)
            estimate.latitude += (latitude - estimate.latitude) * w
            estimate.longitude += (longitude - estimate.longitude) * w
            estimate.accuracy += (acc - estimate.accuracy) * w
            estimate.fixAtMillis = fixAtMillis
            return estimate
        }
    }
}

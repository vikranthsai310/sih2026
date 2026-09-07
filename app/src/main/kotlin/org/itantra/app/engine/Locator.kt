package org.itantra.app.engine

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.itantra.app.platform.Heading
import org.itantra.app.platform.LocateSiren
import org.itantra.app.platform.PositionSource
import org.itantra.app.ui.ArrowMode
import org.itantra.app.ui.LocateState
import org.itantra.link.Signal
import org.itantra.proto.Presence
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
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
 *
 * Under three metres the figure is given in centimetres, because that is the resolution
 * the last steps want, and beside it the spread of the recent readings, because a signal
 * figure without its width is a false precision: "60 cm" from a reading that has ranged
 * over 40 to 110 is "40 to 110 cm".
 *
 * ## Making the bearing worth drawing
 *
 * A bearing between two GPS fixes is only as good as the fixes, and a handset's fix
 * wanders several metres from second to second even standing still. Three things are done
 * about that, each of which was a wrong arrow in the field before it was done.
 *
 * **Positions are averaged, weighted by their own stated accuracy.** Each side's fix is
 * folded into a running estimate; a fix better than the estimate moves it more, a worse
 * one less, and a fix that lands further from the estimate than both their errors is
 * treated as real movement and taken whole. The wander is averaged out; a walk is not.
 *
 * **The compass is checked against the walk.** While the operator is walking -- a metre a
 * second or more, with the receiver sure of the direction of travel -- the GPS course is
 * the direction the phone is being carried in, and a phone held out to follow an arrow is
 * carried pointing forward. The difference between that course and the compass is the
 * compass's error at that spot: iron in a vehicle, a steel frame, a magnet in the case.
 * That error is learned while walking and subtracted, then let go of over half a minute
 * standing still, because a magnetic disturbance belongs to a place. A difference beyond
 * sixty degrees is not learned: that is a phone held sideways, not a compass that is out.
 *
 * **The arrow does not flap.** Whether the two fixes are far enough apart to give a
 * direction is decided with hysteresis, so a distance hovering at the edge does not swap
 * the arrow between "the target" and "nothing" every second. Inside that edge the arrow
 * keeps the last direction it had, marked as such, for a minute and a half; the operator
 * was walking that way and the target has not moved far in that time. Only with no
 * direction ever known does it point north -- and it always turns with the compass, so an
 * operator turning on the spot can see that the compass is alive whatever the arrow means.
 *
 * The width of the arrow's own uncertainty, from the combined error over the distance, is
 * given to the screen to draw behind it.
 *
 * ## Indoors: the sweep
 *
 * Indoors there is no fix on either side and the positions say nothing. There is still one
 * thing about direction a phone can measure, and it is the operator's own body. At
 * Bluetooth's wavelength a human torso takes ten to twenty decibels out of a signal that
 * has to pass through it, which is more than the difference between one metre and five.
 * So an operator who holds the phone in front of them and turns a full circle hears the
 * target loudest when facing it. Every signal reading is tagged with the compass heading
 * it arrived at; once the readings cover most of a circle, the direction of strongest
 * signal -- the power-weighted circular mean of the headings -- is the arrow, and how
 * sharply the signal peaked is its width. The readings age out over forty seconds, so the
 * sweep follows the operator as they walk and turn again. It is coarse, a quadrant rather
 * than a degree, and it is the only direction there is indoors; the screen guides the turn
 * and shows how much of the circle has been covered.
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

    /** The target's transmit power, from its advertising header, when it says. */
    private var targetTxPower: Int? = null
    private var targetPosition: Presence.Position? = null
    private var targetPositionAtMillis = 0L
    private var targetBeaconing = false
    private var sirenWanted = true

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

    /** What the walk has taught about the compass: degrees to add, and when it last taught. */
    private var courseOffset = 0f
    private var courseAtMillis = 0L
    private var courseFixAtMillis = 0L

    /** Lessons that have agreed so far, and what they say; applied at [COURSE_LESSONS_NEEDED]. */
    private var courseLessons = 0
    private var courseCandidate = 0f

    /** Whether the fixes are far enough apart for a direction, with hysteresis. */
    private var pointing = false
    private var lastBearing: Float? = null
    private var lastBearingAtMillis = 0L

    private var declinationAtMillis = 0L

    /** A signal reading and which way the phone was pointing when it arrived. */
    private class Sample(val headingDeg: Float, val rssi: Int, val atMillis: Long)

    private val samples = ArrayDeque<Sample>()

    fun start(
        src: Int,
        name: String,
    ) {
        stop()
        this.name = name
        recent.clear()
        targetTxPower = null
        smoothedRssi = null
        lastSignalAtMillis = 0L
        targetPosition = null
        targetBeaconing = false
        ours = null
        theirs = null
        courseOffset = 0f
        courseAtMillis = 0L
        courseFixAtMillis = 0L
        courseLessons = 0
        courseCandidate = 0f
        pointing = false
        lastBearing = null
        lastBearingAtMillis = 0L
        declinationAtMillis = 0L
        samples.clear()
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
        signal.txPower?.let { targetTxPower = it }
        recent.addLast(signal.rssi)
        while (recent.size > MEDIAN_WINDOW) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2].toDouble()
        // Smoothed against time, not against the count of readings, so the response is
        // the same whether they come ten a second or one: a step settles in under a
        // second either way. A large move is followed faster still -- that is a wall, or
        // a body, or a stride, and not noise.
        val previous = smoothedRssi
        smoothedRssi =
            if (previous == null || lastSignalAtMillis == 0L) {
                median
            } else {
                val dt = (signal.atMillis - lastSignalAtMillis).coerceAtLeast(0L) / 1000.0
                var alpha = 1.0 - kotlin.math.exp(-dt / SMOOTHING_SECONDS)
                if (abs(median - previous) > BIG_STEP_DB) alpha = max(alpha, BIG_STEP_ALPHA)
                previous + (median - previous) * alpha
            }
        lastSignalAtMillis = signal.atMillis
        heading?.degrees?.let { compass ->
            val now = SystemClock.elapsedRealtime()
            samples.addLast(Sample(Heading.normalise(compass + courseCorrection(now)), signal.rssi, now))
            while (samples.size > SWEEP_MAX_SAMPLES) samples.removeFirst()
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
        presence.position?.let {
            targetPosition = it
            targetPositionAtMillis = nowMillis - it.ageSeconds * 1000L
            theirs = fold(theirs, it.latitude, it.longitude, it.accuracyMetres.toFloat(), targetPositionAtMillis)
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
        val reference = referenceFor(targetTxPower)
        val estimated = rssi?.let { metresFor(it, reference) }
        val centimetres = rssi?.let { centimetresFor(it, reference) }
        val spread =
            if (recent.size >= 3) {
                centimetresFor(recent.max().toDouble(), reference)..centimetresFor(recent.min().toDouble(), reference)
            } else {
                null
            }
        val proximity = if (lost || rssi == null) 0f else proximityFor(rssi)

        val fix: Location? = positions?.latest
        fix?.let { absorbOwnFix(it, now) }
        val compassRaw = heading?.degrees
        val needsCalibration = heading?.needsCalibration == true
        val disturbed = heading?.magneticallyDisturbed == true
        val correction = courseCorrection(now)
        val compass = compassRaw?.let { Heading.normalise(it + correction) }
        val theirPosition = targetPosition
        val here = ours
        val there = theirs

        var gps: Int? = null
        var spreadDeg: Float? = null
        var relativeBearing: Float? = null
        var note: String? = null
        when {
            positions == null || !positions.isPermitted() ->
                note = "Location permission is off: the arrow can only show north."
            theirPosition == null || there == null ->
                note = if (targetBeaconing) "Waiting for $name's position…" else "Waiting for $name to answer…"
            now - targetPositionAtMillis > POSITION_STALE_MILLIS -> note = "$name's last position is old."
            here == null -> note = "Waiting for this handset's own position…"
            compass == null -> note = "Waiting for the compass…"
            else -> {
                val metres = Geodesy.distanceMetres(here.latitude, here.longitude, there.latitude, there.longitude)
                gps = metres.toInt()
                val bearing = Geodesy.bearingDegrees(here.latitude, here.longitude, there.latitude, there.longitude)
                val error = combinedError(here.accuracy, there.accuracy)
                // Hysteresis: in at one error, out at well under it.
                pointing = if (pointing) metres >= error * STOP_POINTING_FRACTION else metres >= error
                if (pointing) {
                    lastBearing = bearing
                    lastBearingAtMillis = now
                    relativeBearing = Heading.normalise(bearing - compass)
                    spreadDeg = bearingSpread(error, metres, compassErrorDeg(heading))
                }
                note =
                    when {
                        !pointing ->
                            "Within ${error.toInt()} m of each other: closer than GPS can tell apart. Follow the sound."
                        needsCalibration -> "Compass unsure: move the handset in a figure of eight."
                        disturbed -> IRON_NOTE
                        else -> null
                    }
            }
        }
        if (relativeBearing == null && compass != null && needsCalibration) {
            note = (note?.let { "$it " } ?: "") + "Compass unsure: move the handset in a figure of eight."
        } else if (relativeBearing == null && compass != null && disturbed) {
            note = (note?.let { "$it " } ?: "") + IRON_NOTE
        }

        // What the arrow draws, and what it means.
        while (samples.isNotEmpty() && now - samples.first().atMillis > SWEEP_WINDOW_MILLIS) samples.removeFirst()
        val sweep = if (lost) null else sweepOf(samples.map { it.headingDeg }, samples.map { it.rssi })
        val swept = coverageOf(samples.map { it.headingDeg })
        val remembered = lastBearing?.takeIf { now - lastBearingAtMillis <= REMEMBER_BEARING_MILLIS }
        val mode =
            when {
                compass == null -> ArrowMode.NONE
                relativeBearing != null -> ArrowMode.TARGET
                sweep != null -> ArrowMode.SWEEP
                remembered != null -> ArrowMode.LAST_KNOWN
                else -> ArrowMode.NORTH
            }
        // Two independent bearings -- positions and the sweep -- are combined weighted by
        // the inverse square of their spreads, the way two instruments of known error
        // are: the sharper one leads, the other pulls it a little its way.
        if (mode == ArrowMode.TARGET && sweep != null && spreadDeg != null && compass != null) {
            val wGps = 1.0 / (spreadDeg!! * spreadDeg!!)
            val wSweep = 1.0 / (sweep.spreadDeg * sweep.spreadDeg)
            val gpsBearing = lastBearing!!
            val fused =
                Heading.normalise(
                    gpsBearing + arc(sweep.bearingDeg - gpsBearing) * (wSweep / (wGps + wSweep)).toFloat(),
                )
            relativeBearing = Heading.normalise(fused - compass)
            spreadDeg = (1.0 / kotlin.math.sqrt(wGps + wSweep)).toFloat()
        }
        val arrow =
            when (mode) {
                ArrowMode.NONE -> null
                ArrowMode.TARGET -> relativeBearing
                ArrowMode.SWEEP -> Heading.normalise(sweep!!.bearingDeg - compass!!)
                ArrowMode.LAST_KNOWN -> Heading.normalise(remembered!! - compass!!)
                ArrowMode.NORTH -> Heading.normalise(-compass!!)
            }
        if (mode == ArrowMode.SWEEP) spreadDeg = sweep!!.spreadDeg
        if (mode != ArrowMode.TARGET && !lost) {
            val guide =
                if (mode == ArrowMode.SWEEP) {
                    "Signal peaks this way. Walk on, then turn a circle again to check."
                } else {
                    "Hold the phone in front of you and turn a slow full circle: " +
                        "the signal is strongest when you face $name."
                }
            note = (note?.let { "$it " } ?: "") + guide
        }

        return LocateState(
            target = src,
            name = name,
            proximity = proximity,
            rssi = rssi?.toInt(),
            estimatedMetres = if (lost) null else estimated,
            estimatedCentimetres = if (lost) null else centimetres,
            spreadCentimetres = if (lost) null else spread,
            gpsMetres = gps,
            arrowDeg = arrow,
            arrowMode = mode,
            arrowSpreadDeg = spreadDeg,
            targetAccuracyMetres = there?.accuracy?.toInt() ?: theirPosition?.accuracyMetres,
            ownAccuracyMetres = here?.accuracy?.toInt(),
            relativeBearingDeg = relativeBearing,
            headingDeg = compass,
            headingCorrectionDeg = if (correction != 0f) correction else null,
            sweptDeg = swept,
            bearingDeg =
                when (mode) {
                    ArrowMode.TARGET -> lastBearing
                    ArrowMode.SWEEP -> sweep?.bearingDeg
                    else -> remembered
                },
            compassErrorDeg = heading?.errorDegrees,
            compassNeedsCalibration = needsCalibration,
            compassDisturbed = disturbed,
            lost = lost,
            beaconing = targetBeaconing,
            arrowNote = note,
            sirenOn = sirenWanted,
        )
    }

    /** Folds our own latest fix into the estimate, the declination, and the course lesson. */
    private fun absorbOwnFix(
        fix: Location,
        now: Long,
    ) {
        val fixAt = fix.elapsedRealtimeNanos / 1_000_000
        ours = fold(ours, fix.latitude, fix.longitude, fix.accuracy, fixAt)

        if (now - declinationAtMillis > DECLINATION_EVERY_MILLIS) {
            heading?.calibrate(fix)
            declinationAtMillis = now
        }

        // One lesson per fix, and only from a fix that knows it is walking somewhere.
        if (fixAt == courseFixAtMillis) return
        val compassRaw = heading?.degrees ?: return
        if (!fix.hasSpeed() || fix.speed < WALKING_SPEED || !fix.hasBearing()) return
        // A receiver that states its course error is believed only when that error is
        // small; one that does not state it is believed only at a brisk walk, where the
        // course is least noisy.
        val courseError = if (fix.hasBearingAccuracy()) fix.bearingAccuracyDegrees else null
        if (courseError != null && courseError > COURSE_ACCURACY_LIMIT_DEG) return
        if (courseError == null && fix.speed < BRISK_SPEED) return
        courseFixAtMillis = fixAt
        val sample = arc(fix.bearing - compassRaw)
        if (abs(sample) > COURSE_OFFSET_LIMIT_DEG) return
        // Three lessons that agree before any of them is applied: one fix with a wrong
        // course is a common thing, and an arrow that swings on it is worse than one that
        // waits a few seconds.
        if (courseLessons > 0 && abs(arc(sample - courseCandidate)) > COURSE_AGREEMENT_DEG) {
            courseLessons = 0
        }
        courseCandidate =
            if (courseLessons == 0) sample else courseCandidate + arc(sample - courseCandidate) * COURSE_SMOOTHING
        courseLessons++
        if (courseLessons < COURSE_LESSONS_NEEDED) return
        courseOffset = courseCandidate.coerceIn(-COURSE_OFFSET_CAP_DEG, COURSE_OFFSET_CAP_DEG)
        courseAtMillis = now
    }

    /** The compass correction in force now: whole while walking, let go of after standing still. */
    private fun courseCorrection(now: Long): Float {
        if (courseAtMillis == 0L) return 0f
        val since = now - courseAtMillis
        val keep =
            when {
                since <= COURSE_HOLD_MILLIS -> 1f
                since >= COURSE_HOLD_MILLIS + COURSE_FADE_MILLIS -> 0f
                else -> 1f - (since - COURSE_HOLD_MILLIS).toFloat() / COURSE_FADE_MILLIS
            }
        return courseOffset * keep
    }

    companion object {
        /** Signal expected one metre from a handset advertising at high power, dBm. */
        const val RSSI_AT_ONE_METRE = -59.0

        /** Path-loss exponent at range: 2 is free space, 3 to 4 deep indoors. In between for a field. */
        const val PATH_LOSS_EXPONENT = 2.8

        /** Where the siren is at its fastest: this close, the eyes take over. */
        const val NEAR_RSSI = -50.0

        /** Where the siren is at its slowest: fainter than this and it is barely there. */
        const val FAR_RSSI = -95.0

        const val MEDIAN_WINDOW = 5

        /** Time constant of the signal smoothing: a step is two-thirds followed in this long. */
        const val SMOOTHING_SECONDS = 0.6

        /** A jump this large in the median is a real change and is followed at [BIG_STEP_ALPHA] at least. */
        const val BIG_STEP_DB = 8.0
        const val BIG_STEP_ALPHA = 0.5

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

        const val POSITION_STALE_MILLIS = 30_000L

        /** How much of a new fix of the same quality as the estimate moves the estimate. */
        const val POSITION_SMOOTHING = 0.45f

        /** Once pointing, keep pointing until the fixes are this fraction of their error apart. */
        const val STOP_POINTING_FRACTION = 0.6

        /** How long a direction is kept after the fixes fall within their error. */
        const val REMEMBER_BEARING_MILLIS = 90_000L

        /** A fix slower than this is standing still; its course is which way it drifted. */
        const val WALKING_SPEED = 1.0f

        const val COURSE_ACCURACY_LIMIT_DEG = 20f

        /** Without a stated course error, only a walk this fast has a course worth learning from. */
        const val BRISK_SPEED = 1.5f

        /** Lessons within this of each other agree. */
        const val COURSE_AGREEMENT_DEG = 25f
        const val COURSE_LESSONS_NEEDED = 3

        /** No correction beyond this: a compass that far out is one to be recalibrated, not corrected. */
        const val COURSE_OFFSET_CAP_DEG = 45f

        /** What the compass is taken to be out by when the platform does not say. */
        const val COMPASS_ERROR_ASSUMED_DEG = 5f

        const val IRON_NOTE = "Iron nearby: the compass is held on the gyroscope. Move a few metres."

        /** Beyond this the phone is not being carried forward, and the course says nothing about the compass. */
        const val COURSE_OFFSET_LIMIT_DEG = 60f

        const val COURSE_SMOOTHING = 0.3f

        /** The correction is kept whole this long after the last walking fix, then faded out. */
        const val COURSE_HOLD_MILLIS = 15_000L
        const val COURSE_FADE_MILLIS = 30_000L

        const val DECLINATION_EVERY_MILLIS = 60_000L

        /** Readings older than this no longer describe where the operator is standing. */
        const val SWEEP_WINDOW_MILLIS = 40_000L
        const val SWEEP_MAX_SAMPLES = 400

        /** A sweep needs most of a circle: fewer than this many degrees and one side is unsampled. */
        const val SWEEP_MIN_COVERAGE_DEG = 240
        const val SWEEP_MIN_SAMPLES = 8

        const val SWEEP_BINS = 24

        /** The hump must stand this far above the trough for the body to be the cause. */
        const val SWEEP_MIN_HEIGHT_DB = 3.0

        /**
         * The two fixes' errors combined, in metres.
         *
         * In quadrature, not added: each accuracy is the radius the fix is inside with
         * two chances in three, the two errors are independent, and the error of the
         * line between them is the root of the sum of their squares. Adding them made the
         * arrow wait for the units to be twenty metres apart when fourteen would do.
         */
        fun combinedError(
            ownAccuracy: Float,
            targetAccuracy: Float,
        ): Double {
            val squares = ownAccuracy.toDouble().pow(2) + targetAccuracy.toDouble().pow(2)
            return kotlin.math.sqrt(squares).coerceAtLeast(1.0)
        }

        /**
         * How far the arrow may be out, in degrees: the positions' error over the distance,
         * and the compass's own error, in quadrature.
         */
        fun bearingSpread(
            errorMetres: Double,
            distanceMetres: Double,
            compassErrorDeg: Float,
        ): Float {
            val fromPositions = Math.toDegrees(atan2(errorMetres, distanceMetres.coerceAtLeast(0.1)))
            return kotlin.math.sqrt(fromPositions * fromPositions + compassErrorDeg.toDouble().pow(2)).toFloat()
        }

        private fun compassErrorDeg(heading: Heading?): Float =
            heading?.errorDegrees?.takeIf { it > 0f } ?: COMPASS_ERROR_ASSUMED_DEG

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
        ): Double {
            val loss = reference - rssi
            val n = 2.0 + (PATH_LOSS_EXPONENT - 2.0) * (loss / 30.0).coerceIn(0.0, 1.0)
            return 10.0.pow(loss / (10 * n))
        }

        fun metresFor(
            rssi: Double,
            reference: Double = RSSI_AT_ONE_METRE,
        ): Int = distanceFor(rssi, reference).toInt().coerceIn(0, 999)

        /** The same model at the resolution the last few metres want. Capped at 999 m. */
        fun centimetresFor(
            rssi: Double,
            reference: Double = RSSI_AT_ONE_METRE,
        ): Int = (100.0 * distanceFor(rssi, reference)).toInt().coerceIn(0, 99_900)

        /** 0 at [FAR_RSSI], 1 at [NEAR_RSSI], on the signal's own logarithmic scale. */
        fun proximityFor(rssi: Double): Float = ((rssi - FAR_RSSI) / (NEAR_RSSI - FAR_RSSI)).toFloat().coerceIn(0f, 1f)

        /** A direction from a turn on the spot, and how sharply the signal peaked there. */
        class Sweep(val bearingDeg: Float, val spreadDeg: Float)

        /** How many degrees of the circle [headings] touch, in 30° bins. */
        fun coverageOf(headings: List<Float>): Int {
            if (headings.isEmpty()) return 0
            val bins = BooleanArray(12)
            for (h in headings) bins[((Heading.normalise(h) / 30f).toInt()).coerceIn(0, 11)] = true
            return bins.count { it } * 30
        }

        /**
         * The direction of strongest signal round the circle.
         *
         * The body's shadow makes the signal, plotted against heading, one broad hump: a
         * cosine, at its top where the operator faces the target. So a cosine is fitted.
         * The readings are first averaged into fifteen-degree bins, because an operator
         * turns unevenly -- slowly here, quickly there, a pause at the end -- and a fit to
         * the raw readings would be dragged towards wherever they lingered. On the binned
         * means the fit `a + b·cos θ + c·sin θ` is linear least squares, solved in closed
         * form; the peak is at `atan2(c, b)` and its height `√(b² + c²)` is how much the
         * body is doing. Under three decibels of hump the body is not between the phones
         * in any direction that matters and there is no answer, which is the right one.
         * The spread comes from the hump's height and how well the cosine fits: a tall
         * clean hump is a narrow arrow, a low ragged one a wide fan. Null until the
         * circle is mostly covered.
         */
        fun sweepOf(
            headings: List<Float>,
            rssis: List<Int>,
        ): Sweep? {
            if (headings.size < SWEEP_MIN_SAMPLES || coverageOf(headings) < SWEEP_MIN_COVERAGE_DEG) return null
            val sums = DoubleArray(SWEEP_BINS)
            val angles = DoubleArray(SWEEP_BINS)
            val counts = IntArray(SWEEP_BINS)
            for (i in headings.indices) {
                val h = Heading.normalise(headings[i])
                val bin = (h / (360f / SWEEP_BINS)).toInt().coerceIn(0, SWEEP_BINS - 1)
                sums[bin] += rssis[i].toDouble()
                // The bin's angle is where its readings actually were, not its centre: a
                // turn sampled on the bin edges would otherwise read half a bin out.
                angles[bin] += h.toDouble()
                counts[bin]++
            }
            // Normal equations for y = a + b cos θ + c sin θ over the filled bins.
            var n = 0.0
            var sc = 0.0
            var ss = 0.0
            var scc = 0.0
            var sss = 0.0
            var scs = 0.0
            var sy = 0.0
            var syc = 0.0
            var sys = 0.0
            for (bin in 0 until SWEEP_BINS) {
                if (counts[bin] == 0) continue
                val theta = Math.toRadians(angles[bin] / counts[bin])
                val cs = kotlin.math.cos(theta)
                val sn = kotlin.math.sin(theta)
                val y = sums[bin] / counts[bin]
                n += 1.0
                sc += cs
                ss += sn
                scc += cs * cs
                sss += sn * sn
                scs += cs * sn
                sy += y
                syc += y * cs
                sys += y * sn
            }
            val solved = solve3(n, sc, ss, sc, scc, scs, ss, scs, sss, sy, syc, sys) ?: return null
            val (a, b, c) = solved
            val height = kotlin.math.hypot(b, c)
            if (height < SWEEP_MIN_HEIGHT_DB) return null
            // Residual: how much of the ring the cosine does not explain.
            var residual = 0.0
            for (bin in 0 until SWEEP_BINS) {
                if (counts[bin] == 0) continue
                val theta = Math.toRadians(angles[bin] / counts[bin])
                val fit = a + b * kotlin.math.cos(theta) + c * kotlin.math.sin(theta)
                val y = sums[bin] / counts[bin]
                residual += (y - fit) * (y - fit)
            }
            val rms = kotlin.math.sqrt(residual / n)
            val bearing = Heading.normalise(Math.toDegrees(atan2(c, b)).toFloat())
            // Spread: a 15 dB hump with a 1 dB residual is a sharp arrow; a 4 dB hump with
            // 3 dB of residual a broad fan.
            val spread = (90.0 * (rms + 1.0) / height).toFloat().coerceIn(10f, 80f)
            return Sweep(bearing, spread)
        }

        /** Cramer's rule for a 3×3 system; null when singular. */
        private fun solve3(
            a11: Double,
            a12: Double,
            a13: Double,
            a21: Double,
            a22: Double,
            a23: Double,
            a31: Double,
            a32: Double,
            a33: Double,
            b1: Double,
            b2: Double,
            b3: Double,
        ): Triple<Double, Double, Double>? {
            fun det(
                m11: Double,
                m12: Double,
                m13: Double,
                m21: Double,
                m22: Double,
                m23: Double,
                m31: Double,
                m32: Double,
                m33: Double,
            ) = m11 * (m22 * m33 - m23 * m32) - m12 * (m21 * m33 - m23 * m31) + m13 * (m21 * m32 - m22 * m31)
            val d = det(a11, a12, a13, a21, a22, a23, a31, a32, a33)
            if (abs(d) < 1e-9) return null
            val x = det(b1, a12, a13, b2, a22, a23, b3, a32, a33) / d
            val y = det(a11, b1, a13, a21, b2, a23, a31, b3, a33) / d
            val z = det(a11, a12, b1, a21, a22, b2, a31, a32, b3) / d
            return Triple(x, y, z)
        }

        /** Degrees into −180..180. */
        fun arc(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
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

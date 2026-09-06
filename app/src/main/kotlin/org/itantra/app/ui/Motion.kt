package org.itantra.app.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Every loop on the operating screen, and the one setting that stops all of them.
 *
 * ## Motion is always the second carrier, never the first
 *
 * The halos say *transmitting*; so does the inverted dock, the word TRANSMITTING and the
 * held control under the operator's thumb. The equaliser says *the microphone is open*; so
 * does the level meter it is drawn from. The breathing dot says *the link is up*; so does
 * `LINK OK` beside it.
 *
 * That is not a nicety, it is the reason this file can switch all of it off in one place.
 * If any loop here were the only carrier of its fact, [reducedMotion] would be a
 * data-loss switch rather than a comfort setting, and it would not be safe to honour.
 *
 * ## Why the system setting rather than a preference of our own
 *
 * An operator who needs reduced motion has already said so, once, to the platform —
 * usually because motion makes them ill. Asking again inside this application, in a
 * settings screen they would have to find, is asking someone to repeat a medical
 * accommodation to every app they own. `ANIMATOR_DURATION_SCALE` is zero when Android's
 * *Remove animations* is on, and that is the answer.
 *
 * ## Reading it once
 *
 * The scale is read at composition and held. It is a system setting a person changes in
 * another app entirely, and observing it live would mean a `ContentObserver` on a value
 * that changes about once in a device's life. The next launch picks it up.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True when Android's *Remove animations* is on. See the file KDoc for why this is read once. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Shorthand at a call site, matching [palette]. */
val reducedMotion: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalReducedMotion.current

/**
 * The easings, named for what they are for rather than for their curve.
 *
 * Three is enough. A fourth would be a decision nobody could restate later.
 */
object Ease {
    /** A thing arriving or leaving. The platform's own curve, because it is the right one. */
    val Standard: Easing = FastOutSlowInEasing

    /** A loop that must not appear to stutter at its seam — halos, shimmer. */
    val Continuous: Easing = LinearEasing

    /** A thing that swells and settles: a bar in the equaliser, a breathing surface. */
    val Swell: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/**
 * A 0..1 ramp that repeats, or a constant under reduced motion.
 *
 * Every loop in this file is built from this one function, so there is exactly one place
 * where "and it stops when asked" is implemented.
 *
 * @param stillValue what the animation reads as when motion is off. Chosen per caller so
 *   the frozen frame is the *representative* one — a halo freezes invisible, a bar freezes
 *   at its resting height — rather than wherever the clock happened to stop.
 */
@Composable
private fun loop(
    durationMillis: Int,
    label: String,
    easing: Easing = Ease.Continuous,
    offsetMillis: Int = 0,
    reverse: Boolean = false,
    stillValue: Float,
): Float {
    if (reducedMotion) return stillValue
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis, easing = easing),
                repeatMode = if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
                // FastForward, not `tween(delayMillis = ...)`. A delay inside the tween is
                // part of the spec that `infiniteRepeatable` repeats, so it is paid on
                // EVERY iteration: two halves 900 ms apart would run at 1600 and 2500 ms
                // and beat against each other instead of holding formation, and bar n of
                // the equaliser would run at 720 + 90n. FastForward starts the loop already
                // that far in and leaves the period alone, which is what a phase offset is.
                initialStartOffset = StartOffset(offsetMillis, StartOffsetType.FastForward),
            ),
        label = label,
    )
    return value
}

/**
 * One expanding ring on the live transmit dock, as a 0..1 progress.
 *
 * Two of these are drawn half a period apart, which is what makes it read as *emission*
 * rather than as a pulsing outline. Scale it from 0.86 to 1.62 and fade 0.5 to 0, matching
 * the canvas's `@keyframes halo`.
 *
 * Frozen at 1 — fully expanded and fully transparent, so a still handset shows the bare
 * circle rather than a ring stopped mid-flight that reads as a rendering fault.
 */
@Composable
fun haloProgress(offsetMillis: Int = 0): Float =
    loop(
        durationMillis = Tokens.HALO_MILLIS,
        label = "halo",
        offsetMillis = offsetMillis,
        stillValue = 1f,
    )

/**
 * The seven capture bars, each 0..1 of its full height.
 *
 * The stagger is what stops it looking like a single object breathing: bar *n* starts 90 ms
 * after bar *n−1*, so the crest travels. Under reduced motion every bar sits at 0.35 —
 * present, clearly a meter, and still.
 *
 * The **level** the microphone is actually reading is a separate input and is not this
 * function's business: this is the idle shape, and a caller multiplies it by the real level
 * so that a silent room produces a flat meter rather than a lively one.
 */
@Composable
fun equaliserBars(): List<Float> =
    (0 until Tokens.EQ_BARS).map { bar ->
        loop(
            durationMillis = Tokens.EQ_MILLIS,
            label = "eq$bar",
            easing = Ease.Swell,
            offsetMillis = bar * Tokens.EQ_STAGGER_MILLIS,
            reverse = true,
            stillValue = 0.35f,
        ).coerceIn(0.2f, 1f)
    }

/**
 * The link dot's opacity while the link is up: 1 → 0.42 → 1.
 *
 * Frozen fully opaque, because a dot that is up should read as up, and a dot frozen at
 * half opacity reads as a dot that is going out.
 */
@Composable
fun pulseAlpha(): Float {
    val t = loop(Tokens.PULSE_MILLIS, "pulse", Ease.Swell, reverse = true, stillValue = 0f)
    return 1f - (t * 0.58f)
}

/**
 * Sound arcs on the bubble being spoken aloud, as a 0..1 progress.
 *
 * Frozen at 0 — the arcs are simply absent on a still handset. The bubble keeps its 2 dp
 * Aqua signal border and its speaker icon, which are the carriers that matter.
 */
@Composable
fun arcProgress(offsetMillis: Int = 0): Float =
    loop(Tokens.ARC_MILLIS, "arc", offsetMillis = offsetMillis, stillValue = 0f)

/**
 * The splash progress sheen, as a 0..1 sweep across the track.
 *
 * Frozen off-track at 0, so the bar is a plain determinate bar. This is the only purely
 * decorative loop in the application, and it is on the one screen nobody operates.
 */
@Composable
fun shimmerProgress(): Float = loop(Tokens.SHIMMER_MILLIS, "shimmer", stillValue = 0f)

/**
 * A surface asking to be looked at without moving on the screen: 1.0 → 1.035 → 1.0.
 *
 * Used by the incoming alert, where the thing being drawn attention to is already
 * full-bleed and red. Frozen at exactly 1 — no scale at all — because an alert that is
 * 3.5 % larger than it should be, permanently, is a layout bug in every screenshot.
 */
@Composable
fun breatheScale(): Float {
    val t = loop(Tokens.BREATHE_MILLIS, "breathe", Ease.Swell, reverse = true, stillValue = 0f)
    return 1f + (t * 0.035f)
}

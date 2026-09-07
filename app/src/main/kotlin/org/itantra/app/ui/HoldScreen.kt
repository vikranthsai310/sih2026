package org.itantra.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The first screen, every time: one circle, held for three seconds.
 *
 * ## Why a hold and not a tap
 *
 * This handset lives in a chest pocket, in a bag, in a wet glove, and it is opened and
 * put down many times an hour. A tap is what a pocket does by itself. A three-second hold
 * on one spot is a gesture that has to be *meant* — nothing brushing against the screen
 * produces it — so the radio behind this screen is only ever reached on purpose. It is
 * shown on every return to the application, not only at launch, for the same reason: the
 * pocket does not know the difference.
 *
 * Nothing else on this screen does anything. The hardware transmit key is not live until
 * it is passed either, which is what makes it a gate rather than a decoration.
 *
 * ## What the animation says
 *
 * The ring is the hold, drawn as it happens: it fills clockwise from the top over the
 * three seconds, so an operator sees how far along they are and exactly when it will
 * open. The circle leans into the thumb — it grows a little under pressure — and while
 * it is held two halos leave it, the same emission the transmit control makes, because
 * the same gesture on the next screen will be transmitting. Let go early and the ring
 * runs back to nothing on a spring, which is the screen saying *not yet* without a word.
 * At three seconds the ring bursts outward, the handset ticks once, and the screen
 * dissolves into the radio. Under reduced motion the ring still fills — it is the
 * information — and the halos, the lean and the burst are simply absent.
 *
 * ## The colour
 *
 * Periwinkle throughout, turning mint over the last third of the hold. Periwinkle is
 * the transmit colour, so the circle already means "this is the way in"; mint is the
 * all-clear, so the moment of opening reads as one.
 */
@Composable
fun HoldScreen(
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val reduced = reducedMotion
    val haptics = LocalHapticFeedback.current
    val opened = rememberUpdatedState(onOpened)

    var pressing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }

    // The hold, as an animation towards 1 that is only ever reached by not letting go. On
    // release it runs back on a spring, from wherever it was, so a near miss is visibly a
    // near miss rather than a snap to zero.
    LaunchedEffect(pressing) {
        if (done) return@LaunchedEffect
        if (pressing) {
            val remaining = ((1f - progress.value) * HOLD_MILLIS).toInt().coerceAtLeast(1)
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
            if (progress.value >= 1f) {
                done = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!reduced) burst.animateTo(1f, tween(BURST_MILLIS, easing = Ease.Standard))
                opened.value()
            }
        } else {
            progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    val t = progress.value
    // The lean: 1.0 at rest, 1.08 fully held. Nothing under reduced motion.
    val lean = if (reduced) 1f else 1f + LEAN * t
    val ringColour = lerp(p.periwinkle.core, p.mint.core, ((t - 0.66f) / 0.34f).coerceIn(0f, 1f))

    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .graphicsLayer { alpha = 1f - burst.value }
            .padding(Tokens.ScreenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "iTantra",
            fontSize = Tokens.Title,
            fontWeight = FontWeight.Bold,
            color = p.ink,
        )
        Spacer(Modifier.height(48.dp))

        Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
            // Emission while held: two rings half a period apart, as on the transmit dock.
            if (pressing && !done && !reduced) {
                listOf(0, Tokens.HALO_MILLIS / 2).forEach { offset ->
                    val h = haloProgress(offset)
                    Box(
                        Modifier
                            .size(Tokens.TransmitCircle)
                            .graphicsLayer {
                                val scale = HALO_FROM + (HALO_TO - HALO_FROM) * h
                                scaleX = scale
                                scaleY = scale
                                alpha = HALO_ALPHA * (1f - h) * t
                            }
                            .border(Tokens.SignalBorder, p.periwinkle.core, CircleShape),
                    )
                }
            }

            // The burst: the ring leaving, outward and fading, at the moment of opening.
            if (burst.value > 0f) {
                Canvas(
                    Modifier
                        .size(RING)
                        .graphicsLayer {
                            val s = 1f + burst.value * 1.4f
                            scaleX = s
                            scaleY = s
                            alpha = 1f - burst.value
                        },
                ) {
                    drawCircle(
                        color = p.mint.core,
                        style = Stroke(width = RING_STROKE.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // The ring itself: a track, and the hold drawn on it from the top, clockwise.
            Canvas(Modifier.size(RING)) {
                val stroke = RING_STROKE.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = p.sunken,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                if (t > 0f) {
                    drawArc(
                        color = ringColour,
                        startAngle = -90f,
                        sweepAngle = 360f * t,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }

            // The circle. It is the only thing on the screen that takes a touch.
            Box(
                Modifier
                    .size(Tokens.TransmitCircle)
                    .graphicsLayer {
                        scaleX = lean
                        scaleY = lean
                    }
                    .clip(CircleShape)
                    .background(if (done) p.mint.core else p.periwinkle.core)
                    .pointerInput(done) {
                        if (done) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                pressing = true
                                tryAwaitRelease()
                                pressing = false
                            },
                        )
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Hold for three seconds to open"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Transmit,
                    contentDescription = null,
                    tint = p.onAccent,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Spacer(Modifier.height(36.dp))
        Text(
            if (pressing && !done) "Keep holding" else "Hold to open",
            fontSize = Tokens.Body,
            fontWeight = FontWeight.SemiBold,
            color = p.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Counting down is more useful than "three seconds": it says how much is left.
            if (pressing && !done) secondsLeft(t) else "Three seconds, on the circle",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = p.muted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun secondsLeft(t: Float): String {
    val left = ((1f - t) * HOLD_MILLIS / 1000f)
    return String.format(java.util.Locale.ROOT, "%.1f s", left.coerceAtLeast(0f))
}

/** Three seconds. Long enough that nothing accidental produces it; short enough to mean it. */
private const val HOLD_MILLIS = 3_000

private const val BURST_MILLIS = 380

/** How much the circle grows under a full hold. */
private const val LEAN = 0.08f

private val RING = 180.dp
private val RING_STROKE = 6.dp

private const val HALO_FROM = 0.86f
private const val HALO_TO = 1.62f
private const val HALO_ALPHA = 0.5f

package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.itantra.audio.EngineState

/**
 * The six degraded states, each with what it means and what to do. Task **W7.22**.
 *
 * ## Why every one of them has a reason string
 *
 * `DEGRADED` is a first-class state in this system, not an error path — the engine
 * expects to be degraded sometimes and to recover on its own. What it must never be is
 * *silent*. An operator holding a unit that has quietly stopped listening is in a far
 * worse position than one holding a unit that says so, because they will go on speaking
 * into it.
 *
 * So each reason carries three things: what happened, what the system is doing about it,
 * and what — if anything — the operator should do. The third is the one usually missing
 * from a status message, and it is the only part that changes what happens next.
 *
 * ## Announced, not just shown
 *
 * The banner is a **live region**, so TalkBack speaks it when it appears rather than only
 * when the operator happens to navigate to it. Asha is not going to find a status line by
 * exploring the screen (task W7.23).
 */
@Composable
fun DegradedBanner(
    reason: EngineState.Degraded.Reason,
    modifier: Modifier = Modifier,
) {
    val advice = adviceFor(reason)
    Row(
        modifier
            .fillMaxWidth()
            .background(colourFor(reason))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // Cleared, not merged. `semantics { contentDescription = ... }` on a container
            // adds to its children rather than replacing them, so the banner would be
            // announced and then the icon and both lines read again as loose fragments.
            // One banner, one sentence — task W7.23.
            .clearAndSetSemantics {
                // Spoken as soon as it appears; an operator will not go looking for it.
                liveRegion = LiveRegionMode.Assertive
                contentDescription = Spoken.degraded(reason)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(advice.icon, fontSize = 22.sp, color = Color.Black)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                // The engine's own words, verbatim. A banner that paraphrases the state
                // machine drifts from it.
                reason.message,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            Text(advice.doThis, fontSize = 13.sp, color = Color.Black)
        }
    }
}

/** What the operator can do, which is the part a status message usually omits. */
data class DegradedAdvice(val icon: String, val doThis: String, val recoversItself: Boolean)

fun adviceFor(reason: EngineState.Degraded.Reason): DegradedAdvice =
    when (reason) {
        EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE ->
            DegradedAdvice(
                icon = "🎤",
                doThis = "End the call to start listening again. Receiving still works.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.LINK_DOWN ->
            DegradedAdvice(
                icon = "⇄",
                // Naming the retry matters: an operator who thinks nothing is happening
                // starts restarting things, which makes reconnection slower.
                doThis = "Reconnecting automatically. Move closer to the other unit.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.THERMAL ->
            DegradedAdvice(
                icon = "🌡",
                doThis = "The handset is hot and running slower. Get it out of the sun.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.STORAGE_FULL ->
            DegradedAdvice(
                icon = "▤",
                // The only one that cannot fix itself, so it is the only one that asks
                // for a decision.
                doThis = "Delete a language pack or old messages in Settings.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.TEMPLATE_MISMATCH ->
            DegradedAdvice(
                icon = "⚠",
                doThis = "Speak your message instead. Template alerts are disabled.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.MODELS_MISSING ->
            DegradedAdvice(
                icon = "⤓",
                doThis = "Re-download the language pack in Settings.",
                recoversItself = false,
            )
    }

/**
 * Amber for conditions that clear themselves, red for those needing a person.
 *
 * The colour carries the same information as the advice, so an operator who cannot read
 * still learns whether this is something to act on — inclusive design rule 3.
 */
private fun colourFor(reason: EngineState.Degraded.Reason): Color =
    if (adviceFor(reason).recoversItself) Amber else Danger

private val Amber = Color(0xFFF2B705)
private val Danger = Color(0xFFEF6C60)

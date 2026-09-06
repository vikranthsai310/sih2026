package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.audio.EngineState

/**
 * Every degraded state, each with what it means and what to do. Task **W7.22**.
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
    val p = palette
    val advice = adviceFor(reason)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(p.paper, RoundedCornerShape(Tokens.RadiusControl))
            .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusControl))
            .padding(14.dp)
            // Cleared, not merged. `semantics { contentDescription = ... }` on a container
            // adds to its children rather than replacing them, so the banner would be
            // announced and then both lines read again as loose fragments. One banner, one
            // sentence — task W7.23.
            .clearAndSetSemantics {
                // Spoken as soon as it appears; an operator will not go looking for it.
                liveRegion = LiveRegionMode.Assertive
                contentDescription = Spoken.degraded(reason)
            },
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            advice.icon,
            contentDescription = null,
            tint = severityOf(reason).core(p),
            modifier = Modifier.padding(top = 1.dp).size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                // The engine's own words, verbatim. A banner that paraphrases the state
                // machine drifts from it.
                reason.message,
                fontSize = Tokens.BodySmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = Tokens.BodySmall * 1.25f,
                color = p.ink,
            )
            Text(
                advice.doThis,
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.4f,
                color = p.muted,
            )
        }
    }
}

/**
 * How bad it is, which is the only thing the colour says.
 *
 * Boards 11·a and 11·b group the eleven reasons under three headings, and the grouping is
 * the design: *recovers itself* and *needs a person* are different facts about the same
 * silence, and an operator who reads one as the other either waits for nothing or walks
 * toward a peer they did not need to reach.
 */
internal enum class Severity {
    /** Clears on its own. Transmit still works. */
    RECOVERS,

    /** Someone has to do something. */
    NEEDS_A_PERSON,

    /** Passing, and transmit is disabled while it passes. */
    TRANSIENT,

    ;

    fun core(p: ItantraPalette) =
        when (this) {
            RECOVERS -> p.apricot.core
            NEEDS_A_PERSON -> p.blush.core
            TRANSIENT -> p.butter.core
        }
}

internal fun severityOf(reason: EngineState.Degraded.Reason): Severity =
    when {
        reason == EngineState.Degraded.Reason.MODELS_MISSING -> Severity.TRANSIENT
        adviceFor(reason).recoversItself -> Severity.RECOVERS
        else -> Severity.NEEDS_A_PERSON
    }

/** What the operator can do, which is the part a status message usually omits. */
data class DegradedAdvice(val icon: ImageVector, val doThis: String, val recoversItself: Boolean)

fun adviceFor(reason: EngineState.Degraded.Reason): DegradedAdvice =
    when (reason) {
        EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE ->
            DegradedAdvice(
                icon = Icons.Mic,
                doThis = "End the call to start listening again. Receiving still works.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.BLUETOOTH_OFF ->
            DegradedAdvice(
                icon = Icons.Bluetooth,
                // The one instruction that is a single tap away, named exactly as the
                // platform names it. "Enable the radio" sends people looking for a setting
                // that is not called that.
                doThis = "Turn Bluetooth on in Settings, then come back.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.NO_PEERS ->
            DegradedAdvice(
                icon = Icons.Transmit,
                // Bonding is a prerequisite this application deliberately does not do for
                // the operator — W6.11 — so the banner has to say so, or the screen reads
                // as broken when it is merely alone.
                doThis = "Pair the other handset in Bluetooth settings and open iTantra on it.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.PERMISSION_DENIED ->
            DegradedAdvice(
                icon = Icons.Mic,
                // Named where Android actually puts it. Once refused, the request
                // dialog does not reappear, so pointing at Settings is the only
                // instruction that works.
                doThis = "Allow Nearby devices in Settings, Apps, iTantra, Permissions.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.LINK_DOWN ->
            DegradedAdvice(
                icon = Icons.Bluetooth,
                // Naming the retry matters: an operator who thinks nothing is happening
                // starts restarting things, which makes reconnection slower.
                doThis = "Reconnecting automatically. Move closer to the other unit.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.THERMAL ->
            DegradedAdvice(
                icon = Icons.Thermal,
                doThis = "The handset is hot and running slower. Get it out of the sun.",
                recoversItself = true,
            )

        EngineState.Degraded.Reason.STORAGE_FULL ->
            DegradedAdvice(
                icon = Icons.Storage,
                // The only one that cannot fix itself, so it is the only one that asks
                // for a decision.
                doThis = "Delete a language pack or old messages in Settings.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.TEMPLATE_MISMATCH ->
            DegradedAdvice(
                icon = Icons.Alert,
                doThis = "Speak your message instead. Template alerts are disabled.",
                recoversItself = false,
            )

        EngineState.Degraded.Reason.MODELS_MISSING ->
            DegradedAdvice(
                icon = Icons.Hourglass,
                doThis = "Re-download the language pack in Settings.",
                recoversItself = false,
            )
    }

package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The alert screens. Tasks W5.17-W5.19, docs/WIREFRAMES.md sections 9-11.
//
// Three rules from docs/UX.md govern all of them, and each exists because the operator
// may not be able to read:
//
//   1. Icon plus word, never a word alone. Asha cannot read; the icon is the primary
//      carrier and the word is the confirmation for those who can.
//   2. Targets are 96 dp. Gloved hands, in the dark, on a moving vehicle.
//   3. The safe option is never smaller than the dangerous one.

/** The six template alerts. One byte of payload; 21 bytes on the wire, authenticated. */
enum class AlertTemplate(val code: Int, val icon: String, val label: String) {
    MEDICAL(1, "✚", "MEDICAL"),
    FIRE(2, "▲", "FIRE"),
    FLOOD(3, "≈", "FLOOD"),
    EVACUATE(4, "⌂", "EVACUATE"),
    EXTRACT(5, "⌖", "EXTRACT"),
    ALL_CLEAR(6, "✓", "ALL CLEAR"),
}

private val AlertRed = Color(0xFFB3261E)
private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)

/**
 * Task **W5.17**. Six template buttons and a hold-to-speak control.
 *
 * The templates are the fastest path *and* the smallest frame *and* the cross-language
 * path — a template sent here is announced in whatever language each receiver has
 * selected, because the byte identifies the sentence rather than carrying it.
 */
@Composable
fun AlertComposeScreen(
    onTemplate: (AlertTemplate) -> Unit,
    onHoldToSpeak: (Boolean) -> Unit,
    attachPosition: Boolean,
    onAttachPositionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("SEND ALERT", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Alerts are announced at full volume on every unit, even locked and silenced.",
            fontSize = 14.sp,
            color = Muted,
        )
        Spacer(Modifier.height(16.dp))

        // Two columns of three, so every target stays 96 dp on a narrow handset.
        AlertTemplate.entries.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { template ->
                    TemplateButton(
                        template = template,
                        onClick = { onTemplate(template) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the last row aligned when the count is odd.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(8.dp))
        HoldToSpeakButton(onHoldToSpeak)

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = attachPosition, onCheckedChange = onAttachPositionChange)
            Text("Attach my position", fontSize = 16.sp, color = Ink)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Template alert = 21 B · reaches every unit in its own language",
            fontSize = 12.sp,
            color = Muted,
        )
    }
}

@Composable
private fun TemplateButton(
    template: AlertTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = TARGET_DP.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = Paper),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Icon first and larger: it is the primary carrier for an operator who
            // cannot read the word beneath it.
            Text(template.icon, fontSize = 30.sp)
            Text(template.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HoldToSpeakButton(onHold: (Boolean) -> Unit) {
    Button(
        onClick = {},
        modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
    ) {
        Text("●  HOLD TO SPEAK ALERT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Task **W5.18**. The one place the system deliberately adds latency, because an alert is
 * the only message that can cause physical harm if it is wrong.
 *
 * Two rules, and both are load-bearing:
 *
 * - The text is **spoken aloud on open**, so the confirmation works for an operator who
 *   cannot read it (rule 2). [onSpeak] is invoked once when the screen appears.
 * - RETAKE and SEND are **exactly equal in size**. A confirmation that makes the safe
 *   option smaller is not a confirmation — it is a nudge towards sending.
 */
@Composable
fun AlertConfirmScreen(
    text: String,
    onSpeak: (String) -> Unit,
    onRetake: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Spoken on open, not on a button press: the operator who most needs this is the one
    // who would not know to ask for it.
    LaunchedEffect(text) { onSpeak(text) }

    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Text("CONFIRM ALERT", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(2.dp, Ink, RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 28.sp, color = Ink, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // weight(1f) on both, identical height: equal by construction rather than by
            // two numbers that could drift apart.
            ConfirmButton("✕", "RETAKE", Ink, Modifier.weight(1f), onRetake)
            ConfirmButton("✓", "SEND", AlertRed, Modifier.weight(1f), onSend)
        }
    }
}

@Composable
private fun ConfirmButton(
    icon: String,
    label: String,
    colour: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = TARGET_DP.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Paper),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 26.sp)
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Task **W5.19**. The full-screen intent, shown over a locked handset.
 *
 * **There is no swipe-to-dismiss, and that is deliberate**: a swipe is something a pocket
 * can do. Dismissal requires the ACKNOWLEDGE target, which also drives the `3 of 6 units`
 * count on the sender's screen — so an alert nobody acknowledged is visibly different
 * from one everybody did.
 *
 * The text is rendered in the **receiver's** language, not the sender's, when the alert
 * arrived as a template code.
 */
@Composable
fun IncomingAlertScreen(
    from: String,
    text: String,
    position: String? = null,
    repeatOf: Pair<Int, Int>? = null,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            // Full bleed and inverted: unmistakable from across a room, and nothing else
            // in the application looks like this.
            .background(AlertRed)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("⚠", fontSize = 84.sp, color = Paper)
        Spacer(Modifier.height(8.dp))
        Text("A L E R T", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Paper)
        Spacer(Modifier.height(8.dp))
        Text("FROM  ${from.uppercase()}", fontSize = 16.sp, color = Paper)

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(2.dp, Paper, RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, fontSize = 32.sp, color = Paper, textAlign = TextAlign.Center)
                if (position != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(position, fontSize = 18.sp, color = Paper)
                }
            }
        }

        if (repeatOf != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "▶ repeating · ${repeatOf.first} of ${repeatOf.second}",
                fontSize = 14.sp,
                color = Paper,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth().size(height = TARGET_DP.dp, width = 0.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = AlertRed),
        ) {
            Text("ACKNOWLEDGE", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Task **W5.20**. Fires the whole alert path against this handset only.
 *
 * Vendor audio policy varies more than the documentation admits, so a unit that has never
 * announced an alert on *this* model of phone has not been tested. It also lets
 * demonstration step 5 be rehearsed by one person.
 */
@Composable
fun TestAlertButton(
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Button(
            onClick = onTest,
            modifier = Modifier.fillMaxWidth().heightIn(min = TARGET_DP.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
        ) {
            Text("TEST ALERT ON THIS DEVICE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sounds on this handset only. Nothing is transmitted.",
            fontSize = 12.sp,
            color = Muted,
        )
    }
}

/** Minimum touch target: gloved hands, in the dark, `docs/UX.md` rule 3. */
private const val TARGET_DP = 96

package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Board 25 — the empty and error set. `docs/REDESIGN.md` task 5.4.
 *
 * ## Absent is not zero
 *
 * The law this file exists to enforce, and the one the instrument strip already obeyed: a
 * measurement that was never taken prints `—` and never `0`. An empty region is the same
 * mistake told visually. A blank half-screen where a list should be says *the screen is
 * broken*; `0 messages` says *nothing has happened*; and only one of those is usually true.
 *
 * ## Why these are cards and not centred illustrations
 *
 * Each is a row at the top of the region it explains, in the same shape as the rows that
 * will replace it once there is content. That keeps the layout from jumping when the first
 * message arrives, and it means the empty state is read in the same place the operator was
 * already looking rather than in the middle of a void.
 *
 * ## The five
 *
 * | Where | Says |
 * | --- | --- |
 * | Operating thread | No traffic yet · hold the circle to speak |
 * | Message log | Nothing in the last 24 hours · kept a day, then deleted |
 * | Storage | No language packs · transmit sends a template, nothing is spoken |
 * | Metrics | Nothing measured yet · send one utterance and the strip fills |
 * | Licence text | Not bundled with this build |
 *
 * Each second line says **what still works**. "No language packs" that stopped at the noun
 * would leave an operator believing the handset is dead; it is not — it can still send a
 * template and still receive, and that is the sentence that keeps them using it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    /** What still works, or what to do. Never only a restatement of the title. */
    body: String,
    modifier: Modifier = Modifier,
    /** The family whose tint fills the glyph chip. Neutral when the absence is not a state. */
    family: ItantraPalette.Family? = null,
    /** An action, when there is one that resolves the emptiness. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = palette
    val chip = family ?: p.periwinkle
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper, RoundedCornerShape(Tokens.RadiusTile))
                .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusTile))
                .padding(14.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$title. $body" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(chip.tint, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = chip.core, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    fontSize = Tokens.Callout,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = Tokens.Callout * 1.25f,
                    color = p.ink,
                )
                Text(
                    body,
                    fontSize = Tokens.Label,
                    lineHeight = Tokens.Label * 1.4f,
                    color = p.muted,
                )
            }
        }

        if (actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .background(p.orchid.tint, RoundedCornerShape(Tokens.RadiusControl))
                    .clickable(onClick = onAction)
                    .semantics(mergeDescendants = true) { contentDescription = actionLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    fontSize = Tokens.BodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = p.orchid.deep,
                )
            }
        }
    }
}

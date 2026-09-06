package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

// One message, drawn once. `docs/REDESIGN.md` task 5.1 -- one bubble, two screens.
//
// The operating screen's thread and the message log were previously two layouts for the
// same data: a row with a name column on one, a card with an evidence line on the other, so
// the same sentence looked like two different kinds of object depending on where it was
// read. They are the same component now. The log turns the evidence up -- it shows a clock
// time rather than an age, and it groups by day -- and that is the whole difference.

/**
 * One message.
 *
 * ## Colour is by kind, never by sender
 *
 * Blush is an alert, Fuchsia a template that crossed languages, Periwinkle this unit, Aqua
 * someone else, Butter something held rather than sent. Each has a second carrier — the side
 * it sits on, the tag, the delivery mark, the dashed outline — so the thread still separates
 * with the hue removed.
 *
 * ## The corner that points
 *
 * Three corners are 18 dp and the one nearest the speaker is 5 dp. That is what makes a
 * column of bubbles read as a direction rather than as a stack of cards, and it is why the
 * sender column the old rows carried is no longer needed to tell mine from theirs.
 *
 * ## Gap G2
 *
 * Board 15 heads each bubble `Ravi · node 02`. [LoggedMessage] carries `from` and nothing
 * else — one identifier, already whichever of a name or a node id the engine had. The
 * heading prints it as it comes rather than splitting a string that may not have two halves,
 * or inventing a node number to sit beside a name.
 */
@Composable
internal fun MessageBubble(
    message: LoggedMessage,
    /** True while this handset is saying this message out loud. Board 09's signal border. */
    speaking: Boolean = false,
    /** The log shows a clock time; the operating thread shows an age. */
    stamp: String = message.age,
    onReplay: (String) -> Unit,
) {
    // An alert is not a bubble. It is the one kind of traffic that has to be findable by
    // scrolling past it quickly, so it gets a header strip and a heavier setting.
    if (message.isAlert) {
        AlertMessageCard(message, stamp)
        return
    }

    val p = palette
    val mine = message.delivery != LoggedMessage.Delivery.RECEIVED
    val queued = message.delivery == LoggedMessage.Delivery.PENDING
    val failed = message.delivery == LoggedMessage.Delivery.FAILED
    val family =
        when {
            queued || failed -> p.butter
            message.wasTemplate && !mine -> p.fuchsia
            mine -> p.periwinkle
            else -> p.aqua
        }
    val shape =
        if (mine) {
            RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
        } else {
            RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
        }

    Row(
        Modifier
            .fillMaxWidth()
            // Held and failed messages are drawn back, because they are history rather than
            // conversation — but never hidden, which is the point of showing them at all.
            .then(if (queued || failed) Modifier.alpha(0.72f) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = Spoken.messageRow(message) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (mine) Spacer(Modifier.weight(1f)) else Avatar(message.from, family)

        Column(
            Modifier.widthIn(max = if (mine) 250.dp else 222.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (mine) "You" else message.from,
                    fontSize = Tokens.Instrument,
                    fontWeight = FontWeight.SemiBold,
                    color = if (mine) p.periwinkle.deep else p.orchid.deep,
                )
                if (message.wasTemplate && !mine) {
                    Text(
                        "TEMPLATE",
                        fontSize = Tokens.Instrument,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = p.fuchsia.deep,
                        modifier =
                            Modifier
                                .background(p.fuchsia.mid, RoundedCornerShape(Tokens.RadiusInset))
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                }
            }

            Column(
                Modifier
                    .background(family.tint, shape)
                    .then(
                        when {
                            speaking -> Modifier.border(Tokens.SignalBorder, family.core, shape)
                            queued || failed -> Modifier.dashedEdge(family.mid, 18.dp)
                            else -> Modifier
                        },
                    )
                    .padding(start = 13.dp, end = 13.dp, top = 11.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Text(
                    message.text,
                    fontSize = Tokens.Body,
                    color = p.ink,
                    lineHeight = Tokens.Body * Tokens.INDIC_LINE_HEIGHT,
                )
                EvidenceLine(message, family, mine, speaking, stamp)
            }
        }

        // Replay is a 64 dp target outside the bubble, because the bubble is not one. Only
        // on messages that arrived: this handset never spoke the ones it sent.
        if (!mine && !queued) {
            ReplayDisc(family) { onReplay(message.text) }
        }
    }
}

/**
 * The evidence line, inside the bubble where a reader already is.
 *
 * `21 B · sent in தமிழ் · heard in हिन्दी` is the most interesting sentence this application
 * can print — one byte of payload, rendered from a shared table, spoken in a language the
 * sender never chose — so it is typeset rather than tucked into a caption.
 */
@Composable
private fun EvidenceLine(
    message: LoggedMessage,
    family: ItantraPalette.Family,
    mine: Boolean,
    speaking: Boolean,
    stamp: String,
) {
    val p = palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${message.frameBytes} B",
            fontSize = Tokens.Instrument,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = family.deep,
        )
        if (!mine) {
            message.confidence?.let { ConfidenceRun(it, family.core) }
            Text(
                "· ${message.language} · $stamp",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                lineHeight = Tokens.Instrument * Tokens.INDIC_LINE_HEIGHT,
                color = family.deep,
            )
        } else {
            Text(
                "· $stamp",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = family.deep,
            )
            DeliveryMark(message.delivery)
        }
        if (speaking) {
            Icon(Icons.Speaking, contentDescription = null, tint = family.core, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Delivery, as a glyph with its own colour and its own word in the spoken description.
 *
 * `FAILED` is the one that matters: a message the operator believes went out and did not is
 * the worst state this component can fail to show, so it is the only one drawn in Blush and
 * the only one that keeps its ring rather than a tick.
 */
@Composable
private fun DeliveryMark(delivery: LoggedMessage.Delivery) {
    val p = palette
    when (delivery) {
        LoggedMessage.Delivery.DELIVERED ->
            Icon(Icons.TickDouble, contentDescription = null, tint = p.mint.core, modifier = Modifier.size(16.dp))
        LoggedMessage.Delivery.SENT ->
            Icon(Icons.Tick, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
        LoggedMessage.Delivery.FAILED ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Cross, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(15.dp))
                Text(
                    "not delivered",
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = p.blush.deep,
                )
            }
        LoggedMessage.Delivery.PENDING ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(9.dp).border(2.dp, p.butter.core, CircleShape))
                Text(
                    "queued",
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = p.butter.deep,
                )
            }
        LoggedMessage.Delivery.RECEIVED -> Unit
    }
}

/**
 * Board 15's alert card.
 *
 * An alert in a scrolling log has to be findable without reading, so it stops being a bubble:
 * a tinted header strip carries the word ALERT, the sender and the time, and the message
 * itself is set two steps heavier than ordinary speech. It occupies the full width because
 * an alert is not addressed to one side of a conversation.
 */
@Composable
private fun AlertMessageCard(
    message: LoggedMessage,
    stamp: String,
) {
    val p = palette
    val shape = RoundedCornerShape(Tokens.RadiusTile)
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper, shape)
            .border(Tokens.Hairline, p.blush.mid, shape)
            .semantics(mergeDescendants = true) { contentDescription = Spoken.messageRow(message) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.blush.tint)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Alert, contentDescription = null, tint = p.blush.core, modifier = Modifier.size(15.dp))
            Text("ALERT", fontSize = Tokens.Instrument, fontWeight = FontWeight.Bold, color = p.blush.deep)
            Box(Modifier.size(width = 1.dp, height = 11.dp).background(p.blush.mid))
            Text(
                message.from,
                fontSize = Tokens.Instrument,
                fontWeight = FontWeight.SemiBold,
                color = p.blush.deep,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stamp,
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.blush.deep,
            )
        }
        Column(
            Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                message.text,
                fontSize = Tokens.Subtitle,
                fontWeight = FontWeight.Bold,
                lineHeight = Tokens.Subtitle * Tokens.INDIC_LINE_HEIGHT,
                color = p.ink,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "${message.frameBytes} B" + if (message.wasTemplate) " template" else "",
                    fontSize = Tokens.Instrument,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = p.fuchsia.deep,
                )
                DeliveryMark(message.delivery)
                message.sentInLanguage?.let {
                    Text(
                        "· sent in $it",
                        fontSize = Tokens.Instrument,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        lineHeight = Tokens.Instrument * Tokens.INDIC_LINE_HEIGHT,
                        color = p.fuchsia.deep,
                    )
                }
            }
        }
    }
}

/** Four 6 dp discs: how sure the recogniser was, read without reading. */
@Composable
private fun ConfidenceRun(
    level: Int,
    colour: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.decorative()) {
        repeat(4) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .then(
                        if (index < level) {
                            Modifier.background(colour, CircleShape)
                        } else {
                            Modifier.border(1.2.dp, colour, CircleShape)
                        },
                    ),
            )
        }
    }
}

/** The sender's initial, so a glance separates two speakers without reading either name. */
@Composable
private fun Avatar(
    from: String,
    family: ItantraPalette.Family,
) {
    Box(
        Modifier
            .size(32.dp)
            .background(family.tint, CircleShape)
            .border(Tokens.Hairline, family.mid, CircleShape)
            .decorative(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            from.trim().take(1).uppercase(Locale.ROOT),
            fontSize = Tokens.Label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = family.deep,
        )
    }
}

/** A 64 dp disc. `sizeIn`, never `size`, because a fixed one clipped the glyph at 200 %. */
@Composable
private fun ReplayDisc(
    family: ItantraPalette.Family,
    onClick: () -> Unit,
) {
    val p = palette
    Box(
        Modifier
            .sizeIn(minWidth = Tokens.TouchTarget, minHeight = Tokens.TouchTarget)
            .background(p.paper, CircleShape)
            .border(Tokens.Hairline, family.mid, CircleShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Say it again" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Replay, contentDescription = null, tint = family.core, modifier = Modifier.size(24.dp))
    }
}

/** `TODAY · 02:14`, centred, so a scroll has somewhere to land. */
@Composable
internal fun DayDivider(label: String) {
    val p = palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            fontSize = Tokens.Instrument,
            fontWeight = FontWeight.Medium,
            color = p.muted,
            modifier =
                Modifier
                    .background(p.paper, RoundedCornerShape(Tokens.RadiusPill))
                    .border(Tokens.Hairline, p.hairline, RoundedCornerShape(Tokens.RadiusPill))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * A dashed edge, for a message held rather than sent.
 *
 * `Modifier.border` draws solid only, and the distinction matters: a queued frame and a
 * delivered one must not be separated by fill alone, because Butter against Periwinkle is a
 * hue difference and hue is what Field Mode and a colour-blind operator both remove.
 */
internal fun Modifier.dashedEdge(
    colour: Color,
    radius: androidx.compose.ui.unit.Dp,
): Modifier =
    drawBehind {
        val stroke = Tokens.SignalBorder.toPx()
        val r = radius.toPx()
        drawRoundRect(
            color = colour,
            cornerRadius = CornerRadius(r, r),
            style =
                Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 3, stroke * 2), 0f),
                ),
        )
    }

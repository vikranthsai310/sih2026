package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Task **W5.16**, board 15. Everything sent and received in the last twenty-four hours.
 *
 * ## The same bubble as the operating screen
 *
 * This screen and the thread on board 06 draw the identical component — `docs/REDESIGN.md`
 * task 5.1. They used to be two layouts for one kind of thing: a row with a name column
 * here, a card with an evidence line there, so a sentence changed shape depending on which
 * screen an operator read it on. The only difference now is that the log turns the evidence
 * up — a clock time instead of an age, and day dividers so a scroll has somewhere to land.
 *
 * ## What "replay" actually does
 *
 * Captured audio is **never stored**. The replay control re-synthesises from the text, which
 * is why a message can be replayed in a language the sender never spoke and why a twenty-four
 * hour log costs kilobytes rather than megabytes. The footer says so, because a control
 * labelled "replay" otherwise implies a recording exists somewhere on the handset.
 */
@Composable
fun MessageLogScreen(
    messages: List<LoggedMessage>,
    onReplay: (LoggedMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    Column(modifier.fillMaxSize().background(p.ground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Messages", fontSize = Tokens.Title, fontWeight = FontWeight.Bold, color = p.ink)
            Box(Modifier.weight(1f))
            Text(
                "${messages.size} · 24 h",
                fontSize = Tokens.Instrument,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = p.muted,
            )
        }
        Box(Modifier.fillMaxWidth().height(Tokens.Hairline).background(p.hairline))

        if (messages.isEmpty()) {
            EmptyState(
                icon = Icons.Replay,
                title = "Nothing in the last 24 hours",
                body =
                    "Sent and received messages appear here. They are kept for a day and " +
                        "then deleted.",
                modifier = Modifier.weight(1f),
            )
        } else {
            val list = rememberLazyListState()
            val reduced = reducedMotion
            // Oldest at the top, newest at the bottom, opened at the bottom: the last
            // thing said is the thing the operator opened this to check.
            LaunchedEffect(messages.size) {
                val last = messages.lastIndex
                if (last < 0) return@LaunchedEffect
                if (reduced) list.scrollToItem(last) else list.animateScrollToItem(last)
            }
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // One divider per run of messages sharing a day, from the age string the
                // engine already produces. Nothing is parsed back out of it — a message
                // whose age names a day starts a new group, and that is the whole rule.
                var lastDay: String? = null
                items(messages) { message ->
                    val day = dayOf(message)
                    if (day != null && day != lastDay) {
                        lastDay = day
                        DayDivider(day.uppercase())
                    }
                    MessageBubble(
                        message = message,
                        stamp = message.age,
                        onReplay = { onReplay(message) },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(p.paper)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Bin, contentDescription = null, tint = p.muted, modifier = Modifier.size(18.dp))
            Text(
                "Kept 24 hours, then deleted. Audio is never stored — replay re-speaks the text.",
                fontSize = Tokens.Label,
                lineHeight = Tokens.Label * 1.4f,
                color = p.muted,
            )
        }
    }
}

/**
 * Which day a message belongs to, or null when its age does not say.
 *
 * The engine hands this screen a formatted age — `2 s`, `41 m`, `yesterday 23:41` — and not
 * a timestamp. Rather than parse one back out, a message is taken to start a new day only
 * when its age names one. A build whose ages are all relative simply gets no dividers, which
 * is the correct amount of grouping to invent from data that does not carry it.
 */
private fun dayOf(message: LoggedMessage): String? =
    when {
        message.age.contains("yesterday", ignoreCase = true) -> "yesterday"
        message.age.contains("today", ignoreCase = true) -> "today"
        else -> null
    }

/**
 * One row.
 *
 * @param sentInLanguage set only for a template message, and only when it differs from
 *   [language] — that difference is the cross-language delivery working
 */
data class LoggedMessage(
    val from: String,
    val text: String,
    val age: String,
    val frameBytes: Int,
    val language: String,
    val sentInLanguage: String? = null,
    val wasTemplate: Boolean = false,
    val isAlert: Boolean = false,
    val delivery: Delivery = Delivery.RECEIVED,
    val confidence: Int? = null,
    /**
     * Whether this unit produced the text by recognising speech, rather than by falling
     * back to a template because nothing was heard.
     *
     * Meaningless on a received message, which was produced however the sending unit
     * produced it. See [Delivery].
     */
    val fromSpeech: Boolean = false,
) {
    enum class Delivery { PENDING, SENT, DELIVERED, RECEIVED, FAILED, REFUSED }

    /**
     * Delivery as a symbol, because it is scanned rather than read.
     *
     * `FAILED` is the one that matters: a message the operator believes went out and did
     * not is the worst state this screen can fail to show.
     */
    fun deliveryMark(): String =
        when (delivery) {
            Delivery.PENDING -> "○ queued"
            Delivery.SENT -> "✓ sent"
            Delivery.DELIVERED -> "✓✓ delivered"
            Delivery.RECEIVED -> confidence?.let { dots(it) } ?: "received"
            Delivery.FAILED -> "✕ NOT DELIVERED"
            // Heard and refused: the fault is between the units, not in this one's sending.
            Delivery.REFUSED -> "✕ NOT READ"
        }

    /** Recognition confidence as filled dots — readable without reading. */
    private fun dots(level: Int): String = "●".repeat(level.coerceIn(0, 3)) + "○".repeat((3 - level).coerceIn(0, 3))
}

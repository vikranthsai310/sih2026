package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The message log. Task **W7.18**, `docs/WIREFRAMES.md` section 12.
 *
 * ## Why every row shows its frame size
 *
 * The compression claim is the centre of this project, and a number on a slide is an
 * assertion. A number on every message in the log, next to the text it carried, is
 * evidence — and it is evidence the jury can generate themselves by sending a message.
 *
 * A template row shows the language it was **sent** in as well as the one it was rendered
 * in, because that difference is the cross-language delivery working and is otherwise
 * invisible.
 *
 * ## Replay
 *
 * Every row has a replay control, sized like everything else at 64 dp. A name or a grid
 * reference is easy to mishear once, and asking a person to repeat themselves over a
 * half-duplex channel costs a full exchange.
 */
@Composable
fun MessageLogScreen(
    messages: List<LoggedMessage>,
    onReplay: (LoggedMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MESSAGES", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("last 24 h", fontSize = 14.sp, color = Muted)
        }
        Spacer(Modifier.height(12.dp))

        if (messages.isEmpty()) {
            Text("Nothing received yet.", fontSize = 16.sp, color = Muted)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { message -> MessageRow(message, onReplay) }
        }
    }
}

@Composable
private fun MessageRow(
    message: LoggedMessage,
    onReplay: (LoggedMessage) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (message.isAlert) "⚠ ALERT  ${message.from}" else message.from,
                fontSize = 14.sp,
                fontWeight = if (message.isAlert) FontWeight.Bold else FontWeight.Normal,
                color = if (message.isAlert) Danger else Ink,
            )
            Text(message.age, fontSize = 13.sp, color = Muted)
        }
        Spacer(Modifier.height(4.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Muted, RoundedCornerShape(6.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message.text, fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
            Text(
                "⟲",
                fontSize = 22.sp,
                color = Ink,
                modifier =
                    Modifier
                        .size(REPLAY_TARGET_DP.dp)
                        .clickable { onReplay(message) }
                        // Spoken as an action, not as a symbol nobody can pronounce.
                        .semantics { contentDescription = "Replay message from ${message.from}" },
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            // The evidence line. Frame size first, because that is the claim.
            buildString {
                append("${message.frameBytes} B")
                if (message.wasTemplate) append(" template")
                append(" · ${message.deliveryMark()}")
                append(" · ${message.language}")
                message.sentInLanguage?.let { append(" · sent in $it") }
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Muted,
        )
    }
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
) {
    enum class Delivery { PENDING, SENT, DELIVERED, RECEIVED, FAILED }

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
        }

    /** Recognition confidence as filled dots — readable without reading. */
    private fun dots(level: Int): String = "●".repeat(level.coerceIn(0, 3)) + "○".repeat((3 - level).coerceIn(0, 3))
}

private const val REPLAY_TARGET_DP = 64
private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF5F5F5F)
private val Danger = Color(0xFFB3261E)

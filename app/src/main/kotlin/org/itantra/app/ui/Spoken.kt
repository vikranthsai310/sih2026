package org.itantra.app.ui

import org.itantra.audio.EngineState

/**
 * What a screen reader says where the screen shows a symbol. Task **W7.23**.
 *
 * ## The defect a TalkBack pass finds first
 *
 * This interface leans on symbols deliberately: `✓✓` for delivered, `●●○` for confidence,
 * `⚠` for an alert. That is the right choice for the operator the design is for — an
 * indicator that can be read at arm's length in bright sun, by someone who may not read the
 * language at all, is worth more than a word.
 *
 * It is precisely the wrong choice for TalkBack. A screen reader hands those glyphs to a
 * speech engine, which says "check mark check mark" if it knows the character and nothing
 * at all if it does not — and `●●○` becomes "black circle black circle white circle",
 * which is not merely unhelpful but actively misleading, since it sounds like a
 * description of a picture rather than a confidence score.
 *
 * So each symbol has a spoken form here, and the composable sets it as the content
 * description of the row rather than of the glyph. The row is the unit an operator
 * navigates to; announcing the glyph separately would make them swipe through five
 * fragments to assemble one message.
 *
 * ## Kept apart from the composables on purpose
 *
 * Nothing in this file imports Compose, so the strings can be asserted in an ordinary unit
 * test. A Compose UI test needs a device, and the property worth checking — that no spoken
 * string is a symbol — does not.
 */
object Spoken {
    /**
     * One message log row, as one sentence.
     *
     * Sender, then text, then how it got here. That order because the first two are what
     * the operator wants and the delivery state is the qualifier; a reader who has heard
     * enough can swipe on without waiting for the frame size.
     */
    fun messageRow(message: LoggedMessage): String =
        buildString {
            if (message.isAlert) append("Alert. ")
            append("From ${message.from}. ")
            append("${message.text}. ")
            append(delivery(message.delivery, message.confidence))
            append(", ${message.age}")
            append(", ${frameSize(message.frameBytes)}")
            if (message.wasTemplate) append(", sent as a template")
            message.sentInLanguage?.let { append(", spoken in $it") }
            append(".")
        }

    /** `✓✓`, `○`, `●●○` and `✕` in words. */
    fun delivery(
        delivery: LoggedMessage.Delivery,
        confidence: Int? = null,
    ): String =
        when (delivery) {
            LoggedMessage.Delivery.PENDING -> "waiting to send"
            LoggedMessage.Delivery.SENT -> "sent"
            LoggedMessage.Delivery.DELIVERED -> "delivered"
            LoggedMessage.Delivery.RECEIVED ->
                confidence?.let { "received, ${confidenceWord(it)} confidence" } ?: "received"
            // The one that matters. A message the operator believes went out and did not
            // is the worst state this screen can fail to communicate, so it is said in
            // full words rather than left as a mark.
            LoggedMessage.Delivery.FAILED -> "NOT delivered"
        }

    fun confidenceWord(level: Int): String =
        when (level.coerceIn(0, 3)) {
            0 -> "low"
            1 -> "medium"
            2 -> "high"
            else -> "exact"
        }

    /**
     * "13 B" is read by a speech engine as "thirteen bee". The compression claim is the
     * centre of this project and it should not arrive as a letter of the alphabet.
     */
    fun frameSize(bytes: Int): String = "$bytes bytes on the air"

    /**
     * A degraded banner, spoken. What happened and what to do — the icon carries no
     * information a listener can use, so it is not described.
     */
    fun degraded(reason: EngineState.Degraded.Reason): String = "${reason.message}. ${adviceFor(reason).doThis}"

    /**
     * The incoming alert screen. Spoken on arrival, because an operator who cannot see it
     * has to learn about it from the announcement rather than by exploring.
     */
    fun incomingAlert(
        from: String,
        text: String,
        position: String?,
    ): String =
        buildString {
            append("Alert from $from. ")
            append("$text. ")
            position?.let { append("Position $it. ") }
            append("Double tap to acknowledge.")
        }
}

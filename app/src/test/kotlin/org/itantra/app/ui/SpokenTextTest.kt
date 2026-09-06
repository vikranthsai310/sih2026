package org.itantra.app.ui

import org.itantra.audio.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What TalkBack says. Task **W7.23**.
 *
 * ## What this suite can and cannot do
 *
 * The full TalkBack pass is a device exercise — the swipe order, the focus traps, whether
 * an announcement actually interrupts — and no unit test replaces it. What it can do is
 * hold the part that is pure data: that every state has a spoken form, and that no spoken
 * form is a symbol.
 *
 * That second property is the one worth automating, because it is the one that regresses.
 * Adding a delivery state and giving it a tick mark takes a minute; noticing three months
 * later that a screen reader says "check mark check mark" takes a device, a blindfold and
 * somebody who remembered to look.
 */
class SpokenTextTest {
    /** Anything a speech engine cannot pronounce as a word. */
    private fun isSpeakable(text: String): Boolean =
        text.all { it.isLetterOrDigit() || it.isWhitespace() || it in ",.;:'-" }

    // ── nothing spoken is a symbol ───────────────────────────────────────────

    @Test
    fun `every delivery state has a spoken form made of words`() {
        for (state in LoggedMessage.Delivery.entries) {
            val spoken = Spoken.delivery(state)
            assertTrue("$state has no spoken form", spoken.isNotBlank())
            assertTrue("$state is spoken as '$spoken', which is not words", isSpeakable(spoken))
        }
    }

    @Test
    fun `the symbols on screen never reach the spoken form`() {
        val marks = LoggedMessage.Delivery.entries.map { state -> message(delivery = state).deliveryMark() }
        assertTrue("the screen really does use symbols", marks.any { !isSpeakable(it) })

        for (state in LoggedMessage.Delivery.entries) {
            val spoken = Spoken.messageRow(message(delivery = state))
            for (symbol in listOf("✓", "○", "●", "✕", "⚠", "·", "⟲")) {
                assertFalse("'$symbol' reached the spoken row for $state", symbol in spoken)
            }
        }
    }

    /** `●●○` would be read as "black circle black circle white circle". */
    @Test
    fun `confidence is spoken as a level rather than as dots`() {
        assertEquals("low", Spoken.confidenceWord(0))
        assertEquals("medium", Spoken.confidenceWord(1))
        assertEquals("high", Spoken.confidenceWord(2))
        assertEquals("exact", Spoken.confidenceWord(3))
        for (level in -2..5) assertTrue(isSpeakable(Spoken.confidenceWord(level)))
    }

    /** "13 B" is read as "thirteen bee". The compression claim deserves better. */
    @Test
    fun `a frame size is spoken as bytes rather than as the letter B`() {
        val spoken = Spoken.frameSize(13)
        assertTrue(spoken, "bytes" in spoken)
        assertTrue(isSpeakable(spoken))
    }

    @Test
    fun `every degraded reason has a spoken form carrying its advice`() {
        for (reason in EngineState.Degraded.Reason.entries) {
            val spoken = Spoken.degraded(reason)
            assertTrue("$reason is not announced", spoken.isNotBlank())
            assertTrue(
                "$reason is announced without telling the operator what to do",
                adviceFor(reason).doThis in spoken,
            )
            // The glyph is an ImageVector now, so it cannot reach a string at all. What is
            // still worth asserting is that the advice does -- the part of a banner that
            // says what to do is the part a listener most needs.
            assertTrue(
                "the advice did not reach the announcement",
                adviceFor(reason).doThis.isEmpty() || spoken.contains(adviceFor(reason).doThis),
            )
        }
    }

    // ── the row reads as one sentence ────────────────────────────────────────

    @Test
    fun `a message row is announced sender first, then the message`() {
        val spoken = Spoken.messageRow(message())
        assertTrue(spoken, spoken.startsWith("From Bravo."))
        assertTrue(spoken, spoken.indexOf("Bravo") < spoken.indexOf("मदद"))
    }

    /**
     * An alert must be announced as one. A listener who hears the sender and the text
     * without the word "alert" has no way to know this row is different from the rest.
     */
    @Test
    fun `an alert row says so before anything else`() {
        assertTrue(Spoken.messageRow(message(isAlert = true)).startsWith("Alert."))
    }

    /**
     * The state that matters most on this screen. A message the operator believes went out
     * and did not must be unmistakable in speech as well as on screen.
     */
    @Test
    fun `a failed delivery is spoken as not delivered`() {
        val spoken = Spoken.messageRow(message(delivery = LoggedMessage.Delivery.FAILED))
        assertTrue(spoken, "NOT delivered" in spoken)
    }

    @Test
    fun `a cross-language template says which language it was spoken in`() {
        val spoken =
            Spoken.messageRow(
                message(wasTemplate = true, language = "Tamil", sentInLanguage = "Hindi"),
            )
        assertTrue(spoken, "template" in spoken)
        assertTrue(spoken, "Hindi" in spoken)
    }

    @Test
    fun `the incoming alert screen announces what to do about it`() {
        val spoken = Spoken.incomingAlert("Bravo", "मदद चाहिए", position = "20.29, 85.82")
        assertTrue(spoken, spoken.startsWith("Alert from Bravo."))
        assertTrue(spoken, "acknowledge" in spoken.lowercase())
    }

    @Test
    fun `an alert without a position does not announce an empty one`() {
        val spoken = Spoken.incomingAlert("Bravo", "मदद चाहिए", position = null)
        assertFalse(spoken, "Position" in spoken)
    }

    private fun message(
        delivery: LoggedMessage.Delivery = LoggedMessage.Delivery.RECEIVED,
        isAlert: Boolean = false,
        wasTemplate: Boolean = false,
        language: String = "Hindi",
        sentInLanguage: String? = null,
    ) = LoggedMessage(
        from = "Bravo",
        text = "मदद चाहिए",
        age = "2 minutes ago",
        frameBytes = 13,
        language = language,
        sentInLanguage = sentInLanguage,
        wasTemplate = wasTemplate,
        isAlert = isAlert,
        delivery = delivery,
        confidence = 2,
    )
}

package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    private fun feed(
        e: Endpointer,
        isSpeech: Boolean,
        frames: Int,
    ): Endpointer.Event? {
        var last: Endpointer.Event? = null
        repeat(frames) { last = e.onFrame(isSpeech) ?: last }
        return last
    }

    /** 20 ms per frame throughout, matching the capture hop. */
    private fun frames(millis: Int) = millis / 20

    @Test
    fun `the normative windows are 400 ms telephone and 150 ms push-to-talk`() {
        assertEquals(400, Endpointer.Mode.TELEPHONE.trailingSilenceMillis)
        assertEquals(150, Endpointer.Mode.PUSH_TO_TALK.trailingSilenceMillis)
    }

    @Test
    fun `speech opens an utterance`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        assertEquals(Endpointer.Event.Started, e.onFrame(true))
        assertEquals(Endpointer.State.LISTENING, e.state)
    }

    @Test
    fun `silence alone never opens an utterance`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        repeat(500) { assertNull(e.onFrame(false)) }
        assertEquals(Endpointer.State.IDLE, e.state)
    }

    @Test
    fun `telephone mode finalises after four hundred milliseconds of silence`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        e.onFrame(true)
        feed(e, true, frames(1_000))

        // 390 ms of silence is not yet enough
        assertNull(feed(e, false, frames(380)))
        assertEquals(Endpointer.State.LISTENING, e.state)

        val event = feed(e, false, 1)
        assertTrue(event is Endpointer.Event.Ended)
        assertEquals(Endpointer.EndReason.SILENCE, (event as Endpointer.Event.Ended).reason)
    }

    @Test
    fun `push-to-talk finalises after one hundred and fifty milliseconds of silence`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        e.onFrame(true)
        feed(e, true, frames(1_000))

        assertNull(feed(e, false, frames(120)))
        val event = feed(e, false, frames(40))
        assertTrue(event is Endpointer.Event.Ended)
    }

    /**
     * The reason push-to-talk is measurably faster: releasing the key is itself an
     * end-of-utterance signal, so the silence window is not paid at all.
     */
    @Test
    fun `key release ends the utterance immediately`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        e.onFrame(true)
        feed(e, true, frames(1_000))

        val event = e.onKeyReleased()
        assertTrue(event is Endpointer.Event.Ended)
        assertEquals(Endpointer.EndReason.KEY_RELEASE, (event as Endpointer.Event.Ended).reason)
        assertEquals(Endpointer.State.FINALISING, e.state)
    }

    @Test
    fun `key release does nothing when nothing is in progress`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        assertNull(e.onKeyReleased())
    }

    /** Bounds latency and stops one speaker monopolising a half-duplex channel. */
    @Test
    fun `an utterance is cut at eight seconds`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        e.onFrame(true)
        val event = feed(e, true, frames(9_000))
        assertTrue(event is Endpointer.Event.Ended)
        assertEquals(Endpointer.EndReason.MAX_DURATION, (event as Endpointer.Event.Ended).reason)
        assertTrue("cut at ${event.durationMillis} ms", event.durationMillis >= 8_000)
    }

    /** Coughs, clicks and key noise must not become transmitted frames. */
    @Test
    fun `an utterance shorter than three hundred milliseconds is discarded`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        e.onFrame(true)
        feed(e, true, frames(100))
        val event = feed(e, false, frames(200))

        assertTrue("expected Discarded, got $event", event is Endpointer.Event.Discarded)
        assertEquals("a discard must return straight to idle", Endpointer.State.IDLE, e.state)
    }

    @Test
    fun `trailing silence is not counted as part of the utterance`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        e.onFrame(true)
        feed(e, true, frames(1_000))
        val event = feed(e, false, frames(400)) as Endpointer.Event.Ended

        // roughly a second of speech, not 1.4 s including the window
        assertTrue(
            "duration ${event.durationMillis} ms should exclude the 400 ms window",
            event.durationMillis in 900..1_100,
        )
    }

    @Test
    fun `a brief pause inside a sentence does not end it`() {
        val e = Endpointer(Endpointer.Mode.TELEPHONE)
        e.onFrame(true)
        feed(e, true, frames(600))
        assertNull("a 200 ms inter-clause pause must not finalise", feed(e, false, frames(200)))
        feed(e, true, frames(600))
        assertEquals(Endpointer.State.LISTENING, e.state)
    }

    @Test
    fun `the cycle returns to idle and can run again`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        repeat(3) {
            assertEquals(Endpointer.Event.Started, e.onFrame(true))
            feed(e, true, frames(500))
            assertTrue(e.onKeyReleased() is Endpointer.Event.Ended)
            e.onFinalised()
            assertEquals(Endpointer.State.IDLE, e.state)
        }
    }

    @Test
    fun `frames arriving while finalising are ignored`() {
        val e = Endpointer(Endpointer.Mode.PUSH_TO_TALK)
        e.onFrame(true)
        feed(e, true, frames(500))
        e.onKeyReleased()
        assertEquals(Endpointer.State.FINALISING, e.state)
        repeat(50) { assertNull(e.onFrame(true)) }
        assertEquals(Endpointer.State.FINALISING, e.state)
    }

    /**
     * The whole point of the mode split, expressed as a number: push-to-talk pays
     * 150 ms where telephone mode pays 400 ms.
     */
    @Test
    fun `push-to-talk saves two hundred and fifty milliseconds over telephone mode`() {
        assertEquals(
            250,
            Endpointer.Mode.TELEPHONE.trailingSilenceMillis -
                Endpointer.Mode.PUSH_TO_TALK.trailingSilenceMillis,
        )
    }
}

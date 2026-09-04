package org.itantra.link

import org.itantra.proto.PttControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Floor control, tasks W5.1/W5.3/W5.8, risk S-05.
 *
 * The contention tests are the important ones, and they are written as a **pair of
 * units resolving the same collision independently** — because the property that
 * matters is not that each behaves sensibly alone, but that the two of them reach
 * conclusions that agree. Two units that both yield lose the message; two that both
 * keep the floor garble it.
 */
class FloorControlTest {
    private fun floor(src: Int) = FloorControl(localSrc = src, random = Random(1))

    // ── the ordinary path ────────────────────────────────────────────────────

    @Test
    fun `a press on a free channel takes the floor and announces it`() {
        val f = floor(2)
        val reaction = f.onLocalPress(0)

        assertEquals(FloorControl.Reaction.Send(PttControl.SEIZE), reaction)
        assertEquals(FloorControl.State.HELD_BY_ME, f.state)
        assertTrue(f.mayTransmit)
        assertTrue(f.isBusy)
    }

    @Test
    fun `releasing frees the channel and announces it`() {
        val f = floor(2)
        f.onLocalPress(0)
        val reaction = f.onLocalRelease(100)

        assertEquals(FloorControl.Reaction.Send(PttControl.RELEASE), reaction)
        assertEquals(FloorControl.State.FREE, f.state)
        assertFalse(f.mayTransmit)
        assertFalse(f.isBusy)
    }

    @Test
    fun `a peer seize marks the channel busy without this unit transmitting`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)

        assertEquals(FloorControl.State.HELD_BY_PEER, f.state)
        assertEquals(7, f.holderSrc)
        assertTrue("the busy indicator must light", f.isBusy)
        assertFalse("this unit must not transmit", f.mayTransmit)
    }

    @Test
    fun `a peer release frees the channel`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)
        f.onPeerControl(PttControl.RELEASE, src = 7, nowMillis = 500)

        assertEquals(FloorControl.State.FREE, f.state)
        assertNull(f.holderSrc)
    }

    // ── W5.8: the refusal is haptic, never a dialog ──────────────────────────

    /**
     * Meena is gloved, at altitude, with the screen dark. A modal dialog would have to
     * be dismissed before she could try again.
     */
    @Test
    fun `a press while a peer holds the floor is refused, naming the holder`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)

        val reaction = f.onLocalPress(50)
        assertEquals(FloorControl.Reaction.Refused(7), reaction)
        assertFalse("a refused press must not put this unit on the channel", f.mayTransmit)
    }

    @Test
    fun `pressing again while already transmitting does nothing`() {
        val f = floor(2)
        f.onLocalPress(0)
        assertEquals(FloorControl.Reaction.Nothing, f.onLocalPress(10))
        assertEquals(FloorControl.State.HELD_BY_ME, f.state)
    }

    @Test
    fun `releasing a floor this unit never held announces nothing`() {
        assertEquals(FloorControl.Reaction.Nothing, floor(2).onLocalRelease(0))
    }

    // ── contention: the two units must agree ─────────────────────────────────

    /**
     * The property that matters. Both units press inside the propagation window, then
     * each hears the other's seize. Exactly one must keep the floor.
     */
    @Test
    fun `a simultaneous press resolves to exactly one holder`() {
        val low = floor(2)
        val high = floor(9)

        // Both press before either has heard the other.
        low.onLocalPress(0)
        high.onLocalPress(0)

        // Then each hears the other's announcement.
        val lowReaction = low.onPeerControl(PttControl.SEIZE, src = 9, nowMillis = 30)
        val highReaction = high.onPeerControl(PttControl.SEIZE, src = 2, nowMillis = 30)

        assertEquals(FloorControl.Reaction.KeepFloor, lowReaction)
        assertTrue("the higher SRC must yield", highReaction is FloorControl.Reaction.YieldAndRetry)

        assertTrue("the lower SRC keeps transmitting", low.mayTransmit)
        assertFalse("the higher SRC must stop", high.mayTransmit)
        assertEquals(
            "both must agree on who holds the floor",
            low.holderSrc,
            high.holderSrc,
        )
    }

    /** Whichever order the two seizes are numbered, the answer is the same. */
    @Test
    fun `the contention winner does not depend on who pressed first`() {
        for ((a, b) in listOf(2 to 9, 9 to 2)) {
            val first = floor(a)
            val second = floor(b)
            first.onLocalPress(0)
            second.onLocalPress(0)
            first.onPeerControl(PttControl.SEIZE, src = b, nowMillis = 30)
            second.onPeerControl(PttControl.SEIZE, src = a, nowMillis = 30)

            val winner = if (a < b) first else second
            val loser = if (a < b) second else first
            assertTrue("the lower SRC must win regardless of order", winner.mayTransmit)
            assertFalse(loser.mayTransmit)
        }
    }

    @Test
    fun `the loser is told how long to wait before retrying`() {
        val high = floor(9)
        high.onLocalPress(0)
        val reaction = high.onPeerControl(PttControl.SEIZE, src = 2, nowMillis = 30)

        reaction as FloorControl.Reaction.YieldAndRetry
        val longest = FloorControl.BACKOFF_MIN_MILLIS + FloorControl.BACKOFF_JITTER_MILLIS
        assertTrue(
            "backoff ${reaction.retryAfterMillis} ms out of range",
            reaction.retryAfterMillis in FloorControl.BACKOFF_MIN_MILLIS..longest,
        )
    }

    /** Randomised, so the same pair colliding twice does not collide identically again. */
    @Test
    fun `the backoff is randomised across units`() {
        val delays =
            (0 until 40).map { seed ->
                val f = FloorControl(localSrc = 9, random = Random(seed))
                f.onLocalPress(0)
                (
                    f.onPeerControl(PttControl.SEIZE, src = 2, nowMillis = 30)
                        as FloorControl.Reaction.YieldAndRetry
                ).retryAfterMillis
            }
        assertTrue("expected a spread of backoffs, got ${delays.toSet()}", delays.toSet().size > 5)
    }

    // ── a dead peer must not hold the channel forever ────────────────────────

    /**
     * A unit walks out of range mid-transmission, or its battery dies with the key
     * held. Without expiry every remaining unit believes someone is still talking, and
     * the channel is silently dead.
     */
    @Test
    fun `a peer hold that lasts implausibly long expires`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)
        assertTrue(f.isBusy)

        f.onTick(FloorControl.STALE_HOLD_MILLIS - 1)
        assertTrue("must not expire early", f.isBusy)

        f.onTick(FloorControl.STALE_HOLD_MILLIS)
        assertEquals(FloorControl.State.FREE, f.state)
        assertNull(f.holderSrc)
    }

    /** Longer than the 8 s maximum utterance, so a real transmission is never cut off. */
    @Test
    fun `the expiry is longer than the longest legitimate utterance`() {
        assertTrue(FloorControl.STALE_HOLD_MILLIS > 8_000)
    }

    @Test
    fun `a press after the hold expires is accepted`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)
        val reaction = f.onLocalPress(FloorControl.STALE_HOLD_MILLIS + 1)

        assertEquals(FloorControl.Reaction.Send(PttControl.SEIZE), reaction)
        assertTrue(f.mayTransmit)
    }

    // ── stale and spurious frames ────────────────────────────────────────────

    /**
     * A release from a unit that does not hold the floor must not free it, or a stale
     * frame from a third unit could cut a live transmission short.
     */
    @Test
    fun `a release from a unit that does not hold the floor is ignored`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)
        f.onPeerControl(PttControl.RELEASE, src = 4, nowMillis = 100)

        assertEquals("unit 7 still holds the floor", FloorControl.State.HELD_BY_PEER, f.state)
        assertEquals(7, f.holderSrc)
    }

    @Test
    fun `a peer re-announcing its own hold changes nothing`() {
        val f = floor(2)
        f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 0)
        assertEquals(FloorControl.Reaction.Nothing, f.onPeerControl(PttControl.SEIZE, src = 7, nowMillis = 40))
        assertEquals(7, f.holderSrc)
    }

    @Test
    fun `a peer release does not free a floor this unit holds`() {
        val f = floor(2)
        f.onLocalPress(0)
        f.onPeerControl(PttControl.RELEASE, src = 7, nowMillis = 50)
        assertTrue("this unit must keep transmitting", f.mayTransmit)
    }

    @Test
    fun `reset returns to a free channel`() {
        val f = floor(2)
        f.onLocalPress(0)
        f.reset()
        assertEquals(FloorControl.State.FREE, f.state)
        assertNull(f.holderSrc)
    }
}

/** The `PTT_CTL` payload itself. */
class PttControlTest {
    @Test
    fun `seize and release round-trip`() {
        for (control in PttControl.entries) {
            assertEquals(control, PttControl.decode(control.encode()))
        }
    }

    @Test
    fun `the wire values are normative`() {
        assertEquals(0x01, PttControl.SEIZE.code)
        assertEquals(0x00, PttControl.RELEASE.code)
    }

    @Test
    fun `a payload of the wrong size is refused`() {
        assertNull(PttControl.decode(ByteArray(0)))
        assertNull(PttControl.decode(ByteArray(2)))
    }

    @Test
    fun `a reserved value is refused rather than guessed`() {
        assertNull(PttControl.decode(byteArrayOf(0x02)))
        assertNull(PttControl.decode(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `a control frame is thirteen bytes`() {
        assertEquals(13, PttControl.FRAME_SIZE)
    }
}

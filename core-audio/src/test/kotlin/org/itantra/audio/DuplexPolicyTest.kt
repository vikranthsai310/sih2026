package org.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Half duplex, full duplex and barge-in — tasks W5.5, W5.6 and W5.7. */
class DuplexPolicyTest {
    private fun half() = DuplexPolicy(DuplexPolicy.Mode.HALF)

    private fun full() = DuplexPolicy(DuplexPolicy.Mode.FULL)

    // ── half duplex, W5.5 ────────────────────────────────────────────────────

    /**
     * The mute is not politeness. Without it the handset's own speaker feeds its
     * microphone, the energy gate never closes, and the operator's transmission never
     * ends.
     */
    @Test
    fun `the speaker is muted while transmitting`() {
        val p = half()
        p.onPlaybackStart()
        val decision = p.onTransmitStart()

        assertFalse("playback must stop", decision.playbackEnabled)
        assertEquals("and be silent", 0f, decision.playbackGain, 1e-6f)
        assertTrue(decision.captureEnabled)
    }

    @Test
    fun `capture only runs while transmitting`() {
        val p = half()
        assertFalse(p.current().captureEnabled)
        assertTrue(p.onTransmitStart().captureEnabled)
        assertFalse(p.onTransmitEnd().captureEnabled)
    }

    @Test
    fun `playback resumes once transmission ends`() {
        val p = half()
        p.onPlaybackStart()
        p.onTransmitStart()
        val decision = p.onTransmitEnd()

        assertTrue(decision.playbackEnabled)
        assertEquals(1f, decision.playbackGain, 1e-6f)
    }

    @Test
    fun `push-to-talk uses the shorter endpoint window`() {
        assertEquals(150, half().endpointMillis())
    }

    // ── full duplex, W5.6 ────────────────────────────────────────────────────

    @Test
    fun `capture runs continuously and the VAD decides`() {
        val p = full()
        assertTrue("capture is always on in full duplex", p.current().captureEnabled)
        assertTrue(p.onTransmitEnd().captureEnabled)
    }

    @Test
    fun `telephone mode uses the longer endpoint window`() {
        assertEquals(400, full().endpointMillis())
    }

    // ── barge-in, W5.7 ───────────────────────────────────────────────────────

    /**
     * Ducked rather than cut: an abrupt stop mid-word sounds like a fault, and an
     * operator who hears a fault repeats themselves.
     */
    @Test
    fun `talking over incoming audio ducks it rather than cutting it`() {
        val p = full()
        p.onPlaybackStart()
        val decision = p.onTransmitStart()

        assertTrue("playback continues", decision.playbackEnabled)
        assertEquals(DuplexPolicy.DUCKED_GAIN, decision.playbackGain, 1e-3f)
        assertTrue("but quieter", decision.playbackGain < 1f)
    }

    @Test
    fun `the duck is eighteen decibels`() {
        assertEquals(
            DuplexPolicy.gainForDecibels(-18.0),
            DuplexPolicy.DUCKED_GAIN,
            1e-3f,
        )
    }

    @Test
    fun `the duck lifts when the operator stops talking`() {
        val p = full()
        p.onPlaybackStart()
        p.onTransmitStart()
        assertEquals(1f, p.onTransmitEnd().playbackGain, 1e-6f)
    }

    /** Stopping at a chunk boundary, not mid-word. The tail is under 200 ms. */
    @Test
    fun `barge-in in half duplex stops playback at the end of the chunk`() {
        val p = half()
        p.onPlaybackStart()
        val decision = p.onTransmitStart()
        assertTrue(decision.stopPlaybackAtChunkEnd)
    }

    @Test
    fun `transmitting with nothing playing is not a barge-in`() {
        val p = half()
        assertFalse(p.onTransmitStart().stopPlaybackAtChunkEnd)
    }

    @Test
    fun `the duck must be audible within a hundred milliseconds`() {
        assertEquals(100, DuplexPolicy.DUCK_WITHIN_MILLIS)
    }

    // ── switching modes ──────────────────────────────────────────────────────

    @Test
    fun `switching mode changes the endpoint window`() {
        val p = half()
        assertEquals(150, p.endpointMillis())
        p.setMode(DuplexPolicy.Mode.FULL)
        assertEquals(400, p.endpointMillis())
        assertEquals(DuplexPolicy.Mode.FULL, p.currentMode)
    }

    @Test
    fun `gain conversion is correct at the reference points`() {
        assertEquals(1f, DuplexPolicy.gainForDecibels(0.0), 1e-6f)
        assertEquals(0.5f, DuplexPolicy.gainForDecibels(-6.02), 1e-3f)
    }
}

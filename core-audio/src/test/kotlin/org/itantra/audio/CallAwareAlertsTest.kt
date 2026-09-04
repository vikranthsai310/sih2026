package org.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An alert arriving during a telephone call. Task **W5.25**.
 *
 * The behaviour is the one exception to "an alert always overrides", so these tests are as
 * much about what must *not* happen as about what must.
 */
class CallAwareAlertsTest {
    /** Records what the platform was asked to do, so the ordering is observable. */
    private class FakeAudio : AlertPlayback.AudioSystem {
        val calls = ArrayList<String>()
        var volume = 4

        override fun currentAlarmVolume(): Int = volume

        override fun maxAlarmVolume(): Int = 15

        override fun setAlarmVolume(volume: Int) {
            this.volume = volume
            calls += "volume=$volume"
        }

        override fun requestExclusiveFocus(): Boolean {
            calls += "focus"
            return true
        }

        override fun abandonFocus() {
            calls += "abandonFocus"
        }

        override fun acquireWakeLock() {
            calls += "wake"
        }

        override fun releaseWakeLock() {
            calls += "releaseWake"
        }

        override fun wakeScreenWithFullScreenIntent() {
            calls += "screen"
        }

        override fun vibrate(patternMillis: LongArray) {
            calls += "vibrate"
        }

        override fun playOnAlarmStream(pcm: ShortArray) {
            calls += "play"
        }
    }

    private fun setUp(): Triple<FakeAudio, AlertPlayback, CallAwareAlerts> {
        val audio = FakeAudio()
        val playback = AlertPlayback(audio)
        return Triple(audio, playback, CallAwareAlerts(playback))
    }

    private fun tone(n: Int = 1) = ShortArray(160) { (it * n).toShort() }

    // ── not on a call: nothing changes ───────────────────────────────────────

    @Test
    fun `an alert with no call in progress is announced immediately`() {
        val (audio, _, alerts) = setUp()

        val disposition = alerts.onAlert(tone(), from = "Ravi", nowMillis = 0)

        assertTrue(disposition.toString(), disposition is CallAwareAlerts.Disposition.Announced)
        assertEquals("announced twice, as always", 2, audio.calls.count { it == "play" })
        assertEquals(0, alerts.queuedCount)
    }

    // ── on a call: held, and not silently ────────────────────────────────────

    /**
     * The decision this class exists for. Announcing into a call is not a louder alert; it
     * is an alert nobody hears, and it takes out the channel the operator is more likely
     * to be coordinating on.
     */
    @Test
    fun `an alert during a call is not announced`() {
        val (audio, _, alerts) = setUp()
        alerts.onCallStarted()

        alerts.onAlert(tone(), from = "Ravi", nowMillis = 0)

        assertEquals("nothing may be played into a call", 0, audio.calls.count { it == "play" })
        assertEquals("and the alarm volume is not touched", 4, audio.volume)
        assertTrue("the screen is not woken either", audio.calls.none { it == "screen" })
    }

    /** But it is not held silently. Vibration reaches an ear pressed to a handset. */
    @Test
    fun `an alert during a call vibrates on arrival`() {
        val (audio, _, alerts) = setUp()
        alerts.onCallStarted()

        val disposition = alerts.onAlert(tone(), from = "Ravi", nowMillis = 0)

        assertEquals(1, audio.calls.count { it == "vibrate" })
        assertTrue(disposition is CallAwareAlerts.Disposition.Held)
        assertTrue((disposition as CallAwareAlerts.Disposition.Held).vibrated)
        assertEquals(1, disposition.queued)
    }

    // ── the call ends ────────────────────────────────────────────────────────

    @Test
    fun `a held alert is announced in full when the call ends`() {
        val (audio, _, alerts) = setUp()
        alerts.onCallStarted()
        alerts.onAlert(tone(), from = "Ravi", nowMillis = 0)

        val outcomes = alerts.onCallEnded()

        assertEquals(1, outcomes.size)
        assertTrue("nothing about the announcement is reduced", outcomes.single().succeeded)
        assertEquals("full announcement, twice", 2, audio.calls.count { it == "play" })
        assertTrue("and at maximum volume", audio.calls.contains("volume=15"))
        assertEquals("with the operator's volume put back", 4, audio.volume)
    }

    /**
     * Every one of them, in arrival order. Two alerts about the same incident read as a
     * different situation reversed, and a queue that quietly discarded some would leave a
     * sender believing a message was delivered that never was.
     */
    @Test
    fun `every alert held during a call is announced, in order`() {
        val (audio, _, alerts) = setUp()
        alerts.onCallStarted()
        alerts.onAlert(tone(1), from = "Ravi", nowMillis = 1_000)
        alerts.onAlert(tone(2), from = "Meena", nowMillis = 2_000)
        alerts.onAlert(tone(3), from = "Base", nowMillis = 3_000)

        assertEquals(listOf("Ravi", "Meena", "Base"), alerts.peek().map { it.from })
        val outcomes = alerts.onCallEnded()

        assertEquals(3, outcomes.size)
        assertEquals("three alerts, two plays each", 6, audio.calls.count { it == "play" })
        assertEquals(0, alerts.queuedCount)
    }

    @Test
    fun `the queue is empty and the call flag clear after the call ends`() {
        val (_, _, alerts) = setUp()
        alerts.onCallStarted()
        alerts.onAlert(tone(), from = "Ravi", nowMillis = 0)
        alerts.onCallEnded()

        assertFalse(alerts.onCall)
        assertEquals(0, alerts.queuedCount)

        // And the next alert goes straight out, as normal.
        val disposition = alerts.onAlert(tone(), from = "Ravi", nowMillis = 1_000)
        assertTrue(disposition is CallAwareAlerts.Disposition.Announced)
    }

    @Test
    fun `a call with no alerts ends with nothing to announce`() {
        val (audio, _, alerts) = setUp()
        alerts.onCallStarted()

        assertTrue(alerts.onCallEnded().isEmpty())
        assertEquals(0, audio.calls.count { it == "play" })
    }

    // ── the bound ────────────────────────────────────────────────────────────

    /**
     * A queue is not a filter — deciding the third alert of a call matters less than the
     * first is a judgement this code has no basis for. What it does bound is memory.
     */
    @Test
    fun `the queue is bounded and drops the oldest`() {
        val (_, _, alerts) = setUp()
        alerts.onCallStarted()

        for (n in 1..CallAwareAlerts.CAPACITY + 2) {
            alerts.onAlert(tone(n), from = "unit$n", nowMillis = n * 1_000L)
        }

        assertEquals(CallAwareAlerts.CAPACITY, alerts.queuedCount)
        assertEquals("the newest survive", "unit3", alerts.peek().first().from)
        assertEquals(2, alerts.droppedOldest)
    }

    @Test
    fun `a caller is told which alert was dropped to make room`() {
        val (_, playback, _) = setUp()
        val alerts = CallAwareAlerts(playback, capacity = 2)
        alerts.onCallStarted()

        alerts.onAlert(tone(1), from = "Ravi", nowMillis = 0)
        alerts.onAlert(tone(2), from = "Meena", nowMillis = 1_000)
        val third = alerts.onAlert(tone(3), from = "Base", nowMillis = 2_000)

        assertTrue(third is CallAwareAlerts.Disposition.HeldAndDropped)
        assertEquals("Ravi", (third as CallAwareAlerts.Disposition.HeldAndDropped).droppedFrom)
    }

    /** A caller reusing its buffer must not rewrite an alert already waiting. */
    @Test
    fun `a held alert is copied rather than referenced`() {
        val (_, _, alerts) = setUp()
        alerts.onCallStarted()
        val buffer = ShortArray(160) { 7 }
        alerts.onAlert(buffer, from = "Ravi", nowMillis = 0)
        buffer.fill(0)

        assertEquals(7, alerts.peek().single().pcm.first().toInt())
    }
}

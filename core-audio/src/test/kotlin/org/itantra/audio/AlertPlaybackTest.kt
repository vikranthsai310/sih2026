package org.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alert announcement, tasks W5.9–W5.14.
 *
 * The real behaviour can only be observed on a physical handset that is locked and
 * silenced. What *can* be tested here — exhaustively, and cheaply — is the ordering and
 * the restoration, which is the part that actually gets broken.
 */
class AlertPlaybackTest {
    /** Records every platform call in order, so the sequence itself can be asserted. */
    private class FakeAudio(
        var volume: Int = 3,
        private val maxVolume: Int = 15,
        private val grantFocus: Boolean = true,
        private val failOnPlay: Boolean = false,
    ) : AlertPlayback.AudioSystem {
        val calls = ArrayList<String>()
        var plays = 0
        var wakeLocksHeld = 0
        var focusHeld = 0

        override fun currentAlarmVolume(): Int = volume

        override fun maxAlarmVolume(): Int = maxVolume

        override fun setAlarmVolume(volume: Int) {
            calls += "setVolume($volume)"
            this.volume = volume
        }

        override fun requestExclusiveFocus(): Boolean {
            calls += "requestFocus"
            if (grantFocus) focusHeld++
            return grantFocus
        }

        override fun abandonFocus() {
            calls += "abandonFocus"
            focusHeld--
        }

        override fun acquireWakeLock() {
            calls += "acquireWakeLock"
            wakeLocksHeld++
        }

        override fun releaseWakeLock() {
            calls += "releaseWakeLock"
            wakeLocksHeld--
        }

        override fun wakeScreenWithFullScreenIntent() {
            calls += "wakeScreen"
        }

        override fun vibrate(patternMillis: LongArray) {
            calls += "vibrate"
        }

        override fun playOnAlarmStream(pcm: ShortArray) {
            calls += "play"
            plays++
            if (failOnPlay) throw IllegalStateException("audio device busy")
        }
    }

    private val pcm = ShortArray(16_000)

    // ── all six steps happen, in order ───────────────────────────────────────

    @Test
    fun `every step of the alert path runs`() {
        val audio = FakeAudio()
        AlertPlayback(audio).announce(pcm)

        for (step in listOf("acquireWakeLock", "setVolume(15)", "requestFocus", "wakeScreen", "vibrate", "play")) {
            assertTrue("$step never happened: ${audio.calls}", step in audio.calls)
        }
    }

    /** The wake lock comes first; everything after it is pointless if the device sleeps. */
    @Test
    fun `the wake lock is taken before anything else`() {
        val audio = FakeAudio()
        AlertPlayback(audio).announce(pcm)
        assertEquals("acquireWakeLock", audio.calls.first())
    }

    @Test
    fun `the volume is raised before focus is requested and audio plays`() {
        val audio = FakeAudio()
        AlertPlayback(audio).announce(pcm)

        val raise = audio.calls.indexOf("setVolume(15)")
        assertTrue("volume must be raised before playing", raise < audio.calls.indexOf("play"))
        assertTrue("volume must be raised before focus", raise < audio.calls.indexOf("requestFocus"))
    }

    /** A single announcement in a noisy environment is missed. */
    @Test
    fun `the message is announced twice`() {
        val audio = FakeAudio()
        val outcome = AlertPlayback(audio).announce(pcm)
        assertEquals(2, audio.plays)
        assertEquals(AlertPlayback.REPEATS, outcome.repeats)
    }

    // ── W5.11: restoration, the one that gets forgotten ──────────────────────

    /**
     * Forgetting this leaves the handset permanently at maximum alarm volume — silent,
     * self-inflicted, and certain to be discovered mid-demonstration.
     */
    @Test
    fun `the operator's volume is restored exactly`() {
        val audio = FakeAudio(volume = 4)
        val outcome = AlertPlayback(audio).announce(pcm)

        assertEquals("the volume must be put back", 4, audio.volume)
        assertEquals(4, outcome.volumeRaisedFrom)
        assertEquals(4, outcome.volumeRestoredTo)
        assertEquals(
            "restoration must be the last volume change",
            "setVolume(4)",
            audio.calls.last {
                it.startsWith("setVolume")
            },
        )
    }

    @Test
    fun `a volume of zero is restored to zero, not left raised`() {
        val audio = FakeAudio(volume = 0)
        AlertPlayback(audio).announce(pcm)
        assertEquals("a silenced handset must be left silenced", 0, audio.volume)
    }

    /** The failure that matters most: playback throws and the volume stays at maximum. */
    @Test
    fun `the volume is restored even when playback fails`() {
        val audio = FakeAudio(volume = 5, failOnPlay = true)
        val outcome = AlertPlayback(audio).announce(pcm)

        assertFalse(outcome.succeeded)
        assertEquals("a failed alert must not leave the handset at maximum", 5, audio.volume)
        assertEquals("audio device busy", outcome.failure)
    }

    @Test
    fun `the wake lock is released even when playback fails`() {
        val audio = FakeAudio(failOnPlay = true)
        AlertPlayback(audio).announce(pcm)
        assertEquals("a wake lock must not outlive the alert", 0, audio.wakeLocksHeld)
    }

    @Test
    fun `focus is abandoned even when playback fails`() {
        val audio = FakeAudio(failOnPlay = true)
        AlertPlayback(audio).announce(pcm)
        assertEquals(0, audio.focusHeld)
    }

    @Test
    fun `nothing is left held after a successful alert`() {
        val audio = FakeAudio()
        AlertPlayback(audio).announce(pcm)
        assertEquals(0, audio.wakeLocksHeld)
        assertEquals(0, audio.focusHeld)
    }

    // ── W5.12: a refused focus request does not silence the alert ────────────

    /**
     * Another application holding audio focus is exactly the situation an alert must
     * override. Respecting the refusal here would defeat the whole requirement.
     */
    @Test
    fun `the alert plays even when audio focus is refused`() {
        val audio = FakeAudio(grantFocus = false)
        val outcome = AlertPlayback(audio).announce(pcm)

        assertFalse(outcome.focusGranted)
        assertEquals("a refused focus request must not stop playback", 2, audio.plays)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun `focus that was never granted is not abandoned`() {
        val audio = FakeAudio(grantFocus = false)
        AlertPlayback(audio).announce(pcm)
        assertFalse("abandonFocus" in audio.calls)
    }

    // ── the pattern is distinguishable ───────────────────────────────────────

    /** An operator should be able to tell an alert from a message without looking. */
    @Test
    fun `the vibration pattern is long and repeated, not a notification tick`() {
        val pattern = AlertPlayback.VIBRATION_PATTERN
        assertTrue("expected several pulses", pattern.size >= 5)
        assertTrue("pulses must be long", pattern.filterIndexed { i, _ -> i % 2 == 1 }.all { it >= 500 })
    }
}

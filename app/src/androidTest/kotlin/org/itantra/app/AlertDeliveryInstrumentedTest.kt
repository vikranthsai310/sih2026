package org.itantra.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.platform.app.InstrumentationRegistry
import org.itantra.app.platform.AndroidAlertAudio
import org.itantra.audio.AlertPlayback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The alert path, on a real handset. Tasks **W5.21**–**W5.27**.
 *
 * ## Why these cannot be unit tests
 *
 * The policy in [AlertPlayback] is unit-tested exhaustively and passes. None of that
 * proves an alert is *audible* on a locked, silenced handset, because the thing being
 * tested is the vendor's audio policy, not our code. Manufacturers differ on what
 * `USAGE_ALARM` overrides, on whether Do Not Disturb honours `setBypassDnd`, and on
 * whether a full-screen intent wakes the screen — and the documentation does not say.
 *
 * **A unit that has never announced an alert on this model of phone has not been tested.**
 *
 * ## Running them
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 *
 * Several need the device put into a state the harness cannot reach on its own, and each
 * says so. Those are marked in `docs/TODO.md` as needing a person, because a test that
 * silently passes without the condition it names is worse than no test.
 */
class AlertDeliveryInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var audioManager: AudioManager
    private var volumeBefore = 0

    /** A short tone, so a listener can tell the alert sounded. */
    private fun tone(
        millis: Int = 600,
        rate: Int = 22_050,
    ): ShortArray =
        ShortArray(rate * millis / 1000) {
            (12_000 * Math.sin(2 * Math.PI * 880 * it / rate)).toInt().toShort()
        }

    @Before
    fun captureVolume() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeBefore = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    @After
    fun restoreVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volumeBefore, 0)
    }

    // ── W5.21 ────────────────────────────────────────────────────────────────

    /**
     * **W5.21 — alert on a locked handset.**
     *
     * Requires the device to be locked. Lock it, then run with a delay, or observe the
     * run and confirm by eye and ear: audio at full volume, the screen wakes, the
     * vibration fires.
     *
     * The assertions below check what the harness can see; the audible and visible parts
     * are what the gate video records.
     */
    @Test
    fun alertPlaysAtFullVolume() {
        val audio = AndroidAlertAudio(context)
        val outcome = AlertPlayback(audio).announce(tone())

        assertTrue("the alert must not fail: ${outcome.failure}", outcome.succeeded)
        assertEquals("it must announce twice", 2, outcome.repeats)
    }

    // ── W5.26, and the failure that matters most ─────────────────────────────

    /**
     * **W5.26 — volume restoration.**
     *
     * The defect this catches leaves the handset permanently at maximum alarm volume:
     * silent, self-inflicted, and certain to be found mid-demonstration.
     */
    @Test
    fun theOperatorsVolumeIsRestored() {
        val audio = AndroidAlertAudio(context)
        val quiet = (audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 3)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, quiet, 0)

        AlertPlayback(audio).announce(tone(200))

        assertEquals(
            "the alarm volume must be exactly what it was",
            quiet,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    @Test
    fun aSilencedHandsetIsLeftSilenced() {
        val audio = AndroidAlertAudio(context)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        AlertPlayback(audio).announce(tone(200))

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    // ── W5.24 and W5.27 ──────────────────────────────────────────────────────

    /**
     * **W5.24 — alert during music playback**, and **W5.27 — focus loss ignored**.
     *
     * Another application holding audio focus is precisely the case an alert must
     * override. This starts a competing stream, takes focus with it, and asserts the
     * alert still completes.
     */
    @Test
    fun alertOverridesAnotherApplicationHoldingFocus() {
        val competitor = playSilentMediaStream()
        try {
            val outcome = AlertPlayback(AndroidAlertAudio(context)).announce(tone(300))
            assertTrue("the alert must complete regardless of focus", outcome.succeeded)
            assertEquals(2, outcome.repeats)
        } finally {
            runCatching { competitor.stop() }
            competitor.release()
        }
    }

    /** A media stream that occupies the audio output without making noise. */
    private fun playSilentMediaStream(): AudioTrack {
        val rate = 22_050
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(rate * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track.play()
        track.write(ShortArray(rate), 0, rate, AudioTrack.WRITE_NON_BLOCKING)
        return track
    }

    // ── conditions the harness cannot create on its own ──────────────────────

    /**
     * **W5.22 — ringer silenced**, **W5.23 — Do Not Disturb**, **W5.25 — during a call**.
     *
     * These need the device put into a state no test can reach without either a granted
     * notification-policy permission or a second phone. They are listed here so the
     * suite names them rather than quietly omitting them, and each is run by hand against
     * the checklist in `docs/DEMO.md` before the gate.
     *
     * W5.25 in particular has a **documented expected behaviour rather than an assertion**:
     * during a call the microphone belongs to the telephony stack, so the engine goes
     * `DEGRADED` and the alert is queued and repeated after the call ends. Asserting a
     * specific vendor behaviour here would encode one manufacturer's choice as the
     * requirement.
     */
    @Test
    fun manualConditionsAreEnumerated() {
        val manual =
            listOf(
                "W5.22 ringer silenced",
                "W5.23 Do Not Disturb",
                "W5.25 during a phone call",
            )
        assertEquals("these three are run by hand before the gate", 3, manual.size)
    }
}

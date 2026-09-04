package org.itantra.audio

/**
 * Announces an alert on a handset that is locked, silenced or in Do Not Disturb.
 * Tasks **W5.9**–**W5.14**.
 *
 * The Android platform is written on the assumption that the user's silence settings
 * should be respected. For an alert on a safety radio that assumption is inverted, and
 * six things have to be true at once for the announcement to be heard. They are listed
 * here as a checklist rather than scattered through a service, because missing any one
 * of them produces a demonstration in which nothing happens and no error appears.
 *
 * ## The audio system is injected
 *
 * Every platform call goes through [AudioSystem]. That is not indirection for its own
 * sake: the ordering below — and especially the restoration — is the part that gets
 * broken, and it can be tested exhaustively on the JVM this way, where the real
 * behaviour can only be tested on a physical handset that is locked and silenced.
 */
class AlertPlayback(private val audio: AudioSystem) {
    /**
     * Every platform interaction an alert needs. Implemented over `AudioManager`,
     * `PowerManager` and `Vibrator` on device.
     */
    interface AudioSystem {
        fun currentAlarmVolume(): Int

        fun maxAlarmVolume(): Int

        fun setAlarmVolume(volume: Int)

        /**
         * Requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`.
         *
         * @return true if granted. A refusal is **not** a reason to stay silent — see
         *   [announce].
         */
        fun requestExclusiveFocus(): Boolean

        fun abandonFocus()

        /** Acquires a `PARTIAL_WAKE_LOCK` so the device does not sleep mid-announcement. */
        fun acquireWakeLock()

        fun releaseWakeLock()

        fun wakeScreenWithFullScreenIntent()

        fun vibrate(patternMillis: LongArray)

        /** Plays on `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`. Blocks until finished. */
        fun playOnAlarmStream(pcm: ShortArray)
    }

    /** What actually happened, so the caller can log it and the test can assert it. */
    data class Outcome(
        val focusGranted: Boolean,
        val volumeRaisedFrom: Int,
        val volumeRestoredTo: Int,
        val repeats: Int,
        val failure: String? = null,
    ) {
        val succeeded: Boolean get() = failure == null
    }

    /**
     * Announces [pcm], twice, at full alarm volume, and puts the handset back exactly as
     * it was found.
     *
     * The ordering matters and is not arbitrary:
     *
     * 1. Wake lock **first** — everything after it is pointless if the device sleeps.
     * 2. Save the operator's alarm volume **before** changing it. This is the value that
     *    gets lost if anything below throws.
     * 3. Raise to maximum.
     * 4. Request exclusive focus — but proceed whether or not it is granted.
     * 5. Wake the screen and vibrate, so the alert is seen and felt as well as heard.
     * 6. Play twice; a single announcement in a noisy environment is missed.
     *
     * Restoration happens in a `finally`, because the failure mode otherwise is a
     * handset left permanently at maximum alarm volume — silent, self-inflicted, and
     * certain to be discovered mid-demonstration.
     */
    fun announce(
        pcm: ShortArray,
        repeats: Int = REPEATS,
    ): Outcome {
        val savedVolume = audio.currentAlarmVolume()
        var focusGranted = false
        var failure: String? = null

        audio.acquireWakeLock()
        try {
            audio.setAlarmVolume(audio.maxAlarmVolume())

            // W5.12: focus is requested, and a refusal is ignored. Another application
            // holding audio focus is exactly the situation an alert must override —
            // that is what "non-interruptible" means. Loss callbacks are likewise not
            // subscribed: there is no state in which this stops early.
            focusGranted = audio.requestExclusiveFocus()

            audio.wakeScreenWithFullScreenIntent()
            audio.vibrate(VIBRATION_PATTERN)

            repeat(repeats) { audio.playOnAlarmStream(pcm) }
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName ?: "unknown failure"
        } finally {
            // Both of these run even when playback threw. Leaving the handset at
            // maximum alarm volume, or holding a wake lock, are worse than the original
            // failure because they persist after it.
            runCatching { audio.setAlarmVolume(savedVolume) }
            if (focusGranted) runCatching { audio.abandonFocus() }
            runCatching { audio.releaseWakeLock() }
        }

        return Outcome(
            focusGranted = focusGranted,
            volumeRaisedFrom = savedVolume,
            volumeRestoredTo = audio.currentAlarmVolume(),
            repeats = repeats,
            failure = failure,
        )
    }

    companion object {
        /** Twice. A single announcement in a noisy environment is missed. */
        const val REPEATS = 2

        /**
         * Long pulses, distinct from any notification pattern a handset already uses.
         * An operator should be able to tell an alert from a message without looking.
         */
        val VIBRATION_PATTERN = longArrayOf(0, 600, 200, 600, 200, 600)
    }
}

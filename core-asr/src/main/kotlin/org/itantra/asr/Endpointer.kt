package org.itantra.asr

/**
 * Decides when an utterance has ended.
 *
 * The problem statement asks for recognition "activated after detecting pauses and
 * stoppages" and for the system to "form the sentences detected". This is that
 * mechanism. It is also the piece the production acoustic model cannot supply:
 * every Indic model available is offline and does not endpoint, so this runs off
 * the voice-activity tiers instead. See `docs/ASR.md` sections 2 and 3.5.
 *
 * ```
 *  IDLE --speech--> LISTENING ------------------> FINALISING --> IDLE
 *                     |    |                          |
 *                     |    +-- 8 s elapsed -----------+  force cut
 *                     +-- trailing silence ----------- +
 * ```
 *
 * Normative parameters, `docs/ASR.md` section 2:
 *
 * | Parameter                     | Value  | Why                                        |
 * |-------------------------------|--------|--------------------------------------------|
 * | Trailing silence, phone mode  | 400 ms | Shorter clips natural inter-clause pauses  |
 * | Trailing silence, PTT mode    | 150 ms | Key release is itself an end-of-utterance  |
 * | Maximum utterance             | 8 s    | Bounds latency; frees a half-duplex channel|
 * | Minimum utterance             | 300 ms | Suppresses coughs, clicks and key noise    |
 *
 * The silence window is the largest single component of end-to-end delay, and it is
 * a design choice rather than a computational cost — which is precisely why
 * push-to-talk is measurably faster than telephone mode.
 */
class Endpointer(
    private val mode: Mode,
    private val frameMillis: Int = 20,
    private val minUtteranceMillis: Int = 300,
    private val maxUtteranceMillis: Int = 8_000,
) {
    enum class Mode(val trailingSilenceMillis: Int) {
        /** Half duplex. The key release is an explicit end-of-utterance signal. */
        PUSH_TO_TALK(150),

        /** Full duplex. Silence is the only signal available. */
        TELEPHONE(400),
    }

    enum class State { IDLE, LISTENING, FINALISING }

    /** Why an utterance ended. Carried into `latency.csv` for the stage breakdown. */
    enum class EndReason {
        /** Trailing silence exceeded the window for the mode. */
        SILENCE,

        /** The eight-second cap fired, so one speaker cannot hold the channel. */
        MAX_DURATION,

        /** Push-to-talk key released. */
        KEY_RELEASE,
    }

    sealed interface Event {
        /** Speech began; the leading pad should be prepended to the utterance. */
        data object Started : Event

        data class Ended(
            val durationMillis: Int,
            val reason: EndReason,
        ) : Event

        /** Shorter than [minUtteranceMillis]; a cough, a click or key noise. */
        data class Discarded(val durationMillis: Int) : Event
    }

    var state: State = State.IDLE
        private set

    private var speechMillis = 0
    private var silenceMillis = 0

    /** Milliseconds of audio in the current utterance, pad excluded. */
    val utteranceMillis: Int get() = speechMillis

    /**
     * Feeds one frame of detector output.
     *
     * @param isSpeech whether the voice-activity tiers report speech for this frame.
     * @return an event when the state changed, otherwise null.
     */
    fun onFrame(isSpeech: Boolean): Event? {
        when (state) {
            State.IDLE -> {
                if (isSpeech) {
                    state = State.LISTENING
                    speechMillis = frameMillis
                    silenceMillis = 0
                    return Event.Started
                }
            }

            State.LISTENING -> {
                speechMillis += frameMillis
                silenceMillis = if (isSpeech) 0 else silenceMillis + frameMillis

                if (speechMillis >= maxUtteranceMillis) {
                    return finish(EndReason.MAX_DURATION)
                }
                if (silenceMillis >= mode.trailingSilenceMillis) {
                    return finish(EndReason.SILENCE)
                }
            }

            State.FINALISING -> Unit // the recogniser is draining; frames are buffered elsewhere
        }
        return null
    }

    /**
     * Push-to-talk key released. In [Mode.PUSH_TO_TALK] this is an explicit
     * end-of-utterance signal and ends the utterance immediately, which is what makes
     * push-to-talk faster than telephone mode.
     *
     * @return the resulting event, or null if nothing was in progress.
     */
    fun onKeyReleased(): Event? {
        if (state != State.LISTENING) return null
        return finish(EndReason.KEY_RELEASE)
    }

    /** The recogniser has finished with the utterance; return to idle. */
    fun onFinalised() {
        state = State.IDLE
        speechMillis = 0
        silenceMillis = 0
    }

    fun reset() = onFinalised()

    private fun finish(reason: EndReason): Event {
        // Trailing silence is not part of the utterance.
        val spoken =
            if (reason == EndReason.SILENCE) speechMillis - silenceMillis else speechMillis

        return if (spoken < minUtteranceMillis) {
            onFinalised()
            Event.Discarded(spoken)
        } else {
            state = State.FINALISING
            Event.Ended(spoken, reason)
        }
    }
}

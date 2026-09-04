package org.itantra.audio

/**
 * Who may be heard, and who may be captured, at any moment. Tasks **W5.5**, **W5.6**
 * and **W5.7**.
 *
 * ## Two modes, one policy object
 *
 * **Half duplex** is the radio behaviour: one unit talks, everyone else listens, and the
 * speaker is muted on the transmitting handset. Muting matters more than it sounds —
 * without it the handset's own speaker feeds its microphone, the energy gate never
 * closes, and the endpointer never fires. The operator's transmission simply never ends.
 *
 * **Full duplex** is the telephone behaviour: both directions stream continuously, gated
 * by voice activity. It is the more comfortable mode and the more expensive one, and it
 * is only usable where the two handsets are not in acoustic range of each other.
 *
 * ## Barge-in
 *
 * When the operator presses to talk while incoming audio is playing, playback ducks to
 * −18 dB within 100 ms and stops at the end of the current synthesis chunk.
 *
 * Ducking rather than cutting, and stopping at a chunk boundary rather than immediately,
 * are both deliberate: an abrupt stop mid-word sounds like a fault, and an operator who
 * hears a fault repeats themselves. The duck is instant enough that they can hear
 * themselves think; the chunk tail is under 200 ms.
 */
class DuplexPolicy(private var mode: Mode = Mode.HALF) {
    enum class Mode {
        /** One at a time. The speaker is muted while transmitting. */
        HALF,

        /** Both directions at once, voice-activity gated. */
        FULL,
    }

    /** What the audio stack should be doing right now. */
    data class Decision(
        val captureEnabled: Boolean,
        val playbackEnabled: Boolean,
        /** 1.0 is unattenuated; [DUCKED_GAIN] is −18 dB. */
        val playbackGain: Float,
        /** True when playback should stop once the current chunk finishes. */
        val stopPlaybackAtChunkEnd: Boolean = false,
    )

    private var transmitting = false
    private var playing = false

    val currentMode: Mode get() = mode

    fun setMode(next: Mode) {
        mode = next
    }

    fun onTransmitStart(): Decision {
        transmitting = true
        return decide(bargingIn = playing)
    }

    fun onTransmitEnd(): Decision {
        transmitting = false
        return decide()
    }

    fun onPlaybackStart(): Decision {
        playing = true
        return decide()
    }

    fun onPlaybackEnd(): Decision {
        playing = false
        return decide()
    }

    fun current(): Decision = decide(bargingIn = transmitting && playing)

    private fun decide(bargingIn: Boolean = false): Decision =
        when (mode) {
            Mode.HALF ->
                Decision(
                    captureEnabled = transmitting,
                    // Muted while transmitting. Without this the speaker feeds the
                    // microphone, the gate never closes, and the utterance never ends.
                    playbackEnabled = !transmitting && playing,
                    playbackGain = if (transmitting) 0f else 1f,
                    stopPlaybackAtChunkEnd = bargingIn,
                )
            Mode.FULL ->
                Decision(
                    // Capture runs continuously; the VAD decides what is speech.
                    captureEnabled = true,
                    playbackEnabled = playing,
                    // Ducked rather than cut, so the operator can hear themselves over
                    // the incoming voice without either being lost.
                    playbackGain = if (transmitting && playing) DUCKED_GAIN else 1f,
                    stopPlaybackAtChunkEnd = false,
                )
        }

    /**
     * The endpoint silence window for the current mode.
     *
     * Push-to-talk gets 150 ms because the key release is itself an endpoint signal —
     * the silence window is only there to catch the tail of the last word. Telephone
     * mode has no such signal and needs 400 ms to avoid cutting a speaker off mid-pause.
     */
    fun endpointMillis(): Int =
        when (mode) {
            Mode.HALF -> PUSH_TO_TALK_ENDPOINT_MILLIS
            Mode.FULL -> TELEPHONE_ENDPOINT_MILLIS
        }

    companion object {
        /** −18 dB as a linear gain: 10^(−18/20). */
        const val DUCKED_GAIN = 0.126f

        /** The duck must be audible before the operator has finished their first syllable. */
        const val DUCK_WITHIN_MILLIS = 100

        const val PUSH_TO_TALK_ENDPOINT_MILLIS = 150
        const val TELEPHONE_ENDPOINT_MILLIS = 400

        /** Converts a decibel attenuation to the linear gain `AudioTrack` wants. */
        fun gainForDecibels(db: Double): Float = Math.pow(10.0, db / 20.0).toFloat()
    }
}

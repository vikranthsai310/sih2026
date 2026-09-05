package org.itantra.app.platform

/**
 * Speech, turned into words, by a recogniser this project is allowed to use.
 *
 * ## Why this is an interface with no implementation
 *
 * There was one, and it had to go. It drove Android's [android.speech.SpeechRecognizer],
 * which on this handset is served by `com.google.android.as` — Google Speech Services.
 * `docs/REQUIREMENTS.md` constraint **C1** rules that out by name:
 *
 * > Open source only — ISRO prohibits "proprietary, closed-source, or commercial
 * > voice-activation SDKs". **Rules out Google Speech Services**, Azure Speech, Picovoice.
 *
 * It worked, and it was offline, and it was still the exact thing the problem statement
 * prohibits. Worse for the marking: the Accuracy criterion is 40 % of the score and is
 * measured as word error rate, so a jury measuring that build would have been measuring
 * Google's model rather than this project's.
 *
 * ## What replaces it
 *
 * [org.itantra.asr.SherpaRecogniser], over IndicConformer models fetched by
 * `tools/fetch_models.py`. That is the engine `docs/ARCHITECTURE.md` always specified; what
 * was missing was the model files, and the download source is now real rather than a
 * placeholder. Until a pack is on the handset there is **no recogniser**, the transmit
 * control sends a template, and the screen says so — which is the honest state rather than
 * a compliant-looking one.
 *
 * The interface stays so that swapping the implementation in is one class, and so that the
 * screen's listening states have a contract to be written against.
 */
interface Recogniser {
    /** What was heard, and how much of it should be believed. */
    data class Result(
        val text: String,
        /** The recogniser's own score, 0..1, where it offers one. */
        val confidence: Float?,
    )

    interface Listener {
        /**
         * The microphone is open and recording.
         *
         * Everything before this moment is not in the audio. The screen exists to make that
         * moment visible, because push-to-talk trains an operator to speak the instant
         * their thumb lands.
         */
        fun onReady()

        /** Microphone level, normalised to 0..1 for a meter. */
        fun onLevel(level: Float)

        /** The running hypothesis, for band C′. */
        fun onPartial(text: String)

        /** Exactly one of these two runs, exactly once, per [start]. */
        fun onResult(result: Result)

        fun onNothingHeard(reason: String)
    }

    /** Whether a press can produce words at all, for this language. */
    fun isReady(languageCode: String): Boolean

    /** @return false when there is nothing to listen with, so the caller can go to a template. */
    fun start(
        languageCode: String,
        listener: Listener,
    ): Boolean

    /** The operator let go. Push-to-talk decides where the sentence ends. */
    fun stop()

    /** Release the microphone and any model held open. */
    fun close()
}

package org.itantra.app.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The microphone, turned into words, entirely on the handset.
 *
 * ## Why this is not sherpa-onnx
 *
 * `ARCHITECTURE.md` specifies IndicConformer through sherpa-onnx, and that remains the
 * intended engine — the AAR is in `libs/` and [org.itantra.asr.SherpaRecogniser] is written
 * against it. What is missing is the acoustic models: `models/` contains no `.onnx` file,
 * every `sha256` in `models/manifest.json` is sixty-four zeroes, and `tools/fetch_models.py`
 * refuses to run because *"the artefacts are not published yet"*. There is nowhere to fetch
 * them from, so that path cannot recognise a single word today.
 *
 * This class is the honest substitute: Android's own recogniser, asked to stay **on the
 * device**. It needs no network once its language pack is installed, which keeps the offline
 * claim intact, and it is a system service rather than this project's model — which is why
 * [Result.onDevice] is reported rather than assumed, and why the operating screen says which
 * of the two paths produced each message instead of letting them look alike.
 *
 * ## What it is not allowed to do
 *
 * Fall back to a network recogniser. [EXTRA_PREFER_OFFLINE] is a preference rather than a
 * guarantee on the general recogniser, so on Android 12 and later the on-device recogniser
 * is created explicitly and the general one is used only where that API does not exist. A
 * message that quietly went to a server would break the one claim this project is built on.
 *
 * ## Threading
 *
 * [SpeechRecognizer] must be created and driven from the main looper, and every callback
 * arrives there. Callers are on the activity's scope, which is the main dispatcher.
 */
class SpeechInput(private val context: Context) {
    /** What the recogniser heard, and how much of it should be believed. */
    data class Result(
        val text: String,
        /** Google's own score, 0..1, when it supplies one. Frequently absent. */
        val confidence: Float?,
        /** False if the platform could only offer a networked recogniser. */
        val onDevice: Boolean,
    )

    interface Listener {
        /** Microphone level, already normalised to 0..1 for a meter. */
        fun onLevel(level: Float)

        /** The running hypothesis, for band C′. */
        fun onPartial(text: String)

        /** Exactly one of these two runs, exactly once, per [start]. */
        fun onResult(result: Result)

        fun onNothingHeard(reason: String)
    }

    private var recogniser: SpeechRecognizer? = null
    private var listener: Listener? = null

    /** Guards the contract that one call to [start] produces one terminal callback. */
    private var settled = true

    private val onDevicePreferred: Boolean
        get() =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)

    /** Whether pressing transmit can produce words at all. Drives the screen's wording. */
    val available: Boolean
        get() =
            onDevicePreferred ||
                runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Opens the microphone.
     *
     * @return false when there is no recogniser at all, so the caller can go straight to the
     *   template rather than waiting for a result that is never coming.
     */
    fun start(
        languageTag: String,
        listener: Listener,
    ): Boolean {
        if (!available) return false
        cancel()

        this.listener = listener
        settled = false

        val onDevice = onDevicePreferred
        val engine =
            runCatching {
                if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }.getOrNull() ?: return false

        recogniser = engine
        engine.setRecognitionListener(callbacks(onDevice))

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // A preference on the general recogniser and redundant on the on-device one.
                // Set in both cases: it costs nothing and it is the flag that stops a
                // networked fallback where one is possible at all.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

        return runCatching { engine.startListening(intent) }.isSuccess
    }

    /**
     * Ends the utterance and asks for the final transcription.
     *
     * Push-to-talk decides where the sentence ends — the operator let go — so this is called
     * on release rather than waiting for the recogniser's own endpointer to notice a pause.
     */
    fun stop() {
        runCatching { recogniser?.stopListening() }
    }

    fun cancel() {
        settled = true
        listener = null
        runCatching { recogniser?.cancel() }
        runCatching { recogniser?.destroy() }
        recogniser = null
    }

    private fun settle(action: (Listener) -> Unit) {
        if (settled) return
        settled = true
        listener?.let(action)
    }

    private fun callbacks(onDevice: Boolean) =
        object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) {
                // The documented range is roughly -2..10 dB. Normalised here rather than in
                // the screen, so the meter is not calibrated against one vendor's scale.
                listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstOf(partialResults)?.let { listener?.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstOf(results)
                val score =
                    results
                        ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        ?.firstOrNull()
                        ?.takeIf { it > 0f }
                if (text.isNullOrBlank()) {
                    settle { it.onNothingHeard("nothing recognised") }
                } else {
                    settle { it.onResult(Result(text, score, onDevice)) }
                }
            }

            override fun onError(error: Int) = settle { it.onNothingHeard(describe(error)) }

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private fun firstOf(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The recogniser wants a full locale, and the two that matter are the ones a bare
         * language code gets wrong: `hi` alone is ambiguous, and the Indic packs are all
         * published against the Indian region.
         */
        fun tagFor(code: String): String = "$code-IN"

        /**
         * Errors, said in a way an operator can act on.
         *
         * Only two of these are the operator's problem — silence, and a language pack that
         * is not installed — so the rest collapse into one sentence rather than exposing a
         * platform constant on a screen used on an embankment.
         */
        private fun describe(error: Int): String =
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "nothing recognised"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
                SpeechRecognizer.ERROR_AUDIO -> "microphone unavailable"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone not permitted"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                -> "language pack not installed"

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> "the recogniser wanted the network"

                else -> "recogniser unavailable"
            }
    }
}

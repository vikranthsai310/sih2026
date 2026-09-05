package org.itantra.app.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
        /**
         * The microphone is now open and recording.
         *
         * Everything before this moment is **not in the audio**. The screen exists to make
         * that moment visible, because push-to-talk trains an operator to speak the instant
         * their thumb lands.
         */
        fun onReady()

        /** Microphone level, already normalised to 0..1 for a meter. */
        fun onLevel(level: Float)

        /** The running hypothesis, for band C′. */
        fun onPartial(text: String)

        /** Exactly one of these two runs, exactly once, per [start]. */
        fun onResult(result: Result)

        fun onNothingHeard(reason: String)
    }

    /** Whether a language can be recognised on this handset, right now. */
    enum class Pack {
        /** The on-device model is present. Holding transmit will produce words. */
        INSTALLED,

        /** Not present, but the platform says it can fetch it. A download has been asked for. */
        DOWNLOADING,

        /** The platform has no on-device model for this language, and will not get one. */
        UNAVAILABLE,

        /** Not asked yet, or this Android is too old to be asked. */
        UNKNOWN,
    }

    private val packs = ConcurrentHashMap<String, Pack>()

    private var recogniser: SpeechRecognizer? = null
    private var listener: Listener? = null

    /** Guards the contract that one call to [start] produces one terminal callback. */
    private var settled = true

    private val main = Handler(Looper.getMainLooper())

    /** A stop deferred until the microphone has actually been open for a moment. */
    private var pendingStop: Runnable? = null

    /** `elapsedRealtime` of [RecognitionListener.onReadyForSpeech]; zero until it arrives. */
    private var readyAt = 0L

    /** The operator has let go, whether or not the microphone had opened by then. */
    private var stopWanted = false

    /** Read at [start] and reported with the result, since the engine outlives the session. */
    private var sessionOnDevice = false

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

    /** What is known about [languageTag] without asking the platform again. */
    fun packStateFor(languageTag: String): Pack = packs[languageTag] ?: Pack.UNKNOWN

    /**
     * Asks whether this language can be recognised on the device, and downloads it if not.
     *
     * This is the fix for the commonest failure on a real handset: Hindi produced
     * "language pack not installed" and there was nothing the operator could do about it
     * from inside the application. `triggerModelDownload` is the platform's own answer, and
     * it exists precisely so an application does not have to send people into Settings.
     *
     * Below Android 13 neither API exists, so the state stays [Pack.UNKNOWN] and a press
     * simply finds out the hard way. That is honest: the handset cannot be asked.
     *
     * @param onChange called on the main looper whenever the answer changes.
     */
    fun ensurePack(
        languageTag: String,
        onChange: (Pack) -> Unit = { },
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !available) {
            packs[languageTag] = Pack.UNKNOWN
            onChange(Pack.UNKNOWN)
            return
        }
        val engine =
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
                ?: run {
                    packs[languageTag] = Pack.UNKNOWN
                    onChange(Pack.UNKNOWN)
                    return
                }

        val intent = recogniseIntent(languageTag)
        runCatching {
            engine.checkRecognitionSupport(
                intent,
                Executors.newSingleThreadExecutor(),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed =
                            support.installedOnDeviceLanguages.any { it.equals(languageTag, true) }
                        val gettable =
                            support.supportedOnDeviceLanguages.any { it.equals(languageTag, true) }
                        val state =
                            when {
                                installed -> Pack.INSTALLED
                                gettable -> {
                                    // The download is the platform's, and it continues after
                                    // this object is destroyed.
                                    runCatching { engine.triggerModelDownload(intent) }
                                    Pack.DOWNLOADING
                                }

                                else -> Pack.UNAVAILABLE
                            }
                        packs[languageTag] = state
                        onChange(state)
                        runCatching { engine.destroy() }
                    }

                    override fun onError(error: Int) {
                        packs[languageTag] = Pack.UNKNOWN
                        onChange(Pack.UNKNOWN)
                        runCatching { engine.destroy() }
                    }
                },
            )
        }.onFailure {
            packs[languageTag] = Pack.UNKNOWN
            onChange(Pack.UNKNOWN)
            runCatching { engine.destroy() }
        }
    }

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
        val engine = warmUp() ?: return false

        // A session still in flight is *settled*, not destroyed. Tearing the recogniser
        // down here threw away the previous utterance's result, so pressing twice in quick
        // succession silently turned the first one into a template.
        settle { it.onNothingHeard("superseded by the next press") }
        cancelPendingStop()
        runCatching { engine.cancel() }

        this.listener = listener
        settled = false
        readyAt = 0L
        stopWanted = false
        sessionOnDevice = onDevicePreferred

        return runCatching { engine.startListening(recogniseIntent(languageTag)) }.isSuccess
    }

    /**
     * Builds the recogniser ahead of the first press, and keeps it.
     *
     * This is the main cause of "sometimes spoken, sometimes template". A recogniser was
     * created and destroyed on **every** press, and binding to the system recognition
     * service is not instant — so the microphone often opened after the operator had already
     * started speaking, and a short utterance could be missed in its entirety. Whether it
     * worked depended on whether the service happened to be warm, which is exactly the
     * intermittency reported.
     *
     * One instance, built when the screen appears and reused for every press.
     */
    fun warmUp(): SpeechRecognizer? {
        recogniser?.let { return it }
        if (!available) return null
        val created =
            runCatching {
                if (onDevicePreferred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }.getOrNull() ?: return null
        created.setRecognitionListener(callbacks())
        recogniser = created
        return created
    }

    /**
     * One builder for both callers.
     *
     * `checkRecognitionSupport` answers about the request it is given, so a check that
     * differs in any extra from the request actually made is answering a different question.
     */
    private fun recogniseIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // A preference on the general recogniser and redundant on the on-device one.
            // Set in both cases: it costs nothing and it is the flag that stops a networked
            // fallback where one is possible at all.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Hints, honoured by some engines and ignored by others. On push-to-talk the
            // operator decides where the sentence ends, so the recogniser is asked not to
            // endpoint on an ordinary mid-sentence pause and hand back half a message.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                2_000L,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
        }

    /**
     * Ends the utterance and asks for the final transcription.
     *
     * Push-to-talk decides where the sentence ends — the operator let go — so this is called
     * on release rather than waiting for the recogniser's own endpointer to notice a pause.
     */
    fun stop() {
        stopWanted = true
        if (recogniser == null) return
        if (readyAt == 0L) {
            // The microphone has not opened yet. Stopping now would close a session that
            // never recorded a sample, which is a guaranteed "nothing recognised".
            // onReadyForSpeech honours stopWanted when it arrives.
            return
        }
        scheduleStop(MIN_LISTEN_MILLIS - (SystemClock.elapsedRealtime() - readyAt))
    }

    /**
     * Stops after the microphone has been open for [MIN_LISTEN_MILLIS], not before.
     *
     * A press and release is quick — quicker than a person expects — and a recogniser given
     * eighty milliseconds of audio returns nothing every time. The floor costs an operator
     * who taps nothing they would notice, and it is the difference between a tap that sends
     * a word and a tap that sends a template.
     */
    private fun scheduleStop(delayMillis: Long) {
        cancelPendingStop()
        val stopping = Runnable { runCatching { recogniser?.stopListening() } }
        pendingStop = stopping
        main.postDelayed(stopping, delayMillis.coerceIn(0, MIN_LISTEN_MILLIS))
    }

    private fun cancelPendingStop() {
        pendingStop?.let { main.removeCallbacks(it) }
        pendingStop = null
    }

    /** Ends the session and releases the recogniser. For teardown, not between presses. */
    fun close() {
        cancelPendingStop()
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

    private fun callbacks() =
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
                    settle { it.onResult(Result(text, score, sessionOnDevice)) }
                }
            }

            override fun onError(error: Int) = settle { it.onNothingHeard(describe(error)) }

            override fun onReadyForSpeech(params: Bundle?) {
                readyAt = SystemClock.elapsedRealtime()
                listener?.onReady()
                // Released before the microphone opened. The press still gets its floor of
                // recording time rather than being thrown away.
                if (stopWanted) scheduleStop(MIN_LISTEN_MILLIS)
            }

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
         * The shortest the microphone stays open once it has opened.
         *
         * Long enough for one word, short enough that it never feels like a delay.
         */
        const val MIN_LISTEN_MILLIS = 900L

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

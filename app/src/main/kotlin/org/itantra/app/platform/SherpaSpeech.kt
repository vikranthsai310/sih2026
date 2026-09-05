package org.itantra.app.platform

import org.itantra.asr.SherpaRecogniser
import org.itantra.audio.AudioCapture
import org.itantra.proto.Language
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Speech into words, on the handset, with nothing proprietary in the path.
 *
 * This is the implementation [Recogniser] was left waiting for. `AudioCapture` opens the
 * microphone, [SherpaRecogniser] runs AI4Bharat's IndicConformer through ONNX Runtime, and
 * both are Apache-2.0 — which is what `docs/REQUIREMENTS.md` constraint **C1** requires and
 * what Android's own recogniser could not offer, being Google Speech Services.
 *
 * ## Decoded on release, not while speaking
 *
 * IndicConformer is an **offline** model: `OfflineRecognizer` takes a complete buffer and
 * returns one transcription, and there is no streaming export for these ten languages. So
 * audio accumulates while the control is held and is decoded when it is let go. That is
 * push-to-talk's own shape — the operator has already told us where the sentence ends — but
 * it does put the whole decode after the release, which is what
 * [org.itantra.asr.SlidingWindowDecoder] exists to recover and is not wired in yet.
 *
 * ## What is bounded, and why
 *
 * The buffer is capped at [MAX_SECONDS]. A control held down in a pocket would otherwise
 * grow it without limit, and the failure would be an out-of-memory kill rather than a long
 * message. A cap is a truncated sentence, which the operator can see and repeat.
 */
class SherpaSpeech(
    private val store: ModelStore,
    private val capture: AudioCapture = AudioCapture(),
) : Recogniser {
    /** Model loading and decoding, both far too slow for the main thread. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa").apply { isDaemon = true } }

    private var recogniser: SherpaRecogniser? = null
    private var loadedFor: String? = null

    private var listener: Recogniser.Listener? = null
    private val settled = AtomicBoolean(true)

    /** Guarded by itself: appended on the capture thread, read on the worker. */
    private val buffer = ArrayList<Short>(SAMPLE_RATE * 4)

    override fun isReady(languageCode: String): Boolean = store.hasPack(languageCode)

    /**
     * Loads a language's model, off the main thread.
     *
     * A 189 MB graph takes seconds to memory-map and initialise, and doing it on the first
     * press is how an operator loses their first sentence. Called when the language is
     * chosen so the cost is paid while nobody is talking.
     */
    fun preload(languageCode: String) {
        if (!isReady(languageCode) || loadedFor == languageCode) return
        worker.execute { load(languageCode) }
    }

    private fun load(languageCode: String): SherpaRecogniser? {
        if (loadedFor == languageCode) return recogniser
        // One model resident at a time. Two of these is 378 MB of native heap on a handset
        // the problem statement says is entry-tier.
        recogniser?.let { runCatching { it.close() } }
        recogniser = null
        loadedFor = null

        val language = Language.entries.firstOrNull { it.code == languageCode } ?: return null
        val built =
            runCatching {
                SherpaRecogniser(
                    modelPath = store.modelFor(languageCode).absolutePath,
                    tokensPath = store.tokens.absolutePath,
                    language = language,
                )
            }.getOrNull() ?: return null

        recogniser = built
        loadedFor = languageCode
        return built
    }

    override fun start(
        languageCode: String,
        listener: Recogniser.Listener,
    ): Boolean {
        if (!isReady(languageCode)) return false
        this.listener = listener
        settled.set(false)
        synchronized(buffer) { buffer.clear() }

        val started =
            capture.start(
                onHop = { samples, count -> onHop(samples, count) },
                onError = { settle { it.onNothingHeard("the microphone is not available") } },
            )
        if (!started) {
            settle { it.onNothingHeard("the microphone is not available") }
            return false
        }

        // The model is loaded on the worker while the microphone is already recording, so
        // the two costs overlap instead of adding up.
        worker.execute { load(languageCode) }

        // AudioCapture.start returning true means AudioRecord is recording, so the floor is
        // live from here. The screen stops saying "opening the microphone" now.
        listener.onReady()
        return true
    }

    private fun onHop(
        samples: ShortArray,
        count: Int,
    ) {
        var sum = 0.0
        synchronized(buffer) {
            val room = MAX_SAMPLES - buffer.size
            val take = min(count, room)
            for (i in 0 until take) buffer.add(samples[i])
        }
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        // Root mean square, mapped so ordinary speech fills most of the meter. Not decibels:
        // the meter is read at arm's length by someone mid-sentence, not measured.
        val rms = sqrt(sum / count.coerceAtLeast(1))
        listener?.onLevel((rms * METER_GAIN).coerceIn(0.0, 1.0).toFloat())
    }

    override fun stop() {
        capture.stop()
        val pcm =
            synchronized(buffer) {
                ShortArray(buffer.size) { buffer[it] }.also { buffer.clear() }
            }
        worker.execute {
            val engine = recogniser
            when {
                engine == null -> settle { it.onNothingHeard("the model is still loading") }
                pcm.size < MIN_SAMPLES ->
                    settle { it.onNothingHeard("too short to recognise") }

                else -> {
                    val text = runCatching { engine.decode(pcm) }.getOrNull()
                    if (text.isNullOrBlank()) {
                        settle { it.onNothingHeard("nothing recognised") }
                    } else {
                        // IndicConformer gives no per-utterance score, so none is claimed.
                        // A confidence invented here would be read as the model's.
                        settle { it.onResult(Recogniser.Result(text, confidence = null)) }
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { capture.stop() }
        worker.execute {
            recogniser?.let { runCatching { it.close() } }
            recogniser = null
            loadedFor = null
        }
        worker.shutdown()
    }

    /** One terminal callback per [start], whichever path gets there first. */
    private fun settle(action: (Recogniser.Listener) -> Unit) {
        if (settled.compareAndSet(false, true)) listener?.let(action)
    }

    private companion object {
        const val SAMPLE_RATE = SherpaRecogniser.SAMPLE_RATE

        /** A held control in a pocket must truncate a sentence, not exhaust the heap. */
        const val MAX_SECONDS = 30
        const val MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS

        /** Below this there is nothing to decode; 200 ms is shorter than any word. */
        const val MIN_SAMPLES = SAMPLE_RATE / 5

        /** Speech sits near 0.1 RMS, so this puts an ordinary voice around two thirds. */
        const val METER_GAIN = 6.0
    }
}

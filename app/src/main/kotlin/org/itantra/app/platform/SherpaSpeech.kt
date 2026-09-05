package org.itantra.app.platform

import org.itantra.asr.SherpaRecogniser
import org.itantra.asr.SlidingWindowDecoder
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
 * ## Decoded while the operator is still speaking
 *
 * IndicConformer is an **offline** model: `OfflineRecognizer` takes a complete buffer and
 * returns one transcription, and no streaming export exists for these ten languages. Decoded
 * naively that puts the entire pass after the release — measured at **4 116 ms** on a
 * three-second utterance, against a target of 800–1 200 ms, on a criterion worth 20 % of
 * the mark.
 *
 * [SlidingWindowDecoder] is the answer and was written in week 3 with no caller. Windows are
 * decoded as they fill, on the worker, while the microphone is still recording; releasing
 * the control leaves only the last partial window to pay for. It costs roughly 1.6× the
 * compute of a single pass, spent entirely while somebody is talking.
 *
 * Each completed window also gives the operator running text in band C′ — which is not a
 * side effect worth losing, since it is the only chance to notice a misrecognition before
 * it goes out.
 *
 * ## What is bounded, and why
 *
 * The pending buffer is capped at [MAX_SECONDS]. A control held down in a pocket, or a model
 * that never finishes loading, would otherwise grow it without limit and the failure would
 * be an out-of-memory kill rather than a long message. A cap truncates a sentence, which the
 * operator can see and repeat.
 */
class SherpaSpeech(
    private val store: ModelStore,
    private val capture: AudioCapture = AudioCapture(),
) : Recogniser {
    /** Model loading and decoding, both far too slow for the main thread. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa").apply { isDaemon = true } }

    private var recogniser: SherpaRecogniser? = null
    private var windows: SlidingWindowDecoder? = null
    private var loadedFor: String? = null

    private var listener: Recogniser.Listener? = null
    private val settled = AtomicBoolean(true)

    /** Appended on the capture thread, drained on the worker. */
    private val pending = ArrayList<Short>(SAMPLE_RATE * 2)

    /** One drain in flight at a time; hops that arrive meanwhile join the next one. */
    private val draining = AtomicBoolean(false)

    /** Window texts so far, for band C′ only. The final stitch is the decoder's. */
    private val partials = ArrayList<String>()

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
        windows = null
        loadedFor = null

        val language = Language.entries.firstOrNull { it.code == languageCode } ?: return null
        val tokens = store.tokensFor(languageCode)
        if (!tableSuits(tokens, language)) return null
        val built =
            runCatching {
                SherpaRecogniser(
                    modelPath = store.modelFor(languageCode).absolutePath,
                    tokensPath = tokens.absolutePath,
                    language = language,
                )
            }.getOrNull() ?: return null

        recogniser = built
        windows = built.windowedDecoder()
        loadedFor = languageCode
        return built
    }

    /**
     * Whether a token table is the one this language's model was trained against.
     *
     * Eight of the nine models share an Indic table; English has its own in Latin. Give
     * either model the other's table and sherpa-onnx does not complain — it loads, decodes,
     * and emits nonsense, and the conclusion drawn is that the model is bad. Checked once
     * per load rather than on every readiness poll, because it reads the file.
     *
     * The test is the script itself rather than a list of which languages have their own
     * table, so a language that later ships one needs no change here.
     */
    private fun tableSuits(
        tokens: java.io.File,
        language: Language,
    ): Boolean {
        val sample = runCatching { tokens.readText() }.getOrNull() ?: return false
        val base = language.blockBase
        return if (base == null) {
            // English. A table dense with Devanagari is not the Latin one it needs.
            sample.count { it.code in 0x41..0x7A } > sample.count { it.code >= 0x0900 }
        } else {
            sample.any { it.code in base until base + SCRIPT_BLOCK }
        }
    }

    override fun start(
        languageCode: String,
        listener: Recogniser.Listener,
    ): Boolean {
        if (!isReady(languageCode)) return false
        this.listener = listener
        settled.set(false)
        synchronized(pending) { pending.clear() }
        partials.clear()

        val started =
            capture.start(
                onHop = ::onHop,
                onError = { settle { it.onNothingHeard("the microphone is not available") } },
            )
        if (!started) {
            settle { it.onNothingHeard("the microphone is not available") }
            return false
        }

        // The model is loaded on the worker while the microphone is already recording, so
        // the two costs overlap instead of adding up. windows.reset() runs on the same
        // thread as every decode, so it cannot race one.
        worker.execute {
            load(languageCode)
            windows?.reset()
        }

        // AudioCapture.start returning true means AudioRecord is recording, so the floor is
        // live from here. The screen stops saying "opening the microphone" now.
        listener.onReady()
        return true
    }

    private fun onHop(
        samples: ShortArray,
        count: Int,
    ) {
        synchronized(pending) {
            val room = MAX_SAMPLES - pending.size
            for (i in 0 until min(count, room)) pending.add(samples[i])
        }

        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        // Root mean square, mapped so ordinary speech fills most of the meter. Not decibels:
        // the meter is read at arm's length by someone mid-sentence, not measured.
        val rms = sqrt(sum / count.coerceAtLeast(1))
        listener?.onLevel((rms * METER_GAIN).coerceIn(0.0, 1.0).toFloat())

        scheduleDrain()
    }

    /**
     * Decodes whatever has accumulated, once at a time.
     *
     * Hops arrive every 20 ms and a window takes several hundred milliseconds to decode, so
     * posting one task per hop would queue thousands of them behind the first. The flag
     * collapses that: while a drain runs, arriving audio simply lands in [pending], and
     * `onSpeech` loops through every window it completes.
     */
    private fun scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return
        worker.execute {
            try {
                drainInto(windows ?: return@execute)
            } finally {
                draining.set(false)
            }
        }
    }

    private fun drainInto(decoder: SlidingWindowDecoder) {
        val block = takePending()
        if (block.isEmpty()) return
        val texts = runCatching { decoder.onSpeech(block) }.getOrDefault(emptyList())
        if (texts.isEmpty()) return
        partials += texts
        // Running text while the operator is still speaking: rule 6, and their only chance
        // to notice a misrecognition before it goes out.
        val soFar = SlidingWindowDecoder.stitch(partials)
        if (soFar.isNotEmpty()) listener?.onPartial(soFar)
    }

    private fun takePending(): ShortArray =
        synchronized(pending) {
            if (pending.isEmpty()) {
                ShortArray(0)
            } else {
                ShortArray(pending.size) { pending[it] }.also { pending.clear() }
            }
        }

    override fun stop() {
        capture.stop()
        worker.execute {
            val decoder = windows
            when {
                decoder == null -> settle { it.onNothingHeard("the model is still loading") }
                else -> {
                    // Anything captured since the last drain, then the tail. This is the
                    // only decode that costs latency, and it covers at most one window.
                    drainInto(decoder)
                    val text = runCatching { decoder.onEndpoint() }.getOrNull()
                    partials.clear()
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
            windows = null
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

        /** A Unicode script block is 128 code points, which is how Language.blockBase is defined. */
        const val SCRIPT_BLOCK = 0x80

        /** Speech sits near 0.1 RMS, so this puts an ordinary voice around two thirds. */
        const val METER_GAIN = 6.0
    }
}

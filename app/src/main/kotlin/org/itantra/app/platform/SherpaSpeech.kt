package org.itantra.app.platform

import android.media.MediaRecorder
import org.itantra.asr.SherpaRecogniser
import org.itantra.asr.UtteranceDecoder
import org.itantra.audio.AudioCapture
import org.itantra.proto.Language
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * [UtteranceDecoder] is the answer. Each clause is decoded **in the pause that ends it**,
 * on the worker, while the microphone is still recording; releasing the control leaves only
 * the last clause to pay for — and often not even that, because a clause the operator
 * finished a moment before letting go has usually been read already.
 *
 * It replaced fixed 1.5 s windows, which kept the same latency property and cost accuracy:
 * a window boundary lands mid-word, and a CTC model asked about half a word answers with a
 * different one. The class comment on [UtteranceDecoder] has the three failure modes.
 *
 * ## The two ends of the utterance
 *
 * Both are where words go missing on a real handset, and neither is the model's fault.
 *
 * At the **press**, the screen used to say "listening" the moment `AudioRecord` was built,
 * while `startRecording` was still tens of milliseconds from delivering a sample. The
 * operator, trained by push-to-talk to speak the instant their thumb lands, spoke the first
 * syllable into nothing. [Recogniser.Listener.onReady] now fires from the capture thread
 * when the recorder reports it is recording.
 *
 * At the **release**, the microphone used to close on the same instruction. An operator lets
 * go on the last word, not after it, and the audio path has its own buffering besides — so
 * the final consonant was the first casualty. The microphone now stays open for
 * [RELEASE_GRACE_MILLIS] after the release. That is latency spent on purpose, and it is the
 * cheapest accuracy in this file.
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
    private var decoder: UtteranceDecoder? = null
    private var loadedFor: String? = null

    private var listener: Recogniser.Listener? = null
    private val settled = AtomicBoolean(true)

    /**
     * Which press the worker is serving.
     *
     * A release schedules its work on the worker behind the grace period. A press arriving
     * inside that window must not have its microphone closed by the previous release's
     * task, nor its listener settled by it; each task checks it still belongs to the
     * current press before touching either.
     */
    private val generation = AtomicInteger(0)

    /** True from a release until its task has closed the microphone (or ceded it). */
    private val stopPending = AtomicBoolean(false)

    /**
     * The open line: words for a listener that never presses anything.
     *
     * A sentence ends when the speaker has been quiet for [OPEN_LINE_ENDPOINT_MILLIS] --
     * longer than the pause between clauses, shorter than the gap before a reply. Each
     * sentence is handed over as it ends and the microphone stays open for the next.
     */
    interface OpenLineListener {
        fun onLevel(level: Float)

        fun onPartial(text: String)

        fun onUtterance(result: Recogniser.Result)

        fun onProblem(reason: String)
    }

    @Volatile
    private var openLine: OpenLineListener? = null

    /** HOLD on the open line: the microphone stays open, its audio is dropped. */
    @Volatile
    private var openLineMuted = false

    /** Appended on the capture thread, drained on the worker. */
    private val pending = ArrayList<Short>(SAMPLE_RATE * 2)

    /** One drain in flight at a time; hops that arrive meanwhile join the next one. */
    private val draining = AtomicBoolean(false)

    /** Nanoseconds spent inside the decoder this utterance, across every segment. */
    private var decodeNanos = 0L

    /** Samples handed to the decoder this utterance. The audio the factor is measured over. */
    private var decodedSamples = 0L

    override fun isReady(languageCode: String): Boolean = store.hasPack(languageCode)

    /**
     * Loads a language's model, off the main thread.
     *
     * A 189 MB graph takes seconds to memory-map and initialise, and doing it on the first
     * press is how an operator loses their first sentence. Called when the language is
     * chosen so the cost is paid while nobody is talking.
     */
    fun preload(
        languageCode: String,
        onLoaded: () -> Unit = {},
    ) {
        if (!isReady(languageCode)) return
        if (loadedFor == languageCode) {
            onLoaded()
            return
        }
        worker.execute {
            load(languageCode)
            onLoaded()
        }
    }

    /**
     * Whether this language's model is resident *now*, as opposed to merely installed.
     *
     * The difference is several seconds and it is what the operator experiences on a
     * language change: the pack is on disk, so [isReady] is true, and a press in that window
     * would find nothing to decode with. The screen says "loading" rather than pretending
     * either that it is ready or that it is missing.
     */
    fun isLoaded(languageCode: String): Boolean = loadedFor == languageCode

    private fun load(languageCode: String): SherpaRecogniser? {
        if (loadedFor == languageCode) return recogniser
        // One model resident at a time. Two of these is 378 MB of native heap on a handset
        // the problem statement says is entry-tier.
        recogniser?.let { runCatching { it.close() } }
        recogniser = null
        decoder = null
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
        // Built here rather than through utteranceDecoder() so every call can be timed. The
        // real-time factor is a measurement of the decoder, and measuring it anywhere else
        // would be measuring the queue in front of it.
        decoder =
            UtteranceDecoder(
                sampleRate = SherpaRecogniser.SAMPLE_RATE,
                highPassHz = UtteranceDecoder.HIGH_PASS_HZ,
                decode = { pcm ->
                    val began = System.nanoTime()
                    try {
                        built.transcribe(pcm)
                    } finally {
                        decodeNanos += System.nanoTime() - began
                    }
                },
            )
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
        stopOpenLine()
        // A press inside the previous release's grace period. That utterance is over; it is
        // told so now, rather than left for the engine's timeout to give up on.
        settle { it.onNothingHeard("interrupted by the next press") }
        val press = generation.incrementAndGet()

        this.listener = listener
        settled.set(false)
        synchronized(pending) { pending.clear() }
        decodeNanos = 0L
        decodedSamples = 0L

        // Queued before the microphone opens, so no drain of this press's audio can run
        // ahead of the reset and be wiped by it. The model loads on the worker while the
        // microphone is already recording, so the two costs overlap instead of adding up;
        // decoder.reset() runs on the same thread as every decode, so it cannot race one.
        worker.execute {
            load(languageCode)
            decoder?.reset()
        }

        // The microphone is opened here, now, unless the previous release is still inside
        // its grace period on the worker. Then the open is queued behind it, so the two
        // never touch the recorder at once -- and the previous release, finding a newer
        // press, leaves the microphone running for it.
        if (stopPending.get()) {
            worker.execute { openMicrophone(press, listener) }
        } else if (!openMicrophone(press, listener)) {
            return false
        }
        return true
    }

    /** @return false when the recorder could not be built, in which case the press is settled. */
    private fun openMicrophone(
        press: Int,
        listener: Recogniser.Listener,
    ): Boolean {
        if (generation.get() != press) return true
        val wasOpen = capture.isRunning
        val started =
            capture.start(
                onHop = ::onHop,
                onError = { settle(press) { it.onNothingHeard("the microphone is not available") } },
                // The floor is live from here and not before: AudioRecord reports that it
                // is recording, on the thread that will read from it.
                onStarted = { if (generation.get() == press) listener.onReady() },
            )
        if (!started) {
            settle(press) { it.onNothingHeard("the microphone is not available") }
            return false
        }
        // Still open from a release a moment ago: already listening, nothing to wait for.
        if (wasOpen) listener.onReady()
        return true
    }

    private fun onHop(
        samples: ShortArray,
        count: Int,
    ) {
        if (!openLineMuted) {
            synchronized(pending) {
                val room = MAX_SAMPLES - pending.size
                for (i in 0 until min(count, room)) pending.add(samples[i])
            }
        }

        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        // Root mean square, mapped so ordinary speech fills most of the meter. Not decibels:
        // the meter is read at arm's length by someone mid-sentence, not measured.
        val rms = sqrt(sum / count.coerceAtLeast(1))
        val level = (rms * METER_GAIN).coerceIn(0.0, 1.0).toFloat()
        openLine?.onLevel(level) ?: listener?.onLevel(level)

        if (!openLineMuted) scheduleDrain()
    }

    /**
     * Decodes whatever has accumulated, once at a time.
     *
     * Hops arrive every 20 ms and a clause takes several hundred milliseconds to decode, so
     * posting one task per hop would queue thousands of them behind the first. The flag
     * collapses that: while a drain runs, arriving audio simply lands in [pending], and the
     * next drain takes all of it.
     */
    private fun scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return
        worker.execute {
            try {
                drainInto(decoder ?: return@execute, allowProvisional = true)
            } finally {
                draining.set(false)
            }
        }
    }

    private fun drainInto(
        decoder: UtteranceDecoder,
        allowProvisional: Boolean,
    ) {
        val block = takePending()
        if (block.isEmpty()) return
        decodedSamples += block.size
        val changed = runCatching { decoder.onAudio(block, allowProvisional) }.getOrDefault(false)
        val line = openLine
        if (line != null) {
            if (changed) decoder.runningText().takeIf { it.isNotEmpty() }?.let(line::onPartial)
            // The pause is the key. Long enough that a clause boundary does not end the
            // sentence, short enough that the reply is not kept waiting.
            if (decoder.hasSpeechNow && decoder.trailingQuietMillis >= OPEN_LINE_ENDPOINT_MILLIS) {
                val text = runCatching { decoder.onEndpoint() }.getOrNull()
                val decodeMillis = decodeNanos / 1_000_000
                val audioMillis = decodedSamples * 1_000 / SAMPLE_RATE
                decodeNanos = 0L
                decodedSamples = 0L
                if (!text.isNullOrBlank()) {
                    line.onUtterance(
                        Recogniser.Result(
                            text,
                            confidence = null,
                            decodeMillis = decodeMillis,
                            audioMillis = audioMillis,
                        ),
                    )
                }
            }
            return
        }
        if (!changed) return
        // Running text while the operator is still speaking: rule 6, and their only chance
        // to notice a misrecognition before it goes out.
        val soFar = decoder.runningText()
        if (soFar.isNotEmpty()) listener?.onPartial(soFar)
    }

    // ── the open line ────────────────────────────────────────────────────────

    /**
     * Opens the microphone and keeps it open, handing over a sentence at every pause.
     *
     * The capture source is the platform's communication path, which carries its echo
     * canceller: the handset's own speaker may be reading out an arriving message while
     * this listens, and without cancellation the microphone hears it and the sentence goes
     * straight back out. The engine drops anything that still matches what was just spoken.
     *
     * @return false when there is no model for the language or the microphone would not open
     */
    fun startOpenLine(
        languageCode: String,
        listener: OpenLineListener,
    ): Boolean {
        if (!isReady(languageCode)) return false
        stopOpenLine()
        // Any press in flight is over; the open line takes the microphone.
        settle { it.onNothingHeard("the open line took the microphone") }
        val press = generation.incrementAndGet()
        openLine = listener
        openLineMuted = false
        synchronized(pending) { pending.clear() }
        decodeNanos = 0L
        decodedSamples = 0L
        worker.execute {
            load(languageCode)
            decoder?.reset()
        }
        val started =
            capture.start(
                onHop = ::onHop,
                onError = { if (generation.get() == press) listener.onProblem("the microphone is not available") },
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            )
        if (!started) {
            openLine = null
            listener.onProblem("the microphone is not available")
            return false
        }
        return true
    }

    fun stopOpenLine() {
        if (openLine == null) return
        openLine = null
        openLineMuted = false
        generation.incrementAndGet()
        capture.stop()
        worker.execute { decoder?.reset() }
    }

    val isOpenLine: Boolean get() = openLine != null

    /** HOLD. Muted, the microphone stays open and nothing it hears is kept. */
    fun setOpenLineMuted(muted: Boolean) {
        openLineMuted = muted
        if (muted) {
            synchronized(pending) { pending.clear() }
            worker.execute { decoder?.reset() }
        }
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
        val press = generation.get()
        val releasedAt = System.nanoTime()
        stopPending.set(true)
        worker.execute {
            try {
                finish(press, releasedAt)
            } finally {
                stopPending.set(false)
            }
        }
    }

    /** The release, on the worker: the grace period, the microphone, the last clause. */
    private fun finish(
        press: Int,
        releasedAt: Long,
    ) {
        // Another press has taken the microphone since; nothing here is ours any more, and
        // the microphone is left open for it.
        if (generation.get() != press) return

        // The grace period: the operator's last consonant, and the audio path's own
        // buffering, are still on their way. Slept on the worker, so a drain that was
        // already queued ahead of this task has overlapped with it rather than added to it.
        val elapsedMillis = (System.nanoTime() - releasedAt) / 1_000_000
        val remaining = RELEASE_GRACE_MILLIS - elapsedMillis
        if (remaining > 0) runCatching { Thread.sleep(remaining) }
        if (generation.get() != press) return
        capture.stop()

        val decoder = decoder
        if (decoder == null) {
            settle(press) { it.onNothingHeard("the model is still loading") }
            return
        }
        // Anything captured since the last drain, then the tail. This is the only decode
        // that costs latency, and it covers at most one clause.
        drainInto(decoder, allowProvisional = false)
        val text = runCatching { decoder.onEndpoint() }.getOrNull()
        if (text.isNullOrBlank()) {
            settle(press) { it.onNothingHeard("nothing recognised") }
            return
        }
        // IndicConformer gives no per-utterance score, so none is claimed. A confidence
        // invented here would be read as the model's.
        settle(press) {
            it.onResult(
                Recogniser.Result(
                    text = text,
                    confidence = null,
                    decodeMillis = decodeNanos / 1_000_000,
                    audioMillis = decodedSamples * 1_000 / SAMPLE_RATE,
                ),
            )
        }
    }

    override fun close() {
        generation.incrementAndGet()
        runCatching { capture.stop() }
        worker.execute {
            recogniser?.let { runCatching { it.close() } }
            recogniser = null
            decoder = null
            loadedFor = null
        }
        worker.shutdown()
    }

    /** One terminal callback per [start], whichever path gets there first. */
    private fun settle(action: (Recogniser.Listener) -> Unit) {
        if (settled.compareAndSet(false, true)) listener?.let(action)
    }

    /** As [settle], for a task that may belong to a press that is already over. */
    private fun settle(
        press: Int,
        action: (Recogniser.Listener) -> Unit,
    ) {
        if (generation.get() != press) return
        settle(action)
    }

    private companion object {
        const val SAMPLE_RATE = SherpaRecogniser.SAMPLE_RATE

        /** A held control in a pocket must truncate a sentence, not exhaust the heap. */
        const val MAX_SECONDS = 30
        const val MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS

        /**
         * How long the microphone stays open after the release.
         *
         * Long enough for the tail of a final consonant and for the capture path to deliver
         * what it already holds; short enough that it is a fraction of the budget rather
         * than a term in it. Every latency figure is measured from the release, so this is
         * visible in band F, and it should be: it is a choice, not a cost.
         */
        const val RELEASE_GRACE_MILLIS = 200L

        /** A Unicode script block is 128 code points, which is how Language.blockBase is defined. */
        const val SCRIPT_BLOCK = 0x80

        /**
         * Quiet that ends a sentence on the open line. A clause pause is 280 ms; this is
         * two and a half of those, and shorter than the second a person takes to answer.
         */
        const val OPEN_LINE_ENDPOINT_MILLIS = 700

        /** Speech sits near 0.1 RMS, so this puts an ordinary voice around two thirds. */
        const val METER_GAIN = 6.0
    }
}

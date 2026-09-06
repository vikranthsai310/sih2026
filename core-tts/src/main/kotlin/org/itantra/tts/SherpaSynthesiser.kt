package org.itantra.tts

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * The Piper binding, and the streaming playout on top of it. Tasks **W3.2**, **W3.7**
 * and **W3.8**.
 *
 * ## Why playback starts before synthesis finishes
 *
 * Synthesising a whole sentence and then playing it wastes the entire synthesis duration
 * as latency — roughly 700 ms for a typical operational sentence. The evaluation measures
 * *"the time delay between the text received and audio processed and played"*, so that
 * wait is directly worth marks.
 *
 * Two mechanisms cut it to about 180 ms, and they compose:
 *
 * 1. [ClauseSplitter] breaks the sentence at clause boundaries, so the first unit of work
 *    is short.
 * 2. `generateWithCallback` emits audio **as it is produced**, and each chunk is written
 *    to `AudioTrack` immediately rather than accumulated.
 *
 * ## The underrun rule
 *
 * > An audible gap inside a sentence is worse than 100 ms of extra initial latency.
 *
 * `AudioTrack` is created in `MODE_STREAM` with a buffer several times the minimum, and
 * writes block. If synthesis cannot keep ahead of playback the write simply waits, which
 * stretches the delivery rather than tearing a hole in it.
 */
class SherpaSynthesiser(
    modelPath: String,
    tokensPath: String,
    dataDir: String,
    /**
     * Null for a voice on the filesystem, which is the shipping case.
     *
     * A Piper voice is ~63 MB and the espeak-ng data another 18, against a 30 MB installer
     * cap — so voices are fetched at setup and read from app storage, exactly as the
     * acoustic models are.
     */
    assets: AssetManager? = null,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
    numThreads: Int = SYNTHESIS_THREADS,
) : AutoCloseable {
    private val tts =
        OfflineTts(
            assets,
            OfflineTtsConfig(
                model =
                    OfflineTtsModelConfig(
                        // Piper voices are VITS. espeak-ng data lives in dataDir and is
                        // what turns text into the phonemes the model expects.
                        vits =
                            OfflineTtsVitsModelConfig(
                                model = modelPath,
                                tokens = tokensPath,
                                dataDir = dataDir,
                            ),
                        numThreads = numThreads,
                    ),
            ),
        )

    val sampleRate: Int get() = tts.sampleRate()

    val speakerCount: Int get() = tts.numSpeakers()

    /** Synthesises everything before returning. Used by the alert path, which repeats it. */
    fun synthesise(text: String): ShortArray {
        val audio = tts.generate(text, speakerId, speed)
        return audio.samples.toShortArray()
    }

    /**
     * Synthesises and plays, starting playback on the first chunk.
     *
     * Runs on the calling thread and blocks until the audio has been written, so callers
     * should use [speakOnAudioThread].
     *
     * @param onFirstAudio invoked when the first chunk reaches `AudioTrack` — this is the
     *   `t_chunk1` stage in `latency.csv`
     * @param shouldContinue polled per chunk; returning false stops at a chunk boundary,
     *   which is what barge-in needs (task W5.7)
     */
    fun speak(
        text: String,
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ) {
        val track = createTrack()
        try {
            track.play()
            tts.generateWithCallback(text, speakerId, speed, sink(track, onFirstAudio, shouldContinue))
        } finally {
            // stop() lets what is already queued drain; flush() would discard it and
            // clip the last word.
            runCatching { track.stop() }
            track.release()
        }
    }

    /**
     * The audio callback sherpa calls from C, as an **object expression rather than a
     * lambda**. That is not a style choice and must not be tidied into one.
     *
     * `generateWithCallbackImpl` finds this object's method through JNI, by the exact
     * signature `invoke([F)Ljava/lang/Integer;`. Kotlin 2.0 compiles a lambda to an
     * `invokedynamic`, and D8 desugars that into a synthetic class carrying only the
     * erased `Object invoke(Object)` — so the method the native side asks for does not
     * exist in any build, and the first attempt to speak aborts the whole process:
     *
     * ```
     * NoSuchMethodError: no non-static method "Lg2/f0;.invoke([F)Ljava/lang/Integer;"
     * ```
     *
     * An object expression is compiled to a real class with the specialised method, which
     * is what JNI can find. R8 would still delete it as unreachable — nothing in the
     * bytecode calls it — so `app/proguard-rules.pro` keeps it explicitly. Both halves are
     * needed; either one alone leaves a handset that crashes the moment it is spoken to.
     */
    private fun sink(
        track: AudioTrack,
        onFirstAudio: () -> Unit,
        shouldContinue: () -> Boolean,
    ): (FloatArray) -> Int =
        object : (FloatArray) -> Int {
            private var started = false

            override fun invoke(chunk: FloatArray): Int {
                if (!started) {
                    started = true
                    onFirstAudio()
                }
                val shorts = chunk.toShortArray()
                // WRITE_BLOCKING: if synthesis falls behind, the write waits rather than
                // returning short and leaving a hole in the middle of a sentence.
                track.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
                // sherpa reads 1 as "keep going" and 0 as "stop".
                return if (shouldContinue()) 1 else 0
            }
        }

    /**
     * Speaks each clause in turn, so the first sound arrives after one short clause
     * rather than one long sentence.
     */
    fun speakSentence(
        text: String,
        splitter: ClauseSplitter = ClauseSplitter(),
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ) {
        var first = true
        for (clause in splitter.split(text)) {
            if (!shouldContinue()) return
            speak(
                clause,
                onFirstAudio = {
                    if (first) {
                        first = false
                        onFirstAudio()
                    }
                },
                shouldContinue = shouldContinue,
            )
        }
    }

    /**
     * Task **W3.8**. Synthesis runs at `THREAD_PRIORITY_AUDIO` because it is feeding a
     * real-time sink: descheduled synthesis is an audible gap, not merely slow work.
     */
    fun speakOnAudioThread(
        text: String,
        splitter: ClauseSplitter = ClauseSplitter(),
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Thread =
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            speakSentence(text, splitter, onFirstAudio, shouldContinue)
        }.also { it.start() }

    private fun createTrack(): AudioTrack {
        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // Several times the minimum: the cost is a little more latency before the
            // first sample, the benefit is that a slow synthesis chunk does not underrun.
            .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLE)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun close() = tts.release()

    companion object {
        /** Two, per `docs/TTS.md`: synthesis shares the device with everything else. */
        const val SYNTHESIS_THREADS = 2

        private const val BUFFER_MULTIPLE = 4

        fun FloatArray.toShortArray(): ShortArray =
            ShortArray(size) { (this[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}

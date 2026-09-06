package org.itantra.tts

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * The Piper binding, and the playout on top of it. Tasks **W3.2**, **W3.7** and **W3.8**.
 *
 * ## What changed, and why it sounded like a robot
 *
 * The first version cut a sentence every twelve words and at every conjunction, opened a
 * fresh `AudioTrack` for each piece, synthesised it as a sentence of its own, and released
 * the track before its tail had played. Every piece therefore ended with the pitch falling
 * to a full stop, began and ended with a click, and lost its last few milliseconds. A
 * six-word message sounded like three separate announcements. None of that was the voice.
 *
 * Now a message is shaped into the phrases a person would say it in ([SpeechShaper]),
 * each phrase is synthesised whole with the voice's own tuning ([VoiceProfile]), levelled
 * and de-clicked ([AudioPolish]), and written to **one** track for the whole message with
 * the right silence between phrases. The track is released only after the last sample has
 * played.
 *
 * ## Why playback still starts before synthesis finishes
 *
 * Synthesising a whole message and then playing it wastes the entire synthesis duration
 * as latency. The evaluation measures *"the time delay between the text received and audio
 * processed and played"*, so the first phrase is played while the second is synthesised.
 * The first phrase is a clause rather than a twelve-word slice, so the first sound arrives
 * a little later than it used to and is the beginning of a sentence rather than a fragment.
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
    private val profile: VoiceProfile = VoiceProfile(),
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
                                noiseScale = profile.noiseScale,
                                noiseScaleW = profile.noiseScaleW,
                                lengthScale = profile.lengthScale,
                            ),
                        numThreads = numThreads,
                    ),
            ),
        )

    val sampleRate: Int get() = tts.sampleRate()

    val speakerCount: Int get() = tts.numSpeakers()

    /** The speaker asked for, or the first, on a voice with fewer speakers than that. */
    private val speakerId: Int get() = if (profile.speakerId in 0 until speakerCount) profile.speakerId else 0

    /** One phrase, synthesised whole, levelled and de-clicked. Blocking. */
    fun synthesisePhrase(text: String): ShortArray {
        val audio = tts.generate(text, speakerId, SPEED)
        return AudioPolish.polish(audio.samples, sampleRate)
    }

    /**
     * Synthesises a whole message before returning, pauses included. Used by the alert
     * path, which repeats it.
     */
    fun synthesise(
        text: String,
        shaper: SpeechShaper = SpeechShaper(),
    ): ShortArray {
        val parts = ArrayList<ShortArray>()
        for (phrase in shaper.shape(text)) {
            parts += synthesisePhrase(phrase.text)
            if (phrase.pauseAfterMillis > 0) parts += AudioPolish.silence(phrase.pauseAfterMillis, sampleRate)
        }
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /**
     * Speaks the phrases in turn on one track, starting playback on the first.
     *
     * Runs on the calling thread and blocks until the last sample has played, so callers
     * should use [speakOnAudioThread] or their own worker.
     *
     * @param onFirstAudio invoked when the first phrase reaches `AudioTrack` — this is the
     *   `t_chunk1` stage in `latency.csv`
     * @param shouldContinue polled per phrase; returning false stops at a phrase boundary,
     *   which is what barge-in needs (task W5.7)
     */
    fun speak(
        phrases: List<Phrase>,
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ) {
        if (phrases.isEmpty()) return
        val track = createTrack()
        var framesWritten = 0L
        var first = true
        try {
            track.play()
            for (phrase in phrases) {
                if (!shouldContinue()) break
                val pcm = synthesisePhrase(phrase.text)
                if (pcm.isEmpty()) continue
                if (first) {
                    first = false
                    onFirstAudio()
                }
                // WRITE_BLOCKING: if synthesis falls behind, the write waits rather than
                // returning short and leaving a hole in the middle of a sentence.
                framesWritten += write(track, pcm)
                if (phrase.pauseAfterMillis > 0 && shouldContinue()) {
                    framesWritten += write(track, AudioPolish.silence(phrase.pauseAfterMillis, sampleRate))
                }
            }
            // Everything is queued; let it play out. Releasing now would clip the tail of
            // the last word, which is exactly the part that carries the sentence's end.
            if (shouldContinue()) drain(track, framesWritten)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Shapes [text] and speaks it. The whole pipeline after normalisation. */
    fun speakSentence(
        text: String,
        shaper: SpeechShaper = SpeechShaper(),
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ) = speak(shaper.shape(text), onFirstAudio, shouldContinue)

    /**
     * Task **W3.8**. Synthesis runs at `THREAD_PRIORITY_AUDIO` because it is feeding a
     * real-time sink: descheduled synthesis is an audible gap, not merely slow work.
     */
    fun speakOnAudioThread(
        text: String,
        shaper: SpeechShaper = SpeechShaper(),
        onFirstAudio: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): Thread =
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            speakSentence(text, shaper, onFirstAudio, shouldContinue)
        }.also { it.start() }

    private fun write(
        track: AudioTrack,
        pcm: ShortArray,
    ): Int {
        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        return if (written > 0) written else 0
    }

    /**
     * Waits until the track has played what was written, or until that should have
     * happened and then some. The head position is the device's own count of frames
     * played, so this is the audio actually leaving the speaker rather than an estimate.
     */
    private fun drain(
        track: AudioTrack,
        framesWritten: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + framesWritten * 1000 / sampleRate + DRAIN_GRACE_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (played >= framesWritten) return
            try {
                Thread.sleep(DRAIN_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

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

        /** Tempo is the profile's business; this multiplier stays neutral. */
        private const val SPEED = 1.0f

        private const val BUFFER_MULTIPLE = 4

        /** Past the expected end of playback, how long to keep waiting for the device. */
        private const val DRAIN_GRACE_MILLIS = 400L
        private const val DRAIN_POLL_MILLIS = 15L

        fun FloatArray.toShortArray(): ShortArray =
            ShortArray(size) { (this[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}

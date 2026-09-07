package org.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Microphone capture. Tasks **W1.17** and **W1.19**.
 *
 * ## The three choices that matter
 *
 * **`VOICE_RECOGNITION`, not `MIC`.** The platform applies a different processing chain
 * to each. `MIC` may add automatic gain control and aggressive noise suppression tuned
 * for human listeners, which is exactly the wrong preprocessing ahead of an acoustic
 * model — it distorts the spectrum the model was trained on. `VOICE_RECOGNITION` asks
 * the platform to leave the signal alone.
 *
 * **20 ms hops.** One hop is 320 samples at 16 kHz. Short enough that the energy gate
 * reacts promptly, long enough that the read loop is not the dominant cost.
 *
 * **A capture thread at `THREAD_PRIORITY_URGENT_AUDIO`.** `AudioRecord` has a finite
 * internal buffer; if this thread is descheduled long enough the buffer overruns and
 * samples are lost silently. Lost samples are the one failure the pipeline cannot
 * recover from, because nothing downstream knows they were missing.
 */
class AudioCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val hopMillis: Int = HOP_MILLIS,
) {
    /** Samples per hop — 320 at 16 kHz and 20 ms. */
    val hopSamples: Int get() = sampleRate * hopMillis / 1000

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Overruns seen since [start]. Anything above zero is a defect worth chasing. */
    @Volatile
    var overruns: Int = 0
        private set

    /**
     * Begins capture, delivering one hop at a time on the capture thread.
     *
     * The caller must hold `RECORD_AUDIO`; without it `AudioRecord` construction throws,
     * which is caught and surfaced through [onError] rather than crashing the service.
     *
     * @param onHop called with a buffer that is **reused between calls** — copy it if you
     *   intend to keep it. Copying every hop unconditionally would allocate 50 arrays a
     *   second for audio that is usually silence.
     * @param onStarted called on the capture thread once `AudioRecord` is actually
     *   recording. This method returning true means the recorder was built, not that it is
     *   listening yet: `startRecording` takes tens of milliseconds on some handsets, and a
     *   screen that says "listening" before this fires is inviting the operator to speak
     *   the first syllable into nothing.
     */
    @SuppressLint("MissingPermission")
    fun start(
        onHop: (ShortArray, Int) -> Unit,
        onError: (EngineState.Degraded.Reason) -> Unit = {},
        onStarted: () -> Unit = {},
        /**
         * `VOICE_RECOGNITION` for push-to-talk, where nothing plays while the microphone is
         * open. `VOICE_COMMUNICATION` for the open line, where the handset's own speaker may
         * be talking at the same time: that source carries the platform's echo canceller,
         * which is what stops the phone re-transmitting what it has just played.
         */
        source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    ): Boolean {
        if (running) return true

        val minBuffer =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBuffer <= 0) {
            onError(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
            return false
        }

        val recorder =
            try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    // Well above the minimum: the read loop must tolerate being late
                    // without losing samples.
                    maxOf(minBuffer * BUFFER_MULTIPLE, hopSamples * 2 * BUFFER_MULTIPLE),
                )
            } catch (denied: SecurityException) {
                onError(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
                return false
            }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            // The usual cause is a phone call holding the microphone — risk T-12.
            onError(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
            return false
        }

        record = recorder
        running = true
        overruns = 0

        thread =
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ShortArray(hopSamples)
                recorder.startRecording()
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    onStarted()
                } else {
                    onError(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
                    running = false
                }
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> onHop(buffer, read)
                        read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_DEAD_OBJECT -> {
                            onError(EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE)
                            running = false
                        }
                        read < 0 -> overruns++
                    }
                }
                runCatching { recorder.stop() }
                recorder.release()
            }.also {
                it.name = "itantra-capture"
                it.start()
            }

        return true
    }

    fun stop() {
        running = false
        thread?.join(STOP_TIMEOUT_MILLIS)
        thread = null
        record = null
    }

    val isRunning: Boolean get() = running

    companion object {
        const val SAMPLE_RATE = 16_000
        const val HOP_MILLIS = 20
        private const val BUFFER_MULTIPLE = 4
        private const val STOP_TIMEOUT_MILLIS = 500L
    }
}

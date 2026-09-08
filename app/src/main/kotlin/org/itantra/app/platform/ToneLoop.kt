package org.itantra.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * One thread that plays a sound, then a measured silence, then the sound again, on the
 * alarm stream at full volume, for as long as it is wanted.
 *
 * Both sounds of a search are this loop with a different [Voice]: the searcher's own
 * siren ([LocateSiren]) and the target's chirp ([FoundBeacon]). What they share is the
 * part that has to be right for either to be heard.
 *
 * ## The stream never stops
 *
 * The first version wrote a beep and then slept for the gap. A track in stream mode
 * that is not fed runs dry, and a track that runs dry restarts with a click and a lag
 * the controller decides, not the loop; and a sleep is only as accurate as the
 * scheduler. Here the silence is written as samples, so the audio clock paces the loop
 * and the gap is exactly as long as asked. The silence goes out in short slices, and the
 * gap is asked for again before each one: a searcher who takes three quick steps hears
 * the beeps speed up inside the pause, not after it.
 *
 * ## Full volume, for as long as it lasts
 *
 * `USAGE_ALARM` plays through Do Not Disturb, a silenced ringer and a locked screen. The
 * alarm volume itself is raised to the top while the loop runs and put back after, the
 * way an alert does it: a searcher's handset on silent would otherwise beep to nobody,
 * and a target's in a pocket is the case the chirp exists for.
 *
 * ## Latency
 *
 * The track's buffer is the smallest the platform allows, or [BUFFER_MILLIS] if that is
 * smaller, so a write blocks once the loop is about that far ahead of the speaker. That
 * is how far behind a change in the [Voice] can be heard, and how long [stop] takes to
 * take effect.
 *
 * Generated from arithmetic: no sound file, nothing to load.
 */
class ToneLoop(
    private val threadName: String,
    context: Context?,
    private val voice: Voice,
) {
    /** What to play, asked from the audio thread. */
    interface Voice {
        /** The next sound, as 16-bit mono PCM at [SAMPLE_RATE]. */
        fun sound(): ShortArray

        /**
         * The silence after it, in milliseconds. Asked again before every slice of silence
         * is written, so a shorter answer cuts the pause short.
         */
        fun gapMillis(): Long
    }

    private val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** The alarm volume before it was raised, put back on [stop]. Null when it was not touched. */
    private var restoreVolume: Int? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        raiseVolume()
        thread =
            Thread({ run() }, threadName).also {
                it.isDaemon = true
                it.start()
            }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        restoreVolumeIfRaised()
    }

    val isRunning: Boolean get() = running.get()

    private fun raiseVolume() {
        val manager = audio ?: return
        runCatching {
            val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            val top = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current < top) {
                restoreVolume = current
                manager.setStreamVolume(AudioManager.STREAM_ALARM, top, 0)
            }
        }
    }

    private fun restoreVolumeIfRaised() {
        val manager = audio ?: return
        val before = restoreVolume ?: return
        restoreVolume = null
        runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
    }

    private fun run() {
        val track = build() ?: return
        val silence = ShortArray(SAMPLE_RATE * SLICE_MILLIS / 1000)
        try {
            track.play()
            loop@ while (running.get()) {
                if (!write(track, voice.sound())) break
                var elapsed = 0L
                while (running.get() && elapsed < voice.gapMillis()) {
                    if (!write(track, silence)) break@loop
                    elapsed += SLICE_MILLIS
                }
            }
        } finally {
            // Pause and flush before stop, so what is still buffered is dropped rather
            // than played out after the operator pressed the button.
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
    }

    /** @return false on a write error, which is a track that has died. */
    private fun write(
        track: AudioTrack,
        samples: ShortArray,
    ): Boolean = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) >= 0

    private fun build(): AudioTrack? =
        runCatching {
            val minimum =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val wanted = SAMPLE_RATE * 2 * BUFFER_MILLIS / 1000
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimum, wanted))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

    companion object {
        const val SAMPLE_RATE = 22_050

        /** How far ahead of the speaker the loop may run: the most a change can lag. */
        const val BUFFER_MILLIS = 120

        /** A gap is written in slices this long, and the gap re-asked before each. */
        const val SLICE_MILLIS = 20

        /**
         * A sine of [hz] for [millis], faded in and out over [fadeMillis] so there is no
         * click at either end. A click is a wide-band burst, and a wide-band burst is the
         * one thing two ears mislocate.
         */
        fun tone(
            hz: Float,
            millis: Int,
            amplitude: Double,
            fadeMillis: Int,
        ): ShortArray {
            val out = ShortArray(SAMPLE_RATE * millis / 1000)
            tone(out, 0, out.size, hz, amplitude, fadeMillis)
            return out
        }

        /** Writes one shaped sine into [out] from [from] for [length] samples. */
        fun tone(
            out: ShortArray,
            from: Int,
            length: Int,
            hz: Float,
            amplitude: Double,
            fadeMillis: Int,
        ) {
            val fade = (SAMPLE_RATE * fadeMillis / 1000).coerceAtLeast(1)
            for (i in 0 until length) {
                var s = sin(2.0 * PI * hz * i / SAMPLE_RATE) * amplitude
                if (i < fade) s *= i.toDouble() / fade
                if (length - 1 - i < fade) s *= (length - 1 - i).toDouble() / fade
                out[from + i] = (s * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}

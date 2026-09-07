package org.itantra.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sound a unit makes so the person looking for it can walk towards the noise.
 *
 * ## Why the target sounds, and not only the searcher
 *
 * Inside the last fifteen metres no radio a handset has can say *which way*: the two
 * positions overlap and a signal is only a strength. Two ears can, to a few degrees, in
 * the dark, around a corner, under a roof. So when the searcher is close and asks for it
 * ([org.itantra.proto.Locate.sound]), this unit chirps and the searcher follows the chirp
 * -- the way every "find my phone" ends, because it is the way that works.
 *
 * ## The sound
 *
 * A rising pair, every second and a half: two short notes a fifth apart, the second
 * higher. Distinct from the searcher's own siren -- a single note at a rate -- and from
 * the alert tone, so a unit that hears both knows which is which. Rising, because a
 * rising pair reads as a question, "here?", and is easy to pick out of wind and water.
 * Sixty-cycle fades on each note, so there is no click to mislocate.
 *
 * ## The stream
 *
 * `USAGE_ALARM`, the same stream alerts use: it plays through Do Not Disturb, a silenced
 * ringer and a locked screen, and the engine raises its volume to maximum for as long as
 * the chirp is wanted, the way an alert does, and puts it back after. A handset being
 * searched for in a pocket, on silent, is the case this exists for.
 *
 * Generated from arithmetic like [LocateSiren]: nothing to load, nothing to fetch.
 */
class FoundBeacon(context: Context? = null) {
    private val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** The alarm volume before it was raised, put back on [stop]. Null when it was not touched. */
    private var restoreVolume: Int? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        raiseVolume()
        thread =
            Thread({ run() }, "found-beacon").also {
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

    /** To the top, like an alert: a handset on silent in a pocket is the case this exists for. */
    private fun raiseVolume() {
        val manager = audio ?: return
        runCatching {
            val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                restoreVolume = current
                manager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    private fun restoreVolumeIfRaised() {
        val manager = audio ?: return
        val before = restoreVolume ?: return
        restoreVolume = null
        runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
    }

    val isRunning: Boolean get() = running.get()

    private fun run() {
        val track =
            runCatching {
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
                    .setBufferSizeInBytes(SAMPLE_RATE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return
        val chirp = chirp()
        try {
            track.play()
            while (running.get()) {
                track.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)
                try {
                    Thread.sleep(GAP_MILLIS)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Two notes and the silence between them, as one buffer. */
    private fun chirp(): ShortArray {
        val note = SAMPLE_RATE * NOTE_MILLIS / 1000
        val rest = SAMPLE_RATE * REST_MILLIS / 1000
        val out = ShortArray(note * 2 + rest)
        tone(out, 0, note, LOW_HZ)
        tone(out, note + rest, note, HIGH_HZ)
        return out
    }

    private fun tone(
        out: ShortArray,
        from: Int,
        length: Int,
        hz: Float,
    ) {
        val fade = SAMPLE_RATE * FADE_MILLIS / 1000
        for (i in 0 until length) {
            var s = sin(2.0 * PI * hz * i / SAMPLE_RATE) * AMPLITUDE
            if (i < fade) s *= i.toDouble() / fade
            if (length - 1 - i < fade) s *= (length - 1 - i).toDouble() / fade
            out[from + i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val AMPLITUDE = 0.85

        /** A fifth: 880 to 1320 Hz, well inside where a phone speaker is loud and an ear is sharp. */
        const val LOW_HZ = 880f
        const val HIGH_HZ = 1_320f

        const val NOTE_MILLIS = 140
        const val REST_MILLIS = 60
        const val FADE_MILLIS = 6

        /** Between chirps. Often enough to walk to; seldom enough to hear the searcher's own siren between. */
        const val GAP_MILLIS = 1_500L
    }
}

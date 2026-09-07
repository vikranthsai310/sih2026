package org.itantra.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sound that leads an operator to another unit: beeps that come faster and higher
 * the closer they get, and slow right down when the signal is gone.
 *
 * A Geiger counter rather than a wail, because a rate is something a person can judge
 * with their ears while their eyes are on the ground, and because "faster" is
 * unambiguous where "louder" competes with everything else in a field. Every beep is
 * shaped -- a few milliseconds of fade at each end -- so there is no click, and the
 * whole thing is generated here from arithmetic: no sound file, nothing to load.
 *
 * Thread-safe: [proximity] and [lost] are set from wherever the readings arrive and read
 * by the one thread that writes audio.
 */
class LocateSiren {
    /** 0 far, 1 at arm's reach. */
    @Volatile
    var proximity: Float = 0f

    /** No signal lately: a slow, low beep that says "still looking". */
    @Volatile
    var lost: Boolean = true

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread({ run() }, "locate-siren").also {
                it.isDaemon = true
                it.start()
            }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
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
        try {
            track.play()
            while (running.get()) {
                val lostNow = lost
                val p = proximity.coerceIn(0f, 1f)
                val hz = if (lostNow) LOST_HZ else FAR_HZ + (NEAR_HZ - FAR_HZ) * p
                val gap =
                    if (lostNow) LOST_GAP_MILLIS else (FAR_GAP_MILLIS + (NEAR_GAP_MILLIS - FAR_GAP_MILLIS) * p).toLong()
                val beep = beep(hz, if (lostNow) LOST_BEEP_MILLIS else BEEP_MILLIS)
                track.write(beep, 0, beep.size, AudioTrack.WRITE_BLOCKING)
                try {
                    Thread.sleep(gap)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun beep(
        hz: Float,
        millis: Int,
    ): ShortArray {
        val n = SAMPLE_RATE * millis / 1000
        val fade = SAMPLE_RATE * FADE_MILLIS / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            var s = sin(2.0 * PI * hz * i / SAMPLE_RATE) * AMPLITUDE
            if (i < fade) s *= i.toDouble() / fade
            if (n - 1 - i < fade) s *= (n - 1 - i).toDouble() / fade
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val AMPLITUDE = 0.6
        const val BEEP_MILLIS = 70
        const val LOST_BEEP_MILLIS = 160
        const val FADE_MILLIS = 5

        const val FAR_HZ = 620f
        const val NEAR_HZ = 1_480f
        const val LOST_HZ = 380f

        const val FAR_GAP_MILLIS = 1_400f
        const val NEAR_GAP_MILLIS = 90f
        const val LOST_GAP_MILLIS = 2_800L
    }
}

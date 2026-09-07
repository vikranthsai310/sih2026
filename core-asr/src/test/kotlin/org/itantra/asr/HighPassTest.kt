package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The input high-pass: rumble and offset go, the voice stays. */
class HighPassTest {
    /** A decoder whose "model" reports what it was given, so the filtered audio can be inspected. */
    private class Capture {
        var last: ShortArray = ShortArray(0)

        fun decode(pcm: ShortArray): Transcript {
            last = pcm
            return Transcript.fromText("heard")
        }
    }

    private fun rms(a: ShortArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size)

    private fun tone(
        hz: Double,
        millis: Int,
        amplitude: Double,
        offset: Double = 0.0,
    ): ShortArray {
        val n = 16_000 * millis / 1000
        return ShortArray(n) { i -> (offset + amplitude * sin(2 * PI * hz * i / 16_000)).toInt().toShort() }
    }

    @Test
    fun `a DC offset and rumble are removed and a voice band tone is kept`() {
        val capture = Capture()
        val decoder = UtteranceDecoder(decode = capture::decode, highPassHz = UtteranceDecoder.HIGH_PASS_HZ)
        // 300 Hz at 6000 on a 4000 offset, plus 10 Hz rumble at 3000: the offset and rumble
        // are louder than the tone together.
        val voice = tone(300.0, 1_500, 6_000.0, offset = 4_000.0)
        val rumble = tone(10.0, 1_500, 3_000.0)
        val mixed = ShortArray(voice.size) { (voice[it] + rumble[it]).toShort() }
        val hop = 320
        for (at in mixed.indices step hop) {
            decoder.onAudio(mixed.copyOfRange(at, minOf(mixed.size, at + hop)), allowProvisional = false)
        }
        decoder.onEndpoint()
        val out = capture.last
        assertTrue("the model was given audio", out.size > 8_000)
        // Settled: skip the first quarter second of the filter's own start-up.
        val body = out.copyOfRange(4_000, out.size)
        val mean = body.sumOf { it.toDouble() } / body.size
        assertEquals("offset removed", 0.0, mean, 150.0)
        // The 300 Hz tone alone has an rms of 6000/√2 ≈ 4243; with the rumble it was ~4800.
        assertEquals("voice kept, rumble gone", 4_243.0, rms(body), 250.0)
    }

    @Test
    fun `off by default, the audio is untouched`() {
        val capture = Capture()
        val decoder = UtteranceDecoder(decode = capture::decode)
        val voice = tone(300.0, 1_200, 6_000.0, offset = 4_000.0)
        for (at in voice.indices step 320) {
            decoder.onAudio(voice.copyOfRange(at, minOf(voice.size, at + 320)), allowProvisional = false)
        }
        decoder.onEndpoint()
        val out = capture.last
        val mean = out.sumOf { it.toDouble() } / out.size
        assertEquals(4_000.0, mean, 100.0)
    }
}

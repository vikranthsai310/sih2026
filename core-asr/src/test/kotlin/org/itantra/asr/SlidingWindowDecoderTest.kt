package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowing is tested without a model, because the claim being tested is not
 * "the recogniser is accurate" — that is measured separately against a corpus — but
 * "**only a bounded tail is left to decode when the speaker stops**". That claim is
 * what the revised 800–1200 ms latency target rests on, and it is pure arithmetic.
 *
 * Risk T-16, requirement N1, task W3.13.
 */
class SlidingWindowDecoderTest {
    /** Records what it was asked to decode, so the test can assert on the work done. */
    private class SpyDecoder {
        val windows = ArrayList<Int>()

        fun decode(pcm: ShortArray): String {
            windows += pcm.size
            return "w${windows.size}"
        }
    }

    private fun silence(
        millis: Int,
        rate: Int = 16_000,
    ) = ShortArray(rate * millis / 1000)

    // ── window scheduling ────────────────────────────────────────────────────

    @Test
    fun `nothing is decoded before a full window has accumulated`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        assertTrue(d.onSpeech(silence(500)).isEmpty())
        assertTrue(d.onSpeech(silence(500)).isEmpty())
        assertTrue(d.onSpeech(silence(400)).isEmpty())
        assertTrue("no decode may happen below one window", spy.windows.isEmpty())
    }

    @Test
    fun `a window is decoded as soon as it fills`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        assertTrue(d.onSpeech(silence(1_000)).isEmpty())
        assertEquals(
            "1.5 s of speech must trigger exactly one decode",
            1,
            d.onSpeech(silence(500)).size,
        )
        assertEquals(1, spy.windows.size)
        assertEquals("the window handed to the model is 1.5 s", 24_000, spy.windows[0])
    }

    @Test
    fun `consecutive windows advance by the stride, not the whole window`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        d.onSpeech(silence(1_500))
        assertEquals(1, spy.windows.size)
        // A further 1.1 s -- the stride -- must produce the next window, not 1.5 s.
        d.onSpeech(silence(1_100))
        assertEquals("stride is window minus overlap", 2, spy.windows.size)
        assertEquals(24_000, spy.windows[1])
    }

    @Test
    fun `the overlap is retained so a word straddling a boundary is not lost`() {
        val d = SlidingWindowDecoder(decode = { "x" })
        d.onSpeech(silence(1_500))
        assertEquals("400 ms must remain buffered after a window", 400, d.pendingMillis)
    }

    /**
     * The reason [SlidingWindowDecoder.onSpeech] loops. A caller handing over a large
     * block at once must not leave several windows' worth of audio buffered, because
     * that becomes the tail the endpoint has to pay for.
     */
    @Test
    fun `one large chunk completes every window it contains, not just the first`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        val decodedNow = d.onSpeech(silence(8_000))

        assertTrue("expected several windows, got ${spy.windows.size}", spy.windows.size >= 6)
        assertEquals("every completed window is returned", spy.windows.size, decodedNow.size)
        assertTrue(
            "8 s in one chunk left a ${d.pendingMillis} ms tail",
            d.pendingMillis <= 1_500,
        )
    }

    // ── the latency claim ────────────────────────────────────────────────────

    /**
     * The load-bearing invariant. However long someone talks, the audio still
     * undecoded when they stop never exceeds one window — so the post-endpoint decode
     * is bounded by a constant instead of growing with the utterance.
     */
    @Test
    fun `the tail left at the endpoint never exceeds one window, for any duration`() {
        for (utteranceMillis in 100..8_000 step 100) {
            val d = SlidingWindowDecoder(decode = { "x" })
            var fed = 0
            while (fed < utteranceMillis) {
                val chunk = minOf(100, utteranceMillis - fed)
                d.onSpeech(silence(chunk))
                fed += chunk
                assertTrue(
                    "after $fed ms of a $utteranceMillis ms utterance the tail was " +
                        "${d.pendingMillis} ms, which exceeds the 1500 ms window",
                    d.pendingMillis <= 1_500,
                )
            }
        }
    }

    /**
     * The comparison that justifies the whole class: decoding the utterance in one
     * call after the endpoint versus decoding only the tail.
     */
    @Test
    fun `a three second utterance leaves well under a third of itself to decode`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        repeat(30) { d.onSpeech(silence(100)) } // 3 s, in 100 ms captures
        val tailSamples = d.pendingSamples
        d.onEndpoint()

        val wholeUtterance = 48_000
        assertTrue(
            "the endpoint decode covered $tailSamples samples of $wholeUtterance",
            tailSamples < wholeUtterance / 3,
        )
        // At a real-time factor of 0.30, that tail is the only decode in the budget.
        val tailDecodeMillis = (tailSamples / 16.0 * 0.30).toInt()
        assertTrue("tail decode of $tailDecodeMillis ms must fit the budget", tailDecodeMillis < 300)
    }

    @Test
    fun `the endpoint decodes the remainder exactly once`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)

        d.onSpeech(silence(2_000))
        val before = spy.windows.size
        d.onEndpoint()
        assertEquals("exactly one tail decode", before + 1, spy.windows.size)
    }

    @Test
    fun `an endpoint with nothing buffered does not call the model`() {
        val spy = SpyDecoder()
        val d = SlidingWindowDecoder(decode = spy::decode)
        assertEquals("", d.onEndpoint())
        assertTrue("an empty tail must not be sent to the model", spy.windows.isEmpty())
    }

    @Test
    fun `state does not leak between utterances`() {
        val d = SlidingWindowDecoder(decode = { "x" })
        d.onSpeech(silence(1_500))
        d.onEndpoint()
        assertEquals(0, d.pendingSamples)
        assertEquals(0, d.windowsDecoded)

        d.onSpeech(silence(200))
        d.reset()
        assertEquals(0, d.pendingSamples)
        assertEquals(0, d.windowsDecoded)
    }

    @Test
    fun `an overlap at least as long as the window is rejected`() {
        try {
            SlidingWindowDecoder(windowMillis = 400, overlapMillis = 400, decode = { "" })
            throw AssertionError("expected a rejection: the buffer would never drain")
        } catch (expected: IllegalArgumentException) {
            // The stride would be zero or negative and windows would never advance.
        }
    }
}

/** The stitching is separable from the buffering, so it is tested on its own. */
class StitchTest {
    private fun stitch(vararg parts: String) = SlidingWindowDecoder.stitch(parts.toList())

    @Test
    fun `words repeated in the overlap appear once`() {
        assertEquals(
            "हमें तुरंत मदद चाहिए दो लोग घायल हैं",
            stitch("हमें तुरंत मदद चाहिए", "मदद चाहिए दो लोग घायल हैं"),
        )
    }

    @Test
    fun `it prefers the longest overlap, not the first match`() {
        // "दो" occurs twice; matching only the short tail would duplicate "लोग दो".
        assertEquals("दो लोग दो नाव भेजो", stitch("दो लोग दो नाव", "दो नाव भेजो"))
    }

    @Test
    fun `three windows stitch in sequence`() {
        assertEquals(
            "a b c d e f g h",
            stitch("a b c d", "c d e f", "e f g h"),
        )
    }

    /**
     * Where the recogniser produced different words for the same audio there is no
     * overlap to find. Concatenating is the deliberate choice: a listener recovers
     * from a repeated word, but never from one that was silently dropped.
     */
    @Test
    fun `windows that share nothing are concatenated rather than trimmed`() {
        assertEquals("आग लगी है नाव भेजो", stitch("आग लगी है", "नाव भेजो"))
    }

    @Test
    fun `a window that fully repeats the previous one adds nothing`() {
        assertEquals("मदद चाहिए", stitch("मदद चाहिए", "मदद चाहिए"))
    }

    @Test
    fun `empty and blank windows are dropped`() {
        assertEquals("", stitch())
        assertEquals("", stitch("", "   "))
        assertEquals("मदद चाहिए", stitch("", "मदद चाहिए", "   "))
    }

    @Test
    fun `a single window passes through with its whitespace tidied`() {
        assertEquals("मदद चाहिए", stitch("  मदद   चाहिए  "))
    }

    @Test
    fun `no word is ever lost`() {
        val result = stitch("एक दो तीन", "तीन चार पाँच", "पाँच छह")
        for (word in listOf("एक", "दो", "तीन", "चार", "पाँच", "छह")) {
            assertTrue("'$word' was dropped from '$result'", result.contains(word))
        }
        assertEquals("एक दो तीन चार पाँच छह", result)
    }
}

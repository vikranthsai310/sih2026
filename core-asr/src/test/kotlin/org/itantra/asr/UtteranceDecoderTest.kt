package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The segmentation is tested against a **scripted model** rather than a real one.
 *
 * The claim under test is not that the recogniser is accurate — that is measured against
 * a corpus — but that the audio it is handed is the audio it should be handed: whole
 * clauses cut in the pauses between them, never half a word, each word read exactly once,
 * and a bounded tail left for the endpoint. Those are properties of the cutting, and a
 * model that knows where every word really is can check them exactly.
 *
 * Risk T-16, requirement N1, task W3.13.
 */
class UtteranceDecoderTest {
    /**
     * An utterance with known words at known positions, and a "model" that reads them.
     *
     * Speech is loud pseudo-noise and quiet is faint pseudo-noise, which is all an energy
     * floor can see. The model finds where in the utterance a window came from by matching
     * its samples, then reports the words that lie inside it. A word only partly inside is
     * reported with a `½` prefix — a real model asked about half a word gives a wrong word,
     * and this makes the wrong word visible.
     */
    private class Script(val rate: Int = 16_000) {
        private var out = ShortArray(rate * 4)
        var length = 0
            private set

        /** text, first sample, one past the last sample. */
        val words = ArrayList<Triple<String, Int, Int>>()

        /** Every window the model was asked about, as sample lengths. */
        val windows = ArrayList<Int>()

        private var seed = 0x5DEECE66DL

        private fun next(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 33).toInt()
        }

        private fun emit(value: Short) {
            if (length == out.size) out = out.copyOf(out.size * 2)
            out[length++] = value
        }

        fun say(
            text: String,
            millis: Int,
        ) {
            val start = length
            repeat(rate * millis / 1000) { emit((next() % 12_000).toShort()) }
            words += Triple(text, start, length)
        }

        fun quiet(millis: Int) {
            repeat(rate * millis / 1000) { emit((next() % 40).toShort()) }
        }

        /** Words separated by the brief dips real speech has between them. */
        fun sentence(
            texts: List<String>,
            wordMillis: Int = 320,
            gapMillis: Int = 60,
        ) {
            for ((i, text) in texts.withIndex()) {
                if (i > 0) quiet(gapMillis)
                say(text, wordMillis)
            }
        }

        fun pcm(): ShortArray = out.copyOf(length)

        private val index: Map<Long, List<Int>> by lazy {
            val map = HashMap<Long, MutableList<Int>>()
            for (i in 0..length - 4) map.getOrPut(key(out, i)) { ArrayList() }.add(i)
            map
        }

        private fun key(
            a: ShortArray,
            at: Int,
        ): Long =
            (a[at].toLong() and 0xFFFF shl 48) or
                (a[at + 1].toLong() and 0xFFFF shl 32) or
                (a[at + 2].toLong() and 0xFFFF shl 16) or
                (a[at + 3].toLong() and 0xFFFF)

        private fun offsetOf(window: ShortArray): Int {
            require(window.size >= 4) { "a window of ${window.size} samples cannot be placed" }
            val candidates = index[key(window, 0)] ?: error("window not from this script")
            return candidates.first { at ->
                at + window.size <= length && (0 until window.size).all { out[at + it] == window[it] }
            }
        }

        fun decode(window: ShortArray): Transcript {
            windows += window.size
            val at = offsetOf(window)
            val end = at + window.size
            val heard = ArrayList<Word>()
            for ((text, start, stop) in words) {
                if (stop <= at || start >= end) continue
                val whole = start >= at && stop <= end
                val seen = maxOf(start, at)
                heard += Word(if (whole) text else "½$text", (seen - at).toFloat() / rate)
            }
            return Transcript(heard)
        }

        fun expected(): String = words.joinToString(" ") { it.first }
    }

    private fun feed(
        decoder: UtteranceDecoder,
        pcm: ShortArray,
        chunkMillis: Int = 20,
        rate: Int = 16_000,
    ) {
        val chunk = rate * chunkMillis / 1000
        var at = 0
        while (at < pcm.size) {
            val end = minOf(pcm.size, at + chunk)
            decoder.onAudio(pcm.copyOfRange(at, end))
            at = end
        }
    }

    private fun decoderFor(script: Script) = UtteranceDecoder(decode = script::decode)

    /** The words alone: the commas mark the speaker's pauses and are tested separately. */
    private fun words(text: String) = text.replace(",", "")

    @Test
    fun `a pause the speaker left is carried as a comma, and only there`() {
        val script = Script()
        script.sentence(listOf("हमें", "तुरंत", "मदद", "चाहिए"))
        script.quiet(400)
        script.sentence(listOf("दो", "लोग", "घायल", "हैं"))
        script.quiet(100)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals("हमें तुरंत मदद चाहिए, दो लोग घायल हैं", d.onEndpoint())
    }

    @Test
    fun `a forced cut is not a pause and gets no comma`() {
        val script = Script()
        script.sentence((1..20).map { "w$it" }, wordMillis = 400, gapMillis = 0)
        script.quiet(100)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals(script.expected(), d.onEndpoint())
    }

    // ── the accuracy claim: whole clauses, every word once ───────────────────

    @Test
    fun `a clause is closed in the pause after it and read whole`() {
        val script = Script()
        script.sentence(listOf("हमें", "तुरंत", "मदद", "चाहिए"))
        script.quiet(400)
        script.sentence(listOf("दो", "लोग", "घायल", "हैं"))
        script.quiet(100)

        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals("the first clause closes in the pause", 1, d.segmentsDecoded)
        assertEquals(script.expected(), words(d.onEndpoint()))
    }

    @Test
    fun `no window ever begins or ends inside a word when the speaker pauses`() {
        val script = Script()
        repeat(4) {
            script.sentence(listOf("एक", "दो", "तीन", "चार", "पांच"))
            script.quiet(350)
        }

        val d = decoderFor(script)
        feed(d, script.pcm())
        val text = d.onEndpoint()
        assertFalse("a window cut a word: $text", text.contains('½'))
        assertEquals(script.expected(), words(text))
    }

    @Test
    fun `running text is offered while the speaker is still talking`() {
        val script = Script()
        script.sentence(listOf("आग", "लगी", "है", "यहाँ", "पर", "अभी"))
        script.quiet(100)

        val d = decoderFor(script)
        var partials = 0
        val pcm = script.pcm()
        val chunk = 320
        var at = 0
        while (at < pcm.size) {
            val end = minOf(pcm.size, at + chunk)
            if (d.onAudio(pcm.copyOfRange(at, end))) partials++
            at = end
        }
        assertTrue("the operator saw nothing while speaking", partials > 0)
        assertTrue("running text is words, not empty", d.runningText().isNotEmpty())
        assertEquals(script.expected(), words(d.onEndpoint()))
    }

    // ── a speaker who never pauses ───────────────────────────────────────────

    @Test
    fun `a speaker who never pauses is cut between words and every word is read once`() {
        val script = Script()
        val texts = (1..24).map { "w$it" }
        script.sentence(texts, wordMillis = 320, gapMillis = 60) // ~9 s, no pause
        script.quiet(100)

        val d = decoderFor(script)
        feed(d, script.pcm())
        assertTrue("a forced cut was needed", d.segmentsDecoded >= 1)
        val text = d.onEndpoint()
        assertFalse("a forced cut fell inside a word: $text", text.contains('½'))
        assertEquals(script.expected(), words(text))
    }

    /**
     * The hardest case for a forced cut: words with no dip between them at all, so the cut
     * lands inside one. Each side keeps context past the cut, and a word is owned by the
     * side its emission time falls on — so the word on the cut is read once, whole.
     */
    @Test
    fun `a word split by a forced cut is read once by the side that heard all of it`() {
        val script = Script()
        val texts = (1..20).map { "w$it" }
        script.sentence(texts, wordMillis = 400, gapMillis = 0) // 8 s, wall to wall
        script.quiet(100)

        val d = decoderFor(script)
        feed(d, script.pcm())
        val text = d.onEndpoint()
        assertEquals(script.expected(), words(text))
    }

    // ── the latency claim ────────────────────────────────────────────────────

    /**
     * The load-bearing invariant. However long someone talks, what is left to decode
     * when they stop is bounded by one segment, not by the utterance.
     */
    @Test
    fun `the tail left at the endpoint is bounded by one segment for any duration`() {
        for (utteranceMillis in 200..12_000 step 200) {
            val script = Script()
            script.say("x", utteranceMillis)
            val d = decoderFor(script)
            feed(d, script.pcm(), chunkMillis = 100)
            assertTrue(
                "after $utteranceMillis ms of unbroken speech the tail was " +
                    "${d.endpointDecodeMillis} ms, more than one segment",
                d.endpointDecodeMillis <= UtteranceDecoder.MAX_SEGMENT_MILLIS + 100,
            )
        }
    }

    @Test
    fun `a clause finished before the release costs the endpoint nothing`() {
        val script = Script()
        script.sentence(listOf("मदद", "चाहिए", "जल्दी"))
        script.quiet(240) // shorter than a pause, longer than the settle
        val d = decoderFor(script)
        feed(d, script.pcm())

        val decodesBefore = script.windows.size
        assertTrue("a provisional reading was made", d.provisionalDecodes > 0)
        assertEquals("nothing is left for the endpoint", 0, d.endpointDecodeMillis)
        assertEquals(script.expected(), words(d.onEndpoint()))
        assertEquals("the endpoint reused the reading rather than decoding again", decodesBefore, script.windows.size)
    }

    @Test
    fun `a pause between clauses leaves only the last clause for the endpoint`() {
        val script = Script()
        script.sentence(listOf("पहला", "वाक्य", "यहाँ", "है", "अभी", "भी"))
        script.quiet(400)
        script.sentence(listOf("दूसरा", "वाक्य"))
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertTrue(
            "the tail was ${d.endpointDecodeMillis} ms, more than the last clause",
            d.endpointDecodeMillis <= 2 * 320 + 60 + 2 * UtteranceDecoder.KEEP_QUIET_MILLIS + 40,
        )
        assertEquals(script.expected(), words(d.onEndpoint()))
    }

    // ── silence ──────────────────────────────────────────────────────────────

    @Test
    fun `silence is never sent to the model`() {
        val script = Script()
        script.quiet(3_000)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals("", d.onEndpoint())
        assertTrue("the model was asked about silence", script.windows.isEmpty())
    }

    @Test
    fun `a click is not a word`() {
        val script = Script()
        script.quiet(500)
        script.say("click", 40)
        script.quiet(500)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals("", d.onEndpoint())
        assertTrue(script.windows.isEmpty())
    }

    @Test
    fun `dead air before a sentence is not decoded with it`() {
        val script = Script()
        script.quiet(7_000)
        script.sentence(listOf("अब", "बोल", "रहा", "हूँ"))
        script.quiet(100)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals(script.expected(), words(d.onEndpoint()))
        val longest = script.windows.max() * 1000 / 16_000
        assertTrue(
            "the model was handed $longest ms for a 1.4 s sentence",
            longest <= 4 * 320 + 3 * 60 + 2 * UtteranceDecoder.KEEP_QUIET_MILLIS + 40,
        )
    }

    @Test
    fun `a loud opening is not mistaken for the noise floor and trimmed away`() {
        // The operator was already mid-word when the control went down: the very first
        // frames are speech, and there is no quiet before them to set the floor from.
        val script = Script()
        script.sentence(listOf("पहला", "शब्द", "यहाँ"))
        script.quiet(100)
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals(script.expected(), words(d.onEndpoint()))
    }

    // ── housekeeping ─────────────────────────────────────────────────────────

    @Test
    fun `state does not leak between utterances`() {
        val script = Script()
        script.sentence(listOf("एक", "दो"))
        script.quiet(400)
        script.sentence(listOf("तीन"))
        val d = decoderFor(script)
        feed(d, script.pcm())
        assertEquals(script.expected(), words(d.onEndpoint()))
        assertEquals(0, d.pendingMillis)
        assertEquals(0, d.segmentsDecoded)
        assertEquals("", d.runningText())

        val again = Script()
        again.sentence(listOf("चार"))
        again.quiet(100)
        val d2 = UtteranceDecoder(decode = again::decode)
        feed(d2, again.pcm())
        assertEquals("चार", words(d2.onEndpoint()))
    }

    @Test
    fun `a forced cut needs room for its context`() {
        try {
            UtteranceDecoder(maxSegmentMillis = 1_000, contextMillis = 600, decode = { Transcript.EMPTY })
            throw AssertionError("expected a rejection: both contexts would overlap the whole segment")
        } catch (expected: IllegalArgumentException) {
            // A segment shorter than twice its context could never advance.
        }
    }
}

class TranscriptTest {
    @Test
    fun `sentencepiece tokens are grouped into words at their first token's time`() {
        val t =
            Transcript.fromTokens(
                arrayOf("▁मदद", "▁चा", "हिए", "▁जल्", "दी"),
                floatArrayOf(0.40f, 0.84f, 0.92f, 1.20f, 1.28f),
            )
        assertEquals("मदद चाहिए जल्दी", t.text)
        assertEquals(listOf(0.40f, 0.84f, 1.20f), t.words.map { it.startSeconds })
    }

    @Test
    fun `the unknown token is dropped rather than written into a message`() {
        val t = Transcript.fromTokens(arrayOf("▁go", "<unk>", "▁now"), floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals("go now", t.text)
    }

    @Test
    fun `a continuation token with no word before it starts one`() {
        val t = Transcript.fromTokens(arrayOf("ार", "▁बात"), floatArrayOf(0.1f, 0.5f))
        assertEquals("ार बात", t.text)
        assertEquals(0.1f, t.words[0].startSeconds)
    }

    @Test
    fun `timestamps that do not match the tokens leave every word untimed, not mistimed`() {
        val t = Transcript.fromTokens(arrayOf("▁a", "▁b"), floatArrayOf(0.1f))
        assertEquals("a b", t.text)
        assertTrue(t.words.none { it.isTimed })
    }

    @Test
    fun `text alone becomes untimed words`() {
        val t = Transcript.fromText("  दो   लोग घायल ")
        assertEquals(listOf("दो", "लोग", "घायल"), t.words.map { it.text })
        assertTrue(t.words.none { it.isTimed })
    }
}

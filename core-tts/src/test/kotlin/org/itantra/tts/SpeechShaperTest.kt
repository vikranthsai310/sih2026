package org.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechShaperTest {
    private val shaper = SpeechShaper(fullStop = "।")

    private fun texts(text: String) = shaper.shape(text).map { it.text }

    @Test
    fun `a short message is one phrase, given the language's full stop`() {
        assertEquals(listOf("हमें मदद चाहिए।"), texts("हमें मदद चाहिए"))
    }

    @Test
    fun `a message that already ends a sentence is left as it is`() {
        assertEquals(listOf("Send the boat now."), SpeechShaper().shape("Send the boat now.").map { it.text })
        assertEquals(listOf("आग लगी है!"), texts("आग लगी है!"))
    }

    @Test
    fun `a comma keeps its clause, and the clause keeps its comma`() {
        assertEquals(
            listOf("हमें तुरंत मदद चाहिए,", "दो लोग घायल हैं।"),
            texts("हमें तुरंत मदद चाहिए, दो लोग घायल हैं"),
        )
    }

    @Test
    fun `a sentence end pauses longer than a clause end, and the last phrase not at all`() {
        val phrases = shaper.shape("हमें तुरंत मदद चाहिए, दो लोग घायल हैं। नाव भेजो जल्दी")
        assertEquals(3, phrases.size)
        assertEquals(SpeechShaper.CLAUSE_PAUSE_MILLIS, phrases[0].pauseAfterMillis)
        assertEquals(SpeechShaper.SENTENCE_PAUSE_MILLIS, phrases[1].pauseAfterMillis)
        assertEquals(0, phrases[2].pauseAfterMillis)
    }

    @Test
    fun `it splits on the danda`() {
        val out = texts("आग लगी है। तुरंत निकलो।")
        assertEquals(listOf("आग लगी है।", "तुरंत निकलो।"), out)
    }

    /** A voice ending a two-word fragment with a full stop is the robot sound. */
    @Test
    fun `a fragment too short to be a breath joins its neighbour`() {
        assertEquals(1, texts("ok, हमें तुरंत मदद चाहिए").size)
        assertEquals(listOf("हमें तुरंत मदद चाहिए, अभी।"), texts("हमें तुरंत मदद चाहिए, अभी"))
    }

    @Test
    fun `an unpunctuated run is not cut at twelve words`() {
        val twelve = "हमें तुरंत मदद चाहिए दो लोग घायल हैं और नाव भेजो जल्दी"
        assertEquals("twelve words are one breath", 1, texts(twelve).size)
    }

    @Test
    fun `a very long unpunctuated run is cut at a conjunction, with a comma so the voice does not stop`() {
        val long =
            "हमें तुरंत मदद चाहिए दो लोग घायल हैं यहाँ पर पानी बहुत तेज़ है और नाव भेजो जल्दी से " +
                "सेक्टर सत्रह की तरफ आओ अभी क्योंकि रास्ता बंद है सब लोग यहाँ रुके हैं"
        val out = texts(long)
        assertTrue("expected more than one phrase, got ${out.size}", out.size > 1)
        assertTrue("the cut phrase ends in a comma: ${out[0]}", out[0].endsWith(","))
        assertTrue(out.dropLast(1).all { it.split(' ').size <= SpeechShaper.MAX_WORDS })
        assertTrue("the last phrase ends the sentence", out.last().endsWith("।"))
    }

    @Test
    fun `stray spaces and doubled marks are tidied`() {
        assertEquals(listOf("मदद चाहिए,", "दो लोग घायल हैं।"), texts("  मदद   चाहिए ,, दो लोग  घायल हैं ।"))
    }

    @Test
    fun `rejoining the phrases recovers the words`() {
        val text = "हमें तुरंत मदद चाहिए, दो लोग घायल हैं। नाव भेजो"
        val rejoined = texts(text).joinToString(" ")
        assertEquals(text + "।", rejoined)
    }

    @Test
    fun `nothing to say produces no phrases`() {
        assertTrue(shaper.shape("").isEmpty())
        assertTrue(shaper.shape("   ").isEmpty())
        assertTrue(shaper.shape(", .").isEmpty())
    }
}

class AudioPolishTest {
    private fun tone(
        samples: Int,
        amplitude: Float,
    ) = FloatArray(samples) { amplitude * (if (it % 20 < 10) 1f else -1f) }

    @Test
    fun `a quiet phrase is brought up to speech level`() {
        val quiet = tone(22_050, 0.05f)
        val gain = AudioPolish.gainFor(quiet)
        assertTrue("expected a gain above one, got $gain", gain > 1f)
        assertEquals(AudioPolish.TARGET_RMS / 0.05f, gain, 0.01f)
    }

    @Test
    fun `a loud phrase is held below the ceiling rather than clipped`() {
        val loud = tone(22_050, 1.0f)
        val out = AudioPolish.polish(loud, 22_050)
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak $peak must not reach full scale", peak <= (AudioPolish.PEAK_CEILING * 32767).toInt() + 1)
    }

    @Test
    fun `silence is not amplified into hiss`() {
        val hiss = tone(22_050, 0.001f)
        assertEquals(AudioPolish.MAX_GAIN, AudioPolish.gainFor(hiss), 0.0001f)
    }

    @Test
    fun `each end fades from and to zero`() {
        val out = AudioPolish.polish(tone(22_050, 0.3f), 22_050)
        assertEquals(0, out.first().toInt())
        assertEquals(0, out.last().toInt())
        val fade = 22_050 * AudioPolish.FADE_MILLIS / 1000
        assertTrue(
            "inside the fade the signal is still rising",
            kotlin.math.abs(out[fade / 2].toInt()) < kotlin.math.abs(out[fade * 2].toInt()),
        )
    }

    @Test
    fun `silence has the length asked for`() {
        assertEquals(22_050 * 380 / 1000, AudioPolish.silence(380, 22_050).size)
        assertEquals(0, AudioPolish.silence(0, 22_050).size)
    }
}

class VoiceProfileTest {
    @Test
    fun `the voice's own tuning is read from its config`() {
        val english =
            """{"audio":{"sample_rate":22050},""" +
                """"inference":{"noise_scale":0.333,"length_scale":0.8,"noise_w":0.8},"num_speakers":1}"""
        val p = VoiceProfile.fromPiperConfig(english)
        assertEquals(0.333f, p.noiseScale, 0.0001f)
        assertEquals(0.8f, p.lengthScale, 0.0001f)
        assertEquals(0.8f, p.noiseScaleW, 0.0001f)
    }

    @Test
    fun `a config without tuning yields the defaults`() {
        val p = VoiceProfile.fromPiperConfig("""{"audio":{"sample_rate":22050}}""")
        assertEquals(VoiceProfile(), p)
    }

    @Test
    fun `nonsense in a config is refused in favour of the defaults`() {
        assertEquals(VoiceProfile(), VoiceProfile.fromPiperConfig("""{"inference":{"length_scale":40}}"""))
        assertEquals(VoiceProfile(), VoiceProfile.fromPiperConfig("not json"))
    }

    @Test
    fun `pacing multiplies the tempo and nothing else`() {
        val p = VoiceProfile(lengthScale = 0.8f).paced(1.05f)
        assertEquals(0.84f, p.lengthScale, 0.0001f)
        assertEquals(VoiceProfile.DEFAULT_NOISE_SCALE, p.noiseScale, 0f)
    }
}

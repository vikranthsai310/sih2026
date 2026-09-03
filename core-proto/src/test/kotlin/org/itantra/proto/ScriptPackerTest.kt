package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import kotlin.random.Random

class ScriptPackerTest {
    // ── the conformance requirement, PROTOCOL.md §14 ─────────────────────────

    /**
     * `unpack(pack(s)) == s` over a corpus containing **every code point in each
     * block**, ASCII, ZWJ/ZWNJ and surrogate pairs. This is the test that would have
     * caught the original specification being silently lossy (risk T-13).
     */
    @Test
    fun `round trip holds over every code point of every block plus ASCII and joiners`() {
        for (language in Language.entries) {
            val base = language.blockBase
            val sb = StringBuilder()

            // the whole 128-code-point block, not merely the ~70 in running text
            if (base != null) {
                for (cp in base until base + 128) {
                    if (Character.isValidCodePoint(cp)) sb.appendCodePoint(cp)
                }
            }
            // printable ASCII, digits and punctuation
            for (cp in 0x20..0x7E) sb.appendCodePoint(cp)
            // the joiners that sit outside every Indic block and change conjunct forming
            sb.append('‌').append('‍')
            // a code point beyond the basic multilingual plane
            sb.appendCodePoint(0x1F600)
            // the escape byte's own code point must survive
            sb.append('')

            val original = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
            val packed = ScriptPacker.pack(original, language)
            assertEquals(
                "round trip failed for ${language.code}",
                original,
                ScriptPacker.unpack(packed, language),
            )
        }
    }

    @Test
    fun `round trip holds for random strings drawn from the block and ASCII`() {
        val random = Random(20260903)
        for (language in Language.entries) {
            val base = language.blockBase ?: continue
            repeat(500) {
                val sb = StringBuilder()
                repeat(random.nextInt(1, 40)) {
                    when (random.nextInt(4)) {
                        0 -> sb.appendCodePoint(base + random.nextInt(128))
                        1 -> sb.appendCodePoint(random.nextInt(0x20, 0x7F))
                        2 -> sb.append(if (random.nextBoolean()) '‌' else '‍')
                        else -> sb.appendCodePoint(base + random.nextInt(128))
                    }
                }
                val original = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
                assertEquals(original, ScriptPacker.unpack(ScriptPacker.pack(original, language), language))
            }
        }
    }

    // ── the compression claim the whole project rests on ─────────────────────

    /**
     * The worked example in `docs/PROTOCOL.md` section 4.5: "हमें तुरंत मदद चाहिए" is
     * 54 bytes as UTF-8 and 20 packed.
     */
    @Test
    fun `the worked example from the specification packs as documented`() {
        val text = "हमें तुरंत मदद चाहिए"
        val utf8 = text.toByteArray(Charsets.UTF_8).size
        val packed = ScriptPacker.pack(text, Language.HINDI).size

        assertTrue("UTF-8 should be about 54 bytes, was $utf8", utf8 in 50..58)
        assertTrue("packed should be about 20 bytes, was $packed", packed in 18..22)
        assertTrue("packing must reduce the payload", packed < utf8)
    }

    @Test
    fun `packed text is never larger than UTF-8 for ordinary Indic running text`() {
        val samples =
            mapOf(
                Language.HINDI to "हमें तुरंत मदद चाहिए, दो लोग घायल हैं",
                Language.TAMIL to "எங்களுக்கு உடனடியாக உதவி தேவை",
                Language.BENGALI to "আমাদের এখনই সাহায্য দরকার",
                Language.TELUGU to "మాకు వెంటనే సహాయం కావాలి",
                Language.KANNADA to "ನಮಗೆ ತಕ್ಷಣ ಸಹಾಯ ಬೇಕು",
                Language.MALAYALAM to "ഞങ്ങൾക്ക് ഉടൻ സഹായം വേണം",
                Language.GUJARATI to "અમને તાત્કાલિક મદદ જોઈએ",
                Language.ODIA to "ଆମକୁ ତୁରନ୍ତ ସାହାଯ୍ୟ ଦରକାର",
                Language.MARATHI to "आम्हाला तात्काळ मदत हवी",
            )
        for ((language, text) in samples) {
            val utf8 = text.toByteArray(Charsets.UTF_8).size
            val packed = ScriptPacker.pack(text, language).size
            assertTrue(
                "${language.code}: packed $packed >= utf8 $utf8",
                packed < utf8,
            )
        }
    }

    // ── the escape rule ──────────────────────────────────────────────────────

    @Test
    fun `an out-of-block character costs four bytes and survives`() {
        // ZWNJ is outside the Devanagari block
        val packed = ScriptPacker.pack("‌", Language.HINDI)
        assertEquals(4, packed.size)
        assertEquals(0x1B.toByte(), packed[0])
        assertEquals("‌", ScriptPacker.unpack(packed, Language.HINDI))
    }

    @Test
    fun `the escape byte itself round trips`() {
        assertEquals("", ScriptPacker.unpack(ScriptPacker.pack("", Language.HINDI), Language.HINDI))
    }

    @Test
    fun `ASCII is one byte per character in every language`() {
        for (language in Language.entries) {
            assertEquals(9, ScriptPacker.pack("SECTOR 17", language).size)
        }
    }

    @Test
    fun `English packs as ASCII passthrough and is not worth packing`() {
        val text = "we need a boat, sector seventeen"
        assertEquals(text.length, ScriptPacker.pack(text, Language.ENGLISH).size)
        assertTrue(
            "plain ASCII gains nothing from packing, so the PACKED flag must stay clear",
            !ScriptPacker.isWorthPacking(text, Language.ENGLISH),
        )
    }

    @Test
    fun `Hindi and Marathi share the Devanagari table`() {
        val text = "मदद"
        assertTrue(
            ScriptPacker.pack(text, Language.HINDI).contentEquals(ScriptPacker.pack(text, Language.MARATHI)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a truncated escape sequence is rejected rather than read past the end`() {
        ScriptPacker.unpack(byteArrayOf(0x1B, 0x00, 0x20), Language.HINDI)
    }

    @Test
    fun `unpack never throws on arbitrary bytes for a language with a block`() {
        val random = Random(7)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt().toByte() }
            try {
                ScriptPacker.unpack(bytes, Language.HINDI)
            } catch (expected: IllegalArgumentException) {
                // a truncated or invalid escape is a documented rejection, not a crash
            }
        }
    }
}

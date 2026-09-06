package org.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normalisation regression suite.
 *
 * Correctness here is a **100 % target**, not a best effort, because every failure is
 * audible and every failure is trivially reproducible in front of a jury. Adding a
 * rule without adding its fixtures is a rejected review. `docs/TTS.md` section 1.
 */
class HindiNumeralsTest {
    @Test
    fun `single digits`() {
        assertEquals("शून्य", HindiNumerals.cardinal(0))
        assertEquals("एक", HindiNumerals.cardinal(1))
        assertEquals("पाँच", HindiNumerals.cardinal(5))
        assertEquals("नौ", HindiNumerals.cardinal(9))
    }

    @Test
    fun `the irregular teens and twenties`() {
        assertEquals("दस", HindiNumerals.cardinal(10))
        assertEquals("ग्यारह", HindiNumerals.cardinal(11))
        assertEquals("सत्रह", HindiNumerals.cardinal(17))
        assertEquals("उन्नीस", HindiNumerals.cardinal(19))
        assertEquals("बीस", HindiNumerals.cardinal(20))
        assertEquals("इक्कीस", HindiNumerals.cardinal(21))
    }

    /** Every value below a hundred is irregular, so every value is worth checking. */
    @Test
    fun `every value below one hundred is present and distinct`() {
        val words = (0..99).map { HindiNumerals.cardinal(it.toLong()) }
        assertEquals(100, words.size)
        assertEquals("no two numbers may share a word", 100, words.toSet().size)
        assertTrue("no entry may be blank", words.none { it.isBlank() })
    }

    @Test
    fun `round tens`() {
        assertEquals("तीस", HindiNumerals.cardinal(30))
        assertEquals("चालीस", HindiNumerals.cardinal(40))
        assertEquals("पचास", HindiNumerals.cardinal(50))
        assertEquals("साठ", HindiNumerals.cardinal(60))
        assertEquals("सत्तर", HindiNumerals.cardinal(70))
        assertEquals("अस्सी", HindiNumerals.cardinal(80))
        assertEquals("नब्बे", HindiNumerals.cardinal(90))
        assertEquals("निन्यानवे", HindiNumerals.cardinal(99))
    }

    @Test
    fun `hundreds compose`() {
        assertEquals("एक सौ", HindiNumerals.cardinal(100))
        assertEquals("दो सौ पचास", HindiNumerals.cardinal(250))
        assertEquals("नौ सौ निन्यानवे", HindiNumerals.cardinal(999))
    }

    /** The Indian scale, not the Western one: 100 000 is a lakh, not a hundred thousand. */
    @Test
    fun `the Indian scale is used`() {
        assertEquals("एक हज़ार", HindiNumerals.cardinal(1_000))
        assertEquals("एक लाख", HindiNumerals.cardinal(100_000))
        assertEquals("एक करोड़", HindiNumerals.cardinal(10_000_000))
    }

    @Test
    fun `digit-wise reading`() {
        assertEquals("एक एक दो", HindiNumerals.digitwise("112"))
        assertEquals("नौ नौ नौ", HindiNumerals.digitwise("999"))
        assertEquals("शून्य एक", HindiNumerals.digitwise("01"))
    }

    @Test
    fun `times name the part of day`() {
        assertEquals("दोपहर दो बजकर तीस मिनट", HindiNumerals.time(14, 30))
        assertEquals("सुबह नौ बजे", HindiNumerals.time(9, 0))
        assertEquals("रात ग्यारह बजकर पंद्रह मिनट", HindiNumerals.time(23, 15))
        assertEquals("शाम छह बजे", HindiNumerals.time(18, 0))
    }

    @Test
    fun `midnight and noon read as twelve`() {
        assertTrue(HindiNumerals.time(0, 0).contains("बारह"))
        assertTrue(HindiNumerals.time(12, 0).contains("बारह"))
    }
}

class TextNormaliserTest {
    private val n = HindiRules.normaliser()

    // ── the four worked examples from docs/TTS.md section 1 ──────────────────

    /** A number to dial is read digit-wise, never as a quantity. */
    @Test
    fun `112 is read digit-wise`() {
        assertEquals("एक एक दो", n.normalise("112"))
    }

    @Test
    fun `5 km is read as a quantity with the unit expanded`() {
        assertEquals("पाँच किलोमीटर", n.normalise("5 km"))
    }

    @Test
    fun `Sector 17 is read as a cardinal after the marker`() {
        assertEquals("सेक्टर सत्रह", n.normalise("सेक्टर 17"))
        assertEquals("Sector सत्रह", n.normalise("Sector 17"))
    }

    @Test
    fun `a time is read as a time`() {
        assertEquals("दोपहर दो बजकर तीस मिनट", n.normalise("14:30"))
    }

    // ── the quantity versus identifier rule ──────────────────────────────────

    @Test
    fun `a long digit run is an identifier, not a quantity`() {
        assertEquals("नौ आठ सात छह पाँच", n.normalise("98765"))
        assertEquals("एक दो तीन चार पाँच छह", n.normalise("123456"))
    }

    @Test
    fun `a two-digit number is a quantity`() {
        assertEquals("सत्रह", n.normalise("17"))
        assertEquals("निन्यानवे", n.normalise("99"))
    }

    @Test
    fun `a four-digit number is a quantity, not an identifier`() {
        assertEquals("दो हज़ार पच्चीस", n.normalise("2025"))
    }

    @Test
    fun `a unit beats the three-digit dialling rule`() {
        assertEquals("एक सौ बीस किलोमीटर", n.normalise("120 km"))
    }

    // ── whole operational sentences ──────────────────────────────────────────

    @Test
    fun `a distress sentence normalises end to end`() {
        assertEquals(
            "सेक्टर सत्रह में दो लोग घायल हैं",
            n.normalise("सेक्टर 17 में 2 लोग घायल हैं"),
        )
    }

    @Test
    fun `a sentence with a distance and a time`() {
        assertEquals(
            "पाँच किलोमीटर दूर दोपहर दो बजकर तीस मिनट पर",
            n.normalise("5 km दूर 14:30 पर"),
        )
    }

    @Test
    fun `text without digits is unchanged`() {
        val text = "हमें तुरंत मदद चाहिए"
        assertEquals(text, n.normalise(text))
    }

    @Test
    fun `empty and blank input are safe`() {
        assertEquals("", n.normalise(""))
        assertEquals("", n.normalise("   "))
    }

    @Test
    fun `whitespace is collapsed`() {
        assertEquals("दो लोग", n.normalise("2    लोग"))
    }

    /**
     * A fixture sweep. The suite target is at least sixty cases per language; this
     * generates the numeric spine of that and asserts none of it is left as digits,
     * because an unconverted digit is the audible failure.
     */
    @Test
    fun `no digit survives normalisation anywhere from zero to one thousand`() {
        for (value in 0..1_000) {
            val out = n.normalise("$value")
            assertTrue(
                "a digit survived normalisation of $value: '$out'",
                out.none { it.isDigit() },
            )
        }
    }

    @Test
    fun `no digit survives in sentences containing units and markers`() {
        val samples =
            listOf(
                "सेक्टर 4 में 12 लोग", "8 km आगे", "यूनिट 9 भेजो", "112 पर कॉल करो",
                "23:59 तक", "45 kg सामान", "चैनल 7 पर", "3 min में", "100 m दूर",
                "नोड 12 से 60 s पहले",
            )
        for (s in samples) {
            val out = n.normalise(s)
            assertTrue("a digit survived '$s' -> '$out'", out.none { it.isDigit() })
        }
    }
}

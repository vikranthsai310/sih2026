package org.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule-file loader, task **W7.2**.
 *
 * ## What this suite is really for
 *
 * Moving a language's rules from Kotlin into JSON is a refactor that can silently change
 * behaviour, and the failure is inaudible in code review: a numeral table off by one index
 * reads every value in the eighties as the wrong word, and nothing crashes. So the loader
 * is proved the only way that means anything — by asserting that the file reproduces the
 * week-3 implementation **exactly**, over every value and every time of day, rather than
 * over a handful of examples chosen by the person who wrote both sides.
 */
class NormaliseSpecTest {
    private val hindi = NormaliseSpec.parse(ruleFile("hi"))

    // ── the loader reproduces the code it replaced ───────────────────────────

    @Test
    fun `the Hindi file agrees with HindiNumerals on every value up to a lakh`() {
        for (value in 0L..100_000L) {
            assertEquals(
                "cardinal($value)",
                HindiNumerals.cardinal(value),
                hindi.numerals.cardinal(value),
            )
        }
    }

    @Test
    fun `the Hindi file agrees on digit-wise reading`() {
        for (run in listOf("0", "112", "108", "90210", "1234567890")) {
            assertEquals(run, HindiNumerals.digitwise(run), hindi.numerals.digitwise(run))
        }
    }

    /** Every minute of the day, because the period boundaries are where this goes wrong. */
    @Test
    fun `the Hindi file agrees on every time of day`() {
        val clock = hindi.clock.withNumerals(hindi.numerals)
        for (hour in 0..23) {
            for (minute in 0..59) {
                assertEquals(
                    "$hour:$minute",
                    HindiNumerals.time(hour, minute),
                    clock.render(hour, minute),
                )
            }
        }
    }

    @Test
    fun `the Hindi file agrees with HindiRules on whole sentences`() {
        val fromCode = HindiRules.normaliser()
        val fromFile = hindi.normaliser()
        for (sentence in SENTENCES) {
            assertEquals(sentence, fromCode.normalise(sentence), fromFile.normalise(sentence))
        }
    }

    // ── the format itself ────────────────────────────────────────────────────

    /**
     * The unit alternation is generated from the unit table, so a file cannot list a unit
     * its own pattern will never reach.
     */
    @Test
    fun `a unit added to the table is reachable without touching the pattern`() {
        val spec =
            hindi.copy(
                units = hindi.units + ("nm" to "नॉटिकल मील"),
                rules = hindi.rules,
            )
        assertEquals("सात नॉटिकल मील", spec.normaliser().normalise("7 nm"))
    }

    /** `km` must be tried before `m`, or every kilometre is read as a metre. */
    @Test
    fun `a longer unit symbol wins over a shorter one that prefixes it`() {
        assertEquals("पाँच किलोमीटर", hindi.normaliser().normalise("5 km"))
    }

    @Test
    fun `a rule file with a duplicate rule id is refused`() {
        val broken = ruleFile("hi").replace("\"id\": \"cardinal\"", "\"id\": \"time-24h\"")
        val thrown = runCatching { NormaliseSpec.parse(broken) }.exceptionOrNull()
        assertNotNull("a duplicate id must not load", thrown)
        assertTrue(thrown!!.message!!.contains("duplicate"))
    }

    /**
     * A hundred-entry table with ninety-nine entries reads every value above the gap as
     * the wrong word, and nothing throws. It must fail at load.
     */
    @Test
    fun `a table language missing a numeral is refused`() {
        val broken = ruleFile("hi").replace("\"शून्य\", \"एक\", \"दो\",", "\"शून्य\", \"एक\",")
        assertNotNull(runCatching { NormaliseSpec.parse(broken) }.exceptionOrNull())
    }

    @Test
    fun `an out-of-order scale table is refused`() {
        val broken =
            ruleFile("hi")
                .replace("\"value\": 10000000", "\"value\": 100")
                .replace("{ \"value\": 100, \"word\": \"सौ\" }", "{ \"value\": 10000000, \"word\": \"सौ\" }")
        assertNotNull(runCatching { NormaliseSpec.parse(broken) }.exceptionOrNull())
    }

    // ── composition, for the languages that do it ────────────────────────────

    @Test
    fun `a composed language builds the twenties upward from two words`() {
        val numerals =
            Numerals(
                kind = NumeralKind.COMPOSED,
                digits = List(10) { "d$it" },
                onesToNineteen = List(20) { "n$it" },
                tens = listOf("t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"),
                tensCombining = listOf("c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9"),
            )
        assertEquals("n19", numerals.cardinal(19))
        assertEquals("t2", numerals.cardinal(20))
        assertEquals("c2 n1", numerals.cardinal(21))
        assertEquals("c9 n9", numerals.cardinal(99))
    }

    /** Tamil's இருபது becomes இருபத்தி before a ones digit; absent that, the plain form. */
    @Test
    fun `a composed language without combining forms falls back to the plain tens`() {
        val numerals =
            Numerals(
                kind = NumeralKind.COMPOSED,
                digits = List(10) { "d$it" },
                onesToNineteen = List(20) { "n$it" },
                tens = listOf("t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"),
            )
        assertEquals("t2 n1", numerals.cardinal(21))
    }

    /**
     * A magnitude past the end of the scale table must still be spoken. Reading the digits
     * is wrong; leaving a numeral silent is worse, because the sentence then means
     * something different from what was said.
     */
    @Test
    fun `a value beyond the largest scale is read digit-wise rather than dropped`() {
        val noScales =
            Numerals(
                kind = NumeralKind.TABLE,
                digits = List(10) { "d$it" },
                words = List(100) { "w$it" },
            )
        assertEquals("d1 d2 d3", noScales.cardinal(123))
    }

    private companion object {
        val SENTENCES =
            listOf(
                "सेक्टर 17 में 3 घायल",
                "14:30 बजे 5 km दूर",
                "108 पर कॉल करें",
                "यूनिट 4 को 250 kg राशन चाहिए",
                "नोड 12 से 7 min में",
                "पहचान 4820193 दर्ज करें",
                "12:00 पर 100 % तैयार",
                "channel 9 पर 45 s प्रतीक्षा",
                "बिना किसी अंक के एक वाक्य",
                "0 घायल, 2 सुरक्षित",
            )

        fun ruleFile(lang: String): String {
            val candidates =
                listOf(File("../models/rules/normalise.$lang.json"), File("models/rules/normalise.$lang.json"))
            val file = candidates.firstOrNull { it.isFile }
            assertNotNull("normalise.$lang.json not found from ${File(".").absolutePath}", file)
            return file!!.readText()
        }
    }
}

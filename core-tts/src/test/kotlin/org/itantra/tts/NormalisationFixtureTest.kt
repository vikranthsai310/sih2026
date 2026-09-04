package org.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Normalisation fixtures for all ten languages, at **100 %**. Tasks **W7.2**, **W7.3**.
 *
 * ## Why the bar is a hundred per cent and not a target
 *
 * Every normalisation failure is *audible*, and every one is trivially reproducible in
 * front of a jury: type the number, press play, hear "one four three zero" where the
 * message said half past two. There is no partial credit available here, so the suite is
 * asserted at a hundred per cent rather than tracked as a rate. `docs/TTS.md` section 1.
 *
 * ## What a fixture file is, honestly
 *
 * `models/fixtures/normalise.<lang>.tsv` is a **regression lock**, not an oracle. The
 * expected column was generated from the rule file and read over by an engineer, which
 * proves that behaviour cannot change unnoticed — it does not prove that a native speaker
 * would say it that way. The rule files carry `reviewed: false` for exactly that reason,
 * and [`the unreviewed languages are the ones the file says they are`] keeps the two
 * statements from drifting apart.
 *
 * To regenerate after a deliberate rule change:
 *
 * ```
 * ./gradlew :core-tts:testDebugUnitTest --tests '*NormalisationFixtureTest*' \
 *     -Dfixtures.regenerate=true
 * ```
 *
 * Then **read the diff**. A regeneration that is not read is a suite that asserts nothing.
 *
 * ## The properties that are an oracle
 *
 * Two assertions here do not depend on anyone's judgement, and they are the ones that
 * catch a genuinely broken language:
 *
 * - **No digit survives.** A surviving digit is a synthesiser about to read a numeral as
 *   a glyph name or skip it silently, and it is a defect in every language equally.
 * - **Every value below a lakh, and every minute of the day, renders.** An index error in
 *   a hundred-entry table throws on exactly one value, and a fixture list of sixty cases
 *   will miss it.
 */
class NormalisationFixtureTest {
    // ── the suite proper ─────────────────────────────────────────────────────

    @Test
    fun `every language passes its fixtures at a hundred per cent`() {
        val failures = ArrayList<String>()

        for (lang in LANGUAGES) {
            val spec = specFor(lang)
            val normaliser = spec.normaliser()
            val file = fixtureFile(lang)

            if (REGENERATE) {
                file.parentFile.mkdirs()
                file.writeText(generate(lang, spec, normaliser))
                continue
            }

            assertTrue(
                "no fixture file for $lang; regenerate with -Dfixtures.regenerate=true",
                file.isFile,
            )
            val cases = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            assertTrue(
                "$lang ships ${cases.size} fixtures; the floor is $MINIMUM_CASES",
                cases.size >= MINIMUM_CASES,
            )

            for (line in cases) {
                val parts = line.split('\t')
                assertEquals("malformed fixture in $lang: $line", 2, parts.size)
                val actual = normaliser.normalise(parts[0])
                if (actual != parts[1]) failures += "$lang: '${parts[0]}' -> '$actual', expected '${parts[1]}'"
            }
        }

        assertTrue(
            "${failures.size} fixture failures:\n" + failures.joinToString("\n"),
            failures.isEmpty() || REGENERATE,
        )
    }

    /**
     * The assertion that needs no native speaker. A digit that reaches the phonemiser is
     * read as a glyph name or dropped, and either way the sentence no longer carries the
     * number that was spoken.
     */
    @Test
    fun `no digit survives normalisation in any language`() {
        for (lang in LANGUAGES) {
            val normaliser = specFor(lang).normaliser()
            for (case in inputsFor(lang)) {
                val out = normaliser.normalise(case)
                assertTrue(
                    "$lang left a digit in '$case' -> '$out'",
                    out.none { it.isDigit() },
                )
            }
        }
    }

    /**
     * A gap in a hundred-entry table throws on exactly one value, and sixty hand-listed
     * fixtures will not be the ones that find it.
     */
    @Test
    fun `every value below a lakh renders in every language`() {
        for (lang in LANGUAGES) {
            val numerals = specFor(lang).numerals
            for (value in 0L..100_000L) {
                val spoken = numerals.cardinal(value)
                assertTrue("$lang: cardinal($value) is empty", spoken.isNotBlank())
                assertTrue("$lang: cardinal($value) kept a digit: $spoken", spoken.none { it.isDigit() })
            }
        }
    }

    @Test
    fun `every minute of the day renders in every language`() {
        for (lang in LANGUAGES) {
            val spec = specFor(lang)
            val clock = spec.clock.withNumerals(spec.numerals)
            for (hour in 0..23) {
                for (minute in 0..59) {
                    val spoken = clock.render(hour, minute)
                    assertTrue("$lang: $hour:$minute is empty", spoken.isNotBlank())
                    assertTrue("$lang: $hour:$minute kept a digit: $spoken", spoken.none { it.isDigit() })
                }
            }
        }
    }

    // ── the manifest and the files must agree ────────────────────────────────

    @Test
    fun `every language in the protocol has a rule file`() {
        for (lang in LANGUAGES) {
            assertTrue("models/rules/normalise.$lang.json is missing", ruleFileFor(lang).isFile)
            assertEquals("the file names a different language", lang, specFor(lang).language)
        }
    }

    @Test
    fun `a callsign marker is declared in the language's own script`() {
        for (lang in LANGUAGES) {
            val markers = specFor(lang).callsignMarkers
            assertTrue("$lang declares no callsign markers", markers.isNotEmpty())
            if (lang == "en") continue
            assertTrue(
                "$lang lists only Latin markers; an operator says the word in their own language",
                markers.any { word -> word.any { it.code > 0x7F } },
            )
        }
    }

    /**
     * The `reviewed` flag is a claim about who has checked the numerals, and a claim that
     * can drift is worse than no claim. Hindi was checked in week 3 against a
     * hand-verified table; English is composed from twenty words a reader can check by
     * eye. Everything else is an engineer's draft until the intelligibility panel says
     * otherwise — task **W7.11**.
     */
    @Test
    fun `the unreviewed languages are the ones the files say they are`() {
        val reviewed = LANGUAGES.filter { specFor(it).reviewed }.toSet()
        assertEquals(
            "a language's reviewed flag changed; W7.11 is what clears one",
            setOf("hi", "en"),
            reviewed,
        )
        for (lang in LANGUAGES - reviewed) {
            assertNotNull("$lang claims no review and gives no reason", specFor(lang).note)
        }
    }

    // ── generation ───────────────────────────────────────────────────────────

    private fun generate(
        lang: String,
        spec: NormaliseSpec,
        normaliser: TextNormaliser,
    ): String =
        buildString {
            append("# Normalisation fixtures for ${spec.language}. Task W7.3.\n")
            append("# input <TAB> expected. Generated from models/rules/normalise.$lang.json;\n")
            append("# a regression lock, not an oracle -- see NormalisationFixtureTest.\n")
            for (case in inputsFor(lang)) {
                append(case).append('\t').append(normaliser.normalise(case)).append('\n')
            }
        }

    /** The same operational shapes in every language, with that language's marker word. */
    private fun inputsFor(lang: String): List<String> {
        val marker = specFor(lang).callsignMarkers.first()
        return TEMPLATES.map { it.replace("{marker}", marker) }
    }

    private companion object {
        val REGENERATE = System.getProperty("fixtures.regenerate") == "true"
        const val MINIMUM_CASES = 60

        /** Protocol order, `core-proto` `Language`. Ten, and the suite says so. */
        val LANGUAGES = listOf("en", "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or")

        /**
         * Sixty-eight cases across the categories `docs/TTS.md` section 1 names: bare
         * numbers, quantities with units, times, callsign contexts, dialled numbers, long
         * identifiers, coordinates and mixed sentences.
         */
        val TEMPLATES =
            listOf(
                // Bare numbers, including the boundaries of an irregular table.
                "0", "1", "7", "9", "10", "11", "17", "19", "20", "21",
                "40", "49", "50", "68", "70", "79", "88", "90", "99", "1000",
                "2500", "10000", "100000",
                // Quantities: the unit decides that these are numbers, not identifiers.
                "5 km", "250 kg", "12 m", "7 min", "45 s", "3 hr", "100 %", "2 l",
                "30 cm", "9 g", "5km", "20 KM",
                // Times, including both period boundaries and midnight.
                "00:00", "05:30", "09:00", "12:00", "13:45", "14:30", "17:05", "19:59",
                "21:15", "23:59",
                // Callsign context: a cardinal, because that is how an operator says it.
                "{marker} 1", "{marker} 7", "{marker} 17", "{marker} 40", "{marker} 99",
                "{marker} 1234", "sector 12", "unit 8",
                // Dialled numbers: three digits standing alone are read digit-wise.
                "108", "112", "100", "911", "102", "101",
                // Long identifiers: five or more digits are never a quantity anyone says.
                "4820193", "90210", "1234567890", "20260904", "99999", "100001",
                // Coordinates and mixed sentences.
                "20.29 85.82", "26.85 80.94",
                "{marker} 17 14:30", "14:30 5 km", "{marker} 4 250 kg", "0 2",
            )

        val specs = HashMap<String, NormaliseSpec>()

        fun specFor(lang: String): NormaliseSpec =
            specs.getOrPut(lang) { NormaliseSpec.parse(ruleFileFor(lang).readText()) }

        fun ruleFileFor(lang: String): File = repoFile("models/rules/normalise.$lang.json")

        fun fixtureFile(lang: String): File = repoFile("models/fixtures/normalise.$lang.tsv")

        /** Unit tests run from the module directory in Gradle and the repo root in an IDE. */
        fun repoFile(path: String): File {
            val candidates = listOf(File("../$path"), File(path))
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }
    }
}

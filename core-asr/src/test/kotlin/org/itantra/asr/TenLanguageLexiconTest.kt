package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every language ships a vocabulary, and every vocabulary obeys the same rules. Task
 * **W7.1**.
 *
 * ## Why this suite is not "the Hindi tests, ten times"
 *
 * A language pack that ships without its biasing lists still works — it recognises, it
 * transmits, it speaks. It is simply worse at the words that matter, and nothing in the
 * build says so. That is the failure this suite exists to catch: a language quietly
 * enabled in the manifest with none of the vocabulary that makes it usable, discovered by
 * a field team rather than by CI.
 *
 * The properties asserted here are the ones that must hold in **every** language, not the
 * ones that happen to hold in Hindi:
 *
 * - both files exist and parse;
 * - the negation floor outranks the domain ceiling, so risk **S-03** is mitigated in each
 *   language rather than only in the one that was written first;
 * - the lists are in the language's own script, which catches the commonest copy-paste
 *   error — a file duplicated from another language and never translated.
 */
class TenLanguageLexiconTest {
    @Test
    fun `every language in the protocol ships both lists`() {
        for (lang in LANGUAGES) {
            assertTrue("models/lexicon/alert-lexicon.$lang.txt is missing", domainFile(lang).isFile)
            assertTrue("models/lexicon/negation.$lang.txt is missing", negationFile(lang).isFile)
        }
    }

    @Test
    fun `every language's lists load and carry a usable number of terms`() {
        for (lang in LANGUAGES) {
            val lexicon = lexiconFor(lang)
            assertTrue(
                "$lang has only ${lexicon.size} biased terms",
                lexicon.size >= MINIMUM_TERMS,
            )
        }
    }

    /**
     * Risk **S-03** in every language, not only in the one written first. A negation
     * ranked below a domain term is a negation the decoder can be talked out of.
     */
    @Test
    fun `every language's negation floor outranks its domain ceiling`() {
        for (lang in LANGUAGES) {
            val merged = lexiconFor(lang)
            val domain = BiasingLexicon.parse(read(domainFile(lang)))
            val negation = BiasingLexicon.parse(read(negationFile(lang)))

            val worstNegation = negation.phrases().keys.minOf { merged.weightOf(it)!! }
            val bestDomain = domain.phrases().keys.maxOf { merged.weightOf(it)!! }
            assertTrue(
                "$lang: negation floor $worstNegation must exceed domain ceiling $bestDomain",
                worstNegation > bestDomain,
            )
        }
    }

    /**
     * A file copied from another language and never translated parses cleanly, weights
     * correctly and biases the decoder towards words nobody in this deployment will say.
     * Checking the script is the cheapest way to catch it.
     */
    @Test
    fun `every Indic list is written in its own script`() {
        for (lang in LANGUAGES) {
            val block = BLOCKS[lang] ?: continue // English is Latin by definition.
            for (file in listOf(domainFile(lang), negationFile(lang))) {
                val terms = BiasingLexicon.parse(read(file)).phrases().keys
                val inScript =
                    terms.count { term -> term.any { it.code in block } }
                assertTrue(
                    "${file.name}: only $inScript of ${terms.size} terms are in the $lang script",
                    inScript >= terms.size * 9 / 10,
                )
            }
        }
    }

    /**
     * Two languages sharing a script must not share a list. Hindi and Marathi are both
     * Devanagari, and a Marathi pack holding Hindi words is exactly the mistake the script
     * check above cannot see.
     */
    @Test
    fun `Hindi and Marathi do not ship the same Devanagari list`() {
        val hindi = BiasingLexicon.parse(read(domainFile("hi"))).phrases().keys
        val marathi = BiasingLexicon.parse(read(domainFile("mr"))).phrases().keys
        val shared = hindi.intersect(marathi).size.toDouble() / minOf(hindi.size, marathi.size)
        assertTrue(
            "the two Devanagari lists overlap ${(shared * 100).toInt()}%; one is probably a copy",
            shared < 0.5,
        )
    }

    /** The categories are the same everywhere, so the headings are a structural check. */
    @Test
    fun `every domain list is organised into the same categories`() {
        for (lang in LANGUAGES) {
            val headings =
                read(domainFile(lang))
                    .lineSequence()
                    .filter { it.startsWith("# ──") }
                    .count()
            assertEquals("$lang does not use the seven standard categories", 7, headings)
        }
    }

    private fun lexiconFor(lang: String) = BiasingLexicon.of(read(domainFile(lang)), read(negationFile(lang)))

    private companion object {
        const val MINIMUM_TERMS = 70

        val LANGUAGES = listOf("en", "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or")

        /** Unicode block per script, from `core-proto` `Language.blockBase`. */
        val BLOCKS =
            mapOf(
                "hi" to 0x0900..0x097F,
                "mr" to 0x0900..0x097F,
                "bn" to 0x0980..0x09FF,
                "gu" to 0x0A80..0x0AFF,
                "or" to 0x0B00..0x0B7F,
                "ta" to 0x0B80..0x0BFF,
                "te" to 0x0C00..0x0C7F,
                "kn" to 0x0C80..0x0CFF,
                "ml" to 0x0D00..0x0D7F,
            )

        fun domainFile(lang: String) = repoFile("models/lexicon/alert-lexicon.$lang.txt")

        fun negationFile(lang: String) = repoFile("models/lexicon/negation.$lang.txt")

        fun read(file: File): String = file.readText()

        fun repoFile(path: String): File {
            val candidates = listOf(File("../$path"), File(path))
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }
    }
}

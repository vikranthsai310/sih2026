package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped deployment profile, task **W7.4**.
 *
 * Loading the real `models/templates/templates.json` rather than a fixture is deliberate.
 * A profile that parses in a test and is incomplete on disk has tested nothing, and the
 * failure it hides — one language missing on one row — is silence on a handset in the
 * field rather than an error anyone sees.
 */
class TemplateProfileTest {
    private val profile = TemplateProfile.parse(profileFile().readText())
    private val table = profile.toTable()

    // ── the profile as shipped ───────────────────────────────────────────────

    @Test
    fun `the shipped profile carries every language on every template`() {
        for (entry in profile.templates) {
            assertEquals(
                "template ${entry.id} does not cover all ten languages",
                Language.entries.map { it.code }.toSet(),
                entry.text.keys,
            )
        }
    }

    @Test
    fun `every template renders in every language`() {
        for (id in table.ids) {
            for (language in Language.entries) {
                val rendered = table.render(id, language)
                assertNotNull("template $id has no ${language.code} text", rendered)
                assertTrue("template $id is blank in ${language.code}", rendered!!.isNotBlank())
            }
        }
    }

    /**
     * The cross-language claim, stated as a test rather than as a paragraph: a sentence
     * recognised in Hindi becomes a byte, and that byte becomes Tamil on the receiver.
     */
    @Test
    fun `a sentence spoken in Hindi is rendered in Tamil by the same byte`() {
        val id = table.match("चिकित्सा सहायता चाहिए", Language.HINDI, recogniserConfident = true)
        assertEquals(1, id)
        assertEquals("மருத்துவ உதவி தேவை", table.render(id!!, Language.TAMIL))
    }

    /**
     * Every language must match its **own** text, not just the language the profile was
     * drafted in. A translation that the matcher cannot find is a template that can be
     * received but never sent, and the operator whose language it is would never know.
     */
    @Test
    fun `every language can match its own text back to its own id`() {
        val failures = ArrayList<String>()
        for (entry in profile.templates) {
            for (language in Language.entries) {
                val text = entry.text.getValue(language.code)
                val matched = table.match(text, language, recogniserConfident = true)
                if (matched != entry.id) failures += "${language.code} ${entry.id} -> $matched"
            }
        }
        assertTrue("templates unreachable from their own language: $failures", failures.isEmpty())
    }

    // ── the safety properties ────────────────────────────────────────────────

    /**
     * Risk **S-03**, stated for the template path. "Fire, evacuate immediately" and "Do not
     * evacuate, stay where you are" are the two sentences in this profile that mean the
     * opposite of one another, they share their most distinctive word, and confusing them
     * kills people. Neither may match the other at any confidence.
     */
    @Test
    fun `the evacuate and do-not-evacuate templates cannot be confused`() {
        for (language in Language.entries) {
            val evacuate = table.render(EVACUATE, language)!!
            val doNot = table.render(DO_NOT_EVACUATE, language)!!

            assertTrue(
                "${language.code}: 'evacuate' scores ${table.scoreAgainst(evacuate, DO_NOT_EVACUATE, language)} " +
                    "against 'do not evacuate'",
                table.scoreAgainst(evacuate, DO_NOT_EVACUATE, language) < TemplateTable.ACCEPT_THRESHOLD,
            )
            assertTrue(
                "${language.code}: 'do not evacuate' scores " +
                    "${table.scoreAgainst(doNot, EVACUATE, language)} against 'evacuate'",
                table.scoreAgainst(doNot, EVACUATE, language) < TemplateTable.ACCEPT_THRESHOLD,
            )
            assertEquals(
                "${language.code}: the negated sentence matched the wrong template",
                DO_NOT_EVACUATE,
                table.match(doNot, language, recogniserConfident = true),
            )
        }
    }

    /**
     * A confident recognition of the wrong sentence and a hesitant recognition of the
     * right one are both unsafe. `docs/PROTOCOL.md` section 5.3.
     */
    @Test
    fun `a hesitant recogniser matches nothing however good the text is`() {
        assertNull(table.match("नाव भेजें", Language.HINDI, recogniserConfident = false))
    }

    /**
     * The digest is what stops two handsets disagreeing about a byte. It must depend on
     * the content of every language, not only the one the sender happens to use.
     */
    @Test
    fun `the digest changes when any language's text changes`() {
        val altered =
            profile.copy(
                templates =
                    profile.templates.map { entry ->
                        if (entry.id != 7) entry else entry.copy(text = entry.text + ("or" to "ବଦଳାଗଲା"))
                    },
            )
        assertNotEquals(
            table.digest.toList(),
            altered.toTable().digest.toList(),
        )
    }

    @Test
    fun `the digest is stable across loads of the same file`() {
        val again = TemplateProfile.parse(profileFile().readText()).toTable()
        assertEquals(table.digest.toList(), again.digest.toList())
        assertTrue(table.digestMatches(again.digest))
    }

    // ── what a malformed profile must not do ─────────────────────────────────

    /**
     * The assertion [TemplateProfile] exists for. A row missing one language renders as
     * silence on the handset that needed it — not as a garbled sentence someone queries.
     */
    @Test
    fun `a template missing one language is refused at load`() {
        val broken = profileFile().readText().replace("\"en\": \"Send a boat\",", "")
        val thrown = runCatching { TemplateProfile.parse(broken) }.exceptionOrNull()
        assertNotNull("an incomplete template must not load", thrown)
    }

    @Test
    fun `a template naming a language the protocol does not have is refused`() {
        val broken = profileFile().readText().replace("\"en\": \"Send a boat\"", "\"xx\": \"Send a boat\"")
        assertNotNull(runCatching { TemplateProfile.parse(broken) }.exceptionOrNull())
    }

    @Test
    fun `template id zero is refused because an empty payload is not a template`() {
        val broken = profileFile().readText().replace("\"id\": 7,", "\"id\": 0,")
        assertNotNull(runCatching { TemplateProfile.parse(broken) }.exceptionOrNull())
    }

    @Test
    fun `a duplicate template id is refused`() {
        val broken = profileFile().readText().replace("\"id\": 8,", "\"id\": 7,")
        assertNotNull(runCatching { TemplateProfile.parse(broken) }.exceptionOrNull())
    }

    private companion object {
        const val EVACUATE = 4
        const val DO_NOT_EVACUATE = 5

        fun profileFile(): File {
            val candidates =
                listOf(File("../models/templates/templates.json"), File("models/templates/templates.json"))
            val file = candidates.firstOrNull { it.isFile }
            assertNotNull("models/templates/templates.json not found from ${File(".").absolutePath}", file)
            return file!!
        }
    }
}

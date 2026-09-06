package org.itantra.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The language switch, task W4.6. */
class LanguageSwitchTest {
    private val manifest: Manifest by lazy {
        val candidates = listOf(File("../models/manifest.json"), File("models/manifest.json"))
        Manifest.parse(candidates.first { it.isFile }.readText())
    }

    private val switch by lazy { LanguageSwitch(manifest) }

    private val everythingInstalled: (String) -> Boolean = { true }

    private fun decide(
        from: String? = "hi",
        to: String = "bn",
        floorHeld: Boolean = false,
        installed: (String) -> Boolean = everythingInstalled,
        sharedHeld: Boolean = true,
    ) = switch.decide(from, to, floorHeld, installed, sharedHeld)

    // ── the ordering that matters ────────────────────────────────────────────

    /**
     * One model resident at a time. Loading before unloading needs both in memory at
     * once and is the most likely way to be killed by the out-of-memory reaper.
     */
    @Test
    fun `the outgoing pack is unloaded before the incoming one is loaded`() {
        val decision = decide(from = "hi", to = "bn")
        assertTrue(decision is LanguageSwitch.Decision.Proceed)
        decision as LanguageSwitch.Decision.Proceed
        assertEquals("hi", decision.unload)
        assertEquals("bn", decision.load)
    }

    @Test
    fun `the first start has nothing to unload`() {
        val decision = decide(from = null, to = "hi")
        assertTrue(decision is LanguageSwitch.Decision.Proceed)
        assertEquals(null, (decision as LanguageSwitch.Decision.Proceed).unload)
        assertEquals("hi", decision.load)
    }

    // ── the refusals ─────────────────────────────────────────────────────────

    /** Switching mid-transmission unloads the model decoding the speaker's own words. */
    @Test
    fun `a switch is refused while the floor is held`() {
        assertEquals(LanguageSwitch.Decision.FloorHeld, decide(floorHeld = true))
    }

    @Test
    fun `switching to the language already active does nothing`() {
        val decision = decide(from = "hi", to = "hi")
        assertTrue(decision is LanguageSwitch.Decision.AlreadyActive)
    }

    /** Even while transmitting: a no-op must not report "busy". */
    @Test
    fun `a no-op switch is reported as such even while the floor is held`() {
        val decision = decide(from = "hi", to = "hi", floorHeld = true)
        assertTrue(decision is LanguageSwitch.Decision.AlreadyActive)
    }

    @Test
    fun `an unknown language is refused`() {
        val decision = decide(to = "xx")
        assertTrue(decision is LanguageSwitch.Decision.NotInManifest)
    }

    @Test
    fun `an uninstalled pack offers its download size rather than switching`() {
        val decision = decide(to = "bn", installed = { it != "bn" })
        assertTrue(decision is LanguageSwitch.Decision.NotInstalled)
        decision as LanguageSwitch.Decision.NotInstalled
        assertTrue("a download size must be offered", decision.downloadBytes > 0)
    }

    /**
     * Reported before the floor check, so a device that simply lacks the language says
     * so rather than blaming a transmission that is not the real problem.
     */
    @Test
    fun `a missing pack is reported ahead of a held floor`() {
        val decision = decide(to = "bn", floorHeld = true, installed = { false })
        assertTrue(decision is LanguageSwitch.Decision.NotInstalled)
    }

    /**
     * There is no shared model to be missing any more, so holding it or not cannot change
     * the figure. This test used to assert the opposite — that the absent 120 MB encoder
     * doubled the first download — against a manifest block describing an artefact that was
     * never published. `shared` is null in the shipped manifest as of 2026-09-06.
     */
    @Test
    fun `the download size does not depend on a shared model that does not exist`() {
        val withShared = decide(to = "bn", sharedHeld = true, installed = { false })
        val withoutShared = decide(to = "bn", sharedHeld = false, installed = { false })

        val a = (withShared as LanguageSwitch.Decision.NotInstalled).downloadBytes
        val b = (withoutShared as LanguageSwitch.Decision.NotInstalled).downloadBytes
        assertEquals("no shared model is declared, so nothing is added for it", a, b)
    }

    /**
     * The figure must be the recogniser plus the voice, and dominated by the recogniser.
     * Before `asr` was added to the schema it was the vocabulary plus the voice, and the
     * ~197 MB that actually dominates a language download was not in it at all.
     */
    @Test
    fun `the download size is dominated by the recogniser`() {
        val decision = decide(to = "bn", installed = { false }) as LanguageSwitch.Decision.NotInstalled
        val pack = manifest.pack("bn")!!
        val asr = pack.asr!!.bytes
        assertTrue("the recogniser must be the bulk of it", asr > decision.downloadBytes / 2)
        assertEquals(asr + pack.tts!!.bytes, decision.downloadBytes)
        assertTrue("bundled lexicon must not be counted", decision.downloadBytes > pack.vocabulary.bytes * 100)
    }

    // ── what the interface offers ────────────────────────────────────────────

    @Test
    fun `every language in the manifest is offered`() {
        for (lang in listOf("en", "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or")) {
            assertTrue("$lang must be selectable", switch.isOffered(lang))
        }
        assertFalse(switch.isOffered("xx"))
    }

    /**
     * Three languages have no permissively licensed voice in any family. They are still
     * offered: they recognise and display, they just cannot speak. Hiding them would
     * remove most of the country.
     *
     * Gujarati left this set on 2026-09-06, when the Mimic 3 CMU Indic voice was added.
     */
    @Test
    fun `languages with no voice are still offered, and named`() {
        assertEquals(setOf("ta", "kn", "or"), switch.silentLanguages().toSet())
        for (lang in switch.silentLanguages()) {
            assertTrue(switch.isOffered(lang))
        }
    }

    @Test
    fun `switching to a language that cannot speak is still permitted`() {
        val decision = decide(from = "hi", to = "ta")
        assertTrue(decision is LanguageSwitch.Decision.Proceed)
    }
}

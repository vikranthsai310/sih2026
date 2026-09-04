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

    @Test
    fun `a missing shared model is counted in the download`() {
        val withShared = decide(to = "bn", sharedHeld = true, installed = { false })
        val withoutShared = decide(to = "bn", sharedHeld = false, installed = { false })

        val a = (withShared as LanguageSwitch.Decision.NotInstalled).downloadBytes
        val b = (withoutShared as LanguageSwitch.Decision.NotInstalled).downloadBytes
        assertTrue("the 120 MB shared model must dominate the first download", b > a * 2)
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
     * Four languages have no Piper voice. They are still offered: they recognise and
     * display, they just cannot speak. Hiding them would remove most of the country.
     */
    @Test
    fun `languages with no voice are still offered, and named`() {
        assertEquals(setOf("ta", "gu", "kn", "or"), switch.silentLanguages().toSet())
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

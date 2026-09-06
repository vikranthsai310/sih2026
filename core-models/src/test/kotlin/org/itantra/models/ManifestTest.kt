package org.itantra.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest, task W4.4.
 *
 * The shape is the point. The design document assumed ten independent acoustic models;
 * the published IndicConformer is one multilingual model, so the manifest carries a
 * single shared entry. Ten copies of a 120 MB model would be 1.2 GB of downloads for
 * something a device needs once — risk T-17.
 */
class ManifestTest {
    /** All zeros: a placeholder, and the manifest must refuse it. */
    private val zero = "0".repeat(64)

    /** A syntactically valid digest, so fixtures exercise the parse rather than the guard. */
    private val hash = "9f".repeat(32)

    private fun manifestText(
        version: Int = 1,
        packs: String = hindiPack(),
    ) = """
        {
          "manifestVersion": $version,
          "shared": {
            "family": "IndicConformer",
            "licence": "Permissive",
            "files": ["encoder.int8.onnx"],
            "bytes": 125829120,
            "sha256": "$hash"
          },
          "packs": [$packs]
        }
        """.trimIndent()

    private fun hindiPack(
        lang: String = "hi",
        index: Int = 1,
        tts: String = """{
            "family": "Piper", "licence": "MIT", "files": ["v.onnx"],
            "bytes": 63963136, "sha256": "$hash", "sampleRate": 22050 }""",
        low: Double = -1.8,
        high: Double = -0.6,
    ) = """
        {
          "lang": "$lang", "index": $index, "displayName": "x", "script": "Devanagari",
          "blockBase": "U+0900",
          "vocabulary": { "files": ["tokens.txt"], "bytes": 262144, "sha256": "$hash" },
          "tts": $tts,
          "rules": { "files": ["normalise.json"], "version": 3 },
          "confidenceLow": $low, "confidenceHigh": $high
        }
        """.trimIndent()

    private fun parse(text: String) = Manifest.parse(text)

    // ── shape ────────────────────────────────────────────────────────────────

    /**
     * The schema still *supports* a shared encoder, and this proves the parse. What ships
     * declares `null` — see `the shipped manifest declares no shared model` below — because
     * the artefact was never published in a form sherpa-onnx can load.
     */
    @Test
    fun `a shared acoustic model parses when one is declared`() {
        val m = parse(manifestText())
        assertEquals("IndicConformer", m.shared?.family)
        assertEquals(125_829_120L, m.shared?.bytes)
    }

    @Test
    fun `a language pack carries its vocabulary, voice and rules`() {
        val pack = parse(manifestText()).pack("hi")!!
        assertEquals(1, pack.index)
        assertEquals(262_144L, pack.vocabulary.bytes)
        assertEquals(22_050, pack.tts!!.sampleRate)
        assertEquals(3, pack.rules.version)
    }

    @Test
    fun `an unknown language is absent rather than an error`() {
        assertNull(parse(manifestText()).pack("xx"))
    }

    /**
     * The saving the shared model buys. Adding a second language costs its vocabulary
     * and voice only — not another 120 MB.
     */
    @Test
    fun `the shared model is counted once, not once per language`() {
        val m = parse(manifestText())
        val first = m.downloadBytes("hi", sharedAlreadyHeld = false)
        val second = m.downloadBytes("hi", sharedAlreadyHeld = true)

        // The vocabulary is no longer in this figure: those files ship inside the APK, so
        // they are not part of any download. This fixture declares no `asr`, so what is
        // left is the shared model and the voice.
        assertEquals(125_829_120L + 63_963_136L, first)
        assertEquals(63_963_136L, second)
        assertTrue("the second language must be far cheaper", second < first / 2)
    }

    // ── a manifest that cannot be trusted must not half-load ─────────────────

    @Test
    fun `a future manifest version is refused`() {
        try {
            parse(manifestText(version = 2))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("understands 1"))
        }
    }

    @Test
    fun `two packs for one language are refused`() {
        try {
            parse(manifestText(packs = hindiPack() + "," + hindiPack()))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("duplicate"))
        }
    }

    /**
     * A placeholder hash must fail while parsing the manifest, not at install time. By
     * then the bytes are on disk and the failure looks like a corrupt download rather
     * than a bad manifest.
     */
    @Test
    fun `a hash that is not a SHA-256 is refused`() {
        for (bad in listOf("\"\"", "\"abc\"", "\"" + "0".repeat(63) + "\"", "\"" + "Z".repeat(64) + "\"")) {
            val text = manifestText().replace("\"$hash\"", bad)
            try {
                parse(text)
                throw AssertionError("hash $bad should have been refused")
            } catch (expected: IllegalArgumentException) {
                // A hash of the wrong shape cannot be a SHA-256.
            }
        }
    }

    @Test
    fun `an uppercase hash is refused, because comparison is exact`() {
        val text = manifestText().replace(hash, "A".repeat(64))
        try {
            parse(text)
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("lowercase"))
        }
    }

    @Test
    fun `a language index that does not fit the wire field is refused`() {
        try {
            parse(manifestText(packs = hindiPack(index = 16)))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("4-bit"))
        }
    }

    @Test
    fun `inverted confidence thresholds are refused`() {
        try {
            parse(manifestText(packs = hindiPack(low = -0.6, high = -1.8)))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("confidenceLow"))
        }
    }

    /**
     * The guard's comment always claimed a placeholder "must fail here rather than at
     * install time", and for months it did not: sixty-four zeros are sixty-four lowercase
     * hex characters and passed the length-and-alphabet check. Seventeen of them were in
     * the shipped manifest.
     */
    @Test
    fun `an all-zero hash is refused as the placeholder it is`() {
        val text = manifestText().replace(hash, zero)
        val failure = runCatching { parse(text) }.exceptionOrNull()
        assertNotNull("an all-zero hash must not parse", failure)
        assertTrue(
            "the message must name the cause: ${failure?.message}",
            failure?.message?.contains("placeholder") == true,
        )
    }

    @Test
    fun `a zero-byte artefact is refused`() {
        val text = manifestText().replace("\"bytes\": 262144", "\"bytes\": 0")
        try {
            parse(text)
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("0 bytes"))
        }
    }

    // ── W4.16: thresholds live in the manifest, not in code ──────────────────

    @Test
    fun `confidence thresholds come from the manifest`() {
        val pack = parse(manifestText(packs = hindiPack(low = -2.4, high = -0.9))).pack("hi")!!
        assertEquals(-2.4, pack.confidenceLow, 1e-9)
        assertEquals(-0.9, pack.confidenceHigh, 1e-9)
    }

    // ── a language with no voice ─────────────────────────────────────────────

    /**
     * Three of the ten languages have no voice in any permissively licensed family —
     * Tamil, Kannada and Odia. The manifest must be able to say so rather than pretending
     * otherwise, and such a pack is still useful: it recognises and it displays, it just
     * cannot speak.
     */
    @Test
    fun `a pack with no voice parses and reports that it cannot speak`() {
        val pack = parse(manifestText(packs = hindiPack(lang = "ta", index = 5, tts = "null"))).pack("ta")!!
        assertNull(pack.tts)
        assertFalse(pack.canSpeak)
        // No voice and, in this fixture, no recogniser either — so there is nothing to
        // fetch. The vocabulary is not counted: it is bundled in the APK.
        assertEquals("nothing to download", 0L, pack.totalBytes)
    }

    @Test
    fun `a pack with a voice reports that it can speak`() {
        assertTrue(parse(manifestText()).pack("hi")!!.canSpeak)
    }
}

/** The manifest that actually ships is held to the same rules as any other. */
class ShippedManifestTest {
    private fun load(): Manifest {
        // The test runs from the module directory; the manifest lives at the repo root.
        val candidates =
            listOf(File("../models/manifest.json"), File("models/manifest.json"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("models/manifest.json not found from ${File(".").absolutePath}", file)
        return Manifest.parse(file!!.readText())
    }

    @Test
    fun `the shipped manifest parses`() {
        assertEquals(1, load().manifestVersion)
    }

    @Test
    fun `it covers all ten languages exactly once`() {
        val langs = load().packs.map { it.lang }.toSet()
        assertEquals(
            setOf("en", "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or"),
            langs,
        )
    }

    /**
     * The language index in the manifest is the value that goes into the 4-bit `LANG`
     * field on the wire. If it disagrees with `Language`, two handsets decode the same
     * frame as different languages.
     */
    @Test
    fun `every index matches the wire encoding in core-proto`() {
        val expected =
            mapOf(
                "en" to 0, "hi" to 1, "bn" to 2, "mr" to 3, "te" to 4,
                "ta" to 5, "gu" to 6, "kn" to 7, "ml" to 8, "or" to 9,
            )
        for (pack in load().packs) {
            assertEquals("${pack.lang} index", expected[pack.lang], pack.index)
        }
    }

    /**
     * Verified 2026-09-04 against the `rhasspy/piper-voices` tree: it holds no Tamil,
     * Gujarati, Kannada or Odia directory. **Narrowed 2026-09-06:** Piper is not the only
     * permissively licensed family. sherpa-onnx also publishes a Mimic 3 VITS voice for
     * Gujarati, trained on CMU Indic, whose licence grants use "for any purpose ...
     * without fee" — so Gujarati speaks and the silent set is three, not four.
     *
     * Tamil, Kannada and Odia were searched for across every family sherpa-onnx packages
     * (VITS, Piper, Mimic 3, Coqui, Matcha, Kokoro — 642 published artefacts) and none
     * exists. The manifest must record that rather than name voices that cannot be
     * downloaded — risk T-05.
     */
    @Test
    fun `the three languages with no permissive voice declare none`() {
        val silent = load().packs.filter { !it.canSpeak }.map { it.lang }.toSet()
        assertEquals(setOf("ta", "kn", "or"), silent)
    }

    /** Gujarati is not a Piper voice, so nothing may assume every voice is one. */
    @Test
    fun `Gujarati declares a voice from outside the Piper family`() {
        val voice = load().pack("gu")?.tts
        assertNotNull("Gujarati must declare a voice", voice)
        assertTrue("Gujarati voice must not claim to be Piper", !voice!!.family.contains("Piper"))
        assertTrue("Gujarati voice must name its licence", voice.licence.isNotBlank())
    }

    @Test
    fun `every declared voice names its licence`() {
        for (pack in load().packs) {
            val voice = pack.tts ?: continue
            assertTrue("${pack.lang} voice has no licence", voice.licence.isNotBlank())
        }
    }
}

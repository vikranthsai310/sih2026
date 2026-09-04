package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The noise mixer, task W4.9.
 *
 * Two properties matter: the mix actually achieves the SNR it claims, and it is
 * reproducible. Without the first the noise-robustness figures are decorative; without
 * the second, re-running the benchmark after a model change measures the model and a
 * different noise draw at once.
 */
class NoiseMixerTest {
    private val mixer = NoiseMixer()

    /** A quiet, speech-like signal, well below full scale so a mix has headroom. */
    private fun speech(samples: Int = 16_000): ShortArray =
        ShortArray(samples) {
            (4_000 * Math.sin(2 * Math.PI * 200 * it / 16_000.0)).toInt().toShort()
        }

    // ── the mix achieves the ratio it claims ─────────────────────────────────

    @Test
    fun `the measured SNR matches the requested SNR`() {
        val clean = speech()
        for (snr in listOf(20.0, 10.0, 5.0)) {
            val noise = mixer.generate(NoiseMixer.Noise.WIND, clean.size)
            val mixed = mixer.mix(clean, noise, snr)
            val measured = NoiseMixer.measureSnrDb(clean, mixed)
            assertEquals("requested $snr dB", snr, measured, 0.5)
        }
    }

    @Test
    fun `every noise kind mixes to the requested ratio`() {
        val clean = speech()
        for (kind in NoiseMixer.Noise.entries) {
            val noise = mixer.generate(kind, clean.size)
            val mixed = mixer.mix(clean, noise, 10.0)
            assertEquals("$kind", 10.0, NoiseMixer.measureSnrDb(clean, mixed), 0.5)
        }
    }

    @Test
    fun `a lower SNR really is noisier`() {
        val clean = speech()
        val noise = mixer.generate(NoiseMixer.Noise.CROWD, clean.size)
        val gentle = NoiseMixer.rms(mixer.mix(clean, noise, 20.0).minus(clean))
        val harsh = NoiseMixer.rms(mixer.mix(clean, noise, 5.0).minus(clean))
        assertTrue("5 dB must add more noise than 20 dB", harsh > gentle)
    }

    @Test
    fun `the clean signal is not modified`() {
        val clean = speech(64)
        val before = clean.toList()
        mixer.mix(clean, mixer.generate(NoiseMixer.Noise.WIND, 64), 5.0)
        assertEquals(before, clean.toList())
    }

    // ── reproducibility ──────────────────────────────────────────────────────

    @Test
    fun `the same seed produces the same noise`() {
        val a = NoiseMixer(seed = 26_173).generate(NoiseMixer.Noise.CROWD, 4_000)
        val b = NoiseMixer(seed = 26_173).generate(NoiseMixer.Noise.CROWD, 4_000)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `a different seed produces different noise`() {
        val a = NoiseMixer(seed = 1).generate(NoiseMixer.Noise.CROWD, 4_000)
        val b = NoiseMixer(seed = 2).generate(NoiseMixer.Noise.CROWD, 4_000)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `the whole mix is reproducible end to end`() {
        val clean = speech()
        val one = NoiseMixer(seed = 7).let { it.mix(clean, it.generate(NoiseMixer.Noise.ENGINE, clean.size), 10.0) }
        val two = NoiseMixer(seed = 7).let { it.mix(clean, it.generate(NoiseMixer.Noise.ENGINE, clean.size), 10.0) }
        assertEquals(one.toList(), two.toList())
    }

    @Test
    fun `the noise kinds differ from one another`() {
        val kinds = NoiseMixer.Noise.entries.map { mixer.generate(it, 2_000).toList() }
        assertEquals("each kind must be distinct", kinds.size, kinds.toSet().size)
    }

    // ── boundaries ───────────────────────────────────────────────────────────

    @Test
    fun `noise shorter than the speech is tiled rather than truncating the speech`() {
        val clean = speech(16_000)
        val shortNoise = mixer.generate(NoiseMixer.Noise.WIND, 1_000)
        val mixed = mixer.mix(clean, shortNoise, 10.0)
        assertEquals("the output must be as long as the speech", clean.size, mixed.size)
    }

    @Test
    fun `silence cannot be mixed to a stated ratio, so it is returned unchanged`() {
        val silence = ShortArray(100)
        val noise = mixer.generate(NoiseMixer.Noise.WIND, 100)
        assertEquals(silence.toList(), mixer.mix(silence, noise, 10.0).toList())
    }

    @Test
    fun `clipping is reported rather than hidden`() {
        // A signal near full scale, mixed at a punishing ratio, must clip.
        val loud = ShortArray(16_000) { (30_000 * Math.sin(2 * Math.PI * 200 * it / 16_000.0)).toInt().toShort() }
        val noise = mixer.generate(NoiseMixer.Noise.SIREN, loud.size)
        mixer.mix(loud, noise, 0.0)
        assertTrue("clipping must be counted", mixer.lastClippedSamples > 0)
    }

    @Test
    fun `a well-behaved mix does not clip`() {
        val clean = speech()
        mixer.mix(clean, mixer.generate(NoiseMixer.Noise.WIND, clean.size), 20.0)
        assertEquals(0, mixer.lastClippedSamples)
    }

    @Test
    fun `empty input is refused`() {
        for (call in listOf<() -> Unit>(
            { mixer.mix(ShortArray(0), ShortArray(10), 10.0) },
            { mixer.mix(ShortArray(10), ShortArray(0), 10.0) },
        )) {
            try {
                call()
                throw AssertionError("expected a refusal")
            } catch (expected: IllegalArgumentException) {
                // Nothing to mix.
            }
        }
    }

    @Test
    fun `the reported SNR levels are the ones the criterion names`() {
        assertEquals(listOf(20.0, 10.0, 5.0), NoiseMixer.SNR_LEVELS)
    }

    private fun ShortArray.minus(other: ShortArray) = ShortArray(size) { (this[it] - other[it]).toInt().toShort() }
}

class ScorecardTest {
    private fun row(
        lang: String = "hi",
        mos: Double? = 3.9,
        panel: Int? = 15,
        werClean: Double? = 0.082,
    ) = ScorecardRow(
        lang = lang,
        model = "IndicConformer-int8",
        modelBytes = 125_829_120,
        voiceBytes = 63_963_136,
        werClean = werClean,
        werSnr20 = 0.104,
        werSnr10 = 0.171,
        werSnr5 = 0.283,
        cterBiased = 0.031,
        cterUnbiased = 0.048,
        mos = mos,
        mosPanelN = panel,
        rtfAsr = 0.264,
        rtfTts = 0.19,
        device = "Redmi 13C",
        build = "abc1234",
        soakMinutes = 30,
        date = "2026-09-04",
    )

    private fun rowsOf(vararg rows: ScorecardRow): List<String> {
        val out = StringBuilder()
        val writer = ScorecardWriter(out)
        rows.forEach { writer.write(it) }
        return out.toString().trim().lines()
    }

    @Test
    fun `the header matches the schema`() {
        val header = rowsOf(row()).first()
        assertTrue(header.startsWith("lang,model,model_bytes,voice_bytes,wer_clean"))
        assertTrue(header.endsWith("device,build,soak_minutes,date,noise_seed"))
    }

    @Test
    fun `every row has the schema width`() {
        val lines = rowsOf(row("hi"), row("bn"), row("ta"))
        val width = lines.first().split(",").size
        for (line in lines.drop(1)) assertEquals(width, line.split(",").size)
    }

    @Test
    fun `error rates are written as percentages to one decimal`() {
        val fields = rowsOf(row())[1].split(",")
        assertEquals("wer_clean", "8.2", fields[4])
        assertEquals("wer_snr5", "28.3", fields[7])
    }

    @Test
    fun `real-time factors keep three decimals`() {
        val fields = rowsOf(row())[1].split(",")
        assertEquals("0.264", fields[12])
    }

    /**
     * A zero WER means perfect recognition; an unmeasured WER means nothing at all.
     * A results file must never confuse the two.
     */
    @Test
    fun `an unmeasured figure is empty, not zero`() {
        val fields = rowsOf(row(werClean = null))[1].split(",")
        assertEquals("", fields[4])
    }

    @Test
    fun `a perfect score is written as zero, not as absent`() {
        val fields = rowsOf(row(werClean = 0.0))[1].split(",")
        assertEquals("0.0", fields[4])
    }

    /** A MOS quoted without a panel size is not a measurement. */
    @Test
    fun `a mean opinion score without a panel size is refused`() {
        try {
            row(mos = 4.1, panel = null)
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("panel size"))
        }
    }

    /** A scorecard that does not name its hardware is not evidence. */
    @Test
    fun `a row must name its device and build`() {
        for (bad in listOf<() -> Unit>(
            { row().copy(device = "") },
            { row().copy(build = "  ") },
        )) {
            try {
                bad()
                throw AssertionError("expected a refusal")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("must name"))
            }
        }
    }

    @Test
    fun `the noise seed is recorded so a run can be reproduced`() {
        val fields = rowsOf(row())[1].split(",")
        assertEquals(NoiseMixer.DEFAULT_SEED.toString(), fields.last())
    }

    @Test
    fun `completeness is reported honestly`() {
        assertTrue(row().isComplete)
        assertFalseCompleteness(row(werClean = null))
    }

    private fun assertFalseCompleteness(r: ScorecardRow) {
        assertTrue("a row missing wer_clean is not complete", !r.isComplete)
    }
}

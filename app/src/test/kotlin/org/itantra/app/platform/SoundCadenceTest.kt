package org.itantra.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rate and pitch of the two sounds against proximity: geometric, bounded, monotonic. */
class SoundCadenceTest {
    @Test
    fun `the siren spans its range and is clamped outside it`() {
        assertEquals(1_400L, LocateSiren.gapFor(0f))
        assertEquals(90L, LocateSiren.gapFor(1f))
        assertEquals(1_400L, LocateSiren.gapFor(-1f))
        assertEquals(90L, LocateSiren.gapFor(2f))
        assertEquals(620f, LocateSiren.hzFor(0f), 0.01f)
        assertEquals(1_480f, LocateSiren.hzFor(1f), 0.01f)
    }

    @Test
    fun `halfway in proximity is the geometric middle, not the arithmetic one`() {
        // sqrt(1400 * 90) = 355: a rate that has multiplied by the same factor as it
        // will again over the second half. The linear middle would be 745.
        assertEquals(355L, LocateSiren.gapFor(0.5f))
        assertEquals(958f, LocateSiren.hzFor(0.5f), 1f)
        assertEquals(775L, FoundBeacon.gapFor(0.5f))
    }

    @Test
    fun `nearer is always faster and higher`() {
        var lastGap = Long.MAX_VALUE
        var lastHz = 0f
        var lastChirp = Long.MAX_VALUE
        for (i in 0..20) {
            val p = i / 20f
            val gap = LocateSiren.gapFor(p)
            val hz = LocateSiren.hzFor(p)
            val chirp = FoundBeacon.gapFor(p)
            assertTrue("gap at $p", gap <= lastGap)
            assertTrue("hz at $p", hz >= lastHz)
            assertTrue("chirp at $p", chirp <= lastChirp)
            lastGap = gap
            lastHz = hz
            lastChirp = chirp
        }
    }

    @Test
    fun `the chirp quickens but never crowds its own notes`() {
        assertEquals(1_500L, FoundBeacon.gapFor(0f))
        assertEquals(400L, FoundBeacon.gapFor(1f))
        // The chirp itself is 340 ms; the shortest pause is longer than that, so two
        // ears always get a clean pair to place.
        assertTrue(FoundBeacon.gapFor(1f) > FoundBeacon.NOTE_MILLIS * 2 + FoundBeacon.REST_MILLIS)
    }

    @Test
    fun `a tone is faded at both ends and silent at neither ends' neighbours`() {
        val tone = ToneLoop.tone(1_000f, 50, 0.5, 5)
        assertEquals(ToneLoop.SAMPLE_RATE * 50 / 1000, tone.size)
        assertEquals(0, tone.first().toInt())
        assertEquals(0, tone.last().toInt())
        // Something in the middle, at the asked amplitude or under it.
        val peak = tone.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak $peak", peak in (Short.MAX_VALUE * 0.45).toInt()..(Short.MAX_VALUE * 0.5).toInt())
    }
}

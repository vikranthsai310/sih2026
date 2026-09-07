package org.itantra.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The gyroscope-anchored compass, on numbers rather than sensors. */
class HeadingFusionTest {
    private fun assertHeading(
        expected: Float,
        actual: Float?,
        tolerance: Float = 1f,
    ) {
        val a = actual ?: error("no heading")
        var delta = HeadingFusion.normalise(a) - expected
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        assertTrue("expected $expected, got $a", abs(delta) <= tolerance)
    }

    @Test
    fun `without a gyroscope the magnetic heading is the heading`() {
        val f = HeadingFusion()
        assertEquals(42f, f.update(null, 42f, trusted = true, nowMillis = 0))
        assertEquals(42f, f.update(null, 42f, trusted = false, nowMillis = 0))
        assertNull(f.update(null, null, trusted = true, nowMillis = 0))
    }

    @Test
    fun `the first trusted reading sets the offset outright`() {
        val f = HeadingFusion()
        // The gyroscope says 100 from its own origin; north is really at 30.
        assertHeading(30f, f.update(100f, 30f, trusted = true, nowMillis = 0))
        assertEquals(-70f, f.offsetDegrees!!, 0.01f)
    }

    @Test
    fun `a turn is followed by the gyroscope with no lag`() {
        val f = HeadingFusion()
        f.update(100f, 30f, trusted = true, nowMillis = 0)
        // The hand turns ninety degrees; the magnetometer has not caught up yet.
        assertHeading(120f, f.update(190f, 30f, trusted = false, nowMillis = 20))
        assertHeading(120f, f.update(190f, 121f, trusted = true, nowMillis = 40))
    }

    @Test
    fun `an untrusted magnetic swing does not move the arrow`() {
        val f = HeadingFusion()
        f.update(100f, 30f, trusted = true, nowMillis = 0)
        // Walking past a steel cabinet: the magnetic heading swings forty degrees, the gyro
        // says the handset has not turned.
        assertHeading(30f, f.update(100f, 70f, trusted = false, nowMillis = 1_000))
        assertHeading(30f, f.update(100f, 70f, trusted = false, nowMillis = 2_000))
        assertEquals(-70f, f.offsetDegrees!!, 0.01f)
        assertTrue(f.isHolding(5_000))
        assertFalse(f.isHolding(1_000))
    }

    @Test
    fun `a trusted disagreement is taken slowly, over the settle time`() {
        val f = HeadingFusion(settleSeconds = 2.0)
        f.update(100f, 30f, trusted = true, nowMillis = 0)
        // Gyroscope drift of ten degrees, now corrected by a trusted magnetometer.
        val after1s = f.update(100f, 40f, trusted = true, nowMillis = 1_000)!!
        assertTrue("$after1s", after1s > 32f && after1s < 38f)
        var t = 1_000L
        repeat(10) {
            t += 1_000
            f.update(100f, 40f, trusted = true, nowMillis = t)
        }
        assertHeading(40f, f.update(100f, 40f, trusted = true, nowMillis = t + 1_000))
    }

    @Test
    fun `the offset crosses north the short way`() {
        val f = HeadingFusion()
        f.update(10f, 358f, trusted = true, nowMillis = 0)
        assertEquals(-12f, f.offsetDegrees!!, 0.01f)
        assertHeading(3f, f.update(15f, 3f, trusted = false, nowMillis = 100))
    }

    @Test
    fun `before any trusted reading the magnetic heading is shown rather than nothing`() {
        val f = HeadingFusion()
        assertHeading(30f, f.update(100f, 30f, trusted = false, nowMillis = 0))
        assertNull(f.offsetDegrees)
    }

    @Test
    fun `the earth gate takes the model when there is one and the planet's range when not`() {
        assertTrue(HeadingFusion.looksLikeEarth(45f, null, null, null, accuracyOk = true))
        assertFalse("iron", HeadingFusion.looksLikeEarth(120f, null, null, null, accuracyOk = true))
        assertFalse("shielded", HeadingFusion.looksLikeEarth(10f, null, null, null, accuracyOk = true))
        assertFalse("flagged", HeadingFusion.looksLikeEarth(45f, null, null, null, accuracyOk = false))

        // Hyderabad: about 43 µT, dip about 25°.
        assertTrue(HeadingFusion.looksLikeEarth(44f, 27f, 43f, 25f, accuracyOk = true))
        assertFalse("strength", HeadingFusion.looksLikeEarth(60f, 25f, 43f, 25f, accuracyOk = true))
        assertFalse("dip", HeadingFusion.looksLikeEarth(43f, 50f, 43f, 25f, accuracyOk = true))
        assertTrue("dip unknown", HeadingFusion.looksLikeEarth(43f, null, 43f, 25f, accuracyOk = true))
    }

    @Test
    fun `dip is measured against the horizontal whatever way the handset is held`() {
        // Identity matrix: device axes are world axes. A field pointing north and down at 25°.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val dip = Math.toRadians(25.0)
        val field = floatArrayOf(0f, kotlin.math.cos(dip).toFloat() * 43f, -kotlin.math.sin(dip).toFloat() * 43f)
        assertEquals(25f, HeadingFusion.dipOf(identity, field), 0.5f)

        // The same field seen by a handset rolled ninety degrees about its long axis: the
        // device's x axis now points down, so the field's downward part lands on x.
        val rolled = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f)
        val fieldOnRolled = floatArrayOf(kotlin.math.sin(dip).toFloat() * 43f, kotlin.math.cos(dip).toFloat() * 43f, 0f)
        assertEquals(25f, HeadingFusion.dipOf(rolled, fieldOnRolled), 0.5f)
    }
}

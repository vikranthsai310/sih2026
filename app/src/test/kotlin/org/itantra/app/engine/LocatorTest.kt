package org.itantra.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distance model, in the units the last steps want. */
class LocatorTest {
    @Test
    fun `one metre at the reference strength, in centimetres`() {
        assertEquals(100, Locator.centimetresFor(Locator.RSSI_AT_ONE_METRE))
        assertEquals(1, Locator.metresFor(Locator.RSSI_AT_ONE_METRE))
    }

    @Test
    fun `stronger is nearer and the figure is finer than a metre`() {
        val near = Locator.centimetresFor(-50.0)
        val far = Locator.centimetresFor(-70.0)
        assertTrue("$near cm at -50 dBm", near in 30..60)
        assertTrue("$far cm at -70 dBm", far in 250..350)
        assertTrue(near < far)
    }

    @Test
    fun `the figure is capped rather than absurd`() {
        assertEquals(99_900, Locator.centimetresFor(-200.0))
    }

    @Test
    fun `one exponent throughout, the first version's model`() {
        // -59 dBm at a metre, 2.6 all the way: 26 dB is ten times the distance.
        assertEquals(10.0, Locator.distanceFor(Locator.RSSI_AT_ONE_METRE - 26), 0.001)
        assertEquals(2.42, Locator.distanceFor(Locator.RSSI_AT_ONE_METRE - 10), 0.01)
        assertEquals(0.1, Locator.distanceFor(Locator.RSSI_AT_ONE_METRE + 26), 0.001)
    }
}

/** What both sounds follow: the distance on a logarithmic scale. */
class LocatorScaleTest {
    @Test
    fun `arm's reach is one, the far edge is zero, and beyond either is clamped`() {
        assertEquals(1f, Locator.proximityForMetres(Locator.NEAR_METRES), 0.0001f)
        assertEquals(1f, Locator.proximityForMetres(0.0), 0.0001f)
        assertEquals(0f, Locator.proximityForMetres(Locator.FAR_METRES), 0.0001f)
        assertEquals(0f, Locator.proximityForMetres(500.0), 0.0001f)
    }

    @Test
    fun `halving the distance is the same step anywhere in the range`() {
        val step = Locator.proximityForMetres(8.0) - Locator.proximityForMetres(16.0)
        assertEquals(step, Locator.proximityForMetres(2.0) - Locator.proximityForMetres(4.0), 0.0001f)
        assertTrue("$step", step > 0f)
        // The middle of the scale is the geometric mean of the ends: under four metres.
        val middle = kotlin.math.sqrt(Locator.NEAR_METRES * Locator.FAR_METRES)
        assertEquals(0.5f, Locator.proximityForMetres(middle), 0.0001f)
    }

    @Test
    fun `two fixes' errors combine in quadrature, never below a metre`() {
        assertEquals(5.0, Locator.combinedError(3f, 4f), 0.001)
        assertEquals(14.14, Locator.combinedError(10f, 10f), 0.01)
        assertEquals(1.0, Locator.combinedError(0f, 0f), 0.0)
    }
}

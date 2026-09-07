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
        assertTrue("$far cm at -70 dBm", far in 200..300)
        assertTrue(near < far)
    }

    @Test
    fun `the figure is capped rather than absurd`() {
        assertEquals(99_900, Locator.centimetresFor(-140.0))
    }
}

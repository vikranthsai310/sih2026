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

/** The pieces of the bearing that can be tested without a phone. */
class LocatorBearingTest {
    @Test
    fun `an arc is the short way round`() {
        assertEquals(-20f, Locator.arc(340f), 0.01f)
        assertEquals(20f, Locator.arc(-340f), 0.01f)
        assertEquals(170f, Locator.arc(170f), 0.01f)
        assertEquals(-170f, Locator.arc(190f), 0.01f)
    }
}

/** The sweep: direction of strongest signal from a turn on the spot. */
class LocatorSweepTest {
    /** Readings every 15° round the circle, strongest at [peakDeg], [depthDb] weaker opposite. */
    private fun circle(
        peakDeg: Float,
        depthDb: Double,
    ): Pair<List<Float>, List<Int>> {
        val headings = ArrayList<Float>()
        val rssis = ArrayList<Int>()
        for (h in 0 until 360 step 15) {
            val rad = Math.toRadians((h - peakDeg).toDouble())
            headings += h.toFloat()
            rssis += (-60.0 - depthDb * (1 - kotlin.math.cos(rad)) / 2).toInt()
        }
        return headings to rssis
    }

    @Test
    fun `a body-shadowed circle points at the peak`() {
        for (peak in listOf(0f, 45f, 200f, 350f)) {
            val (h, r) = circle(peak, 18.0)
            val sweep = Locator.sweepOf(h, r) ?: error("no sweep at $peak")
            var delta = sweep.bearingDeg - peak
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            assertTrue("peak $peak read as ${sweep.bearingDeg}", kotlin.math.abs(delta) < 8f)
            assertTrue("spread ${sweep.spreadDeg}", sweep.spreadDeg < 60f)
        }
    }

    @Test
    fun `a flat circle gives no direction`() {
        val (h, r) = circle(90f, 0.0)
        assertEquals(null, Locator.sweepOf(h, r))
    }

    @Test
    fun `half a circle is not enough`() {
        val h = (0 until 180 step 15).map { it.toFloat() }
        val r = h.map { -60 - (it / 15).toInt() }
        assertEquals(null, Locator.sweepOf(h, r))
        assertEquals(180, Locator.coverageOf(h))
        assertEquals(0, Locator.coverageOf(emptyList()))
    }
}

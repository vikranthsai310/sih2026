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
    fun `the sender's own power sets the one-metre reference`() {
        // A sender at +4 dBm is expected at -53 dBm one metre off; at 0 dBm, -57.
        assertEquals(-53.0, Locator.referenceFor(4), 0.01)
        assertEquals(-57.0, Locator.referenceFor(0), 0.01)
        assertEquals(Locator.RSSI_AT_ONE_METRE, Locator.referenceFor(null), 0.01)
        assertEquals(100, Locator.centimetresFor(-53.0, Locator.referenceFor(4)))
    }

    @Test
    fun `the exponent is free space close in and rises with range`() {
        // 20 dB below the reference: exponent 2.53, so nearer than free space's 10 m
        // would say and further than a corridor's 5 m.
        val d = Locator.distanceFor(Locator.RSSI_AT_ONE_METRE - 20)
        assertTrue("$d", d > 5.0 && d < 10.0)
        // 6 dB above it: half a metre, free space.
        assertEquals(0.5, Locator.distanceFor(Locator.RSSI_AT_ONE_METRE + 6), 0.02)
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
    fun `an uneven turn does not drag the peak towards where the operator lingered`() {
        // Peak at 90°, but forty readings taken while standing at 270° and one each
        // elsewhere: a mean of the readings would sit near 270; the fit must not.
        val (h0, r0) = circle(90f, 16.0)
        val h = ArrayList(h0)
        val r = ArrayList(r0)
        repeat(40) {
            h += 270f
            r += r0[h0.indexOf(270f)]
        }
        val sweep = Locator.sweepOf(h, r) ?: error("no sweep")
        var delta = sweep.bearingDeg - 90f
        if (delta > 180f) delta -= 360f
        assertTrue("read as ${sweep.bearingDeg}", kotlin.math.abs(delta) < 8f)
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

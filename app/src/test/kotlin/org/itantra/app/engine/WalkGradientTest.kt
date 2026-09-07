package org.itantra.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A direction from walking. Every test puts a target somewhere, walks a path past it on a
 * real path-loss model, and asks the gradient which way the target was.
 */
class WalkGradientTest {
    /** Target at the origin; the signal a walker hears at (x, y) metres from it. */
    private fun rssiAt(
        x: Double,
        y: Double,
    ): Double = -59.0 - 10 * 2.5 * log10(sqrt(x * x + y * y).coerceAtLeast(0.5))

    /**
     * Walks from [x0], [y0] on [courseDeg] for [steps] metres, one fix a second, and
     * returns the gradient's estimate at the end. North is +y, east is +x.
     */
    private fun walk(
        gradient: WalkGradient,
        x0: Double,
        y0: Double,
        courseDeg: Float,
        steps: Int,
        startAtMillis: Long = 0L,
    ): Locator.Companion.Sweep? {
        val r = Math.toRadians(courseDeg.toDouble())
        var x = x0
        var y = y0
        var t = startAtMillis
        repeat(steps + 1) { i ->
            gradient.walked(courseDeg, if (i == 0) 0.0 else 1.0, rssiAt(x, y), t)
            x += sin(r)
            y += cos(r)
            t += 1_000
        }
        return gradient.estimate(t)
    }

    private fun arc(deg: Float): Float = ((deg + 540f) % 360f) - 180f

    @Test
    fun `walking straight at the target says the target is ahead`() {
        // Start 20 m south of it, walk north.
        val estimate = walk(WalkGradient(), 0.0, -20.0, 0f, 8)
        assertNotNull(estimate)
        assertTrue("bearing ${estimate!!.bearingDeg}", abs(arc(estimate.bearingDeg - 0f)) < 5f)
        assertTrue("spread ${estimate.spreadDeg}", estimate.spreadDeg < 40f)
    }

    @Test
    fun `walking away says the target is behind`() {
        // Start 5 m north of it, walk north: signal falls, so the target is south.
        val estimate = walk(WalkGradient(), 0.0, 5.0, 0f, 8)
        assertNotNull(estimate)
        assertTrue("bearing ${estimate!!.bearingDeg}", abs(arc(estimate.bearingDeg - 180f)) < 5f)
    }

    /** Two legs of a walk that both gain resolve the target between them. */
    @Test
    fun `two legs triangulate`() {
        val gradient = WalkGradient()
        // From the south-west corner: north for a bit, then east. Target is north-east
        // of the start, and after the first leg it is due east.
        walk(gradient, -12.0, -12.0, 0f, 6)
        val estimate = walk(gradient, -12.0, -6.0, 90f, 6, startAtMillis = 7_000)
        assertNotNull(estimate)
        // The sum of a northward gain and an eastward gain points north-east-ish; the
        // eastward leg was nearer and gained more, so it leans east.
        val b = estimate!!.bearingDeg
        assertTrue("bearing $b", b in 30f..90f)
    }

    @Test
    fun `standing still says nothing`() {
        val gradient = WalkGradient()
        var t = 0L
        repeat(10) {
            t += 1_000
            gradient.walked(0f, 0.0, -70.0, t)
        }
        assertNull(gradient.estimate(t))
    }

    /** Walking past at a tangent: the signal barely changes, and that is not a direction. */
    @Test
    fun `a flat signal gives no direction`() {
        val gradient = WalkGradient()
        var t = 0L
        repeat(8) {
            t += 1_000
            gradient.walked(90f, 1.0, -70.0 + (it % 2) * 0.05, t)
        }
        assertNull(gradient.estimate(t))
    }

    /**
     * Out and back is not a contradiction: walking away with the signal falling says the
     * target is behind, which is the same direction the walk in said. Both legs agree.
     */
    @Test
    fun `a walk out and back agrees with itself`() {
        val gradient = WalkGradient()
        walk(gradient, 0.0, -20.0, 0f, 5)
        val estimate = walk(gradient, 0.0, -15.0, 180f, 5, startAtMillis = 6_000)
        assertNotNull(estimate)
        assertTrue("bearing ${estimate!!.bearingDeg}", abs(arc(estimate.bearingDeg - 0f)) < 5f)
    }

    /** Legs that genuinely contradict each other cancel, and the answer is honestly none. */
    @Test
    fun `contradictory legs cancel`() {
        val gradient = WalkGradient()
        var t = 0L
        // The signal rises walking north, and rises again walking south: noise, not a
        // target, and the vectors sum to nothing.
        gradient.walked(0f, 0.0, -80.0, t)
        repeat(3) {
            t += 1_000
            gradient.walked(0f, 1.0, -80.0 + (it + 1) * 2.0, t)
        }
        gradient.interrupt()
        t += 1_000
        gradient.walked(180f, 0.0, -80.0, t)
        repeat(3) {
            t += 1_000
            gradient.walked(180f, 1.0, -80.0 + (it + 1) * 2.0, t)
        }
        assertNull(gradient.estimate(t))
    }

    @Test
    fun `an interruption starts the next stretch fresh`() {
        val gradient = WalkGradient()
        gradient.walked(0f, 0.0, -80.0, 1_000)
        gradient.interrupt()
        // The first reading after an interruption has nothing to be compared with.
        gradient.walked(0f, 1.0, -60.0, 2_000)
        assertEquals(0, gradient.sampleCount)
    }

    @Test
    fun `old stretches age out`() {
        val gradient = WalkGradient(windowMillis = 5_000)
        walk(gradient, 0.0, -20.0, 0f, 8)
        assertNull("eight seconds of walking, five kept, none of it recent", gradient.estimate(60_000))
    }
}

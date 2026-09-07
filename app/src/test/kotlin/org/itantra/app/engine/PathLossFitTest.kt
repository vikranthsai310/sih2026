package org.itantra.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The signal-to-distance model, fitted on the way in.
 *
 * Every test feeds the fit readings from a *known* model and asks whether it finds that
 * model back, which is the only sense in which a calibration can be said to work.
 */
class PathLossFitTest {
    /** A handset that is 8 dB louder than assumed and loses signal faster than free space. */
    private val trueReference = -51.0
    private val trueExponent = 3.1

    private fun rssiAt(metres: Double): Double = trueReference - 10 * trueExponent * log10(metres)

    /** Readings walking in from [from] to [to] metres, a metre at a time, with good GPS. */
    private fun walkIn(
        fit: PathLossFit,
        from: Int,
        to: Int,
        errorMetres: Double = 4.0,
    ) {
        var t = 0L
        for (d in from downTo to) {
            t += 1_000
            fit.learn(d.toDouble(), errorMetres, rssiAt(d.toDouble()), t)
        }
    }

    @Test
    fun `nothing is fitted before there are enough readings`() {
        val fit = PathLossFit()
        walkIn(fit, 40, 36)
        assertFalse(fit.isFitted)
        assertNull(fit.referenceDbm)
    }

    @Test
    fun `a walk in from far recovers both the reference and the exponent`() {
        val fit = PathLossFit()
        walkIn(fit, 60, 15)
        assertTrue(fit.isFitted)
        assertEquals(trueReference, fit.referenceDbm!!, 0.5)
        assertEquals(trueExponent, fit.exponent!!, 0.05)
    }

    /**
     * The point of the whole thing: after the walk in, the model gives the true distance
     * where the assumed one is off by a factor.
     */
    @Test
    fun `the fitted model is right in the last metres where the assumed one is not`() {
        val fit = PathLossFit()
        walkIn(fit, 60, 15)
        val heardAtThreeMetres = rssiAt(3.0)
        val fitted = Locator.distanceFor(heardAtThreeMetres, fit.referenceDbm!!, fit.exponent!!, fitted = true)
        val assumed = Locator.distanceFor(heardAtThreeMetres)
        assertEquals(3.0, fitted, 0.15)
        // Not "the assumed model is wrong by a metre" -- how wrong it is depends on the
        // handset -- but that the fit cuts the error by several times.
        val fittedError = kotlin.math.abs(fitted - 3.0)
        val assumedError = kotlin.math.abs(assumed - 3.0)
        assertTrue("assumed $assumed, fitted $fitted", assumedError > fittedError * 3)
    }

    /** Readings all at nearly one distance cannot say how fast the signal falls off. */
    @Test
    fun `without a span of distances only the reference is learned`() {
        val fit = PathLossFit()
        walkIn(fit, 30, 24)
        assertTrue(fit.isFitted)
        assertNull("no slope from a six-metre span", fit.exponent)
        // The intercept through the centroid with the assumed slope is not the true
        // reference, but it is in the right region and better than nothing.
        assertTrue(fit.referenceDbm!! in -60.0..-45.0)
    }

    @Test
    fun `a distance inside its own error teaches nothing`() {
        val fit = PathLossFit()
        var t = 0L
        repeat(20) {
            t += 1_000
            // 10 m apart, ±5 m each side: 10 is not three times 7.
            fit.learn(10.0, 7.0, rssiAt(10.0), t)
        }
        assertEquals(0, fit.sampleCount)
    }

    @Test
    fun `too near and too far are refused`() {
        val fit = PathLossFit()
        fit.learn(3.0, 0.5, rssiAt(3.0), 1_000)
        fit.learn(200.0, 0.5, rssiAt(200.0), 2_000)
        assertEquals(0, fit.sampleCount)
    }

    /** A line that no radio produces is a wrong line, and is thrown away whole. */
    @Test
    fun `a fit outside what physics allows is rejected rather than clamped`() {
        val fit = PathLossFit()
        var t = 0L
        // Signal that *rises* with distance: nonsense, exponent negative.
        for (d in 60 downTo 15) {
            t += 1_000
            fit.learn(d.toDouble(), 4.0, -90.0 + d * 0.5, t)
        }
        assertFalse(fit.isFitted)
    }

    @Test
    fun `samples age out so a new place is refitted`() {
        val fit = PathLossFit(windowMillis = 10_000)
        var t = 0L
        for (d in 60 downTo 15) {
            t += 1_000
            fit.learn(d.toDouble(), 4.0, rssiAt(d.toDouble()), t)
        }
        assertTrue(fit.sampleCount <= 11)
    }
}

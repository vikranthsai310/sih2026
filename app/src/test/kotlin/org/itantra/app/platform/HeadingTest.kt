package org.itantra.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The compass geometry, on rotation matrices built by hand.
 *
 * A matrix here maps device axes to world axes (east, north, up), column by column: the
 * first column is where the phone's right edge points, the second its top edge, the
 * third the direction out of its screen.
 */
class HeadingTest {
    private fun matrix(
        right: FloatArray,
        top: FloatArray,
        screen: FloatArray,
    ): FloatArray =
        floatArrayOf(
            right[0], top[0], screen[0],
            right[1], top[1], screen[1],
            right[2], top[2], screen[2],
        )

    private fun east() = floatArrayOf(1f, 0f, 0f)

    private fun north() = floatArrayOf(0f, 1f, 0f)

    private fun south() = floatArrayOf(0f, -1f, 0f)

    private fun up() = floatArrayOf(0f, 0f, 1f)

    private fun west() = floatArrayOf(-1f, 0f, 0f)

    private fun assertBearing(
        expected: Float,
        actual: Float,
    ) {
        var delta = Heading.normalise(actual) - expected
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        assertEquals("expected $expected, got $actual", 0f, abs(delta), 1.5f)
    }

    @Test
    fun `flat on a palm, the top edge is forward`() {
        // Screen up, top edge north.
        assertBearing(0f, Heading.forwardAzimuth(matrix(east(), north(), up())))
        // Screen up, top edge east.
        assertBearing(90f, Heading.forwardAzimuth(matrix(south(), east(), up())))
    }

    @Test
    fun `held upright, the back of the phone is forward`() {
        // Operator faces north, screen towards them: screen normal points south.
        assertBearing(0f, Heading.forwardAzimuth(matrix(east(), up(), south())))
        // Operator faces west.
        assertBearing(270f, Heading.forwardAzimuth(matrix(north(), up(), east())))
    }

    @Test
    fun `tilted up towards the face, forward does not move`() {
        // Top edge north, raised by θ about the phone's short axis: the direction the
        // operator faces is north at every angle, and so must the reading be.
        for (deg in 0..90 step 10) {
            val t = Math.toRadians(deg.toDouble())
            val top = floatArrayOf(0f, cos(t).toFloat(), sin(t).toFloat())
            val screen = floatArrayOf(0f, -sin(t).toFloat(), cos(t).toFloat())
            assertBearing(0f, Heading.forwardAzimuth(matrix(east(), top, screen)))
        }
    }

    @Test
    fun `nearly upright and facing north-east, still north-east`() {
        // 80° raised, facing 45°: the top edge's horizontal projection is small, and
        // the platform's own azimuth from it is where the old arrow went wrong.
        val t = Math.toRadians(80.0)
        val face = Math.toRadians(45.0)
        val forward = floatArrayOf(sin(face).toFloat(), cos(face).toFloat(), 0f)
        val right = floatArrayOf(cos(face).toFloat(), -sin(face).toFloat(), 0f)
        val top = floatArrayOf(forward[0] * cos(t).toFloat(), forward[1] * cos(t).toFloat(), sin(t).toFloat())
        val screen = floatArrayOf(-forward[0] * sin(t).toFloat(), -forward[1] * sin(t).toFloat(), cos(t).toFloat())
        assertBearing(45f, Heading.forwardAzimuth(matrix(right, top, screen)))
    }

    @Test
    fun `smoothing crosses north the short way`() {
        assertEquals(0.5f, Heading.smooth(359f, 2f, 0.5f), 0.01f)
        assertEquals(0.5f, Heading.smooth(2f, 359f, 0.5f), 0.01f)
        assertEquals(358.5f, Heading.smooth(0f, 357f, 0.5f), 0.01f)
        assertEquals(west()[0], -1f, 0f)
    }
}

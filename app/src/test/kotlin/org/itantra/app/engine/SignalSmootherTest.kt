package org.itantra.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two stages between a signal reading and a sound. */
class SignalSmootherTest {
    @Test
    fun `the first reading is taken whole`() {
        val s = SignalSmoother()
        assertNull(s.value)
        assertEquals(-70.0, s.offer(-70, 1_000L), 0.0)
        assertEquals(listOf(-70), s.recent)
    }

    @Test
    fun `one spike among steady readings is thrown away by the median`() {
        val s = SignalSmoother()
        var t = 0L
        repeat(5) { s.offer(-70, t.also { t += 100 }) }
        // A single -40 among -70s: the median never sees it.
        val after = s.offer(-40, t)
        assertEquals(-70.0, after, 0.01)
    }

    @Test
    fun `a step is two-thirds followed in the time constant, whatever the reading rate`() {
        // A six-decibel step: under the big-step rule, so only the time constant acts.
        fun afterOneTimeConstant(intervalMillis: Long): Double {
            val s = SignalSmoother()
            var t = 0L
            repeat(5) { s.offer(-80, t.also { t += intervalMillis }) }
            // The median flips on the third reading at -74; from there the average
            // moves with time, and is read 600 ms after the flip.
            val flipAt = t + 2 * intervalMillis
            var last = 0.0
            while (true) {
                last = s.offer(-74, t)
                if (t >= flipAt + 600L) break
                t += intervalMillis
            }
            return last
        }
        // Two-thirds of six decibels is four: about -76 either way, where a per-reading
        // weight would have moved twelve readings four times as far as three.
        val fast = afterOneTimeConstant(50L)
        val slow = afterOneTimeConstant(200L)
        assertTrue("fast $fast", fast > -77.5 && fast < -75.0)
        assertTrue("slow $slow", slow > -77.5 && slow < -75.0)
    }

    @Test
    fun `a big jump is followed at half at least`() {
        val s = SignalSmoother()
        var t = 0L
        repeat(5) { s.offer(-90, t.also { t += 100 }) }
        repeat(3) { s.offer(-60, t.also { t += 10 }) }
        // The median has moved to -60 after three of five; ten milliseconds of time
        // constant would move the average by under two percent, but a 30 dB jump is
        // a stride, not noise, and is half followed at once.
        assertTrue("${s.value}", s.value!! > -76.0)
    }

    @Test
    fun `clear forgets everything`() {
        val s = SignalSmoother()
        s.offer(-50, 1L)
        s.clear()
        assertNull(s.value)
        assertTrue(s.recent.isEmpty())
    }
}

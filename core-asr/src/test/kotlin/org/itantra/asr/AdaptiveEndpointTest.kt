package org.itantra.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive endpointing, task W6.3. */
class AdaptiveEndpointTest {
    private fun endpoint() = AdaptiveEndpoint(baseMillis = 400)

    @Test
    fun `it uses the default until there is evidence to move`() {
        val e = endpoint()
        assertFalse(e.isConfident)
        assertEquals(400, e.effectiveMillis())

        // A window tuned on two utterances is tuned on noise.
        repeat(2) { e.observeInternalPause(300) }
        assertEquals(400, e.effectiveMillis())
    }

    @Test
    fun `it becomes confident after enough observations`() {
        val e = endpoint()
        repeat(AdaptiveEndpoint.MIN_OBSERVATIONS) { e.observeInternalPause(200) }
        assertTrue(e.isConfident)
    }

    /**
     * The asymmetry is the whole design. Being cut off mid-sentence is far worse than
     * 100 ms of extra latency, so evidence of long pauses is acted on immediately.
     */
    @Test
    fun `a long internal pause widens the window quickly`() {
        val e = endpoint()
        val before = e.currentMillis
        e.observeInternalPause(600)
        assertTrue("expected a quick rise from $before", e.currentMillis > before + 50)
    }

    @Test
    fun `brisk speech narrows the window only slowly`() {
        val e = endpoint()
        val before = e.currentMillis
        e.observeInternalPause(100)
        val afterOne = e.currentMillis
        assertTrue("must not collapse on one sample", afterOne > before - 50)

        repeat(40) { e.observeInternalPause(100) }
        assertTrue("but it should eventually come down", e.currentMillis < before)
    }

    /** The strongest possible evidence that the window is too short. */
    @Test
    fun `being cut off widens the window immediately`() {
        val e = endpoint()
        val before = e.currentMillis
        e.observeCutOff()
        assertTrue("a cut-off must cause a jump, not a nudge", e.currentMillis >= before * 1.3)
    }

    @Test
    fun `the window never leaves its bounds`() {
        val fast = endpoint()
        repeat(200) { fast.observeInternalPause(0) }
        assertTrue(fast.currentMillis >= AdaptiveEndpoint.MIN_MILLIS)

        val slow = endpoint()
        repeat(200) { slow.observeCutOff() }
        assertTrue(slow.currentMillis <= AdaptiveEndpoint.MAX_MILLIS)
    }

    @Test
    fun `the bounds keep the latency claim defensible`() {
        // Above 700 ms the silence window alone would eat most of the budget.
        assertTrue(AdaptiveEndpoint.MAX_MILLIS <= 700)
        // Below 120 ms word tails get clipped.
        assertTrue(AdaptiveEndpoint.MIN_MILLIS >= 120)
    }

    @Test
    fun `reset returns to the default`() {
        val e = endpoint()
        repeat(10) { e.observeCutOff() }
        e.reset()
        assertEquals(400, e.effectiveMillis())
        assertEquals(0, e.observations)
    }
}

/** Thermal thread reduction, task W6.17, risk T-03. */
class ThermalThreadsTest {
    @Test
    fun `a cool device gets every thread`() {
        assertEquals(4, ThermalThreads.threadsFor(0))
        assertEquals(4, ThermalThreads.threadsFor(1))
    }

    /**
     * Once the platform is reducing clocks, more threads make it worse: the cores are
     * already contended and the scheduling is overhead on a device shedding heat.
     */
    @Test
    fun `a warm device backs off before the platform forces it`() {
        assertEquals(2, ThermalThreads.threadsFor(2))
    }

    @Test
    fun `a hot device drops to a single thread`() {
        for (status in 3..6) {
            assertEquals("status $status", 1, ThermalThreads.threadsFor(status))
        }
    }

    @Test
    fun `it never returns zero threads`() {
        for (status in 0..6) {
            assertTrue(ThermalThreads.threadsFor(status, normal = 1) >= 1)
        }
    }

    @Test
    fun `the operator is warned once latency will be visibly worse`() {
        assertFalse(ThermalThreads.shouldWarn(2))
        assertTrue(ThermalThreads.shouldWarn(3))
    }
}

/** Stabilised partials, task W6.2. */
class StabilisedPartialsTest {
    private fun partials() = StabilisedPartials()

    @Test
    fun `nothing is released before a prefix has been seen enough times`() {
        val p = partials()
        assertTrue(p.offer("हमें मदद").isEmpty())
        assertTrue(p.offer("हमें मदद चाहिए").isEmpty())
    }

    @Test
    fun `a prefix that survives three partials is released`() {
        val p = partials()
        p.offer("हमें मदद चाहिए")
        p.offer("हमें मदद चाहिए दो")
        val released = p.offer("हमें मदद चाहिए दो लोग")

        assertEquals(listOf("हमें", "मदद", "चाहिए"), released)
        assertEquals("हमें मदद चाहिए", p.releasedText())
    }

    /**
     * The last words of a window are the least reliable — the window ends mid-word and
     * the next overlap revises them. Releasing them early would have the receiver speak
     * a word the sender never said.
     */
    @Test
    fun `the unstable tail is withheld`() {
        val p = partials()
        p.offer("भेजो नाव जल")
        p.offer("भेजो नाव जल्दी")
        val released = p.offer("भेजो नाव जल्दी से")

        assertTrue("the revised word must not be released", "जल" !in released)
        assertEquals(listOf("भेजो", "नाव"), released)
    }

    /** A word already spoken by the receiver cannot be recalled. */
    @Test
    fun `nothing is ever un-released`() {
        val p = partials()
        p.offer("एक दो तीन")
        p.offer("एक दो तीन")
        p.offer("एक दो तीन")
        assertEquals("एक दो तीन", p.releasedText())

        // A later partial disagrees. The correction waits for FINAL.
        p.offer("एक दो चार")
        p.offer("एक दो चार")
        assertEquals("एक दो तीन", p.releasedText())
    }

    @Test
    fun `each word is released exactly once`() {
        val p = partials()
        val all = ArrayList<String>()
        for (text in listOf(
            "एक",
            "एक दो",
            "एक दो तीन",
            "एक दो तीन चार",
            "एक दो तीन चार पाँच",
            "एक दो तीन चार पाँच छह",
        )) {
            all += p.offer(text)
        }
        assertEquals("no word may be released twice", all.size, all.toSet().size)
    }

    @Test
    fun `the final frame carries the whole utterance, not a remainder`() {
        val p = partials()
        repeat(3) { p.offer("एक दो तीन") }
        assertEquals("एक दो तीन चार", p.onFinal("एक दो तीन चार"))
        assertEquals("state must be cleared for the next utterance", "", p.releasedText())
    }

    @Test
    fun `three repeats is the stability threshold`() {
        assertEquals(3, StabilisedPartials.STABLE_REPEATS)
    }

    @Test
    fun `the common prefix of disagreeing lists is empty`() {
        val prefix =
            StabilisedPartials.commonPrefix(
                listOf(listOf("a", "b"), listOf("x", "b"), listOf("a", "b")),
            )
        assertTrue(prefix.isEmpty())
    }
}

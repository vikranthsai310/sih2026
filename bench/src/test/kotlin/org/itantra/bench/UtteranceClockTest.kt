package org.itantra.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage timestamps on the live path, task **W1.33**. */
class UtteranceClockTest {
    /** A clock the test drives, so the assertions are about arithmetic and not timing. */
    private class FakeTime(var nanos: Long = 0) {
        fun advance(millis: Long): Long {
            nanos += millis * 1_000_000
            return nanos
        }
    }

    private fun clock(time: FakeTime) = UtteranceClock("u1", "hi", "ptt", "ble", now = { time.nanos })

    @Test
    fun `a full utterance produces a trace with every stage`() {
        val time = FakeTime()
        val clock = clock(time).start()

        time.advance(30)
        clock.mark(UtteranceClock.Stage.VAD)
        time.advance(200)
        clock.mark(UtteranceClock.Stage.ENDPOINT)
        time.advance(330)
        clock.mark(UtteranceClock.Stage.FINAL)
        time.advance(40)
        clock.mark(UtteranceClock.Stage.RX)
        time.advance(5)
        clock.mark(UtteranceClock.Stage.NORM)
        time.advance(195)
        clock.mark(UtteranceClock.Stage.CHUNK1)
        time.advance(50)
        clock.mark(UtteranceClock.Stage.AUDIO)

        val trace = clock.toTrace()
        assertEquals(850L, trace.endToEndMillis)
        assertEquals(0L, StageSummary.unaccountedMillis(trace))
    }

    @Test
    fun `elapsed time is measured from the microphone`() {
        val time = FakeTime()
        val clock = clock(time).start()
        time.advance(230)
        clock.mark(UtteranceClock.Stage.ENDPOINT)

        assertEquals(230L, clock.elapsedMillis(UtteranceClock.Stage.ENDPOINT))
        assertEquals(0L, clock.elapsedMillis(UtteranceClock.Stage.MIC))
        assertNull("a stage not reached has no elapsed time", clock.elapsedMillis(UtteranceClock.Stage.AUDIO))
    }

    /**
     * An endpointer that retriggers or a decoder that emits a second final must not move a
     * stage that has already happened. A trace whose stages drift as the utterance goes on
     * is internally inconsistent in a way no reader could detect.
     */
    @Test
    fun `a stage recorded twice keeps the first mark`() {
        val time = FakeTime()
        val clock = clock(time).start()
        time.advance(100)
        clock.mark(UtteranceClock.Stage.ENDPOINT)
        time.advance(500)
        clock.mark(UtteranceClock.Stage.ENDPOINT)

        assertEquals(100L, clock.elapsedMillis(UtteranceClock.Stage.ENDPOINT))
    }

    @Test
    fun `starting twice does not move the origin`() {
        val time = FakeTime()
        val clock = clock(time).start()
        val origin = clock[UtteranceClock.Stage.MIC]
        time.advance(1_000)
        clock.start()

        assertEquals(origin, clock[UtteranceClock.Stage.MIC])
    }

    /** A stage taken on another thread arrives with its own timestamp. */
    @Test
    fun `a mark taken elsewhere can be supplied`() {
        val time = FakeTime()
        val clock = clock(time).start()
        val takenOnTheAudioThread = time.nanos + 20 * 1_000_000

        clock.markAt(UtteranceClock.Stage.VAD, takenOnTheAudioThread)
        assertEquals(20L, clock.elapsedMillis(UtteranceClock.Stage.VAD))
    }

    /**
     * A row with no origin cannot have its stages measured from anything, and a line of
     * nulls in `latency.csv` is worse than no line.
     */
    @Test
    fun `a trace cannot be built from an utterance that never started`() {
        val clock = clock(FakeTime())
        assertTrue(!clock.isStarted)
        assertNotNull(runCatching { clock.toTrace() }.exceptionOrNull())
    }

    /**
     * Out-of-order stages are reported rather than thrown away. The trace is still
     * evidence — of a defect in the instrumentation — and discarding it at collection is
     * how a measurement bug survives to the next run.
     */
    @Test
    fun `stages recorded out of order are reported and the trace still built`() {
        val time = FakeTime()
        val clock = clock(time).start()
        time.advance(300)
        clock.mark(UtteranceClock.Stage.ENDPOINT)
        // A final stamped before the endpoint: the instrumentation is wrong somewhere.
        clock.markAt(UtteranceClock.Stage.FINAL, time.nanos - 100 * 1_000_000)

        assertEquals(listOf(UtteranceClock.Stage.FINAL), clock.outOfOrder())
        assertNotNull("the row is still produced", clock.toTrace())
    }

    @Test
    fun `a well-ordered utterance reports nothing out of order`() {
        val time = FakeTime()
        val clock = clock(time).start()
        for (stage in UtteranceClock.Stage.entries) {
            time.advance(10)
            clock.mark(stage)
        }
        assertEquals(emptyList<UtteranceClock.Stage>(), clock.outOfOrder())
    }

    @Test
    fun `the frame size and template id travel with the row`() {
        val time = FakeTime()
        val clock = clock(time).start()
        time.advance(800)
        clock.mark(UtteranceClock.Stage.AUDIO)

        val trace = clock.toTrace(frameBytes = 29, templateId = 4, compressionRatio = 3310.3)
        assertEquals(29, trace.frameBytes)
        assertEquals(4, trace.templateId)
        val row = trace.toFields()
        assertTrue("29" in row)
        assertTrue("4" in row)
    }

    /** The receiver's clock is corrected on to the sender's exactly once, in the trace. */
    @Test
    fun `a clock offset does not leak into the end-to-end figure`() {
        val time = FakeTime()
        val clock = clock(time).start()
        time.advance(800)
        val offsetMillis = 45_000L
        clock.markAt(UtteranceClock.Stage.AUDIO, time.nanos + offsetMillis * 1_000_000)

        val trace = clock.toTrace(clockOffsetNanos = offsetMillis * 1_000_000)
        assertEquals(800L, trace.endToEndMillis)
    }
}

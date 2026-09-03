package org.itantra.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PreTriggerRingTest {
    /** 250 ms at 16 kHz is 4 000 samples, which is 8 000 bytes. `docs/ASR.md` section 2. */
    @Test
    fun `the leading pad is four thousand samples`() {
        val ring = PreTriggerRing.forLeadingPad()
        assertEquals(4_000, ring.capacity)
        assertEquals(8_000, ring.capacity * 2)
    }

    @Test
    fun `retains the most recent samples in order once full`() {
        val ring = PreTriggerRing(5)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.snapshot())
    }

    @Test
    fun `reports a partial fill before wrapping`() {
        val ring = PreTriggerRing(5)
        ring.write(shortArrayOf(1, 2, 3))
        assertEquals(3, ring.size)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `a frame longer than the ring leaves only its tail`() {
        val ring = PreTriggerRing(4)
        ring.write(ShortArray(100) { it.toShort() })
        assertArrayEquals(shortArrayOf(96, 97, 98, 99), ring.snapshot())
    }

    @Test
    fun `many small writes behave like one large one`() {
        val random = Random(11)
        val ring = PreTriggerRing(320)
        val all = ShortArray(2_000) { random.nextInt(-3000, 3000).toShort() }
        var i = 0
        while (i < all.size) {
            val n = minOf(20, all.size - i)
            ring.write(all, i, n)
            i += n
        }
        assertArrayEquals(all.copyOfRange(all.size - 320, all.size), ring.snapshot())
    }

    @Test
    fun `clear discards the audio`() {
        val ring = PreTriggerRing(4)
        ring.write(shortArrayOf(1, 2, 3, 4))
        ring.clear()
        assertEquals(0, ring.size)
        assertArrayEquals(ShortArray(0), ring.snapshot())
    }
}

class EnergyGateTest {
    private fun silence(n: Int = 320) = ShortArray(n)

    private fun tone(
        amplitude: Int,
        n: Int = 320,
    ) = ShortArray(n) { (amplitude * sin(2 * PI * 440 * it / 16_000.0)).toInt().toShort() }

    private fun noise(
        amplitude: Int,
        seed: Int = 5,
        n: Int = 320,
    ): ShortArray {
        val random = Random(seed)
        return ShortArray(n) { random.nextInt(-amplitude, amplitude + 1).toShort() }
    }

    @Test
    fun `the designed frame is 320 samples at 16 kHz`() {
        assertEquals(320, EnergyGate().frameSamples)
    }

    @Test
    fun `stays shut through silence`() {
        val gate = EnergyGate()
        repeat(200) { assertFalse(gate.process(silence())) }
    }

    @Test
    fun `stays shut through steady background noise`() {
        val gate = EnergyGate()
        // let the floor settle on the noise, then keep feeding the same noise
        repeat(400) { gate.process(noise(400, seed = it)) }
        var opened = false
        repeat(200) { if (gate.process(noise(400, seed = 1000 + it))) opened = true }
        assertFalse("steady background noise must not open the gate", opened)
    }

    @Test
    fun `opens on speech-like energy well above the floor`() {
        val gate = EnergyGate()
        repeat(300) { gate.process(noise(200, seed = it)) }
        assertFalse(gate.isOpen)

        var openedAfter = -1
        for (i in 0 until 20) {
            if (gate.process(tone(12_000)) && openedAfter < 0) openedAfter = i + 1
        }
        assertTrue("gate never opened on loud speech", openedAfter > 0)
        assertEquals("should open after exactly three frames of hysteresis", 3, openedAfter)
    }

    @Test
    fun `closes only after ten quiet frames`() {
        val gate = EnergyGate()
        repeat(300) { gate.process(noise(200, seed = it)) }
        repeat(5) { gate.process(tone(12_000)) }
        assertTrue(gate.isOpen)

        var closedAfter = -1
        for (i in 0 until 30) {
            if (!gate.process(silence()) && closedAfter < 0) closedAfter = i + 1
        }
        assertEquals("should close after exactly ten frames of hysteresis", 10, closedAfter)
    }

    /**
     * The failure this class exists to avoid: a long utterance dragging the noise
     * floor up until the speaker is gated out mid-sentence.
     */
    @Test
    fun `a sustained talker does not raise their own floor`() {
        val gate = EnergyGate()
        repeat(300) { gate.process(noise(200, seed = it)) }
        val floorBeforeSpeech = gate.floorDb

        // eight seconds of continuous speech at 20 ms per frame
        repeat(400) { gate.process(tone(12_000), speechDownstream = true) }

        assertEquals(
            "the floor moved while someone was speaking",
            floorBeforeSpeech,
            gate.floorDb,
            0.001,
        )
        assertTrue("gate closed on a talker mid-sentence", gate.isOpen)
    }

    @Test
    fun `the floor follows a rising environment while quiet`() {
        val gate = EnergyGate()
        repeat(300) { gate.process(noise(100, seed = it)) }
        val quietFloor = gate.floorDb

        // the room gets louder, but still no speech
        repeat(1_500) { gate.process(noise(1_500, seed = 9_000 + it)) }

        assertTrue(
            "floor should adapt upward in a louder room ($quietFloor -> ${gate.floorDb})",
            gate.floorDb > quietFloor + 3.0,
        )
    }

    @Test
    fun `reset forgets the floor`() {
        val gate = EnergyGate()
        repeat(50) { gate.process(noise(300, seed = it)) }
        gate.reset()
        assertTrue(gate.floorDb.isNaN())
        assertFalse(gate.isOpen)
    }
}

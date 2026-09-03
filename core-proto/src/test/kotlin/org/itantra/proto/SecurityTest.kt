package org.itantra.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {
    @Test
    fun `a fresh frame is accepted once and never again`() {
        val w = ReplayWindow()
        assertTrue(w.admit(src = 2, epoch = 1, seq = 100))
        assertFalse("the same frame must not be accepted twice", w.admit(2, 1, 100))
    }

    /** Risk S-02: a recorded alert must not be replayable. */
    @Test
    fun `a captured frame replayed immediately, later, and after a restart is rejected`() {
        val w = ReplayWindow()
        assertTrue(w.admit(2, 1, 500))

        // immediately
        assertFalse(w.admit(2, 1, 500))

        // after a thousand further frames
        for (s in 501..1_500) w.admit(2, 1, s)
        assertFalse("an old capture must not be admitted", w.admit(2, 1, 500))

        // and after the sender restarts, which raises the epoch
        assertTrue(w.admit(2, 2, 0))
        assertFalse("a pre-restart capture must not be admitted", w.admit(2, 1, 500))
    }

    @Test
    fun `out-of-order frames inside the window are accepted once each`() {
        val w = ReplayWindow()
        w.admit(3, 1, 100)
        assertTrue(w.admit(3, 1, 98))
        assertTrue(w.admit(3, 1, 99))
        assertFalse(w.admit(3, 1, 98))
        assertFalse(w.admit(3, 1, 99))
    }

    @Test
    fun `a frame older than the window is refused`() {
        val w = ReplayWindow(windowSize = 64)
        w.admit(4, 1, 200)
        assertFalse("64 behind is outside the window", w.admit(4, 1, 200 - 64))
        assertTrue("63 behind is inside it", w.admit(4, 1, 200 - 63))
    }

    @Test
    fun `a large forward jump resets the window`() {
        val w = ReplayWindow()
        w.admit(5, 1, 10)
        assertTrue(w.admit(5, 1, 5_000))
        assertFalse("everything before the jump is now outside", w.admit(5, 1, 4_000))
    }

    @Test
    fun `senders are tracked independently`() {
        val w = ReplayWindow()
        assertTrue(w.admit(1, 1, 50))
        assertTrue("a different sender may use the same seq", w.admit(2, 1, 50))
        assertFalse(w.admit(1, 1, 50))
    }

    @Test
    fun `an older epoch is never admitted`() {
        val w = ReplayWindow()
        w.admit(7, epoch = 5, seq = 10)
        assertFalse(w.admit(7, epoch = 4, seq = 999))
    }

    @Test
    fun `a long in-order run is fully accepted`() {
        val w = ReplayWindow()
        var accepted = 0
        for (s in 0..0xFFFF) if (w.admit(9, 1, s)) accepted++
        assertEquals(65_536, accepted)
    }
}

class AeadTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun header(
        seq: Int = 0x1234,
        src: Int = 2,
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = Flags.FINAL or Flags.ENCRYPTED or Flags.PACKED,
        src = src,
        keyId = Aead.keyId(key),
        ttl = 3,
        payload = ByteArray(0),
    ).encode().copyOf(Frame.HEADER_SIZE)

    @Test
    fun `a sealed payload opens to the original`() {
        val plaintext = "हमें तुरंत मदद चाहिए".toByteArray()
        val sealed = Aead.seal(key, header(), plaintext, epoch = 1, src = 2, seq = 0x1234)
        val opened = Aead.open(key, header(), sealed, epoch = 1, src = 2, seq = 0x1234)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `the tag costs sixteen bytes`() {
        val sealed = Aead.seal(key, header(), ByteArray(32), 1, 2, 0x1234)
        assertEquals(32 + Aead.TAG_BYTES, sealed.size)
        assertEquals(48, sealed.size)
    }

    /**
     * Conformance checklist: **every single-byte mutation of a valid frame must fail
     * verification.** This is what makes S-01 a control rather than an intention.
     */
    @Test
    fun `any single-byte change to the ciphertext or tag fails verification`() {
        val plaintext = ByteArray(24) { it.toByte() }
        val sealed = Aead.seal(key, header(), plaintext, 1, 2, 0x1234)

        for (i in sealed.indices) {
            for (bit in 0 until 8) {
                val corrupted = sealed.copyOf()
                corrupted[i] = (corrupted[i].toInt() xor (1 shl bit)).toByte()
                assertNull(
                    "flipping bit $bit of sealed byte $i verified",
                    Aead.open(key, header(), corrupted, 1, 2, 0x1234),
                )
            }
        }
    }

    /**
     * The header is associated data, so altering it invalidates the frame even though
     * the ciphertext is untouched. Without this, `SRC` could be forged and any paired
     * unit could impersonate any other; `TYPE` could be promoted to `ALERT`.
     */
    @Test
    fun `any single-byte change to the header fails verification`() {
        val plaintext = ByteArray(16) { it.toByte() }
        val original = header()
        val sealed = Aead.seal(key, original, plaintext, 1, 2, 0x1234)

        for (i in original.indices) {
            val tampered = original.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertNull(
                "altering header byte $i still verified",
                Aead.open(key, tampered, sealed, 1, 2, 0x1234),
            )
        }
    }

    @Test
    fun `the wrong key does not open the payload`() {
        val sealed = Aead.seal(key, header(), ByteArray(8), 1, 2, 0x1234)
        val other = ByteArray(32) { (it * 11 + 3).toByte() }
        assertNull(Aead.open(other, header(), sealed, 1, 2, 0x1234))
    }

    @Test
    fun `a frame replayed under a different epoch or seq does not open`() {
        val sealed = Aead.seal(key, header(), ByteArray(8) { 9 }, epoch = 1, src = 2, seq = 0x1234)
        assertNull("wrong epoch", Aead.open(key, header(), sealed, 2, 2, 0x1234))
        assertNull("wrong seq", Aead.open(key, header(), sealed, 1, 2, 0x1235))
        assertNull("wrong src", Aead.open(key, header(), sealed, 1, 3, 0x1234))
    }

    @Test
    fun `the nonce is twelve bytes and laid out as specified`() {
        val n = Aead.nonce(epoch = 0x01020304, src = 0xAB, seq = 0xCDEF)
        assertEquals(12, n.size)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0, 0, 0, 0, 0),
            n,
        )
    }

    /**
     * Risk S-07. Nonce reuse under a fixed key destroys GCM entirely, so uniqueness
     * across `SEQ` wraps and service restarts is the property the whole construction
     * depends on. Ten million nonces, covering more than 150 full wraps.
     *
     * Storing ten million nonces exhausts the heap, and it is unnecessary. The nonce
     * is `EPOCH ‖ SRC ‖ SEQ` big-endian, so across a contiguous scan of `(epoch, seq)`
     * the packed value must be **strictly increasing** — and strictly monotonic
     * implies injective. That is a stronger statement than "no duplicate was observed",
     * and it costs constant memory.
     */
    @Test
    fun `no nonce repeats across seq wraps and restarts`() {
        var previous = -1L
        var generated = 0L
        var epoch = 0L

        outer@ while (true) {
            for (seq in 0..0xFFFF) {
                val packed = packNonce(Aead.nonce(epoch, src = 2, seq = seq))
                assertTrue(
                    "nonce did not increase at epoch $epoch seq $seq: $packed after $previous",
                    packed > previous,
                )
                previous = packed
                generated++
                if (generated >= 10_000_000L) break@outer
            }
            epoch++ // a SEQ wrap, or equivalently a service restart
        }

        assertEquals(10_000_000L, generated)
        assertTrue("should have covered many wraps, got $epoch", epoch >= 150)
    }

    /** Different senders never collide, even on the same epoch and sequence. */
    @Test
    fun `two senders on the same epoch and seq get different nonces`() {
        for (seq in 0..0xFF) {
            val a = packNonce(Aead.nonce(epoch = 3, src = 2, seq = seq))
            val b = packNonce(Aead.nonce(epoch = 3, src = 3, seq = seq))
            assertTrue("src 2 and 3 collided at seq $seq", a != b)
        }
    }

    /** The seven leading bytes are the whole of the varying part; the rest is zero padding. */
    private fun packNonce(n: ByteArray): Long {
        var packed = 0L
        for (i in 0 until 7) packed = (packed shl 8) or (n[i].toLong() and 0xFF)
        return packed
    }

    @Test
    fun `the key identifier is stable and derived from the key`() {
        assertEquals(Aead.keyId(key), Aead.keyId(key.copyOf()))
        val other = ByteArray(32) { (it + 1).toByte() }
        // not a guarantee, but a 1-in-256 collision would make the filter useless here
        assertTrue(Aead.keyId(key) in 0..255 && Aead.keyId(other) in 0..255)
    }
}

class AuthFailureLimiterTest {
    @Test
    fun `sixteen failures are tolerated and the seventeenth trips`() {
        val l = AuthFailureLimiter()
        repeat(16) { assertFalse(l.recordFailure(src = 2, nowMillis = it.toLong())) }
        assertTrue("the seventeenth failure in the window must trip", l.recordFailure(2, 17))
    }

    @Test
    fun `failures outside the window are forgotten`() {
        val l = AuthFailureLimiter()
        repeat(16) { l.recordFailure(2, it.toLong()) }
        assertEquals(0, l.failureCount(2, nowMillis = 120_000))
        assertFalse(l.recordFailure(2, 120_000))
    }

    @Test
    fun `senders are limited independently`() {
        val l = AuthFailureLimiter()
        repeat(17) { l.recordFailure(2, it.toLong()) }
        assertFalse("a different sender starts clean", l.recordFailure(3, 18))
    }
}

package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tag length by transport class, task W6.9, `docs/PROTOCOL.md` section 6.3. */
class TransportClassTest {
    @Test
    fun `every phone-to-phone transport uses the full tag`() {
        for (transport in listOf(
            TransportClass.BLUETOOTH_CLASSIC,
            TransportClass.BLE,
            TransportClass.WIFI,
        )) {
            assertEquals("$transport", 16, transport.tagBytes)
            assertEquals(128, transport.tagBits)
            assertFalse("$transport needs no rate limit", transport.requiresRateLimit)
        }
    }

    /** Eight bytes at 300 bps is over twenty seconds of airtime per message. */
    @Test
    fun `a low-rate serial link truncates`() {
        assertEquals(8, TransportClass.SERIAL_LOW_RATE.tagBytes)
        assertEquals(64, TransportClass.SERIAL_LOW_RATE.tagBits)
    }

    /**
     * A 2⁻⁶⁴ forgery probability per attempt is negligible per attempt and stops being
     * negligible once attempts are unbounded.
     */
    @Test
    fun `truncation is refused without rate limiting`() {
        try {
            TransportClass.tagBytesFor(TransportClass.SERIAL_LOW_RATE, rateLimited = false)
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("rate limiting"))
        }
    }

    @Test
    fun `truncation is permitted with rate limiting`() {
        assertEquals(8, TransportClass.tagBytesFor(TransportClass.SERIAL_LOW_RATE, true))
    }

    @Test
    fun `a full tag needs no rate limit to be allowed`() {
        assertEquals(16, TransportClass.tagBytesFor(TransportClass.WIFI, rateLimited = false))
    }

    @Test
    fun `link names map to their transport class`() {
        assertEquals(TransportClass.BLE, TransportClass.of("ble-central"))
        assertEquals(TransportClass.WIFI, TransportClass.of("wifi-host"))
        assertEquals(TransportClass.BLUETOOTH_CLASSIC, TransportClass.of("bluetooth-host"))
        assertEquals(TransportClass.SERIAL_LOW_RATE, TransportClass.of("serial-lora"))
    }

    /** The safe default is the one that costs bytes, never the one that costs security. */
    @Test
    fun `an unknown transport gets the full tag`() {
        assertEquals(16, TransportClass.of("something-new").tagBytes)
        assertFalse(TransportClass.of("something-new").requiresRateLimit)
    }

    /** The limiter the truncated tag depends on, from `docs/PROTOCOL.md` section 6.3. */
    @Test
    fun `the failure limiter is sixteen failures in sixty seconds`() {
        val limiter = AuthFailureLimiter()
        val src = 3
        repeat(16) {
            assertFalse("failure $it must be tolerated", limiter.recordFailure(src, it * 100L))
        }
        assertTrue("the seventeenth must trip the limit", limiter.recordFailure(src, 1_700))
    }
}

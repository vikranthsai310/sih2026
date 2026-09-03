package org.itantra.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Crc16Test {
    /**
     * The conformance checklist in `docs/PROTOCOL.md` section 14 requires this exact
     * assertion. It is the standard check value for CRC-16/CCITT-FALSE and it pins
     * every parameter at once: get the polynomial, the initial value, the reflection
     * or the final XOR wrong and this fails.
     */
    @Test
    fun `check value for 123456789 is 0x29B1`() {
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Crc16.compute(input))
    }

    @Test
    fun `empty input yields the initial value`() {
        assertEquals(0xFFFF, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun `checksum is big endian on the wire`() {
        assertArrayEquals(
            byteArrayOf(0x29.toByte(), 0xB1.toByte()),
            Crc16.toBytes(0x29B1),
        )
    }

    /** A single flipped bit anywhere must change the checksum. */
    @Test
    fun `any single bit flip changes the checksum`() {
        val frame = ByteArray(45) { (it * 7 + 3).toByte() }
        val expected = Crc16.compute(frame)

        for (byteIndex in frame.indices) {
            for (bit in 0 until 8) {
                val corrupted = frame.copyOf()
                corrupted[byteIndex] = (corrupted[byteIndex].toInt() xor (1 shl bit)).toByte()
                assertNotEquals(
                    "flipping bit $bit of byte $byteIndex went undetected",
                    expected,
                    Crc16.compute(corrupted),
                )
            }
        }
    }

    @Test
    fun `range arguments are honoured`() {
        val padded = byteArrayOf(0x00, 0x00) + "123456789".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        assertEquals(0x29B1, Crc16.compute(padded, offset = 2, length = 9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a range beyond the buffer is rejected`() {
        Crc16.compute(ByteArray(4), offset = 2, length = 8)
    }
}

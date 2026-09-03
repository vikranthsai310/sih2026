package org.itantra.proto

/**
 * CRC-16/CCITT-FALSE over the header and payload of a frame.
 *
 * Normative parameters, from `docs/PROTOCOL.md` section 1:
 *  - polynomial `0x1021`
 *  - initial value `0xFFFF`
 *  - no input reflection, no output reflection
 *  - no final XOR
 *  - check value for the ASCII string `123456789` is `0x29B1`
 *
 * This is an integrity check against corruption, **not** a security control. It is
 * computed and verified even when the payload is authenticated, because it lets a
 * corrupt frame be discarded before the more expensive AEAD verification and before
 * any relay decision.
 */
object Crc16 {
    private const val POLYNOMIAL = 0x1021
    private const val INITIAL = 0xFFFF

    /** Precomputed so the per-byte cost is a table lookup rather than eight shifts. */
    private val TABLE =
        IntArray(256) { index ->
            var crc = index shl 8
            repeat(8) {
                crc =
                    if (crc and 0x8000 != 0) {
                        (crc shl 1) xor POLYNOMIAL
                    } else {
                        crc shl 1
                    }
            }
            crc and 0xFFFF
        }

    /**
     * @param bytes buffer to checksum
     * @param offset first byte to include
     * @param length number of bytes to include
     * @return the checksum as an unsigned 16-bit value held in an [Int]
     */
    fun compute(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Int {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(length >= 0) { "length must not be negative: $length" }
        require(offset + length <= bytes.size) {
            "range $offset..${offset + length} exceeds buffer of ${bytes.size}"
        }

        var crc = INITIAL
        for (i in offset until offset + length) {
            val index = ((crc shr 8) xor (bytes[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor TABLE[index]) and 0xFFFF
        }
        return crc
    }

    /** Big-endian, as every multi-byte field on the wire is. */
    fun toBytes(crc: Int): ByteArray = byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
}

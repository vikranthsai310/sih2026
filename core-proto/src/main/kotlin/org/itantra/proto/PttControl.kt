package org.itantra.proto

/**
 * The `PTT_CTL` payload: one byte announcing that a unit has taken or released the
 * floor. `docs/PROTOCOL.md` section 2.1, task **W5.2**.
 *
 * | Value | Meaning |
 * | --- | --- |
 * | `0x01` | Seize — I am about to transmit |
 * | `0x00` | Release — I have finished |
 *
 * There is no central arbiter and no destination field; a seize is an announcement to
 * everyone in range, not a request to anyone. That is what makes the announcement
 * advisory rather than authoritative, and it is why [org.itantra.proto.PttControl]
 * cannot by itself prevent two units transmitting at once — see the contention handling
 * in `FloorControl`.
 */
enum class PttControl(val code: Int) {
    RELEASE(0x00),
    SEIZE(0x01),
    ;

    fun encode(): ByteArray = byteArrayOf(code.toByte())

    companion object {
        const val SIZE = 1

        /** A `PTT_CTL` frame is 13 bytes: 10 header, 1 payload, 2 CRC. */
        const val FRAME_SIZE = Frame.HEADER_SIZE + SIZE + Frame.CRC_SIZE

        /** @return null if the payload is the wrong size or carries a reserved value. */
        fun decode(payload: ByteArray): PttControl? {
            if (payload.size != SIZE) return null
            val code = payload[0].toInt() and 0xFF
            return entries.firstOrNull { it.code == code }
        }
    }
}

package org.itantra.proto

/**
 * Message type. Says how a frame is *handled* — queue priority, acknowledgement,
 * whether the receiver announces it on the alarm stream. How the payload is
 * *encoded* is carried in [Flags] instead, which is what makes a template-coded
 * alert expressible. See `docs/PROTOCOL.md` section 2.1.
 */
enum class MessageType(val code: Int) {
    TEXT(0x1),
    ALERT(0x2),
    ACK(0x3),
    PTT_CTL(0x4),
    HEARTBEAT(0x5),
    TEMPLATE(0x6),
    POSITION(0x7),
    AUDIO_FB(0x8),
    ;

    /** `ACK` and `HEARTBEAT` MUST NOT be relayed. */
    val relayable: Boolean get() = this != ACK && this != HEARTBEAT

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        /** @return null for the reserved codes 0x0 and 0x9..0xF, which MUST be discarded. */
        fun fromCode(code: Int): MessageType? = BY_CODE[code]
    }
}

/** Flag bits, `docs/PROTOCOL.md` section 7. */
object Flags {
    const val FINAL = 0x80
    const val PARTIAL = 0x40
    const val ENCRYPTED = 0x20
    const val FRAGMENT = 0x10
    const val PACKED = 0x08
    const val TEMPLATE = 0x04
    const val CONFIDENCE_MASK = 0x03
}

/** Why a frame was rejected. The wire behaviour is to discard silently; the reason exists for tests and logs. */
enum class RejectReason {
    TOO_SHORT,
    BAD_MAGIC,
    BAD_VERSION,
    RESERVED_TYPE,
    RESERVED_LANGUAGE,
    LENGTH_OUT_OF_RANGE,
    LENGTH_MISMATCH,
    FINAL_AND_PARTIAL,
    PACKED_AND_TEMPLATE,
    BAD_CRC,
}

sealed interface DecodeResult {
    data class Ok(val frame: Frame) : DecodeResult

    data class Rejected(val reason: RejectReason) : DecodeResult

    /** Convenience for callers that only care whether a frame survived. */
    fun orNull(): Frame? = (this as? Ok)?.frame
}

/**
 * One complete protocol message: a ten-byte header, the payload, and a two-byte
 * CRC trailer. Byte order is big-endian throughout, without exception.
 *
 * ```
 *  byte  0      1      2   3     4      5   6     7      8      9     10 …    n-2 n-1
 *      +------+------+---------+------+---------+------+------+------+-------+---------+
 *      |MAGIC | TYPE |   SEQ   |FLAGS |   LEN   | SRC  |KEYID | TTL  |PAYLOAD|  CRC16  |
 *      | VER  | LANG |         |      |         |      |      |      |       |         |
 *      +------+------+---------+------+---------+------+------+------+-------+---------+
 * ```
 *
 * There is deliberately **no destination field**. Every frame reaches every unit
 * holding the shared key, exactly as a walkie-talkie operates; selection is by
 * cryptographic key rather than by address, because address filtering is a
 * convention any transmitter may disregard. See `docs/PROTOCOL.md` section 8.
 */
data class Frame(
    val type: MessageType,
    val language: Language,
    val seq: Int,
    val flags: Int,
    val src: Int,
    val keyId: Int,
    val ttl: Int,
    val payload: ByteArray,
) {
    init {
        require(seq in 0..0xFFFF) { "seq must fit 16 bits: $seq" }
        require(flags in 0..0xFF) { "flags must fit 8 bits: $flags" }
        require(src in 0..0xFF) { "src must fit 8 bits: $src" }
        require(keyId in 0..0xFF) { "keyId must fit 8 bits: $keyId" }
        require(ttl in 0..0xFF) { "ttl must fit 8 bits: $ttl" }
        require(payload.size <= MAX_PAYLOAD) {
            "payload of ${payload.size} exceeds the $MAX_PAYLOAD byte limit"
        }
    }

    val isFinal: Boolean get() = flags and Flags.FINAL != 0
    val isPartial: Boolean get() = flags and Flags.PARTIAL != 0
    val isEncrypted: Boolean get() = flags and Flags.ENCRYPTED != 0
    val isFragment: Boolean get() = flags and Flags.FRAGMENT != 0
    val isPacked: Boolean get() = flags and Flags.PACKED != 0
    val isTemplate: Boolean get() = flags and Flags.TEMPLATE != 0
    val confidence: Int get() = flags and Flags.CONFIDENCE_MASK

    /** Total size on the wire, header and trailer included. */
    val wireSize: Int get() = HEADER_SIZE + payload.size + CRC_SIZE

    fun encode(): ByteArray {
        val out = ByteArray(wireSize)
        out[0] = ((MAGIC shl 4) or VERSION).toByte()
        out[1] = ((type.code shl 4) or language.index).toByte()
        out[2] = ((seq shr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = flags.toByte()
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        out[6] = (payload.size and 0xFF).toByte()
        out[7] = src.toByte()
        out[8] = keyId.toByte()
        out[9] = ttl.toByte()
        payload.copyInto(out, HEADER_SIZE)

        val crc = Crc16.compute(out, 0, HEADER_SIZE + payload.size)
        out[out.size - 2] = ((crc shr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    // ByteArray in a data class needs these written out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
            language == other.language &&
            seq == other.seq &&
            flags == other.flags &&
            src == other.src &&
            keyId == other.keyId &&
            ttl == other.ttl &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + seq
        result = 31 * result + flags
        result = 31 * result + src
        result = 31 * result + keyId
        result = 31 * result + ttl
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** High nibble of byte 0. A fixed sentinel used to resynchronise a corrupted byte stream. */
        const val MAGIC = 0xA

        /** Low nibble of byte 0. Increment whenever the wire format changes incompatibly. */
        const val VERSION = 0x1

        /** Byte 0 in full, which is what a stream reader scans for when resynchronising. */
        const val SENTINEL = ((MAGIC shl 4) or VERSION).toByte()

        const val HEADER_SIZE = 10
        const val CRC_SIZE = 2
        const val MAX_PAYLOAD = 1024

        /** Smallest possible frame: header, empty payload, CRC. */
        const val MIN_WIRE_SIZE = HEADER_SIZE + CRC_SIZE

        /**
         * Reads the declared payload length without validating anything else, so a
         * stream reader knows how many bytes to wait for. See `docs/PROTOCOL.md`
         * section 13.
         *
         * @return the declared length, or null if fewer than [HEADER_SIZE] bytes are available.
         */
        fun peekPayloadLength(
            bytes: ByteArray,
            offset: Int = 0,
        ): Int? {
            if (offset + HEADER_SIZE > bytes.size) return null
            return ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
        }

        fun decode(
            bytes: ByteArray,
            offset: Int = 0,
            length: Int = bytes.size - offset,
        ): DecodeResult {
            if (length < MIN_WIRE_SIZE) return DecodeResult.Rejected(RejectReason.TOO_SHORT)

            val b0 = bytes[offset].toInt() and 0xFF
            if ((b0 shr 4) != MAGIC) return DecodeResult.Rejected(RejectReason.BAD_MAGIC)
            if ((b0 and 0x0F) != VERSION) return DecodeResult.Rejected(RejectReason.BAD_VERSION)

            val b1 = bytes[offset + 1].toInt() and 0xFF
            val type =
                MessageType.fromCode(b1 shr 4)
                    ?: return DecodeResult.Rejected(RejectReason.RESERVED_TYPE)
            val language =
                Language.fromIndex(b1 and 0x0F)
                    ?: return DecodeResult.Rejected(RejectReason.RESERVED_LANGUAGE)

            val payloadLength =
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
            if (payloadLength > MAX_PAYLOAD) {
                return DecodeResult.Rejected(RejectReason.LENGTH_OUT_OF_RANGE)
            }
            val wireSize = HEADER_SIZE + payloadLength + CRC_SIZE
            if (length < wireSize) return DecodeResult.Rejected(RejectReason.LENGTH_MISMATCH)

            val flags = bytes[offset + 4].toInt() and 0xFF
            if (flags and Flags.FINAL != 0 && flags and Flags.PARTIAL != 0) {
                return DecodeResult.Rejected(RejectReason.FINAL_AND_PARTIAL)
            }
            if (flags and Flags.PACKED != 0 && flags and Flags.TEMPLATE != 0) {
                return DecodeResult.Rejected(RejectReason.PACKED_AND_TEMPLATE)
            }

            val expected = Crc16.compute(bytes, offset, HEADER_SIZE + payloadLength)
            val actual =
                ((bytes[offset + wireSize - 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + wireSize - 1].toInt() and 0xFF)
            if (expected != actual) return DecodeResult.Rejected(RejectReason.BAD_CRC)

            val payload =
                bytes.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + payloadLength)

            return DecodeResult.Ok(
                Frame(
                    type = type,
                    language = language,
                    seq = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF),
                    flags = flags,
                    src = bytes[offset + 7].toInt() and 0xFF,
                    keyId = bytes[offset + 8].toInt() and 0xFF,
                    ttl = bytes[offset + 9].toInt() and 0xFF,
                    payload = payload,
                ),
            )
        }
    }
}

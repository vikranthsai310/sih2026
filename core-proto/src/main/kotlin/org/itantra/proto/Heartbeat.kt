package org.itantra.proto

/**
 * The twelve-byte `HEARTBEAT` payload, sent every two seconds by every paired unit so
 * the interface can tell a quiet channel from a dead one.
 *
 * It carries the two values that make other safety properties work:
 *
 * - [epoch], so a joining or restarting receiver learns the sender's current epoch
 *   without a handshake, which is what keeps the derived AEAD nonce unique (S-07).
 * - [profileDigest], so two handsets holding different template tables discover it
 *   before either of them speaks the wrong sentence from the same byte (S-06).
 *
 * Layout, `docs/PROTOCOL.md` section 9:
 *
 * | Offset | Size | Field                                    |
 * |--------|------|------------------------------------------|
 * | 0      | 4    | `EPOCH`, big-endian                      |
 * | 4      | 4    | `profileDigest`, first 4 bytes of SHA-256|
 * | 8      | 1    | Battery percentage, or 0xFF if unknown   |
 * | 9      | 1    | Link quality, 0..255                     |
 * | 10     | 1    | State: 0 ready, 1 busy, 2 degraded       |
 * | 11     | 1    | Active language index                    |
 */
data class Heartbeat(
    val epoch: Long,
    val profileDigest: ByteArray,
    val batteryPercent: Int,
    val linkQuality: Int,
    val state: State,
    val language: Language,
) {
    enum class State(val code: Int) {
        READY(0),
        BUSY(1),
        DEGRADED(2),
        ;

        companion object {
            fun fromCode(code: Int): State? = entries.firstOrNull { it.code == code }
        }
    }

    init {
        require(epoch in 0..0xFFFFFFFFL) { "epoch must fit 32 bits: $epoch" }
        require(profileDigest.size == TemplateTable.DIGEST_BYTES) {
            "digest must be ${TemplateTable.DIGEST_BYTES} bytes, was ${profileDigest.size}"
        }
        require(batteryPercent in 0..100 || batteryPercent == BATTERY_UNKNOWN) {
            "battery must be 0..100 or $BATTERY_UNKNOWN, was $batteryPercent"
        }
        require(linkQuality in 0..255) { "link quality must fit 8 bits: $linkQuality" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = ((epoch shr 24) and 0xFF).toByte()
        out[1] = ((epoch shr 16) and 0xFF).toByte()
        out[2] = ((epoch shr 8) and 0xFF).toByte()
        out[3] = (epoch and 0xFF).toByte()
        profileDigest.copyInto(out, 4)
        out[8] = batteryPercent.toByte()
        out[9] = linkQuality.toByte()
        out[10] = state.code.toByte()
        out[11] = language.index.toByte()
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Heartbeat) return false
        return epoch == other.epoch &&
            profileDigest.contentEquals(other.profileDigest) &&
            batteryPercent == other.batteryPercent &&
            linkQuality == other.linkQuality &&
            state == other.state &&
            language == other.language
    }

    override fun hashCode(): Int {
        var result = epoch.hashCode()
        result = 31 * result + profileDigest.contentHashCode()
        result = 31 * result + batteryPercent
        result = 31 * result + linkQuality
        result = 31 * result + state.hashCode()
        result = 31 * result + language.hashCode()
        return result
    }

    companion object {
        const val SIZE = 12
        const val BATTERY_UNKNOWN = 0xFF

        /** A `HEARTBEAT` frame is 24 bytes: 12 header and trailer, 12 payload. */
        const val FRAME_SIZE = Frame.HEADER_SIZE + SIZE + Frame.CRC_SIZE

        /** @return null if the payload is the wrong size or carries a reserved value. */
        fun decode(payload: ByteArray): Heartbeat? {
            if (payload.size != SIZE) return null
            val state = State.fromCode(payload[10].toInt() and 0xFF) ?: return null
            val language = Language.fromIndex(payload[11].toInt() and 0x0F) ?: return null
            val battery = payload[8].toInt() and 0xFF
            if (battery !in 0..100 && battery != BATTERY_UNKNOWN) return null

            val epoch =
                ((payload[0].toLong() and 0xFF) shl 24) or
                    ((payload[1].toLong() and 0xFF) shl 16) or
                    ((payload[2].toLong() and 0xFF) shl 8) or
                    (payload[3].toLong() and 0xFF)

            return Heartbeat(
                epoch = epoch,
                profileDigest = payload.copyOfRange(4, 8),
                batteryPercent = battery,
                linkQuality = payload[9].toInt() and 0xFF,
                state = state,
                language = language,
            )
        }
    }
}

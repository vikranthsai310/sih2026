package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameTest {
    private fun sample(
        type: MessageType = MessageType.TEXT,
        language: Language = Language.HINDI,
        payload: ByteArray = ByteArray(32) { it.toByte() },
        flags: Int = Flags.FINAL or Flags.PACKED or 2,
    ) = Frame(
        type = type,
        language = language,
        seq = 0x1234,
        flags = flags,
        src = 0x02,
        keyId = 0x07,
        ttl = 3,
        payload = payload,
    )

    // ── conformance checklist, PROTOCOL.md §14 ───────────────────────────────

    @Test
    fun `decode of encode returns the original frame`() {
        val frame = sample()
        assertEquals(frame, Frame.decode(frame.encode()).orNull())
    }

    /** 10 000 randomly generated frames across all types, flags and lengths. */
    @Test
    fun `round trip holds for ten thousand random frames`() {
        val random = Random(20260903)
        repeat(10_000) {
            val payload = ByteArray(random.nextInt(0, 200)) { random.nextInt().toByte() }
            // FINAL/PARTIAL and PACKED/TEMPLATE are mutually exclusive on the wire
            val flags =
                (if (random.nextBoolean()) Flags.FINAL else Flags.PARTIAL) or
                    (if (random.nextBoolean()) Flags.ENCRYPTED else 0) or
                    (if (random.nextBoolean()) Flags.PACKED else Flags.TEMPLATE) or
                    random.nextInt(0, 4)
            val frame =
                Frame(
                    type = MessageType.entries.random(random),
                    language = Language.entries.random(random),
                    seq = random.nextInt(0, 0x10000),
                    flags = flags,
                    src = random.nextInt(0, 256),
                    keyId = random.nextInt(0, 256),
                    ttl = random.nextInt(0, 256),
                    payload = payload,
                )
            assertEquals("round trip failed for $frame", frame, Frame.decode(frame.encode()).orNull())
        }
    }

    @Test
    fun `a frame with a bad crc is rejected`() {
        val wire = sample().encode()
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0xFF).toByte()
        assertEquals(DecodeResult.Rejected(RejectReason.BAD_CRC), Frame.decode(wire))
    }

    @Test
    fun `a frame with FINAL and PARTIAL both set is discarded`() {
        val wire = sample().encode()
        wire[4] = (Flags.FINAL or Flags.PARTIAL).toByte()
        repairCrc(wire)
        assertEquals(DecodeResult.Rejected(RejectReason.FINAL_AND_PARTIAL), Frame.decode(wire))
    }

    @Test
    fun `a frame with PACKED and TEMPLATE both set is discarded`() {
        val wire = sample().encode()
        wire[4] = (Flags.FINAL or Flags.PACKED or Flags.TEMPLATE).toByte()
        repairCrc(wire)
        assertEquals(DecodeResult.Rejected(RejectReason.PACKED_AND_TEMPLATE), Frame.decode(wire))
    }

    @Test
    fun `a frame with a reserved type is discarded`() {
        for (reserved in listOf(0x0, 0x9, 0xA, 0xF)) {
            val wire = sample().encode()
            wire[1] = ((reserved shl 4) or Language.HINDI.index).toByte()
            repairCrc(wire)
            assertEquals(DecodeResult.Rejected(RejectReason.RESERVED_TYPE), Frame.decode(wire))
        }
    }

    @Test
    fun `a frame with a language above nine is discarded`() {
        for (reserved in 10..15) {
            val wire = sample().encode()
            wire[1] = ((MessageType.TEXT.code shl 4) or reserved).toByte()
            repairCrc(wire)
            assertEquals(DecodeResult.Rejected(RejectReason.RESERVED_LANGUAGE), Frame.decode(wire))
        }
    }

    @Test
    fun `a frame whose magic nibble is not 0xA is discarded`() {
        val wire = sample().encode()
        wire[0] = 0x51
        repairCrc(wire)
        assertEquals(DecodeResult.Rejected(RejectReason.BAD_MAGIC), Frame.decode(wire))
    }

    @Test
    fun `a frame of a different protocol version is discarded`() {
        val wire = sample().encode()
        wire[0] = ((Frame.MAGIC shl 4) or 0x2).toByte()
        repairCrc(wire)
        assertEquals(DecodeResult.Rejected(RejectReason.BAD_VERSION), Frame.decode(wire))
    }

    @Test
    fun `a declared length beyond the maximum is rejected before allocating`() {
        val wire = sample().encode()
        wire[5] = 0xFF.toByte()
        wire[6] = 0xFF.toByte()
        assertEquals(DecodeResult.Rejected(RejectReason.LENGTH_OUT_OF_RANGE), Frame.decode(wire))
    }

    @Test
    fun `a truncated frame is rejected rather than throwing`() {
        val wire = sample().encode()
        for (cut in 0 until wire.size) {
            val result = Frame.decode(wire.copyOf(cut))
            assertTrue("a truncated frame decoded at cut $cut", result is DecodeResult.Rejected)
        }
    }

    // ── wire-format facts the specification fixes ────────────────────────────

    @Test
    fun `the header is ten bytes and the trailer two`() {
        assertEquals(10, Frame.HEADER_SIZE)
        assertEquals(2, Frame.CRC_SIZE)
    }

    /**
     * The sizes quoted throughout the documents follow from this. A packed Hindi
     * sentence of 32 bytes is 44 bytes on the wire; a template alert of one byte
     * is 13. See `docs/PROTOCOL.md` section 1.
     */
    @Test
    fun `documented frame sizes hold`() {
        assertEquals(44, sample(payload = ByteArray(32)).wireSize)
        assertEquals(13, sample(payload = ByteArray(1), flags = Flags.FINAL or Flags.TEMPLATE).wireSize)
        assertEquals(12, sample(payload = ByteArray(0)).wireSize)
    }

    @Test
    fun `there is no destination field`() {
        // Ten header bytes with nothing left over for a destination: every frame
        // reaches every unit holding the key. PROTOCOL.md section 8.
        assertEquals(Frame.HEADER_SIZE, 1 + 1 + 2 + 1 + 2 + 1 + 1 + 1)
    }

    @Test
    fun `peek reads the declared length without validating`() {
        val frame = sample(payload = ByteArray(77))
        assertEquals(77, Frame.peekPayloadLength(frame.encode()))
        assertNull(Frame.peekPayloadLength(ByteArray(4)))
    }

    @Test
    fun `flag accessors read the right bits`() {
        val f = sample(flags = Flags.FINAL or Flags.ENCRYPTED or Flags.PACKED or 3)
        assertTrue(f.isFinal && f.isEncrypted && f.isPacked)
        assertTrue(!f.isPartial && !f.isFragment && !f.isTemplate)
        assertEquals(3, f.confidence)
    }

    @Test
    fun `acknowledgements and heartbeats are never relayed`() {
        assertTrue(!MessageType.ACK.relayable)
        assertTrue(!MessageType.HEARTBEAT.relayable)
        assertTrue(MessageType.ALERT.relayable)
        assertTrue(MessageType.TEXT.relayable)
    }

    @Test
    fun `a frame decodes from within a larger buffer`() {
        val frame = sample()
        val wire = frame.encode()
        val padded = ByteArray(5) { 0x7F } + wire + ByteArray(9) { 0x7F }
        assertEquals(frame, Frame.decode(padded, offset = 5, length = wire.size).orNull())
    }

    /** Recompute the CRC after deliberately corrupting a header field, so the test isolates one rule. */
    private fun repairCrc(wire: ByteArray) {
        val crc = Crc16.compute(wire, 0, wire.size - Frame.CRC_SIZE)
        wire[wire.size - 2] = ((crc shr 8) and 0xFF).toByte()
        wire[wire.size - 1] = (crc and 0xFF).toByte()
    }
}

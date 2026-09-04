package org.itantra.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eight-byte tag, task **W2.18**.
 *
 * ## Why this needs its own suite
 *
 * The full-tag path is one JCE call and the provider is responsible for it. The truncated
 * path is not: SunJCE rejects a 64-bit GCM tag outright — `Unsupported TLen value. Must be
 * one of {128, 120, 112, 104, 96}` — so the truncation is done in this project's own code,
 * and code that decides whether a frame is authentic has to be held to a higher standard
 * than "the round trip works".
 *
 * A truncated-tag implementation that verified nothing would pass a round-trip test
 * perfectly. Every test below is therefore about **rejection**, except the two that
 * establish the round trip works at all.
 */
class TruncatedTagTest {
    private val key = ByteArray(Aead.KEY_BYTES) { (it * 11 + 3).toByte() }
    private val header = ByteArray(Frame.HEADER_SIZE) { (it * 5).toByte() }
    private val plaintext = "सेक्टर सत्रह में तीन घायल".toByteArray(Charsets.UTF_8)

    private fun sealed(tagBytes: Int = Aead.TRUNCATED_TAG_BYTES) =
        Aead.seal(key, header, plaintext, epoch = 4, src = 2, seq = 9, tagBytes = tagBytes)

    private fun open(
        bytes: ByteArray,
        tagBytes: Int = Aead.TRUNCATED_TAG_BYTES,
        epoch: Long = 4,
        src: Int = 2,
        seq: Int = 9,
        aad: ByteArray = header,
    ) = Aead.open(key, aad, bytes, epoch, src, seq, tagBytes)

    // ── it works ─────────────────────────────────────────────────────────────

    @Test
    fun `a truncated frame opens to the original plaintext`() {
        assertArrayEquals(plaintext, open(sealed()))
    }

    @Test
    fun `the truncated tag costs eight bytes rather than sixteen`() {
        assertEquals(plaintext.size + Aead.TAG_BYTES, sealed(Aead.TAG_BYTES).size)
        assertEquals(plaintext.size + Aead.TRUNCATED_TAG_BYTES, sealed().size)
    }

    /**
     * The truncation must keep the **leading** bytes of the full tag, or it is not the
     * construction NIST SP 800-38D appendix C describes and its security argument does not
     * apply.
     */
    @Test
    fun `the truncated tag is the leading half of the full tag`() {
        val full = sealed(Aead.TAG_BYTES)
        val short = sealed()
        assertArrayEquals(full.copyOf(short.size), short)
    }

    @Test
    fun `an empty payload seals and opens`() {
        val empty = Aead.seal(key, header, ByteArray(0), 1, 1, 1, Aead.TRUNCATED_TAG_BYTES)
        assertEquals(Aead.TRUNCATED_TAG_BYTES, empty.size)
        assertArrayEquals(ByteArray(0), Aead.open(key, header, empty, 1, 1, 1, Aead.TRUNCATED_TAG_BYTES))
    }

    // ── it rejects ───────────────────────────────────────────────────────────

    /**
     * The assertion the whole construction exists to satisfy. Every single-byte change
     * anywhere in the frame must fail, exactly as the full tag does — this is W2.18's half
     * of the mutation test the audit's item 7 covers for the full tag.
     */
    @Test
    fun `every single-byte mutation of the ciphertext or tag fails`() {
        val original = sealed()
        var checked = 0
        for (index in original.indices) {
            for (bit in 0 until 8) {
                val mutated = original.copyOf()
                mutated[index] = (mutated[index].toInt() xor (1 shl bit)).toByte()
                assertNull(
                    "byte $index bit $bit verified when it should not have",
                    open(mutated),
                )
                checked++
            }
        }
        assertEquals("every byte and bit must have been tried", original.size * 8, checked)
    }

    /** The header is associated data, so altering it must invalidate the frame too. */
    @Test
    fun `every single-byte mutation of the header fails`() {
        val original = sealed()
        for (index in header.indices) {
            for (bit in 0 until 8) {
                val mutated = header.copyOf()
                mutated[index] = (mutated[index].toInt() xor (1 shl bit)).toByte()
                assertNull("header byte $index bit $bit", open(original, aad = mutated))
            }
        }
    }

    @Test
    fun `the wrong key does not open a truncated frame`() {
        val otherKey = ByteArray(Aead.KEY_BYTES) { 0x42 }
        assertNull(Aead.open(otherKey, header, sealed(), 4, 2, 9, Aead.TRUNCATED_TAG_BYTES))
    }

    /** The nonce is derived, so a frame replayed under a different epoch must not open. */
    @Test
    fun `a frame opened under the wrong epoch, sender or sequence fails`() {
        val original = sealed()
        assertNull("epoch", open(original, epoch = 5))
        assertNull("src", open(original, src = 3))
        assertNull("seq", open(original, seq = 10))
    }

    /**
     * The dangerous confusion. A frame sealed with a full tag, read as though it were
     * truncated, must not verify — otherwise an attacker could strip eight bytes off any
     * captured frame and have it accepted on the low-rate link.
     */
    @Test
    fun `a full-tag frame does not open as a truncated one`() {
        assertNull(open(sealed(Aead.TAG_BYTES)))
    }

    /** And the reverse: a truncated frame read as a full-tag one must fail. */
    @Test
    fun `a truncated frame does not open as a full-tag one`() {
        assertNull(open(sealed(), tagBytes = Aead.TAG_BYTES))
    }

    /**
     * Extending a truncated tag with the bytes an attacker would guess must not work
     * either. The re-sealed tag is compared over its leading eight bytes only, and
     * appended rubbish changes the ciphertext length, which changes what is compared.
     */
    @Test
    fun `a truncated frame with bytes appended fails`() {
        assertNull(open(sealed() + ByteArray(8)))
    }

    @Test
    fun `a frame shorter than its own tag is refused rather than throwing`() {
        assertNull(open(ByteArray(Aead.TRUNCATED_TAG_BYTES - 1)))
        assertNull(open(ByteArray(0)))
    }

    // ── the policy around it ─────────────────────────────────────────────────

    /**
     * Only the two lengths the protocol names are offered. A caller reaching for 32 bits
     * from the JCE's menu would produce something that verifies almost anything.
     */
    @Test
    fun `no tag length other than sixteen or eight is accepted`() {
        for (bad in listOf(0, 4, 6, 12, 15, 17, 32)) {
            val thrown =
                runCatching { Aead.seal(key, header, plaintext, 1, 1, 1, bad) }.exceptionOrNull()
            assertNotNull("sealing with a $bad-byte tag must be refused", thrown)
            assertNull("opening with a $bad-byte tag must fail", open(sealed(), tagBytes = bad))
        }
    }

    /**
     * A truncated tag is only defensible where forgery attempts are limited, so the
     * transport has to declare its limiter to be given one. `docs/PROTOCOL.md` section 6.
     */
    @Test
    fun `a truncated tag is refused for a transport with no rate limiter`() {
        val thrown =
            runCatching {
                TransportClass.tagBytesFor(TransportClass.SERIAL_LOW_RATE, rateLimited = false)
            }.exceptionOrNull()
        assertNotNull("an unlimited link must not get a short tag", thrown)

        assertEquals(
            8,
            TransportClass.tagBytesFor(TransportClass.SERIAL_LOW_RATE, rateLimited = true),
        )
        assertEquals(
            "a full tag needs no limiter",
            16,
            TransportClass.tagBytesFor(TransportClass.BLE, rateLimited = false),
        )
    }

    @Test
    fun `Bluetooth and Wi-Fi keep the full tag because bytes are free there`() {
        for (transport in listOf(TransportClass.BLUETOOTH_CLASSIC, TransportClass.BLE, TransportClass.WIFI)) {
            assertEquals(transport.name, Aead.TAG_BYTES, transport.tagBytes)
            assertFalse("${transport.name} needs no rate limit", transport.requiresRateLimit)
        }
        assertTrue(TransportClass.SERIAL_LOW_RATE.requiresRateLimit)
    }
}

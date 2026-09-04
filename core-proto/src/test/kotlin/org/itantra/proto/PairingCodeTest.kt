package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Pairing codes, task W6.11.
 *
 * A camera reads whatever is put in front of it, so a scanned code is untrusted input in
 * the strictest sense — these tests are mostly about refusing things.
 */
class PairingCodeTest {
    private val digest = ByteArray(TemplateTable.DIGEST_BYTES) { it.toByte() }
    private val now = 1_700_000_000_000L

    private fun code(nowMillis: Long = now) =
        PairingCode.generate(
            keyId = 7,
            profileId = "sih-2026",
            profileDigest = digest,
            nodeId = 2,
            nowMillis = nowMillis,
        )

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun `a generated code round-trips through its encoding`() {
        val original = code()
        val parsed = PairingCode.decode(original.encode(), now)!!

        assertEquals(original.keyId, parsed.keyId)
        assertEquals(original.profileId, parsed.profileId)
        assertEquals(original.nodeId, parsed.nodeId)
        assertEquals(original.tagBits, parsed.tagBits)
        assertEquals(original.key().toList(), parsed.key().toList())
        assertEquals(original.profileDigest.toList(), parsed.profileDigest.toList())
    }

    @Test
    fun `the key is thirty-two bytes`() {
        assertEquals(32, code().key().size)
    }

    /** `Random` output is predictable from a few samples; a key must not be. */
    @Test
    fun `two codes never share a key`() {
        val keys = (1..50).map { code().key().toList() }
        assertEquals("every key must be distinct", 50, keys.toSet().size)
    }

    @Test
    fun `the key comes from SecureRandom`() {
        // Injecting a fixed-seed SecureRandom proves the generator is actually used
        // rather than something else being substituted internally.
        val fixed = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3)) }
        val a =
            PairingCode.generate(1, "p", digest, 1, now, random = fixed)
        val fixedAgain = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3)) }
        val b =
            PairingCode.generate(1, "p", digest, 1, now, random = fixedAgain)
        assertEquals(a.key().toList(), b.key().toList())
    }

    // ── expiry fails closed ──────────────────────────────────────────────────

    /** A photograph of a screen is a key otherwise. */
    @Test
    fun `a code expires after two minutes`() {
        val c = code()
        assertFalse(c.isExpired(now + 119_000))
        assertTrue(c.isExpired(now + PairingCode.VALIDITY_MILLIS))
    }

    @Test
    fun `an expired code is refused on scan`() {
        val text = code().encode()
        assertNull(PairingCode.decode(text, now + PairingCode.VALIDITY_MILLIS))
        assertNull(PairingCode.decode(text, now + 600_000))
    }

    /**
     * A forged code claiming a long life must not buy one. The rule is enforced on the
     * scanning side, not merely on the generating side.
     */
    @Test
    fun `a code claiming more than the permitted lifetime is refused`() {
        val forged =
            PairingCode(
                key = ByteArray(32) { 9 },
                keyId = 1,
                profileId = "p",
                profileDigest = digest,
                tagBits = 128,
                nodeId = 1,
                expiresAtMillis = now + 86_400_000,
            )
        assertNull("a day-long code must be refused", PairingCode.decode(forged.encode(), now))
    }

    @Test
    fun `the countdown reaches zero and does not go negative`() {
        val c = code()
        assertEquals(120, c.secondsRemaining(now))
        assertEquals(0, c.secondsRemaining(now + 600_000))
    }

    // ── malformed input ──────────────────────────────────────────────────────

    @Test
    fun `arbitrary text is refused`() {
        for (text in listOf(
            "",
            "hello",
            "iT1",
            "iT1:not-base64!:7:p:AAAA:128:2:$now",
            "XX1:" + "a".repeat(43) + ":7:p:AAAA:128:2:$now",
            "iT1:a:b:c:d:e:f:g",
        )) {
            assertNull("'$text' should be refused", PairingCode.decode(text, now))
        }
    }

    @Test
    fun `a code with the wrong field count is refused`() {
        val parts = code().encode().split(":")
        assertNull(PairingCode.decode(parts.dropLast(1).joinToString(":"), now))
        assertNull(PairingCode.decode(code().encode() + ":extra", now))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        val parts = code().encode().split(":").toMutableList()
        parts[1] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        assertNull(PairingCode.decode(parts.joinToString(":"), now))
    }

    @Test
    fun `an implausible tag length is refused`() {
        val parts = code().encode().split(":").toMutableList()
        parts[5] = "32"
        assertNull(PairingCode.decode(parts.joinToString(":"), now))
    }

    @Test
    fun `identifiers that do not fit their wire fields are refused`() {
        val parts = code().encode().split(":").toMutableList()
        parts[2] = "256"
        assertNull(PairingCode.decode(parts.joinToString(":"), now))
    }

    // ── the key must not leak through the ordinary routes ────────────────────

    /**
     * Risk S-04. The most likely way key material escapes is not an attacker but a
     * developer printing an object into a log or a crash report.
     */
    @Test
    fun `toString never contains the key`() {
        val c = code()
        val rendered = c.toString()
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(c.key())

        assertFalse("the key must not appear in toString", rendered.contains(encoded))
        assertTrue(rendered.contains("redacted"))
        // A few raw bytes rendered as numbers would be just as bad.
        assertFalse(rendered.contains(c.key().joinToString(",")))
    }

    @Test
    fun `the key is copied in and out, so no caller shares our array`() {
        val source = ByteArray(32) { 5 }
        val c =
            PairingCode(source, 1, "p", digest, 128, 1, now + 1_000)

        source.fill(9)
        assertEquals("mutating the source must not change the code", 5.toByte(), c.key()[0])

        c.key().fill(7)
        assertEquals("mutating a returned copy must not change the code", 5.toByte(), c.key()[0])
    }

    @Test
    fun `destroy overwrites the key in memory`() {
        val c = code()
        assertNotEquals(ByteArray(32).toList(), c.key().toList())
        c.destroy()
        assertEquals("the key must be zeroed", ByteArray(32).toList(), c.key().toList())
    }

    @Test
    fun `the profile digest travels with the code, so a mismatch is caught before speech`() {
        val parsed = PairingCode.decode(code().encode(), now)!!
        assertEquals(digest.toList(), parsed.profileDigest.toList())
    }
}

package org.itantra.proto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for the payload: AES-256 in Galois/Counter Mode.
 *
 * The problem statement does not ask for this. It is here because the system wakes a
 * locked, silenced handset and announces at maximum volume on command; a device with
 * that capability and no authentication is a way to inject a fraudulent evacuation
 * order into a disaster area. Risk **S-01**. Address filtering is a convention any
 * transmitter may disregard — **the key is the only real boundary.**
 *
 * Two decisions carry most of the weight, both from `docs/PROTOCOL.md` section 6:
 *
 * **The whole header is associated data.** `SRC`, `TYPE`, `FLAGS`, `SEQ` and `KEYID`
 * are all bound into the tag, so none can be altered without invalidating the frame.
 * If `SRC` sat outside it, any paired unit could impersonate any other; if `TYPE` did,
 * an attacker could promote a `TEXT` frame to an `ALERT`.
 *
 * **The nonce is derived, never transmitted.** `EPOCH ‖ SRC ‖ SEQ ‖ padding` saves
 * twelve bytes on every frame — a quarter of the payload budget on a constrained
 * link. The cost is that `EPOCH` must be persisted correctly, because nonce reuse
 * under a fixed key destroys GCM completely. That makes `EPOCH` persistence a
 * correctness-critical path, tracked as risk **S-07**.
 */
object Aead {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8

    /** The truncated tag a low-rate serial link uses. See [TransportClass]. */
    const val TRUNCATED_TAG_BYTES = 8

    /**
     * Builds the nonce for a frame.
     *
     * ```
     *   EPOCH (4, big-endian) ‖ SRC (1) ‖ SEQ (2, big-endian) ‖ 0x00 × 5
     * ```
     *
     * Uniqueness rests on the sender never reusing an `(EPOCH, SEQ)` pair under one
     * key: `SEQ` wraps at 65 536 and `EPOCH` increments on every wrap and every
     * service start.
     */
    fun nonce(
        epoch: Long,
        src: Int,
        seq: Int,
    ): ByteArray {
        require(epoch in 0..0xFFFFFFFFL) { "epoch must fit 32 bits: $epoch" }
        require(src in 0..0xFF) { "src must fit 8 bits: $src" }
        require(seq in 0..0xFFFF) { "seq must fit 16 bits: $seq" }

        val out = ByteArray(NONCE_BYTES)
        out[0] = ((epoch shr 24) and 0xFF).toByte()
        out[1] = ((epoch shr 16) and 0xFF).toByte()
        out[2] = ((epoch shr 8) and 0xFF).toByte()
        out[3] = (epoch and 0xFF).toByte()
        out[4] = src.toByte()
        out[5] = ((seq shr 8) and 0xFF).toByte()
        out[6] = (seq and 0xFF).toByte()
        // bytes 7..11 stay zero
        return out
    }

    /** First byte of SHA-256 of the key: a cheap reject filter, never a security control. */
    fun keyId(key: ByteArray): Int {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, was ${key.size}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
        return digest[0].toInt() and 0xFF
    }

    /**
     * Seals [plaintext], binding [header] into the tag.
     *
     * @param header the complete ten-byte frame header, used as associated data.
     * @return ciphertext followed by the 16-byte tag.
     */
    fun seal(
        key: ByteArray,
        header: ByteArray,
        plaintext: ByteArray,
        epoch: Long,
        src: Int,
        seq: Int,
        tagBytes: Int = TAG_BYTES,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, was ${key.size}" }
        require(header.size == Frame.HEADER_SIZE) {
            "associated data must be the whole ${Frame.HEADER_SIZE}-byte header, was ${header.size}"
        }
        requireSupportedTag(tagBytes)
        val full = sealFullTag(key, header, plaintext, nonce(epoch, src, seq))
        if (tagBytes == TAG_BYTES) return full

        // Truncation, per NIST SP 800-38D appendix C: keep the *leading* bytes of the tag
        // and discard the rest. The ciphertext is unchanged and sits before the tag, so
        // taking the first `ciphertext + tagBytes` bytes is exactly the truncated frame.
        return full.copyOf(full.size - TAG_BYTES + tagBytes)
    }

    private fun sealFullTag(
        key: ByteArray,
        header: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(header)
        return cipher.doFinal(plaintext)
    }

    /**
     * Tag lengths this project will produce. Task **W2.18**.
     *
     * GCM admits shorter tags than 16 bytes and most of them are a bad idea. Eight is the
     * shortest length with a forgery probability low enough to defend at all — 2⁻⁶⁴ per
     * attempt — and it is only defensible *because* [AuthFailureLimiter] caps attempts at
     * sixteen per minute per sender. Nothing below eight is offered, so no future caller
     * can pick 32 bits from the JCE's menu and produce something that verifies almost
     * anything.
     *
     * [TransportClass] decides which of the two applies, so the choice is a property of
     * the link rather than of the call site.
     */
    private fun requireSupportedTag(tagBytes: Int) {
        require(tagBytes == TAG_BYTES || tagBytes == TRUNCATED_TAG_BYTES) {
            "tag must be $TAG_BYTES or $TRUNCATED_TAG_BYTES bytes, was $tagBytes"
        }
    }

    /**
     * Opens a sealed payload.
     *
     * @return the plaintext, or **null** if verification fails. Failure is silent by
     *   design: a frame that does not authenticate is discarded without telling the
     *   sender anything, and without ever reaching the speaker.
     */
    fun open(
        key: ByteArray,
        header: ByteArray,
        sealed: ByteArray,
        epoch: Long,
        src: Int,
        seq: Int,
        tagBytes: Int = TAG_BYTES,
    ): ByteArray? {
        if (key.size != KEY_BYTES || header.size != Frame.HEADER_SIZE) return null
        if (tagBytes != TAG_BYTES && tagBytes != TRUNCATED_TAG_BYTES) return null
        // A payload shorter than its own tag cannot be opened, and asking the cipher to
        // try would surface as an exception rather than as the silent discard the contract
        // promises.
        if (sealed.size < tagBytes) return null
        val nonce = nonce(epoch, src, seq)

        return try {
            if (tagBytes == TAG_BYTES) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                cipher.updateAAD(header)
                cipher.doFinal(sealed)
            } else {
                openTruncated(key, header, sealed, nonce, tagBytes)
            }
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    /**
     * Opens a frame whose tag was truncated on the wire.
     *
     * ## Why this is not one `doFinal` call
     *
     * The JCE will not do it. SunJCE — and the Android provider with it — accepts a GCM tag
     * length of 128, 120, 112, 104 or 96 bits and **rejects 64**, which is the length
     * `docs/PROTOCOL.md` specifies for a low-rate link. Discovered by trying it: the
     * exception is `Unsupported TLen value`.
     *
     * The options were to move the protocol to a 12-byte tag the platform will verify, or
     * to do the truncation ourselves. The protocol wins, because eight bytes is 27 seconds
     * of airtime on a 300 bps link and that saving is the entire argument for the
     * truncation existing.
     *
     * ## What it does, and why it is the same thing
     *
     * GCM decryption is two separable operations: CTR decryption of the ciphertext, and a
     * GHASH over the associated data and ciphertext compared against the tag. Since GCM
     * encryption is deterministic for a fixed key, nonce and associated data, the tag can
     * be recomputed rather than verified in place:
     *
     * 1. CTR-decrypt the ciphertext. GCM's keystream for the payload starts at the second
     *    counter block, `nonce ‖ 00000002`, so that is the IV handed to CTR.
     * 2. Re-seal the recovered plaintext with the **full** tag.
     * 3. Require the re-sealed ciphertext to equal what arrived, and the leading
     *    [tagBytes] of the recomputed tag to equal the truncated tag that arrived.
     *
     * Step 3 is exactly the comparison a 64-bit GCM verification would perform. Both
     * halves are compared in constant time, because a length-dependent or content-dependent
     * early exit would leak how much of a forged tag was correct — which over the sixteen
     * attempts a minute [AuthFailureLimiter] allows is the difference between a bound of
     * 2⁻⁶⁴ and a bound of nothing.
     *
     * ## The condition that makes 64 bits defensible at all
     *
     * A truncated tag is only sound where forgery attempts are limited, which is why
     * [TransportClass.tagBytesFor] refuses to hand one out for a transport that has not
     * declared its rate limiter. That is not decoration; at 2⁻⁶⁴ per attempt an
     * unrestricted attacker is a different calculation entirely.
     */
    private fun openTruncated(
        key: ByteArray,
        header: ByteArray,
        sealed: ByteArray,
        nonce: ByteArray,
        tagBytes: Int,
    ): ByteArray? {
        val ciphertextLength = sealed.size - tagBytes
        if (ciphertextLength < 0) return null
        val ciphertext = sealed.copyOf(ciphertextLength)
        val arrivedTag = sealed.copyOfRange(ciphertextLength, sealed.size)

        val plaintext = ctr(key, nonce, ciphertext)
        val resealed = sealFullTag(key, header, plaintext, nonce)
        if (resealed.size != ciphertextLength + TAG_BYTES) return null

        val matchesCiphertext = constantTimeEquals(resealed, 0, ciphertext, 0, ciphertextLength)
        val matchesTag = constantTimeEquals(resealed, ciphertextLength, arrivedTag, 0, tagBytes)
        return if (matchesCiphertext and matchesTag) plaintext else null
    }

    /**
     * AES-CTR over [input] with GCM's payload counter.
     *
     * For a 96-bit nonce, GCM sets `J0 = nonce ‖ 00000001` and encrypts the payload from
     * `inc32(J0)`, so the CTR initial block is `nonce ‖ 00000002`. Getting that wrong
     * produces plaintext that is wrong in every byte, which the comparison above would
     * catch — but it would catch it as "not authentic", which is the most misleading
     * possible symptom.
     */
    private fun ctr(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val counter = ByteArray(16)
        nonce.copyInto(counter, 0, 0, NONCE_BYTES)
        counter[15] = 2
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(counter),
        )
        return cipher.doFinal(input)
    }

    /** No early exit: the loop runs the full length whatever it finds. */
    private fun constantTimeEquals(
        a: ByteArray,
        aFrom: Int,
        b: ByteArray,
        bFrom: Int,
        length: Int,
    ): Boolean {
        if (a.size < aFrom + length || b.size < bFrom + length) return false
        var difference = 0
        for (i in 0 until length) {
            difference = difference or (a[aFrom + i].toInt() xor b[bFrom + i].toInt())
        }
        return difference == 0
    }
}

/**
 * Rate-limits authentication failures from one sender.
 *
 * A tag that fails is either corruption the CRC missed or a forgery attempt, and the
 * two are indistinguishable from here. More than [limit] failures from one `SRC`
 * within [windowMillis] puts the link into `DEGRADED` and warns the operator, so a
 * sustained attempt is visible rather than silent.
 */
class AuthFailureLimiter(
    private val limit: Int = 16,
    private val windowMillis: Long = 60_000,
) {
    private val failures = HashMap<Int, ArrayDeque<Long>>()

    /** @return true if this sender has now exceeded the limit. */
    fun recordFailure(
        src: Int,
        nowMillis: Long,
    ): Boolean {
        val times = failures.getOrPut(src) { ArrayDeque() }
        times.addLast(nowMillis)
        while (times.isNotEmpty() && nowMillis - times.first() > windowMillis) {
            times.removeFirst()
        }
        return times.size > limit
    }

    fun failureCount(
        src: Int,
        nowMillis: Long,
    ): Int {
        val times = failures[src] ?: return 0
        return times.count { nowMillis - it <= windowMillis }
    }

    fun reset(src: Int) {
        failures.remove(src)
    }
}

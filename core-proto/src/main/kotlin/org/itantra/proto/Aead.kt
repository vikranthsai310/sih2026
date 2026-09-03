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
    ): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, was ${key.size}" }
        require(header.size == Frame.HEADER_SIZE) {
            "associated data must be the whole ${Frame.HEADER_SIZE}-byte header, was ${header.size}"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(epoch, src, seq)),
        )
        cipher.updateAAD(header)
        return cipher.doFinal(plaintext)
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
    ): ByteArray? {
        if (key.size != KEY_BYTES || header.size != Frame.HEADER_SIZE) return null
        if (sealed.size < TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(epoch, src, seq)),
            )
            cipher.updateAAD(header)
            cipher.doFinal(sealed)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
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

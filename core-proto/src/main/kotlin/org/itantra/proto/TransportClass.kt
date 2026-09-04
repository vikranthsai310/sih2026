package org.itantra.proto

// Declared at file level rather than in the companion: enum entry arguments are
// evaluated before the companion object is initialised, so the companion cannot
// supply them.
private const val FULL = 16
private const val TRUNCATED = 8

/**
 * How long the AEAD tag is, chosen by transport class. Task **W6.9**,
 * `docs/PROTOCOL.md` section 6.3.
 *
 * ## Why this is not simply "always 16 bytes"
 *
 * On Bluetooth and Wi-Fi bytes are free and the full 128-bit tag is used. On a serial
 * link to a LoRa or HF radio at 300 bps, eight bytes is over twenty seconds of airtime
 * per message, which changes what the system can do rather than merely how fast it does
 * it.
 *
 * ## Truncation is only sound with the rate limit
 *
 * A 64-bit tag raises the per-attempt forgery probability to 2⁻⁶⁴. That is negligible
 * *per attempt* and stops being negligible if an attacker can make attempts without
 * bound. So truncation is permitted **only** on transports where
 * [AuthFailureLimiter] is enforced — more than 16 failed verifications from one `SRC`
 * within 60 s puts the link into `DEGRADED`.
 *
 * [requiresRateLimit] states that dependency in code rather than leaving it in prose,
 * and [Companion.tagBytesFor] refuses to hand out a truncated tag for a transport that has
 * not declared the limiter.
 */
enum class TransportClass(val tagBytes: Int) {
    /** Bytes are free. Full tag. */
    BLUETOOTH_CLASSIC(FULL),
    BLE(FULL),
    WIFI(FULL),

    /**
     * 300 bps to a LoRa or HF radio. Eight bytes saved is 27 seconds of airtime.
     *
     * Not currently used — the project is phone-to-phone — but the protocol has to
     * describe it, because the compression argument is aimed at exactly this kind of
     * link and a reviewer will ask.
     */
    SERIAL_LOW_RATE(TRUNCATED),
    ;

    val tagBits: Int get() = tagBytes * 8

    /** A truncated tag is only defensible where failures are rate-limited. */
    val requiresRateLimit: Boolean get() = tagBytes < FULL

    companion object {
        const val FULL_TAG_BYTES = FULL
        const val TRUNCATED_TAG_BYTES = TRUNCATED

        /**
         * @param rateLimited whether the receiver enforces [AuthFailureLimiter] on this
         *   link
         * @throws IllegalArgumentException if a transport that needs the limiter does not
         *   have it. Failing here is the point: a truncated tag without a bound on
         *   attempts is a weaker system that looks like a stronger one.
         */
        fun tagBytesFor(
            transport: TransportClass,
            rateLimited: Boolean,
        ): Int {
            require(!transport.requiresRateLimit || rateLimited) {
                "$transport uses a ${transport.tagBytes}-byte tag, which is only sound " +
                    "with authentication-failure rate limiting"
            }
            return transport.tagBytes
        }

        /** Maps a [Link] name to its class, so the policy follows the actual transport. */
        fun of(linkName: String): TransportClass =
            when {
                linkName.startsWith("ble") -> BLE
                linkName.startsWith("wifi") -> WIFI
                linkName.startsWith("bluetooth") -> BLUETOOTH_CLASSIC
                linkName.startsWith("serial") -> SERIAL_LOW_RATE
                // An unknown transport gets the full tag. The safe default is the one
                // that costs bytes, never the one that costs security.
                else -> BLUETOOTH_CLASSIC
            }
    }
}

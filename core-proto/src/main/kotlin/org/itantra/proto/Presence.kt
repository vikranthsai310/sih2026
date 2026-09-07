package org.itantra.proto

/**
 * What a unit says about itself, every few seconds: its name, and — while it is being
 * looked for — where it is.
 *
 * Carried in a **sealed** `HEARTBEAT` frame, so a name or a position is as authentic as a
 * message. (The unsealed `HEARTBEAT` that announces the epoch is a different thing, and is
 * told apart by the `ENCRYPTED` flag; see `docs/PROTOCOL.md` section 9.)
 *
 * ## Why the name travels
 *
 * A node id is one byte and means nothing to a person. The screen that lists who is on
 * the channel, and the screen that leads an operator to one of them, both need what the
 * other unit calls itself — which the operator can edit, and which therefore cannot be
 * derived from anything this unit already knows.
 *
 * ## Why position is optional, and only ever sent on request
 *
 * A unit's position is sent only while another unit has asked to find it ([Locate]), and
 * for a bounded time after. Nothing reports position in the background. That is what
 * keeps the location permission this feature needs inside constraint C2's spirit: the
 * radio still never touches a network, and a position leaves the handset only on the
 * channel, sealed, to units holding the key, at the operator's own request.
 *
 * ## Layout
 *
 * ```
 *  0      1       2        3 ..           +4 +4     +2   +1  +1
 *  ver    flags   nameLen  name (UTF-8)   lat lon   acc  age batt
 * ```
 *
 * Latitude and longitude are signed 32-bit micro-degrees, big-endian; accuracy is metres,
 * unsigned 16-bit; age is seconds since the fix, one byte, capped. Position bytes are
 * present only when `flags` bit 0 is set.
 */
data class Presence(
    val name: String,
    val position: Position? = null,
    /** Whether this unit is currently beaconing for a locator. */
    val beaconing: Boolean = false,
    /** Whether this unit is on an open line rather than push-to-talk. */
    val openLine: Boolean = false,
    /** 0..100, or [BATTERY_UNKNOWN]. */
    val batteryPercent: Int = BATTERY_UNKNOWN,
) {
    data class Position(
        val latitude: Double,
        val longitude: Double,
        /** Horizontal accuracy, metres. */
        val accuracyMetres: Int,
        /** Seconds since the fix was taken, as the sender knew it. */
        val ageSeconds: Int,
    )

    fun encode(): ByteArray {
        val nameBytes = clip(name)
        val out = ArrayList<Byte>(HEADER + nameBytes.size + POSITION_BYTES + 1)
        out += VERSION.toByte()
        var flags = 0
        if (position != null) flags = flags or HAS_POSITION
        if (beaconing) flags = flags or BEACONING
        if (openLine) flags = flags or OPEN_LINE
        out += flags.toByte()
        out += nameBytes.size.toByte()
        for (b in nameBytes) out += b
        position?.let { p ->
            putInt(out, (p.latitude * MICRO).toInt())
            putInt(out, (p.longitude * MICRO).toInt())
            val acc = p.accuracyMetres.coerceIn(0, 0xFFFF)
            out += (acc shr 8).toByte()
            out += acc.toByte()
            out += p.ageSeconds.coerceIn(0, 0xFF).toByte()
        }
        out += batteryPercent.coerceIn(0, 0xFF).toByte()
        return out.toByteArray()
    }

    companion object {
        const val VERSION = 1
        const val MAX_NAME_BYTES = 24
        const val BATTERY_UNKNOWN = 0xFF

        private const val HAS_POSITION = 0x01
        private const val BEACONING = 0x02
        private const val OPEN_LINE = 0x04
        private const val HEADER = 3
        private const val POSITION_BYTES = 4 + 4 + 2 + 1
        private const val MICRO = 1_000_000.0

        /** @return null for a payload this version cannot read, rather than a guess. */
        fun decode(payload: ByteArray): Presence? {
            if (payload.size < HEADER + 1) return null
            if (payload[0].toInt() and 0xFF != VERSION) return null
            val flags = payload[1].toInt() and 0xFF
            val nameLen = payload[2].toInt() and 0xFF
            if (nameLen > MAX_NAME_BYTES) return null
            var at = HEADER
            if (at + nameLen > payload.size) return null
            val name = String(payload, at, nameLen, Charsets.UTF_8)
            at += nameLen
            var position: Position? = null
            if (flags and HAS_POSITION != 0) {
                if (at + POSITION_BYTES > payload.size) return null
                val lat = getInt(payload, at) / MICRO
                val lon = getInt(payload, at + 4) / MICRO
                val acc = ((payload[at + 8].toInt() and 0xFF) shl 8) or (payload[at + 9].toInt() and 0xFF)
                val age = payload[at + 10].toInt() and 0xFF
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                position = Position(lat, lon, acc, age)
                at += POSITION_BYTES
            }
            if (at >= payload.size) return null
            val battery = payload[at].toInt() and 0xFF
            return Presence(
                name = name,
                position = position,
                beaconing = flags and BEACONING != 0,
                openLine = flags and OPEN_LINE != 0,
                batteryPercent = battery,
            )
        }

        /**
         * A name as it will travel: at most [MAX_NAME_BYTES] of UTF-8, never cut inside a
         * character. Twenty-four bytes is eight Devanagari letters or twenty-four Latin ones,
         * which is a call sign, not a sentence.
         */
        fun clip(name: String): ByteArray {
            val trimmed = name.trim()
            var bytes = trimmed.toByteArray(Charsets.UTF_8)
            var end = trimmed.length
            while (bytes.size > MAX_NAME_BYTES && end > 0) {
                end--
                bytes = trimmed.substring(0, end).toByteArray(Charsets.UTF_8)
            }
            return bytes
        }

        private fun putInt(
            out: MutableList<Byte>,
            value: Int,
        ) {
            out += (value shr 24).toByte()
            out += (value shr 16).toByte()
            out += (value shr 8).toByte()
            out += value.toByte()
        }

        private fun getInt(
            bytes: ByteArray,
            at: Int,
        ): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
    }
}

/**
 * "Find unit N": asks one unit to start (or stop) beaconing its position and signal so a
 * locator can walk to it. Carried in a sealed `POSITION` frame, which relays, so the
 * request reaches a unit three hops away.
 *
 * ```
 *  0    1    2
 *  ver  cmd  target
 * ```
 */
data class Locate(
    val target: Int,
    val start: Boolean,
) {
    fun encode(): ByteArray = byteArrayOf(VERSION.toByte(), (if (start) START else STOP).toByte(), target.toByte())

    companion object {
        const val VERSION = 1
        private const val START = 1
        private const val STOP = 2

        fun decode(payload: ByteArray): Locate? {
            if (payload.size < 3) return null
            if (payload[0].toInt() and 0xFF != VERSION) return null
            val start =
                when (payload[1].toInt() and 0xFF) {
                    START -> true
                    STOP -> false
                    else -> return null
                }
            return Locate(target = payload[2].toInt() and 0xFF, start = start)
        }
    }
}

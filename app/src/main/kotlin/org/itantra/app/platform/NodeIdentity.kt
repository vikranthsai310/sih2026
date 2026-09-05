package org.itantra.app.platform

import org.itantra.proto.Aead
import java.security.MessageDigest

/**
 * Which unit this handset is on the net.
 *
 * ## Why an identity has to exist before anything can be sent
 *
 * `SRC` is one byte and there is no destination field: every frame carries who it came
 * from and nothing about where it is going. Two handsets that take the same `SRC` do not
 * merely look confusing — each one **drops the other's frames as its own transmission**,
 * because that is how a broadcast net stops a unit relaying itself into a loop. The net
 * would appear to be up, with no traffic ever arriving, and nothing on either screen would
 * say why.
 *
 * So the id must be stable across restarts and distinct between devices without anybody
 * configuring it.
 *
 * ## Derived from the installation id
 *
 * `Settings.Secure.ANDROID_ID` is unique per app-signing-key per device and survives
 * restarts. It is hashed rather than used directly — the low byte of a raw id is not
 * uniformly distributed — and folded into 1..254.
 *
 * Zero and 255 are avoided: `SRC 0` reads as "unset" in a hex dump and 255 is the natural
 * broadcast-looking value, so both are the ones a reader would misinterpret first.
 *
 * ## A collision is possible and is not silently ignored
 *
 * Two devices in 254 have about a 0.4 % chance of colliding, and by six units it is 6 %.
 * That is too high to shrug at, which is why [collidesWith] exists and the engine watches
 * for a peer announcing this unit's own id. The real fix is assignment at pairing —
 * task W6.11 — and until that exists this is the honest approximation rather than a
 * pretence that one byte of hash is unique.
 */
class NodeIdentity(
    val src: Int,
    val displayName: String,
) {
    init {
        require(src in MIN_SRC..MAX_SRC) { "src must be $MIN_SRC..$MAX_SRC, was $src" }
    }

    /**
     * Which of two units listens and which dials, decided without negotiation.
     *
     * RFCOMM needs exactly one side to accept and one to connect. Both dialling leaves
     * nobody listening; both listening leaves nobody dialling; and negotiating it needs a
     * channel, which is the thing being established. Comparing ids is deterministic, needs
     * no messages, and gives the same answer on both handsets.
     *
     * @return true if **this** unit should dial [other]
     */
    fun dials(other: Int): Boolean = src > other

    fun collidesWith(other: Int): Boolean = src == other

    companion object {
        const val MIN_SRC = 1
        const val MAX_SRC = 254

        /**
         * @param installationId `Settings.Secure.ANDROID_ID`, or any stable per-device
         *   string. Passed in rather than read here so this class stays testable and free
         *   of Android.
         */
        fun of(
            installationId: String,
            displayName: String,
        ): NodeIdentity = NodeIdentity(srcFor(installationId), displayName)

        /** SHA-256 folded into 1..254. */
        fun srcFor(installationId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray())
            val value = digest[0].toInt() and 0xFF
            return MIN_SRC + value % (MAX_SRC - MIN_SRC + 1)
        }

        /**
         * The shared key, until pairing exists.
         *
         * **This is not a secret.** It is a fixed development key so that two handsets
         * built from this repository can talk without a pairing flow, and every unit
         * everywhere holds the same one. It gives the frames integrity against corruption
         * and nothing at all against an attacker who has read this file.
         *
         * It is here, named this, rather than hidden in a constant called `KEY`, because
         * the one thing that must not happen is this shipping quietly. Task **W6.11**
         * replaces it with a key distributed at pairing; until then the interface shows an
         * UNSECURED banner and `SECURITY.md` audit item 5 stays open.
         */
        fun developmentKey(): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest("itantra-development-key-not-a-secret".toByteArray())
                .copyOf(Aead.KEY_BYTES)
    }
}

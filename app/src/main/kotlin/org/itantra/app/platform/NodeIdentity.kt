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
 * ## A collision is possible, and is currently not detected
 *
 * Two devices in 254 have about a 0.4 % chance of colliding, and by six units it is 6 %.
 * When it happens, `Session.receive` drops the other unit's frames as `NOT_FOR_US` —
 * "own transmission" — because that is how a broadcast net stops a unit relaying itself
 * into a loop. The net shows `LINK OK` and nothing ever arrives.
 *
 * There is no automatic check for it, and there was one that did not work: it compared this
 * unit's id, derived from the installation id, against a peer's id derived from that peer's
 * MAC address. Two different inputs, so the comparison meant nothing. Telling them apart
 * properly needs the sequence numbers this unit has actually sent — a frame carrying our
 * `SRC` and a `SEQ` we never used is a collision, and one carrying a `SEQ` we did use is
 * our own frame relayed back. That is not built yet.
 *
 * The real fix is assignment at pairing, task **W6.11**. Until then `docs/ON_DEVICE.md`
 * names the symptom and the workaround, which is honest rather than solved.
 *
 * ## This is not what decides who dials
 *
 * It was, and it was wrong for the same reason. See
 * [org.itantra.link.PeerPreference].
 */
class NodeIdentity(
    val src: Int,
    val displayName: String,
) {
    init {
        require(src in MIN_SRC..MAX_SRC) { "src must be $MIN_SRC..$MAX_SRC, was $src" }
    }

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

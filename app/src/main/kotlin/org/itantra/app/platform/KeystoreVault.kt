package org.itantra.app.platform

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the shared key in Android Keystore. Task **W6.11**, risk **S-04**.
 *
 * ## The rule
 *
 * > The key goes straight into Keystore on scan. The decoded QR string is never written
 * > to disk.
 *
 * DataStore, `SharedPreferences`, a file in app storage, a log line and a crash report
 * are all "disk". Keystore is different in kind: on a device with hardware backing the
 * key material never leaves the secure element, so a `SecretKey` handed out by
 * [secretKey] is a *reference*, not the bytes. An attacker with a rooted handset and a
 * full filesystem image gets the reference and not the key.
 *
 * ## What this deliberately does not offer
 *
 * There is no method that returns the key as a `ByteArray`. Not because a caller could
 * not eventually get at it, but because an API that hands out raw key bytes is an API
 * that will one day appear in a log line. Everything that needs the key takes a
 * `SecretKey` and passes it to `Cipher`.
 *
 * ## Not user-authentication bound
 *
 * `setUserAuthenticationRequired` would be stronger, and is wrong here: an alert must be
 * decryptable on a locked handset in a pocket without anyone entering a PIN. That is a
 * deliberate trade recorded rather than an oversight — see `docs/SECURITY.md`.
 */
class KeystoreVault(private val keyStore: KeyStore = androidKeyStore()) {
    /**
     * Imports [key] under [alias], replacing any existing entry.
     *
     * The caller should zero its copy immediately afterwards; [PairingCode.destroy] does
     * this for the pairing path.
     */
    fun store(
        alias: String,
        key: ByteArray,
    ) {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        val spec = SecretKeySpec(key, KeyProperties.KEY_ALGORITHM_AES)
        keyStore.setEntry(
            alias,
            KeyStore.SecretKeyEntry(spec),
            KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                // No padding with GCM, and randomised encryption is disabled because the
                // nonce is derived from EPOCH and SEQ rather than generated per message —
                // see Aead. Keystore rejects a caller-supplied IV unless this is false.
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
    }

    /** @return the key reference, or null if this unit has never been paired. */
    fun secretKey(alias: String): SecretKey? = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    fun has(alias: String): Boolean = keyStore.containsAlias(alias)

    /** Removes a key. Used when a handset is lost and the channel is re-keyed. */
    fun remove(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    fun aliases(): List<String> = keyStore.aliases().toList().filter { it.startsWith(PREFIX) }

    companion object {
        const val KEY_BYTES = 32
        const val PREFIX = "itantra."

        /** One alias per `KEYID`, so a re-key can be rolled out without a flag day. */
        fun aliasFor(keyId: Int): String = "$PREFIX$keyId"

        fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
}

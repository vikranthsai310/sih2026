package org.itantra.models

/**
 * Decides when the active language may change, and in what order. Task **W4.6**.
 *
 * `docs/MODELS.md` section 4: *"Unload outgoing, load incoming. Blocked while the floor
 * is held. The only runtime operation permitted to exceed one second."*
 *
 * ## Why this is a class and not two lines in the service
 *
 * One acoustic model and one voice are resident at a time, on a handset chosen for
 * being cheap. Loading the incoming pack before unloading the outgoing one would need
 * both resident at once and is the most likely way to be killed by the out-of-memory
 * reaper — during a language switch, which is to say in front of a jury.
 *
 * The ordering is therefore mandatory rather than incidental, and the three refusals
 * below each prevent a failure that would otherwise be silent.
 */
class LanguageSwitch(private val manifest: Manifest) {
    sealed interface Decision {
        /** Carry out the steps in this order. Unload always precedes load. */
        data class Proceed(val unload: String?, val load: String) : Decision

        /** Already there. Doing the work anyway would drop audio for no reason. */
        data class AlreadyActive(val lang: String) : Decision

        /**
         * Someone is mid-transmission. Switching now would unload the model decoding
         * their speech and lose the utterance.
         */
        data object FloorHeld : Decision

        data class NotInManifest(val lang: String) : Decision

        /** The pack is known but not on the device. Offer the download, do not switch. */
        data class NotInstalled(val lang: String, val downloadBytes: Long) : Decision
    }

    /**
     * @param from the active language, or null at first start
     * @param to the requested language
     * @param floorHeld true while this unit or a peer is transmitting
     * @param isInstalled whether a pack's files are present and verified
     * @param sharedModelHeld whether the shared acoustic model is already on the device
     */
    fun decide(
        from: String?,
        to: String,
        floorHeld: Boolean,
        isInstalled: (String) -> Boolean,
        sharedModelHeld: Boolean = true,
    ): Decision {
        if (from == to) return Decision.AlreadyActive(to)
        manifest.pack(to) ?: return Decision.NotInManifest(to)

        // Checked before the floor, so a request for a language the device does not
        // have reports the real problem rather than "busy".
        if (!isInstalled(to) || !sharedModelHeld) {
            return Decision.NotInstalled(to, manifest.downloadBytes(to, sharedModelHeld))
        }
        if (floorHeld) return Decision.FloorHeld

        return Decision.Proceed(unload = from, load = to)
    }

    /**
     * Whether the interface should offer [lang] at all.
     *
     * A language with no voice can still be selected: it recognises and it displays,
     * it just cannot speak. Three of the ten are in that position, so hiding them would
     * remove most of the country.
     */
    fun isOffered(lang: String): Boolean = manifest.pack(lang) != null

    /** Languages that can be recognised but not spoken aloud. */
    fun silentLanguages(): List<String> = manifest.packs.filter { !it.canSpeak }.map { it.lang }
}

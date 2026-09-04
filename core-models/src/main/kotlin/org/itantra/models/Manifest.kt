package org.itantra.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `models/manifest.json` — what a pack contains, how big it is, and what it hashes to.
 *
 * ## The shape, and why it changed
 *
 * The design document assumed ten independent acoustic models, one per language. The
 * published IndicConformer is **one multilingual model** covering all ten, so the
 * manifest carries a single [shared] acoustic-model entry plus a per-language [packs]
 * entry holding only the vocabulary, the voice and the rules. Task **W4.4**, risk T-17.
 *
 * Getting this wrong the other way would be expensive: ten copies of a 120 MB model is
 * 1.2 GB of downloads for something the device only ever needs once.
 */
@Serializable
data class Manifest(
    val manifestVersion: Int,
    val shared: SharedModel,
    val packs: List<Pack>,
) {
    init {
        require(manifestVersion == SUPPORTED_VERSION) {
            "manifest version $manifestVersion, this build understands $SUPPORTED_VERSION"
        }
        val duplicates = packs.groupBy { it.lang }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate language packs: $duplicates" }
    }

    fun pack(lang: String): Pack? = packs.firstOrNull { it.lang == lang }

    /** Total bytes to fetch for [lang], counting the shared model only if not yet held. */
    fun downloadBytes(
        lang: String,
        sharedAlreadyHeld: Boolean,
    ): Long {
        val pack = pack(lang) ?: return 0
        return pack.totalBytes + if (sharedAlreadyHeld) 0 else shared.bytes
    }

    companion object {
        const val SUPPORTED_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @throws IllegalArgumentException if the document is malformed or declares a
         *   version this build does not understand. A manifest that cannot be trusted
         *   must not half-load: it is the index of everything else.
         */
        fun parse(text: String): Manifest = json.decodeFromString(serializer(), text)
    }
}

/**
 * The one acoustic model, shared by every language.
 *
 * `~120 MB int8`, so [Artefact.bytes] is the single largest download in the project and
 * the one whose interruption matters most — see [ResumePlan].
 */
@Serializable
data class SharedModel(
    val family: String,
    val licence: String,
    val files: List<String>,
    val bytes: Long,
    val sha256: String,
    val numThreads: Int = 2,
) {
    init {
        require(files.isNotEmpty()) { "shared model declares no files" }
        require(bytes > 0) { "shared model declares $bytes bytes" }
        requireSha256(sha256, "shared model")
    }
}

/** One language: its vocabulary, its voice, its normalisation rules. */
@Serializable
data class Pack(
    val lang: String,
    val index: Int,
    val displayName: String,
    val script: String,
    @SerialName("blockBase") val blockBase: String? = null,
    val vocabulary: Artefact,
    val tts: Voice?,
    val rules: Rules,
    /**
     * Calibrated per language in week 7 and held here rather than in code, because a
     * threshold that is right for Hindi is wrong for Odia. Task **W4.16**.
     */
    val confidenceLow: Double,
    val confidenceHigh: Double,
) {
    init {
        require(index in 0..15) { "$lang: index $index does not fit the 4-bit LANG field" }
        require(confidenceLow < confidenceHigh) {
            "$lang: confidenceLow $confidenceLow must be below confidenceHigh $confidenceHigh"
        }
    }

    /** A language with no voice can be recognised and read, but never spoken aloud. */
    val canSpeak: Boolean get() = tts != null

    val totalBytes: Long get() = vocabulary.bytes + (tts?.bytes ?: 0)
}

@Serializable
data class Artefact(
    val files: List<String>,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(files.isNotEmpty()) { "artefact declares no files" }
        require(bytes > 0) { "artefact declares $bytes bytes" }
        requireSha256(sha256, "artefact")
    }
}

@Serializable
data class Voice(
    val family: String,
    val licence: String,
    val files: List<String>,
    val bytes: Long,
    val sha256: String,
    val sampleRate: Int,
) {
    init {
        require(bytes > 0) { "voice declares $bytes bytes" }
        require(sampleRate in 8_000..48_000) { "implausible sample rate $sampleRate" }
        requireSha256(sha256, "voice")
    }
}

@Serializable
data class Rules(val files: List<String>, val version: Int)

/**
 * A hash that is not 64 hex characters cannot be a SHA-256, and a manifest carrying a
 * placeholder must fail here rather than at install time — by then the bytes are on disk
 * and the failure looks like a corrupt download rather than a bad manifest.
 */
internal fun requireSha256(
    value: String,
    what: String,
) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$what: '$value' is not a lowercase 64-character SHA-256"
    }
}

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
    /**
     * The multilingual shared encoder, or **null**, which is what ships.
     *
     * It was never optional in the schema and the manifest carried a block describing it
     * with an all-zero hash, because the artefact does not exist: the only IndicConformer
     * export published in sherpa-onnx form is one self-contained int8 model per language,
     * and those are in each pack's `asr` block. A block that describes an intention is not
     * a manifest entry, so this is nullable and the shipped manifest says null.
     */
    val shared: SharedModel? = null,
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
        return pack.totalBytes + if (sharedAlreadyHeld) 0 else (shared?.bytes ?: 0)
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
    /**
     * The recogniser for this language: one self-contained int8 model and its tokens.
     *
     * Absent from this class until 2026-09-06, while the manifest carried it all along and
     * `ignoreUnknownKeys` quietly dropped it. The consequence was [totalBytes], which is
     * meant to be what a language costs to fetch and was missing the ~197 MB that dominates
     * it — the number happened to look plausible only because the fictional shared model
     * was being added instead.
     */
    val asr: AsrModel? = null,
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

    /**
     * What this language costs to fetch: its recogniser and its voice.
     *
     * The vocabulary is deliberately **not** counted. Those files are copied into the APK
     * at build time from `models/lexicon/` — `app/build.gradle.kts` does it — so they are
     * already on the handset and are not part of any download. Counting them was harmless
     * only while the figure was fiction; now that it is measured, four kilobytes of
     * already-installed lexicon must not appear in a download estimate.
     */
    val totalBytes: Long get() = (asr?.bytes ?: 0) + (tts?.bytes ?: 0)
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
 * One language's recogniser, as published rather than as designed.
 *
 * [artefacts] is a list because the model and its token table are fetched separately and
 * hashed separately: eight of the ten share one Indic token table, so the same artefact
 * appears in eight packs and is downloaded once.
 */
@Serializable
data class AsrModel(
    val family: String,
    val licence: String,
    val source: String = "",
    val baseUrl: String = "",
    val artefacts: List<Artefact>,
) {
    init {
        require(artefacts.isNotEmpty()) { "an ASR model with no artefacts cannot be fetched" }
    }

    val bytes: Long get() = artefacts.sumOf { it.bytes }
}

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
    // The comment above promised this and the check did not deliver it: sixty-four zeros
    // are sixty-four lowercase hex characters, so every placeholder in the manifest passed
    // the guard written to stop them. Seventeen did, for months.
    require(value != PLACEHOLDER_SHA256) {
        "$what: the hash is all zeros, which is a placeholder rather than a digest. " +
            "Run tools/build_manifest_hashes.py"
    }
}

/** Not a hash any file has. Named so the check that rejects it reads as intent. */
private const val PLACEHOLDER_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"

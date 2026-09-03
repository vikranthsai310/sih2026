package org.itantra.proto

import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.max

/**
 * Level 3 compression: a shared table mapping common operational sentences to
 * single-byte identifiers.
 *
 * In an emergency the vocabulary is small and predictable, so a whole sentence
 * becomes one byte of payload — a 13-byte frame, transmitted in 0.3 s over a
 * 300 bps link.
 *
 * **The property that falls out for free.** Every device holds the table in all ten
 * languages, so a template-coded message is spoken in whichever language the
 * *receiver* has selected. A Hindi speaker's alert reaches a Tamil speaker in Tamil,
 * with no translation model, no extra download and no extra latency. That applies to
 * template traffic only; free-form text is delivered in the language it was spoken
 * in, and the report claims nothing wider.
 *
 * **Why the digest exists.** Two devices holding different tables would render
 * different sentences from the same byte — `0x02` meaning "evacuate immediately" on
 * one handset and "position secure" on another. That is a safety defect, not a
 * compatibility inconvenience, so [digest] is advertised in every `HEARTBEAT` and a
 * mismatch disables template sending. Risk **S-06**, `docs/PROTOCOL.md` section 5.2.
 */
class TemplateTable private constructor(
    val profileId: Int,
    private val byId: Map<Int, Map<Language, String>>,
) {
    /** Identifiers present, in ascending order. */
    val ids: List<Int> get() = byId.keys.sorted()

    val size: Int get() = byId.size

    /**
     * Renders a template in a chosen language — the cross-language path.
     *
     * @return the sentence, or null if this identifier or language is absent.
     */
    fun render(
        id: Int,
        language: Language,
    ): String? = byId[id]?.get(language)

    /**
     * Finds the best template for recognised text.
     *
     * Matching is normalised-token similarity against the table in the **sender's**
     * language. A match is accepted only when the ratio is at least [threshold]
     * **and** the recogniser was independently confident: a confident recognition of
     * the wrong sentence and a hesitant recognition of the right one are both unsafe,
     * so both conditions are required. See `docs/PROTOCOL.md` section 5.3.
     *
     * @return the identifier, or null to fall back to Level 2 script packing.
     */
    fun match(
        text: String,
        language: Language,
        recogniserConfident: Boolean,
        threshold: Double = ACCEPT_THRESHOLD,
    ): Int? {
        if (!recogniserConfident) return null
        val needle = normalise(text)
        if (needle.isEmpty()) return null

        var bestId: Int? = null
        var bestScore = 0.0
        for ((id, translations) in byId) {
            val candidate = translations[language] ?: continue
            val score = similarity(needle, normalise(candidate))
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return if (bestScore >= threshold) bestId else null
    }

    /** Similarity of two already-normalised strings, 0.0 to 1.0. */
    fun scoreAgainst(
        text: String,
        id: Int,
        language: Language,
    ): Double {
        val candidate = byId[id]?.get(language) ?: return 0.0
        return similarity(normalise(text), normalise(candidate))
    }

    /**
     * Canonical serialisation: identifiers ascending, languages by index, one entry
     * per line. Stable across platforms, which is what makes [digest] comparable.
     */
    fun canonicalForm(): String =
        buildString {
            append(profileId).append('\n')
            for (id in ids) {
                for (language in Language.entries) {
                    val text = byId[id]?.get(language) ?: continue
                    append(id).append('\t')
                        .append(language.index).append('\t')
                        .append(Normalizer.normalize(text, Normalizer.Form.NFC))
                        .append('\n')
                }
            }
        }

    /**
     * First four bytes of SHA-256 over [canonicalForm], advertised in `HEARTBEAT`.
     * Degrading to script packing costs bytes; speaking the wrong sentence costs more.
     */
    val digest: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalForm().toByteArray(Charsets.UTF_8))
            .copyOf(DIGEST_BYTES)
    }

    fun digestMatches(other: ByteArray): Boolean = digest.contentEquals(other)

    companion object {
        const val ACCEPT_THRESHOLD = 0.85
        const val DIGEST_BYTES = 4
        const val MAX_ID = 255

        /**
         * @param entries identifier to translations. Identifiers must be 1..255;
         *   zero is reserved so an empty payload is never a valid template.
         */
        fun of(
            profileId: Int,
            entries: Map<Int, Map<Language, String>>,
        ): TemplateTable {
            require(profileId in 0..0xFFFF) { "profileId must fit 16 bits: $profileId" }
            for (id in entries.keys) {
                require(id in 1..MAX_ID) { "template id must be 1..$MAX_ID, was $id" }
            }
            return TemplateTable(profileId, entries.toMap())
        }

        /** Lower-case, punctuation stripped, whitespace collapsed, NFC. */
        internal fun normalise(text: String): List<String> =
            Normalizer.normalize(text, Normalizer.Form.NFC)
                .lowercase()
                .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotBlank() }

        /**
         * Token-level Levenshtein similarity: 1.0 minus the edit distance over the
         * longer token count. Token-level rather than character-level because a
         * recogniser's errors are whole words, not letters.
         */
        internal fun similarity(
            a: List<String>,
            b: List<String>,
        ): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val distance = editDistance(a, b)
            return 1.0 - distance.toDouble() / max(a.size, b.size)
        }

        private fun editDistance(
            a: List<String>,
            b: List<String>,
        ): Int {
            var previous = IntArray(b.size + 1) { it }
            var current = IntArray(b.size + 1)
            for (i in 1..a.size) {
                current[0] = i
                for (j in 1..b.size) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.size]
        }
    }
}

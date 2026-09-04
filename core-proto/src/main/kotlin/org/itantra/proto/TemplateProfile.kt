package org.itantra.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `templates.json` — one deployment profile, in all ten languages. Task **W7.4**.
 *
 * ## Why a template must carry every language or none
 *
 * The cross-language property (`docs/PROTOCOL.md` section 5.1) is not a feature the
 * receiver opts into. A Hindi handset sends byte `0x04`; a Tamil handset renders template 4
 * in Tamil and speaks it. If template 4 is missing its Tamil text, [TemplateTable.render]
 * returns null and the Tamil operator hears **nothing at all** — not a garbled sentence
 * they would query, but silence they will read as "no traffic".
 *
 * A profile with nine languages on one row is therefore refused at load. It is the kind of
 * gap that survives review — the file looks fine, the sender's language is present, and
 * nothing fails until the one handset that needed it is in the field.
 *
 * ## Why the profile is data and the table is code
 *
 * [TemplateTable] holds the matching rule, the canonical serialisation and the digest —
 * safety-critical logic that belongs in the module with the coverage gate. This file holds
 * only the sentences, which change per deployment and are edited by people who are not
 * going to rebuild an APK.
 */
@Serializable
data class TemplateProfile(
    val profileId: Int,
    @SerialName("profile") val name: String,
    val version: Int,
    /** False until a native speaker has checked the translations. */
    val reviewed: Boolean = false,
    val note: String? = null,
    val templates: List<TemplateEntry>,
) {
    init {
        require(profileId in 0..0xFFFF) { "profileId must fit 16 bits: $profileId" }
        require(templates.isNotEmpty()) { "profile '$name' carries no templates" }

        val duplicates = templates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "profile '$name': duplicate template ids $duplicates" }

        for (entry in templates) {
            require(entry.id in 1..TemplateTable.MAX_ID) {
                "profile '$name': template id ${entry.id} is outside 1..${TemplateTable.MAX_ID}"
            }
            val present = entry.text.keys
            val known = Language.entries.map { it.code }.toSet()
            val unknown = present - known
            require(unknown.isEmpty()) {
                "profile '$name': template ${entry.id} names unknown languages $unknown"
            }
            val missing = known - present
            // The assertion this class exists for. See the note above.
            require(missing.isEmpty()) {
                "profile '$name': template ${entry.id} has no text for $missing; " +
                    "a receiver in one of those languages would hear silence"
            }
            for ((code, text) in entry.text) {
                require(text.isNotBlank()) { "profile '$name': template ${entry.id} is blank in $code" }
            }
        }
    }

    fun toTable(): TemplateTable {
        val byCode = Language.entries.associateBy { it.code }
        return TemplateTable.of(
            profileId = profileId,
            entries =
                templates.associate { entry ->
                    entry.id to entry.text.mapKeys { (code, _) -> byCode.getValue(code) }
                },
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @throws IllegalArgumentException if the profile is malformed or incomplete. It
         *   must not half-load: a partially loaded table still produces a digest, two
         *   devices would then disagree about what a byte means, and that is the safety
         *   defect section 5.2 exists to prevent.
         */
        fun parse(text: String): TemplateProfile = json.decodeFromString(serializer(), text)
    }
}

/** One template: an identifier and the same sentence in every language. */
@Serializable
data class TemplateEntry(
    val id: Int,
    val text: Map<String, String>,
)

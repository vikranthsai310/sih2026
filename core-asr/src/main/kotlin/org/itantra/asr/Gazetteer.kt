package org.itantra.asr

/**
 * Place names, sectors and call signs for one deployment. Task **W7.5**.
 *
 * ## Why this is separate from the domain lexicon
 *
 * The domain lexicon ships in the language pack and is the same everywhere: मदद, आग,
 * घायल. A gazetteer is loaded **per deployment** and is different in every one — the
 * villages around this district, the sector numbering this operation is using, the call
 * signs assigned this morning.
 *
 * They also differ in how they fail. A missing domain word costs accuracy. A missing
 * place name costs the **one word in the sentence that says where to go**, and it is a
 * proper noun no acoustic model has ever seen. That is why gazetteer entries are weighted
 * above the domain lexicon: not because they are more common, but because they are less
 * recoverable from context.
 *
 * ## Sector numbers are generated, not listed
 *
 * A deployment using sectors 1 to 40 should not need forty lines in a file. [expand]
 * generates them, including the spoken forms, so a gazetteer stays something a
 * coordinator can write in a text editor under time pressure.
 */
class Gazetteer(
    val deployment: String,
    val places: List<String>,
    val callSigns: List<String>,
    val sectorRange: IntRange? = null,
) {
    val size: Int get() = places.size + callSigns.size + (sectorRange?.count() ?: 0)

    /**
     * Every term this gazetteer contributes, at its weight.
     *
     * @param spokenNumber renders a sector number in the target language, so "sector 17"
     *   is biased as the words an operator actually says rather than as digits
     */
    fun terms(spokenNumber: (Int) -> String = { it.toString() }): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (place in places) out[place.trim()] = PLACE_WEIGHT
        for (sign in callSigns) out[sign.trim()] = CALL_SIGN_WEIGHT
        sectorRange?.forEach { number ->
            // Both forms: the recogniser may produce either, and biasing only the digits
            // would miss the far more common spoken one.
            out["sector ${spokenNumber(number)}"] = SECTOR_WEIGHT
            out[spokenNumber(number)] = SECTOR_WEIGHT
        }
        out.remove("")
        return out
    }

    /** Folds this gazetteer into an existing lexicon, keeping the higher weight. */
    fun applyTo(
        lexicon: BiasingLexicon,
        spokenNumber: (Int) -> String = { it.toString() },
    ): BiasingLexicon {
        val merged = LinkedHashMap(lexicon.phrases())
        for ((phrase, weight) in terms(spokenNumber)) {
            merged.merge(phrase, weight) { existing, new -> maxOf(existing, new) }
        }
        return BiasingLexicon.parse(
            merged.entries.joinToString("\n") { "${it.key}\t${it.value}" },
        )
    }

    companion object {
        /**
         * Above the domain lexicon's 1.5. A place name is the one word in the sentence
         * that says where to go, and it is unrecoverable from context.
         */
        const val PLACE_WEIGHT = 2.5
        const val CALL_SIGN_WEIGHT = 2.5
        const val SECTOR_WEIGHT = 2.0

        /**
         * Parses a deployment gazetteer.
         *
         * ```
         *   # Cuttack district, 4 September
         *   deployment: cuttack-2026-09
         *   sectors: 1-40
         *   place: Jagatpur
         *   callsign: Alpha
         * ```
         *
         * Deliberately a flat text format rather than JSON: this file is written by a
         * coordinator on a laptop in a relief camp, and a missing brace should not cost
         * them the gazetteer.
         */
        fun parse(text: String): Gazetteer {
            var deployment = "unnamed"
            val places = ArrayList<String>()
            val callSigns = ArrayList<String>()
            var sectors: IntRange? = null

            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val key = line.substringBefore(':', "").trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (value.isEmpty()) continue

                when (key) {
                    "deployment" -> deployment = value
                    "place" -> places += value
                    "callsign", "call sign" -> callSigns += value
                    "sectors" -> sectors = parseRange(value)
                    // An unknown key is ignored rather than fatal. A typo should cost one
                    // line, not the whole file.
                    else -> Unit
                }
            }
            return Gazetteer(deployment, places, callSigns, sectors)
        }

        /** `1-40`, or a single number. Returns null for anything implausible. */
        private fun parseRange(value: String): IntRange? {
            val parts = value.split('-').map { it.trim().toIntOrNull() }
            return when {
                parts.size == 2 && parts[0] != null && parts[1] != null -> {
                    val from = parts[0]!!
                    val to = parts[1]!!
                    // A gazetteer asking for ten thousand sectors is a typo, and biasing
                    // on ten thousand phrases would slow every decode.
                    if (from in 0..MAX_SECTOR && to in from..MAX_SECTOR) from..to else null
                }
                parts.size == 1 && parts[0] != null ->
                    parts[0]!!.let { if (it in 0..MAX_SECTOR) it..it else null }
                else -> null
            }
        }

        const val MAX_SECTOR = 500
    }
}

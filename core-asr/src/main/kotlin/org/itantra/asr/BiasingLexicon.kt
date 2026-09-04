package org.itantra.asr

/**
 * The phrase list the decoder's scores are boosted against. Tasks **W4.13**–**W4.15**.
 *
 * Contextual biasing is the highest-yield accuracy work available, because it needs no
 * retraining: the decoder simply scores a supplied list of phrases more favourably. In a
 * distress context the critical vocabulary is small and known in advance —
 * `docs/ASR.md` section 3.4.
 *
 * ## Three sources, three weights
 *
 * | Source | Weight | Why |
 * | --- | --- | --- |
 * | Domain lexicon | 1.5 | Distress and operational vocabulary, shipped per language |
 * | **Negation** | **4.0** | Risk **S-03** — see below |
 * | Roster names | 2.5 | Call signs and unit names, injected at runtime (W4.15) |
 *
 * ### Why negation is weighted far above everything else
 *
 * *"Do not evacuate"* recognised as *"now evacuate"* is the most dangerous single
 * failure this system can produce. It is not a garbled message that an operator will
 * question — it is a fluent, plausible instruction that means the opposite of what was
 * said, and it will be acted on. Weighting negation terms high makes the decoder
 * strongly prefer hearing them where they were spoken, and the asymmetry is deliberate:
 * a spurious "not" is an obvious confusion that gets queried, while a dropped one kills
 * people.
 */
class BiasingLexicon private constructor(
    private val entries: Map<String, Double>,
) {
    val size: Int get() = entries.size

    /** Phrases and their boost scores, ready to hand to the decoder. */
    fun phrases(): Map<String, Double> = entries

    fun weightOf(phrase: String): Double? = entries[phrase.trim()]

    operator fun contains(phrase: String): Boolean = entries.containsKey(phrase.trim())

    /**
     * Adds the roster's display names at [ROSTER_WEIGHT]. Task **W4.15**.
     *
     * Unit names are the words whose misrecognition is most costly and least
     * predictable — they are proper nouns, often not in any training corpus, and they
     * are exactly what a message is addressed to. They are known only at runtime, so
     * they cannot be shipped in the pack.
     */
    fun withRoster(displayNames: Collection<String>): BiasingLexicon {
        if (displayNames.isEmpty()) return this
        val merged = LinkedHashMap(entries)
        for (name in displayNames) {
            val clean = name.trim()
            if (clean.isEmpty()) continue
            // A roster name never lowers a weight already assigned — negation wins.
            merged.merge(clean, ROSTER_WEIGHT) { existing, new -> maxOf(existing, new) }
        }
        return BiasingLexicon(merged)
    }

    companion object {
        const val DOMAIN_WEIGHT = 1.5
        const val NEGATION_WEIGHT = 4.0
        const val ROSTER_WEIGHT = 2.5

        /**
         * Parses a lexicon file.
         *
         * Format: one phrase per line. `#` starts a comment. A line may carry an
         * explicit weight after a tab; otherwise [defaultWeight] applies. Blank lines
         * and duplicates are tolerated — a lexicon is edited by hand under time
         * pressure, and refusing to load over a stray blank line would be absurd.
         */
        fun parse(
            text: String,
            defaultWeight: Double = DOMAIN_WEIGHT,
        ): BiasingLexicon {
            val entries = LinkedHashMap<String, Double>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split('\t', limit = 2)
                val phrase = parts[0].trim()
                if (phrase.isEmpty()) continue
                val weight = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: defaultWeight
                entries.merge(phrase, weight) { existing, new -> maxOf(existing, new) }
            }
            return BiasingLexicon(entries)
        }

        /**
         * Builds the full list for a language: domain terms, then negation terms at
         * their much higher weight.
         *
         * Negation is merged last and with `max`, so a term appearing in both lists
         * keeps the negation weight. Getting that precedence backwards would silently
         * undo W4.14.
         */
        fun of(
            domain: String,
            negation: String,
        ): BiasingLexicon {
            val merged = LinkedHashMap(parse(domain, DOMAIN_WEIGHT).entries)
            for ((phrase, weight) in parse(negation, NEGATION_WEIGHT).entries) {
                merged.merge(phrase, weight) { existing, new -> maxOf(existing, new) }
            }
            return BiasingLexicon(merged)
        }

        fun empty(): BiasingLexicon = BiasingLexicon(emptyMap())
    }
}

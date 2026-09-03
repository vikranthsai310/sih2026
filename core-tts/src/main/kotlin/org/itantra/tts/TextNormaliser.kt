package org.itantra.tts

/**
 * Turns recognised text into something a synthesiser can pronounce.
 *
 * No neural synthesiser handles raw digits, units or abbreviations correctly, and
 * those are exactly what operational messages contain. This stage is ordinary
 * software engineering rather than machine learning, and it accounts for most of the
 * perceived output quality — the difference between a synthesiser that sounds
 * professional and one that sounds broken.
 *
 * ## The rule that does the work
 *
 * Distinguishing a **quantity** from an **identifier** is a rule, not a model:
 *
 * - a bare digit run in a dialling or call-sign context — three digits alone, or any
 *   run of five or more — is read **digit-wise**;
 * - a digit run followed by a unit is read as a **number**, and the unit expanded;
 * - a run in `HH:MM` shape is read as a **time**;
 * - anything else is read as a cardinal number.
 *
 * Ordered; first match wins. Adding a language needs a rule set, not a code change.
 * See `docs/TTS.md` section 1.
 */
class TextNormaliser(private val rules: NormalisationRules) {
    fun normalise(text: String): String {
        var out = text
        for (rule in rules.ordered) {
            out = rule.regex.replace(out) { m -> rule.render(m, rules) }
        }
        return out.replace(MULTI_SPACE, " ").trim()
    }

    private companion object {
        val MULTI_SPACE = Regex("\\s{2,}")
    }
}

/** One ordered rule. Shipped as data per language, not compiled in. */
data class NormalisationRule(
    val id: String,
    val regex: Regex,
    val render: (MatchResult, NormalisationRules) -> String,
)

/**
 * A language's rule set, plus the lexicon the rules draw on.
 *
 * @param units symbol to spoken word, for example `km` to किलोमीटर
 * @param callsignMarkers words that put a following digit run into call-sign context
 */
class NormalisationRules(
    val language: String,
    val units: Map<String, String>,
    val callsignMarkers: Set<String>,
    val digitwise: (String) -> String,
    val cardinal: (Long) -> String,
    val time: (Int, Int) -> String,
    val ordered: List<NormalisationRule>,
)

/** Hindi rules. The reference set; every other language follows this shape. */
object HindiRules {
    private val UNITS =
        mapOf(
            "km" to "किलोमीटर",
            "m" to "मीटर",
            "cm" to "सेंटीमीटर",
            "kg" to "किलोग्राम",
            "g" to "ग्राम",
            "l" to "लीटर",
            "hr" to "घंटा",
            "h" to "घंटा",
            "min" to "मिनट",
            "s" to "सेकंड",
            "%" to "प्रतिशत",
        )

    /** A digit run after one of these is an identifier, never a quantity. */
    private val CALLSIGN_MARKERS =
        setOf("सेक्टर", "यूनिट", "चैनल", "नोड", "sector", "unit", "channel", "node")

    val rules: NormalisationRules by lazy {
        lateinit var self: NormalisationRules

        val ordered =
            listOf(
                // 1. HH:MM -- a time, before any bare-number rule can claim the digits.
                NormalisationRule(
                    id = "time-24h",
                    regex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b"""),
                ) { m, r ->
                    r.time(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                },
                // 2. A quantity followed by a unit: 5 km -> पाँच किलोमीटर.
                NormalisationRule(
                    id = "quantity-with-unit",
                    regex = Regex("""\b(\d+)\s*(km|cm|kg|min|hr|m|g|l|h|s|%)\b""", RegexOption.IGNORE_CASE),
                ) { m, r ->
                    val value = m.groupValues[1].toLong()
                    val unit = r.units[m.groupValues[2].lowercase()] ?: m.groupValues[2]
                    "${r.cardinal(value)} $unit"
                },
                // 3. An identifier after a call-sign marker: Sector 17 -> सेक्टर सत्रह.
                //    Read as a cardinal, not digit-wise: "sector seventeen" is how an
                //    operator says it.
                NormalisationRule(
                    id = "callsign-context",
                    regex = Regex("""\b(\p{L}+)\s+(\d{1,4})\b"""),
                ) { m, r ->
                    val marker = m.groupValues[1]
                    if (marker.lowercase() in r.callsignMarkers) {
                        "$marker ${r.cardinal(m.groupValues[2].toLong())}"
                    } else {
                        m.value
                    }
                },
                // 4. Exactly three digits standing alone: a number to dial.
                NormalisationRule(
                    id = "dialled-three-digits",
                    regex = Regex("""(?<!\d)(\d{3})(?!\d)"""),
                ) { m, r -> r.digitwise(m.groupValues[1]) },
                // 5. Five or more digits: an identifier, never a quantity anyone says.
                NormalisationRule(
                    id = "long-identifier",
                    regex = Regex("""(?<!\d)(\d{5,})(?!\d)"""),
                ) { m, r -> r.digitwise(m.groupValues[1]) },
                // 6. Anything left is a cardinal.
                NormalisationRule(
                    id = "cardinal",
                    regex = Regex("""(?<!\d)(\d+)(?!\d)"""),
                ) { m, r -> r.cardinal(m.groupValues[1].toLong()) },
            )

        self =
            NormalisationRules(
                language = "hi",
                units = UNITS,
                callsignMarkers = CALLSIGN_MARKERS,
                digitwise = HindiNumerals::digitwise,
                cardinal = HindiNumerals::cardinal,
                time = HindiNumerals::time,
                ordered = ordered,
            )
        self
    }

    fun normaliser(): TextNormaliser = TextNormaliser(rules)
}

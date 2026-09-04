package org.itantra.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `normalise.<lang>.json` — a language's normalisation rules as **data**. Task **W7.2**.
 *
 * ## Why this file exists at all
 *
 * Week 3 shipped the rule engine and one language's rules as Kotlin ([HindiRules]). That
 * was the right order — the engine had to be proven against a language whose numerals are
 * exhaustively irregular before the format could be designed — but it does not scale to
 * ten. Adding Odia should be a file a linguist can write, not a merge request against
 * `core-tts`.
 *
 * The format documented in `docs/TTS.md` section 1 is the starting point. It is extended
 * here in three places, each because a language actually needed it:
 *
 * 1. **[Numerals]** — the numeral table is now in the file. `docs/TTS.md` showed only a
 *    `digits` array, which is enough to read `112` digit-wise and nothing else.
 * 2. **[Clock]** — a time is assembled from a template string, because
 *    *"दोपहर दो बजकर तीस मिनट"* and *"two thirty in the afternoon"* put the same four parts
 *    in different orders, and a hard-coded order would force English into Hindi's.
 * 3. **`{units}` in a pattern** — expanded from the unit table below it, so a file cannot
 *    drift into listing a unit its own regex will never reach.
 *
 * ## What `reviewed` means
 *
 * A numeral table written by an engineer is a **draft**, and the honest thing is to say so
 * on the file rather than in a commit message nobody reads. `reviewed: false` means no
 * native speaker has checked this table yet; the intelligibility test (task W7.11) is what
 * clears it. The flag is asserted in the fixture suite so it cannot quietly disappear.
 */
@Serializable
data class NormaliseSpec(
    val language: String,
    val version: Int,
    /** False until a native speaker has checked the numerals. Task W7.11 clears it. */
    val reviewed: Boolean = false,
    val note: String? = null,
    val units: Map<String, String> = emptyMap(),
    val callsignMarkers: List<String> = emptyList(),
    val numerals: Numerals,
    val clock: Clock,
    val rules: List<RuleSpec>,
) {
    init {
        require(rules.isNotEmpty()) { "$language: a rule file with no rules" }
        val duplicates = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "$language: duplicate rule ids $duplicates" }
    }

    /** Compiles this specification into the engine's rule set. */
    fun toRules(): NormalisationRules {
        val markers = callsignMarkers.map { it.lowercase() }.toSet()
        val unitAlternation =
            units.keys
                // `km` must be tried before `m`, or every kilometre is a metre.
                .sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }

        val compiled =
            rules.map { spec ->
                val kind = spec.kind
                NormalisationRule(spec.id, spec.compile(unitAlternation)) { match, rules ->
                    kind.render(match, rules)
                }
            }

        return NormalisationRules(
            language = language,
            units = units,
            callsignMarkers = markers,
            digitwise = numerals::digitwise,
            cardinal = numerals::cardinal,
            time = clock.withNumerals(numerals)::render,
            ordered = compiled,
            decimalPoint = numerals.decimalPoint,
        )
    }

    fun normaliser(): TextNormaliser = TextNormaliser(toRules())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @throws IllegalArgumentException if the file is malformed. A half-loaded rule
         *   set speaks digits aloud as digits, which is the exact failure this stage
         *   exists to prevent, so it must not load at all.
         */
        fun parse(text: String): NormaliseSpec = json.decodeFromString(serializer(), text)
    }
}

/** One ordered rule as it appears in the file. First match wins. */
@Serializable
data class RuleSpec(
    val id: String,
    val pattern: String,
    val kind: RuleKind,
    /** `i` for case-insensitive. Only the flags a rule file has actually needed. */
    val flags: String = "",
) {
    /**
     * @param unitAlternation substituted for `{units}`, so the pattern is generated from
     *   the unit table rather than restated beside it
     */
    fun compile(unitAlternation: String): Regex {
        val source = pattern.replace(UNITS_PLACEHOLDER, unitAlternation)
        val options = buildSet { if ('i' in flags) add(RegexOption.IGNORE_CASE) }
        return Regex(source, options)
    }

    private companion object {
        const val UNITS_PLACEHOLDER = "{units}"
    }
}

/** What a matched digit run means. The whole of text normalisation is this distinction. */
@Serializable
enum class RuleKind {
    /** `14:30` — group 1 the hour, group 2 the minute. */
    @SerialName("time")
    TIME,

    /** `5 km` — group 1 the value, group 2 the unit symbol. */
    @SerialName("quantity")
    QUANTITY,

    /** `sector 17` — group 1 the marker word, group 2 the digits. */
    @SerialName("callsign")
    CALLSIGN,

    /** Read one digit at a time: dialled numbers and long identifiers. */
    @SerialName("digits")
    DIGITS,

    /** Read as a quantity. */
    @SerialName("number")
    NUMBER,
    ;

    internal fun render(
        match: MatchResult,
        rules: NormalisationRules,
    ): String =
        when (this) {
            TIME -> rules.time(match.groupValues[1].toInt(), match.groupValues[2].toInt())

            QUANTITY -> {
                val unit = rules.units[match.groupValues[2].lowercase()] ?: match.groupValues[2]
                "${rules.spoken(match.groupValues[1])} $unit"
            }

            CALLSIGN -> {
                val marker = match.groupValues[1]
                // A cardinal, not digit-wise: "sector seventeen" is how an operator says
                // it. A word that is not a marker leaves the text for a later rule.
                if (marker.lowercase() in rules.callsignMarkers) {
                    "$marker ${rules.cardinal(match.groupValues[2].toLong())}"
                } else {
                    match.value
                }
            }

            DIGITS -> rules.digitwise(match.groupValues[1])

            NUMBER -> rules.spoken(match.groupValues[1])
        }
}

/**
 * A language's numbers.
 *
 * ## Two kinds, because the languages genuinely differ
 *
 * **`TABLE`** — every value below a hundred is listed. The Indo-Aryan languages need this:
 * Hindi's सत्रह, अड़तीस and उनहत्तर are not composed from anything, and a generator that
 * tried would be wrong ninety times in a hundred.
 *
 * **`COMPOSED`** — zero through nineteen are listed, then the twenties upward are built
 * from a tens word and a ones word. The Dravidian languages compose regularly enough for
 * this: Telugu's ఇరవై ఒకటి is transparently *twenty one*.
 *
 * Forcing one kind on both would cost either eight hundred hand-written words that can be
 * generated, or a generator emitting plausible non-words in five languages. The format
 * admits both because that is the truth about the languages.
 *
 * @param tensCombining the form a tens word takes **before** a ones digit — Tamil's
 *   இருபது becomes இருபத்தி in இருபத்தி ஒன்று. Empty means the standalone form is used.
 */
@Serializable
data class Numerals(
    val kind: NumeralKind,
    val digits: List<String>,
    /** `TABLE`: exactly one hundred entries, zero to ninety-nine. */
    val words: List<String> = emptyList(),
    /** `COMPOSED`: exactly twenty entries, zero to nineteen. */
    val onesToNineteen: List<String> = emptyList(),
    /** `COMPOSED`: eight entries, twenty to ninety. */
    val tens: List<String> = emptyList(),
    val tensCombining: List<String> = emptyList(),
    val joiner: String = " ",
    val scales: List<Scale> = emptyList(),
    val negative: String = "-",
    /**
     * The word for the separator in a grid reference — दशमलव, point, ದಶಮಾಂಶ. It has to be
     * a word: a full stop reaching the phonemiser is either silent or an end-of-sentence
     * pause, and 20.29 then arrives as two unrelated numbers.
     */
    val decimalPoint: String = ".",
) {
    init {
        require(digits.size == 10) { "digits must have ten entries, had ${digits.size}" }
        when (kind) {
            NumeralKind.TABLE ->
                require(words.size == 100) {
                    "a table language must list all hundred values, had ${words.size}"
                }

            NumeralKind.COMPOSED -> {
                require(onesToNineteen.size == 20) {
                    "onesToNineteen must have twenty entries, had ${onesToNineteen.size}"
                }
                require(tens.size == 8) { "tens must be twenty to ninety, had ${tens.size}" }
                require(tensCombining.isEmpty() || tensCombining.size == 8) {
                    "tensCombining must be empty or eight entries, had ${tensCombining.size}"
                }
            }
        }
        // Descending, so [cardinal] can consume them in order. An out-of-order scale table
        // reads 100 000 as "ten ten-thousands", and nobody notices until a listening panel
        // does.
        for (i in 1 until scales.size) {
            require(scales[i - 1].value > scales[i].value) {
                "scales must descend: ${scales[i - 1].value} then ${scales[i].value}"
            }
        }
        require(scales.all { it.value > 0 }) { "a scale must be positive" }
    }

    /** Reads each digit separately: `112` becomes three words. */
    fun digitwise(run: String): String =
        run.mapNotNull { c -> if (c.isDigit()) digits[c - '0'] else null }.joinToString(" ")

    /** Reads a value as a quantity. */
    fun cardinal(value: Long): String {
        if (value < 0) return "$negative ${cardinal(-value)}"
        if (value < 100) return belowHundred(value.toInt())

        val parts = ArrayList<String>()
        var rest = value
        for (scale in scales) {
            if (rest >= scale.value) {
                parts += cardinal(rest / scale.value)
                parts += scale.word
                rest %= scale.value
            }
        }
        // Nothing consumed it: the scale table does not reach this magnitude. Reading the
        // digits is wrong, but leaving a numeral unspoken is worse.
        if (parts.isEmpty()) return digitwise(value.toString())
        if (rest > 0) parts += belowHundred(rest.toInt())
        return parts.joinToString(" ")
    }

    private fun belowHundred(value: Int): String =
        when (kind) {
            NumeralKind.TABLE -> words[value]
            NumeralKind.COMPOSED ->
                when {
                    value < 20 -> onesToNineteen[value]
                    value % 10 == 0 -> tens[value / 10 - 2]
                    else -> {
                        val prefix = tensCombining.getOrNull(value / 10 - 2) ?: tens[value / 10 - 2]
                        prefix + joiner + onesToNineteen[value % 10]
                    }
                }
        }
}

@Serializable
enum class NumeralKind {
    @SerialName("table")
    TABLE,

    @SerialName("composed")
    COMPOSED,
}

/** A place-value word: सौ, हज़ार, लाख, करोड़ — or hundred, thousand, million. */
@Serializable
data class Scale(val value: Long, val word: String)

/**
 * How a language says a time.
 *
 * The two templates are strings rather than code because the parts arrive in different
 * orders: Hindi leads with the period of day, English trails it. `{period}`, `{hour}` and
 * `{minute}` are substituted; everything else is literal.
 *
 * @param periods the day divided into named stretches, tried in order
 */
@Serializable
data class Clock(
    val periods: List<Period> = emptyList(),
    val onTheHour: String,
    val withMinutes: String,
    /** Hindi says दो for 14:00, English says two. Both want the twelve-hour value. */
    val twelveHour: Boolean = true,
) {
    /**
     * The clock reads its hour with the same numerals as everything else, so it is handed
     * them rather than carrying a second copy that could drift.
     */
    internal fun withNumerals(numerals: Numerals): Bound = Bound(this, numerals)

    internal class Bound(private val clock: Clock, private val numerals: Numerals) {
        fun render(
            hour24: Int,
            minute: Int,
        ): String {
            require(hour24 in 0..23) { "hour must be 0..23, was $hour24" }
            require(minute in 0..59) { "minute must be 0..59, was $minute" }

            val period = clock.periods.firstOrNull { hour24 in it.from..it.to }?.word ?: ""
            val hour =
                if (clock.twelveHour) {
                    if (hour24 % 12 == 0) 12 else hour24 % 12
                } else {
                    hour24
                }
            val template = if (minute == 0) clock.onTheHour else clock.withMinutes

            return template
                .replace("{period}", period)
                .replace("{hour}", numerals.cardinal(hour.toLong()))
                .replace("{minute}", numerals.cardinal(minute.toLong()))
                .replace(MULTI_SPACE, " ")
                .trim()
        }

        private companion object {
            val MULTI_SPACE = Regex("\\s{2,}")
        }
    }
}

/** A named stretch of the day, in twenty-four-hour terms. */
@Serializable
data class Period(val from: Int, val to: Int, val word: String) {
    init {
        require(from in 0..23 && to in 0..23) { "period $from..$to is not a time of day" }
        require(from <= to) { "period $from..$to runs backwards" }
    }
}

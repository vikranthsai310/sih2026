package org.itantra.tts

/**
 * Hindi cardinal numbers.
 *
 * Every value from zero to ninety-nine is irregular in Hindi — there is no
 * "twenty-" plus "-one" composition as in English — so the table is exhaustive by
 * necessity rather than by choice. Above a hundred the language does compose, using
 * the Indian scale: सौ (100), हज़ार (1 000), लाख (100 000), करोड़ (10 000 000).
 *
 * This is the unglamorous half of synthesis quality. No neural vocoder handles a
 * digit correctly, and operational messages are full of them. See `docs/TTS.md`
 * section 1.
 */
object HindiNumerals {
    /** Read one digit at a time: for dialling, call signs and long identifiers. */
    val DIGITS =
        listOf("शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ")

    private val ONES_TO_NINETY_NINE =
        listOf(
            "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
            "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
            "बीस", "इक्कीस", "बाईस", "तेईस", "चौबीस", "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस",
            "तीस", "इकतीस", "बत्तीस", "तैंतीस", "चौंतीस", "पैंतीस", "छत्तीस", "सैंतीस", "अड़तीस", "उनतालीस",
            "चालीस", "इकतालीस", "बयालीस", "तैंतालीस", "चवालीस", "पैंतालीस", "छियालीस", "सैंतालीस", "अड़तालीस", "उनचास",
            "पचास", "इक्यावन", "बावन", "तिरेपन", "चौवन", "पचपन", "छप्पन", "सत्तावन", "अट्ठावन", "उनसठ",
            "साठ", "इकसठ", "बासठ", "तिरेसठ", "चौंसठ", "पैंसठ", "छियासठ", "सड़सठ", "अड़सठ", "उनहत्तर",
            "सत्तर", "इकहत्तर", "बहत्तर", "तिहत्तर", "चौहत्तर", "पचहत्तर", "छिहत्तर", "सतहत्तर", "अठहत्तर", "उन्यासी",
            "अस्सी", "इक्यासी", "बयासी", "तिरासी", "चौरासी", "पचासी", "छियासी", "सत्तासी", "अट्ठासी", "नवासी",
            "नब्बे", "इक्यानवे", "बानवे", "तिरानवे", "चौरानवे", "पचानवे", "छियानवे", "सत्तानवे", "अट्ठानवे", "निन्यानवे",
        )

    private const val HUNDRED = "सौ"
    private const val THOUSAND = "हज़ार"
    private const val LAKH = "लाख"
    private const val CRORE = "करोड़"

    /** Reads each digit separately: `112` becomes "एक एक दो". */
    fun digitwise(digits: String): String =
        digits.mapNotNull { c ->
            when {
                c.isDigit() -> DIGITS[c - '0']
                else -> null
            }
        }.joinToString(" ")

    /**
     * Reads a value as a quantity: `17` becomes "सत्रह", `250` becomes "दो सौ पचास".
     *
     * Uses the Indian scale, so 100 000 is "एक लाख" rather than "one hundred
     * thousand" — reading a Hindi sentence with a Western scale is a small thing that
     * immediately marks a synthesiser as foreign.
     */
    fun cardinal(value: Long): String {
        if (value < 0) return "ऋण " + cardinal(-value)
        if (value < 100) return ONES_TO_NINETY_NINE[value.toInt()]

        val parts = ArrayList<String>()
        var rest = value

        for ((scale, word) in listOf(10_000_000L to CRORE, 100_000L to LAKH, 1_000L to THOUSAND, 100L to HUNDRED)) {
            if (rest >= scale) {
                val count = rest / scale
                parts += cardinal(count)
                parts += word
                rest %= scale
            }
        }
        if (rest > 0) parts += ONES_TO_NINETY_NINE[rest.toInt()]
        return parts.joinToString(" ")
    }

    /** `14:30` becomes "दोपहर दो बजकर तीस मिनट". */
    fun time(
        hour24: Int,
        minute: Int,
    ): String {
        require(hour24 in 0..23) { "hour must be 0..23, was $hour24" }
        require(minute in 0..59) { "minute must be 0..59, was $minute" }

        val period =
            when (hour24) {
                in 0..3 -> "रात"
                in 4..11 -> "सुबह"
                in 12..16 -> "दोपहर"
                in 17..19 -> "शाम"
                else -> "रात"
            }
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12

        return if (minute == 0) {
            "$period ${cardinal(hour12.toLong())} बजे"
        } else {
            "$period ${cardinal(hour12.toLong())} बजकर ${cardinal(minute.toLong())} मिनट"
        }
    }
}

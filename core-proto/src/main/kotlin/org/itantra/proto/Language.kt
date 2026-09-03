package org.itantra.proto

/**
 * Language indices are normative and fixed. Changing this table is a protocol
 * version change. See `docs/PROTOCOL.md` section 3.
 *
 * Each Indic block spans exactly 128 code points, which is what makes single-byte
 * script packing possible: the frame header declares the language, so the receiver
 * knows which block applies and subtracting [blockBase] yields one byte per
 * character.
 *
 * Hindi and Marathi share Devanagari and therefore share a packing table. They
 * remain distinct indices because they select different acoustic models, synthesis
 * voices and normalisation rules.
 */
enum class Language(
    val index: Int,
    val code: String,
    /** First code point of the script's Unicode block, or null for Latin. */
    val blockBase: Int?,
) {
    ENGLISH(0, "en", null),
    HINDI(1, "hi", 0x0900),
    BENGALI(2, "bn", 0x0980),
    MARATHI(3, "mr", 0x0900),
    TELUGU(4, "te", 0x0C00),
    TAMIL(5, "ta", 0x0B80),
    GUJARATI(6, "gu", 0x0A80),
    KANNADA(7, "kn", 0x0C80),
    MALAYALAM(8, "ml", 0x0D00),
    ODIA(9, "or", 0x0B00),
    ;

    companion object {
        private val BY_INDEX = entries.associateBy { it.index }

        /**
         * @return the language for a wire index, or null if the index is reserved.
         *   Values 10..15 are reserved and a frame carrying one MUST be rejected.
         */
        fun fromIndex(index: Int): Language? = BY_INDEX[index]
    }
}

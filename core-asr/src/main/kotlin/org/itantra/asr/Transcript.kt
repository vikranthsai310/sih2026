package org.itantra.asr

/**
 * One recognised word, and when the decoder emitted it.
 *
 * @param startSeconds seconds from the start of the audio the decoder was given, or `NaN`
 *   when the decoder offered no timing. A CTC decoder emits each token at one frame, and
 *   sherpa-onnx reports that frame; the first token of a word is close to where the word
 *   was actually spoken, which is what [UtteranceDecoder] needs to know which of two
 *   overlapping decodes a word belongs to.
 */
data class Word(
    val text: String,
    val startSeconds: Float = Float.NaN,
) {
    val isTimed: Boolean get() = !startSeconds.isNaN()
}

/** What a decoder made of one buffer of audio. */
data class Transcript(val words: List<Word>) {
    val text: String get() = words.joinToString(" ") { it.text }

    val isEmpty: Boolean get() = words.isEmpty()

    companion object {
        val EMPTY = Transcript(emptyList())

        /**
         * From sherpa-onnx's token list and its timestamps.
         *
         * IndicConformer's vocabulary is SentencePiece: a token beginning with `▁` starts a
         * word and every other token continues the one before it. That is the rule sherpa
         * itself uses to build `text`, applied here so each word keeps the time of its first
         * token. `<unk>` is dropped — it is the model saying it heard something it has no
         * spelling for, and "<unk>" in a message is noise rather than a word.
         *
         * A timestamp array that does not match the tokens leaves every word untimed rather
         * than mis-timed: an untimed word is kept by every attribution rule, which is the
         * safe failure.
         */
        fun fromTokens(
            tokens: Array<String>,
            timestamps: FloatArray,
        ): Transcript {
            val timed = timestamps.size == tokens.size
            val words = ArrayList<Word>()
            val current = StringBuilder()
            var currentStart = Float.NaN

            fun flush() {
                val text = current.toString().trim()
                if (text.isNotEmpty()) words += Word(text, currentStart)
                current.setLength(0)
                currentStart = Float.NaN
            }

            for ((i, raw) in tokens.withIndex()) {
                if (raw.isEmpty() || raw == UNKNOWN) continue
                val startsWord = raw.startsWith(WORD_START)
                if (startsWord || current.isEmpty()) {
                    flush()
                    currentStart = if (timed) timestamps[i] else Float.NaN
                    current.append(if (startsWord) raw.substring(WORD_START.length) else raw)
                } else {
                    current.append(raw)
                }
            }
            flush()
            return Transcript(words)
        }

        /**
         * Whether a word is a hesitation rather than a word.
         *
         * In every Indic block the independent vowels A and AA sit at the same two
         * offsets, and a lone one is what an "uh" or an "aa" before a sentence is written
         * as. A single Latin filler is listed. Nothing longer is ever a filler: "अब" is a
         * word, and so is "आग".
         */
        fun isFiller(word: String): Boolean {
            val bare = word.trim { !it.isLetterOrDigit() }
            if (bare.isEmpty()) return false
            if (bare.lowercase() in LATIN_FILLERS) return true
            if (bare.codePointCount(0, bare.length) != 1) return false
            val cp = bare.codePointAt(0)
            return cp in 0x0900..0x0DFF && (cp and 0x7F) in INDIC_FILLER_OFFSETS
        }

        private val LATIN_FILLERS = setOf("um", "umm", "uh", "uhh", "hmm", "hm", "er", "erm", "aa", "ah", "a")

        /** Independent vowel A and AA, at the same offsets in every Indic block. */
        private val INDIC_FILLER_OFFSETS = setOf(0x05, 0x06)

        /** For a decoder that offers text only. Every word is untimed. */
        fun fromText(text: String): Transcript =
            Transcript(text.split(WHITESPACE).filter { it.isNotBlank() }.map { Word(it) })

        private const val WORD_START = "▁"
        private const val UNKNOWN = "<unk>"
        private val WHITESPACE = Regex("\\s+")
    }
}

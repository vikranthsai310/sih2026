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

        /** For a decoder that offers text only. Every word is untimed. */
        fun fromText(text: String): Transcript =
            Transcript(text.split(WHITESPACE).filter { it.isNotBlank() }.map { Word(it) })

        private const val WORD_START = "▁"
        private const val UNKNOWN = "<unk>"
        private val WHITESPACE = Regex("\\s+")
    }
}

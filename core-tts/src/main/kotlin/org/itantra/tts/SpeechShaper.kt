package org.itantra.tts

/**
 * One stretch of text the voice says in a single breath, and the silence after it.
 *
 * @param pauseAfterMillis quiet to leave before the next phrase. Zero after the last.
 */
data class Phrase(
    val text: String,
    val pauseAfterMillis: Int,
)

/**
 * Turns a message into the phrases a person would say it in.
 *
 * ## Why the split is where it is
 *
 * The first version of chunking cut a sentence every twelve words and at every
 * conjunction, so playback could begin early. A VITS voice synthesises each chunk as a
 * complete utterance — pitch falling to a full stop at the end of every one — and the
 * result was a sentence spoken as five separate sentences, each with a click between. That
 * is most of what a listener means by "it sounds like a robot".
 *
 * Phrases are now cut only where a speaker would breathe: at sentence punctuation, and at
 * clause punctuation. A phrase that ends in a comma is synthesised *with* the comma, so
 * the voice gives it a continuation contour rather than a full stop. Anything longer than
 * [maxWords] with no punctuation at all is cut at a conjunction if one falls in the middle,
 * and otherwise at the word count, with a comma added so the voice does not end it.
 *
 * ## Why every message ends in a full stop
 *
 * Recognised speech carries no punctuation, and text handed to the voice without any is
 * read with the flat contour of an unfinished sentence. A [fullStop] is added when the
 * message has none — the language's own, because the Devanagari voices were trained on
 * text ending in a danda.
 *
 * ## The pauses
 *
 * A comma is about a sixth of a second of silence in read speech; a sentence end is over a
 * third. They are written into the audio between phrases rather than left to the voice,
 * which pads nothing, and they are why two sentences no longer run into each other.
 */
class SpeechShaper(
    private val fullStop: String = ".",
    private val maxWords: Int = MAX_WORDS,
    private val minWords: Int = MIN_WORDS,
    private val sentencePauseMillis: Int = SENTENCE_PAUSE_MILLIS,
    private val clausePauseMillis: Int = CLAUSE_PAUSE_MILLIS,
    private val conjunctions: Set<String> = CONJUNCTIONS,
) {
    fun shape(text: String): List<Phrase> {
        val clean = tidy(text)
        if (clean.isEmpty()) return emptyList()
        val punctuated = if (endsSentence(clean)) clean else clean + fullStop

        val units = ArrayList<Phrase>()
        for (unit in splitAtPunctuation(punctuated)) {
            units += bound(unit)
        }
        val phrases = merge(units)
        // Nothing follows the last phrase, so nothing waits for it.
        return phrases.mapIndexed { i, phrase ->
            if (i == phrases.lastIndex) phrase.copy(pauseAfterMillis = 0) else phrase
        }
    }

    /** Whitespace collapsed, spaces before punctuation removed, doubled marks folded. */
    private fun tidy(text: String): String {
        var out = text.replace(WHITESPACE, " ").trim()
        out = out.replace(SPACE_BEFORE_MARK, "$1")
        out = out.replace(REPEATED_MARK, "$1")
        return out.trim()
    }

    private fun endsSentence(text: String): Boolean = text.last() in SENTENCE_MARKS

    /** Each run of text through the mark that ends it, with the pause the mark calls for. */
    private fun splitAtPunctuation(text: String): List<Phrase> {
        val out = ArrayList<Phrase>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            val pause =
                when (ch) {
                    in SENTENCE_MARKS -> sentencePauseMillis
                    in CLAUSE_MARKS -> clausePauseMillis
                    else -> continue
                }
            val piece = current.toString().trim()
            current.setLength(0)
            // A mark with no words before it -- a message beginning with a comma -- is
            // nothing to say.
            if (piece.any { it.isLetterOrDigit() }) out += Phrase(piece, pause)
        }
        val rest = current.toString().trim()
        if (rest.isNotEmpty()) out += Phrase(rest, sentencePauseMillis)
        return out
    }

    /** A phrase too long to say in one breath, cut at a conjunction or, failing that, by count. */
    private fun bound(phrase: Phrase): List<Phrase> {
        val words = phrase.text.split(' ').filter { it.isNotBlank() }
        if (words.size <= maxWords) return listOf(phrase)

        val out = ArrayList<Phrase>()
        var current = ArrayList<String>()
        for (word in words) {
            val bare = word.trim { !it.isLetterOrDigit() }.lowercase()
            // Break *before* a conjunction, which is where a speaker draws breath, but
            // only once there is a phrase's worth of words to break after.
            if (current.size >= minWords + 1 && bare in conjunctions) {
                out += Phrase(current.joinToString(" ") + ",", clausePauseMillis)
                current = ArrayList()
            }
            current += word
            if (current.size >= maxWords) {
                out += Phrase(current.joinToString(" ") + ",", clausePauseMillis)
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) out += Phrase(current.joinToString(" "), phrase.pauseAfterMillis)
        return out
    }

    /**
     * A phrase of one or two words is not worth a breath of its own. It joins the phrase
     * before it, or — for the first — the one after.
     */
    private fun merge(phrases: List<Phrase>): List<Phrase> {
        if (phrases.isEmpty()) return phrases
        val out = ArrayList<Phrase>()
        for (phrase in phrases) {
            // Only across a clause mark. A short sentence after a full stop is a sentence,
            // and the pause before it is the point.
            if (out.isNotEmpty() && wordCount(phrase.text) < minWords && !endsSentence(out.last().text)) {
                val previous = out.removeAt(out.size - 1)
                out += Phrase(previous.text + " " + phrase.text, phrase.pauseAfterMillis)
            } else {
                out += phrase
            }
        }
        if (out.size > 1 && wordCount(out[0].text) < minWords && !endsSentence(out[0].text)) {
            val merged = Phrase(out[0].text + " " + out[1].text, out[1].pauseAfterMillis)
            out.removeAt(0)
            out[0] = merged
        }
        return out
    }

    private fun wordCount(text: String): Int = text.split(' ').count { it.isNotBlank() }

    companion object {
        /** Longer than this with no punctuation is not one breath, whatever the writer thought. */
        const val MAX_WORDS = 24

        /** A lone word is not a breath; it joins its neighbour rather than being said alone. */
        const val MIN_WORDS = 2

        const val SENTENCE_PAUSE_MILLIS = 380
        const val CLAUSE_PAUSE_MILLIS = 170

        /** Full stop, question, exclamation; Devanagari danda and double danda. */
        val SENTENCE_MARKS = setOf('.', '?', '!', '।', '॥')

        val CLAUSE_MARKS = setOf(',', ';', ':', '—', '–')

        /** Where a breath falls in a long unpunctuated run, across the ten languages. */
        val CONJUNCTIONS =
            setOf(
                "और", "लेकिन", "क्योंकि", "या", "तथा", "परंतु", "फिर",
                "এবং", "কিন্তু", "আর", "অথবা",
                "आणि", "पण", "किंवा",
                "మరియు", "కానీ", "లేదా",
                "மற்றும்", "ஆனால்", "அல்லது",
                "અને", "પણ", "અથવા",
                "ಮತ್ತು", "ಆದರೆ", "ಅಥವಾ",
                "കൂടാതെ", "പക്ഷേ", "അല്ലെങ്കിൽ",
                "ଏବଂ", "କିନ୍ତୁ", "କିମ୍ବା",
                "and", "but", "because", "or", "then", "so",
            )

        private val WHITESPACE = Regex("\\s+")
        private val SPACE_BEFORE_MARK = Regex("\\s+([,;:.!?।॥])")
        private val REPEATED_MARK = Regex("([,;:.!?।])\\1+")
    }
}

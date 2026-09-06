package org.itantra.asr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decodes an utterance **at the pauses the speaker leaves**, while they are still talking,
 * so that what remains to decode when they let go is short and is never half a word.
 *
 * ## What this replaces, and why
 *
 * The first version of this idea sliced audio into fixed 1.5 s windows with a 0.4 s overlap
 * and glued the texts back together by matching repeated words. It kept the latency claim
 * and it cost accuracy in three separate ways, each of which showed up on a handset as
 * "the text is wrong":
 *
 * 1. **Windows cut words in half.** A boundary lands wherever 1.5 s happens to fall — mid
 *    word, mid syllable — and a CTC model asked about half a word answers with a different
 *    word, or two.
 * 2. **The join could not tell a revision from a new word.** Where the two windows decoded
 *    the shared 0.4 s differently, both readings were kept, so a word appeared twice in two
 *    spellings.
 * 3. **Each window was normalised on its own.** IndicConformer normalises its features per
 *    utterance (`normalize_type = per_feature` in the model's own metadata). Statistics
 *    taken over 1.5 s that is half silence are not the statistics the model was trained
 *    against; over a whole clause they are.
 *
 * ## What it does instead
 *
 * Audio is watched frame by frame with an energy floor. When the speaker has said at least
 * [minSpeechMillis] and then been quiet for [pauseMillis] — a clause boundary, not a stop
 * closure — the segment is closed **in the pause** and decoded in the background. Nothing
 * straddles that cut, so the texts simply follow one another, and each decode sees a whole
 * clause with a little quiet either side, which is what the model saw in training.
 *
 * A speaker who does not pause is handled by a **forced cut** at [maxSegmentMillis]: the
 * quietest frame in the recent past is chosen, and both sides keep [contextMillis] of audio
 * beyond it. The two decodes overlap, and a word is then owned by whichever side its
 * emission time falls on — sherpa-onnx reports when each token was emitted — so a word
 * split by the cut is read once, by the side that heard all of it.
 *
 * ## Provisional decodes
 *
 * While a segment is still open, and while it is short enough to be cheap, it is decoded
 * as it grows so the operator sees running text. Those readings are not kept — the segment
 * is decoded again, whole, when it closes — with one exception: at the endpoint, if the
 * last provisional decode already covered every frame of speech plus [settleMillis] of
 * quiet after it, its reading **is** the final one, and the operator waits for nothing.
 *
 * ## Silence
 *
 * A segment is trimmed to [keepQuietMillis] of quiet either side of its speech before
 * decoding. An operator who holds the control, thinks, and then speaks should not have the
 * thinking included in the model's normalisation statistics; and a segment with no speech
 * at all is never sent to the model.
 *
 * Not thread-safe: it belongs to the inference thread and every decode is made on it.
 *
 * @param decode called with a segment of PCM; returns the words it contains. Injected so
 *   the segmentation can be tested against a scripted "model" without a real one.
 */
class UtteranceDecoder(
    private val sampleRate: Int = 16_000,
    private val decode: (ShortArray) -> Transcript,
    private val pauseMillis: Int = PAUSE_MILLIS,
    private val minSpeechMillis: Int = MIN_SPEECH_MILLIS,
    private val maxSegmentMillis: Int = MAX_SEGMENT_MILLIS,
    private val contextMillis: Int = CONTEXT_MILLIS,
    private val leadMillis: Int = LEAD_MILLIS,
    private val keepQuietMillis: Int = KEEP_QUIET_MILLIS,
    private val provisionalEveryMillis: Int = PROVISIONAL_EVERY_MILLIS,
    private val provisionalMaxMillis: Int = PROVISIONAL_MAX_MILLIS,
    private val settleMillis: Int = SETTLE_MILLIS,
) {
    init {
        require(contextMillis * 2 < maxSegmentMillis) { "a forced cut needs room for its context" }
        require(pauseMillis >= FRAME_MILLIS) { "a pause shorter than a frame cannot be seen" }
    }

    private val frameSamples = sampleRate * FRAME_MILLIS / 1000

    // ── the open segment ─────────────────────────────────────────────────────

    private var pcm = ShortArray(sampleRate * 4)
    private var size = 0

    /** Absolute sample index, since [reset], of `pcm[0]`. Word times are absolute. */
    private var segmentStart = 0L

    /** Level of each complete frame of the open segment, in dBFS. */
    private val levels = ArrayList<Float>()

    /** Adaptive noise floor, dBFS. Falls at once, rises slowly. NaN before the first frame. */
    private var floorDb = Double.NaN

    /** Sentinel for "the frame that was analysed last time a provisional decode ran". */
    private var provisionalAtFrame = 0

    private var provisional: Provisional? = null

    // ── words already decided ────────────────────────────────────────────────

    private val fixed = ArrayList<Placed>()

    /** How the open segment's left edge was made, when it was a forced cut. */
    private var boundary: Boundary? = null

    /** A word with an absolute position, or -1 when the decoder did not time it. */
    private class Placed(
        val text: String,
        val atSample: Long,
        /** The speaker paused after this word: a clause boundary, spoken as a comma. */
        var pauseAfter: Boolean = false,
    )

    private class Boundary(val dropBeforeSample: Long)

    private class Provisional(val words: List<Placed>, val coveredFrames: Int)

    // ── observability ────────────────────────────────────────────────────────

    /** Frames of the open segment analysed so far. */
    private val analysedFrames: Int get() = levels.size

    /** Milliseconds of audio in the open segment, which is what an endpoint might decode. */
    val pendingMillis: Int get() = size * 1000 / sampleRate

    /** Milliseconds an endpoint fired now would actually send to the model. Zero if none. */
    val endpointDecodeMillis: Int
        get() {
            if (!hasSpeech()) return 0
            if (provisionalCoversEverything()) return 0
            val range = decodeRange(analysedFrames, includeTail = true) ?: return 0
            return (range.to - range.from) * 1000 / sampleRate
        }

    /** Segments closed and decoded so far this utterance. */
    var segmentsDecoded: Int = 0
        private set

    /** Decodes made to show running text, which are discarded unless they cover the tail. */
    var provisionalDecodes: Int = 0
        private set

    // ── feeding ──────────────────────────────────────────────────────────────

    /**
     * Feeds captured audio, closing and decoding any segment it completes.
     *
     * @param allowProvisional whether the open segment may be decoded for running text.
     *   The endpoint path passes false: it is about to decode the tail for real.
     * @return whether [runningText] changed.
     */
    fun onAudio(
        samples: ShortArray,
        allowProvisional: Boolean = true,
    ): Boolean {
        append(samples)
        analyse()
        var changed = false
        while (true) {
            val analysed = analysedFrames
            val speechMillis = speechFrames() * FRAME_MILLIS
            val clause = speechMillis >= minSpeechMillis
            val quietTail = trailingQuietFrames() * FRAME_MILLIS
            when {
                clause && quietTail >= pauseMillis -> {
                    closeAtPause(analysed)
                    changed = true
                }

                analysed * FRAME_MILLIS >= maxSegmentMillis ->
                    when {
                        // Still talking, or loud enough that the floor could not tell: cut
                        // it at the quietest recent moment and read it.
                        clause || (speechMillis == 0 && isAudible(analysed)) -> {
                            closeForced(analysed)
                            changed = true
                        }

                        // A word or two, then a long hold. Read the words; a "yes" followed
                        // by six seconds of thought is still a "yes".
                        speechMillis > 0 -> {
                            closeAtPause(analysed)
                            changed = true
                        }

                        // Dead air with nothing said in it: a held control in a pocket.
                        // Keep only what a later word would want as its leading quiet, so
                        // memory stays bounded and the model is never asked to normalise
                        // over ten seconds of nothing.
                        else -> restartFrom(analysed - frames(keepQuietMillis))
                    }

                else -> break
            }
        }
        if (allowProvisional && maybeProvisional()) changed = true
        return changed
    }

    /** Words decided so far, followed by the latest provisional reading of the open segment. */
    fun runningText(): String = spoken(fixed + provisional?.words.orEmpty())

    /**
     * The speaker has stopped. Decodes whatever is still open and returns the utterance.
     *
     * This is the one decode that costs the operator latency. It covers at most one
     * segment, and it is skipped entirely when the last provisional decode already
     * covered every frame of speech.
     */
    fun onEndpoint(): String {
        val tail: List<Placed> =
            when {
                !hasSpeech() -> emptyList()
                provisionalCoversEverything() -> provisional!!.words
                else -> attributed(decodeSegment(analysedFrames, includeTail = true), Long.MAX_VALUE)
            }
        val text = spoken(fixed + tail)
        reset()
        return text
    }

    /**
     * The words as text, with a comma where the speaker paused.
     *
     * The pause is the one piece of prosody a recogniser can recover, and it is worth
     * carrying: the receiving handset's voice pauses where the speaker did, instead of
     * reading a whole message in one breath. A comma is ASCII, so it costs one byte on
     * the wire in every language, and template matching ignores punctuation.
     */
    private fun spoken(words: List<Placed>): String {
        val out = StringBuilder()
        for ((i, word) in words.withIndex()) {
            if (i > 0) out.append(' ')
            out.append(word.text)
            if (word.pauseAfter && i < words.lastIndex) out.append(',')
        }
        return out.toString()
    }

    fun reset() {
        size = 0
        segmentStart = 0L
        levels.clear()
        floorDb = Double.NaN
        provisional = null
        provisionalAtFrame = 0
        fixed.clear()
        boundary = null
        segmentsDecoded = 0
        provisionalDecodes = 0
    }

    // ── segmentation ─────────────────────────────────────────────────────────

    /**
     * Closes the segment in the pause it just ended with.
     *
     * The cut is at the current frame, so the whole pause is the segment's trailing quiet
     * (trimmed to [keepQuietMillis] before decoding). The next segment begins [leadMillis]
     * before the cut, inside that same pause: leading quiet for the next clause, and
     * nothing in it that either decode could read as a word.
     */
    private fun closeAtPause(cutFrame: Int) {
        // The reading made when the speaker fell quiet usually is this clause, whole; the
        // pause only confirms it. Decoding it again would be paying twice for one clause.
        fixed +=
            if (provisionalCoversEverything()) {
                provisional!!.words
            } else {
                attributed(decodeSegment(cutFrame, includeTail = false), Long.MAX_VALUE)
            }
        segmentsDecoded++
        boundary = null
        fixed.lastOrNull()?.pauseAfter = true
        restartFrom(max(0, cutFrame - frames(leadMillis)))
    }

    /**
     * Closes the segment at the quietest recent frame, keeping [contextMillis] both sides.
     *
     * The left decode owns words emitted before the cut (plus [HYSTERESIS_MILLIS], because
     * a word that begins right on the cut was heard whole by the left side, which had the
     * full clause before it). The right decode drops those and owns the rest.
     */
    private fun closeForced(analysed: Int) {
        val context = frames(contextMillis)
        val latest = analysed - context
        val earliest = max(0, latest - frames(SEARCH_MILLIS))
        var cut = latest
        var quietest = Float.MAX_VALUE
        for (f in earliest until latest) {
            if (levels[f] < quietest) {
                quietest = levels[f]
                cut = f
            }
        }
        val cutSample = segmentStart + cut.toLong() * frameSamples
        val handover = cutSample + samples(HYSTERESIS_MILLIS)

        fixed += attributed(decodeSegment(min(analysed, cut + context), includeTail = false), handover)
        segmentsDecoded++
        boundary = Boundary(dropBeforeSample = handover)
        restartFrom(max(0, cut - context))
    }

    /** Makes frame [frame] of the open segment its new first frame, keeping what follows. */
    private fun restartFrom(frame: Int) {
        val keepFrom = frame * frameSamples
        if (keepFrom > 0) {
            System.arraycopy(pcm, keepFrom, pcm, 0, size - keepFrom)
            size -= keepFrom
            segmentStart += keepFrom
            levels.subList(0, min(frame, levels.size)).clear()
        }
        provisional = null
        provisionalAtFrame = 0
    }

    /**
     * Reads the open segment for running text, when a reading is due.
     *
     * Two things make one due. Every [provisionalEveryMillis] of a segment still short
     * enough for the cost to stay off the endpoint; and — whatever the segment's length —
     * the speaker falling quiet for [settleMillis]. The second is the important one: it is
     * made in the pause the speaker is leaving anyway, it covers the whole clause with its
     * last word complete, and both the pause cut and the endpoint will take it as final
     * rather than decode the clause again. A release a moment after the last word then
     * costs nothing, which is the common case on a push-to-talk net.
     */
    private fun maybeProvisional(): Boolean {
        val analysed = analysedFrames
        if (speechFrames() * FRAME_MILLIS < minSpeechMillis) return false
        // Nothing has been said since the last reading; the pause will close the segment.
        if (lastSpeechFrame() < provisionalAtFrame) return false

        val settled = trailingQuietFrames() * FRAME_MILLIS >= settleMillis
        val periodic =
            analysed * FRAME_MILLIS <= provisionalMaxMillis &&
                (analysed - provisionalAtFrame) * FRAME_MILLIS >= provisionalEveryMillis
        if (!settled && !periodic) return false

        val words = attributed(decodeSegment(analysed, includeTail = false), Long.MAX_VALUE)
        provisional = Provisional(words, coveredFrames = analysed)
        provisionalAtFrame = analysed
        provisionalDecodes++
        return true
    }

    /**
     * Whether the last provisional reading can stand as the final one.
     *
     * It can when no speech frame has arrived since it was made, and it extended at least
     * [settleMillis] beyond the last one — so the final word was inside it whole, not cut
     * off at the buffer's end.
     */
    private fun provisionalCoversEverything(): Boolean {
        val p = provisional ?: return false
        return lastSpeechFrame() <= p.coveredFrames - 1 - frames(settleMillis)
    }

    // ── decoding ─────────────────────────────────────────────────────────────

    /** Decodes the open segment up to [endFrame], trimmed of excess quiet. */
    private fun decodeSegment(
        endFrame: Int,
        includeTail: Boolean,
    ): List<Placed> {
        val range = decodeRange(endFrame, includeTail) ?: return emptyList()
        val audio = pcm.copyOfRange(range.from, range.to)
        val transcript = decode(audio)
        return transcript.words.map { word ->
            Placed(
                word.text,
                if (word.isTimed) segmentStart + range.from + (word.startSeconds * sampleRate).toLong() else -1L,
            )
        }
    }

    /** Sample offsets into the open segment: [from] inclusive, [to] exclusive. */
    private class Span(val from: Int, val to: Int)

    /**
     * The sample range worth decoding: the speech, plus [keepQuietMillis] either side.
     *
     * Null when there is no speech in it. Where the energy floor found nothing but the
     * segment is not actually silent — a noisy site where speech never clears the floor by
     * enough — the whole segment is sent rather than nothing, because "nothing recognised"
     * from a sentence somebody clearly said is the worse outcome.
     */
    private fun decodeRange(
        endFrame: Int,
        includeTail: Boolean,
    ): Span? {
        val end = min(endFrame, analysedFrames)
        val first = firstSpeechFrame(end)
        val last = lastSpeechFrame(end)
        val fromFrame: Int
        val toFrame: Int
        if (first < 0) {
            if (!isAudible(end)) return null
            fromFrame = 0
            toFrame = end
        } else {
            fromFrame = max(0, first - frames(keepQuietMillis))
            toFrame = min(end, last + 1 + frames(keepQuietMillis))
        }
        val fromSample = fromFrame * frameSamples
        val toSample =
            if (includeTail && toFrame == analysedFrames) size else min(size, toFrame * frameSamples)
        if (toSample <= fromSample) return null
        return Span(fromSample, toSample)
    }

    /**
     * Applies the ownership rules to a decode of the open segment.
     *
     * @param keepBeforeSample words emitted at or after this are the next segment's; used
     *   by a forced cut for the left side. [Long.MAX_VALUE] keeps everything.
     */
    private fun attributed(
        words: List<Placed>,
        keepBeforeSample: Long,
    ): List<Placed> {
        var kept = words.filter { it.atSample < 0 || it.atSample < keepBeforeSample }
        val edge = boundary ?: return kept
        kept = kept.filter { it.atSample < 0 || it.atSample >= edge.dropBeforeSample }
        // Both sides heard the word on the cut, and they may have timed it a frame apart on
        // either side of the handover. The same text within a quarter second is one word.
        val previous = fixed.lastOrNull()
        val next = kept.firstOrNull()
        if (previous != null && next != null &&
            previous.text == next.text &&
            previous.atSample >= 0 && next.atSample >= 0 &&
            abs(previous.atSample - next.atSample) < samples(DEDUPE_MILLIS)
        ) {
            kept = kept.drop(1)
        }
        return kept
    }

    // ── the energy floor ─────────────────────────────────────────────────────

    private fun append(samples: ShortArray) {
        if (size + samples.size > pcm.size) {
            pcm = pcm.copyOf(max(pcm.size * 2, size + samples.size))
        }
        System.arraycopy(samples, 0, pcm, size, samples.size)
        size += samples.size
    }

    /** Measures every complete frame not yet measured. */
    private fun analyse() {
        while ((levels.size + 1) * frameSamples <= size) {
            val offset = levels.size * frameSamples
            var sum = 0.0
            for (i in offset until offset + frameSamples) {
                val s = pcm[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / frameSamples)
            val level = if (rms < 1.0) NO_SIGNAL_DB else (20.0 * log10(rms / 32768.0)).toFloat()
            levels += level
            if (level > NO_SIGNAL_DB) {
                floorDb =
                    when {
                        floorDb.isNaN() || level < floorDb -> level.toDouble()
                        else -> min(level.toDouble(), floorDb + FLOOR_RISE_DB_PER_FRAME)
                    }.coerceIn(FLOOR_MIN_DB, FLOOR_MAX_DB)
            }
        }
    }

    /**
     * Whether frame [f] carries speech, judged against the floor **as it is now**.
     *
     * Judged now rather than when the frame arrived, deliberately. The floor is a running
     * minimum: if the first frames were loud — the operator was already mid-word when the
     * control went down — the floor starts high and those frames look quiet, until the
     * first real pause pulls it down and they are seen for what they were. Deciding late
     * means a loud opening is never trimmed away as "leading silence".
     */
    private fun isSpeech(f: Int): Boolean {
        if (floorDb.isNaN()) return false
        return levels[f] > floorDb + SPEECH_ABOVE_FLOOR_DB
    }

    private fun speechFrames(end: Int = analysedFrames): Int {
        var n = 0
        for (f in 0 until end) if (isSpeech(f)) n++
        return n
    }

    /**
     * Whether the open segment holds anything worth decoding.
     *
     * Enough speech by the floor's judgement; or, where the floor found none at all, any
     * audio above a quiet room — see [decodeRange]. A frame or two above the floor with
     * nothing else is a click, and is not sent to the model to be made into a word.
     */
    private fun hasSpeech(): Boolean {
        val speech = speechFrames()
        return speech * FRAME_MILLIS >= MIN_TAIL_SPEECH_MILLIS || (speech == 0 && isAudible(analysedFrames))
    }

    private fun firstSpeechFrame(end: Int = analysedFrames): Int {
        for (f in 0 until end) if (isSpeech(f)) return f
        return -1
    }

    private fun lastSpeechFrame(end: Int = analysedFrames): Int {
        for (f in end - 1 downTo 0) if (isSpeech(f)) return f
        return -1
    }

    private fun trailingQuietFrames(): Int = analysedFrames - 1 - lastSpeechFrame()

    /** Whether anything in the segment is louder than a quiet room, floor or no floor. */
    private fun isAudible(end: Int): Boolean {
        for (f in 0 until end) if (levels[f] > AUDIBLE_DB) return true
        return false
    }

    private fun frames(millis: Int): Int = millis / FRAME_MILLIS

    private fun samples(millis: Int): Long = millis.toLong() * sampleRate / 1000

    companion object {
        const val FRAME_MILLIS = 20

        /** Quiet that ends a clause. Stop closures and inter-word gaps are shorter. */
        const val PAUSE_MILLIS = 280

        /** Speech a segment must hold before a pause may close it; below this it is a word, not a clause. */
        const val MIN_SPEECH_MILLIS = 500

        /** Beyond this a segment is cut whether or not the speaker paused. */
        const val MAX_SEGMENT_MILLIS = 6_000

        /** Audio each side of a forced cut keeps past it, so the word on the cut is heard whole by one of them. */
        const val CONTEXT_MILLIS = 600

        /** Quiet a segment closed at a pause begins with. */
        const val LEAD_MILLIS = 120

        /** Quiet kept either side of the speech in a segment sent to the model. */
        const val KEEP_QUIET_MILLIS = 300

        /** How often the open segment is re-read for running text. */
        const val PROVISIONAL_EVERY_MILLIS = 1_000

        /** Beyond this the open segment is not re-read: the cost would land on the endpoint. */
        const val PROVISIONAL_MAX_MILLIS = 2_500

        /** Quiet a provisional reading must extend past the last speech for it to be final. */
        const val SETTLE_MILLIS = 200

        /** Below this much speech in the tail, nothing was said. A click is one frame. */
        const val MIN_TAIL_SPEECH_MILLIS = 100

        /** How far back a forced cut looks for its quietest frame. */
        const val SEARCH_MILLIS = 1_500

        /** A word beginning this close after a forced cut still belongs to the left side. */
        const val HYSTERESIS_MILLIS = 100

        /** The same word timed this close on both sides of a forced cut is one word. */
        const val DEDUPE_MILLIS = 250

        const val SPEECH_ABOVE_FLOOR_DB = 8.0

        /** 2.5 dB per second. A six-second clause raises the floor 15 dB; speech is 25-40 dB up. */
        const val FLOOR_RISE_DB_PER_FRAME = 0.05

        /** A quiet room reads -65 to -55 dBFS; this stops digital silence from making noise "speech". */
        const val FLOOR_MIN_DB = -70.0

        /**
         * No floor sits above this. The operator's own voice at -15 dBFS from the very first
         * frame must not become the floor and make every later frame "quiet"; anything this
         * loud for that long is speech or a site where pauses cannot be heard anyway.
         */
        const val FLOOR_MAX_DB = -35.0

        /** Frames below this are no signal at all and do not move the floor. */
        const val NO_SIGNAL_DB = -80f

        /** Louder than a quiet room. The fallback when the floor found no speech. */
        const val AUDIBLE_DB = -45f
    }
}

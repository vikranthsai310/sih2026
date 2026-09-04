package org.itantra.bench

import org.itantra.proto.Frame
import org.itantra.proto.TransportClass

/**
 * The compression claim, computed four ways. Task **W8.9**.
 *
 * ## Why four and not one
 *
 * 2 182× is the headline and it is the weakest of the four in front of a technical panel,
 * because the first question is always "what about encryption?" — and the second is "why
 * not just use a codec?". Having only the largest number ready is a bad thirty seconds.
 *
 * The four are not variations on a theme; each answers a different question and each is
 * measured against a **different frame variant**. Getting that pairing wrong is how the
 * figures drift:
 *
 * | Ratio | Frame | Answers |
 * | --- | --- | --- |
 * | [unauthenticated] | packed text, no tag | "how small can it get?" |
 * | [authenticated] | packed text, 16-byte tag | "what do you actually deploy?" |
 * | [versusOpus] | packed text, 8-byte tag vs Opus at 6 kbps | "why not just use a codec?" |
 * | [template] | one-byte template id, no tag | "what is the best case?" — labelled as one |
 *
 * Note that the authenticated ratio and the Opus ratio use **different tags**. That is not
 * sloppiness: a Bluetooth link gets the full 16-byte tag because bytes are free there, and
 * a 300 bps serial link gets the truncated 8-byte tag because eight bytes is 27 seconds of
 * airtime — [TransportClass] decides, and the ratio has to follow the transport it is
 * quoted for. Quoting the 8-byte figure as "the authenticated ratio" would overstate the
 * deployment case by 15 %.
 *
 * ## Derived, never transcribed
 *
 * Every size here comes from [Frame.HEADER_SIZE], [Frame.CRC_SIZE] and [TransportClass],
 * so changing the header changes the published ratios and the tests that pin them fail. A
 * ratio typed into a slide from a document that was typed from a spreadsheet is how "22 B"
 * and `RESCUE-A` drifted — see task W8.11.
 */
object CompressionRatios {
    /** 16-bit mono PCM at the recogniser's rate. The thing being compressed. */
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    /** The reference utterance every published figure uses. `docs/EVALUATION.md` §5. */
    const val REFERENCE_SECONDS = 3.0

    /**
     * Opus at its floor. 6 kbps is the lowest bitrate Opus encodes speech at usefully, and
     * it is a **bound of the codec**, not an engineering gap in it — which is the whole
     * answer to "why not just use a codec?".
     */
    const val OPUS_BITS_PER_SECOND = 6_000

    /**
     * A packed Hindi sentence. Level 2 script packing gives one byte per character, so
     * this is a 32-character sentence — `docs/PROTOCOL.md` §1.
     */
    const val PACKED_SENTENCE_PAYLOAD = 32

    /** A template code is one byte of payload. That is the entire message. */
    const val TEMPLATE_PAYLOAD = 1

    /** Raw PCM for [REFERENCE_SECONDS], in bytes: 96 000. */
    val rawAudioBytes: Int
        get() = (SAMPLE_RATE * REFERENCE_SECONDS * BYTES_PER_SAMPLE).toInt()

    /** Opus at its floor for the same utterance, in bytes: 2 250. */
    val opusBytes: Int
        get() = (OPUS_BITS_PER_SECOND * REFERENCE_SECONDS / 8).toInt()

    /**
     * Wire size of a frame carrying [payloadBytes] with [tagBytes] of authentication tag.
     *
     * The tag is part of the payload on the wire — it is appended by the AEAD seal, not
     * carried in a separate field — so it counts once, here.
     */
    fun wireBytes(
        payloadBytes: Int,
        tagBytes: Int = 0,
    ): Int = Frame.HEADER_SIZE + payloadBytes + tagBytes + Frame.CRC_SIZE

    /** 44 B on the wire. The headline, and the one that invites the next question. */
    val unauthenticated: Ratio
        get() =
            Ratio(
                name = "packed text vs raw PCM, unauthenticated",
                fromBytes = rawAudioBytes,
                toBytes = wireBytes(PACKED_SENTENCE_PAYLOAD),
                caveat = "unauthenticated; no deployment worth defending runs this way",
            )

    /**
     * 60 B on the wire with the full tag. **The honest deployment figure**, because
     * `ENCRYPTED` is set by default for every new pairing and Bluetooth is the transport.
     */
    val authenticated: Ratio
        get() =
            Ratio(
                name = "packed text vs raw PCM, authenticated",
                fromBytes = rawAudioBytes,
                toBytes = wireBytes(PACKED_SENTENCE_PAYLOAD, TransportClass.BLE.tagBytes),
                caveat = "16-byte tag, the Bluetooth default",
            )

    /**
     * 52 B against Opus's 2 250 B. Uses the **truncated** tag, because the comparison is
     * with a low-rate radio link and that is the transport where a truncated tag is
     * defensible.
     */
    val versusOpus: Ratio
        get() =
            Ratio(
                name = "packed text vs Opus at 6 kbps",
                fromBytes = opusBytes,
                toBytes = wireBytes(PACKED_SENTENCE_PAYLOAD, TransportClass.SERIAL_LOW_RATE.tagBytes),
                caveat = "8-byte tag, the low-rate link where Opus would be the alternative",
            )

    /** 13 B. The best case, and it must always be introduced as one. */
    val template: Ratio
        get() =
            Ratio(
                name = "template code vs raw PCM",
                fromBytes = rawAudioBytes,
                toBytes = wireBytes(TEMPLATE_PAYLOAD),
                caveat = "best case: a sentence already in the table, unauthenticated",
            )

    /** All four, in the order they should be offered. */
    val all: List<Ratio>
        get() = listOf(unauthenticated, authenticated, versusOpus, template)

    /** `compression.csv`. Every figure with the arithmetic that produced it beside it. */
    fun toCsv(): String =
        buildString {
            append("measure,from_bytes,to_bytes,ratio,caveat\n")
            for (ratio in all) {
                append(ratio.name).append(',')
                    .append(ratio.fromBytes).append(',')
                    .append(ratio.toBytes).append(',')
                    .append(ratio.rounded).append(',')
                    .append('"').append(ratio.caveat.replace("\"", "\"\"")).append('"')
                    .append('\n')
            }
        }

    data class Ratio(
        val name: String,
        val fromBytes: Int,
        val toBytes: Int,
        val caveat: String,
    ) {
        init {
            require(toBytes > 0) { "$name: a ratio against zero bytes is not a ratio" }
            require(fromBytes > 0) { "$name: nothing to compress" }
        }

        val value: Double get() = fromBytes.toDouble() / toBytes

        /** As published: whole numbers, because a compression ratio to one decimal is noise. */
        val rounded: Int get() = Math.round(value).toInt()

        /** "2 182× — 96 000 B to 44 B". The arithmetic travels with the claim. */
        fun statement(): String = "$rounded× — $fromBytes B to $toBytes B ($caveat)"
    }
}

package org.itantra.proto

import java.text.Normalizer

/**
 * Level 2 compression: script packing.
 *
 * UTF-8 spends three bytes per character on every Indic script, because it must be
 * able to represent all scripts at once. The frame header already declares the
 * language, so the receiver knows which 128-code-point block applies and subtracting
 * the block base yields a single byte per character. Three bytes become one.
 *
 * The transformation is lossless, table-driven, costs microseconds, and is available
 * only because we control both endpoints.
 *
 * ## Alphabet — `docs/PROTOCOL.md` section 4.1
 *
 * | Byte range            | Meaning                                              |
 * |-----------------------|------------------------------------------------------|
 * | `0x00`–`0x1A`, `0x1C`–`0x7F` | Literal ASCII code point                      |
 * | `0x1B`                | Escape: the next three bytes are a 24-bit code point  |
 * | `0x80`–`0xFF`         | `blockBase + (byte - 0x80)`                           |
 *
 * ## Why the escape is not optional
 *
 * Real operational messages are not pure Indic script. They contain Latin digits,
 * punctuation, embedded English call signs, and — critically — **ZWJ `U+200D` and
 * ZWNJ `U+200C`**, which lie outside every Indic block but change the rendering and,
 * in some languages, the meaning of a conjunct. A packer without an escape is
 * silently lossy on ordinary input, which is a correctness defect rather than a
 * compression inefficiency. This was risk T-13.
 */
object ScriptPacker {
    private const val ESCAPE = 0x1B
    private const val BLOCK_START = 0x80
    private const val BLOCK_SIZE = 128

    /**
     * Packs [text] for [language].
     *
     * The text is normalised to Unicode NFC first: two visually identical strings
     * that differ in composition would otherwise pack to different byte sequences
     * and defeat template matching.
     */
    fun pack(
        text: String,
        language: Language,
    ): ByteArray {
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = ArrayList<Byte>(normalised.length + 8)
        val base = language.blockBase

        var i = 0
        while (i < normalised.length) {
            val cp = normalised.codePointAt(i)
            i += Character.charCount(cp)

            when {
                cp <= 0x7F && cp != ESCAPE -> out.add(cp.toByte())

                base != null && cp >= base && cp < base + BLOCK_SIZE ->
                    out.add((BLOCK_START + (cp - base)).toByte())

                else -> {
                    // 0x1B then a 24-bit big-endian scalar value. Four bytes for a
                    // character that would otherwise be lost; rare by construction.
                    out.add(ESCAPE.toByte())
                    out.add(((cp shr 16) and 0xFF).toByte())
                    out.add(((cp shr 8) and 0xFF).toByte())
                    out.add((cp and 0xFF).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Reverses [pack].
     *
     * @throws IllegalArgumentException if an escape sequence is truncated, which can
     *   only happen on a corrupt payload — the CRC and the AEAD tag are both checked
     *   before this is reached.
     */
    fun unpack(
        bytes: ByteArray,
        language: Language,
    ): String {
        val sb = StringBuilder(bytes.size)
        val base = language.blockBase

        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == ESCAPE -> {
                    require(i + 3 < bytes.size) {
                        "truncated escape sequence at offset $i of ${bytes.size}"
                    }
                    val cp =
                        ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                            (bytes[i + 3].toInt() and 0xFF)
                    require(Character.isValidCodePoint(cp)) { "invalid code point $cp at offset $i" }
                    sb.appendCodePoint(cp)
                    i += 4
                }

                b < BLOCK_START -> {
                    sb.append(b.toChar())
                    i += 1
                }

                else -> {
                    requireNotNull(base) {
                        "byte 0x${b.toString(16)} needs a script block, but ${language.code} has none"
                    }
                    sb.appendCodePoint(base + (b - BLOCK_START))
                    i += 1
                }
            }
        }
        return sb.toString()
    }

    /**
     * Whether packing is worth doing for this text.
     *
     * Because UTF-8 already encodes ASCII in one byte, the encoder MUST clear the
     * `PACKED` flag and transmit plain UTF-8 whenever packing does not reduce the
     * payload. Receivers honour the flag rather than assume.
     */
    fun isWorthPacking(
        text: String,
        language: Language,
    ): Boolean = pack(text, language).size < text.toByteArray(Charsets.UTF_8).size
}

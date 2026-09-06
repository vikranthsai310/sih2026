package org.itantra.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The conversion sherpa-onnx's own script performs, checked byte for byte against a
 * protobuf reader written independently here. The claim is not that sherpa loads the
 * result — that is proven on a handset — but that what is appended is exactly the
 * `metadata_props` encoding, on the model's own bytes, once.
 */
class PiperVoiceMetadataTest {
    /** A minimal ModelProto: `ir_version = 8` (field 1, varint) and a `producer_name`. */
    private val bareModel = byteArrayOf(0x08, 0x08, 0x12, 0x05) + "piper".toByteArray(Charsets.US_ASCII)

    private val entries =
        listOf(
            "model_type" to "vits",
            "comment" to "piper",
            "language" to "Hindi",
            "voice" to "hi",
            "has_espeak" to "1",
            "n_speakers" to "1",
            "sample_rate" to "22050",
        )

    // ── a tiny protobuf reader, so the test does not trust the encoder it is testing ──

    private class Reader(private val b: ByteArray) {
        var i = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val c = b[i++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(n: Int): ByteArray = b.copyOfRange(i, i + n).also { i += n }

        val done: Boolean get() = i >= b.size
    }

    /** Every top-level field as (field number, payload); varints as their value. */
    private fun fields(bytes: ByteArray): List<Pair<Int, Any>> {
        val r = Reader(bytes)
        val out = ArrayList<Pair<Int, Any>>()
        while (!r.done) {
            val key = r.varint()
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += field to r.varint()
                2 -> out += field to r.bytes(r.varint().toInt())
                else -> error("unexpected wire type in test data")
            }
        }
        return out
    }

    private fun metadata(bytes: ByteArray): List<Pair<String, String>> =
        fields(bytes).filter { it.first == 14 }.map { (_, payload) ->
            val inner = fields(payload as ByteArray)
            String(inner.first { it.first == 1 }.second as ByteArray) to
                String(inner.first { it.first == 2 }.second as ByteArray)
        }

    @Test
    fun `the encoding is metadata_props entries a protobuf reader gets back unchanged`() {
        assertEquals(entries, metadata(PiperVoiceMetadata.encode(entries)))
    }

    @Test
    fun `stamping appends to the model without touching what was there`() {
        val dir = Files.createTempDirectory("voice").toFile()
        val model = File(dir, "model.onnx").apply { writeBytes(bareModel) }

        assertFalse(PiperVoiceMetadata.isStamped(model))
        assertTrue(PiperVoiceMetadata.stamp(model, entries))
        assertTrue(PiperVoiceMetadata.isStamped(model))

        val bytes = model.readBytes()
        assertTrue("the original bytes lead", bytes.copyOfRange(0, bareModel.size).contentEquals(bareModel))
        val parsed = fields(bytes)
        assertEquals("ir_version survives", 8L, parsed.first { it.first == 1 }.second)
        assertEquals(entries, metadata(bytes))
        assertFalse("no staging file is left behind", File(dir, "model.onnx.partial").exists())
    }

    @Test
    fun `a long value uses a multi-byte length`() {
        val long = "x".repeat(300)
        val encoded = PiperVoiceMetadata.encode(listOf("comment" to long))
        assertEquals(listOf("comment" to long), metadata(encoded))
    }

    @Test
    fun `a stamped voice is not stamped twice`() {
        val dir = Files.createTempDirectory("voice").toFile()
        val model = File(dir, "model.onnx").apply { writeBytes(bareModel + PiperVoiceMetadata.encode(entries)) }
        File(dir, "config.json").writeText("{}")
        assertFalse(PiperVoiceMetadata.ensure(model))
        assertEquals(entries, metadata(model.readBytes()))
    }

    @Test
    fun `a voice with no config beside it is left alone`() {
        val dir = Files.createTempDirectory("voice").toFile()
        val model = File(dir, "model.onnx").apply { writeBytes(bareModel) }
        assertFalse(PiperVoiceMetadata.ensure(model))
        assertTrue(model.readBytes().contentEquals(bareModel))
    }
}

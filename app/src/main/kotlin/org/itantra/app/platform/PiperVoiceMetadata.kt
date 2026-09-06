package org.itantra.app.platform

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Makes a voice downloaded from Piper's own repository loadable by sherpa-onnx.
 *
 * ## The problem
 *
 * The install index points at `rhasspy/piper-voices`, which is where the voices are
 * published and the only place all six of them are. A file from there is a valid ONNX
 * graph and a valid Piper voice, and sherpa-onnx cannot use it: sherpa reads the voice's
 * sample rate, speaker count and phonemiser from the model's own **metadata**, and Piper
 * writes none. Given a voice without it, `OfflineTtsVitsModel::Init` logs
 * `'sample_rate' does not exist in the metadata` and calls `exit(-1)`.
 *
 * `ModelStore.hasVoice` refuses such a file rather than let the process die, which is
 * right — and it meant that an operator who downloaded the English voice, selected it and
 * installed it was shown "DOWNLOAD" again, with no word about why. The file was there. It
 * was simply not one this application could ever speak with.
 *
 * ## The fix
 *
 * sherpa-onnx publishes re-exports of *some* Piper voices with the metadata added, and the
 * whole of its conversion is seven key-value pairs appended to the model — its own script,
 * `vits-piper-en_US.py`, does nothing else. Checked, not assumed: the English voice from
 * `rhasspy` is 63 201 294 bytes and sherpa's re-export of it is 63 201 425, the difference
 * being exactly those pairs. The values come from the `.onnx.json` beside the voice, which
 * this application already bundles.
 *
 * So the conversion is done here, on the handset, at install. An ONNX model is a protobuf
 * `ModelProto`, `metadata_props` is its field 14, and a protobuf parser accepts a field
 * anywhere in the message — so the pairs are appended to the file as they are, with no
 * library and without reading sixty megabytes of graph. Every Piper voice becomes usable,
 * not only the ones somebody happened to re-export.
 */
object PiperVoiceMetadata {
    /** What sherpa-onnx looks for, and where it exits if it cannot find it. */
    const val SAMPLE_RATE_KEY = "sample_rate"

    private const val CONFIG = "config.json"

    /** Comfortably more than the few hundred bytes the metadata occupies at the tail. */
    private const val TAIL_BYTES = 64L * 1024

    /**
     * Whether the model already carries sherpa's metadata, judged from its tail.
     *
     * The metadata sits in the last few hundred bytes of a model, whether sherpa's script
     * wrote it or this did, so a tail read settles it without touching the graph.
     */
    fun isStamped(model: File): Boolean =
        runCatching {
            model.inputStream().use { stream ->
                stream.skip((model.length() - TAIL_BYTES).coerceAtLeast(0L))
                contains(stream.readBytes(), SAMPLE_RATE_KEY.toByteArray(Charsets.US_ASCII))
            }
        }.getOrDefault(false)

    /**
     * Stamps [model] from the `config.json` beside it, if it needs stamping and one exists.
     *
     * A voice that is not shaped like a Piper voice — Gujarati, which is Mimic 3 and ships
     * with sherpa's metadata already — has no config beside it and is left alone.
     *
     * @return true if the file was changed.
     */
    fun ensure(model: File): Boolean {
        if (!model.isFile() || isStamped(model)) return false
        val config = File(model.parentFile, CONFIG)
        if (!config.isFile()) return false
        val entries = entriesFrom(runCatching { config.readText() }.getOrNull() ?: return false) ?: return false
        return stamp(model, entries)
    }

    /**
     * Stamps every voice under [voices] that needs it. For a handset that installed a
     * voice before this existed, and has been shown "DOWNLOAD" for it ever since.
     *
     * @return how many were changed.
     */
    fun ensureAll(voices: File): Int =
        voices.listFiles().orEmpty().count { dir ->
            dir.isDirectory && ensure(File(dir, MODEL))
        }

    /**
     * The seven pairs sherpa's own conversion writes, from a Piper `.onnx.json`.
     *
     * Null when the config is not a Piper one — no sample rate, say — because guessing a
     * sample rate is how a voice comes out at the wrong pitch rather than not at all.
     */
    fun entriesFrom(configJson: String): List<Pair<String, String>>? =
        runCatching {
            val config = JSONObject(configJson)
            val sampleRate = config.getJSONObject("audio").getInt("sample_rate")
            val espeak = config.optJSONObject("espeak")
            val language = config.optJSONObject("language")?.optString("name_english").orEmpty()
            listOf(
                "model_type" to "vits",
                // Must be "piper" for models from Piper: it selects sherpa's Piper frontend.
                "comment" to "piper",
                "language" to language.ifEmpty { "unknown" },
                "voice" to (espeak?.optString("voice").orEmpty()),
                "has_espeak" to (if (espeak != null) "1" else "0"),
                "n_speakers" to config.optInt("num_speakers", 1).toString(),
                SAMPLE_RATE_KEY to sampleRate.toString(),
            )
        }.getOrNull()

    /**
     * Appends [entries] to [model] as `metadata_props`, through a staged copy.
     *
     * A copy and a rename rather than an append in place: an append cut short by a flat
     * battery leaves a model whose tail says `sample_rate` and does not parse, which is the
     * one shape of file that gets past [isStamped] and into sherpa's `exit(-1)`.
     */
    fun stamp(
        model: File,
        entries: List<Pair<String, String>>,
    ): Boolean {
        val staged = File(model.parentFile, model.name + ".partial")
        return runCatching {
            staged.outputStream().use { out ->
                model.inputStream().use { it.copyTo(out) }
                out.write(encode(entries))
            }
            // Files.move rather than File.renameTo: the latter will not replace an existing
            // target on every filesystem, and the target here always exists.
            Files.move(staged.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false).also { if (!it) staged.delete() }
    }

    /**
     * The protobuf bytes for a run of `metadata_props` entries.
     *
     * `ModelProto.metadata_props` is field 14, a repeated `StringStringEntryProto` whose
     * `key` is field 1 and `value` field 2. All three are length-delimited (wire type 2),
     * so each is a tag byte, a varint length, and the bytes.
     */
    fun encode(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val entry = ByteArrayOutputStream()
            writeField(entry, 1, key.toByteArray(Charsets.UTF_8))
            writeField(entry, 2, value.toByteArray(Charsets.UTF_8))
            writeField(out, METADATA_PROPS, entry.toByteArray())
        }
        return out.toByteArray()
    }

    private const val METADATA_PROPS = 14
    private const val MODEL = "model.onnx"
    private const val LENGTH_DELIMITED = 2

    private fun writeField(
        out: ByteArrayOutputStream,
        field: Int,
        bytes: ByteArray,
    ) {
        writeVarint(out, ((field shl 3) or LENGTH_DELIMITED).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean =
        (0..haystack.size - needle.size).any { i ->
            needle.indices.all { j -> haystack[i + j] == needle[j] }
        }
}

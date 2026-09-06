package org.itantra.app.platform

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * What a handset may be given, and how it knows what it has been given.
 *
 * ## The problem this solves
 *
 * This application has no `INTERNET` permission and will not get one — constraint **C2** —
 * so it cannot fetch its own language pack. The operator's **browser** can, which is a
 * different program with its own permissions, and that keeps every claim intact: nothing
 * here ever opens a socket.
 *
 * What that creates is a problem of identity. A file sitting in `Download/` called
 * `model.int8.onnx` could be any of ten languages, and `tokens.txt` could be either
 * alphabet. Filenames do not say. **SHA-256 does.** This index maps a hash to the place the
 * file belongs, so a download is identified by its content, installed correctly, and a
 * truncated one is rejected rather than installed as silence.
 *
 * It is the same integrity guarantee `tools/fetch_models.py` gives on a workstation, from
 * the same hashes, reached by a different road.
 */
class InstallIndex(context: Context) {
    data class Item(
        val sha256: String,
        val bytes: Long,
        /** Path under the models directory, e.g. `asr/hi/model.int8.onnx`. */
        val install: String,
        val url: String,
        val language: String,
        val kind: String,
        /**
         * Whether this artefact ships inside the installer rather than being downloaded.
         *
         * A browser will not save a file the server marks `Content-Disposition: inline`,
         * and Hugging Face marks every `text/plain` file that way whatever query is
         * appended. The token tables and voice configs are 2–66 kB, so they are bundled
         * and never appear in a list of things to fetch.
         */
        val bundled: Boolean,
    )

    private val items: List<Item> =
        runCatching {
            val text = context.assets.open(INDEX_ASSET).bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("items")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Item(
                    sha256 = o.getString("sha256").lowercase(),
                    bytes = o.getLong("bytes"),
                    install = o.getString("install"),
                    url = o.getString("url"),
                    language = o.getString("language"),
                    kind = o.getString("kind"),
                    bundled = o.optBoolean("bundled", false),
                )
            }
        }.getOrDefault(emptyList())

    val size: Int get() = items.size

    /** @return where this content belongs, or null if it is not something we asked for. */
    fun identify(sha256: String): Item? = items.firstOrNull { it.sha256 == sha256.lowercase() }

    /**
     * Every byte-count an artefact could have.
     *
     * A cheap pre-filter: a file whose length matches nothing here cannot be a pack file,
     * and reading it to find that out is what makes importing from a full `Download` folder
     * appear to hang.
     */
    fun expectedSizes(): Set<Long> = items.mapTo(HashSet()) { it.bytes }

    /** Everything for one language, so the storage screen can list what to download. */
    fun forLanguage(language: String): List<Item> = items.filter { it.language == language }

    /** Only what the operator must actually fetch: the large binaries. */
    fun downloadableFor(language: String): List<Item> = items.filter { it.language == language && !it.bundled }

    /** Sizes matter to somebody about to download on mobile data. */
    fun bytesFor(language: String): Long = forLanguage(language).sumOf { it.bytes }

    companion object {
        const val INDEX_ASSET = "install-index.json"

        /** Streamed rather than read whole: these files are up to 198 MB. */
        fun sha256Of(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            stream.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The small text artefacts, shipped in the installer and expanded once.
 *
 * Token tables and voice configs are 2–66 kB each and cannot be downloaded on a phone: a
 * browser renders a `text/plain` response rather than saving it, and Hugging Face serves
 * every one of them that way whatever query string is appended. Forty kilobytes in the APK
 * removes the whole problem, and leaves the download list holding only the large binaries
 * — which do save, given `?download=true`.
 *
 * A voice's `tokens.txt` is generated from its config here rather than shipped, because the
 * two would then disagree if either changed. `tools/piper_tokens.py` carries the check that
 * this ordering is the one sherpa-onnx publishes.
 */
class SmallArtefacts(private val context: Context) {
    private val models: File get() = File(context.getExternalFilesDir(null), "models")

    /** @return how many files were written. Zero once they are already there. */
    fun ensure(): Int {
        // A sentinel of its own, not one of the files it writes. Using the shared token
        // table as the marker meant a handset that had already imported that one file by
        // hand was judged complete, and the other fifteen were never expanded.
        val done = File(models, MARKER)
        if (done.isFile()) return 0
        var written = 0
        runCatching {
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val out = File(models, entry.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            written++
                            if (entry.name.endsWith("config.json")) writeVoiceTokens(out)
                        }
                        zip.closeEntry()
                    }
                }
            }
        }.onSuccess {
            if (written > 0) {
                runCatching {
                    done.parentFile?.mkdirs()
                    done.writeText(ASSET)
                }
            }
        }
        return written
    }

    private fun writeVoiceTokens(config: File) {
        runCatching {
            val map = JSONObject(config.readText()).getJSONObject("phoneme_id_map")
            val out = StringBuilder()
            for (phoneme in map.keys()) {
                val ids = map.getJSONArray(phoneme)
                if (ids.length() != 1) return@runCatching
                out.append(phoneme).append(' ').append(ids.getInt(0)).append('\n')
            }
            File(config.parentFile, "tokens.txt").writeText(out.toString())
        }
    }

    private companion object {
        const val ASSET = "small-artefacts.zip"

        /** Written only after a complete expansion, so a partial one is retried. */
        const val MARKER = ".small-artefacts"
    }
}

/**
 * espeak-ng's data, bundled in the installer and expanded once.
 *
 * Every Piper voice needs it to turn text into phonemes, and it is the only part of a voice
 * that arrives as an archive rather than as a single file — which would have made
 * browser-only installation impossible for synthesis. So it ships **inside** the APK
 * instead: pruned from a hundred languages to these ten by `tools/build_espeak_min.py`,
 * which takes 17 MB down to 2.1 MB, and 0.71 MB compressed. The installer stays under the
 * 30 MB constraint N2 asks for.
 *
 * This is also the GPL-3.0 component `LICENSES.md` section 6 is about. Its licence has
 * shipped in the application since the licences screen was built; now the data does too,
 * which changes nothing legally and is worth stating rather than discovering.
 */

class EspeakData(private val context: Context) {
    private val target: File get() = File(context.getExternalFilesDir(null), "models/tts/espeak-ng-data")

    /** @return the directory, expanding it on first use. Null if it could not be written. */
    fun ensure(): File? {
        // phondata is espeak's own core table: if it is there, the expansion finished.
        if (File(target, MARKER).isFile()) return target
        return runCatching {
            target.mkdirs()
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = File(target, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            target
        }.getOrNull()
    }

    private companion object {
        const val ASSET = "espeak-ng-data.zip"
        const val MARKER = "phondata"
    }
}

package org.itantra.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Copies a language pack onto the handset from a folder the operator chooses.
 *
 * ## Why this had to exist
 *
 * The acoustic models are ~189 MB each and the voices ~63 MB, so they cannot be in a 30 MB
 * installer — constraint **N2** — and they must not be downloaded at runtime, because there
 * is no `INTERNET` permission and constraint **C2** says there never will be. `C2` allows
 * exactly one thing: *"Language packs may be fetched once during setup."*
 *
 * Until now "setup" meant `adb push`, which works on a handset wired to a developer's
 * machine and nowhere else. Sharing the APK to a second phone produced an application that
 * reported no language installed and gave the operator no way to fix it — and no way to fix
 * it by hand either, because `Android/data/` has not been reachable by file managers since
 * Android 11.
 *
 * ## Why the Storage Access Framework
 *
 * The operator picks a folder and the system hands this a permission scoped to that folder
 * alone. No `READ_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE` — nothing declared in the
 * manifest at all, so the permission list a jury reads does not grow by one entry for a
 * copy that happens once. The packs can arrive on the handset any way at all: a cable, an
 * SD card, a shared folder, another phone.
 *
 * ## What it expects
 *
 * Any folder. Every file in it is hashed and looked up in [InstallIndex]: a match is
 * installed where it belongs, and anything else is left alone. That is why the operator can
 * simply point this at `Download/` after fetching artefacts in a browser — filenames there
 * are whatever the server called them, and `model.int8.onnx` could be any of ten languages.
 * Content decides, so a truncated download is rejected rather than installed as silence.
 */
class PackInstaller(private val context: Context) {
    data class Result(
        val files: Int,
        val bytes: Long,
        val problem: String? = null,
    ) {
        val ok: Boolean get() = problem == null && files > 0

        fun describe(): String =
            when {
                problem != null -> problem
                files == 0 -> "Nothing to copy in that folder."
                else -> "Installed %d files, %.0f MB.".format(files, bytes / 1_048_576.0)
            }
    }

    /**
     * @param onProgress called with each file as it is examined, so hashing two gigabytes
     *   is visibly working rather than apparently hung
     */
    suspend fun install(
        tree: Uri,
        onProgress: (String) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            val root = File(context.getExternalFilesDir(null), "models")
            val index = InstallIndex(context)
            if (index.size == 0) {
                return@withContext Result(0, 0, "The install index is missing from this build.")
            }

            val candidates = ArrayList<Entry>()
            runCatching { collect(tree, DocumentsContract.getTreeDocumentId(tree), candidates, 0) }
                .getOrNull()
                ?: return@withContext Result(0, 0, "That folder could not be read.")

            if (candidates.isEmpty()) {
                return@withContext Result(0, 0, "No files in that folder.")
            }

            // Size before hash. A model is identified by SHA-256, but hashing everything in
            // `Download` means reading gigabytes of somebody's video library before reaching
            // the four files that matter -- which looks exactly like the application having
            // done nothing at all. Only a file whose length already matches an indexed
            // artefact is worth reading, and that is almost always just those four.
            val sizes = index.expectedSizes()
            val worthReading = candidates.filter { it.bytes in sizes }
            if (worthReading.isEmpty()) {
                return@withContext Result(
                    0,
                    0,
                    "Checked " + candidates.size + " file(s); none is the size of a " +
                        "language-pack file. Downloaded the models yet?",
                )
            }

            var files = 0
            var bytes = 0L
            var unknown = 0
            for ((n, entry) in worthReading.withIndex()) {
                onProgress("checking " + entry.name + " (" + (n + 1) + " of " + worthReading.size + ")")
                val source = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                val hash =
                    runCatching {
                        context.contentResolver.openInputStream(source)?.let(InstallIndex::sha256Of)
                    }.getOrNull() ?: continue

                val item = index.identify(hash)
                if (item == null) {
                    unknown++
                    continue
                }
                val written = copy(source, File(root, item.install), onProgress)
                if (written > 0) {
                    files++
                    bytes += written
                    // A Piper voice publishes its phoneme table inside the config, and
                    // sherpa-onnx wants it as a tokens.txt beside the model. Generated here
                    // rather than asking the operator to download a file that does not
                    // exist -- tools/piper_tokens.py does the same on a workstation, and
                    // this reproduces its output.
                    if (item.install.endsWith("config.json")) {
                        writeVoiceTokens(File(root, item.install))
                    }
                }
            }

            Result(
                files = files,
                bytes = bytes,
                problem =
                    if (files == 0) {
                        "Nothing installed. " + unknown + " file(s) were the right size " +
                            "but the wrong content -- an interrupted download would do that. " +
                            "Try downloading them again."
                    } else {
                        null
                    },
            )
        }

    /** Depth-limited: a picked folder may be all of Download, and this must terminate. */
    private fun collect(
        tree: Uri,
        parentId: String,
        into: MutableList<Entry>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || into.size >= MAX_FILES) return
        for (entry in listing(tree, parentId)) {
            if (entry.isDirectory) {
                collect(tree, entry.id, into, depth + 1)
            } else {
                into += entry
            }
        }
    }

    /**
     * Turns a Piper config into the token table sherpa-onnx loads.
     *
     * The map's own order, not sorted by id: sorting looks tidier and produces a file that
     * differs from sherpa's own on line one. `tools/piper_tokens.py` carries the check that
     * proved which is right.
     */
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

    private fun copy(
        source: Uri,
        target: File,
        onProgress: (String) -> Unit,
    ): Long {
        target.parentFile?.mkdirs()
        onProgress("installing " + target.name)
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                // .partial then rename: a copy interrupted by a flat battery must leave no
                // half a model behind, because a truncated .onnx loads and decodes silence.
                val staged = File(target.parentFile, target.name + ".partial")
                val count = staged.outputStream().use { input.copyTo(it) }
                staged.renameTo(target)
                count
            } ?: 0L
        }.getOrDefault(0L)
    }

    private data class Entry(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val bytes: Long,
    )

    private fun listing(
        tree: Uri,
        parentId: String,
    ): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Entry>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                out +=
                    Entry(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        bytes = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                    )
            }
        }
        return out
    }

    private companion object {
        /** A picked folder may be all of Download; this keeps the walk finite. */
        const val MAX_DEPTH = 4
        const val MAX_FILES = 400
    }
}

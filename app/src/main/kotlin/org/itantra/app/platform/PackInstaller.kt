package org.itantra.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * The `models` folder itself — the one holding `asr/` and `tts/`, exactly as
 * `tools/fetch_models.py --into models` leaves it. Anything else is refused with a sentence
 * naming what was looked for, because a silent no-op here is indistinguishable from a
 * successful copy of nothing.
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
     * @param onProgress called with the file being written, so a copy of two gigabytes is
     *   visibly working rather than apparently hung
     */
    suspend fun install(
        tree: Uri,
        onProgress: (String) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            val root = File(context.getExternalFilesDir(null), "models")
            val children =
                runCatching { listing(tree, DocumentsContract.getTreeDocumentId(tree)) }
                    .getOrNull()
                    ?: return@withContext Result(0, 0, "That folder could not be read.")

            val names = children.map { it.name }
            if (EXPECTED.none { it in names }) {
                return@withContext Result(
                    0,
                    0,
                    "Pick the models folder — the one containing " +
                        EXPECTED.joinToString(" and ") + ". Found: " +
                        names.take(4).joinToString(", ").ifEmpty { "nothing" },
                )
            }

            var files = 0
            var bytes = 0L
            for (child in children) {
                if (child.name !in EXPECTED) continue
                val copied = copyInto(tree, child, File(root, child.name), onProgress)
                files += copied.first
                bytes += copied.second
            }
            Result(files, bytes)
        }

    private data class Entry(val id: String, val name: String, val isDirectory: Boolean)

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
                    )
            }
        }
        return out
    }

    /** @return files written and bytes written. */
    private fun copyInto(
        tree: Uri,
        entry: Entry,
        target: File,
        onProgress: (String) -> Unit,
    ): Pair<Int, Long> {
        if (!entry.isDirectory) {
            val source = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
            target.parentFile?.mkdirs()
            onProgress(entry.name)
            val written =
                runCatching {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        // .partial then rename: a copy interrupted by a flat battery must
                        // leave no half a model behind, because a truncated .onnx loads and
                        // then decodes silence. Same rule as tools/fetch_models.py.
                        val staged = File(target.parentFile, target.name + ".partial")
                        val count = staged.outputStream().use { input.copyTo(it) }
                        staged.renameTo(target)
                        count
                    } ?: 0L
                }.getOrDefault(0L)
            return (if (written > 0) 1 else 0) to written
        }

        var files = 0
        var bytes = 0L
        for (child in listing(tree, entry.id)) {
            val copied = copyInto(tree, child, File(target, child.name), onProgress)
            files += copied.first
            bytes += copied.second
        }
        return files to bytes
    }

    private companion object {
        /** What a `models` folder holds. Either alone is enough; a pack may be speech-only. */
        val EXPECTED = listOf("asr", "tts")
    }
}

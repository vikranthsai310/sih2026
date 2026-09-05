package org.itantra.app.platform

import android.content.Context
import java.io.File

/**
 * Where a language pack lives on the handset, and whether it is there.
 *
 * ## Why the models are not in the installer
 *
 * One IndicConformer model is about 189 MB and there are ten languages. Constraint **N2**
 * caps the installer at 30 MB and the Efficiency criterion is 20 % of the mark, so bundling
 * even one is out of the question. Constraint **C2** settles where they come from instead:
 * *"Language packs may be fetched once during setup; the running system never touches a
 * network."* `tools/fetch_models.py` is that setup step, and nothing in the running
 * application ever reaches for a network — there is no `INTERNET` permission to reach with.
 *
 * ## The layout, which is the fetcher's layout
 *
 * ```
 *   <external files>/models/asr/tokens.txt          shared, 5 633 tokens, nine scripts
 *   <external files>/models/asr/hi/model.int8.onnx  one per language
 * ```
 *
 * Deliberately identical to what `fetch_models.py --into models` produces, so a pack is
 * copied to a handset rather than transformed on the way:
 *
 * ```
 *   adb push models/asr /sdcard/Android/data/org.itantra/files/models/asr
 * ```
 *
 * `getExternalFilesDir` rather than `filesDir` for exactly that reason — it is app-private,
 * removed when the application is uninstalled, needs no permission, and `adb push` can
 * reach it without root. A pack the operator cannot get onto the device is not a pack.
 */
class ModelStore(context: Context) {
    private val root = File(context.getExternalFilesDir(null), "models/asr")

    /** The token table, shared by every language — see `docs/MODELS.md` section 0. */
    val tokens: File get() = File(root, TOKENS)

    fun modelFor(languageCode: String): File = File(File(root, languageCode), MODEL)

    /**
     * Whether this language can be recognised right now.
     *
     * Size is checked as well as existence because a half-copied model is the failure that
     * looks like a recogniser bug: sherpa-onnx loads a truncated graph, decodes, and returns
     * an empty string forever. `adb push` interrupted halfway leaves exactly that.
     */
    fun hasPack(languageCode: String): Boolean {
        val model = modelFor(languageCode)
        return tokens.isFile() &&
            tokens.length() > MIN_TOKENS_BYTES &&
            model.isFile() &&
            model.length() > MIN_MODEL_BYTES
    }

    /** Every language with a usable pack, for the language screen. */
    fun installed(codes: Iterable<String>): Set<String> = codes.filterTo(HashSet()) { hasPack(it) }

    /** Where a pack should be put, said in full, for a message an operator can act on. */
    fun expectedPath(languageCode: String): String = modelFor(languageCode).absolutePath

    private companion object {
        const val TOKENS = "tokens.txt"
        const val MODEL = "model.int8.onnx"

        /** The real table is 67 605 B; anything near zero is a failed copy. */
        const val MIN_TOKENS_BYTES = 1_024L

        /** The real models are 167–198 MB. A hundred megabytes is a generous floor. */
        const val MIN_MODEL_BYTES = 100L * 1024 * 1024
    }
}

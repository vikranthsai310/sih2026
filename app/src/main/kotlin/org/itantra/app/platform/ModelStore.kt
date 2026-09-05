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
 *   <external files>/models/asr/en/tokens.txt       English brings its own, Latin
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

    /**
     * The token table for a language, which is **not** always the shared one.
     *
     * Eight of the nine published models share a 67 605-byte table covering nine Indic
     * scripts. English does not: it has its own 11 433-byte Latin table at `en/tokens.txt`,
     * and the manifest says so. Handing the English model the Indic table would not fail
     * loudly — it would load, decode, and return nonsense, and the conclusion drawn would
     * have been "the English model is broken" rather than "the application gave it the
     * wrong tokens".
     *
     * Resolved by looking rather than by a rule, so a language that later ships its own
     * table needs no code change.
     */
    fun tokensFor(languageCode: String): File {
        val own = File(File(root, languageCode), TOKENS)
        return if (own.isFile() && own.length() > MIN_TOKENS_BYTES) own else File(root, TOKENS)
    }

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
        val tokens = tokensFor(languageCode)
        return tokens.isFile() &&
            tokens.length() > MIN_TOKENS_BYTES &&
            model.isFile() &&
            model.length() > MIN_MODEL_BYTES
    }

    // ── the voice, for speaking a message that arrives ───────────────────────

    private val voices = File(context.getExternalFilesDir(null), "models/tts")

    /**
     * espeak-ng's data, shared by every voice.
     *
     * One copy rather than one per language: it is 18 MB of dictionaries covering every
     * language espeak knows, and six voices carrying their own would be a hundred megabytes
     * of the same files. This is also the GPL-3.0 component `LICENSES.md` section 6 is
     * about — it is data rather than code, and the code is already linked into
     * `libsherpa-onnx-jni.so`.
     */
    val espeakData: File get() = File(voices, ESPEAK)

    fun voiceFor(languageCode: String): File = File(File(voices, languageCode), VOICE)

    fun voiceTokensFor(languageCode: String): File = File(File(voices, languageCode), TOKENS)

    /**
     * Whether a message arriving in this language can be spoken aloud.
     *
     * Four of the ten have no permissively licensed voice — Tamil, Gujarati, Kannada and
     * Odia — so this is false for them by design rather than by omission, and
     * `LICENSES.md` section 6 records the decision to ship those recognise-only rather than
     * take a non-commercial model.
     */
    fun hasVoice(languageCode: String): Boolean =
        espeakData.isDirectory() &&
            voiceFor(languageCode).let { it.isFile() && it.length() > MIN_VOICE_BYTES } &&
            voiceTokensFor(languageCode).isFile()

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

        const val ESPEAK = "espeak-ng-data"
        const val VOICE = "model.onnx"

        /** A Piper medium voice is ~63 MB; ten is a floor that only catches a failed copy. */
        const val MIN_VOICE_BYTES = 10L * 1024 * 1024
    }
}

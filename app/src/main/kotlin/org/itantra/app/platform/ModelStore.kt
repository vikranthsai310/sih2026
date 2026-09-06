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
 * application ever reaches for a network: there is no HTTP client in `src/main` to reach
 * with, and nothing resolves a hostname or opens an outbound network connection.
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

    /** Answers to [isLoadableVoice], which the language screen asks ten times over. */
    private val loadable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Expands the bundled espeak data if it is not on disk yet.
     *
     * Called before a voice loads. It is the one part of synthesis that ships inside the
     * installer, because it is the only part that arrives as an archive rather than a
     * single downloadable file -- see [EspeakData].
     */
    fun ensureEspeak(context: Context): File? = EspeakData(context).ensure()

    /*
     * This check used to begin `espeakData.isDirectory() &&`, which was circular and made
     * synthesis impossible on every correctly provisioned handset. espeak's data is
     * expanded by [ensureEspeak], which runs inside `Speaker.load` -- and `load` is gated
     * by [hasVoice]. So the data was never expanded because the check was false, and the
     * check was false because the data was never expanded. `TTS —` in band F was that
     * deadlock, showing as a missing measurement rather than as an error.
     *
     * It is also the wrong question. espeak's data ships inside the installer and is a
     * property of the application, not of a language pack; what makes a language speakable
     * is whether its **voice** is present. The prerequisite is now expanded at startup,
     * beside the other bundled artefacts, so it is on disk before anything asks.
     */

    fun voiceFor(languageCode: String): File = File(File(voices, languageCode), VOICE)

    fun voiceTokensFor(languageCode: String): File = File(File(voices, languageCode), TOKENS)

    /**
     * Whether a message arriving in this language can be spoken aloud.
     *
     * Three of the ten have no permissively licensed voice — Tamil, Kannada and Odia — so
     * this is false for them by design rather than by omission, and `LICENSES.md` section 6
     * records the decision to ship those recognise-only rather than take a non-commercial
     * model. Gujarati left that set on 2026-09-06; its voice is Mimic 3, not Piper.
     */
    fun hasVoice(languageCode: String): Boolean =
        voiceFor(languageCode).let {
            it.isFile() && it.length() > MIN_VOICE_BYTES && isLoadableVoice(it)
        } && voiceTokensFor(languageCode).isFile()

    /**
     * Whether sherpa-onnx can actually load this file, asked before it is handed over.
     *
     * A Piper voice downloaded from `rhasspy/piper-voices` is a valid ONNX model and a
     * valid Piper voice, and sherpa-onnx cannot use it: sherpa needs its **own** re-export,
     * which adds `sample_rate` and friends to the ONNX metadata. Given one without,
     * `OfflineTtsVitsModel::Init` logs
     *
     * ```
     * 'sample_rate' does not exist in the metadata
     * ```
     *
     * and calls `exit(-1)`. That is not an exception and not a signal: `runCatching` cannot
     * catch it, no tombstone is written, and the process is simply gone. On a handset it
     * looks like the phone switching itself off, and because the launcher restarts a
     * foreground task, it looks like it doing so over and over.
     *
     * So the file is inspected first. The metadata sits in the last few hundred bytes of
     * the model — checked, not assumed: 63 145 034 of 63 145 178 for Hindi — so a tail read
     * settles it without touching sixty megabytes. A voice that fails this is reported as
     * absent, which the language screen already knows how to say, instead of ending the
     * process.
     */
    private fun isLoadableVoice(model: File): Boolean {
        val key = model.path + ':' + model.length()
        loadable[key]?.let { return it }
        val verdict =
            runCatching {
                model.inputStream().use { stream ->
                    val skip = (model.length() - TAIL_BYTES).coerceAtLeast(0L)
                    stream.skip(skip)
                    val tail = stream.readBytes()
                    SAMPLE_RATE.toByteArray(Charsets.US_ASCII).let { needle ->
                        (0..tail.size - needle.size).any { i ->
                            needle.indices.all { j -> tail[i + j] == needle[j] }
                        }
                    }
                }
            }.getOrDefault(false)
        loadable[key] = verdict
        return verdict
    }

    /** One installed artefact, for the storage screen. */
    data class Installed(
        val languageCode: String,
        val kind: String,
        val bytes: Long,
    )

    /**
     * What is on this handset, read from disk rather than from a list of what should be.
     *
     * The storage screen previously returned an empty list unconditionally, so a handset
     * with 2.2 GB of packs reported "0.0 MB used by language packs". A screen whose job is
     * to say what is taking up space must not be the one place that does not look.
     */
    fun installedPacks(): List<Installed> {
        val out = ArrayList<Installed>()
        File(root.path).listFiles()?.sortedBy { it.name }?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val model = File(dir, MODEL)
            if (model.isFile()) out += Installed(dir.name, "recogniser", model.length())
        }
        File(voices.path).listFiles()?.sortedBy { it.name }?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name == ESPEAK) {
                out += Installed("all", "espeak data", dir.walkBottomUp().sumOf { if (it.isFile) it.length() else 0 })
                return@forEach
            }
            val voice = File(dir, VOICE)
            if (voice.isFile()) out += Installed(dir.name, "voice", voice.length())
        }
        return out
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

        const val ESPEAK = "espeak-ng-data"
        const val VOICE = "model.onnx"

        /** The metadata key sherpa-onnx requires and raw Piper voices do not carry. */
        const val SAMPLE_RATE = "sample_rate"

        /** Comfortably more than the few hundred bytes the metadata actually occupies. */
        const val TAIL_BYTES = 64L * 1024

        /** A Piper medium voice is ~63 MB; ten is a floor that only catches a failed copy. */
        const val MIN_VOICE_BYTES = 10L * 1024 * 1024
    }
}

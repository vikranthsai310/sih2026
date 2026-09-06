package org.itantra.app.platform

import android.util.Log
import org.itantra.proto.Language
import org.itantra.tts.NormaliseSpec
import org.itantra.tts.SherpaSynthesiser
import org.itantra.tts.SpeechShaper
import org.itantra.tts.TextNormaliser
import org.itantra.tts.VoiceProfile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The other half of the loop: text that arrives, spoken aloud.
 *
 * ISRO's words — *"The systems TTS module when activated after receiving the Text data
 * should convert it into intelligible speech which will be played as a voice note"*. Until
 * this existed the application recognised speech, compressed it, transmitted it, verified
 * it and rendered it in the receiver's language — and then showed it as text, which is the
 * one thing the problem statement is explicitly not asking for: *"Transmitting Audio
 * information is critical instead of written message as it will be more inclusive and will
 * cater to everyone even if they are literate or not."*
 *
 * ## The pipeline, `docs/TTS.md`: normalise → shape → synthesise → play
 *
 * Three of the four stages are ordinary software rather than the model, and they are most
 * of the difference between a voice that sounds like a person and one that sounds like a
 * machine reading a list:
 *
 * 1. **Normalise.** `112` becomes three spoken digits, `5 km` becomes "पाँच किलोमीटर", `14:30`
 *    becomes a time — from the per-language rule file the repository has carried since
 *    week 7, which was never bundled and never called.
 * 2. **Shape.** The message is cut into the phrases a person would say it in, at sentence
 *    and clause marks rather than every twelve words, given the language's full stop, and
 *    the right silence between phrases.
 * 3. **Synthesise** with the voice's own tuning from its `config.json` rather than the
 *    library's defaults, a touch slower than read speech, and level and de-click each phrase.
 * 4. **Play** on one track for the whole message, released only after the last sample.
 *
 * ## Spoken in the receiver's language, not the sender's
 *
 * A template arrives as one byte and is rendered into whatever this unit is set to, so the
 * voice loaded here is **this** unit's language. A Hindi operator hears Hindi whatever the
 * sender spoke, which is the cross-language claim finishing in sound rather than in text.
 *
 * ## Seven of ten
 *
 * There is no permissively licensed voice for Tamil, Kannada or Odia, in any family
 * sherpa-onnx packages. `LICENSES.md` section 6 chose to ship those recognise-only rather
 * than take Meta MMS under a non-commercial licence. Those three transmit and display;
 * they do not speak, and [canSpeak] says so rather than failing at the moment of an alert.
 *
 * Gujarati was in that set until 2026-09-06 and is not any more: Piper has no Gujarati,
 * but sherpa-onnx publishes a Mimic 3 VITS voice trained on CMU Indic under a licence that
 * grants use "for any purpose ... without fee". It is not a Piper voice and does not carry
 * a Piper `config.json`, so its `tokens.txt` is installed as it comes and it runs on the
 * default profile.
 */
class Speaker(
    private val store: ModelStore,
    /** Needed to expand the bundled espeak data on first use, and to read the rule files. */
    private val context: android.content.Context,
) {
    /** Synthesis is far too slow for the main thread, and must not queue behind decoding. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "piper").apply { isDaemon = true } }

    private var voice: SherpaSynthesiser? = null
    private var loadedFor: String? = null

    /** Rule files, read once each. Null where a language ships none. */
    private val normalisers = HashMap<String, TextNormaliser?>()

    /** Set while speaking, so a new message can cut off an old one — task W5.7, barge-in. */
    private val speaking = AtomicBoolean(false)

    fun canSpeak(languageCode: String): Boolean = store.hasVoice(languageCode)

    fun isLoaded(languageCode: String): Boolean = loadedFor == languageCode

    fun preload(languageCode: String) {
        if (!canSpeak(languageCode) || loadedFor == languageCode) return
        worker.execute { load(languageCode) }
    }

    private fun load(languageCode: String): SherpaSynthesiser? {
        if (loadedFor == languageCode) return voice
        voice?.let { runCatching { it.close() } }
        voice = null
        loadedFor = null
        if (!canSpeak(languageCode)) return null

        // The data is bundled and expanded once; a voice cannot phonemise without it.
        // Normally already done at startup; repeated here because a voice must never be
        // built against a directory that is not there.
        store.ensureEspeak(context)

        // Bracketed deliberately. Everything below this line runs in C, and C can end the
        // process without an exception, a signal or a tombstone -- espeak calls exit() on
        // bad data. `runCatching` cannot see that, so the only evidence such a death
        // leaves is an "opening" line with no "opened" line after it.
        Log.i(TAG, "opening voice $languageCode from ${store.voiceFor(languageCode).name}")
        val built =
            runCatching {
                SherpaSynthesiser(
                    modelPath = store.voiceFor(languageCode).absolutePath,
                    tokensPath = store.voiceTokensFor(languageCode).absolutePath,
                    dataDir = store.espeakData.absolutePath,
                    profile = profileFor(languageCode),
                )
            }.onFailure { Log.w(TAG, "voice $languageCode did not open", it) }
                .getOrNull() ?: return null
        Log.i(TAG, "opened voice $languageCode")

        voice = built
        loadedFor = languageCode
        return built
    }

    /**
     * The voice's own tuning, from the Piper config beside it, paced for a loudspeaker.
     *
     * A voice without a config -- Gujarati -- runs on the defaults, which are what its
     * publisher's own examples use.
     */
    private fun profileFor(languageCode: String): VoiceProfile {
        val config = store.voiceConfigFor(languageCode)
        val own =
            if (config.isFile()) {
                VoiceProfile.fromPiperConfig(runCatching { config.readText() }.getOrDefault(""))
            } else {
                VoiceProfile()
            }
        return own.paced(VoiceProfile.DEFAULT_PACE)
    }

    /**
     * The normaliser for a language, from the bundled rule file. Read once.
     *
     * Null rather than a no-op when the file is missing or refuses to parse, so the
     * absence is a fact this class can log rather than a silent difference in how `112`
     * is read on two handsets.
     */
    private fun normaliserFor(languageCode: String): TextNormaliser? =
        normalisers.getOrPut(languageCode) {
            runCatching {
                val text =
                    context.assets.open(
                        "rules/normalise.$languageCode.json",
                    ).bufferedReader().use { it.readText() }
                NormaliseSpec.parse(text).normaliser()
            }.onFailure { Log.w(TAG, "no normalisation rules for $languageCode", it) }
                .getOrNull()
        }

    /**
     * The shaper for a language: the full stop its voice was trained on.
     *
     * Devanagari, Bengali and Odia end a sentence with the danda, and the voices for those
     * languages saw dandas in training. The rest end with a full stop.
     */
    private fun shaperFor(languageCode: String): SpeechShaper {
        val language = Language.entries.firstOrNull { it.code == languageCode }
        val fullStop = if (language?.blockBase in DANDA_SCRIPTS) "।" else "."
        return SpeechShaper(fullStop = fullStop)
    }

    /** What the voice will actually be given for [text], for a caller that wants to show it. */
    fun prepared(
        languageCode: String,
        text: String,
    ): String {
        val normalised = normaliserFor(languageCode)?.normalise(text) ?: text
        return shaperFor(languageCode).shape(normalised).joinToString(" ") { it.text }
    }

    /**
     * Speaks [text], interrupting anything already being spoken.
     *
     * @param onFirstAudio called when the first phrase reaches the audio device. This is the
     *   latency the problem statement asks for — *"the time delay between the text received
     *   and audio processed and played"* — and it is the first **sound**, not the end of
     *   synthesis, because that is the moment a listener hears something.
     */
    fun speak(
        languageCode: String,
        text: String,
        onFirstAudio: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        if (text.isBlank() || !canSpeak(languageCode)) {
            onFinished()
            return
        }
        // A newer message stops an older one at the next phrase boundary. On a radio net
        // the most recent thing said is the one that matters, and two sentences over each
        // other are neither of them.
        speaking.set(false)
        worker.execute {
            val engine = load(languageCode)
            if (engine == null) {
                onFinished()
                return@execute
            }
            speaking.set(true)
            try {
                val normalised = normaliserFor(languageCode)?.normalise(text) ?: text
                engine.speakSentence(
                    text = normalised,
                    shaper = shaperFor(languageCode),
                    onFirstAudio = onFirstAudio,
                    shouldContinue = { speaking.get() },
                )
            } catch (e: RuntimeException) {
                // A voice that fails mid-sentence must not take the net down with it.
                Log.w(TAG, "voice $languageCode failed mid-sentence", e)
            } finally {
                speaking.set(false)
                onFinished()
            }
        }
    }

    /** Stops at the next phrase boundary, which is what barge-in and a new alert both need. */
    fun stop() = speaking.set(false)

    fun close() {
        speaking.set(false)
        worker.execute {
            voice?.let { runCatching { it.close() } }
            voice = null
            loadedFor = null
        }
        worker.shutdown()
    }

    /** For the language screen, which says what each language can and cannot do. */
    fun spokenLanguages(): Set<String> =
        Language.entries.filterTo(HashSet()) { canSpeak(it.code) }.mapTo(HashSet()) { it.code }

    private companion object {
        const val TAG = "itantra-tts"

        /** Devanagari, Bengali, Odia: the scripts whose sentences end in a danda. */
        val DANDA_SCRIPTS = setOf(0x0900, 0x0980, 0x0B00)
    }
}

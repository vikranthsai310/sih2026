package org.itantra.app.platform

import android.util.Log
import org.itantra.proto.Language
import org.itantra.tts.SherpaSynthesiser
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
 * [org.itantra.tts.SherpaSynthesiser] was written in week 1 and had no caller, like
 * everything else on this side of the loop.
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
 * a Piper `config.json`, so its `tokens.txt` is installed as it comes.
 */
class Speaker(
    private val store: ModelStore,
    /** Needed only to expand the bundled espeak data on first use. */
    private val context: android.content.Context,
) {
    /** Synthesis is far too slow for the main thread, and must not queue behind decoding. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "piper").apply { isDaemon = true } }

    private var voice: SherpaSynthesiser? = null
    private var loadedFor: String? = null

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
                )
            }.onFailure { Log.w(TAG, "voice $languageCode did not open", it) }
                .getOrNull() ?: return null
        Log.i(TAG, "opened voice $languageCode")

        voice = built
        loadedFor = languageCode
        return built
    }

    /**
     * Speaks [text], interrupting anything already being spoken.
     *
     * @param onFirstAudio called when the first chunk reaches the audio device. This is the
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
        // A newer message stops an older one at the next chunk boundary. On a radio net the
        // most recent thing said is the one that matters, and two sentences over each other
        // are neither of them.
        speaking.set(false)
        worker.execute {
            val engine = load(languageCode)
            if (engine == null) {
                onFinished()
                return@execute
            }
            speaking.set(true)
            try {
                engine.speakSentence(
                    text = text,
                    onFirstAudio = onFirstAudio,
                    shouldContinue = { speaking.get() },
                )
            } catch (e: RuntimeException) {
                // A voice that fails mid-sentence must not take the net down with it.
            } finally {
                speaking.set(false)
                onFinished()
            }
        }
    }

    /** Stops at the next chunk boundary, which is what barge-in and a new alert both need. */
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
    }
}

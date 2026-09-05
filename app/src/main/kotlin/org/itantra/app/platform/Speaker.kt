package org.itantra.app.platform

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
 * ## Six of ten
 *
 * There is no permissively licensed voice for Tamil, Gujarati, Kannada or Odia.
 * `LICENSES.md` section 6 chose to ship those recognise-only rather than take Meta MMS
 * under a non-commercial licence. Those languages transmit and display; they do not speak,
 * and [canSpeak] says so rather than failing at the moment of an alert.
 */
class Speaker(private val store: ModelStore) {
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

        val built =
            runCatching {
                SherpaSynthesiser(
                    modelPath = store.voiceFor(languageCode).absolutePath,
                    tokensPath = store.voiceTokensFor(languageCode).absolutePath,
                    dataDir = store.espeakData.absolutePath,
                )
            }.getOrNull() ?: return null

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
}

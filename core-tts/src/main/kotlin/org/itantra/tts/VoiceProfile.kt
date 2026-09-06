package org.itantra.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a VITS voice is driven: the three inference knobs, and which speaker.
 *
 * ## Why these are not left at the library's defaults
 *
 * A VITS voice has a stochastic duration predictor and a flow decoder, and both are fed
 * noise at inference. `noiseScale` sets how much the flow varies from its mean — too low and
 * every sentence has the same flat pitch contour, which is most of what "sounds like a
 * robot" means; too high and it wobbles. `noiseScaleW` does the same for phoneme durations,
 * which is where natural rhythm comes from. `lengthScale` is the overall tempo.
 *
 * Piper ships the values each voice was tuned with in its `.onnx.json`, under `inference`,
 * and they differ: the English voice wants `noise_scale 0.333, length_scale 0.8` and the
 * Indic voices `0.667, 1.0`. Until this existed every voice ran on sherpa-onnx's defaults,
 * which are the Indic ones — so the English voice ran at the wrong tempo with too much
 * variation, and the rest merely happened to match.
 *
 * ## The tempo adjustment
 *
 * [pace] multiplies the voice's own `length_scale`. It is applied by the caller per
 * language rather than baked into the file, because a voice trained on read audiobook
 * speech is tuned for a listener with the text in front of them, and this one is heard
 * over a phone speaker in the open air by somebody who cannot ask for a repeat. A little
 * slower is a little clearer; much slower is a drawl. The default is a nudge.
 */
data class VoiceProfile(
    val noiseScale: Float = DEFAULT_NOISE_SCALE,
    val noiseScaleW: Float = DEFAULT_NOISE_SCALE_W,
    val lengthScale: Float = DEFAULT_LENGTH_SCALE,
    val speakerId: Int = 0,
) {
    /** The same voice at a different tempo. Above one is slower. */
    fun paced(pace: Float): VoiceProfile = copy(lengthScale = lengthScale * pace)

    companion object {
        /** sherpa-onnx's own defaults, which are also Piper's for a typical voice. */
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_SCALE_W = 0.8f
        const val DEFAULT_LENGTH_SCALE = 1.0f

        /**
         * A touch slower than the voice's own tempo, for every voice.
         *
         * Five per cent is inside the range a listener hears as "unhurried" rather than
         * "slow", and it is what a radio operator does with their own voice anyway.
         */
        const val DEFAULT_PACE = 1.05f

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * From a Piper `.onnx.json`. Missing values fall back to the defaults one at a
         * time, so a config carrying only a sample rate still yields a usable profile.
         */
        fun fromPiperConfig(configJson: String): VoiceProfile =
            runCatching {
                val root = json.parseToJsonElement(configJson).jsonObject
                val inference = root["inference"]?.jsonObject

                fun float(
                    key: String,
                    default: Float,
                ) = inference?.get(key)?.jsonPrimitive?.floatOrNull ?: default
                VoiceProfile(
                    noiseScale = float("noise_scale", DEFAULT_NOISE_SCALE),
                    noiseScaleW = float("noise_w", DEFAULT_NOISE_SCALE_W),
                    lengthScale = float("length_scale", DEFAULT_LENGTH_SCALE),
                    speakerId = 0,
                ).also { profile ->
                    // A profile with nonsense in it is worse than the default one.
                    require(profile.noiseScale in 0f..2f && profile.noiseScaleW in 0f..2f)
                    require(profile.lengthScale in 0.3f..3f)
                    root["num_speakers"]?.jsonPrimitive?.intOrNull?.let { require(it >= 1) }
                }
            }.getOrDefault(VoiceProfile())
    }
}

package org.itantra.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Captures the volume keys with the screen off. The other half of task **W5.4**.
 *
 * ## Why a `MediaSession` and not a key listener
 *
 * An `Activity` key override sees nothing once the screen is off or another app is in
 * front, which is exactly Meena's situation — gloved, at altitude, screen dark. The
 * platform routes volume keys to the *active media session* when that session declares
 * **remote** playback with a [VolumeProvider], and that is the only documented way to
 * receive them without an accessibility service.
 *
 * Note that media-button events are **not** the mechanism: `onMediaButtonEvent` delivers
 * `KEYCODE_MEDIA_*` and headset hook, never the volume keys. Reaching for it is the
 * obvious wrong turn here.
 *
 * ## What this can and cannot give you
 *
 * A [VolumeProvider] receives *adjustments*, not key down and key up. The platform calls
 * [VolumeProvider.onAdjustVolume] once per press and then repeatedly while the key is
 * held — but there is no event when the key is released.
 *
 * So a hold is **inferred**: the first adjustment opens the floor, each further
 * adjustment keeps it open, and [releaseAfterIdleMillis] of silence closes it. The
 * consequence is honest and worth stating plainly:
 *
 * - The floor is released up to [releaseAfterIdleMillis] after the operator lets go.
 *   That is a tail of dead air on the channel, not a lost word — the endpointer has
 *   already finished the utterance by then.
 * - A single tap transmits a very short utterance rather than nothing.
 *
 * The alternative — an accessibility service — sees true key up and down, but asks the
 * operator to grant a permission that reads as "this app can watch everything you do",
 * which is a poor trade for a few hundred milliseconds.
 *
 * ## The session must be active
 *
 * Volume routing only reaches a session that is active and playing. [start] therefore
 * publishes a `STATE_PLAYING` state; this is a radio holding the volume keys, not a
 * media player, but the platform has no other category for it.
 */
class VolumeKeyCapture(
    context: Context,
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val releaseAfterIdleMillis: Long = RELEASE_AFTER_IDLE_MILLIS,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var held = false

    val isHeld: Boolean get() = held

    private val releaseRunnable =
        Runnable {
            if (held) {
                held = false
                onRelease()
            }
        }

    private val volumeProvider =
        object : VolumeProvider(VOLUME_CONTROL_RELATIVE, MAX_VOLUME, CURRENT_VOLUME) {
            override fun onAdjustVolume(direction: Int) {
                // Only volume-down transmits. Volume-up is left alone so the operator can
                // still make an incoming message louder.
                if (direction >= 0) return
                onVolumeDown()
            }

            override fun onSetVolumeTo(volume: Int) {
                // A slider drag, not a key press. Ignored: this provider exists to
                // receive keys, not to be a volume control.
            }
        }

    private val session =
        MediaSession(context, SESSION_TAG).apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
            setPlaybackToRemote(volumeProvider)
        }

    /** Begins capturing. The session must stay active for the routing to hold. */
    fun start() {
        session.isActive = true
    }

    /**
     * Stops capturing and releases the floor if it was held.
     *
     * Not releasing here would leave the channel busy until the 12 s stale-hold expiry
     * on every other unit.
     */
    fun stop() {
        handler.removeCallbacks(releaseRunnable)
        if (held) {
            held = false
            onRelease()
        }
        session.isActive = false
        session.release()
    }

    private fun onVolumeDown() {
        if (!held) {
            held = true
            onPress()
        }
        // Each repeat pushes the release further out; silence is what ends the hold.
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, releaseAfterIdleMillis)
    }

    companion object {
        private const val SESSION_TAG = "itantra-ptt"

        /**
         * Longer than the platform's key-repeat interval, short enough that the dead air
         * after a release is not noticeable against an 800 ms end-to-end budget.
         */
        const val RELEASE_AFTER_IDLE_MILLIS = 400L

        /** Arbitrary: the provider exists to receive keys, not to represent a volume. */
        private const val MAX_VOLUME = 10
        private const val CURRENT_VOLUME = 5

        /** Attributes a session needs to be considered for volume routing. */
        val ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}

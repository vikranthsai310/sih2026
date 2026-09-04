package org.itantra.app.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.itantra.audio.AlertPlayback

/**
 * The Android side of [AlertPlayback.AudioSystem]. Tasks **W5.9**–**W5.14**.
 *
 * The policy — the ordering, the restoration, ignoring a refused focus request — lives in
 * [AlertPlayback] and is unit-tested there. This class is deliberately mechanical: each
 * method is one platform call, so there is nothing here that can be wrong without being
 * obviously wrong.
 *
 * @param fullScreenIntent launched when an alert arrives, so a locked handset shows the
 *   alert rather than a notification shade entry
 */
class AndroidAlertAudio(
    private val context: Context,
    private val sampleRate: Int = 22_050,
    private val fullScreenIntent: PendingIntent? = null,
) : AlertPlayback.AudioSystem {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    /** `USAGE_ALARM` is what lets this sound through Do Not Disturb and a silenced ringer. */
    private val alarmAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    override fun currentAlarmVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    override fun maxAlarmVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    override fun setAlarmVolume(volume: Int) {
        // Flag 0: change the volume without showing the system slider, which would
        // obscure the alert the operator needs to read.
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
    }

    override fun requestExclusiveFocus(): Boolean {
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttributes)
                // Deliberately no listener. Task W5.12: there is no state in which this
                // alert stops early because something else wanted the audio.
                .setWillPauseWhenDucked(false)
                .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // A timeout, so a bug here cannot flatten the battery: the eight-hour
                // standby figure is a requirement, and a stuck wake lock destroys it.
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
    }

    override fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun wakeScreenWithFullScreenIntent() {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Emergency alerts from other units"
                setBypassDnd(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )

        val builder =
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("ALERT")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
        // A full-screen intent is what turns the screen on and shows the alert on a
        // locked device, rather than adding a line to the shade nobody will see.
        fullScreenIntent?.let { builder.setFullScreenIntent(it, true) }

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    @Suppress("DEPRECATION")
    override fun vibrate(patternMillis: LongArray) {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(patternMillis, -1))
        }
    }

    override fun playOnAlarmStream(pcm: ShortArray) {
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(alarmAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(pcm.size * 2, MIN_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        try {
            track.play()
            // Blocking, so announce() knows when the alert has actually been heard
            // rather than merely queued.
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            runCatching { track.stop() }
        } finally {
            track.release()
        }
    }

    /** Clears the alert notification once the operator has acknowledged it. */
    fun dismissNotification() {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "itantra.alerts"
        const val NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TAG = "itantra:alert"

        /** Far longer than any alert, short enough that a leak cannot flatten a battery. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
        private const val MIN_BUFFER_BYTES = 8 * 1024

        /** Builds the intent that shows the alert screen over a locked handset. */
        fun fullScreenIntentTo(
            context: Context,
            activity: Class<*>,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, activity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

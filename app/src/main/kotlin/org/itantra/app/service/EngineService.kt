package org.itantra.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.itantra.audio.EngineState
import org.itantra.audio.EngineTransitions

/**
 * The foreground service that owns the engine, so the device keeps listening with the
 * screen off. Task **W1.13**.
 *
 * ## Why a foreground service and not an activity
 *
 * Meena's handset is in a chest pocket, screen dark, gloves on
 * (`docs/REQUIREMENTS.md` section 6.3). An activity is killed or frozen the moment the
 * screen goes off, and a background service without a notification is stopped within
 * minutes by every Android version since Oreo. A radio that stops listening when nobody
 * is looking at it is not a radio, so the persistent notification is not a formality —
 * it is what buys the eight-hour standby figure in requirement N3.
 *
 * The notification channel is deliberately **silent and low-importance**: this service
 * runs for hours and a radio that pings every time it changes state would be turned off
 * within the first hour of a deployment.
 */
class EngineService : LifecycleService() {
    private val _state = MutableStateFlow<EngineState>(EngineState.Initialising)

    /** Observed by the interface; the single source of truth for what the device is doing. */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: EngineService get() = this@EngineService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()

        // Keep the notification honest: it always shows the state the engine is in,
        // including DEGRADED, which must never be a silent condition.
        lifecycleScope.launch {
            state.collect { notificationManager().notify(NOTIFICATION_ID, buildNotification(it)) }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: if the platform kills us under memory pressure, come back. A
        // unit that quietly stops receiving is the failure mode this project cannot have.
        return START_STICKY
    }

    /**
     * Moves the engine to [next], rejecting a transition the state machine forbids.
     *
     * Illegal transitions are dropped rather than applied, because a state machine that
     * accepts anything is not one — see `docs/ARCHITECTURE.md` section 4.
     *
     * @return true if the transition was legal and applied.
     */
    fun moveTo(next: EngineState): Boolean {
        val current = _state.value
        if (!EngineTransitions.isLegal(current, next)) return false
        _state.value = next
        return true
    }

    private fun startInForeground() {
        val notification = buildNotification(_state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: EngineState): Notification {
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, EngineService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(applicationInfo.labelRes))
            .setContentText(describe(state))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(null, getString(android.R.string.cancel), stop).build(),
            )
            .build()
    }

    /**
     * One line an operator can read at a glance. A degraded engine states its reason
     * verbatim rather than a generic failure, because the reason decides what the
     * operator does next.
     */
    private fun describe(state: EngineState): String =
        when (state) {
            EngineState.Initialising -> "Starting up"
            EngineState.Ready -> "Ready"
            EngineState.Listening -> "Listening"
            EngineState.Recognising -> "Recognising"
            EngineState.Transmitting -> "Transmitting"
            EngineState.Receiving -> "Receiving"
            EngineState.Speaking -> "Speaking"
            is EngineState.Degraded -> state.reason.message
        }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Engine",
                // LOW: visible and persistent, but never makes a sound. This runs for hours.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that iTantra is listening"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "itantra.engine"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.itantra.app.service.STOP"

        /** `minSdk` is 26, so the foreground path is the only one that ever applies. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EngineService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

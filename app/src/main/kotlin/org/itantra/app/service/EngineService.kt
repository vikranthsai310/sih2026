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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.itantra.app.ItantraApplication
import org.itantra.app.engine.MessageEngine
import org.itantra.app.ui.OperatingState

/**
 * Owns the engine. Task **W1.13**, and relay mode.
 *
 * ## Two lifetimes, one service
 *
 * The activity **binds** to this service and borrows [engine]. With nothing else going on
 * the service lives exactly as long as its binding: the activity is destroyed, the
 * binding drops, the service is destroyed, the engine is stopped. That is the lifetime
 * the engine always had, moved one class over.
 *
 * **Relay mode** is the second lifetime. The operator turns it on, the activity *starts*
 * the service as well as binding to it, and it goes foreground with a notification. Now
 * it outlives the binding: the screen goes off, the application is swiped away, and the
 * radios keep listening and rebroadcasting for the units that are relying on this one to.
 * Turn relay mode off and it drops back to the first lifetime — [stopForeground] and
 * [stopSelf], after which the next unbind ends it.
 *
 * ## Why it builds its own engine
 *
 * `START_STICKY`: if the platform kills the process under memory pressure while relay
 * mode is on, the service comes back — with a null intent, no activity, and nobody to
 * hand it an engine. So the engine is built here, from [ItantraApplication], not passed
 * in. A relay that comes back from the dead is only worth having if it comes back
 * *working*.
 *
 * ## What relay mode costs, and why it is explicit
 *
 * The radios stay on and so does the processor: a partial wake lock is held for as long
 * as relay mode is, because a BLE scan result arriving on a sleeping CPU is not processed
 * until something else wakes it. That is real battery, and it is why the mode is a switch
 * the operator throws rather than a thing the application does for them. A foreground
 * service is exempt from Doze's network restrictions, which is what lets the Wi-Fi
 * road keep receiving.
 *
 * ## The notification
 *
 * Silent and low-importance: this runs for hours, and a radio that pinged on every state
 * change would be turned off within the first hour of a deployment. It says what the
 * engine is doing, including DEGRADED, which must never be a silent condition. The
 * `connectedDevice` type is what Android 14 requires of a foreground service whose job is
 * Bluetooth and Wi-Fi; `microphone` is kept for the open line.
 */
class EngineService : LifecycleService() {
    /**
     * The engine, or null before Bluetooth has been granted.
     *
     * Compose state rather than a field, for the same reason the activity's reference to
     * it was: the screen has to redraw the moment it appears.
     */
    var engine: MessageEngine? by mutableStateOf(null)
        private set

    /**
     * When the engine started, for the soak duration the report conditions require.
     *
     * `elapsedRealtime` rather than `currentTimeMillis`: the wall clock can be moved by the
     * network or by hand mid-run, and a soak that appears to last minus four minutes is not
     * a soak that can be reported.
     */
    var engineStartedAt: Long = SystemClock.elapsedRealtime()
        private set

    private var relaying = false

    private var wakeLock: PowerManager.WakeLock? = null

    private var notifier: Job? = null

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
        ensureEngine()
    }

    /**
     * Builds and starts the engine if Bluetooth is granted and it does not exist yet.
     *
     * Called at creation, and again by the activity once the operator has answered the
     * permission dialog. Idempotent: the activity calls it on every bind.
     *
     * @return the engine, or null while permission is still wanting
     */
    fun ensureEngine(): MessageEngine? {
        engine?.let { return it }
        val app = ItantraApplication.of(this)
        if (!app.hasBluetoothPermission()) return null
        val built = app.buildEngine(lifecycleScope).also { it.start() }
        engine = built
        // The soak clock starts with the workload, not with the process.
        engineStartedAt = SystemClock.elapsedRealtime()
        if (relaying) watchForNotification(built)
        return built
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        val preferences = ItantraApplication.of(this).preferences
        // A null intent is the platform restarting us after a kill: relay mode was on
        // when we died, or the preference says so now. Either way the answer is on disk.
        val wanted =
            when (intent?.action) {
                ACTION_RELAY_ON -> true
                ACTION_RELAY_OFF -> false
                else -> preferences.relayMode
            }
        // The notification's "Stop relaying" arrives here with nobody on the screen to
        // record it. Written before acting, so a restart after this reads the right
        // answer and the switch on the screen agrees with the service.
        if (preferences.relayMode != wanted) preferences.setRelayMode(wanted)
        if (wanted) enterRelayMode() else leaveRelayMode()
        // START_STICKY only while relaying: a service that was only ever bound has no
        // business coming back on its own.
        return if (wanted) START_STICKY else START_NOT_STICKY
    }

    private fun enterRelayMode() {
        if (relaying) return
        relaying = true
        startInForeground()
        acquireWakeLock()
        ensureEngine()?.let { watchForNotification(it) }
        Log.i(TAG, "relay mode on")
    }

    /**
     * Back to the bound-only lifetime. [stopSelf] does not end a service something is
     * still bound to — it ends the *started* state, so the next unbind is the last.
     */
    private fun leaveRelayMode() {
        if (!relaying) {
            stopSelf()
            return
        }
        relaying = false
        notifier?.cancel()
        notifier = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "relay mode off")
    }

    override fun onDestroy() {
        notifier?.cancel()
        releaseWakeLock()
        engine?.stopLocating()
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification(engine?.state?.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Keeps the notification honest: it always shows the state the engine is in. */
    private fun watchForNotification(engine: MessageEngine) {
        notifier?.cancel()
        notifier =
            lifecycleScope.launch {
                engine.state.collectLatest { state ->
                    if (relaying) notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(state: OperatingState?): Notification {
        val off =
            PendingIntent.getService(
                this,
                0,
                Intent(this, EngineService::class.java).setAction(ACTION_RELAY_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(applicationInfo.labelRes))
            .setContentText(describe(state))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop relaying", off).build())
            .build()
    }

    /**
     * One line an operator can read at a glance. A degraded engine states its reason
     * verbatim rather than a generic failure, because the reason decides what the
     * operator does next.
     */
    private fun describe(state: OperatingState?): String {
        if (state == null) {
            // No engine means no permission: the one thing this service cannot ask for.
            return "Waiting for the Nearby devices permission. Open the app to grant it"
        }
        state.degraded?.let { return "Relaying · ${it.message}" }
        val units =
            when (state.peerCount) {
                0 -> "no other units heard"
                1 -> "1 unit heard"
                else -> "${state.peerCount} units heard"
            }
        return "Relaying · $units"
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Relay",
                // LOW: visible and persistent, but never makes a sound. This runs for hours.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that iTantra is relaying for other units"
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
        const val ACTION_RELAY_ON = "org.itantra.app.service.RELAY_ON"
        const val ACTION_RELAY_OFF = "org.itantra.app.service.RELAY_OFF"
        private const val WAKE_LOCK_TAG = "itantra:relay"
        private const val TAG = "itantra-service"

        fun bindIntent(context: Context): Intent = Intent(context, EngineService::class.java)

        /**
         * Turns relay mode on: the service goes foreground and outlives the activity.
         *
         * `minSdk` is 26, so the foreground path is the only one that ever applies. Must be
         * called while the application is visible — Android 12 and later refuse a
         * foreground service started from the background, and Android 14 refuses a
         * microphone-typed one in particular.
         */
        fun startRelay(context: Context) {
            context.startForegroundService(bindIntent(context).setAction(ACTION_RELAY_ON))
        }

        /** Turns relay mode off. The service drops back to living with its binding. */
        fun stopRelay(context: Context) {
            context.startService(bindIntent(context).setAction(ACTION_RELAY_OFF))
        }
    }
}

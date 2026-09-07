package org.itantra.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.itantra.proto.Presence

/**
 * Where this handset is, from the platform's own receivers, only while somebody needs it.
 *
 * Started when this unit is asked to beacon for a locator, or is itself locating, and
 * stopped when neither is true. Nothing here runs in the background and nothing here ever
 * leaves the handset except inside a sealed frame on the channel. The permission is the
 * platform's condition for reading the receiver at all; [isPermitted] says whether the
 * operator has granted it, and every screen that needs a position says so when they have
 * not, rather than pretending.
 *
 * GPS and the network provider are both asked, because indoors GPS gives nothing and a
 * network fix, though coarse, still points an arrow the right way across a compound. On
 * Android 12 and later the platform's own fused provider is asked as well: it blends the
 * satellite fix with the inertial sensors, so a fix keeps coming between satellite
 * epochs and through the momentary dropouts under a tree or beside a wall, and it is what
 * every map application on the handset is drawing from.
 */
class PositionSource(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    var latest: Location? = null
        private set

    /** `SystemClock.elapsedRealtime()` when [latest] was set. */
    @Volatile
    private var latestAtMillis = 0L

    private var listening = false

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val current = latest
                // A worse fix from the network provider must not replace a fresh GPS fix.
                if (current != null &&
                    location.provider != current.provider &&
                    location.accuracy > current.accuracy + 5f &&
                    SystemClock.elapsedRealtime() - latestAtMillis < STALE_MILLIS
                ) {
                    return
                }
                latest = location
                latestAtMillis = SystemClock.elapsedRealtime()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: android.os.Bundle?,
            ) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

    fun isPermitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** @return false when the permission is missing or the handset has no receiver. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        val lm = manager ?: return false
        if (!isPermitted()) return false
        var any = false
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            providers += LocationManager.FUSED_PROVIDER
        }
        for (provider in providers) {
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, INTERVAL_MILLIS, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(provider)?.let { last ->
                        if (latest == null) {
                            latest = last
                            val ageMillis = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000
                            latestAtMillis = SystemClock.elapsedRealtime() - ageMillis
                        }
                    }
                    any = true
                }
            }
        }
        listening = any
        return any
    }

    fun stop() {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
    }

    val isRunning: Boolean get() = listening

    /** The latest fix as it travels, or null without one fresh enough to be worth sending. */
    fun current(): Presence.Position? {
        val fix = latest ?: return null
        val ageMillis = SystemClock.elapsedRealtime() - latestAtMillis
        if (ageMillis > STALE_MILLIS) return null
        return Presence.Position(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMetres = fix.accuracy.toInt().coerceAtLeast(1),
            ageSeconds = (ageMillis / 1000).toInt().coerceIn(0, 255),
        )
    }

    private companion object {
        const val INTERVAL_MILLIS = 1_000L

        /** A fix older than this says where the handset was, not where it is. */
        const val STALE_MILLIS = 60_000L
    }
}

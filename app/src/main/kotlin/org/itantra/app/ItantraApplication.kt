package org.itantra.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import org.itantra.app.engine.MessageEngine
import org.itantra.app.platform.DataStoreEpochStore
import org.itantra.app.platform.Heading
import org.itantra.app.platform.ModelStore
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.platform.PositionSource
import org.itantra.app.platform.SherpaSpeech
import org.itantra.app.platform.Speaker
import org.itantra.app.platform.UnitPreferences
import org.itantra.asr.BiasingLexicon
import org.itantra.proto.TemplateProfile

/**
 * What the activity and the service both need and must not each build for themselves.
 *
 * ## Why this exists
 *
 * The engine used to be built by the activity, on the activity's own coroutine scope, and
 * stopped in its `onDestroy`. That is the right lifetime for a screen and the wrong one
 * for a radio: a unit whose operator locked the phone and put it in a pocket stopped
 * hearing, and stopped rebroadcasting for the units that were relying on it to. Relay
 * mode -- `docs/TRANSPORT.md` section 8 -- is the promise that it keeps going, and a
 * promise like that has to be kept by something that outlives the screen.
 *
 * So the engine now belongs to [org.itantra.app.service.EngineService], and the service
 * has to be able to build it **on its own**: a service the platform restarts after killing
 * the process comes back with no activity attached and nobody to hand it an engine. Every
 * dependency the engine has is derived from the application context, so the factory
 * lives here, where both can reach it and where there is exactly one of each thing the
 * two of them share.
 *
 * ## What must be shared, and why
 *
 * [preferences] is read once from disk with a blocking read and then held in memory. Two
 * instances would each hold their own copy, and the operator's change on the screen would
 * never reach the copy the engine reads. [identity] is derived from the preferences and
 * the install id; [positions] and [heading] hold a sensor each. One of each, here.
 */
class ItantraApplication : Application() {
    /** The operator's settings for this handset, kept across restarts. */
    val preferences: UnitPreferences by lazy { UnitPreferences(this, defaultUnitName()) }

    val identity: NodeIdentity by lazy { NodeIdentity.of(installationId(), preferences.unitName) }

    /** Position and compass, for finding a unit. Both idle until somebody is looking. */
    val positions: PositionSource by lazy { PositionSource(this) }
    val heading: Heading by lazy { Heading(this) }

    /**
     * Builds the engine, on [scope], with everything it needs.
     *
     * The caller owns the result and is responsible for stopping it. Bluetooth must be
     * granted first: the adapter cannot be enumerated until it is, so the net is built
     * after the answer rather than before the question.
     */
    fun buildEngine(scope: CoroutineScope): MessageEngine {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return MessageEngine(
            scope = scope,
            adapter = adapter,
            identity = identity,
            epochStore = DataStoreEpochStore(this),
            templates = deploymentProfile(),
            bondedDevices = { bonded(adapter) },
            speech = SherpaSpeech(ModelStore(this)),
            lexicons = ::lexiconFor,
            wifiContext = this,
            speaker = Speaker(ModelStore(this), this),
            preferences = preferences,
            positions = positions,
            heading = heading,
        )
    }

    /**
     * All three Bluetooth permissions became runtime permissions in Android 12, and the
     * net needs all three: CONNECT for the bonded sockets, SCAN to hear the broadcast
     * channel, ADVERTISE to speak on it. Checking CONNECT alone let the engine start with
     * the other two refused, on a channel it could neither hear nor speak.
     */
    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The handset's model is what the operator already calls this unit. */
    fun defaultUnitName(): String = (Build.MODEL ?: "UNIT").uppercase()

    /**
     * The alert lexicon for a language, or null where none ships.
     *
     * Null rather than empty when a file is missing: an empty lexicon and an absent one look
     * identical to a corrector, and only one of them is a packaging fault worth noticing.
     */
    fun lexiconFor(code: String): BiasingLexicon? {
        val domain = readAsset("lexicon/alert-lexicon.$code.txt") ?: return null
        // Negation is optional only in the sense that a missing file must not stop the
        // domain terms loading; every language in this repository ships one.
        val negation = readAsset("lexicon/negation.$code.txt").orEmpty()
        return BiasingLexicon.of(domain, negation)
    }

    fun readAsset(path: String): String? =
        runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    /**
     * The template table every unit in this deployment shares.
     *
     * A failure here is fatal by choice. Every unit must hold the same table, and a handset
     * that started with an empty one would send bytes that mean nothing on arrival -- which
     * is worse than not starting, because it looks like it is working.
     */
    private fun deploymentProfile() =
        TemplateProfile
            .parse(assets.open(PROFILE_ASSET).bufferedReader().use { it.readText() })
            .toTable()

    /** Stable per device, per signing key, across restarts. See [NodeIdentity]. */
    @SuppressLint("HardwareIds")
    private fun installationId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL

    private fun bonded(adapter: BluetoothAdapter?): List<BluetoothDevice> =
        try {
            if (hasBluetoothPermission()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()
        } catch (denied: SecurityException) {
            // The platform can revoke between the check and the call. A crash here would
            // take out the whole screen for a permission problem.
            emptyList()
        }

    companion object {
        const val PROFILE_ASSET = "templates.json"

        /** The application, from any context that has one. */
        fun of(context: Context): ItantraApplication = context.applicationContext as ItantraApplication
    }
}

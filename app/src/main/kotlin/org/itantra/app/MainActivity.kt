package org.itantra.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import org.itantra.app.engine.MessageEngine
import org.itantra.app.platform.DataStoreEpochStore
import org.itantra.app.platform.NodeIdentity
import org.itantra.app.platform.PushToTalkKey
import org.itantra.app.ui.OperatingScreen
import org.itantra.audio.EngineState
import org.itantra.proto.TemplateProfile

/**
 * The operating screen, hosted, over a real Bluetooth net. Tasks **W1.11**, **W3.12**.
 *
 * ## What this class does, and deliberately no more
 *
 * Holds the window, asks for the permissions, routes the hardware key, and hands a state
 * flow to a composable. [MessageEngine] owns the net; the screen owns the pixels; this owns
 * neither. The week-2 bring-up screen that used to live here — a text field and two
 * Bluetooth buttons — was deleted in W3.12 and is not coming back.
 *
 * ## Why the engine is Compose state
 *
 * It is created *after* the permission answer, which arrives long after the first
 * composition. Held in a plain field it was invisible to Compose: the screen composed once
 * against `null`, fell back to [startingState], and stayed there for the life of the
 * process — showing `node 00`, `0 units` and `NO LINK` on a handset whose net was up. Every
 * control was live and every one of them was talking to an engine the screen could not see.
 *
 * `mutableStateOf` is the whole fix, and the bug is worth naming because nothing about the
 * symptom points at it.
 *
 * ## What works on two or more handsets today
 *
 * Install on every unit, bond them in Android's Bluetooth settings, and open the app. Each
 * one listens for connections and dials the units it is paired with, and the transmit
 * control sends a template code over the real path: matched, sealed with the transport's tag
 * length, framed, transmitted, verified, replay-checked and rendered in the **receiver's**
 * language.
 *
 * What is absent is speech at either end, because there are no model files. Band F's frame
 * size is real; its latency figures stay as dashes until there is a recogniser to measure.
 */
class MainActivity : ComponentActivity() {
    /** Compose state, not a field. See the class comment — this was a real defect. */
    private var engine by mutableStateOf<MessageEngine?>(null)

    /** Set once the operator has said no, since Android will not ask a second time. */
    private var permissionRefused by mutableStateOf(false)

    private val identity by lazy { NodeIdentity.of(installationId(), unitName()) }

    private val transmitKey =
        PushToTalkKey(
            onPress = { engine?.onTransmit(true) },
            onRelease = { engine?.onTransmit(false) },
        )

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Bluetooth cannot be enumerated until it is granted, so the net is built after
            // the answer rather than before the question.
            permissionRefused = !startEngine()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!startEngine()) permissions.launch(requiredPermissions())

        setContent {
            val running = engine
            val state = running?.state?.collectAsState()?.value ?: startingState()
            OperatingScreen(
                state = state,
                onTransmitChange = { running?.onTransmit(it) },
                onAlert = { running?.onAlert() },
                onPosition = { },
                onLanguage = { running?.onLanguageCycle() },
                onMenu = { },
            )
        }
    }

    /**
     * Retries the two things an operator most often leaves and comes back from: switching
     * Bluetooth on, and pairing the other handset. Both are fixed in Settings, and returning
     * here is the only signal that they might have been.
     */
    override fun onResume() {
        super.onResume()
        val running = engine
        if (running == null) startEngine() else running.restartIfIdle()
    }

    /** @return false when a permission is still needed, so the caller can ask for it. */
    private fun startEngine(): Boolean {
        if (engine != null) return true
        if (!hasBluetoothPermission()) return false

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        engine =
            MessageEngine(
                scope = lifecycleScope,
                adapter = adapter,
                identity = identity,
                epochStore = DataStoreEpochStore(applicationContext),
                templates = deploymentProfile(),
                bondedDevices = { bonded(adapter) },
            ).also { it.start() }
        return true
    }

    /**
     * The deployment profile, from the asset the build copies out of `models/`.
     *
     * A failure here is fatal by choice. Every unit must hold the same table, and a handset
     * that started with an empty one would send bytes that mean nothing on arrival — which
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

    /** The handset's own Bluetooth name is what the operator already calls this unit. */
    private fun unitName(): String = (Build.MODEL ?: "UNIT").uppercase()

    private fun bonded(adapter: BluetoothAdapter?): List<BluetoothDevice> =
        try {
            if (hasBluetoothPermission()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()
        } catch (denied: SecurityException) {
            // The platform can revoke between the check and the call. A crash here would
            // take out the whole screen for a permission problem.
            emptyList()
        }

    override fun onKeyDown(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean =
        transmitKey.onKey(keyCode, event.action, event.repeatCount) ||
            super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean =
        transmitKey.onKey(keyCode, event.action, event.repeatCount) ||
            super.onKeyUp(keyCode, event)

    override fun onPause() {
        super.onPause()
        // An operator interrupted mid-transmission must not leave the floor held.
        transmitKey.releaseIfHeld()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    /** `BLUETOOTH_CONNECT` became a runtime permission in Android 12. */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Shown between launch and the permission answer, and for good if it was refused.
     *
     * It carries the real node id rather than a zero: the id comes from the installation
     * id and needs no permission, and `node 00` is a value [NodeIdentity] cannot produce —
     * so seeing one on screen means the engine is missing, which is not a fact worth
     * hiding behind a plausible-looking placeholder.
     */
    private fun startingState() =
        org.itantra.app.ui.OperatingState(
            unitName = identity.displayName,
            nodeId = identity.src,
            peerCount = 0,
            linkUp = false,
            transportName = "bluetooth",
            mode = "PTT",
            audience = "ALL UNITS",
            language = "हिन्दी",
            degraded =
                if (permissionRefused) EngineState.Degraded.Reason.PERMISSION_DENIED else null,
        )

    private companion object {
        const val PROFILE_ASSET = "templates.json"
    }
}

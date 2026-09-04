package org.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.itantra.app.platform.PushToTalkKey
import org.itantra.app.ui.BandFMetrics
import org.itantra.app.ui.OperatingScreen
import org.itantra.app.ui.OperatingState
import org.itantra.audio.EngineState

/**
 * The operating screen, hosted. Tasks **W1.11** and **W3.12**.
 *
 * ## What was deleted here
 *
 * This class was the week-2 bring-up screen: a text field, two Bluetooth buttons and a
 * byte counter. It closed gate W2 — *typed text on device A appears on device B* — and
 * task **W2.31** said plainly that it was temporary and that **W3.12** would delete it once
 * speech replaced typing.
 *
 * It is deleted rather than hidden behind a flag. An application whose fastest route to
 * sending a message is to type it is the one thing this project exists not to be, and a
 * debug field that survives to a demonstration gets used in one — usually at the moment the
 * recogniser is being uncooperative, which is exactly the moment it must not be available.
 *
 * ## What replaces it
 *
 * [OperatingScreen]. This activity's job is now what an activity's job should be: hold the
 * window, ask for the permissions, route the hardware key, and hand a state object to a
 * composable that knows nothing about Android.
 *
 * ## The state is still assembled by hand
 *
 * There is no engine behind it yet — the recogniser and the synthesiser need models and a
 * handset, and the service that would own a [org.itantra.link.Session] is task W1.30. The
 * screen is wired to a state that a person updates, so the layout, the bands, the
 * accessibility and the 200 % behaviour can all be exercised now rather than after the
 * hardware arrives. Nothing here fabricates a measurement: band F shows `—` until something
 * real fills it.
 */
class MainActivity : ComponentActivity() {
    /** Lit while the transmit key is held. Rule 4: the screen may be off. */
    private var transmitting by mutableStateOf(false)

    private var microphoneGranted by mutableStateOf(false)

    private val transmitKey =
        PushToTalkKey(
            onPress = { transmitting = true },
            onRelease = { transmitting = false },
        )

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            microphoneGranted = hasMicrophonePermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        microphoneGranted = hasMicrophonePermission()
        if (!microphoneGranted) permissions.launch(requiredPermissions())

        setContent {
            OperatingScreen(
                state = operatingState(),
                onTransmitChange = { transmitting = it },
                onAlert = { },
                onPosition = { },
                onMenu = { },
            )
        }
    }

    /**
     * What the screen shows today.
     *
     * The degraded reason is derived rather than invented: without the microphone the unit
     * genuinely cannot listen, and saying so is the whole point of the band. Everything
     * else that has no source yet is left null, which the screen draws as `—`.
     */
    private fun operatingState() =
        OperatingState(
            unitName = "BASE",
            nodeId = 1,
            peerCount = 0,
            linkUp = false,
            transportName = "bluetooth",
            mode = "PTT",
            audience = "ALL UNITS",
            language = "हिन्दी",
            transmitting = transmitting,
            degraded =
                if (microphoneGranted) null else EngineState.Degraded.Reason.MICROPHONE_UNAVAILABLE,
            metrics = BandFMetrics(),
        )

    /**
     * Task **W5.4**, partially. Consuming the event is what stops transmitting from also
     * changing the alarm volume.
     *
     * **This only works while the activity is in the foreground.** An `Activity` key
     * override never sees volume keys once the screen is off or another app is on top, so
     * the screen-off half of W5.4 needs a `MediaSession` receiving media-button events from
     * the foreground service instead. That is not built yet, and this is not a substitute
     * for it.
     */
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

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

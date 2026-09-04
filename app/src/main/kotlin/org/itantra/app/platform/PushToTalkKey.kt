package org.itantra.app.platform

import android.view.KeyEvent

/**
 * Binds a hardware key to the transmit control. Task **W5.4**.
 *
 * ## Why a hardware key at all
 *
 * Meena is wearing gloves, at altitude, with the screen dark. A capacitive touchscreen
 * does not respond to a gloved finger, and finding an on-screen button without looking is
 * not possible. Rule 4 of the inclusive design rules exists for this: the primary
 * transmit control must be operable with the screen off.
 *
 * **Volume-down** is the choice because it is present on every handset, it is findable by
 * touch, and it is not the power button.
 *
 * ## Press and release, not toggle
 *
 * Push-to-talk means the key is *held*. A toggle would leave a handset transmitting after
 * a knock in a pocket, which on a half-duplex channel silences everyone else until the
 * stale-hold expiry fires 12 seconds later. Holding costs the operator nothing and fails
 * safe.
 *
 * Android repeats `ACTION_DOWN` while a key is held, so repeats are filtered here — a
 * press is the first down, and a release is the up.
 */
class PushToTalkKey(
    private val keyCode: Int = DEFAULT_KEY,
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
) {
    private var held = false

    val isHeld: Boolean get() = held

    /** Adapter for the platform type. The decision itself is in [onKey]. */
    fun onKeyEvent(event: KeyEvent): Boolean = onKey(event.keyCode, event.action, event.repeatCount)

    /**
     * The binding logic, in primitives so it can be tested without a `KeyEvent` — which
     * a JVM unit test cannot construct.
     *
     * @return true if the event was consumed, which suppresses the platform's own
     *   handling. Otherwise every transmission would also change the alarm volume,
     *   including the volume this app raises for its own alerts.
     */
    fun onKey(
        code: Int,
        action: Int,
        repeatCount: Int,
    ): Boolean {
        if (code != keyCode) return false

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                // Auto-repeat while held must not re-trigger the press.
                if (repeatCount == 0 && !held) {
                    held = true
                    onPress()
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (held) {
                    held = false
                    onRelease()
                }
                true
            }
            else -> false
        }
    }

    /**
     * Releases the key if it is held.
     *
     * Called when the activity loses focus or the service stops. Without it, an operator
     * who holds the key and is interrupted by an incoming call leaves the floor held
     * until the 12-second expiry — and every other unit sees a channel that is busy for
     * no reason.
     */
    fun releaseIfHeld() {
        if (held) {
            held = false
            onRelease()
        }
    }

    companion object {
        /** Present on every handset, findable by touch, and not the power button. */
        const val DEFAULT_KEY = KeyEvent.KEYCODE_VOLUME_DOWN
    }
}

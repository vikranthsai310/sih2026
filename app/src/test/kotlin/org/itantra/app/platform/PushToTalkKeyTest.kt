package org.itantra.app.platform

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transmit key, task W5.4.
 *
 * `KeyEvent.ACTION_DOWN` and friends are compile-time constants, so they inline and work
 * in a JVM unit test; a `KeyEvent` instance would not, which is why the logic takes
 * primitives.
 */
class PushToTalkKeyTest {
    private var presses = 0
    private var releases = 0

    private fun key() = PushToTalkKey(onPress = { presses++ }, onRelease = { releases++ })

    private fun PushToTalkKey.down(repeat: Int = 0) = onKey(PushToTalkKey.DEFAULT_KEY, KeyEvent.ACTION_DOWN, repeat)

    private fun PushToTalkKey.up() = onKey(PushToTalkKey.DEFAULT_KEY, KeyEvent.ACTION_UP, 0)

    @Test
    fun `a press and release drive the floor once each`() {
        val k = key()
        k.down()
        assertTrue(k.isHeld)
        k.up()

        assertEquals(1, presses)
        assertEquals(1, releases)
        assertFalse(k.isHeld)
    }

    /**
     * Android repeats `ACTION_DOWN` while a key is held. Without filtering, a two-second
     * transmission would seize the floor dozens of times.
     */
    @Test
    fun `auto-repeat while held does not re-trigger the press`() {
        val k = key()
        k.down(repeat = 0)
        for (r in 1..20) k.down(repeat = r)

        assertEquals("exactly one press", 1, presses)
        assertTrue(k.isHeld)
    }

    /**
     * Consuming the event is what stops the platform also changing the alarm volume —
     * including the volume this app raises for its own alerts.
     */
    @Test
    fun `the event is consumed so the platform does not also act on it`() {
        val k = key()
        assertTrue(k.down())
        assertTrue(k.up())
    }

    @Test
    fun `another key is left alone`() {
        val k = key()
        assertFalse(k.onKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertEquals(0, presses)
        assertFalse(k.isHeld)
    }

    @Test
    fun `a release without a press does nothing`() {
        val k = key()
        k.up()
        assertEquals(0, releases)
    }

    /**
     * An operator holding the key who is interrupted by a call would otherwise leave the
     * floor held until the 12-second expiry, and every other unit would see a channel
     * busy for no reason.
     */
    @Test
    fun `losing focus while held releases the floor`() {
        val k = key()
        k.down()
        k.releaseIfHeld()

        assertEquals(1, releases)
        assertFalse(k.isHeld)
    }

    @Test
    fun `releasing when nothing is held is harmless`() {
        val k = key()
        k.releaseIfHeld()
        k.releaseIfHeld()
        assertEquals(0, releases)
    }

    @Test
    fun `the default binding is volume-down`() {
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, PushToTalkKey.DEFAULT_KEY)
    }

    /** Push-to-talk is held, not toggled: a knock in a pocket must not start a transmission. */
    @Test
    fun `a second press without a release does not double-seize`() {
        val k = key()
        k.down()
        k.down()
        assertEquals(1, presses)
    }
}

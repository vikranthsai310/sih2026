package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    @Test
    fun `a name alone round-trips in a handful of bytes`() {
        val p = Presence(name = "ALPHA 2")
        val bytes = p.encode()
        assertEquals(3 + 7 + 1, bytes.size)
        assertEquals(p, Presence.decode(bytes))
    }

    @Test
    fun `a position round-trips to micro-degree precision`() {
        val p =
            Presence(
                name = "बेस",
                position = Presence.Position(20.296059, 85.824539, accuracyMetres = 7, ageSeconds = 2),
                beaconing = true,
                openLine = true,
                batteryPercent = 63,
            )
        val back = Presence.decode(p.encode())!!
        assertEquals("बेस", back.name)
        assertEquals(20.296059, back.position!!.latitude, 1e-6)
        assertEquals(85.824539, back.position!!.longitude, 1e-6)
        assertEquals(7, back.position!!.accuracyMetres)
        assertEquals(2, back.position!!.ageSeconds)
        assertTrue(back.beaconing)
        assertTrue(back.openLine)
        assertEquals(63, back.batteryPercent)
    }

    @Test
    fun `a long name is cut to twenty-four bytes and never inside a character`() {
        val long = Presence(name = "हिन्दी बचाव दल संख्या सत्रह")
        val bytes = long.encode()
        val back = Presence.decode(bytes)!!
        assertTrue(back.name.toByteArray(Charsets.UTF_8).size <= Presence.MAX_NAME_BYTES)
        assertTrue("a clipped name is a prefix of the original", "हिन्दी बचाव दल संख्या सत्रह".startsWith(back.name))
        assertTrue(back.name.isNotEmpty())
    }

    @Test
    fun `garbage is refused rather than guessed`() {
        assertNull(Presence.decode(ByteArray(0)))
        assertNull(Presence.decode(byteArrayOf(9, 0, 0, 0)))
        assertNull(Presence.decode(byteArrayOf(1, 0, 30, 0)))
        // Position flag set, but no position bytes.
        assertNull(Presence.decode(byteArrayOf(1, 1, 1, 'A'.code.toByte(), 0)))
    }

    @Test
    fun `a locate request round-trips`() {
        assertEquals(Locate(target = 208, start = true), Locate.decode(Locate(208, true).encode()))
        assertEquals(Locate(target = 7, start = false), Locate.decode(Locate(7, false).encode()))
        assertNull(Locate.decode(byteArrayOf(1, 9, 1)))
        assertNull(Locate.decode(byteArrayOf(2, 1, 1)))
    }

    @Test
    fun `the sound flag round-trips and is four bytes`() {
        val asked = Locate(target = 5, start = true, sound = true)
        assertEquals(4, asked.encode().size)
        assertEquals(asked, Locate.decode(asked.encode()))
        assertEquals(Locate(5, true, sound = false), Locate.decode(Locate(5, true).encode()))
    }

    /** The two versions share a channel: three bytes is silence, and a stop never sounds. */
    @Test
    fun `a three-byte request from an older unit reads as silence`() {
        assertEquals(Locate(target = 5, start = true, sound = false), Locate.decode(byteArrayOf(1, 1, 5)))
        assertEquals(Locate(target = 5, start = false, sound = false), Locate.decode(byteArrayOf(1, 2, 5, 1)))
    }
}

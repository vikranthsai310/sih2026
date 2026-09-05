package org.itantra.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property under test is **agreement**, so every case is asserted from both ends.
 *
 * A rule that is individually sensible on each handset and disagrees between them is
 * exactly the defect this replaced, and it is invisible from one side: each unit's own
 * behaviour looked correct while the pair never connected.
 */
class PeerPreferenceTest {
    // ── who dials ────────────────────────────────────────────────────────────

    @Test
    fun `exactly one of two named units dials the other`() {
        val a = PeerPreference.shouldDial("alpha", "bravo")
        val b = PeerPreference.shouldDial("bravo", "alpha")
        assertEquals("both dialling leaves nobody listening; neither leaves nobody dialling", 1, count(a, b))
    }

    @Test
    fun `the answer does not depend on which unit is asking first`() {
        val names = listOf("BASE", "Galaxy S25", "moto g84", "अग्रिम", "unit-7")
        for (ours in names) {
            for (theirs in names) {
                if (ours == theirs) continue
                assertEquals(
                    "$ours and $theirs disagree about who dials",
                    1,
                    count(
                        PeerPreference.shouldDial(ours, theirs),
                        PeerPreference.shouldDial(theirs, ours),
                    ),
                )
            }
        }
    }

    /**
     * Two handsets out of the box genuinely share a name. There is no ordering to agree on,
     * so the tie breaks towards connecting: a duplicated socket costs one wasted write per
     * message, where a missing one is a dead net.
     */
    @Test
    fun `identical names make both units dial rather than neither`() {
        assertTrue(PeerPreference.shouldDial("Galaxy S25", "Galaxy S25"))
    }

    @Test
    fun `an absent or blank name makes this unit dial`() {
        assertTrue(PeerPreference.shouldDial(null, "bravo"))
        assertTrue(PeerPreference.shouldDial("alpha", null))
        assertTrue(PeerPreference.shouldDial("", "bravo"))
        assertTrue(PeerPreference.shouldDial("alpha", "   "))
    }

    // ── which socket survives ────────────────────────────────────────────────

    /**
     * When both units dialled anyway, the two ends must discard the *same* socket. If each
     * keeps its own outbound one they close each other's and the pair is left with nothing.
     */
    @Test
    fun `both ends keep the same socket of a duplicated pair`() {
        // A keeps the socket B dialled; B keeps the one it dialled itself.
        assertTrue("alpha should keep the inbound socket", PeerPreference.keepsInbound("alpha", "bravo"))
        assertFalse("bravo should keep its own outbound socket", PeerPreference.keepsInbound("bravo", "alpha"))
    }

    @Test
    fun `the surviving socket is the one dialled by the unit that should have dialled`() {
        val names = listOf("alpha", "bravo", "charlie", "delta")
        for (ours in names) {
            for (theirs in names) {
                if (ours == theirs) continue
                // "They dialled it" and "they were the one who should dial" are the same
                // statement, which is what makes the two ends agree.
                assertEquals(
                    "$ours keeps the wrong socket against $theirs",
                    PeerPreference.shouldDial(theirs, ours),
                    PeerPreference.keepsInbound(ours, theirs),
                )
            }
        }
    }

    /** No ordering means no agreement to be had, so whatever is registered stays put. */
    @Test
    fun `an unusable name leaves the existing socket alone`() {
        assertFalse(PeerPreference.keepsInbound(null, "bravo"))
        assertFalse(PeerPreference.keepsInbound("alpha", null))
        assertFalse(PeerPreference.keepsInbound("same", "same"))
    }

    private fun count(vararg flags: Boolean): Int = flags.count { it }
}

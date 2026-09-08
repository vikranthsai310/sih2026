package org.itantra.link

import org.itantra.link.WifiDirectElection.Action
import org.itantra.link.WifiDirectElection.Peer
import org.itantra.link.WifiDirectElection.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules in [WifiDirectElection], walked through on the desk.
 *
 * The random waits are pinned -- `random` returns a fixed fraction -- so every duration
 * below is exact and a test that fails says which rule broke rather than which dice fell.
 */
class WifiDirectElectionTest {
    private val nobody = emptyList<Peer>()
    private val owner = Peer("aa:bb:cc:dd:ee:01", isOwner = true)
    private val otherOwner = Peer("aa:bb:cc:dd:ee:02", isOwner = true)
    private val bystander = Peer("aa:bb:cc:dd:ee:03", isOwner = false)

    private fun election(fraction: Double = 0.0) = WifiDirectElection(random = { fraction }).apply { reset(now = 0) }

    @Test
    fun `a unit alone discovers first and becomes owner when its wait ends`() {
        val e = election()
        assertEquals(Action.Discover, e.advance(0, nobody))
        assertEquals("nothing to do while the wait runs", Action.None, e.advance(1_000, nobody))
        assertEquals(
            Action.CreateGroup,
            e.advance(WifiDirectElection.SEARCH_MIN_MILLIS, nobody),
        )
        // Asked once, not on every tick while the platform forms the group.
        assertEquals(Action.None, e.advance(WifiDirectElection.SEARCH_MIN_MILLIS + 2_000, nobody))
        e.formed(now = 7_000, isOwner = true, owner = null, clients = 0)
        assertTrue(e.phase is Phase.Owner)
    }

    @Test
    fun `an owner in sight is joined at once, and the lowest address when there are two`() {
        val e = election()
        e.advance(0, nobody)
        assertEquals(Action.Join(owner.address), e.advance(500, listOf(bystander, otherOwner, owner)))
        assertEquals(Phase.Joining(owner.address, since = 500), e.phase)
        e.formed(now = 3_000, isOwner = false, owner = owner.address, clients = 0)
        assertEquals(Phase.Client(owner.address), e.phase)
        assertEquals("a client has nothing to decide", Action.None, e.advance(60_000, listOf(owner)))
    }

    @Test
    fun `a join that never completes is given up, the owner shunned, and the search resumed`() {
        val e = election()
        e.advance(0, listOf(owner))
        val gaveUp = WifiDirectElection.JOIN_TIMEOUT_MILLIS
        assertEquals(Action.Discover, e.advance(gaveUp, listOf(owner)))
        assertTrue(e.phase is Phase.Searching)
        assertEquals("the shunned owner is not tried again yet", Action.None, e.advance(gaveUp + 1_000, listOf(owner)))
        assertEquals(
            "but it is once the shun expires",
            Action.Join(owner.address),
            e.advance(gaveUp + WifiDirectElection.SHUN_MILLIS + 1, listOf(owner)),
        )
    }

    @Test
    fun `a refused join is shunned too, and a different owner is taken instead`() {
        val e = election()
        e.advance(0, listOf(owner))
        e.joinFailed(now = 1_000)
        assertEquals(Action.Join(otherOwner.address), e.advance(1_100, listOf(owner, otherOwner)))
    }

    @Test
    fun `a refused create is retried after another wait`() {
        val e = election()
        e.advance(0, nobody)
        assertEquals(Action.CreateGroup, e.advance(WifiDirectElection.SEARCH_MIN_MILLIS, nobody))
        e.createFailed(now = WifiDirectElection.SEARCH_MIN_MILLIS + 100)
        assertEquals(Action.None, e.advance(WifiDirectElection.SEARCH_MIN_MILLIS + 200, nobody))
        assertEquals(Action.CreateGroup, e.advance(2 * WifiDirectElection.SEARCH_MIN_MILLIS + 100, nobody))
    }

    @Test
    fun `two owners with nobody aboard, the empty one steps down and joins the other`() {
        val e = election()
        e.formed(now = 0, isOwner = true, owner = null, clients = 0)
        // Seeing a rival starts the clock; nothing happens until it runs out.
        assertEquals(Action.Discover, e.advance(0, listOf(otherOwner)))
        assertEquals(Action.None, e.advance(1_000, listOf(otherOwner)))
        assertEquals(Action.RemoveGroup, e.advance(WifiDirectElection.STEP_DOWN_MIN_MILLIS, listOf(otherOwner)))
        assertTrue(e.phase is Phase.Searching)
        e.lost(now = WifiDirectElection.STEP_DOWN_MIN_MILLIS + 500)
        assertEquals(
            Action.Join(otherOwner.address),
            e.advance(WifiDirectElection.STEP_DOWN_MIN_MILLIS + 600, listOf(otherOwner)),
        )
    }

    @Test
    fun `an owner with clients never steps down for a rival`() {
        val e = election()
        e.formed(now = 0, isOwner = true, owner = null, clients = 2)
        e.advance(0, listOf(otherOwner))
        assertEquals(Action.None, e.advance(WifiDirectElection.STEP_DOWN_MAX_MILLIS + 1, listOf(otherOwner)))
        assertEquals(Phase.Owner(since = 0, clients = 2), e.phase)
    }

    @Test
    fun `a rival that goes away cancels the step-down`() {
        val e = election()
        e.formed(now = 0, isOwner = true, owner = null, clients = 0)
        e.advance(0, listOf(otherOwner))
        e.advance(1_000, nobody)
        assertEquals(Phase.Owner(since = 0, clients = 0, stepDownAt = null), e.phase)
        assertEquals(Action.None, e.advance(WifiDirectElection.STEP_DOWN_MAX_MILLIS + 1, nobody))
    }

    @Test
    fun `a client joining an owner is counted, and the count cancels a step-down`() {
        val e = election()
        e.formed(now = 0, isOwner = true, owner = null, clients = 0)
        e.advance(0, listOf(otherOwner))
        e.formed(now = 1_000, isOwner = true, owner = null, clients = 1)
        assertEquals(Action.None, e.advance(WifiDirectElection.STEP_DOWN_MAX_MILLIS + 1, listOf(otherOwner)))
        assertEquals(Phase.Owner(since = 0, clients = 1), e.phase)
    }

    @Test
    fun `losing the group returns to searching, and a client left alone searches again`() {
        val e = election()
        e.advance(0, listOf(owner))
        e.formed(now = 1_000, isOwner = false, owner = owner.address, clients = 0)
        e.lost(now = 30_000)
        assertTrue(e.phase is Phase.Searching)
        assertEquals(Action.Discover, e.advance(30_000, nobody))
        assertEquals(Action.CreateGroup, e.advance(30_000 + WifiDirectElection.SEARCH_MIN_MILLIS, nobody))
    }

    @Test
    fun `discovery is re-issued on its cadence, and at once when the radio stopped it`() {
        val e = election(fraction = 1.0) // the longest wait, so no create interrupts
        assertEquals(Action.Discover, e.advance(0, nobody))
        assertEquals(Action.None, e.advance(WifiDirectElection.DISCOVER_EVERY_MILLIS - 1, nobody))
        assertEquals(Action.Discover, e.advance(WifiDirectElection.DISCOVER_EVERY_MILLIS, nobody))
        e.discoveryStopped()
        assertEquals(Action.Discover, e.advance(WifiDirectElection.DISCOVER_EVERY_MILLIS + 1, nobody))
    }

    @Test
    fun `two units switched on together end up as one owner and one client`() {
        // Their waits differ because the dice differ; that is the whole mechanism.
        val quick = WifiDirectElection(random = { 0.0 }).apply { reset(0) }
        val slow = WifiDirectElection(random = { 0.5 }).apply { reset(0) }
        val quickAddress = "aa:bb:cc:dd:ee:10"

        quick.advance(0, nobody)
        slow.advance(0, nobody)
        val t = WifiDirectElection.SEARCH_MIN_MILLIS
        assertEquals(Action.CreateGroup, quick.advance(t, listOf(Peer("slow", false))))
        assertEquals(Action.None, slow.advance(t, listOf(Peer(quickAddress, false))))
        quick.formed(now = t + 1_000, isOwner = true, owner = null, clients = 0)
        // The slow unit's next peer report shows the quick one owning.
        assertEquals(Action.Join(quickAddress), slow.advance(t + 2_000, listOf(Peer(quickAddress, true))))
        slow.formed(now = t + 4_000, isOwner = false, owner = quickAddress, clients = 0)
        quick.formed(now = t + 4_000, isOwner = true, owner = null, clients = 1)
        assertEquals(Phase.Client(quickAddress), slow.phase)
        assertEquals(Phase.Owner(since = t + 1_000, clients = 1), quick.phase)
    }
}

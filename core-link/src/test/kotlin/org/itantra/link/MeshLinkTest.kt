package org.itantra.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fan-out to many units, which is what a walkie-talkie is.
 *
 * `docs/PROTOCOL.md` section 8 has said since week 2 that every frame goes to every unit,
 * and [RfcommLink] carries one peer at a time. These tests are about the layer that closes
 * that gap.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeshLinkTest {
    /** A peer whose connectedness and failures a test controls. */
    private class FakePeer(
        override val name: String = "peer",
        override val mtu: Int = 244,
        var throwOnSend: Boolean = false,
    ) : Link {
        val sent = ArrayList<ByteArray>()
        private val _state = MutableStateFlow(LinkState.IDLE)
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        private val _metrics = MutableStateFlow(LinkMetrics())

        override val state: StateFlow<LinkState> = _state
        override val incoming: Flow<ByteArray> = _incoming
        override val metrics: StateFlow<LinkMetrics> = _metrics

        override suspend fun send(frame: ByteArray) {
            if (throwOnSend) throw java.io.IOException("peer gone")
            sent.add(frame)
        }

        override suspend fun connect() {
            _state.value = LinkState.CONNECTED
        }

        override suspend fun disconnect() {
            _state.value = LinkState.IDLE
        }

        fun drop() {
            _state.value = LinkState.DEGRADED
        }

        suspend fun deliver(frame: ByteArray) = _incoming.emit(frame)
    }

    private fun frame(n: Int) = ByteArray(8) { n.toByte() }

    // ── broadcast ────────────────────────────────────────────────────────────

    @Test
    fun `one frame reaches every connected unit`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val peers = (1..5).map { FakePeer("unit$it") }
            peers.forEachIndexed { i, peer -> mesh.addPeer("unit$i", peer) }
            mesh.connect()

            mesh.send(frame(7))

            assertEquals(5, peers.count { it.sent.size == 1 })
            assertTrue(peers.all { it.sent.single().contentEquals(frame(7)) })
        }

    @Test
    fun `a frame from any unit is surfaced once`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val a = FakePeer("a")
            val b = FakePeer("b")
            mesh.addPeer("a", a)
            mesh.addPeer("b", b)
            mesh.connect()

            val received = ArrayList<ByteArray>()
            // Unconfined and undispatched, so the collector is subscribed before the first
            // frame is delivered. `incoming` has no replay — a walkie-talkie does not hold
            // what was said before you switched on — so a late subscriber sees nothing.
            val collector =
                CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                    .launch(start = CoroutineStart.UNDISPATCHED) {
                        mesh.incoming.collect { received += it }
                    }

            a.deliver(frame(1))
            b.deliver(frame(2))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(2, received.size)
        }

    /**
     * On a radio net one unit walking out of range must not silence the channel. An
     * exception propagating out of the send loop would do exactly that.
     */
    @Test
    fun `a peer that fails does not stop the others`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val good = FakePeer("good")
            val broken = FakePeer("broken", throwOnSend = true)
            mesh.addPeer("good", good)
            mesh.addPeer("broken", broken)
            mesh.connect()

            mesh.send(frame(3))

            assertEquals("the working peer still got it", 1, good.sent.size)
        }

    @Test
    fun `a disconnected peer is skipped rather than queued`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val up = FakePeer("up")
            val down = FakePeer("down")
            mesh.addPeer("up", up)
            mesh.addPeer("down", down)
            mesh.connect()
            down.drop()
            advanceUntilIdle()

            mesh.send(frame(4))

            assertEquals(1, up.sent.size)
            assertEquals(0, down.sent.size)
        }

    // ── the state of a net ───────────────────────────────────────────────────

    /** A walkie-talkie with one other unit on the channel is working. */
    @Test
    fun `the mesh is connected while any single unit is reachable`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val a = FakePeer("a")
            val b = FakePeer("b")
            mesh.addPeer("a", a)
            mesh.addPeer("b", b)
            mesh.connect()
            advanceUntilIdle()
            assertEquals(LinkState.CONNECTED, mesh.state.value)

            a.drop()
            advanceUntilIdle()
            assertEquals("one peer left is still a working channel", LinkState.CONNECTED, mesh.state.value)
            assertEquals(1, mesh.connectedCount)

            b.drop()
            advanceUntilIdle()
            assertEquals("all down is recoverable, not an error", LinkState.DEGRADED, mesh.state.value)
        }

    @Test
    fun `a mesh with no peers is idle rather than degraded`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertEquals(LinkState.IDLE, mesh.state.value)
            assertEquals(0, mesh.peerCount)
        }

    // ── the roster ───────────────────────────────────────────────────────────

    /** A duplicated peer would deliver every frame twice and show as two units. */
    @Test
    fun `adding the same peer twice replaces rather than duplicates it`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            mesh.addPeer("ravi", FakePeer("ravi"))
            val second = FakePeer("ravi")
            mesh.addPeer("ravi", second)
            mesh.connect()

            mesh.send(frame(5))

            assertEquals(1, mesh.peerCount)
            assertEquals(1, second.sent.size)
        }

    @Test
    fun `a removed peer stops receiving and stops being counted`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val gone = FakePeer("gone")
            mesh.addPeer("gone", gone)
            mesh.connect()
            advanceUntilIdle()

            mesh.removePeer("gone")
            advanceUntilIdle()
            mesh.send(frame(6))

            assertEquals(0, mesh.peerCount)
            assertEquals(0, gone.sent.size)
            assertEquals(LinkState.IDLE, mesh.state.value)
        }

    // ── sizing ───────────────────────────────────────────────────────────────

    /**
     * The smallest, because one frame goes to all of them. Sizing to the largest produces
     * a frame the narrowest peer cannot carry — failing for that unit only, only for long
     * messages, and only sometimes.
     */
    @Test
    fun `the mesh MTU is the narrowest peer`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            mesh.addPeer("wide", FakePeer("wide", mtu = 1024))
            mesh.addPeer("narrow", FakePeer("narrow", mtu = 185))
            assertEquals(185, mesh.mtu)
        }

    // ── roads: the operator's switch, and what an alert does to it ───────────

    /**
     * Routine traffic respects the switch. This is the whole feature: a demonstration
     * that wants one radio carrying everything, or a unit sparing a radio's battery.
     */
    @Test
    fun `routine traffic skips a road that is switched off`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val ble = FakePeer("ble")
            val wifi = FakePeer("wifi")
            mesh.addPeer(Road.BLE, ble)
            mesh.addPeer(Road.WIFI, wifi)
            mesh.connect()

            mesh.roads = setOf(Road.WIFI)
            mesh.send(frame(1))

            assertEquals("BLE is off", 0, ble.sent.size)
            assertEquals("Wi-Fi carries it", 1, wifi.sent.size)
        }

    /**
     * The reason the switch is safe to offer at all. A preference set on a quiet
     * afternoon must never be why an evacuation order stayed on one handset.
     */
    @Test
    fun `an urgent frame goes down every road that is up, switch or no switch`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val ble = FakePeer("ble")
            val wifi = FakePeer("wifi")
            mesh.addPeer(Road.BLE, ble)
            mesh.addPeer(Road.WIFI, wifi)
            mesh.connect()

            mesh.roads = setOf(Road.WIFI)
            mesh.send(frame(2), urgent = true)

            assertEquals(1, ble.sent.size)
            assertEquals(1, wifi.sent.size)
        }

    /** RFCOMM is many peers and one road: one switch covers every bonded handset. */
    @Test
    fun `switching RFCOMM off silences every bonded peer at once`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val ble = FakePeer("ble")
            val bonded = (1..3).map { FakePeer("unit$it") }
            mesh.addPeer(Road.BLE, ble)
            bonded.forEachIndexed { i, peer -> mesh.addPeer("AA:BB:CC:00:00:0$i", peer, road = Road.RFCOMM) }
            mesh.connect()

            mesh.roads = setOf(Road.BLE)
            mesh.send(frame(3))

            assertEquals(1, ble.sent.size)
            assertTrue("no bonded handset hears routine traffic", bonded.all { it.sent.isEmpty() })
            assertEquals(Road.RFCOMM, mesh.peerRoads["AA:BB:CC:00:00:01"])
        }

    /**
     * A selection that would send nothing is a configuration error, and the right
     * failure for a radio is to transmit anyway. Two shapes of it: a set naming only a
     * road that is down, and a set naming nothing that exists.
     */
    @Test
    fun `a switch that would leave nothing standing is set aside`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val ble = FakePeer("ble")
            val wifi = FakePeer("wifi")
            mesh.addPeer(Road.BLE, ble)
            mesh.addPeer(Road.WIFI, wifi)
            mesh.connect()
            wifi.drop()

            mesh.roads = setOf(Road.WIFI)
            mesh.send(frame(4))
            assertEquals("the only road on is down, so the one that is up carries it", 1, ble.sent.size)

            mesh.roads = emptySet()
            mesh.send(frame(5))
            assertEquals("an empty selection means everything", 2, ble.sent.size)
        }

    /** Switching a road off changes what is sent, never what is heard. */
    @Test
    fun `a road that is switched off is still received on`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val ble = FakePeer("ble")
            mesh.addPeer(Road.BLE, ble)
            mesh.connect()
            mesh.roads = setOf(Road.WIFI)

            val heard = ArrayList<ByteArray>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { mesh.incoming.collect { heard.add(it) } }
            ble.deliver(frame(6))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, heard.size)
            assertEquals(LinkState.CONNECTED, mesh.state.value)
        }

    @Test
    fun `metrics count what actually went out`() =
        runTest {
            val mesh = MeshLink(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            mesh.addPeer("a", FakePeer("a"))
            mesh.addPeer("b", FakePeer("b"))
            mesh.connect()

            mesh.send(frame(1))

            assertEquals("one frame to two peers is two transmissions", 2, mesh.metrics.value.framesSent)
            assertEquals(16, mesh.metrics.value.bytesSent)
        }
}

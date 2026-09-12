package org.itantra.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.itantra.proto.Aead
import org.itantra.proto.ClockSync
import org.itantra.proto.EpochCounter
import org.itantra.proto.Language
import org.itantra.proto.TemplateTable
import org.itantra.proto.Timing
import org.itantra.proto.TransportClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock-sync exchange over the real session path: sealed, authenticated, replay
 * checked, and told apart from a presence by its first byte. Task **W3.10**.
 */
class SessionTimingTest {
    private val key = ByteArray(Aead.KEY_BYTES) { (it * 3 + 5).toByte() }

    private class MemoryEpochs : EpochCounter.Store {
        var value: Long? = null

        override fun read(): Long? = value

        override fun write(epoch: Long) {
            value = epoch
        }
    }

    private class CapturingLink : Link {
        val sent = ArrayList<ByteArray>()
        override val mtu: Int = 244
        override val name: String = "ble"
        private val _state = MutableStateFlow(LinkState.CONNECTED)
        override val state: StateFlow<LinkState> = _state
        override val metrics: StateFlow<LinkMetrics> = MutableStateFlow(LinkMetrics())
        override val incoming: Flow<ByteArray> = emptyFlow()

        override suspend fun send(frame: ByteArray) = send(frame, urgent = false)

        override suspend fun send(
            frame: ByteArray,
            urgent: Boolean,
        ) {
            sent.add(frame)
        }

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit
    }

    private val profile = TemplateTable.of(profileId = 1, entries = mapOf(1 to mapOf(Language.HINDI to "मदद")))

    private fun session(
        link: Link,
        src: Int,
    ) = Session(
        link = link,
        key = key,
        localSrc = src,
        epochs = EpochCounter(MemoryEpochs()),
        templates = profile,
        transport = TransportClass.BLE,
        language = Language.HINDI,
    )

    @Test
    fun `a ping crosses sealed and the pong comes back to a synchronised offset`() =
        runTest {
            val link = CapturingLink()
            val a = session(link, src = 1)
            val b = session(link, src = 2)
            val sync = ClockSync()

            repeat(ClockSync.REQUIRED_SAMPLES) { round ->
                val t1 = 1_000_000_000L + round * 5_000_000_000L
                assertTrue(a.sendTiming(Timing.Ping(t1)))
                val ping = b.receive(link.sent.last(), nowMillis = 1L)
                assertTrue("b read $ping", ping is Session.Received.Timing && ping.timing == Timing.Ping(t1))
                // b's clock runs 250 ms ahead; the path takes 20 ms each way.
                val t2 = t1 + 20_000_000L + 250_000_000L
                assertTrue(b.sendTiming(Timing.Pong(t1, t2, t2 + 1_000_000L)))
                val pong = a.receive(link.sent.last(), nowMillis = 2L) as Session.Received.Timing
                assertEquals(2, pong.from)
                val p = pong.timing as Timing.Pong
                sync.add(ClockSync.Sample(p.t1, p.t2, p.t3, t4 = t1 + 41_000_000L))
            }
            assertTrue(sync.isSynchronised)
            assertEquals(250, sync.offsetMillis())
        }

    @Test
    fun `an audio receipt names the sender and sequence it answers, and is not a presence`() =
        runTest {
            val link = CapturingLink()
            val a = session(link, src = 1)
            val b = session(link, src = 2)
            val sent = a.send("मदद चाहिए", nowMillis = 1L)
            assertTrue(b.receive(link.sent.last(), nowMillis = 1L) is Session.Received.Message)

            val report = Timing.AudioReport(sender = 1, seq = sent.seq, rxNanos = 10L, audioNanos = 400_000_010L)
            assertTrue(b.sendTiming(report))
            val heard = a.receive(link.sent.last(), nowMillis = 2L)
            assertTrue("a read $heard", heard is Session.Received.Timing)
            assertEquals(report, (heard as Session.Received.Timing).timing)
        }

    @Test
    fun `a replayed pong is refused like any other sealed frame`() =
        runTest {
            val link = CapturingLink()
            val a = session(link, src = 1)
            val b = session(link, src = 2)
            b.sendTiming(Timing.Pong(1, 2, 3))
            val wire = link.sent.last()
            assertTrue(a.receive(wire, nowMillis = 1L) is Session.Received.Timing)
            val again = a.receive(wire, nowMillis = 2L)
            assertTrue("replay was $again", again is Session.Received.Dropped && again.reason == Session.Reason.REPLAYED)
        }
}

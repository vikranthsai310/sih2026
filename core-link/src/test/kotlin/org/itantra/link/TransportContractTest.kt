package org.itantra.link

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.StreamFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport contract, task **W6.8**.
 *
 * > **The same suite runs green against every transport.**
 *
 * Written once against the [Link] interface, because the value is not in testing any one
 * transport — it is in proving the application genuinely cannot tell them apart. A suite
 * written separately per transport would drift, and the divergence would surface as
 * "works on Bluetooth, not on Wi-Fi", which is exactly the failure this abstraction
 * exists to prevent.
 *
 * ## What runs where
 *
 * [LoopbackLink] is exercised in full here. `RfcommLink`, `BleLink` and `WifiLink` need
 * two radios and two handsets, so their conformance runs as an instrumented test on
 * device — the contract stated below is the specification those runs check.
 *
 * | # | Requirement |
 * | --- | --- |
 * | 1 | A frame sent arrives byte-identical |
 * | 2 | Exactly one complete frame per emission — never a partial, never two |
 * | 3 | Frames arrive in the order they were sent |
 * | 4 | The payload is never interpreted; arbitrary bytes survive |
 * | 5 | Metrics count what actually crossed the link |
 * | 6 | `disconnect` is idempotent and safe from any state |
 */
class TransportContractTest {
    private fun frame(
        seq: Int,
        payload: ByteArray = ByteArray(20) { it.toByte() },
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = Flags.FINAL,
        src = 2,
        keyId = 7,
        ttl = 3,
        payload = payload,
    )

    private fun pair(chunkSize: Int = 7): Pair<LoopbackLink, LoopbackLink> {
        val a = LoopbackLink("A", chunkSize = chunkSize)
        val b = LoopbackLink("B", chunkSize = chunkSize)
        LoopbackLink.pair(a, b)
        return a to b
    }

    // ── 1, 2 and 4: what arrives is what was sent ────────────────────────────

    @Test
    fun `a frame arrives byte-identical`() =
        runTest {
            val (a, b) = pair()
            val sent = frame(1).encode()

            val received = async { b.incoming.take(1).toList() }
            yield()
            a.send(sent)

            assertEquals(sent.toList(), received.await().single().toList())
        }

    /**
     * The requirement an implementation is most likely to get wrong. [LoopbackLink]
     * delivers one byte per emission here, so a consumer that ever saw a partial frame
     * would fail immediately.
     */
    @Test
    fun `exactly one complete frame is emitted per emission`() =
        runTest {
            val (a, b) = pair(chunkSize = 1)
            val sent = (1..5).map { frame(it) }

            val received = async { b.incoming.take(5).toList() }
            yield()
            sent.forEach { a.send(it.encode()) }

            val frames = received.await()
            assertEquals(5, frames.size)
            for (bytes in frames) {
                assertNotNull(
                    "every emission must be one complete, valid frame",
                    Frame.decode(bytes).orNull(),
                )
            }
        }

    @Test
    fun `frames arrive in the order they were sent`() =
        runTest {
            val (a, b) = pair(chunkSize = 1)
            val received = async { b.incoming.take(10).toList() }
            yield()
            (1..10).forEach { a.send(frame(it).encode()) }

            val order = received.await().mapNotNull { Frame.decode(it).orNull()?.seq }
            assertEquals((1..10).toList(), order)
        }

    /** The transport sees bytes. A payload that looks like a header must survive. */
    @Test
    fun `an arbitrary payload is not interpreted`() =
        runTest {
            val (a, b) = pair(chunkSize = 1)
            // Contains the frame sentinel, which a naive de-framer would resync on.
            val hostile = byteArrayOf(0xA1.toByte(), 0, 0xA1.toByte(), 0xFF.toByte(), 0x00)

            val received = async { b.incoming.take(1).toList() }
            yield()
            a.send(frame(1, hostile).encode())

            val decoded = Frame.decode(received.await().single()).orNull()!!
            assertEquals(hostile.toList(), decoded.payload.toList())
        }

    @Test
    fun `a payload of every byte value survives`() =
        runTest {
            val (a, b) = pair()
            val everyByte = ByteArray(256) { it.toByte() }

            val received = async { b.incoming.take(1).toList() }
            yield()
            a.send(frame(1, everyByte).encode())

            val decoded = Frame.decode(received.await().single()).orNull()!!
            assertEquals(everyByte.toList(), decoded.payload.toList())
        }

    // ── 5: metrics reflect reality ───────────────────────────────────────────

    @Test
    fun `metrics count what actually crossed the link`() =
        runTest {
            val (a, b) = pair()
            val bytes = frame(1).encode()

            val received = async { b.incoming.take(1).toList() }
            yield()
            a.send(bytes)
            received.await()

            assertEquals(1, a.metrics.value.framesSent)
            assertEquals(bytes.size.toLong(), a.metrics.value.bytesSent)
            assertTrue(
                "the compression figure must follow the bytes actually sent",
                a.metrics.value.compressionVersusRawAudio > 1.0,
            )
        }

    // ── 6: lifecycle ─────────────────────────────────────────────────────────

    @Test
    fun `disconnect is safe from any state and idempotent`() =
        runTest {
            val (a, _) = pair()
            a.disconnect()
            a.connect()
            a.disconnect()
            a.disconnect()
            assertEquals(LinkState.IDLE, a.state.value)
        }

    @Test
    fun `every link reports a name and a usable MTU`() {
        val (a, b) = pair()
        for (link in listOf(a, b)) {
            assertTrue(link.name.isNotBlank())
            assertTrue("an MTU must be usable", link.mtu > Frame.MIN_WIRE_SIZE)
        }
    }

    // ── the de-framing contract every stream transport shares ────────────────

    /**
     * `RfcommLink` and `WifiLink` both read into a buffer and hand it to [StreamFramer],
     * so the shared piece is worth proving directly: a frame split across reads is
     * reassembled, and two frames in one read are separated.
     */
    @Test
    fun `the shared de-framer handles split and coalesced reads`() {
        val framer = StreamFramer()
        val a = frame(1).encode()
        val b = frame(2).encode()

        assertTrue(framer.offer(a, 0, 4).isEmpty())
        assertTrue(framer.offer(a, 4, 6).isEmpty())
        val fromSplit = framer.offer(a, 10, a.size - 10)
        assertEquals(1, fromSplit.size)
        assertEquals(1, fromSplit[0].seq)

        val both = a + b
        val fromCoalesced = framer.offer(both, 0, both.size)
        assertEquals(listOf(1, 2), fromCoalesced.map { it.seq })
    }

    /**
     * The MTU each transport reports, so the fragmenter is sized correctly. BLE is the
     * constraint: 20 bytes usable before negotiation, ~244 after.
     */
    @Test
    fun `the declared MTUs match what each medium can carry`() {
        assertEquals("BLE before negotiation", 20, BleLink.DEFAULT_USABLE_MTU)
        assertEquals("BLE after negotiation", 244, BleLink.MAX_PAYLOAD)
        assertEquals("Wi-Fi frame port", 38_173, WifiLink.FRAME_PORT)
        assertEquals("Wi-Fi discovery port", 38_174, WifiLink.DISCOVERY_PORT)
    }
}

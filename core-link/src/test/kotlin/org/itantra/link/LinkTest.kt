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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    /** No jitter, so the schedule itself can be asserted. */
    private fun fixed() = Backoff(jitterFraction = 0.0, random = { 0.0 })

    @Test
    fun `delays double from one second and cap at thirty`() {
        val b = fixed()
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { b.nextDelayMillis() },
        )
    }

    @Test
    fun `a successful frame resets the schedule`() {
        val b = fixed()
        repeat(5) { b.nextDelayMillis() }
        b.reset()
        assertEquals(1_000L, b.nextDelayMillis())
    }

    /**
     * Without jitter several units that lost the same link retry in lockstep and
     * collide repeatedly. With it they spread out.
     */
    @Test
    fun `jitter spreads retries around the base delay`() {
        val seen = mutableSetOf<Long>()
        repeat(200) {
            val b = Backoff()
            repeat(3) { b.nextDelayMillis() }
            seen += b.nextDelayMillis()
        }
        assertTrue("jitter produced only ${seen.size} distinct delays", seen.size > 20)
        assertTrue("jitter should stay near the 8 s base", seen.all { it in 6_000..10_000 })
    }
}

class LoopbackLinkTest {
    private fun frame(seq: Int) =
        Frame(
            type = MessageType.TEXT,
            language = Language.HINDI,
            seq = seq,
            flags = Flags.FINAL or Flags.PACKED,
            src = 2,
            keyId = 7,
            ttl = 3,
            payload = ByteArray(32) { (it + seq).toByte() },
        )

    @Test
    fun `a frame sent on one link arrives whole on the other`() =
        runTest {
            val a = LoopbackLink("A")
            val b = LoopbackLink("B")
            LoopbackLink.pair(a, b)

            val received = async { b.incoming.take(1).toList() }
            yield()
            a.send(frame(1).encode())

            assertEquals(listOf(frame(1)), received.await().map { Frame.decode(it).orNull() })
        }

    /**
     * The link delivers seven bytes at a time, so a consumer that assumes one write
     * equals one read fails here rather than in the field. Risk T-08.
     */
    @Test
    fun `frames split across deliveries are still reassembled whole`() =
        runTest {
            val a = LoopbackLink("A", chunkSize = 1)
            val b = LoopbackLink("B", chunkSize = 1)
            LoopbackLink.pair(a, b)

            val sent = (1..5).map { frame(it) }
            val received = async { b.incoming.take(5).toList() }
            yield()
            sent.forEach { a.send(it.encode()) }

            assertEquals(sent, received.await().map { Frame.decode(it).orNull() })
        }

    @Test
    fun `metrics count frames and bytes in both directions`() =
        runTest {
            val a = LoopbackLink("A")
            val b = LoopbackLink("B")
            LoopbackLink.pair(a, b)

            val received = async { b.incoming.take(1).toList() }
            yield()
            val wire = frame(9).encode()
            a.send(wire)
            received.await()

            assertEquals(1L, a.metrics.value.framesSent)
            assertEquals(wire.size.toLong(), a.metrics.value.bytesSent)
            assertEquals(1L, b.metrics.value.framesReceived)
        }

    /** The figure demonstration step 3 points at: 44 bytes against 96 000. */
    @Test
    fun `the byte counter reports the compression ratio`() =
        runTest {
            val a = LoopbackLink("A")
            val b = LoopbackLink("B")
            LoopbackLink.pair(a, b)
            a.send(frame(1).encode()) // 44 bytes on the wire

            assertEquals(44L, a.metrics.value.bytesSent)
            assertEquals(2_182.0, a.metrics.value.compressionVersusRawAudio, 1.0)
        }

    @Test
    fun `an unpaired link reports degraded rather than error`() =
        runTest {
            val a = LoopbackLink("A")
            a.connect()
            assertEquals(
                "an unpaired link is recoverable, so DEGRADED not ERROR",
                LinkState.DEGRADED,
                a.state.value,
            )
        }

    @Test
    fun `a dropped link counts losses without throwing`() =
        runTest {
            val a = LoopbackLink("A")
            val b = LoopbackLink("B")
            LoopbackLink.pair(a, b)
            a.dropEverything = true

            repeat(3) { a.send(frame(it).encode()) }

            assertEquals(3L, a.metrics.value.framesSent)
            assertEquals(3L, a.metrics.value.framesLost)
            assertEquals(0L, b.metrics.value.framesReceived)
        }
}

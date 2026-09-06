package org.itantra.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.itantra.proto.Aead
import org.itantra.proto.EpochCounter
import org.itantra.proto.Language
import org.itantra.proto.TemplateTable
import org.itantra.proto.TransportClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two handsets that were never started together still hear each other.
 *
 * ## The bug this is about
 *
 * The AEAD nonce is `EPOCH ‖ SRC ‖ SEQ ‖ padding` and the epoch is **not on the wire** —
 * eight bytes saved on a forty-four byte frame. A receiver must therefore know which epoch
 * the *sender* is using, and [Session] simply assumed it was its own:
 *
 * ```kotlin
 * private fun peerEpoch(frame: Frame): Long = epoch
 * ```
 *
 * That holds only when both units have been started the same number of times, which is
 * true of a loopback test and of nothing else. On real handsets it produced a channel that
 * transmitted perfectly and dropped every frame it received:
 *
 * ```
 * W itantra-net: dropped 66 B: NOT_AUTHENTIC src 54
 * ```
 *
 * `PROTOCOL.md` §9 names HEARTBEAT as the carrier of EPOCH, and in the same breath requires
 * HEARTBEAT to be authenticated like any other frame — which cannot be done without already
 * knowing the epoch. The circularity is why it was never implemented.
 *
 * ## Why searching is a sound answer and not a hack
 *
 * The tag is the oracle. Only the true epoch produces a nonce whose GCM tag verifies under
 * the shared key, so a candidate that opens the frame **is** the sender's epoch — the same
 * proof an ordinary receive relies on, applied once per peer. Every wrong candidate is
 * rejected exactly as a forgery would be, so the search weakens nothing; it costs at most
 * [Session.EPOCH_SEARCH] tag checks on first contact, and none afterwards, because the
 * replay window remembers the answer.
 *
 * These tests are here because the loopback suite structurally cannot catch this: it gives
 * both sessions a fresh counter, so both epochs are 1 and the assumption holds.
 */
class EpochDiscoveryTest {
    private val key = ByteArray(Aead.KEY_BYTES) { (it * 7 + 1).toByte() }

    /** A counter that has already been used [runs] times, as on a handset restarted that often. */
    private class Restarted(private var value: Long?) : EpochCounter.Store {
        override fun read(): Long? = value

        override fun write(epoch: Long) {
            value = epoch
        }
    }

    private val profile =
        TemplateTable.of(
            profileId = 1,
            entries = mapOf(1 to mapOf(Language.HINDI to "मदद चाहिए")),
        )

    private fun session(
        link: Link,
        src: Int,
        priorRuns: Long?,
    ) = Session(
        link = link,
        key = key,
        localSrc = src,
        epochs = EpochCounter(Restarted(priorRuns)),
        templates = profile,
        transport = TransportClass.BLE,
        language = Language.HINDI,
    )

    private class CapturingLink(override val mtu: Int = 244) : Link {
        val sent = ArrayList<ByteArray>()
        override val name: String = "ble"
        private val _state = MutableStateFlow(LinkState.IDLE)
        private val _metrics = MutableStateFlow(LinkMetrics())

        override val state: StateFlow<LinkState> = _state
        override val metrics: StateFlow<LinkMetrics> = _metrics
        override val incoming: Flow<ByteArray> = emptyFlow()

        override suspend fun send(frame: ByteArray) {
            sent.add(frame)
        }

        override suspend fun connect() {
            _state.value = LinkState.CONNECTED
        }

        override suspend fun disconnect() {
            _state.value = LinkState.IDLE
        }
    }

    /** The handset case: one phone has been opened forty times, the other twice. */
    @Test
    fun `a frame from a peer on a different epoch is accepted`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 0x54, priorRuns = 40)
            val receiver = session(link, src = 0x11, priorRuns = 1)

            sender.send("यहाँ तीन घायल हैं")
            val received = receiver.receive(link.sent.single())

            assertTrue(received.toString(), received is Session.Received.Message)
            assertEquals("यहाँ तीन घायल हैं", (received as Session.Received.Message).text)
            assertEquals(0x54, received.from)
        }

    /** A first-ever start against a long-running peer: the gap runs the other way. */
    @Test
    fun `discovery works when the receiver is the newer handset`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 0x54, priorRuns = 200)
            val receiver = session(link, src = 0x11, priorRuns = null)

            sender.send("मदद चाहिए")
            val received = receiver.receive(link.sent.single())

            assertTrue(received.toString(), received is Session.Received.Message)
            assertEquals("मदद चाहिए", (received as Session.Received.Message).text)
        }

    /**
     * The search must not become a way in. A frame under a foreign key is still rejected,
     * whatever epoch it claims, because every candidate fails the tag check.
     */
    @Test
    fun `a frame sealed under a different key is still rejected`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val stranger =
                Session(
                    link = link,
                    key = ByteArray(Aead.KEY_BYTES) { (it * 13 + 5).toByte() },
                    localSrc = 0x54,
                    epochs = EpochCounter(Restarted(40)),
                    templates = profile,
                    transport = TransportClass.BLE,
                    language = Language.HINDI,
                )
            val receiver = session(link, src = 0x11, priorRuns = 1)

            stranger.send("मदद चाहिए")
            val received = receiver.receive(link.sent.single())

            assertTrue(received.toString(), received is Session.Received.Dropped)
        }

    /**
     * The cost is paid once. After the first frame the replay window knows the peer's
     * epoch, so the second frame takes the ordinary path with no search at all.
     */
    @Test
    fun `the second frame from the same peer needs no search`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 0x54, priorRuns = 300)
            val receiver = session(link, src = 0x11, priorRuns = 1)

            sender.send("मदद चाहिए")
            sender.send("यहाँ तीन घायल हैं")
            assertEquals(2, link.sent.size)

            assertTrue(receiver.receive(link.sent[0]) is Session.Received.Message)
            val second = receiver.receive(link.sent[1])
            assertTrue(second.toString(), second is Session.Received.Message)
            assertEquals("यहाँ तीन घायल हैं", (second as Session.Received.Message).text)
        }
}

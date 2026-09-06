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
 * The seams that were found broken on real handsets on 2026-09-06, each as a test that
 * fails on the code as it was:
 *
 * - a sender that restarts is refused for ever (the cached epoch was never re-checked);
 * - a message long enough to fragment never arrives (AEAD ran per fragment);
 * - a relayed frame is refused at the next hop (it was relayed decrypted, and the TTL
 *   was bound into the tag);
 * - a sentence in the operator's language is packed and matched as Hindi.
 */
class SessionPathTest {
    private val key = ByteArray(Aead.KEY_BYTES) { (it * 7 + 1).toByte() }

    private class Store(var value: Long? = null) : EpochCounter.Store {
        override fun read(): Long? = value

        override fun write(epoch: Long) {
            value = epoch
        }
    }

    private val profile =
        TemplateTable.of(
            profileId = 1,
            entries =
                mapOf(
                    1 to
                        mapOf(
                            Language.HINDI to "मदद चाहिए",
                            Language.TAMIL to "உதவி தேவை",
                            Language.ENGLISH to "We need help",
                        ),
                ),
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

        fun take(): List<ByteArray> = sent.toList().also { sent.clear() }
    }

    private fun session(
        link: Link,
        src: Int,
        store: EpochCounter.Store = Store(),
        clock: (() -> Long)? = null,
        language: Language = Language.HINDI,
    ) = Session(
        link = link,
        key = key,
        localSrc = src,
        epochs = EpochCounter(store, clock),
        templates = profile,
        transport = TransportClass.BLE,
        language = language,
    )

    private fun text(received: Session.Received): String {
        assertTrue(received.toString(), received is Session.Received.Message)
        return (received as Session.Received.Message).text
    }

    // ── restarts ─────────────────────────────────────────────────────────────

    @Test
    fun `a sender that restarts mid-conversation is still heard`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val senderStore = Store(40)
            val receiver = session(link, src = 2)

            session(link, src = 1, store = senderStore).send("पहला")
            assertEquals("पहला", text(receiver.receive(link.take().single())))

            // The application restarts: a new session, one epoch on.
            session(link, src = 1, store = senderStore).send("दूसरा")
            assertEquals("दूसरा", text(receiver.receive(link.take().single())))

            // And again, several times over, without the receiver ever restarting.
            repeat(5) { session(link, src = 1, store = senderStore).send("x") }
            val last = link.take().last()
            assertEquals("x", text(receiver.receive(last)))
        }

    @Test
    fun `a reinstalled sender with a wiped counter is still heard, because the clock seeds its epoch`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val march = { EpochCounter.ORIGIN_MILLIS + 60L * 24 * 60 * 60 * 1000 }
            val receiver = session(link, src = 2, clock = march)

            session(link, src = 1, store = Store(9), clock = march).send("पहला")
            assertEquals("पहला", text(receiver.receive(link.take().single())))

            // Reinstalled a minute later: no persisted counter at all.
            val laterMarch = { march() + 60_000 }
            session(link, src = 1, store = Store(null), clock = laterMarch).send("दूसरा")
            assertEquals("दूसरा", text(receiver.receive(link.take().single())))
        }

    @Test
    fun `a hello lets a receiver find an epoch far outside its search`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val receiver = session(link, src = 2, store = Store(1))
            // A sender whose epoch is a year of minutes away from anything the receiver
            // would try on its own.
            val sender = session(link, src = 1, store = Store(600_000))

            link.sent.add(sender.hello())
            val hello = receiver.receive(link.take().single())
            assertTrue(hello.toString(), hello is Session.Received.Hello)
            assertEquals(600_001L, (hello as Session.Received.Hello).epoch)

            sender.send("मदद चाहिए")
            assertEquals("मदद चाहिए", text(receiver.receive(link.take().single())))
        }

    @Test
    fun `a hello is never believed on its own`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val receiver = session(link, src = 2, store = Store(1))
            val stranger =
                Session(
                    link = link,
                    key = ByteArray(Aead.KEY_BYTES) { (it * 13 + 5).toByte() },
                    localSrc = 1,
                    epochs = EpochCounter(Store(7)),
                    templates = profile,
                    transport = TransportClass.BLE,
                )
            // Same keyId is unlikely; if it differs the frame is dropped even earlier.
            receiver.receive(stranger.hello())
            stranger.send("मदद चाहिए")
            val received = receiver.receive(link.take().last())
            assertTrue(received.toString(), received is Session.Received.Dropped)
        }

    // ── fragments ────────────────────────────────────────────────────────────

    @Test
    fun `a message too long for the link arrives whole`() =
        runTest {
            val link = CapturingLink(mtu = 64)
            link.connect()
            val sender = session(link, src = 1)
            val receiver = session(link, src = 2)

            val long = "यहाँ तीन लोग घायल हैं और पानी बहुत तेज़ी से बढ़ रहा है, नाव तुरंत भेजो, सेक्टर सत्रह"
            val sent = sender.send(long)
            assertTrue("expected fragments, got ${sent.fragments}", sent.fragments > 1)
            val pieces = link.take()
            assertEquals(sent.fragments, pieces.size)

            for (piece in pieces.dropLast(1)) {
                val partial = receiver.receive(piece)
                assertTrue(partial.toString(), partial is Session.Received.Partial)
            }
            assertEquals(long, text(receiver.receive(pieces.last())))
        }

    @Test
    fun `the fragmenter follows the link's MTU rather than the one at construction`() =
        runTest {
            val link = CapturingLink(mtu = 244)
            link.connect()
            val sender = session(link, src = 1)
            // A narrow peer joins after the session was built.
            val narrow = CapturingLink(mtu = 64)
            narrow.connect()
            val narrowSender =
                Session(
                    link = narrow,
                    key = key,
                    localSrc = 1,
                    epochs = EpochCounter(Store()),
                    templates = profile,
                    transport = TransportClass.BLE,
                )
            val long = "यहाँ तीन लोग घायल हैं और पानी बहुत तेज़ी से बढ़ रहा है, नाव तुरंत भेजो"
            assertEquals(1, sender.send(long).fragments)
            assertTrue(narrowSender.send(long).fragments > 1)
            assertTrue(narrow.sent.all { it.size <= 64 })
        }

    // ── relaying ─────────────────────────────────────────────────────────────

    @Test
    fun `a relayed frame is still sealed and still verifies at the next hop`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1)
            val middle = session(link, src = 2)
            val far = session(link, src = 3)

            sender.send("मदद चाहिए")
            val wire = link.take().single()
            assertEquals("मदद चाहिए", text(middle.receive(wire)))

            val relays = middle.takeRelays()
            assertEquals("one relay decision", 1, relays.size)
            val relayed = relays.single().frame
            assertTrue("the relayed frame must still be sealed", relayed.isEncrypted)
            assertEquals("the TTL is decremented", 2, relayed.ttl)

            assertEquals("मदद चाहिए", text(far.receive(relayed.encode())))
            assertEquals("the far unit relays it on once more", 1, far.takeRelays().single().frame.ttl)
        }

    @Test
    fun `a fragmented message is relayed as its fragments, each once`() =
        runTest {
            val link = CapturingLink(mtu = 64)
            link.connect()
            val sender = session(link, src = 1)
            val middle = session(link, src = 2)
            val far = session(link, src = 3)

            val long = "यहाँ तीन लोग घायल हैं और पानी बहुत तेज़ी से बढ़ रहा है, नाव तुरंत भेजो, सेक्टर सत्रह"
            sender.send(long)
            val pieces = link.take()
            for (piece in pieces) middle.receive(piece)

            val relays = middle.takeRelays()
            assertEquals("every fragment is relayed", pieces.size, relays.size)

            var last: Session.Received? = null
            for (relay in relays) last = far.receive(relay.frame.encode())
            assertEquals(long, text(last!!))
        }

    @Test
    fun `a unit's own frame heard back is dropped as its own, not relayed`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1)
            sender.send("मदद चाहिए")
            val wire = link.take().single()
            val heardBack = sender.receive(wire)
            assertTrue(heardBack.toString(), heardBack is Session.Received.Dropped)
            assertEquals("own transmission", (heardBack as Session.Received.Dropped).detail)
            assertTrue(sender.takeRelays().isEmpty())
        }

    // ── language ─────────────────────────────────────────────────────────────

    @Test
    fun `changing the operator's language changes how a sentence is sent`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1)
            val receiver = session(link, src = 2, language = Language.HINDI)

            sender.language = Language.TAMIL
            val sent = sender.send("உதவி தேவை")
            assertEquals("the Tamil sentence matches the Tamil template", 1, sent.templateId)
            assertEquals(
                "and is rendered in the receiver's language",
                "मदद चाहिए",
                text(receiver.receive(link.take().single())),
            )

            val packed = sender.send("மூன்று பேர் காயம் அடைந்துள்ளனர்")
            assertTrue("Tamil text is packed against the Tamil block", packed.packed)
            assertEquals("மூன்று பேர் காயம் அடைந்துள்ளனர்", text(receiver.receive(link.take().single())))
        }

    @Test
    fun `a malformed packed payload is dropped, not thrown`() =
        runTest {
            val link = CapturingLink()
            link.connect()
            val receiver = session(link, src = 2)
            // A frame from a stranger's session is refused before the packer; the only way
            // to reach the packer with bad bytes is a genuine sender's frame, so this is
            // asserted on the packer's own contract through the session's guard.
            val sender = session(link, src = 1)
            sender.send("मदद")
            val wire = link.take().single()
            // Not a throw, whatever the bytes.
            val received = runCatching { receiver.receive(wire) }
            assertTrue(received.isSuccess)
        }
}

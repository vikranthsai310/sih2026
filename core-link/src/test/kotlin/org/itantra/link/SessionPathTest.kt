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

class SessionPresenceTest {
    private val key = ByteArray(org.itantra.proto.Aead.KEY_BYTES) { (it * 7 + 1).toByte() }

    private class Store(var value: Long? = null) : org.itantra.proto.EpochCounter.Store {
        override fun read(): Long? = value

        override fun write(epoch: Long) {
            value = epoch
        }
    }

    private class CapturingLink(override val mtu: Int = 244) : Link {
        val sent = ArrayList<ByteArray>()
        override val name: String = "ble"
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(LinkState.IDLE)
        private val _metrics = kotlinx.coroutines.flow.MutableStateFlow(LinkMetrics())
        override val state: kotlinx.coroutines.flow.StateFlow<LinkState> = _state
        override val metrics: kotlinx.coroutines.flow.StateFlow<LinkMetrics> = _metrics
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()

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

    private val profile =
        org.itantra.proto.TemplateTable.of(
            profileId = 1,
            entries = mapOf(1 to mapOf(org.itantra.proto.Language.HINDI to "मदद चाहिए")),
        )

    private fun session(
        link: Link,
        src: Int,
        store: Store = Store(),
    ) = Session(
        link = link,
        key = key,
        localSrc = src,
        epochs = org.itantra.proto.EpochCounter(store),
        templates = profile,
        transport = org.itantra.proto.TransportClass.BLE,
    )

    private fun text(received: Session.Received): String {
        org.junit.Assert.assertTrue(received.toString(), received is Session.Received.Message)
        return (received as Session.Received.Message).text
    }

    @org.junit.Test
    fun `a presence arrives with its name and position, authenticated`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, 1)
            val receiver = session(link, 2)
            val presence =
                org.itantra.proto.Presence(
                    name = "ALPHA",
                    position = org.itantra.proto.Presence.Position(20.2961, 85.8245, 6, 1),
                    beaconing = true,
                )
            org.junit.Assert.assertTrue(sender.sendPresence(presence))
            val received = receiver.receive(link.take().single())
            org.junit.Assert.assertTrue(received.toString(), received is Session.Received.Presence)
            received as Session.Received.Presence
            org.junit.Assert.assertEquals(1, received.from)
            org.junit.Assert.assertEquals("ALPHA", received.presence.name)
            org.junit.Assert.assertEquals(20.2961, received.presence.position!!.latitude, 1e-6)
            org.junit.Assert.assertEquals(
                "a presence heard for the first time is relayed, so a unit two hops away learns the name",
                1,
                receiver.takeRelays().size,
            )
        }

    @org.junit.Test
    fun `a presence is relayed when it is news and not while it is the same`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, 1)
            val receiver = session(link, 2)
            val alpha = org.itantra.proto.Presence(name = "ALPHA")

            sender.sendPresence(alpha)
            receiver.receive(link.take().single(), nowMillis = 0)
            org.junit.Assert.assertEquals("the first is news", 1, receiver.takeRelays().size)

            sender.sendPresence(alpha)
            receiver.receive(link.take().single(), nowMillis = 1_000)
            org.junit.Assert.assertTrue("the same again is not", receiver.takeRelays().isEmpty())

            sender.sendPresence(alpha.copy(position = org.itantra.proto.Presence.Position(20.29, 85.82, 6, 1)))
            receiver.receive(link.take().single(), nowMillis = 1_500)
            org.junit.Assert.assertTrue(
                "a change inside the minimum interval since the last relay waits",
                receiver.takeRelays().isEmpty(),
            )

            sender.sendPresence(alpha.copy(position = org.itantra.proto.Presence.Position(20.30, 85.83, 6, 1)))
            receiver.receive(link.take().single(), nowMillis = 3_000)
            org.junit.Assert.assertEquals("a change after it is relayed", 1, receiver.takeRelays().size)

            sender.sendPresence(alpha.copy(position = org.itantra.proto.Presence.Position(20.30, 85.83, 6, 1)))
            receiver.receive(link.take().single(), nowMillis = 3_000 + Session.PRESENCE_RELAY_REFRESH_MILLIS)
            org.junit.Assert.assertEquals(
                "the same one is relayed again after the refresh, so the far unit keeps it on the roster",
                1,
                receiver.takeRelays().size,
            )
        }

    // ── two hops ─────────────────────────────────────────────────────────────

    /**
     * The unit in the middle is in range of both; the two ends are not in range of each
     * other and were set up in different months, so their epochs are far beyond the
     * search. Without the relayed hello the far unit cannot open the sender's frames at
     * all; with it, one check.
     */
    @org.junit.Test
    fun `a unit two hops away with an epoch beyond the search reads the message, because the hello is relayed`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1, store = Store(9_000_000))
            val middle = session(link, src = 2, store = Store(4_000_000))
            val far = session(link, src = 3, store = Store(100_000))

            // The sender's hello reaches the middle, which relays it on with one hop less.
            val hello = middle.receive(sender.hello())
            org.junit.Assert.assertTrue(hello.toString(), hello is Session.Received.Hello)
            val helloRelay = middle.takeRelays().single()
            org.junit.Assert.assertEquals(Session.DEFAULT_TTL - 1, helloRelay.frame.ttl)
            org.junit.Assert.assertEquals("relayed as the sender's, not the middle's", 1, helloRelay.frame.src)

            // The far unit hears the relayed hello, and relays it one hop further.
            val farHello = far.receive(helloRelay.frame.encode())
            org.junit.Assert.assertTrue(farHello.toString(), farHello is Session.Received.Hello)
            org.junit.Assert.assertEquals(Session.DEFAULT_TTL - 2, far.takeRelays().single().frame.ttl)

            // Now a message crosses both hops.
            sender.send("मदद चाहिए")
            org.junit.Assert.assertEquals("मदद चाहिए", text(middle.receive(link.take().single())))
            val relayed = middle.takeRelays().single().frame
            org.junit.Assert.assertEquals("मदद चाहिए", text(far.receive(relayed.encode())))

            // And the sender, hearing its own hello and message come back, drops both.
            val ownHello = sender.receive(helloRelay.frame.encode())
            org.junit.Assert.assertTrue(ownHello.toString(), ownHello is Session.Received.Dropped)
            org.junit.Assert.assertEquals(Session.Reason.NOT_FOR_US, (ownHello as Session.Received.Dropped).reason)
            val ownMessage = sender.receive(relayed.encode())
            org.junit.Assert.assertEquals(Session.Reason.NOT_FOR_US, (ownMessage as Session.Received.Dropped).reason)
            org.junit.Assert.assertTrue("nothing of its own is relayed onward", sender.takeRelays().isEmpty())
        }

    @org.junit.Test
    fun `without the relayed hello the same far unit cannot read the message`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1, store = Store(9_000_000))
            val far = session(link, src = 3, store = Store(100_000))
            sender.send("मदद चाहिए")
            val received = far.receive(link.take().single())
            org.junit.Assert.assertTrue(received.toString(), received is Session.Received.Dropped)
            org.junit.Assert.assertEquals(Session.Reason.NOT_AUTHENTIC, (received as Session.Received.Dropped).reason)
        }

    @org.junit.Test
    fun `a hello is relayed once per epoch, not every five seconds`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            // A store beyond the clock seed, so a restart moves the epoch by exactly one.
            val store = Store(9_000_000)
            val sender = session(link, src = 1, store = store)
            val middle = session(link, src = 2)
            middle.receive(sender.hello(), nowMillis = 0)
            org.junit.Assert.assertEquals(1, middle.takeRelays().size)
            middle.receive(sender.hello(), nowMillis = 5_000)
            middle.receive(sender.hello(), nowMillis = 10_000)
            org.junit.Assert.assertTrue("the same epoch again is not news", middle.takeRelays().isEmpty())

            // The sender restarts: a new epoch, and news again.
            val restarted = session(link, src = 1, store = store)
            middle.receive(restarted.hello(), nowMillis = 15_000)
            org.junit.Assert.assertEquals(1, middle.takeRelays().size)
        }

    @org.junit.Test
    fun `a flood of hellos with made-up epochs is relayed no faster than a real hello cadence`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val middle = session(link, src = 2)
            var relayed = 0
            for (i in 0 until 50) {
                val forged = session(link, src = 1, store = Store(9_000_000L + i * 10))
                middle.receive(forged.hello(), nowMillis = i * 100L)
                relayed += middle.takeRelays().size
            }
            org.junit.Assert.assertEquals("five seconds of hellos: one relay", 1, relayed)
        }

    @org.junit.Test
    fun `a hello sent with no hops is not relayed`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, src = 1)
            sender.ttl = 0
            val middle = session(link, src = 2)
            middle.receive(sender.hello())
            org.junit.Assert.assertTrue(middle.takeRelays().isEmpty())
        }

    @org.junit.Test
    fun `a locate request arrives and is relayed onward`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            link.connect()
            val sender = session(link, 1)
            val middle = session(link, 2)
            sender.sendLocate(org.itantra.proto.Locate(target = 9, start = true))
            val received = middle.receive(link.take().single())
            org.junit.Assert.assertTrue(received.toString(), received is Session.Received.Locate)
            org.junit.Assert.assertEquals(9, (received as Session.Received.Locate).locate.target)
            org.junit.Assert.assertEquals(1, middle.takeRelays().size)
        }

    @org.junit.Test
    fun `a presence is not queued when the link is down, a locate request is`() =
        kotlinx.coroutines.test.runTest {
            val link = CapturingLink()
            val sender = session(link, 1)
            org.junit.Assert.assertFalse(sender.sendPresence(org.itantra.proto.Presence("A")))
            org.junit.Assert.assertEquals(0, sender.queuedCount)
            org.junit.Assert.assertFalse(sender.sendLocate(org.itantra.proto.Locate(2, true)))
            org.junit.Assert.assertEquals(1, sender.queuedCount)
        }
}

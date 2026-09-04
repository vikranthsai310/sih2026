package org.itantra.link

import kotlinx.coroutines.test.runTest
import org.itantra.proto.Aead
import org.itantra.proto.EpochCounter
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.TemplateTable
import org.itantra.proto.TransportClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path, sender to receiver. Tasks **W3.9**, **W2.16**, **W2.18**.
 *
 * ## Why these tests are different from the ones underneath them
 *
 * Every component here has its own suite and all of them pass. What none of those suites
 * can see is whether the components were ever **joined** — whether the epoch advances,
 * whether a tag length is ever chosen, whether the replay window is consulted before or
 * after the frame is authenticated. Each of those is a security property the documents
 * claim, each is invisible to a unit test of the part, and each was in fact absent until
 * [Session] was written.
 *
 * So these tests are deliberately about the seams: two sessions, a loopback between them,
 * and assertions about the properties that only exist when the parts are connected.
 */
class SessionTest {
    private val key = ByteArray(Aead.KEY_BYTES) { (it * 7 + 1).toByte() }

    /** An epoch store that survives only as long as the test, which is the point. */
    private class MemoryEpochs : EpochCounter.Store {
        var value: Long? = null

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

    private fun session(
        link: Link,
        src: Int,
        epochStore: EpochCounter.Store = MemoryEpochs(),
        language: Language = Language.HINDI,
        transport: TransportClass = TransportClass.BLE,
    ) = Session(
        link = link,
        key = key,
        localSrc = src,
        epochs = EpochCounter(epochStore),
        templates = profile,
        transport = transport,
        language = language,
    )

    /** A pair of sessions over one loopback, so a frame sent by one is read by the other. */
    private suspend fun pair(
        senderLanguage: Language = Language.HINDI,
        receiverLanguage: Language = Language.HINDI,
        transport: TransportClass = TransportClass.BLE,
    ): Triple<Session, Session, CapturingLink> {
        val link = CapturingLink()
        link.connect()
        return Triple(
            session(link, src = 1, language = senderLanguage, transport = transport),
            session(link, src = 2, language = receiverLanguage, transport = transport),
            link,
        )
    }

    /**
     * Records what was sent instead of delivering it, so a test can hand the exact bytes
     * to the receiving session and to nothing else.
     *
     * Written out rather than delegating to [LoopbackLink] because the loopback only
     * reports `CONNECTED` when it has a peer, and half these tests are about what a
     * session does when the link is **down**.
     */
    private class CapturingLink(override val mtu: Int = 244) : Link {
        val sent = ArrayList<ByteArray>()

        override val name: String = "ble"
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(LinkState.IDLE)
        private val _metrics = kotlinx.coroutines.flow.MutableStateFlow(LinkMetrics())

        override val state: kotlinx.coroutines.flow.StateFlow<LinkState> = _state
        override val metrics: kotlinx.coroutines.flow.StateFlow<LinkMetrics> = _metrics
        override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> =
            kotlinx.coroutines.flow.emptyFlow()

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

    // ── the path works ───────────────────────────────────────────────────────

    @Test
    fun `a sentence sent by one session is read by the other`() =
        runTest {
            val (sender, receiver, link) = pair()

            sender.send("यहाँ तीन घायल हैं")
            assertEquals(1, link.sent.size)

            val received = receiver.receive(link.sent.single())
            assertTrue(received.toString(), received is Session.Received.Message)
            assertEquals("यहाँ तीन घायल हैं", (received as Session.Received.Message).text)
            assertEquals(1, received.from)
        }

    /**
     * The cross-language property, end to end rather than as a table lookup: the sender
     * chose a byte and the receiver chose the words.
     */
    @Test
    fun `a template sent in Hindi is read in Tamil`() =
        runTest {
            val (sender, receiver, link) =
                pair(
                    senderLanguage = Language.HINDI,
                    receiverLanguage = Language.TAMIL,
                )

            val sent = sender.send("मदद चाहिए")
            assertEquals("the sentence is in the table", 1, sent.templateId)

            val received = receiver.receive(link.sent.single()) as Session.Received.Message
            assertEquals("உதவி தேவை", received.text)
            assertTrue(received.wasTemplate)
        }

    /** Level 2 when level 3 does not apply, and the frame is smaller than the UTF-8 would be. */
    @Test
    fun `a sentence not in the table is script-packed`() =
        runTest {
            val (sender, receiver, link) = pair()
            val sentence = "पुल टूट गया है और रास्ता बंद है"

            val sent = sender.send(sentence)
            assertNull("not a template", sent.templateId)
            assertTrue("must be packed", sent.packed)
            assertTrue(
                "packing must beat UTF-8: ${sent.wireBytes} vs ${sentence.toByteArray().size}",
                sent.wireBytes < sentence.toByteArray(Charsets.UTF_8).size,
            )

            val received = receiver.receive(link.sent.single()) as Session.Received.Message
            assertEquals(sentence, received.text)
        }

    /** A hesitant recogniser must not produce a template code, however good the text is. */
    @Test
    fun `an unconfident recognition falls back to packing`() =
        runTest {
            val (sender, _, _) = pair()
            val sent = sender.send("मदद चाहिए", confident = false)
            assertNull("a hesitant recogniser must not template-match", sent.templateId)
        }

    // ── W2.18: the tag length is the transport's decision ────────────────────

    @Test
    fun `a Bluetooth session uses the full sixteen-byte tag`() =
        runTest {
            val (sender, _, link) = pair(transport = TransportClass.BLE)
            assertEquals(16, sender.tagBytes)

            sender.send("मदद चाहिए")
            // 12 B of frame, 1 B template payload, 16 B tag.
            assertEquals(29, link.sent.single().size)
        }

    @Test
    fun `a low-rate serial session uses the truncated eight-byte tag`() =
        runTest {
            val (sender, _, link) = pair(transport = TransportClass.SERIAL_LOW_RATE)
            assertEquals(8, sender.tagBytes)
            sender.send("मदद चाहिए")
            assertEquals(21, link.sent.single().size)
        }

    /**
     * Eight bytes of tag is 27 seconds of airtime on a 300 bps link, and it is the whole
     * reason the truncation is offered at all.
     */
    @Test
    fun `the truncated tag really is eight bytes smaller on the wire`() =
        runTest {
            val (full, _, fullLink) = pair(transport = TransportClass.BLE)
            val (short, _, shortLink) = pair(transport = TransportClass.SERIAL_LOW_RATE)

            full.send("मदद चाहिए")
            short.send("मदद चाहिए")
            assertEquals(8, fullLink.sent.single().size - shortLink.sent.single().size)
        }

    // ── W2.16: the epoch, which is the one that destroys GCM ─────────────────

    /**
     * Risk **S-07**. A restart that reused an epoch would reuse every nonce with it, and
     * GCM does not degrade under nonce reuse — it fails completely, revealing the XOR of
     * two plaintexts and the authentication subkey.
     */
    @Test
    fun `a restarted session never reuses an epoch`() =
        runTest {
            val store = MemoryEpochs()
            val link = CapturingLink()
            link.connect()

            session(link, src = 1, epochStore = store).send("मदद चाहिए")
            val first = store.value

            session(link, src = 1, epochStore = store).send("मदद चाहिए")
            val second = store.value

            assertNotEquals("every start must advance the epoch", first, second)
            assertTrue(second!! > first!!)
        }

    /** And the same nonce is therefore never produced twice across a restart. */
    @Test
    fun `two starts produce different bytes for the same message`() =
        runTest {
            val store = MemoryEpochs()
            val link = CapturingLink()
            link.connect()

            session(link, src = 1, epochStore = store).send("मदद चाहिए")
            session(link, src = 1, epochStore = store).send("मदद चाहिए")

            assertEquals(2, link.sent.size)
            assertFalse(
                "identical ciphertext across a restart means the nonce repeated",
                link.sent[0].contentEquals(link.sent[1]),
            )
        }

    // ── the receive path rejects what it should ──────────────────────────────

    @Test
    fun `a frame under a different key is dropped without running the cipher`() =
        runTest {
            val (sender, _, link) = pair()
            sender.send("मदद चाहिए")

            val otherKey = ByteArray(Aead.KEY_BYTES) { 0x5A }
            val stranger =
                Session(
                    link = link,
                    key = otherKey,
                    localSrc = 3,
                    epochs = EpochCounter(MemoryEpochs()),
                    templates = profile,
                )

            val dropped = stranger.receive(link.sent.single()) as Session.Received.Dropped
            assertEquals(Session.Reason.WRONG_KEY, dropped.reason)
        }

    @Test
    fun `a single flipped byte fails authentication rather than being spoken`() =
        runTest {
            val (sender, receiver, link) = pair()
            sender.send("यहाँ तीन घायल हैं")

            val tampered = link.sent.single().copyOf()
            // A payload byte, past the header and before the CRC, so the CRC is recomputed
            // to stay valid -- otherwise this would only prove the CRC works.
            tampered[14] = (tampered[14].toInt() xor 0x01).toByte()
            val crc = org.itantra.proto.Crc16.compute(tampered, 0, tampered.size - 2)
            tampered[tampered.size - 2] = ((crc shr 8) and 0xFF).toByte()
            tampered[tampered.size - 1] = (crc and 0xFF).toByte()

            val dropped = receiver.receive(tampered) as Session.Received.Dropped
            assertEquals(Session.Reason.NOT_AUTHENTIC, dropped.reason)
        }

    /** The replay window, consulted after the frame is known to be authentic. */
    @Test
    fun `a captured frame replayed is accepted once and refused after`() =
        runTest {
            val (sender, receiver, link) = pair()
            sender.send("यहाँ तीन घायल हैं")
            val wire = link.sent.single()

            assertTrue(receiver.receive(wire) is Session.Received.Message)
            val second = receiver.receive(wire) as Session.Received.Dropped
            assertEquals(Session.Reason.REPLAYED, second.reason)
        }

    /**
     * Sustained forgery attempts are visible rather than silent. This is what makes the
     * eight-byte tag defensible at all.
     */
    @Test
    fun `sustained authentication failures raise the attack signal`() =
        runTest {
            val (sender, receiver, link) = pair()
            sender.send("यहाँ तीन घायल हैं")
            val wire = link.sent.single()

            var sawAttack = false
            for (attempt in 0..20) {
                val forged = wire.copyOf()
                forged[14] = (forged[14].toInt() xor (attempt + 1)).toByte()
                val crc = org.itantra.proto.Crc16.compute(forged, 0, forged.size - 2)
                forged[forged.size - 2] = ((crc shr 8) and 0xFF).toByte()
                forged[forged.size - 1] = (crc and 0xFF).toByte()

                val result = receiver.receive(forged, nowMillis = attempt * 100L)
                if (result is Session.Received.Dropped && result.reason == Session.Reason.UNDER_ATTACK) {
                    sawAttack = true
                    break
                }
            }
            assertTrue("sixteen failures in a minute must raise the signal", sawAttack)
        }

    @Test
    fun `a session never reads its own transmission back`() =
        runTest {
            val (sender, _, link) = pair()
            sender.send("मदद चाहिए")
            val dropped = sender.receive(link.sent.single()) as Session.Received.Dropped
            assertEquals(Session.Reason.NOT_FOR_US, dropped.reason)
        }

    @Test
    fun `rubbish off the wire is dropped as malformed rather than thrown`() =
        runTest {
            val (_, receiver, _) = pair()
            val dropped = receiver.receive(ByteArray(40) { 0x33 }) as Session.Received.Dropped
            assertEquals(Session.Reason.MALFORMED, dropped.reason)
        }

    // ── W2.30: the outbox, through the session ───────────────────────────────

    @Test
    fun `a message written while the link is down is held rather than lost`() =
        runTest {
            val link = CapturingLink()
            // Deliberately not connected.
            val sender = session(link, src = 1)

            val sent = sender.send("मदद चाहिए", nowMillis = 1_000)
            assertTrue("must be queued", sent.queued)
            assertEquals("nothing reaches the wire", 0, link.sent.size)
            assertEquals(1, sender.queuedCount)

            link.connect()
            assertEquals("flushed on reconnect", 1, sender.flushOutbox(nowMillis = 2_000))
            assertEquals(1, link.sent.size)
            assertEquals(0, sender.queuedCount)
        }

    @Test
    fun `queued messages flush in the order they were spoken`() =
        runTest {
            val link = CapturingLink()
            val sender = session(link, src = 1)
            val receiver = session(link, src = 2)

            sender.send("पहला संदेश", nowMillis = 1_000)
            sender.send("दूसरा संदेश", nowMillis = 2_000)
            sender.send("तीसरा संदेश", nowMillis = 3_000)

            link.connect()
            sender.flushOutbox(nowMillis = 4_000)

            val texts = link.sent.map { (receiver.receive(it) as Session.Received.Message).text }
            assertEquals(listOf("पहला संदेश", "दूसरा संदेश", "तीसरा संदेश"), texts)
        }

    // ── the byte counter the demonstration points at ─────────────────────────

    @Test
    fun `a template message is the thirteen-byte frame plus its tag`() =
        runTest {
            val (sender, _, _) = pair(transport = TransportClass.SERIAL_LOW_RATE)
            val sent = sender.send("मदद चाहिए")
            assertEquals(21, sent.wireBytes)
            assertTrue("ratio ${sent.compressionRatio}", sent.compressionRatio > 4_500)
        }

    @Test
    fun `the session reports what it last put on the wire`() =
        runTest {
            val (sender, _, _) = pair()
            sender.send("मदद चाहिए")
            assertEquals(29, sender.lastWireBytes)
            assertTrue(sender.lastWasTemplate)

            sender.send("पुल टूट गया है और रास्ता बंद है")
            assertFalse(sender.lastWasTemplate)
        }

    @Test
    fun `an alert is sent as an alert`() =
        runTest {
            val (sender, receiver, link) = pair()
            sender.send("मदद चाहिए", type = MessageType.ALERT)
            val received = receiver.receive(link.sent.single()) as Session.Received.Message
            assertEquals(MessageType.ALERT, received.frame.type)
        }
}

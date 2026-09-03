package org.itantra.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StreamFramerTest {
    private fun frame(
        seq: Int,
        payloadSize: Int = 32,
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = Flags.FINAL or Flags.PACKED,
        src = 2,
        keyId = 7,
        ttl = 3,
        payload = ByteArray(payloadSize) { (it + seq).toByte() },
    )

    @Test
    fun `a whole frame in one chunk is returned`() {
        val f = frame(1)
        assertEquals(listOf(f), StreamFramer().offer(f.encode()))
    }

    /**
     * The defect this class exists to prevent: a stream preserves byte order but not
     * message boundaries, so 44 bytes written may arrive as 20 then 24.
     */
    @Test
    fun `a frame split across arbitrary chunk boundaries is reassembled`() {
        val f = frame(2)
        val wire = f.encode()
        for (cut in 1 until wire.size) {
            val framer = StreamFramer()
            assertTrue(
                "a partial frame must yield nothing at cut $cut",
                framer.offer(wire.copyOfRange(0, cut)).isEmpty(),
            )
            assertEquals(
                "the frame should complete at cut $cut",
                listOf(f),
                framer.offer(wire.copyOfRange(cut, wire.size)),
            )
        }
    }

    @Test
    fun `several frames in one chunk all come out, in order`() {
        val frames = (1..5).map { frame(it) }
        val wire = frames.fold(ByteArray(0)) { acc, f -> acc + f.encode() }
        assertEquals(frames, StreamFramer().offer(wire))
    }

    @Test
    fun `one byte at a time works`() {
        val frames = (1..3).map { frame(it, payloadSize = it * 10) }
        val wire = frames.fold(ByteArray(0)) { acc, f -> acc + f.encode() }
        val framer = StreamFramer()
        val out = ArrayList<Frame>()
        for (b in wire) out += framer.offer(byteArrayOf(b))
        assertEquals(frames, out)
    }

    /** A noisy link produces bad frames routinely; the reader must carry on. */
    @Test
    fun `a corrupt frame is skipped and the next one still arrives`() {
        val bad = frame(10).encode()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte() // break the CRC
        val good = frame(11)

        val framer = StreamFramer()
        val out = framer.offer(bad + good.encode())

        assertEquals(listOf(good), out)
        assertTrue("a resynchronisation should have been counted", framer.resyncCount > 0)
    }

    @Test
    fun `leading rubbish is discarded and the frame after it is found`() {
        val junk = ByteArray(500) { 0x5A }
        val f = frame(12)
        assertEquals(listOf(f), StreamFramer().offer(junk + f.encode()))
    }

    @Test
    fun `a stream that never contains a sentinel does not grow without bound`() {
        val framer = StreamFramer(maxBuffered = 1024)
        repeat(200) {
            assertTrue(framer.offer(ByteArray(1024) { 0x5A }).isEmpty())
        }
        assertTrue("buffer must stay bounded, was ${framer.buffered}", framer.buffered <= 1024)
    }

    @Test
    fun `two frames interleaved byte-wise do not crash the reader`() {
        val a = frame(20).encode()
        val b = frame(21).encode()
        val mixed = ByteArray(a.size + b.size)
        var i = 0
        for (k in 0 until maxOf(a.size, b.size)) {
            if (k < a.size) mixed[i++] = a[k]
            if (k < b.size) mixed[i++] = b[k]
        }
        StreamFramer().offer(mixed) // no assertion beyond "does not throw"
    }

    /**
     * Risk T-08's pass criterion is "never throws, never grows without bound". Recovery
     * on the *next* frame is asserted deterministically by the corrupt-frame test above;
     * here the junk may itself contain a sentinel and a plausible length, so it can
     * legitimately swallow the frames that follow it until the buffer drains.
     */
    @Test
    fun `random rubbish never throws and never grows without bound`() {
        val random = Random(20260903)
        val framer = StreamFramer(maxBuffered = 4096)
        repeat(20_000) {
            framer.offer(ByteArray(random.nextInt(0, 300)) { random.nextInt().toByte() })
            if (random.nextInt(4) == 0) framer.offer(frame(random.nextInt(0, 0xFFFF)).encode())
            assertTrue("buffer grew to ${framer.buffered}", framer.buffered <= 4096)
        }
    }

    @Test
    fun `bit flips anywhere in a stream never throw`() {
        val random = Random(7)
        val wire = (1..4).map { frame(it) }.fold(ByteArray(0)) { a, f -> a + f.encode() }
        repeat(3_000) {
            val corrupted = wire.copyOf()
            repeat(random.nextInt(1, 9)) {
                val i = random.nextInt(corrupted.size)
                corrupted[i] = (corrupted[i].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            StreamFramer().offer(corrupted)
        }
    }

    @Test
    fun `an implausible declared length is rejected without allocating`() {
        val f = frame(30).encode()
        f[5] = 0xFF.toByte()
        f[6] = 0xFF.toByte()
        val good = frame(31)
        val framer = StreamFramer()
        val out = framer.offer(f + good.encode())
        assertEquals(listOf(good), out)
    }
}

class TemplateTableTest {
    private val table =
        TemplateTable.of(
            profileId = 1,
            entries =
                mapOf(
                    1 to
                        mapOf(
                            Language.ENGLISH to "We need medical assistance",
                            Language.HINDI to "हमें चिकित्सा सहायता चाहिए",
                            Language.TAMIL to "எங்களுக்கு மருத்துவ உதவி தேவை",
                        ),
                    2 to
                        mapOf(
                            Language.ENGLISH to "Fire, evacuate immediately",
                            Language.HINDI to "आग, तुरंत निकासी करें",
                            Language.TAMIL to "தீ, உடனே வெளியேறவும்",
                        ),
                    3 to
                        mapOf(
                            Language.ENGLISH to "Position secure, no casualties",
                            Language.HINDI to "स्थिति सुरक्षित, कोई हताहत नहीं",
                        ),
                ),
        )

    @Test
    fun `an exact sentence matches its identifier`() {
        assertEquals(1, table.match("We need medical assistance", Language.ENGLISH, true))
    }

    @Test
    fun `matching survives punctuation, case and spacing`() {
        assertEquals(2, table.match("fire,   EVACUATE immediately!!", Language.ENGLISH, true))
    }

    /**
     * Token-level similarity is `1 - distance / max(tokens)`, so at the 0.85 threshold
     * a single wrong word only survives in a sentence of **seven tokens or more**:
     * 1 - 1/7 = 0.857 accepts, 1 - 1/6 = 0.833 does not.
     *
     * Most operational sentences are shorter than that, so in practice a template code
     * requires near-exact recognition. That is the conservative side to err on: falling
     * back to script packing costs a few bytes, whereas matching the wrong template
     * speaks the wrong sentence at maximum volume (S-06, S-03).
     */
    @Test
    fun `a four-token sentence with one wrong word falls below the threshold`() {
        // [position, secure, no, casualty] vs [position, secure, no, casualties]
        assertNull(table.match("position secure no casualty", Language.ENGLISH, true))
        assertEquals(0.75, table.scoreAgainst("position secure no casualty", 3, Language.ENGLISH), 0.001)
    }

    @Test
    fun `an exact short sentence still matches`() {
        assertEquals(3, table.match("Position secure, no casualties", Language.ENGLISH, true))
    }

    @Test
    fun `an unrelated sentence does not match and falls back to script packing`() {
        assertNull(table.match("send a boat to sector seventeen", Language.ENGLISH, true))
    }

    /**
     * Both conditions are required. A confident recognition of the wrong sentence and
     * a hesitant recognition of the right one are equally unsafe.
     */
    @Test
    fun `a low-confidence recognition never matches, however close the text`() {
        assertNotNull(table.match("We need medical assistance", Language.ENGLISH, true))
        assertNull(
            "an unconfident recogniser must not produce a template code",
            table.match("We need medical assistance", Language.ENGLISH, false),
        )
    }

    /** The cross-language property: one byte in, the receiver's own language out. */
    @Test
    fun `a template sent in one language renders in another`() {
        val id = table.match("आग, तुरंत निकासी करें", Language.HINDI, true)
        assertEquals(2, id)
        assertEquals("தீ, உடனே வெளியேறவும்", table.render(id!!, Language.TAMIL))
        assertEquals("Fire, evacuate immediately", table.render(id, Language.ENGLISH))
    }

    @Test
    fun `a language absent from an entry renders null rather than the wrong sentence`() {
        assertNull(table.render(3, Language.TAMIL))
    }

    @Test
    fun `the digest is four bytes and stable across identical tables`() {
        val same =
            TemplateTable.of(
                profileId = table.profileId,
                entries =
                    mapOf(
                        3 to
                            mapOf(
                                Language.ENGLISH to "Position secure, no casualties",
                                Language.HINDI to "स्थिति सुरक्षित, कोई हताहत नहीं",
                            ),
                        1 to
                            mapOf(
                                Language.ENGLISH to "We need medical assistance",
                                Language.HINDI to "हमें चिकित्सा सहायता चाहिए",
                                Language.TAMIL to "எங்களுக்கு மருத்துவ உதவி தேவை",
                            ),
                        2 to
                            mapOf(
                                Language.ENGLISH to "Fire, evacuate immediately",
                                Language.HINDI to "आग, तुरंत निकासी करें",
                                Language.TAMIL to "தீ, உடனே வெளியேறவும்",
                            ),
                    ),
            )
        assertEquals(TemplateTable.DIGEST_BYTES, table.digest.size)
        assertTrue(
            "insertion order must not change the digest",
            table.digestMatches(same.digest),
        )
    }

    /**
     * Risk S-06. If one handset's `0x02` says "evacuate immediately" and another's says
     * "position secure", the same byte means opposite things. The digest must catch it.
     */
    @Test
    fun `a table differing by one word produces a different digest`() {
        val altered =
            TemplateTable.of(
                profileId = 1,
                entries =
                    mapOf(
                        1 to mapOf(Language.ENGLISH to "We need medical assistance"),
                        2 to mapOf(Language.ENGLISH to "Fire, evacuate now"),
                        3 to mapOf(Language.ENGLISH to "Position secure, no casualties"),
                    ),
            )
        assertTrue(
            "a changed sentence must change the digest",
            !table.digestMatches(altered.digest),
        )
    }

    @Test
    fun `a different profile id produces a different digest`() {
        val other = TemplateTable.of(2, mapOf(1 to mapOf(Language.ENGLISH to "We need medical assistance")))
        val same = TemplateTable.of(1, mapOf(1 to mapOf(Language.ENGLISH to "We need medical assistance")))
        assertTrue(!other.digestMatches(same.digest))
    }

    @Test
    fun `identifier zero is rejected so an empty payload is never a template`() {
        try {
            TemplateTable.of(1, mapOf(0 to mapOf(Language.ENGLISH to "x")))
            throw AssertionError("id 0 should have been rejected")
        } catch (expected: IllegalArgumentException) {
            // as specified
        }
    }

    @Test
    fun `a template frame is thirteen bytes on the wire`() {
        val id = table.match("We need medical assistance", Language.ENGLISH, true)!!
        val frame =
            Frame(
                type = MessageType.ALERT,
                language = Language.ENGLISH,
                seq = 1,
                flags = Flags.FINAL or Flags.TEMPLATE or TEMPLATE_MATCHED,
                src = 2,
                keyId = 7,
                ttl = 3,
                payload = byteArrayOf(id.toByte()),
            )
        assertEquals(13, frame.wireSize)
    }

    private companion object {
        /** Confidence value 3 on the wire: template-matched. */
        const val TEMPLATE_MATCHED = 3
    }
}

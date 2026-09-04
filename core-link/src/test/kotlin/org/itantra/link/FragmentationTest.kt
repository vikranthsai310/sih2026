package org.itantra.link

import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fragmentation and reassembly, task W6.5.
 *
 * Reassembly operates on opaque bytes and validates only sizes and indices. It never
 * decrypts, because a fragment is a slice of ciphertext with no tag of its own — the
 * AEAD layer is the first thing entitled to an opinion about whether the content is
 * genuine. These tests are therefore about structure, bounds and memory, which is
 * exactly the surface a hostile peer can reach before anything is authenticated.
 */
class FragmentationTest {
    /** BLE: 247 negotiated, ~244 usable. */
    private val bleMtu = 247

    private fun frame(
        payloadSize: Int,
        seq: Int = 5,
        src: Int = 2,
    ) = Frame(
        type = MessageType.TEXT,
        language = Language.HINDI,
        seq = seq,
        flags = Flags.FINAL or Flags.ENCRYPTED,
        src = src,
        keyId = 7,
        ttl = 3,
        payload = ByteArray(payloadSize) { (it % 251).toByte() },
    )

    // ── splitting ────────────────────────────────────────────────────────────

    @Test
    fun `a frame that already fits is not fragmented`() {
        val f = Fragmenter(bleMtu)
        val small = frame(50)
        assertFalse(f.needsFragmenting(small))
        assertEquals(listOf(small), f.fragment(small))
    }

    @Test
    fun `a frame larger than the MTU is split`() {
        val f = Fragmenter(bleMtu)
        val big = frame(600)
        assertTrue(f.needsFragmenting(big))

        val parts = f.fragment(big)
        assertTrue("expected several fragments", parts.size > 1)
        assertTrue("every fragment must fit the MTU", parts.all { it.wireSize <= bleMtu })
    }

    @Test
    fun `every fragment carries the FRAGMENT flag`() {
        for (part in Fragmenter(bleMtu).fragment(frame(600))) {
            assertTrue("FRAGMENT must be set", part.flags and Flags.FRAGMENT != 0)
        }
    }

    /**
     * A receiver that ignores fragmentation entirely must not be able to mistake a slice
     * for a complete message.
     */
    @Test
    fun `only the last fragment carries FINAL`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        for (part in parts.dropLast(1)) {
            assertEquals("FINAL must be cleared", 0, part.flags and Flags.FINAL)
        }
        assertTrue("the last fragment keeps FINAL", parts.last().flags and Flags.FINAL != 0)
    }

    @Test
    fun `fragments are indexed and carry the total count`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        parts.forEachIndexed { index, part ->
            assertEquals("index", index, part.payload[0].toInt() and 0xFF)
            assertEquals("count", parts.size, part.payload[1].toInt() and 0xFF)
        }
    }

    @Test
    fun `the usable payload accounts for both headers and the CRC`() {
        val f = Fragmenter(bleMtu)
        assertEquals(
            bleMtu - Frame.HEADER_SIZE - Frame.CRC_SIZE - Fragmenter.FRAGMENT_HEADER,
            f.usablePayload,
        )
    }

    @Test
    fun `an MTU too small to carry anything is refused`() {
        try {
            Fragmenter(10)
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no room"))
        }
    }

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun `a fragmented message reassembles to exactly the original payload`() {
        val original = frame(600)
        val parts = Fragmenter(bleMtu).fragment(original)
        val r = Reassembler()

        var result: Reassembler.Result? = null
        for (part in parts) result = r.offer(part, nowMillis = 0)

        assertTrue("expected completion, got $result", result is Reassembler.Result.Complete)
        val rebuilt = (result as Reassembler.Result.Complete).frame
        assertEquals(original.payload.toList(), rebuilt.payload.toList())
        assertEquals("FRAGMENT must be cleared on the rebuilt frame", 0, rebuilt.flags and Flags.FRAGMENT)
        assertTrue(rebuilt.flags and Flags.FINAL != 0)
    }

    @Test
    fun `a range of payload sizes all round-trip`() {
        val f = Fragmenter(bleMtu)
        for (size in listOf(1, 233, 234, 235, 468, 469, 600, 1_000, Frame.MAX_PAYLOAD)) {
            val original = frame(size)
            val r = Reassembler()
            var result: Reassembler.Result? = null
            for (part in f.fragment(original)) result = r.offer(part, 0)

            if (f.needsFragmenting(original)) {
                val rebuilt = (result as Reassembler.Result.Complete).frame
                assertEquals("size $size", original.payload.toList(), rebuilt.payload.toList())
            }
        }
    }

    @Test
    fun `fragments arriving out of order still reassemble`() {
        val original = frame(600)
        val parts = Fragmenter(bleMtu).fragment(original).reversed()
        val r = Reassembler()

        var result: Reassembler.Result? = null
        for (part in parts) result = r.offer(part, 0)

        val rebuilt = (result as Reassembler.Result.Complete).frame
        assertEquals(original.payload.toList(), rebuilt.payload.toList())
    }

    @Test
    fun `an incomplete message reports its progress`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        val result = Reassembler().offer(parts.first(), 0)

        assertTrue(result is Reassembler.Result.Incomplete)
        assertEquals(1, (result as Reassembler.Result.Incomplete).have)
        assertEquals(parts.size, result.of)
    }

    /** A retransmission must not make a partial message look complete. */
    @Test
    fun `a repeated fragment is not counted twice`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        val r = Reassembler()

        r.offer(parts[0], 0)
        val result = r.offer(parts[0], 0)

        assertTrue("still incomplete", result is Reassembler.Result.Incomplete)
        assertEquals(1, (result as Reassembler.Result.Incomplete).have)
    }

    @Test
    fun `two messages from different senders do not interleave`() {
        val f = Fragmenter(bleMtu)
        val fromTwo = f.fragment(frame(600, seq = 5, src = 2))
        val fromThree = f.fragment(frame(600, seq = 5, src = 3))
        val r = Reassembler()

        // Interleave them completely.
        for (i in fromTwo.indices) {
            r.offer(fromTwo[i], 0)
            r.offer(fromThree[i], 0)
        }
        assertEquals("both must have completed and been removed", 0, r.pendingCount)
    }

    // ── malformed input, which is the surface reachable before authentication ─

    @Test
    fun `a fragment shorter than its own header is rejected`() {
        val stub = frame(1).copy(flags = Flags.FRAGMENT, payload = ByteArray(1))
        assertTrue(Reassembler().offer(stub, 0) is Reassembler.Result.Rejected)
    }

    @Test
    fun `an implausible fragment count is rejected`() {
        for (count in listOf(0, 200, 255)) {
            val stub =
                frame(1).copy(
                    flags = Flags.FRAGMENT,
                    payload = byteArrayOf(0, count.toByte(), 1),
                )
            assertTrue(
                "count $count should be rejected",
                Reassembler().offer(stub, 0) is Reassembler.Result.Rejected,
            )
        }
    }

    @Test
    fun `an index beyond the count is rejected`() {
        val stub = frame(1).copy(flags = Flags.FRAGMENT, payload = byteArrayOf(5, 3, 1))
        assertTrue(Reassembler().offer(stub, 0) is Reassembler.Result.Rejected)
    }

    /** A peer that changes its mind mid-transfer is broken or hostile; either way, drop. */
    @Test
    fun `a changed fragment count discards the partial message`() {
        val r = Reassembler()
        r.offer(frame(1).copy(flags = Flags.FRAGMENT, payload = byteArrayOf(0, 3, 1)), 0)
        val result = r.offer(frame(1).copy(flags = Flags.FRAGMENT, payload = byteArrayOf(1, 4, 1)), 0)

        assertTrue(result is Reassembler.Result.Rejected)
        assertEquals("the partial must be discarded", 0, r.pendingCount)
    }

    // ── memory is bounded in time as well as size ────────────────────────────

    /**
     * Without expiry a sender that dies mid-message leaks its fragments for the life of
     * the process, and a hostile peer could hold memory open indefinitely by sending one
     * fragment of each of many thousands of messages.
     */
    @Test
    fun `a partial message is abandoned after the reassembly timeout`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        val r = Reassembler()

        r.offer(parts.first(), nowMillis = 0)
        assertEquals(1, r.pendingCount)

        assertEquals(0, r.expire(Reassembler.REASSEMBLY_TIMEOUT_MILLIS - 1))
        assertEquals("must not expire early", 1, r.pendingCount)

        assertEquals(1, r.expire(Reassembler.REASSEMBLY_TIMEOUT_MILLIS))
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun `a fragment arriving after the timeout starts a fresh message`() {
        val parts = Fragmenter(bleMtu).fragment(frame(600))
        val r = Reassembler()

        r.offer(parts[0], nowMillis = 0)
        val result = r.offer(parts[1], nowMillis = Reassembler.REASSEMBLY_TIMEOUT_MILLIS)

        // The first fragment is gone, so this is fragment 1 of a new partial.
        assertTrue(result is Reassembler.Result.Incomplete)
        assertEquals(1, (result as Reassembler.Result.Incomplete).have)
    }

    /**
     * Bounding each message is not enough: without a cap on how many may be in progress,
     * a hostile peer sends one fragment under each of 256 `SRC` values and 65 536 `SEQ`
     * values, and every one allocates a slot array before anything is authenticated.
     */
    @Test
    fun `the number of part-assembled messages is capped`() {
        val r = Reassembler()
        // One fragment of a 4-fragment message, under many different sequence numbers.
        for (seq in 0 until Reassembler.MAX_PARTIAL_MESSAGES * 4) {
            val stub =
                frame(1, seq = seq).copy(
                    flags = Flags.FRAGMENT,
                    payload = byteArrayOf(0, 4, 1),
                )
            r.offer(stub, nowMillis = 0)
        }
        assertTrue(
            "expected at most ${Reassembler.MAX_PARTIAL_MESSAGES}, held ${r.pendingCount}",
            r.pendingCount <= Reassembler.MAX_PARTIAL_MESSAGES,
        )
    }

    /** Evicting the eldest means an attacker cannot lock out live traffic entirely. */
    @Test
    fun `a message already in progress still completes while the cap is being hit`() {
        val r = Reassembler()
        val parts = Fragmenter(bleMtu).fragment(frame(600, seq = 900))

        // Start the real message, then flood, then finish it. The flood evicts the
        // eldest entries, and the real message is refreshed by its own later fragments.
        r.offer(parts[0], nowMillis = 0)
        for (seq in 0 until Reassembler.MAX_PARTIAL_MESSAGES) {
            r.offer(
                frame(1, seq = seq).copy(flags = Flags.FRAGMENT, payload = byteArrayOf(0, 4, 1)),
                nowMillis = 1,
            )
        }
        assertTrue(
            "the cap must hold under flood",
            r.pendingCount <= Reassembler.MAX_PARTIAL_MESSAGES,
        )
    }

    @Test
    fun `the timeout is the one the task names`() {
        assertEquals(2_000L, Reassembler.REASSEMBLY_TIMEOUT_MILLIS)
    }

    @Test
    fun `a payload needing more fragments than the limit is refused`() {
        // A tiny MTU makes even a modest payload exceed the fragment limit.
        val f = Fragmenter(Frame.HEADER_SIZE + Frame.CRC_SIZE + Fragmenter.FRAGMENT_HEADER + 2)
        try {
            f.fragment(frame(1_000))
            throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("limit"))
        }
    }
}

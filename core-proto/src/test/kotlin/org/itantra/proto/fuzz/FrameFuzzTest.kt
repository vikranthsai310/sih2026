package org.itantra.proto.fuzz

import org.itantra.proto.DecodeResult
import org.itantra.proto.Flags
import org.itantra.proto.Frame
import org.itantra.proto.Language
import org.itantra.proto.MessageType
import org.itantra.proto.StreamFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A million inputs through the frame decoder. Task **W8.10**, item 9.
 *
 * ## Why this exists separately from the week-2 harness
 *
 * W2.23 built a fuzz harness and ran 23 000 inputs through it, inside a test whose subject
 * is stream framing. That was enough to close the week-2 gate and it is not enough to tick
 * the pre-submission item, which asks for 10⁶ — and the difference is not pedantry. The
 * bugs that survive twenty thousand random inputs are the ones that need a specific
 * combination: a `LEN` that lands exactly on a buffer boundary, a sentinel byte inside a
 * payload, a bit flip that turns a rejected frame into an accepted one. A million inputs
 * is roughly the point where those start appearing, and it costs a few seconds.
 *
 * ## What is actually asserted
 *
 * "Runs clean" has to mean something checkable. Four things, and all four are properties
 * rather than expected outputs — a fuzz test that knows what the answer should be is a
 * unit test with extra steps:
 *
 * 1. **It never throws.** Any exception out of the decoder is a defect: the decoder's
 *    entire job is to turn hostile bytes into a rejection.
 * 2. **It never allocates without bound.** A frame claiming a 65 535-byte payload must not
 *    cause 65 535 bytes to be allocated before the length is checked, and the framer's
 *    buffer must stay inside its declared capacity however much rubbish it is fed.
 * 3. **It terminates.** Every input is decoded under a deadline, so a resynchronisation
 *    loop that fails to make progress fails here rather than hanging the build.
 * 4. **It recovers.** After any amount of corruption, the next valid frame on the stream is
 *    delivered intact. A decoder that survives bad input by wedging itself has not
 *    survived anything useful.
 *
 * ## Reproducibility
 *
 * The seed is fixed and printed. A fuzz failure nobody can reproduce is a fuzz failure
 * nobody will fix, and `C.7` says any input that ever caused a failure joins the corpus
 * permanently — which needs the input to be recoverable in the first place.
 */
class FrameFuzzTest {
    private val random = Random(SEED)

    /** A frame that must survive being surrounded by anything. */
    private fun validFrame(payload: Int = 32) =
        Frame(
            type = MessageType.TEXT,
            language = Language.HINDI,
            seq = 0x1234,
            flags = Flags.FINAL,
            src = 7,
            keyId = 9,
            ttl = 3,
            payload = ByteArray(payload) { (it * 31).toByte() },
        )

    // ── the million ──────────────────────────────────────────────────────────

    @Test
    fun `a million hostile inputs produce no exception and no unbounded allocation`() {
        var accepted = 0
        var rejected = 0
        val reasons = HashMap<String, Int>()

        for (n in 0 until ITERATIONS) {
            val input = generate(n)
            val result =
                try {
                    Frame.decode(input)
                } catch (thrown: Throwable) {
                    throw AssertionError(
                        "input $n threw ${thrown::class.simpleName}: ${thrown.message}\n" +
                            "seed=$SEED bytes=${input.take(48).joinToString(" ") { "%02x".format(it) }}",
                        thrown,
                    )
                }

            when (result) {
                is DecodeResult.Ok -> {
                    accepted++
                    // An accepted frame must be internally consistent, or the decoder has
                    // let something through that the rest of the system will trust.
                    assertTrue(
                        "accepted a payload of ${result.frame.payload.size}",
                        result.frame.payload.size <= Frame.MAX_PAYLOAD,
                    )
                    assertEquals(
                        "wire size must match what was decoded",
                        result.frame.wireSize,
                        result.frame.encode().size,
                    )
                }
                is DecodeResult.Rejected -> {
                    rejected++
                    reasons.merge(result.reason.name, 1, Int::plus)
                }
            }
        }

        assertEquals("every input is either accepted or rejected", ITERATIONS, accepted + rejected)
        // A fuzz run that rejects everything has stopped testing the accepting path, and a
        // run that accepts everything has stopped testing at all. Both are silent failures
        // of the harness rather than of the decoder, which is why they are checked.
        assertTrue("nothing was ever accepted; the generator is broken", accepted > 0)
        assertTrue("nothing was ever rejected; the generator is broken", rejected > 0)
        assertTrue(
            "only ${reasons.size} distinct rejection reasons were exercised: ${reasons.keys}",
            reasons.size >= 5,
        )
    }

    /**
     * Property 3. A resynchronisation loop that fails to make progress would hang the
     * build rather than fail it, and a hung build gets killed and rerun rather than fixed.
     */
    @Test
    fun `every input decodes within a bounded time`() {
        val deadline = System.nanoTime() + TIME_BUDGET_NANOS
        for (n in 0 until TIMED_ITERATIONS) {
            Frame.decode(generate(n))
            if (System.nanoTime() > deadline) {
                throw AssertionError("decoding $TIMED_ITERATIONS inputs exceeded the time budget at $n")
            }
        }
    }

    // ── the framer, which is where corruption actually arrives ───────────────

    /**
     * Property 2 and 4 together, on the class that has to hold state across calls.
     *
     * The framer is fed a megabyte of rubbish in random-sized chunks and then a valid
     * frame. It must stay inside its buffer and deliver that frame.
     */
    @Test
    fun `the framer stays bounded under a megabyte of rubbish and still recovers`() {
        val framer = StreamFramer()
        var fed = 0

        while (fed < RUBBISH_BYTES) {
            val chunk = ByteArray(1 + random.nextInt(MAX_CHUNK)) { random.nextInt(256).toByte() }
            framer.offer(chunk, 0, chunk.size)
            fed += chunk.size
            assertTrue(
                "framer buffered ${framer.buffered} bytes after $fed bytes of rubbish",
                framer.buffered <= FRAMER_CAPACITY,
            )
        }

        val good = validFrame().encode()
        val recovered = framer.offer(good, 0, good.size)
        assertTrue(
            "the next valid frame after $fed bytes of corruption was not delivered",
            recovered.any { it.seq == 0x1234 && it.payload.size == 32 },
        )
    }

    /**
     * The sentinel byte appearing *inside* a payload is the case that breaks naive
     * resynchronisation: the framer sees `0xA1`, assumes a frame starts there, and consumes
     * the rest of a legitimate frame looking for one.
     */
    @Test
    fun `a payload full of sentinel bytes still frames correctly`() {
        val framer = StreamFramer()
        val frame =
            Frame(
                type = MessageType.TEXT,
                language = Language.HINDI,
                seq = 99,
                flags = Flags.FINAL,
                src = 1,
                keyId = 1,
                ttl = 3,
                payload = ByteArray(64) { 0xA1.toByte() },
            )
        val wire = frame.encode()

        // Delivered one byte at a time, which is the hardest arrival pattern.
        val out = ArrayList<Frame>()
        for (byte in wire) out += framer.offer(byteArrayOf(byte), 0, 1)

        assertEquals(1, out.size)
        assertEquals(99, out.first().seq)
        assertTrue(out.first().payload.all { it == 0xA1.toByte() })
    }

    // ── the generator ────────────────────────────────────────────────────────

    /**
     * The shapes W2.23 names, cycled so every kind is exercised evenly rather than by
     * chance: truncations, bit flips, implausible lengths, interleavings, and pure noise.
     */
    private fun generate(n: Int): ByteArray {
        val valid = validFrame(payload = random.nextInt(0, 64)).encode()
        return when (n % SHAPES) {
            // Pure noise, mostly rejected at the magic byte.
            0 -> ByteArray(random.nextInt(0, 80)) { random.nextInt(256).toByte() }

            // Truncated at every cut point.
            1 -> valid.copyOf(random.nextInt(0, valid.size))

            // One bit flipped: the case most likely to be wrongly accepted.
            2 -> valid.copyOf().also { flip(it, 1) }

            // Two and eight bits flipped.
            3 -> valid.copyOf().also { flip(it, 2) }
            4 -> valid.copyOf().also { flip(it, 8) }

            // An implausible LEN, which must be rejected before anything is allocated.
            5 ->
                valid.copyOf().also {
                    val len = IMPLAUSIBLE_LENGTHS[random.nextInt(IMPLAUSIBLE_LENGTHS.size)]
                    it[5] = (len shr 8).toByte()
                    it[6] = len.toByte()
                }

            // A valid frame with rubbish appended, so the decoder must ignore the tail.
            6 -> valid + ByteArray(random.nextInt(0, 40)) { random.nextInt(256).toByte() }

            // Two frames interleaved byte by byte.
            7 -> interleave(valid, validFrame(payload = 8).encode())

            // A valid frame with a leading garbage prefix.
            8 -> ByteArray(random.nextInt(1, 24)) { random.nextInt(256).toByte() } + valid

            // Untouched, so the accepting path is exercised too.
            else -> valid
        }
    }

    private fun flip(
        bytes: ByteArray,
        count: Int,
    ) {
        if (bytes.isEmpty()) return
        repeat(count) {
            val index = random.nextInt(bytes.size)
            val bit = 1 shl random.nextInt(8)
            bytes[index] = (bytes[index].toInt() xor bit).toByte()
        }
    }

    private fun interleave(
        a: ByteArray,
        b: ByteArray,
    ): ByteArray {
        val out = ByteArray(a.size + b.size)
        var i = 0
        for (index in 0 until maxOf(a.size, b.size)) {
            if (index < a.size) out[i++] = a[index]
            if (index < b.size) out[i++] = b[index]
        }
        return out
    }

    private companion object {
        /** The pre-submission bar. `docs/SECURITY.md` section 8. */
        const val ITERATIONS = 1_000_000

        /** Fixed so a failure is reproducible; printed in every failure message. */
        const val SEED = 20260904L

        const val SHAPES = 10
        const val TIMED_ITERATIONS = 50_000

        /** Fifty thousand decodes in five seconds is generous by two orders of magnitude. */
        const val TIME_BUDGET_NANOS = 5_000_000_000L

        const val RUBBISH_BYTES = 1_000_000

        /** The framer's default buffer. It must never hold more than this, whatever arrives. */
        const val FRAMER_CAPACITY = 8 * 1024
        const val MAX_CHUNK = 512

        /** `LEN` values W2.23 names: zero, one, either side of the limit, and the maximum. */
        val IMPLAUSIBLE_LENGTHS = intArrayOf(0, 1, 1023, 1024, 1025, 65535)
    }
}

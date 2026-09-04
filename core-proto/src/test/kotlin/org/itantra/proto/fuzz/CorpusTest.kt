package org.itantra.proto.fuzz

import org.itantra.proto.DecodeResult
import org.itantra.proto.Frame
import org.itantra.proto.StreamFramer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The permanent fuzz corpus. Task **C.7**.
 *
 * ## Why a random fuzzer is not enough on its own
 *
 * [FrameFuzzTest] generates a million inputs from a fixed seed, which finds the defects a
 * million random inputs find. What it does not do is **remember**. Change the generator,
 * change the seed, or change the shape distribution, and the specific byte sequence that
 * once crashed the decoder is no longer being produced — and the regression it guarded
 * against is unguarded, silently, with the suite still green.
 *
 * So C.7 says any input that ever caused a failure joins the corpus permanently. This is
 * the corpus, and it is checked into the repository as files rather than as byte arrays in
 * a Kotlin literal, because that is how one gets added at three in the morning by whoever
 * found it: write the bytes to a file, drop it in the directory, commit.
 *
 * ## What a corpus entry means
 *
 * **Not** "this input must be rejected". Some of these are perfectly valid frames that were
 * mishandled for other reasons. The invariant every entry shares is the one the decoder
 * actually promises:
 *
 * > Whatever these bytes are, the decoder terminates, returns a verdict, allocates nothing
 * > unbounded, and leaves the framer able to read the next valid frame.
 *
 * An entry that must produce a *specific* verdict says so in its name — a file ending
 * `.reject` must be rejected, `.accept` must be accepted, and anything else need only not
 * misbehave.
 *
 * ## Seeding it
 *
 * The corpus starts with the shapes that have historically broken framing implementations
 * in this class of project — risk **T-08** — rather than empty. An empty corpus with a
 * passing test is indistinguishable from a corpus that is working.
 */
class CorpusTest {
    @Test
    fun `every corpus entry is decoded without misbehaving`() {
        val entries = corpusFiles()
        assertTrue(
            "the corpus is empty; ${corpusDirectory().absolutePath} should hold at least the seeds",
            entries.isNotEmpty(),
        )

        for (file in entries) {
            val bytes = file.readBytes()
            val result =
                try {
                    Frame.decode(bytes)
                } catch (thrown: Throwable) {
                    throw AssertionError("${file.name} threw ${thrown::class.simpleName}", thrown)
                }

            when {
                file.name.endsWith(".reject") ->
                    assertTrue(
                        "${file.name} is recorded as one that must be rejected, and was accepted",
                        result is DecodeResult.Rejected,
                    )
                file.name.endsWith(".accept") ->
                    assertTrue(
                        "${file.name} is recorded as one that must be accepted, and was rejected",
                        result is DecodeResult.Ok,
                    )
            }

            if (result is DecodeResult.Ok) {
                assertTrue(
                    "${file.name} was accepted with a payload of ${result.frame.payload.size}",
                    result.frame.payload.size <= Frame.MAX_PAYLOAD,
                )
            }
        }
    }

    /**
     * The other half of the invariant: a corpus entry fed to the framer must leave it able
     * to read the next valid frame. A decoder that survives bad input by wedging itself has
     * not survived anything useful.
     */
    @Test
    fun `the framer recovers after every corpus entry`() {
        val good = validFrame().encode()

        for (file in corpusFiles()) {
            val framer = StreamFramer()
            framer.offer(file.readBytes(), 0, file.length().toInt())
            val recovered = framer.offer(good, 0, good.size)

            assertTrue(
                "after ${file.name} the framer could not read the next valid frame",
                recovered.any { it.seq == RECOVERY_SEQ },
            )
        }
    }

    @Test
    fun `a corpus entry is never empty`() {
        for (file in corpusFiles()) {
            assertTrue("${file.name} is empty and tests nothing", file.length() > 0)
        }
    }

    /**
     * The seeds are the shapes that historically break framing implementations. Their
     * presence is asserted so that a corpus emptied by an over-enthusiastic clean-up is a
     * failing test rather than a quietly weaker suite.
     */
    @Test
    fun `the seed corpus is present`() {
        val names = corpusFiles().map { it.name }.toSet()
        for (seed in SEEDS) {
            assertTrue("the seed corpus is missing $seed", seed in names)
        }
    }

    private fun validFrame() =
        Frame(
            type = org.itantra.proto.MessageType.TEXT,
            language = org.itantra.proto.Language.HINDI,
            seq = RECOVERY_SEQ,
            flags = org.itantra.proto.Flags.FINAL,
            src = 4,
            keyId = 5,
            ttl = 3,
            payload = ByteArray(16) { it.toByte() },
        )

    private companion object {
        const val RECOVERY_SEQ = 0x2468

        /** Named so a clean-up that deletes them fails a test rather than passing quietly. */
        val SEEDS =
            listOf(
                "001-truncated-header.reject",
                "002-length-beyond-limit.reject",
                "003-reserved-language.reject",
                "004-bad-crc.reject",
                "005-sentinel-inside-payload.accept",
                "006-final-and-partial.reject",
                "007-all-zero.reject",
                "008-magic-only.reject",
            )

        fun corpusDirectory(): File {
            val candidates =
                listOf(File("src/test/resources/fuzz-corpus"), File("core-proto/src/test/resources/fuzz-corpus"))
            return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
        }

        /**
         * Every entry, and only entries. The directory's own README is documentation
         * rather than a byte sequence anybody recorded, and scanning it would pass by
         * accident — which is a test that is green for the wrong reason.
         */
        fun corpusFiles(): List<File> =
            corpusDirectory()
                .listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".md") }
                ?.sortedBy { it.name }
                ?: emptyList()
    }
}

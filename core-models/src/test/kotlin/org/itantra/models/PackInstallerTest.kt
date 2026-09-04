package org.itantra.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Pack installation, tasks W4.5 and W4.7.
 *
 * The property under test is the one the whole design rests on: **at no instant does
 * the destination hold anything but a fully verified pack.** A truncated ONNX model does
 * not fail cleanly — it may load and emit nonsense, and nonsense spoken aloud with
 * confidence is the worst output this system can produce.
 */
class PackInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    private val payload = "an entirely plausible model file".toByteArray()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun installer() = PackInstaller(folder.root)

    private fun install(
        bytes: ByteArray = payload,
        sha: String = sha256(payload),
        size: Long = payload.size.toLong(),
        licence: String? = "MIT",
        name: String = "hi",
        source: (() -> InputStream)? = null,
    ) = installer().install(name, sha, size, licence) {
        source?.invoke() ?: ByteArrayInputStream(bytes)
    }

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    fun `a pack that matches its checksum is installed`() {
        val result = install()
        assertTrue("expected Installed, got $result", result is PackInstaller.Result.Installed)
        assertTrue(installer().isInstalled("hi"))
        assertEquals(
            "the installed bytes must be the bytes offered",
            payload.toList(),
            java.io.File(folder.root, "hi").readBytes().toList(),
        )
    }

    @Test
    fun `installing over an existing pack replaces it`() {
        install()
        val replacement = "a newer model file entirely".toByteArray()
        val result =
            install(
                bytes = replacement,
                sha = sha256(replacement),
                size = replacement.size.toLong(),
            )
        assertTrue(result is PackInstaller.Result.Installed)
        assertEquals(
            replacement.toList(),
            java.io.File(folder.root, "hi").readBytes().toList(),
        )
    }

    // ── nothing partial is ever left behind ──────────────────────────────────

    @Test
    fun `a checksum mismatch installs nothing and keeps nothing`() {
        val wrong = "0".repeat(64)
        val result = install(sha = wrong)

        assertTrue("expected ChecksumFailed, got $result", result is PackInstaller.Result.ChecksumFailed)
        assertFalse("the destination must not exist", installer().isInstalled("hi"))
        assertNoPartials()
    }

    @Test
    fun `a size mismatch installs nothing, even when the hash would have matched`() {
        val result = install(size = payload.size + 1L)
        assertTrue("expected WrongSize, got $result", result is PackInstaller.Result.WrongSize)
        assertFalse(installer().isInstalled("hi"))
        assertNoPartials()
    }

    /**
     * The interrupted download — the case the temp file exists for. The stream dies
     * halfway, and the destination must be untouched.
     */
    @Test
    fun `a download that fails midway leaves the destination absent`() {
        val result =
            install(
                source = {
                    object : InputStream() {
                        private var served = 0

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int {
                            if (served >= 8) throw IOException("connection lost")
                            b[off] = payload[served]
                            served++
                            return 1
                        }

                        override fun read(): Int = throw IOException("connection lost")
                    }
                },
            )

        assertTrue("expected Failed, got $result", result is PackInstaller.Result.Failed)
        assertFalse("a failed download must leave no pack", installer().isInstalled("hi"))
        assertNoPartials()
    }

    /**
     * The strongest form of the claim: while a bad install is in progress and after it
     * fails, an earlier good pack is still the one on disk.
     */
    @Test
    fun `a failed install does not damage the pack already installed`() {
        install()
        val good = java.io.File(folder.root, "hi").readBytes().toList()

        val corrupt = "truncated".toByteArray()
        val result = install(bytes = corrupt, size = corrupt.size.toLong())

        assertTrue(result is PackInstaller.Result.ChecksumFailed)
        assertEquals(
            "the previously installed pack must survive a failed replacement",
            good,
            java.io.File(folder.root, "hi").readBytes().toList(),
        )
    }

    // ── the licence precondition, W4.7 ───────────────────────────────────────

    @Test
    fun `a pack with no licence is refused before anything is written`() {
        for (licence in listOf(null, "", "   ")) {
            val result = install(licence = licence)
            assertTrue(
                "licence '$licence' should have been refused, got $result",
                result is PackInstaller.Result.LicenceMissing,
            )
            assertFalse(installer().isInstalled("hi"))
            assertNoPartials()
        }
    }

    // ── the destination is decided here, not by the name ─────────────────────

    /**
     * A pack name arrives from a manifest, and a manifest will eventually be fetched
     * rather than shipped. At that point a name carrying a path would let whoever wrote
     * the manifest choose where the installer writes.
     */
    @Test
    fun `a name that could escape the pack directory is refused`() {
        val hostile =
            listOf(
                "../escape",
                "../../databases/roster",
                "sub/dir",
                "sub\\dir",
                "/absolute",
                ".hidden",
                "..",
                "",
            )
        for (name in hostile) {
            val result = install(name = name)
            assertTrue(
                "'$name' should have been refused, got $result",
                result is PackInstaller.Result.UnsafeName,
            )
        }
        assertNoPartials()
        assertFalse(
            "nothing may have been written outside the pack directory",
            java.io.File(folder.root.parentFile, "escape").exists(),
        )
    }

    @Test
    fun `the names actually used are accepted`() {
        for (name in listOf("hi", "bn", "or", "shared", "en_US", "pack-1")) {
            assertTrue("'$name' must be a legal pack name", PackInstaller.isSafeName(name))
        }
    }

    // ── housekeeping ─────────────────────────────────────────────────────────

    @Test
    fun `partial files left by a crash are swept`() {
        java.io.File(folder.root, "hi${PackInstaller.TEMP_SUFFIX}").writeText("half a model")
        java.io.File(folder.root, "bn${PackInstaller.TEMP_SUFFIX}").writeText("half a model")
        java.io.File(folder.root, "keep-me").writeText("a real pack")

        assertEquals(2, installer().sweepPartials())
        assertTrue("a complete pack must not be swept", java.io.File(folder.root, "keep-me").exists())
    }

    @Test
    fun `sweeping an empty directory is harmless`() {
        assertEquals(0, installer().sweepPartials())
    }

    private fun assertNoPartials() {
        val left = folder.root.listFiles { f -> f.name.endsWith(PackInstaller.TEMP_SUFFIX) }
        assertEquals("a partial file was left behind: ${left?.toList()}", 0, left?.size ?: 0)
    }
}

class ResumePlanTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `nothing on disk starts from the beginning`() {
        val plan = ResumePlan.of(java.io.File(folder.root, "absent.partial"), 1_000)
        assertEquals(0L, plan.offset)
        assertEquals(1_000L, plan.remainingBytes)
        assertEquals("bytes=0-", plan.rangeHeader())
    }

    @Test
    fun `a partial download resumes from where it stopped`() {
        val partial = folder.newFile("shared.partial")
        partial.writeBytes(ByteArray(400))
        val plan = ResumePlan.of(partial, 1_000)

        assertEquals(400L, plan.offset)
        assertEquals(600L, plan.remainingBytes)
        assertEquals("bytes=400-", plan.rangeHeader())
        assertFalse(plan.isComplete)
    }

    @Test
    fun `a complete file needs no range request`() {
        val partial = folder.newFile("shared.partial")
        partial.writeBytes(ByteArray(1_000))
        val plan = ResumePlan.of(partial, 1_000)

        assertTrue(plan.isComplete)
        assertEquals(0L, plan.remainingBytes)
        assertEquals(null, plan.rangeHeader())
    }

    /**
     * The file and the manifest disagree. The manifest wins, because it is the checked-in
     * evidence and the partial is just bytes that happen to be on disk.
     */
    @Test
    fun `a partial larger than the manifest says restarts from zero`() {
        val partial = folder.newFile("shared.partial")
        partial.writeBytes(ByteArray(2_000))
        assertEquals(0L, ResumePlan.of(partial, 1_000).offset)
    }

    /** 120 MB over a weak connection is the case this exists for. */
    @Test
    fun `a large interrupted download resumes rather than restarting`() {
        val partial = folder.newFile("shared.partial")
        partial.writeBytes(ByteArray(64 * 1024))
        val plan = ResumePlan.of(partial, 125_829_120)

        assertEquals("bytes=65536-", plan.rangeHeader())
        assertEquals(125_763_584L, plan.remainingBytes)
    }
}

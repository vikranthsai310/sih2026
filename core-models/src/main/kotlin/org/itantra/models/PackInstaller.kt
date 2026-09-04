package org.itantra.models

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs a model pack so that **a partially written pack can never be loaded**.
 *
 * Tasks **W4.5** and **W4.7**, `docs/MODELS.md` section 4.
 *
 * ## The rule
 *
 * Download to a temporary file, verify the SHA-256 against the manifest, and only then
 * rename into place. Rename within a filesystem is atomic, so at every instant the
 * destination either does not exist or holds a fully verified pack. There is no window
 * in which a half-written model is loadable.
 *
 * This matters more here than in an ordinary application. A truncated ONNX model does
 * not fail cleanly — it may load and emit nonsense, and nonsense spoken aloud with
 * confidence is the worst output this system can produce.
 */
class PackInstaller(private val root: File) {
    sealed interface Result {
        data class Installed(val destination: File, val bytes: Long) : Result

        /** The bytes are discarded. A pack that fails its checksum is never kept. */
        data class ChecksumFailed(val expected: String, val actual: String) : Result

        data class WrongSize(val expected: Long, val actual: Long) : Result

        /** Task W4.7: a pack with no licence is not installable, not merely undocumented. */
        data class LicenceMissing(val what: String) : Result

        /**
         * The pack name is not a plain filename, so the path it produces could point
         * outside the pack directory. See [isSafeName].
         */
        data class UnsafeName(val name: String) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * @param name the pack directory name, for example `hi` or `shared`
     * @param expectedSha256 from the manifest, never from the download
     * @param expectedBytes from the manifest
     * @param licence the pack's declared licence; blank or null refuses the install
     * @param source opens the bytes; called once
     */
    fun install(
        name: String,
        expectedSha256: String,
        expectedBytes: Long,
        licence: String?,
        source: () -> InputStream,
    ): Result {
        // Before anything else. A pack name reaches here from a manifest, and a manifest
        // will eventually be fetched rather than shipped, at which point a name like
        // "../../databases/roster" would have this method write wherever it pleased.
        if (!isSafeName(name)) return Result.UnsafeName(name)

        // Checked before a single byte is written: an unlicensed pack must not even
        // occupy disk, and discovering it after a 120 MB download is too late.
        if (licence.isNullOrBlank()) return Result.LicenceMissing(name)

        val destination = File(root, name)
        val temp = File(root, "$name$TEMP_SUFFIX")

        return try {
            root.mkdirs()
            temp.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            source().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read
                    }
                    // The rename is only atomic with respect to what actually reached
                    // the disk, so the bytes are flushed before it happens.
                    output.flush()
                }
            }

            if (written != expectedBytes) {
                temp.delete()
                return Result.WrongSize(expectedBytes, written)
            }

            val actual = digest.digest().toHex()
            if (!actual.equalsConstantTime(expectedSha256)) {
                temp.delete()
                return Result.ChecksumFailed(expectedSha256, actual)
            }

            // Only now does anything appear at the destination.
            destination.delete()
            if (!temp.renameTo(destination)) {
                temp.delete()
                return Result.Failed("could not move $temp into place")
            }
            Result.Installed(destination, written)
        } catch (e: Exception) {
            temp.delete()
            Result.Failed(e.message ?: e::class.simpleName ?: "unknown failure")
        }
    }

    /** True if a fully installed pack of this name is present. */
    fun isInstalled(name: String): Boolean = File(root, name).exists()

    /**
     * Removes any temporary file left by an interrupted install.
     *
     * Called at service start. Without it a crash mid-download leaks the partial file
     * indefinitely, and on a handset chosen for being cheap, storage is scarce.
     */
    fun sweepPartials(): Int {
        val partials = root.listFiles { f -> f.name.endsWith(TEMP_SUFFIX) } ?: return 0
        return partials.count { it.delete() }
    }

    companion object {
        const val TEMP_SUFFIX = ".partial"
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * A pack name must be a plain filename: letters, digits, hyphen and underscore.
         *
         * Language codes and `shared` all satisfy this comfortably, so the rule costs
         * nothing legitimate. It refuses a path separator, a parent reference, a leading
         * dot and an absolute path — each of which would let a name chosen elsewhere
         * decide where this class writes.
         */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= 64 &&
                name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"

        /**
         * Compares without returning early on the first differing character.
         *
         * A timing side channel on a model checksum is not a realistic attack, but
         * constant-time comparison of digests is cheap and the habit is worth keeping
         * where hashes are compared at all.
         */
        internal fun String.equalsConstantTime(other: String): Boolean {
            if (length != other.length) return false
            var difference = 0
            for (i in indices) difference = difference or (this[i].code xor other[i].code)
            return difference == 0
        }
    }
}

/**
 * Where a resumed download should restart from.
 *
 * The shared acoustic model is ~120 MB. On the connection a relief worker actually has,
 * that download will be interrupted, and restarting from zero each time means it never
 * completes. `docs/MODELS.md` section 4.
 */
data class ResumePlan(val offset: Long, val totalBytes: Long) {
    val isComplete: Boolean get() = offset >= totalBytes
    val remainingBytes: Long get() = (totalBytes - offset).coerceAtLeast(0)

    /** The HTTP range header for the remaining bytes, or null when nothing remains. */
    fun rangeHeader(): String? = if (isComplete) null else "bytes=$offset-"

    companion object {
        /**
         * @param partial the `.partial` file, which may not exist
         *
         * A partial larger than the expected total means the manifest and the file
         * disagree; the file is the one that must go, because the manifest is signed
         * evidence and the partial is not.
         */
        fun of(
            partial: File,
            totalBytes: Long,
        ): ResumePlan {
            val onDisk = if (partial.isFile) partial.length() else 0
            val offset = if (onDisk > totalBytes) 0 else onDisk
            return ResumePlan(offset, totalBytes)
        }
    }
}

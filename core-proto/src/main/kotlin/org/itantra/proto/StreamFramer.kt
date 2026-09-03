package org.itantra.proto

/**
 * Recovers whole frames from a byte stream.
 *
 * RFCOMM and TCP preserve byte order but **not** message boundaries. Writing 44 bytes
 * and then 38 may be read as 20 and then 62. Every frame therefore carries an
 * explicit `LEN`, and the reader accumulates until the declared number of bytes has
 * arrived.
 *
 * Omitting this produces a system that works on a desk and fails under load. It is
 * the single most common defect in implementations of this class of project — risk
 * **T-08** — which is why it is a class with its own tests rather than a loop inside
 * the transport.
 *
 * **Resynchronisation.** On a bad CRC or an implausible `LEN` the reader scans forward
 * byte by byte for the next `0xA1` sentinel and parses from there. It never closes the
 * socket: a noisy link produces bad frames routinely, and closing turns a recoverable
 * glitch into an outage. See `docs/PROTOCOL.md` section 13.
 *
 * Not thread-safe; it belongs to the link thread.
 */
class StreamFramer(
    private val maxBuffered: Int = 8 * 1024,
) {
    private var buffer = ByteArray(maxBuffered)
    private var length = 0

    /** Frames discarded after a failed parse. A rising count means a noisy link. */
    var resyncCount: Long = 0L
        private set

    /** Bytes thrown away while scanning for a sentinel. */
    var discardedBytes: Long = 0L
        private set

    /** Bytes currently held awaiting a complete frame. */
    val buffered: Int get() = length

    /**
     * Feeds freshly read bytes and returns every complete frame now available.
     *
     * Never throws, whatever the input: malformed data is discarded and the reader
     * resynchronises.
     */
    fun offer(
        chunk: ByteArray,
        offset: Int = 0,
        count: Int = chunk.size - offset,
    ): List<Frame> {
        append(chunk, offset, count)

        val out = ArrayList<Frame>()
        while (true) {
            val frame = tryExtract() ?: break
            out.add(frame)
        }
        return out
    }

    fun reset() {
        length = 0
    }

    private fun append(
        chunk: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return
        // A stream that never yields a valid frame must not grow without bound.
        if (length + count > buffer.size) {
            val keep = minOf(length, buffer.size / 2)
            System.arraycopy(buffer, length - keep, buffer, 0, keep)
            discardedBytes += (length - keep).toLong()
            length = keep
        }
        val room = minOf(count, buffer.size - length)
        System.arraycopy(chunk, offset + count - room, buffer, length, room)
        length += room
    }

    /** @return the next complete frame, or null if more bytes are needed. */
    private fun tryExtract(): Frame? {
        while (true) {
            if (length < Frame.MIN_WIRE_SIZE) return null

            if (buffer[0] != Frame.SENTINEL) {
                if (!seekSentinel()) return null
                continue
            }

            val payloadLength = Frame.peekPayloadLength(buffer, 0) ?: return null
            if (payloadLength > Frame.MAX_PAYLOAD) {
                dropOneByteAndResync()
                continue
            }

            val wireSize = Frame.HEADER_SIZE + payloadLength + Frame.CRC_SIZE
            if (length < wireSize) return null // wait for the rest

            return when (val result = Frame.decode(buffer, 0, wireSize)) {
                is DecodeResult.Ok -> {
                    consume(wireSize)
                    result.frame
                }

                is DecodeResult.Rejected -> {
                    dropOneByteAndResync()
                    continue
                }
            }
        }
    }

    /** Scans to the next sentinel. @return false if none is buffered. */
    private fun seekSentinel(): Boolean {
        var i = 1
        while (i < length && buffer[i] != Frame.SENTINEL) i++
        if (i >= length) {
            discardedBytes += length.toLong()
            length = 0
            return false
        }
        discardedBytes += i.toLong()
        consume(i)
        return true
    }

    private fun dropOneByteAndResync() {
        resyncCount++
        discardedBytes++
        consume(1)
    }

    private fun consume(count: Int) {
        System.arraycopy(buffer, count, buffer, 0, length - count)
        length -= count
    }
}

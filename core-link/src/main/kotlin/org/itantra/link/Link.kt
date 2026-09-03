package org.itantra.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One interface, three physical media. The application never learns which one is
 * carrying its bytes.
 *
 * This abstraction is what lets the whole transport layer be built and proven before
 * any model exists — a text field stands in for the recogniser and the entire path is
 * exercised. That is not a convenience; it is why the schedule survives.
 *
 * See `docs/TRANSPORT.md` section 1.
 *
 * ## Contract
 *
 * - [send] MUST be safe to call from any thread and MUST NOT block the caller longer
 *   than it takes to enqueue.
 * - [incoming] MUST emit exactly one **complete** frame per emission. De-framing is
 *   the implementation's job, never the consumer's — see [org.itantra.proto.StreamFramer].
 * - [state] MUST reach [LinkState.DEGRADED] rather than [LinkState.ERROR] for any
 *   condition the implementation can recover from by itself.
 * - An implementation MUST NOT interpret the payload. It sees bytes.
 */
interface Link {
    val name: String

    suspend fun send(frame: ByteArray)

    val incoming: Flow<ByteArray>

    val state: StateFlow<LinkState>

    /** Largest payload a single packet can carry, before framing. */
    val mtu: Int

    val metrics: StateFlow<LinkMetrics>

    suspend fun connect()

    suspend fun disconnect()
}

enum class LinkState {
    IDLE,
    DISCOVERING,
    CONNECTED,

    /** Recoverable without user action; the service keeps trying. */
    DEGRADED,

    /** Not recoverable by the implementation. */
    ERROR,
}

/**
 * What the interface shows about the link, and what feeds `latency.csv`.
 *
 * [queueDepth] is the first symptom of a saturated link — a rising value means the
 * consumer is falling behind before anything is visibly wrong.
 */
data class LinkMetrics(
    val rssi: Int? = null,
    val roundTripMillis: Long? = null,
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val framesLost: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val queueDepth: Int = 0,
) {
    /** Drives the on-screen byte counter the demonstration points at. */
    val compressionVersusRawAudio: Double
        get() = if (bytesSent == 0L) 0.0 else RAW_AUDIO_BYTES_PER_SENTENCE / bytesSent.toDouble()

    private companion object {
        /** Three seconds of 16 kHz 16-bit mono, the sentence the documents use throughout. */
        const val RAW_AUDIO_BYTES_PER_SENTENCE = 96_000.0
    }
}

/**
 * Reconnection delay: exponential with jitter, one second to thirty.
 *
 * The jitter matters more than it looks. Without it, several units that lost the same
 * link retry in lockstep and collide repeatedly; with it they spread out. Reset on any
 * successful frame.
 *
 * `docs/TRANSPORT.md` section 8.
 */
class Backoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val multiplier: Double = 2.0,
    private val jitterFraction: Double = 0.25,
    private val random: (Double) -> Double = { it * Math.random() },
) {
    var attempt: Int = 0
        private set

    /** @return the delay to wait before the next attempt, jitter included. */
    fun nextDelayMillis(): Long {
        val base = initialMillis * Math.pow(multiplier, attempt.toDouble())
        val capped = minOf(base, maxMillis.toDouble())
        attempt++
        val jitter = random(capped * jitterFraction)
        return (capped - capped * jitterFraction / 2 + jitter).toLong().coerceAtLeast(0)
    }

    /** Delay that would be used next, without jitter or advancing the attempt. */
    fun peekBaseMillis(): Long = minOf(initialMillis * Math.pow(multiplier, attempt.toDouble()), maxMillis.toDouble()).toLong()

    /** Called on any successful frame. */
    fun reset() {
        attempt = 0
    }
}

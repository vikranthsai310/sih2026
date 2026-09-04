package org.itantra.link

import org.itantra.proto.PttControl
import kotlin.random.Random

/**
 * Decides who may transmit on a half-duplex channel. Tasks **W5.1**, **W5.3**, **W5.8**;
 * risk **S-05**.
 *
 * ## What this can and cannot do
 *
 * There is no central arbiter. A `PTT_CTL` seize is an announcement broadcast to
 * everyone in range, not a request granted by anyone, and it takes 20–60 ms to arrive.
 * Two operators who press within that window will both believe the floor is theirs.
 * **Floor control therefore cannot prevent collisions; it can only detect and resolve
 * them quickly.** Claiming otherwise would be the kind of assertion that collapses under
 * one question from a jury.
 *
 * Resolution has to be **deterministic**, not merely randomised. If both units simply
 * back off by a random interval, they can both back off — losing the message entirely —
 * or both retry into a second collision. Instead the unit with the lower `SRC` wins
 * outright and keeps transmitting, and only the loser yields. Both ends compute the same
 * answer from the same two numbers without exchanging anything further. Randomised
 * backoff is still applied on top, but only to the loser's *retry*, which is what stops
 * a repeated collision between the same pair.
 *
 * ## The refusal is haptic, never a dialog
 *
 * A press while a peer holds the floor produces [Reaction.Refused], which the interface
 * renders as a buzz. Meena is wearing gloves at altitude with the screen dark; a modal
 * dialog is unusable and would have to be dismissed before she could try again.
 */
class FloorControl(
    private val localSrc: Int,
    private val staleHoldMillis: Long = STALE_HOLD_MILLIS,
    private val random: Random = Random.Default,
) {
    enum class State {
        FREE,

        /** This unit is transmitting. */
        HELD_BY_ME,

        /** A peer announced a seize and has not released. */
        HELD_BY_PEER,

        /** Both announced within the propagation window; being resolved. */
        CONTENDED,
    }

    /** What the caller should do about an event. */
    sealed interface Reaction {
        /** Send this frame. */
        data class Send(val control: PttControl) : Reaction

        /**
         * The press is refused. The interface buzzes; it does not open a dialog.
         * [busyWithSrc] identifies who holds the floor so the display can name them.
         */
        data class Refused(val busyWithSrc: Int?) : Reaction

        /**
         * This unit lost a contention and must stop transmitting immediately, then
         * retry after [retryAfterMillis].
         */
        data class YieldAndRetry(val retryAfterMillis: Long) : Reaction

        /** This unit won the contention and keeps the floor. */
        data object KeepFloor : Reaction

        data object Nothing : Reaction
    }

    var state: State = State.FREE
        private set

    /** Who holds the floor, when it is not this unit. */
    var holderSrc: Int? = null
        private set

    private var heldSinceMillis: Long = 0

    /** True whenever the channel-busy indicator should be lit. */
    val isBusy: Boolean get() = state != State.FREE

    /** True while this unit may put audio on the channel. */
    val mayTransmit: Boolean get() = state == State.HELD_BY_ME

    // ── local events ─────────────────────────────────────────────────────────

    /** The operator pressed the transmit key. */
    fun onLocalPress(nowMillis: Long): Reaction {
        expireStaleHold(nowMillis)
        return when (state) {
            State.FREE -> {
                state = State.HELD_BY_ME
                holderSrc = localSrc
                heldSinceMillis = nowMillis
                Reaction.Send(PttControl.SEIZE)
            }
            // Pressing again while already transmitting is not an error, just a no-op.
            State.HELD_BY_ME -> Reaction.Nothing
            State.HELD_BY_PEER, State.CONTENDED -> Reaction.Refused(holderSrc)
        }
    }

    /** The operator released the transmit key. */
    fun onLocalRelease(nowMillis: Long): Reaction =
        if (state == State.HELD_BY_ME || state == State.CONTENDED) {
            state = State.FREE
            holderSrc = null
            Reaction.Send(PttControl.RELEASE)
        } else {
            Reaction.Nothing
        }

    // ── remote events ────────────────────────────────────────────────────────

    /** A `PTT_CTL` frame arrived from [src]. */
    fun onPeerControl(
        control: PttControl,
        src: Int,
        nowMillis: Long,
    ): Reaction {
        expireStaleHold(nowMillis)
        return when (control) {
            PttControl.SEIZE -> onPeerSeize(src, nowMillis)
            PttControl.RELEASE -> onPeerRelease(src)
        }
    }

    private fun onPeerSeize(
        src: Int,
        nowMillis: Long,
    ): Reaction {
        // A unit re-announcing its own hold changes nothing.
        if (src == holderSrc && state == State.HELD_BY_PEER) return Reaction.Nothing

        return when (state) {
            State.FREE, State.HELD_BY_PEER -> {
                state = State.HELD_BY_PEER
                holderSrc = src
                heldSinceMillis = nowMillis
                Reaction.Nothing
            }
            State.HELD_BY_ME, State.CONTENDED -> resolveContention(src, nowMillis)
        }
    }

    /**
     * Both units seized inside the propagation window. The lower `SRC` wins, so both
     * ends reach the same conclusion from the same two numbers with no further exchange.
     */
    private fun resolveContention(
        peerSrc: Int,
        nowMillis: Long,
    ): Reaction =
        if (localSrc < peerSrc) {
            // We win. Stay on the floor; the peer will yield on its own.
            state = State.HELD_BY_ME
            holderSrc = localSrc
            Reaction.KeepFloor
        } else {
            state = State.HELD_BY_PEER
            holderSrc = peerSrc
            heldSinceMillis = nowMillis
            Reaction.YieldAndRetry(backoffMillis())
        }

    private fun onPeerRelease(src: Int): Reaction {
        // A release from a unit that does not hold the floor is ignored rather than
        // trusted: otherwise a stale frame from a third unit could free a live floor.
        if (state == State.HELD_BY_PEER && src == holderSrc) {
            state = State.FREE
            holderSrc = null
        }
        return Reaction.Nothing
    }

    // ── time ─────────────────────────────────────────────────────────────────

    /**
     * Call periodically. Releases a peer's hold that has lasted implausibly long.
     *
     * Without this the channel deadlocks permanently the first time a unit walks out of
     * range mid-transmission, or its battery dies with the key held — and the failure is
     * silent, because every remaining unit believes someone else is talking.
     */
    fun onTick(nowMillis: Long): Reaction {
        expireStaleHold(nowMillis)
        return Reaction.Nothing
    }

    private fun expireStaleHold(nowMillis: Long) {
        if (state != State.HELD_BY_PEER) return
        if (nowMillis - heldSinceMillis >= staleHoldMillis) {
            state = State.FREE
            holderSrc = null
        }
    }

    /**
     * Randomised, so the same pair colliding twice does not collide identically a third
     * time. Only the loser of a contention waits.
     */
    private fun backoffMillis(): Long = BACKOFF_MIN_MILLIS + random.nextLong(BACKOFF_JITTER_MILLIS)

    fun reset() {
        state = State.FREE
        holderSrc = null
        heldSinceMillis = 0
    }

    companion object {
        /**
         * Longer than the 8 s maximum utterance plus transport slack, so a legitimate
         * long transmission is never cut off, but short enough that a dead peer does not
         * hold the channel for an operationally meaningful time.
         */
        const val STALE_HOLD_MILLIS = 12_000L

        const val BACKOFF_MIN_MILLIS = 120L
        const val BACKOFF_JITTER_MILLIS = 180L
    }
}

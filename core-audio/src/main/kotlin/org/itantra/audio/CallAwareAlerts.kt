package org.itantra.audio

/**
 * What happens to an alert that arrives while the operator is on a telephone call. Task
 * **W5.25**.
 *
 * ## The decision, and why it is the opposite of every other alert rule
 *
 * Everywhere else in this system an alert overrides. It wakes a locked handset, it ignores
 * audio focus, it plays at maximum alarm volume through Do Not Disturb, and a refusal from
 * the platform is ignored rather than obeyed. That is what "non-interruptible" means and
 * `docs/UX.md` is unambiguous about it.
 *
 * A telephone call is the exception, and there are two reasons rather than one.
 *
 * **It would not work.** During a call the platform routes audio to the earpiece and gives
 * the telephony stack priority; an alarm-stream announcement is attenuated, ducked, or
 * silently dropped depending on the vendor. Announcing into a call is not a louder alert,
 * it is an alert nobody hears — and the sender is told it was delivered.
 *
 * **It would be worse if it did.** The person on the call is very likely coordinating the
 * same incident. A full-volume siren into their earpiece takes out the more important
 * channel to deliver the less important one, and it does so at the exact moment both
 * matter most.
 *
 * So the alert is **held**, and announced in full the moment the call ends. Nothing is
 * dropped and nothing is delivered quietly.
 *
 * ## What the operator is told, and when
 *
 * The alert is not silent while it waits. The handset **vibrates** on arrival, because
 * vibration reaches someone holding a phone to their ear and does not enter the call
 * audio. That is the whole notification during the call: a person who feels it can end the
 * call, and a person who does not loses nothing because the announcement is still coming.
 *
 * ## Which alerts survive the wait
 *
 * All of them, in arrival order, up to [CAPACITY]. A queue is not a filter — deciding that
 * the third alert of a call was less important than the first is a judgement this code has
 * no basis for making. What it does bound is memory, and when the bound is reached it
 * drops the **oldest**, on the same reasoning as [org.itantra.link.Outbox]: the newest
 * alert is the one most likely to still describe the situation.
 *
 * ## What this class does not do
 *
 * It does not detect the call. `TelephonyManager.callStateChanged` is a platform callback
 * and belongs in the app module; this holds the policy so the policy can be tested without
 * a handset, a SIM and somebody willing to be rung up.
 */
class CallAwareAlerts(
    private val playback: AlertPlayback,
    private val capacity: Int = CAPACITY,
) {
    /** One alert waiting for the call to end. */
    data class Held(
        val pcm: ShortArray,
        val from: String,
        val arrivedAtMillis: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Held &&
                from == other.from &&
                arrivedAtMillis == other.arrivedAtMillis &&
                pcm.contentEquals(other.pcm)

        override fun hashCode(): Int = 31 * (31 * pcm.contentHashCode() + from.hashCode()) + arrivedAtMillis.hashCode()
    }

    /** What was done with an alert that arrived. */
    sealed interface Disposition {
        /** Announced immediately, as normal. */
        data class Announced(val outcome: AlertPlayback.Outcome) : Disposition

        /**
         * Held until the call ends.
         *
         * @param vibrated whether the arrival was signalled. False only if the platform
         *   refused, which is recorded rather than assumed away
         */
        data class Held(val queued: Int, val vibrated: Boolean) : Disposition

        /** Held, and an older one was dropped to make room. */
        data class HeldAndDropped(val queued: Int, val droppedFrom: String) : Disposition
    }

    private val waiting = ArrayDeque<Held>()

    var onCall: Boolean = false
        private set

    val queuedCount: Int get() = waiting.size

    /** Alerts dropped because more arrived during one call than the queue could hold. */
    var droppedOldest: Int = 0
        private set

    /** Called when the platform reports a call starting. */
    fun onCallStarted() {
        onCall = true
    }

    /**
     * An alert arrived.
     *
     * @return what was done with it, so the interface can say so rather than the operator
     *   inferring it from silence
     */
    fun onAlert(
        pcm: ShortArray,
        from: String,
        nowMillis: Long,
    ): Disposition {
        if (!onCall) return Disposition.Announced(playback.announce(pcm))

        var droppedFrom: String? = null
        while (waiting.size >= capacity) {
            droppedFrom = waiting.removeFirst().from
            droppedOldest++
        }
        waiting.addLast(Held(pcm.copyOf(), from, nowMillis))

        // Vibration reaches a person holding the phone to their ear and does not enter the
        // call audio. A refusal is recorded rather than treated as success.
        val vibrated =
            runCatching { playback.vibrateOnly(AlertPlayback.VIBRATION_PATTERN) }.isSuccess

        return droppedFrom?.let { Disposition.HeldAndDropped(waiting.size, it) }
            ?: Disposition.Held(waiting.size, vibrated)
    }

    /**
     * The call ended. Everything held is announced, in the order it arrived.
     *
     * In order because two alerts about the same incident read as a different situation
     * reversed, and every one of them because a queue that quietly discarded some would
     * leave the sender believing a message was delivered that never was.
     *
     * @return the outcome of each announcement, in the order they were played
     */
    fun onCallEnded(): List<AlertPlayback.Outcome> {
        onCall = false
        val outcomes = ArrayList<AlertPlayback.Outcome>(waiting.size)
        while (waiting.isNotEmpty()) {
            outcomes += playback.announce(waiting.removeFirst().pcm)
        }
        return outcomes
    }

    /** What is waiting, for an interface that shows it during the call. */
    fun peek(): List<Held> = waiting.toList()

    companion object {
        /**
         * Alerts held during one call.
         *
         * Eight is far more than a call is likely to span, and the number exists to bound
         * memory rather than to be reached. A call long enough to accumulate nine alerts
         * is a situation where the operator should have ended the call several alerts ago.
         */
        const val CAPACITY = 8
    }
}

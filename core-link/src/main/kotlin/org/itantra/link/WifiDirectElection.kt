package org.itantra.link

/**
 * Who owns the Wi-Fi Direct group, decided by nobody in particular.
 *
 * ## The problem
 *
 * A Wi-Fi Direct group has exactly one owner, and every other unit joins it. Somebody has
 * to be the owner, every unit has to agree on who, and there is no channel to agree on
 * before the group exists: the only thing a unit can see is the list of peers its radio
 * has found, and whether each of them already owns a group. A unit cannot even compare its
 * own address with theirs -- Android reports a handset's own P2P address as
 * `02:00:00:00:00:00` to applications -- so the classic "lowest address wins" is not
 * available.
 *
 * ## The rule
 *
 * 1. **Join any owner you can see.** An owner is a settled fact; joining it is always
 *    right, and the sooner the better.
 * 2. **If you see none for a while, become one.** The while is random, five to twenty
 *    seconds, so two units switched on together do not both become owners in the same
 *    instant. The one whose wait ends first creates the group; the other sees it on its
 *    next peer report and joins.
 * 3. **An owner with no clients that sees another owner steps down**, again after a random
 *    wait, and goes back to rule 1. Two units that did become owners together -- their
 *    waits ended within a few seconds of each other -- resolve this way: one steps down
 *    first, finds the other still owning, and joins it. An owner *with* clients never steps
 *    down, so a group with people in it is never torn up to merge with an empty one.
 *
 * Two groups that both have clients stay two groups. That happens when two crews formed
 * their groups out of range of each other and then met, and the honest answer is that
 * this class does not merge them: the Bluetooth road carries traffic between the two
 * meanwhile, and switching the Wi-Fi road off and on in the settings resets both.
 *
 * ## Why this is a separate class
 *
 * Everything above is a decision, and none of it needs a handset. [WifiDirectGroup] owns
 * the Android plumbing -- `WifiP2pManager`, the broadcasts, the permission -- and asks this
 * class what to do next on every peer report and every tick. That is why this is plain
 * Kotlin with a clock passed in: `WifiDirectElectionTest` walks two and three units
 * through the rules on the JVM, and a change to the rules is a test that fails on the
 * desk rather than a demonstration that fails on stage.
 *
 * Not thread-safe by itself; [WifiDirectGroup] serialises its calls.
 */
class WifiDirectElection(
    private val random: () -> Double = { Math.random() },
) {
    /** A unit the radio can see: its P2P address and whether it already owns a group. */
    data class Peer(
        val address: String,
        val isOwner: Boolean,
    )

    sealed interface Phase {
        /** Looking for an owner; becoming one at [deadline] if none appears. */
        data class Searching(
            val since: Long,
            val deadline: Long,
        ) : Phase

        /** `connect()` has been issued to [owner]; waiting for the group to form. */
        data class Joining(
            val owner: String,
            val since: Long,
        ) : Phase

        /** This unit owns the group. */
        data class Owner(
            val since: Long,
            val clients: Int,
            /** When this unit will step down for a rival owner, if it has decided to. */
            val stepDownAt: Long? = null,
        ) : Phase

        /** This unit is a client of [owner]'s group. */
        data class Client(
            val owner: String,
        ) : Phase
    }

    /** What the radio should be told to do now. */
    sealed interface Action {
        /** Start, or restart, peer discovery. */
        data object Discover : Action

        /** Join [owner]'s group. */
        data class Join(
            val owner: String,
        ) : Action

        /** Create a group with this unit as owner. */
        data object CreateGroup : Action

        /** Leave the group this unit owns. */
        data object RemoveGroup : Action

        data object None : Action
    }

    var phase: Phase = Phase.Searching(since = 0, deadline = 0)
        private set

    /** Owners a join to has failed or timed out, and until when they are left alone. */
    private val shunned = HashMap<String, Long>()

    /** When discovery was last asked for, or null if never since the last reset. */
    private var lastDiscover: Long? = null

    /** Starts, or restarts, the search from [now]. */
    fun reset(now: Long) {
        phase = Phase.Searching(since = now, deadline = now + searchWait())
        shunned.clear()
        lastDiscover = null
    }

    /**
     * The decision for this moment, given the peers the radio can see.
     *
     * Called on every peer report and on every tick, so a rule that depends on time
     * passing -- a deadline, a join that never completed -- fires even when no peer
     * report arrives.
     */
    fun advance(
        now: Long,
        peers: List<Peer>,
    ): Action {
        shunned.values.removeAll { it <= now }
        return when (val current = phase) {
            is Phase.Searching -> search(now, current, peers)
            is Phase.Joining -> {
                if (now - current.since >= JOIN_TIMEOUT_MILLIS) {
                    shunned[current.owner] = now + SHUN_MILLIS
                    phase = Phase.Searching(since = now, deadline = now + searchWait())
                    discover(now)
                } else {
                    Action.None
                }
            }
            is Phase.Owner -> own(now, current, peers)
            is Phase.Client -> Action.None
        }
    }

    private fun search(
        now: Long,
        current: Phase.Searching,
        peers: List<Peer>,
    ): Action {
        // Rule 1. Sorted so that every unit seeing the same two owners picks the same one.
        val owner = peers.filter { it.isOwner && it.address !in shunned }.minByOrNull { it.address }
        if (owner != null) {
            phase = Phase.Joining(owner.address, since = now)
            return Action.Join(owner.address)
        }
        // Rule 2. The deadline moves on so the request is made once, not on every tick
        // while the group is forming; a failure moves it again, see [createFailed].
        if (now >= current.deadline) {
            phase = current.copy(deadline = now + CREATE_WAIT_MILLIS)
            return Action.CreateGroup
        }
        if (due(now, DISCOVER_EVERY_MILLIS)) return discover(now)
        return Action.None
    }

    private fun own(
        now: Long,
        current: Phase.Owner,
        peers: List<Peer>,
    ): Action {
        val rival = peers.any { it.isOwner }
        // Rule 3.
        if (rival && current.clients == 0) {
            val at = current.stepDownAt ?: (now + stepDownWait()).also { phase = current.copy(stepDownAt = it) }
            if (now >= at) {
                phase = Phase.Searching(since = now, deadline = now + searchWait())
                return Action.RemoveGroup
            }
        } else if (current.stepDownAt != null) {
            phase = current.copy(stepDownAt = null)
        }
        // Sparser than while searching: a find on an owner disturbs the traffic it carries,
        // and all it is looking for is a rival.
        if (due(now, OWNER_DISCOVER_EVERY_MILLIS)) return discover(now)
        return Action.None
    }

    private fun due(
        now: Long,
        every: Long,
    ): Boolean = lastDiscover?.let { now - it >= every } ?: true

    private fun discover(now: Long): Action {
        lastDiscover = now
        return Action.Discover
    }

    /** The radio reports a group: this unit owns it, or is a client of [owner]. */
    fun formed(
        now: Long,
        isOwner: Boolean,
        owner: String?,
        clients: Int,
    ) {
        val current = phase
        phase =
            if (isOwner) {
                if (current is Phase.Owner) {
                    current.copy(clients = clients)
                } else {
                    Phase.Owner(since = now, clients = clients)
                }
            } else {
                val address = owner ?: (current as? Phase.Joining)?.owner ?: "?"
                shunned.remove(address)
                Phase.Client(address)
            }
    }

    /** The radio reports no group: it was removed, or this unit lost it. */
    fun lost(now: Long) {
        if (phase !is Phase.Searching) {
            phase = Phase.Searching(since = now, deadline = now + searchWait())
            lastDiscover = null
        }
    }

    /** `connect()` was refused. The owner is left alone for a while and the search resumes. */
    fun joinFailed(now: Long) {
        val current = phase as? Phase.Joining ?: return
        shunned[current.owner] = now + SHUN_MILLIS
        phase = Phase.Searching(since = now, deadline = now + searchWait())
        lastDiscover = null
    }

    /** `createGroup()` was refused -- the radio was busy, usually. Tried again later. */
    fun createFailed(now: Long) {
        val current = phase as? Phase.Searching ?: return
        phase = current.copy(deadline = now + searchWait())
    }

    /** The radio stopped discovering on its own; the next [advance] restarts it. */
    fun discoveryStopped() {
        lastDiscover = null
    }

    private fun searchWait(): Long = SEARCH_MIN_MILLIS + (random() * (SEARCH_MAX_MILLIS - SEARCH_MIN_MILLIS)).toLong()

    private fun stepDownWait(): Long =
        STEP_DOWN_MIN_MILLIS + (random() * (STEP_DOWN_MAX_MILLIS - STEP_DOWN_MIN_MILLIS)).toLong()

    companion object {
        /** How long a unit looks for an owner before becoming one: random in this range. */
        const val SEARCH_MIN_MILLIS = 5_000L
        const val SEARCH_MAX_MILLIS = 20_000L

        /** How long an owner with no clients tolerates a rival before stepping down. */
        const val STEP_DOWN_MIN_MILLIS = 5_000L
        const val STEP_DOWN_MAX_MILLIS = 15_000L

        /**
         * How long a join may take. The first join to an owner needs a person to accept
         * the platform's invitation on the owner's screen, and the platform gives them
         * about thirty seconds; this is that, and a little.
         */
        const val JOIN_TIMEOUT_MILLIS = 40_000L

        /** How long a refused or timed-out owner is left alone before being tried again. */
        const val SHUN_MILLIS = 30_000L

        /** How long to wait for a created group to be reported before asking again. */
        const val CREATE_WAIT_MILLIS = 10_000L

        /**
         * Peer discovery is re-issued this often. The platform stops it by itself after
         * about two minutes, and some vendor builds long before that.
         */
        const val DISCOVER_EVERY_MILLIS = 15_000L
        const val OWNER_DISCOVER_EVERY_MILLIS = 30_000L
    }
}

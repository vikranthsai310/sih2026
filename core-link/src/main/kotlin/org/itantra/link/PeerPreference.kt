package org.itantra.link

/**
 * Which of two handsets dials, and which socket survives when both did.
 *
 * ## The problem this exists to solve
 *
 * RFCOMM needs exactly one side to accept and one to connect. Both dialling leaves nobody
 * listening; both listening leaves nobody dialling. Deciding it by negotiation needs a
 * channel, which is the thing being established — so the two units have to reach the same
 * answer from information they *already share*, without exchanging a byte.
 *
 * The first attempt at this compared node ids and did not work, and the reason is worth
 * recording because it is not visible from either side on its own. A unit's own id was
 * derived from `Settings.Secure.ANDROID_ID` and its peer's from that peer's Bluetooth MAC —
 * two different inputs. Handset A was comparing `hash(A's install id)` against
 * `hash(B's MAC)` while handset B compared `hash(B's install id)` against `hash(A's MAC)`.
 * Those two comparisons have no relationship to one another, so roughly half of all pairs
 * either both dialled or both listened, and simply never connected. Each handset's own logs
 * looked correct.
 *
 * ## What is actually symmetric
 *
 * The **Bluetooth name**. Handset A reads its own from `BluetoothAdapter.getName()` and B's
 * from `BluetoothDevice.getName()`; handset B reads exactly the same two strings the other
 * way round. It is the only identifier both ends can see for both ends, which is what makes
 * an agreed answer possible at all.
 *
 * ## When the names are no help
 *
 * Two handsets out of the box may genuinely share a name, and a name may be absent. Then
 * there is no shared ordering, and the policy inverts: **both** dial, both accept, and the
 * duplicate is resolved afterwards. A duplicated socket is a cost — one wasted write per
 * message — where a missing one is a dead net, so the tie is broken towards connecting.
 */
object PeerPreference {
    /**
     * Whether this unit should dial [theirName] rather than wait to be dialled.
     *
     * The greater name dials, which is arbitrary and only has to be consistent. When either
     * name is missing or the two are equal there is no ordering to be consistent about, so
     * both units dial and [keepsInbound] cleans up after them.
     */
    fun shouldDial(
        ourName: String?,
        theirName: String?,
    ): Boolean {
        if (!usable(ourName) || !usable(theirName)) return true
        // Two handsets sharing a name have no ordering between them, which is the same
        // situation as having no name at all: both dial, rather than neither.
        if (ourName == theirName) return true
        return ourName!! > theirName!!
    }

    /**
     * When both sockets exist, whether the one **they** dialled is the keeper.
     *
     * The complement of [shouldDial] evaluated on the same pair of strings, so the two
     * handsets choose the same socket: if B is the one that should have dialled, then A
     * keeps the inbound socket and B keeps its outbound one — the same connection, named
     * from either end.
     *
     * With no usable ordering this returns false, meaning whatever is already registered
     * stays. That is not agreement, it is only stability: without a shared ordering the
     * pair may keep two sockets, and the replay window makes the duplicate frame harmless.
     */
    fun keepsInbound(
        ourName: String?,
        theirName: String?,
    ): Boolean {
        if (!usable(ourName) || !usable(theirName)) return false
        return theirName!! > ourName!!
    }

    /**
     * Whether the two names give the pair an ordering at all.
     *
     * Without one — a name missing, or two handsets of the same model straight out of the
     * box — both units dial and both accept, and **both sockets are kept**. The first
     * version tried to pick one and the two ends picked differently: each kept the socket
     * it had dialled and closed the one it had accepted, which closed the other end's
     * keeper, and the pair reconnected and did it again, for ever. Two sockets cost one
     * duplicate frame per message, which the replay window discards; a pair that can never
     * hold a connection costs the net.
     */
    fun hasOrdering(
        ourName: String?,
        theirName: String?,
    ): Boolean = usable(ourName) && usable(theirName) && ourName != theirName

    /** A blank name is what the platform returns when it has not resolved one yet. */
    private fun usable(name: String?): Boolean = !name.isNullOrBlank()
}

package org.itantra.app.engine

import org.itantra.app.ui.UnitInfo
import org.itantra.link.Signal
import org.itantra.proto.Presence

/**
 * Who is on the channel, as far as this handset can tell.
 *
 * A broadcast channel has no roster of its own -- nothing is connected to anything -- so
 * this is built from evidence: an authenticated frame says a unit is there; a presence
 * frame says what it is called and, while it is being looked for, where it is; a signal
 * reading says how far. Each fact is kept with the time it was learned, and a unit that
 * has said nothing for [PRESENT_MILLIS] is no longer counted as present, which is what
 * makes the number on the operating screen mean "units here now".
 *
 * Thread-safe: frames arrive on the engine's scope and signal readings on the radios'.
 */
class Roster {
    class Unit(val src: Int) {
        var name: String? = null
        var heardAtMillis: Long = 0
        var rssi: Int? = null
        var rssiAtMillis: Long = 0
        var position: Presence.Position? = null
        var positionAtMillis: Long = 0
        var beaconing: Boolean = false
        var openLine: Boolean = false
    }

    private val units = HashMap<Int, Unit>()

    private fun unit(src: Int): Unit = units.getOrPut(src) { Unit(src) }

    /** An authenticated frame of any kind from [src]. */
    fun heard(
        src: Int,
        nowMillis: Long,
    ) = synchronized(units) { unit(src).heardAtMillis = nowMillis }

    fun presence(
        src: Int,
        presence: Presence,
        nowMillis: Long,
    ) = synchronized(units) {
        val u = unit(src)
        u.heardAtMillis = nowMillis
        if (presence.name.isNotBlank()) u.name = presence.name
        u.beaconing = presence.beaconing
        u.openLine = presence.openLine
        presence.position?.let {
            u.position = it
            u.positionAtMillis = nowMillis - it.ageSeconds * 1000L
        }
    }

    /**
     * A signal reading. Only for a unit already heard from: a reading is unverified, and
     * a unit that exists on the list only because a radio somewhere shares the service
     * UUID would be a unit nobody could ever reach.
     */
    fun signal(signal: Signal) =
        synchronized(units) {
            val u = units[signal.src] ?: return
            u.rssi = signal.rssi
            u.rssiAtMillis = signal.atMillis
        }

    fun nameOf(src: Int): String = synchronized(units) { units[src]?.name } ?: "node $src"

    fun positionOf(src: Int): Pair<Presence.Position, Long>? =
        synchronized(units) { units[src]?.let { u -> u.position?.let { it to u.positionAtMillis } } }

    fun isBeaconing(src: Int): Boolean = synchronized(units) { units[src]?.beaconing == true }

    /** Units heard inside [PRESENT_MILLIS], nearest first. */
    fun present(nowMillis: Long): List<UnitInfo> =
        synchronized(units) {
            units.values
                .filter { nowMillis - it.heardAtMillis <= PRESENT_MILLIS }
                .map { it.info(nowMillis) }
                .sortedWith(compareByDescending<UnitInfo> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.heardMillisAgo })
        }

    /** Every unit ever heard this run, present or not, for the locate list. */
    fun everyone(nowMillis: Long): List<UnitInfo> =
        synchronized(units) {
            units.values.map { it.info(nowMillis) }.sortedBy { it.heardMillisAgo }
        }

    fun presentCount(nowMillis: Long): Int =
        synchronized(units) { units.values.count { nowMillis - it.heardAtMillis <= PRESENT_MILLIS } }

    private fun Unit.info(nowMillis: Long) =
        UnitInfo(
            src = src,
            name = name ?: "node $src",
            heardMillisAgo = nowMillis - heardAtMillis,
            rssi = rssi?.takeIf { nowMillis - rssiAtMillis <= SIGNAL_FRESH_MILLIS },
            signalMillisAgo = rssi?.let { nowMillis - rssiAtMillis },
            hasPosition = position != null,
            openLine = openLine,
            beaconing = beaconing,
        )

    companion object {
        /**
         * Presence is sent every ten seconds; three missed in a row is a unit that has gone.
         * Half a minute is also what an operator means by "here now".
         */
        const val PRESENT_MILLIS = 35_000L

        /** A signal reading older than this describes where the unit was, not is. */
        const val SIGNAL_FRESH_MILLIS = 8_000L
    }
}

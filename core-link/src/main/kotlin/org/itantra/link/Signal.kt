package org.itantra.link

import kotlinx.coroutines.flow.Flow

/**
 * One reception of one unit's advertisement, and how strong it was.
 *
 * Not a frame: a frame is decoded and verified once, whereas the same advertisement is
 * heard again every hundred milliseconds for as long as it is on the air, and *every* one
 * of those is a fresh measurement of how far away the sender is. The locate screen's siren
 * and distance are made of these. The node id is read straight from the header byte,
 * unverified, because a signal-strength reading needs no more trust than that: the worst
 * a forger achieves is a siren that beeps at them.
 */
data class Signal(
    val src: Int,
    val keyId: Int,
    /** dBm, as the controller reported it. */
    val rssi: Int,
    /** `SystemClock.elapsedRealtime()` at reception. */
    val atMillis: Long,
    /**
     * The sender's transmit power in dBm, when its extended advertising header carried
     * it, or null. With it, a distance can be reckoned against the power that actually
     * left the sender's antenna rather than a figure assumed for every handset; two phones
     * of different make differ by ten decibels at the same range, which is the difference
     * between "one metre" and "three".
     */
    val txPower: Int? = null,
)

/** A link that can say how strongly it hears each sender. Only a radio can. */
interface SignalSource {
    val signals: Flow<Signal>
}

package org.itantra.link

/**
 * The three roads a frame can take, named once.
 *
 * A road is what the operator can switch on or off — see [MeshLink.roads]. Two of them
 * are a single broadcast peer each; the third is every bonded handset, one RFCOMM peer per
 * unit, which is why a road and a peer are different things and why the name of the road
 * has to be agreed between the class that adds RFCOMM peers ([BluetoothNet]) and the one
 * that reads the switch ([MeshLink]). Kept here so neither has to know about the other.
 */
object Road {
    /** Bluetooth LE advertising. No pairing; every unit with the application open. */
    const val BLE = "ble-broadcast"

    /** UDP broadcast on whatever Wi-Fi network the handsets share. */
    const val WIFI = "wifi-broadcast"

    /** Bluetooth Classic, one socket per bonded handset. */
    const val RFCOMM = "rfcomm"

    /** Every road, in the order the settings screen lists them. */
    val ALL: Set<String> = linkedSetOf(BLE, WIFI, RFCOMM)

    /**
     * A selection that is safe to apply: known names only, and never empty.
     *
     * A set naming a road that does not exist is trimmed; a set that would leave nothing
     * is replaced by everything. The mesh defends against an empty selection too, but a
     * preference that is stored empty and then displayed as "all roads off" is a screen
     * telling the operator something that is not happening.
     */
    fun sanitise(selected: Collection<String>): Set<String> {
        val known = ALL.filterTo(linkedSetOf()) { it in selected }
        return known.ifEmpty { ALL }
    }
}

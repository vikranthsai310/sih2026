package org.itantra.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The arithmetic behind the arrow, against figures worked by hand and by a map. */
class GeodesyTest {
    // Charminar and the Hussain Sagar Buddha, Hyderabad: 6.9 km apart, a hair west of north.
    private val charminarLat = 17.3616
    private val charminarLon = 78.4747
    private val buddhaLat = 17.4239
    private val buddhaLon = 78.4738

    @Test
    fun `distance across a city agrees with the map to a fraction of a percent`() {
        val d = Geodesy.distanceMetres(charminarLat, charminarLon, buddhaLat, buddhaLon)
        assertEquals(6_930.0, d, 60.0)
    }

    @Test
    fun `bearing across a city`() {
        val b = Geodesy.bearingDegrees(charminarLat, charminarLon, buddhaLat, buddhaLon)
        assertEquals(359.2f, b, 0.5f)
    }

    @Test
    fun `the four cardinal directions over fifty metres`() {
        val lat = 17.4
        val lon = 78.5
        val dLat = 50.0 / 111_195.0
        val dLon = 50.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(lat)))
        assertEquals(0f, Geodesy.bearingDegrees(lat, lon, lat + dLat, lon), 0.01f)
        assertEquals(90f, Geodesy.bearingDegrees(lat, lon, lat, lon + dLon), 0.05f)
        assertEquals(180f, Geodesy.bearingDegrees(lat, lon, lat - dLat, lon), 0.01f)
        assertEquals(270f, Geodesy.bearingDegrees(lat, lon, lat, lon - dLon), 0.05f)
        assertEquals(50.0, Geodesy.distanceMetres(lat, lon, lat + dLat, lon), 0.1)
        assertEquals(50.0, Geodesy.distanceMetres(lat, lon, lat, lon + dLon), 0.1)
    }

    @Test
    fun `the same point is zero metres away and the bearing is finite`() {
        assertEquals(0.0, Geodesy.distanceMetres(17.4, 78.5, 17.4, 78.5), 0.0)
        assertEquals(0f, Geodesy.bearingDegrees(17.4, 78.5, 17.4, 78.5), 0f)
    }
}

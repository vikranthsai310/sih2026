package org.itantra.app.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and bearing between two positions, on a sphere.
 *
 * The platform's `Location.distanceBetween` does the same on an ellipsoid, and it is
 * native, which means it returns zero on the JVM and the arithmetic behind the arrow could
 * never be tested. Over the few hundred metres a locate walk covers, the sphere and the
 * ellipsoid disagree by less than a part in three hundred on distance and by nothing that
 * matters on bearing, so the sphere is used everywhere and proved once here.
 */
object Geodesy {
    /** Mean Earth radius, metres. */
    const val EARTH_RADIUS_METRES = 6_371_008.8

    /** Great-circle distance in metres, by the haversine formula. */
    fun distanceMetres(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from the first position to the second, degrees clockwise from true north. */
    fun bearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val deg = Math.toDegrees(atan2(y, x)).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }
}

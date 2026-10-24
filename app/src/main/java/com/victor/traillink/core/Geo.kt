package com.victor.traillink.core

import kotlin.math.*

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine), metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial true bearing from point 1 to point 2, degrees 0..360 (0 = north). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val CARDINALS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun cardinal(deg: Double): String = CARDINALS[(((deg % 360 + 360) % 360 + 11.25) / 22.5).toInt() % 16]

    fun formatDistance(m: Double, metric: Boolean): String = if (metric) {
        if (m < 1000) "${m.roundToInt()} m" else if (m < 10_000) "%.2f km".format(m / 1000) else "%.1f km".format(m / 1000)
    } else {
        val ft = m * 3.28084
        val mi = m / 1609.344
        if (mi < 0.1) "${ft.roundToInt()} ft" else if (mi < 10) "%.2f mi".format(mi) else "%.1f mi".format(mi)
    }

    fun formatAltitude(m: Int?, metric: Boolean): String? = m?.let { if (metric) "$it m" else "${(it * 3.28084).roundToInt()} ft" }

    fun formatAge(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 0 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** Number of 256px web-mercator tiles covering a bbox across zoom levels (inclusive). */
    fun tileCount(south: Double, west: Double, north: Double, east: Double, minZ: Int, maxZ: Int): Long {
        var total = 0L
        for (z in minZ..maxZ) {
            val x1 = lonToTile(west, z); val x2 = lonToTile(east, z)
            val y1 = latToTile(north, z); val y2 = latToTile(south, z)
            total += (abs(x2 - x1) + 1).toLong() * (abs(y2 - y1) + 1).toLong()
        }
        return total
    }
    fun lonToTile(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun latToTile(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(r) + 1 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
}

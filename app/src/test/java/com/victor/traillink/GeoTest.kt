package com.victor.traillink

import com.victor.traillink.core.Geo
import org.junit.Assert.*
import org.junit.Test

class GeoTest {
    @Test fun distanceKnownPairs() {
        // Anchorage (Ted Stevens airport) to Kodiak airport: ~ 405 km great-circle
        val d = Geo.distanceM(61.1743, -149.9982, 57.7500, -152.4939)
        assertEquals(405_000.0, d, 3_000.0)
        // 1 degree of latitude ≈ 111.19 km
        assertEquals(111_195.0, Geo.distanceM(61.0, -149.0, 62.0, -149.0), 50.0)
        assertEquals(0.0, Geo.distanceM(61.1, -149.7, 61.1, -149.7), 1e-6)
    }

    @Test fun bearingCardinals() {
        assertEquals(0.0, Geo.bearingDeg(61.0, -149.0, 62.0, -149.0), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(62.0, -149.0, 61.0, -149.0), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(0.0, 1.0, 0.0, 0.0), 1e-6)
        // Glen Alps trailhead -> Flattop summit is roughly south-east
        val b = Geo.bearingDeg(61.1031, -149.6814, 61.0868, -149.6607)
        assertTrue("got $b", b in 130.0..150.0)
        assertTrue(Geo.cardinal(b) in setOf("SE", "SSE")); assertEquals("SE", Geo.cardinal(135.0)); assertEquals("N", Geo.cardinal(359.0)); assertEquals("NNE", Geo.cardinal(22.5))
    }

    @Test fun formatting() {
        assertEquals("328 ft", Geo.formatDistance(100.0, false))
        assertEquals("1.00 mi", Geo.formatDistance(1609.344, false))
        assertEquals("1.50 km", Geo.formatDistance(1500.0, true))
        assertEquals("3 min ago", Geo.formatAge(3 * 60_000L + 5_000))
    }

    @Test fun tileCounts() {
        assertEquals(1L, Geo.tileCount(-80.0, -179.0, 80.0, 179.0, 0, 0))
        assertEquals(4L, Geo.tileCount(-80.0, -179.0, 80.0, 179.0, 1, 1))
        val n = Geo.tileCount(61.05, -149.75, 61.15, -149.60, 10, 15)
        assertTrue("got $n", n in 100L..2000L)
    }
}
